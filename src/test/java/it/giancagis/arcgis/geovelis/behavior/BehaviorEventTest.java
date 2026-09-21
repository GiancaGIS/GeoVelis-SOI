package it.giancagis.arcgis.geovelis.behavior;

import com.esri.arcgis.system.IPropertySet;
import it.giancagis.arcgis.geovelis.rest.RestRequestContext;
import it.giancagis.arcgis.geovelis.risk.*;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.Assert.*;

public class BehaviorEventTest {
    static final String SECRET = "test-only-" + UUID.randomUUID();

    static IPropertySet properties(String... pairs) {
        Map<String,String> values = new HashMap<>();
        for (int i=0;i<pairs.length;i+=2) values.put(pairs[i],pairs[i+1]);
        return (IPropertySet) Proxy.newProxyInstance(IPropertySet.class.getClassLoader(),new Class<?>[]{IPropertySet.class},
                (p,m,args) -> values.get(args[0]));
    }
    static BehaviorConfig config(String service, String... extra) throws Exception {
        List<String> values = new ArrayList<>(Arrays.asList("riskRedisEndpoint","redis://127.0.0.1:6379","riskServiceKey",service));
        values.addAll(Arrays.asList(extra));
        return BehaviorConfig.fromPropertySet(properties(values.toArray(new String[0])),Collections.singletonMap("GEOVELIS_RISK_HMAC_KEY",SECRET));
    }
    static RiskConfig risk() throws Exception { return RiskConfig.fromPropertySet(properties("riskEnabled","true")); }
    static RestRequestContext context(String user, String layer, String input) {
        return new RestRequestContext(user,"",layer,"query",input,"json","");
    }
    static BehaviorEvent event(BehaviorConfig config, String user, String layer, String input,
                               String effective, long bytes, RiskAssessment.Outcome outcome) throws Exception {
        RestRequestContext ctx = context(user,layer,input);
        RiskAssessment risk = new RiskAnalyzer(risk(),true).observe(ctx,bytes,1,outcome);
        return BehaviorEvent.create(config,ctx,effective,risk,bytes,outcome,"balanced");
    }
    static BehaviorEvent event(BehaviorConfig config, String input) throws Exception {
        return event(config,"test-user","0",input,input,100,RiskAssessment.Outcome.RETURNED);
    }

