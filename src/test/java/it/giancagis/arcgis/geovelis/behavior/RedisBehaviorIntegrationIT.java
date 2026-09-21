package it.giancagis.arcgis.geovelis.behavior;

import it.giancagis.arcgis.geovelis.risk.*;
import org.junit.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Explicit integration suite against localhost:6379. Uses only isolated, expiring test keys; never FLUSHDB. */
public class RedisBehaviorIntegrationIT {
    private BehaviorConfig config;
    private RiskConfig risk;
    private final Set<String> keys=ConcurrentHashMap.newKeySet();

    @Before public void setup() throws Exception {
        config=BehaviorEventTest.config("integration-"+UUID.randomUUID());
        risk=BehaviorEventTest.risk();
        try(RedisConnection redis=new RedisConnection(config)) { assertEquals("PONG",redis.command("PING")); }
    }
    @After public void cleanup() throws Exception {
        if(keys.isEmpty()) return;
        try(RedisConnection redis=new RedisConnection(config)) {
            for(String key:keys) redis.command("DEL",key,BehaviorEvent.blockKey(key));
        }
    }
    private BehaviorEvent event(String input) throws Exception {
        BehaviorEvent event=BehaviorEventTest.event(config,input);
        keys.add(event.key);
        return event;
    }
    private RedisBehaviorStore at(long seconds) {
        return new RedisBehaviorStore(config,risk,RedisBehaviorStore.loadScript().replace(
                "local now = tonumber(clock[1])","local now = "+seconds));
    }
    private String page(int offset,int count) {
        return "{\"where\":\"POP>100\",\"returnGeometry\":false,\"resultOffset\":"+offset+",\"resultRecordCount\":"+count+"}";
    }