    @Test public void disabledByDefaultAndStrictlyValidatesRedisConfiguration() throws Exception {
        assertFalse(BehaviorConfig.fromPropertySet(properties(),Collections.emptyMap()).enabled());
        for (String uri : Arrays.asList("http://localhost", "redis://secret:password@localhost",
                "redis://localhost/1","redis://localhost:0","redis://localhost?secret=x")) {
            try {
                BehaviorConfig.fromPropertySet(properties("riskRedisEndpoint",uri,"riskServiceKey","test"),
                        Collections.singletonMap("GEOVELIS_RISK_HMAC_KEY",SECRET));
                fail(uri);
            } catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().contains("password@")); }
        }
        try { BehaviorConfig.fromPropertySet(properties("riskRedisEndpoint","redis://localhost","riskServiceKey","test"),Collections.emptyMap()); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("HMAC")); }
        try { BehaviorConfig.fromPropertySet(properties("riskRedisEndpoint","redis://localhost"),
                Collections.singletonMap("GEOVELIS_RISK_HMAC_KEY",SECRET)); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("riskServiceKey")); }
    }

    @Test public void blockThresholdRequiresValidRedisAndRiskConfiguration() throws Exception {
        assertEquals(0,config("test").blockThreshold());
        assertEquals(80,config("test","riskBlockThreshold","80").blockThreshold());
        for(String value:Arrays.asList("-1","101","bad")) {
            try { config("test","riskBlockThreshold",value); fail(value); }
            catch(IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("riskBlockThreshold")); }
        }
        try { BehaviorConfig.fromPropertySet(properties("riskBlockThreshold","80")); fail(); }
        catch(IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("riskRedisEndpoint")); }
    }

    @Test public void blockCheckBypassesAnonymousAndNonQueryWithoutRedis() throws Exception {
        BehaviorConfig cfg=config("test","riskBlockThreshold","80","riskRedisEndpoint","redis://127.0.0.1:1");
        try(BehaviorBlockGate gate=new BehaviorBlockGate(cfg,"balanced",true)) {
            assertFalse(gate.check(context("anonymous","0","{}")).reportable());
            assertFalse(gate.check(context("user","","{}")).reportable());
            assertFalse(gate.check(new RestRequestContext("user","","0","identify","{}","json","")).reportable());
        }
    }

    @Test public void blockCheckFailsOpenWithinCallerDeadline() throws Exception {
        java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService server=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(java.net.ServerSocket listener=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) {
            server.submit(() -> { try(java.net.Socket socket=listener.accept()) { release.await(3,java.util.concurrent.TimeUnit.SECONDS); } catch(Exception ignored) { } });
            BehaviorConfig cfg=config("timeout","riskBlockThreshold","80","riskRedisEndpoint","redis://localhost:"+listener.getLocalPort());
            try(BehaviorBlockGate gate=new BehaviorBlockGate(cfg,"balanced",true)) {
                long start=System.nanoTime();
                BehaviorBlockGate.Result result=gate.check(context("user","0","{}"));
                assertFalse(result.blocked());
                assertTrue(result.logMessage().contains("FAIL_OPEN"));
                assertTrue(System.nanoTime()-start<java.util.concurrent.TimeUnit.SECONDS.toNanos(1));
            }
        } finally { release.countDown(); server.shutdownNow(); }
    }

    @Test public void identitiesAreStableAcrossInstancesAndScopesAreIsolated() throws Exception {
        BehaviorEvent a=event(config("site/service"),"{}"), b=event(config("site/service"),"{}");
        assertEquals(a.key,b.key);
        assertEquals(a.subject,b.subject);
        assertNotEquals(a.id,b.id);
        assertNotEquals(a.key,event(config("other/service"),"{}").key);
        assertFalse(a.key.contains("test-user"));
        assertFalse(a.key.contains("site/service"));
        assertFalse(config("site/service").toString().contains(SECRET));
    }

    @Test public void anonymousAndUnknownLayersDoNotCreateSharedState() throws Exception {
        BehaviorConfig cfg=config("test");
        for(String user:Arrays.asList("","anonymous","anonymous-or-unknown")) {
            assertNull(event(cfg,user,"0","{}","{}",0,RiskAssessment.Outcome.RETURNED));
        }
        assertNull(event(cfg,"user","","{}","{}",0,RiskAssessment.Outcome.RETURNED));
    }

    @Test public void originalIntentAndEffectiveLimitRemainDistinct() throws Exception {
        BehaviorEvent event=event(config("test"),"user","0","{\"resultOffset\":0,\"resultRecordCount\":2000}",
                "{\"resultOffset\":0,\"resultRecordCount\":10}",999,RiskAssessment.Outcome.RULE_BLOCKED);
        assertEquals(2000,event.requestedRecords);
        assertEquals(10,event.effectiveRecords);
        assertEquals(0,event.responseBytes);
        assertFalse(event.returned);
    }

    @Test public void fingerprintsIgnorePagingAndTokensButSeparateQueries() throws Exception {
        BehaviorConfig cfg=config("test");
        BehaviorEvent a=event(cfg,"{\"where\":\"EMAIL='private@example.test'\",\"resultOffset\":0,\"token\":\"secret\"}");
        BehaviorEvent b=event(cfg,"{\"token\":\"another\",\"resultOffset\":100,\"resultRecordCount\":100,\"where\":\"EMAIL='private@example.test'\"}");
        assertEquals(a.query,b.query);
        assertNotEquals(a.query,event(cfg,"{\"where\":\"OTHER=1\"}").query);
        assertFalse(a.query.contains("private"));
        assertEquals(64,a.query.length());
    }

    @Test public void malformedOrLargeInputHasExplicitStatusAndNoFingerprint() throws Exception {
        assertEquals("INVALID_JSON",event(config("test"),"{").inputStatus);
        BehaviorEvent large=event(config("test")," ".repeat(65537));
        assertEquals("TOO_LARGE",large.inputStatus);
        assertEquals("",large.query);
    }

    @Test public void requestOnlyModeKeepsLocalUserAndSessionUnavailable() throws Exception {
        RiskAssessment result=new RiskAnalyzer(risk(),true).observe(context("user","0","{\"where\":\"1=1\",\"outFields\":\"*\"}"),
                10,1,RiskAssessment.Outcome.RETURNED);
        assertEquals(40,result.score());
        assertEquals(-1,result.userScore());
        assertEquals(-1,result.sessionScore());
        assertTrue(result.logMessage().contains("correlation=REDIS_ASYNC"));
    }

    @Test public void unresponsiveRedisDoesNotWaitOnCallerAndReportsUnavailable() throws Exception {
        java.util.concurrent.CountDownLatch warning=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
        List<String> messages=new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.ExecutorService server=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(java.net.ServerSocket listener=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) {
            java.util.concurrent.Future<?> accepted=server.submit(() -> {
                try(java.net.Socket socket=listener.accept()) { release.await(5,java.util.concurrent.TimeUnit.SECONDS); }
                catch(Exception ignored) { }
            });
            BehaviorConfig cfg=BehaviorConfig.fromPropertySet(properties("riskRedisEndpoint","redis://localhost:"+listener.getLocalPort(),
                    "riskServiceKey","failure-test"),Collections.singletonMap("GEOVELIS_RISK_HMAC_KEY",SECRET));
            try(BehaviorObserver observer=new BehaviorObserver(cfg,risk(),messages::add,message->{messages.add(message);warning.countDown();})) {
                RestRequestContext ctx=context("user","0","{}");
                RiskAssessment risk=new RiskAnalyzer(risk(),true).observe(ctx,0,0,RiskAssessment.Outcome.RETURNED);
                long start=System.nanoTime();
                observer.submit(ctx,"{}",risk,0,RiskAssessment.Outcome.RETURNED);
                assertTrue("Caller waited for network timeout",System.nanoTime()-start<java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(750));
                assertTrue(warning.await(4,java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(messages.toString(),messages.get(0).contains("behaviorRiskScore=NA"));
                assertTrue(messages.get(0).contains("REDIS_UNAVAILABLE"));
                observer.submit(ctx,"{}",risk,0,RiskAssessment.Outcome.RETURNED); // Backoff, no exception.
            }
            release.countDown();
            accepted.get(2,java.util.concurrent.TimeUnit.SECONDS);
        } finally { release.countDown(); server.shutdownNow(); }
    }
}