    @Test public void separateJavaClientsSharePagingVolumeAndFrequency() throws Exception {
        try(RedisBehaviorStore first=at(2000000000L); RedisBehaviorStore second=at(2000000000L)) {
            String message="";
            for(int i=0;i<40;i++) message=(i%2==0?first:second).observe(event(page(i*2000,2000)));
            assertTrue(message,message.contains("behaviorRiskScore=85"));
            assertTrue(message.contains("windowRequests=40"));
            assertTrue(message.contains("windowRequestedRecords=80000"));
            assertTrue(message.contains("sequentialTransitions=39"));
            assertTrue(message.contains("HighQueryFrequency,HighVolume,SequentialPaging"));
        }
    }
    @Test public void windowExpiresWhileSessionAndItsPeakSurvive() throws Exception {
        try(RedisBehaviorStore first=at(2000000000L); RedisBehaviorStore later=at(2000000061L)) {
            for(int i=0;i<40;i++) first.observe(event(page(i*2000,2000)));
            String message=later.observe(event("{}"));
            assertTrue(message,message.contains("windowRequests=1"));
            assertTrue(message.contains("behaviorRiskScore=0"));
            assertTrue(message.contains("sessionPeakBehaviorRisk=85"));
            assertTrue(message.contains("sessionRequests=41"));
        }
    }
    @Test public void idleTtlIsRefreshedAndExpiredStateStartsANewSession() throws Exception {
        try(RedisBehaviorStore store=new RedisBehaviorStore(config,risk); RedisConnection redis=new RedisConnection(config)) {
            BehaviorEvent first=event("{}");
            store.observe(first);
            assertEquals(300L,redis.command("TTL",first.key));
            redis.command("EXPIRE",first.key,"10");
            store.observe(event("{}"));
            assertEquals(300L,redis.command("TTL",first.key));
            redis.command("PEXPIRE",first.key,"1");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while((Long)redis.command("EXISTS",first.key)!=0 && System.nanoTime()<deadline) Thread.sleep(5);
            assertEquals(0L,redis.command("EXISTS",first.key));
            BehaviorEvent next=event("{}");
            String message=store.observe(next);
            assertTrue(message,message.contains("session="+next.id));
            assertTrue(message.contains("sessionRequests=1"));
        }
    }
    @Test public void duplicateEventDoesNotInflateCounters() throws Exception {
        try(RedisBehaviorStore store=at(2000000000L)) {
            BehaviorEvent first=event(page(0,2000));
            assertNotNull(store.observe(first));
            assertNull(store.observe(first));
            assertTrue(store.observe(event(page(2000,2000))).contains("windowRequests=2"));
        }
    }
    @Test public void idsEnumerationThenDistinctBatchesProducesBehaviorSignal() throws Exception {
        try(RedisBehaviorStore store=at(2000000000L)) {
            store.observe(event("{\"where\":\"1=1\",\"returnIdsOnly\":true}"));
            store.observe(event("{\"objectIds\":\"1,2,3\"}"));
            store.observe(event("{\"objectIds\":\"1,2,3\"}"));
            store.observe(event("{\"objectIds\":\"4,5,6\"}"));
            String message=store.observe(event("{\"objectIds\":\"7,8,9\"}"));
            assertTrue(message,message.contains("IdsThenObjectIdBatches"));
            assertTrue(message.contains("distinctIdBatches=3"));
            assertTrue(message.contains("behaviorRiskScore=40"));
            assertFalse(message.contains("1,2,3"));
        }
    }
    @Test public void failedRequestsDoNotAdvancePagingOrReturnedByteVolume() throws Exception {
        try(RedisBehaviorStore store=at(2000000000L)) {
            store.observe(event(page(0,2000)));
            BehaviorEvent failed=BehaviorEventTest.event(config,"test-user","0",page(2000,2000),page(2000,2000),
                    100000000,RiskAssessment.Outcome.ERROR);
            keys.add(failed.key);
            String message=store.observe(failed);
            assertTrue(message,message.contains("windowResponseBytes=100"));
            assertTrue(message.contains("sequentialTransitions=0"));
            assertTrue(message.contains("windowRequests=2"));
        }
    }
    @Test public void differentUsersLayersAndQueryShapesCannotSharePagingSequence() throws Exception {
        try(RedisBehaviorStore store=at(2000000000L)) {
            store.observe(event(page(0,2000)));
            for(String[] identity:new String[][]{{"other","0"},{"test-user","1"}}) {
                BehaviorEvent e=BehaviorEventTest.event(config,identity[0],identity[1],page(2000,2000),page(2000,2000),1,RiskAssessment.Outcome.RETURNED);
                keys.add(e.key);
                assertTrue(store.observe(e).contains("windowRequests=1"));
            }
            String message=store.observe(event(page(2000,2000).replace("POP>100","POP>200")));
            assertTrue(message.contains("sequentialTransitions=0"));
            message=store.observe(event(page(4000,2000))); // Gap must not count as contiguous paging.
            assertTrue(message.contains("sequentialTransitions=0"));
        }
    }
    @Test public void concurrentClientsDoNotLoseUpdates() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> tasks=new ArrayList<>();
            for(int i=0;i<4;i++) tasks.add(pool.submit(() -> {
                try(RedisBehaviorStore store=at(2000000000L)) {
                    for(int j=0;j<25;j++) store.observe(event("{}"));
                } catch(Exception ex) { throw new RuntimeException(ex); }
            }));
            for(Future<?> task:tasks) task.get(15,TimeUnit.SECONDS);
            try(RedisBehaviorStore store=at(2000000000L)) {
                assertTrue(store.observe(event("{}")).contains("windowRequests=101"));
            }
        } finally { pool.shutdownNow(); }
    }
    @Test public void hashStateIsBoundedDespiteManyDifferentQueriesAndEventIds() throws Exception {
        try(RedisBehaviorStore store=at(2000000000L); RedisConnection redis=new RedisConnection(config)) {
            BehaviorEvent last=null;
            for(int i=0;i<350;i++) {
                last=event("{\"where\":\"OBJECTID="+i+"\",\"resultOffset\":0}");
                store.observe(last);
            }
            assertTrue((Long)redis.command("HLEN",last.key)<800);
            assertTrue((Long)redis.command("TTL",last.key)>0);
        }
    }
    @Test public void observerDeliversAsynchronouslyAndClosesCleanly() throws Exception {
        CountDownLatch complete=new CountDownLatch(1);
        List<String> logs=new CopyOnWriteArrayList<>();
        try(BehaviorObserver observer=new BehaviorObserver(config,risk,message->{logs.add(message);complete.countDown();},logs::add)) {
            String input=page(0,2000);
            BehaviorEvent tracked=event(input);
            it.giancagis.arcgis.geovelis.rest.RestRequestContext ctx=BehaviorEventTest.context("test-user","0",input);
            observer.submit(ctx,input,new RiskAnalyzer(risk,true).observe(ctx,100,1,RiskAssessment.Outcome.RETURNED),100,RiskAssessment.Outcome.RETURNED);
            assertTrue(complete.await(5,TimeUnit.SECONDS));
            assertTrue(logs.toString(),logs.get(0).contains("[BEHAVIOR]"));
            assertTrue(logs.get(0).contains("windowRequests=1"));
        }
    }

    @Test public void activeSessionRotatesAfterEightHours() throws Exception {
        try(RedisBehaviorStore first=at(2000000000L); RedisBehaviorStore later=at(2000028800L)) {
            first.observe(event("{}"));
            BehaviorEvent next=event("{}");
            String message=later.observe(next);
            assertTrue(message,message.contains("session="+next.id));
            assertTrue(message.contains("sessionRequests=1"));
        }
    }

    @Test public void thresholdCreatesBlockVisibleToAnotherGateWithoutRenewingTtl() throws Exception {
        config=BehaviorEventTest.config("block-"+UUID.randomUUID(),"riskBlockThreshold","80");
        try(RedisBehaviorStore store=new RedisBehaviorStore(config,risk,RedisBehaviorStore.loadScript(),true);
            RedisConnection redis=new RedisConnection(config);
            BehaviorBlockGate gate=new BehaviorBlockGate(config,"balanced",true)) {
            String message="";
            for(int i=0;i<30;i++) message=store.observe(event(page(i*2000,2000)));
            assertTrue(message,message.contains("decision=BLOCK_CREATED"));
            String key=BehaviorEvent.blockKey(event("{}").key);
            assertEquals("1",redis.command("GET",key));
            assertTrue(gate.check(BehaviorEventTest.context("test-user","0","{}")).blocked());
            assertFalse(gate.check(BehaviorEventTest.context("another-user","0","{}")).blocked());
            assertFalse(gate.check(BehaviorEventTest.context("test-user","1","{}")).blocked());
            redis.command("PEXPIRE",key,"20000");
            assertTrue(store.observe(event(page(60000,2000))).contains("decision=BLOCK_EXISTS"));
            assertTrue((Long)redis.command("PTTL",key)<=20000);
        }
    }

    @Test public void auditAndDisabledThresholdNeverCreateDecisions() throws Exception {
        for(boolean audit:Arrays.asList(true,false)) {
            config=BehaviorEventTest.config("observe-"+UUID.randomUUID(),"riskBlockThreshold",audit?"80":"0");
            try(RedisBehaviorStore store=new RedisBehaviorStore(config,risk,RedisBehaviorStore.loadScript(),!audit);
                RedisConnection redis=new RedisConnection(config)) {
                String message="";
                for(int i=0;i<40;i++) message=store.observe(event(page(i*2000,2000)));
                assertTrue(message,message.contains(audit?"decision=WOULD_BLOCK":"decision=OBSERVE"));
                assertEquals(0L,redis.command("EXISTS",BehaviorEvent.blockKey(event("{}").key)));
            }
        }
    }

    @Test public void manualBlockExpiresAuditPassesAndPersistentKeysAreRejected() throws Exception {
        config=BehaviorEventTest.config("manual-"+UUID.randomUUID(),"riskBlockThreshold","80");
        String key=BehaviorEvent.blockKey(event("{}").key);
        try(RedisConnection redis=new RedisConnection(config);
            BehaviorBlockGate enforce=new BehaviorBlockGate(config,"balanced",true);
            BehaviorBlockGate audit=new BehaviorBlockGate(config,"balanced",false)) {
            redis.command("SET",key,"1","EX","300");
            assertTrue(enforce.check(BehaviorEventTest.context("test-user","0","{}")).blocked());
            assertTrue(audit.check(BehaviorEventTest.context("test-user","0","{}")).logMessage().contains("AUDIT_ONLY"));
            redis.command("PEXPIRE",key,"1");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while((Long)redis.command("EXISTS",key)!=0 && System.nanoTime()<deadline) Thread.sleep(5);
            assertFalse(enforce.check(BehaviorEventTest.context("test-user","0","{}")).blocked());
            redis.command("SET",key,"1");
            assertTrue(enforce.check(BehaviorEventTest.context("test-user","0","{}")).logMessage().contains("INVALID_BLOCK_KEY"));
            redis.command("SET",key,"wrong","EX","300");
            assertFalse(enforce.check(BehaviorEventTest.context("test-user","0","{}")).blocked());
        }
    }

    @Test public void behaviorBlockedRequestsNeverEnterAsyncObservation() throws Exception {
        config=BehaviorEventTest.config("blocked-"+UUID.randomUUID(),"riskBlockThreshold","80");
        BehaviorEvent e=event("{}");
        try(BehaviorObserver observer=new BehaviorObserver(config,risk,true,message->fail(message),message->fail(message));
            RedisConnection redis=new RedisConnection(config)) {
            it.giancagis.arcgis.geovelis.rest.RestRequestContext ctx=BehaviorEventTest.context("test-user","0","{}");
            RiskAssessment assessment=new RiskAnalyzer(risk,true).observe(ctx,100,0,RiskAssessment.Outcome.BEHAVIOR_BLOCKED);
            observer.submit(ctx,"{}",assessment,100,RiskAssessment.Outcome.BEHAVIOR_BLOCKED);
            assertEquals(0L,redis.command("EXISTS",e.key));
        }
    }

    @Test public void queueOverflowIsCountedWithoutRunningRedisOnCaller() throws Exception {
        CountDownLatch workerBlocked=new CountDownLatch(1), release=new CountDownLatch(1);
        List<String> warnings=new CopyOnWriteArrayList<>();
        try(BehaviorObserver observer=new BehaviorObserver(config,risk,message->{
            workerBlocked.countDown();
            try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
        },warnings::add)) {
            event("{}"); // Track the isolated key for cleanup.
            it.giancagis.arcgis.geovelis.rest.RestRequestContext ctx=BehaviorEventTest.context("test-user","0","{}");
            RiskAssessment assessment=new RiskAnalyzer(risk,true).observe(ctx,0,0,RiskAssessment.Outcome.RETURNED);
            observer.submit(ctx,"{}",assessment,0,RiskAssessment.Outcome.RETURNED);
            assertTrue(workerBlocked.await(3,TimeUnit.SECONDS));
            for(int i=0;i<270;i++) observer.submit(ctx,"{}",assessment,0,RiskAssessment.Outcome.RETURNED);
            assertTrue(warnings.toString(),warnings.stream().anyMatch(message->message.contains("QUEUE_FULL_OR_CLOSED")));
        } finally { release.countDown(); }
    }
}
