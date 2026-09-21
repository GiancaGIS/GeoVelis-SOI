package it.giancagis.arcgis.geovelis.risk;

import com.esri.arcgis.system.IPropertySet;
import it.giancagis.arcgis.geovelis.rest.RestRequestContext;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class RiskAnalyzerTest {
    private static final String SMALL = "{\"where\":\"POP>1000\",\"returnGeometry\":false}";

    @Test public void combinesSignalsWithoutTreatingBroadWhereAloneAsHighRisk() throws Exception {
        RiskAnalyzer risk = analyzer(new AtomicLong(), "riskLargeEnvelopeArea", "100");
        RiskAssessment ordinary = observe(risk, "alice", "0", "{\"where\":\"1=1\"}", 0);
        assertEquals(15, ordinary.score());
        assertEquals("LOW", ordinary.level());
        RiskAssessment broad = observe(risk, "alice", "0", "{\"where\":\"1=1\",\"outFields\":\"*\","
                + "\"geometry\":\"0,0,20,20\",\"resultRecordCount\":1000}", 100);
        assertEquals(70, broad.score());
        assertEquals("HIGH", broad.level());
        assertEquals(Integer.valueOf(15), broad.contributions().get(RiskSignal.BROAD_GEOMETRY_EXPORT));
        assertTrue(broad.logMessage().contains("decision=OBSERVE"));
    }

    @Test public void summaryRequestsDoNotGetGeometrySignals() throws Exception {
        for (String parameter : new String[]{"\"returnIdsOnly\":true", "\"returnCountOnly\":true",
                "\"returnExtentOnly\":true", "\"outStatistics\":[{\"statisticType\":\"count\"}]"}) {
            RiskAssessment result = observe(analyzer(new AtomicLong()), "alice", "0", "{" + parameter + "}", 0);
            assertFalse(result.contributions().containsKey(RiskSignal.GEOMETRY));
            assertFalse(result.contributions().containsKey(RiskSignal.BROAD_GEOMETRY_EXPORT));
        }
        assertTrue(observe(analyzer(new AtomicLong()), "alice", "0", "{\"returnIdsOnly\":true}", 0)
                .contributions().containsKey(RiskSignal.IDS_ONLY));
    }

    @Test public void envelopeSignalRequiresCalibratedThresholdAndValidEnvelope() throws Exception {
        assertFalse(observe(analyzer(new AtomicLong()), "a", "0", "{\"geometry\":\"0,0,10000,10000\"}", 0)
                .contributions().containsKey(RiskSignal.LARGE_ENVELOPE));
        RiskAnalyzer risk = analyzer(new AtomicLong(), "riskLargeEnvelopeArea", "100");
        assertTrue(observe(risk, "a", "0", "{\"geometry\":{\"xmin\":0,\"ymin\":0,\"xmax\":10,\"ymax\":10}}", 0)
                .contributions().containsKey(RiskSignal.LARGE_ENVELOPE));
        for (String geometry : new String[]{"\"NaN,0,100,100\"", "{\"rings\":[]}", "\"0,0,1\""}) {
            assertFalse(observe(risk, "a", "0", "{\"geometry\":" + geometry + "}", 0)
                    .contributions().containsKey(RiskSignal.LARGE_ENVELOPE));
        }
    }

    @Test public void tracksIncreasingPagingWhileIgnoringTokenAndTopLevelParameterOrder() throws Exception {
        RiskAnalyzer risk = analyzer(new AtomicLong(), "riskProfile", "sensitive");
        observe(risk, "a", "0", page(0), 0);
        observe(risk, "a", "0", page(10), 0);
        RiskAssessment result = observe(risk, "a", "0",
                "{\"returnGeometry\":false,\"resultOffset\":20,\"where\":\"POP>1000\",\"token\":\"secret\"}", 0);
        assertEquals(5, result.score());
        assertEquals(25, result.userScore());
        assertEquals(25, result.sessionScore());
        assertTrue(result.logMessage().contains("increasingPages=3"));
        assertFalse(result.logMessage().contains("secret"));
    }

    @Test public void unrelatedQueriesLayersAndRepeatedOffsetsDoNotFormPagingSequence() throws Exception {
        RiskAnalyzer risk = analyzer(new AtomicLong(), "riskProfile", "sensitive");
        observe(risk, "a", "0", page(0), 0);
        observe(risk, "a", "1", page(10), 0);
        observe(risk, "a", "0", page(20).replace("POP>1000", "POP>2000"), 0);
        observe(risk, "a", "0", page(0), 0);
        RiskAssessment result = observe(risk, "a", "0", page(10), 0);
        assertEquals(5, result.userScore());
        assertEquals(5, result.sessionScore());
    }

    @Test public void userWindowExpiresWhileSessionRetainsItsEvidence() throws Exception {
        AtomicLong clock = new AtomicLong();
        RiskAnalyzer risk = analyzer(clock);
        RiskAssessment first = observe(risk, "alice", "0", "{\"where\":\"1=1\",\"outFields\":\"*\"}", 0);
        assertEquals(40, first.userScore());
        clock.set(60000);
        RiskAssessment next = observe(risk, "alice", "1", SMALL, 0);
        assertEquals(0, next.userScore());
        assertEquals(40, next.sessionScore());
        assertEquals(first.sessionId(), next.sessionId());
        assertTrue(next.logMessage().contains("windowRequests=1"));
    }

    @Test public void sessionEndsAfterIdleTimeoutAndStateCanBeCleared() throws Exception {
        AtomicLong clock = new AtomicLong();
        RiskAnalyzer risk = analyzer(clock);
        RiskAssessment first = observe(risk, "alice", "0", "{}", 0);
        clock.set(900000);
        RiskAssessment next = observe(risk, "alice", "0", SMALL, 0);
        assertNotEquals(first.sessionId(), next.sessionId());
        assertEquals(0, next.sessionScore());
        risk.clear();
        assertEquals(0, risk.trackedKeys());
        assertNotEquals(next.sessionId(), observe(risk, "alice", "0", SMALL, 0).sessionId());
    }

    @Test public void longActiveSessionIsRotatedAfterEightHours() throws Exception {
        AtomicLong clock = new AtomicLong();
        RiskAnalyzer risk = analyzer(clock);
        String first = observe(risk, "alice", "0", SMALL, 0).sessionId();
        for (int i = 1; i < 48; i++) {
            clock.set(i * 600000L);
            assertEquals(first, observe(risk, "alice", "0", SMALL, 0).sessionId());
        }
        clock.set(28800000);
        assertNotEquals(first, observe(risk, "alice", "0", SMALL, 0).sessionId());
    }

    @Test public void frequencyAggregatesLayersButSeparatesUsers() throws Exception {
        RiskAnalyzer risk = analyzer(new AtomicLong(), "riskProfile", "sensitive");
        RiskAssessment result = null;
        for (int i = 0; i < 15; i++) result = observe(risk, "alice", Integer.toString(i % 2), SMALL, 0);
        assertEquals(0, result.score());
        assertEquals(20, result.userScore());
        assertEquals(20, result.sessionScore());
        RiskAssessment bob = observe(risk, "bob", "0", SMALL, 0);
        assertEquals(0, bob.userScore());
        assertNotEquals(result.sessionId(), bob.sessionId());
        assertFalse(result.logMessage().contains("alice"));
    }

    @Test public void byteVolumeUsesDifferentUserAndSessionBudgetsAndSaturatesSafely() throws Exception {
        RiskAnalyzer risk = analyzer(new AtomicLong());
        RiskAssessment first = observe(risk, "a", "0", SMALL, 10485760);
        assertEquals(20, first.userScore());
        assertEquals(0, first.sessionScore());
        RiskAssessment second = observe(risk, "a", "0", SMALL, Long.MAX_VALUE);
        assertEquals(20, second.userScore());
        assertEquals(20, second.sessionScore());
        assertTrue(second.logMessage().contains("windowResponseBytes=" + Long.MAX_VALUE));
    }

    @Test public void blockedAndFailedRequestsDoNotCountAsDataReturned() throws Exception {
        RiskAnalyzer risk = analyzer(new AtomicLong());
        for (RiskAssessment.Outcome outcome : new RiskAssessment.Outcome[]{RiskAssessment.Outcome.RULE_BLOCKED,
                RiskAssessment.Outcome.MASKING_BLOCKED, RiskAssessment.Outcome.ERROR, RiskAssessment.Outcome.NO_DELEGATE}) {
            RiskAssessment result = risk.observe(ctx("alice", "0", SMALL), Long.MAX_VALUE, 1, outcome);
            assertEquals(0, result.userScore());
            assertTrue(result.logMessage().contains("windowResponseBytes=0"));
        }
    }

    @Test public void anonymousActivityHasNoInventedUserOrSessionScores() throws Exception {
        RiskAnalyzer risk = analyzer(new AtomicLong());
        for (String user : new String[]{"", "anonymous", "anonymous-or-unknown"}) {
            RiskAssessment result = observe(risk, user, "0", "{\"where\":\"1=1\"}", 99999999);
            assertEquals(-1, result.userScore());
            assertEquals(-1, result.sessionScore());
            assertEquals("NA", result.sessionId());
            assertTrue(result.logMessage().contains("correlation=UNAVAILABLE_ANONYMOUS"));
        }
        assertEquals(0, risk.trackedKeys());
    }

    @Test public void noRawQueryTokenCoordinatesOrIdentityIsRetainedInLog() throws Exception {
        RiskAssessment result = observe(analyzer(new AtomicLong()), "private-user", "0",
                "{\"where\":\"EMAIL='private@example.test'\",\"token\":\"secret-token\",\"geometry\":\"111,222,333,444\"}", 0);
        for (String secret : new String[]{"private-user", "private@example.test", "secret-token", "111,222"}) {
            assertFalse(result.logMessage().contains(secret));
        }
    }

    @Test public void invalidAndOversizedInputIsExplicitlyMarkedWithoutRawContent() throws Exception {
        RiskAnalyzer risk = analyzer(new AtomicLong());
        assertTrue(observe(risk, "a", "0", "{broken-private-value", 0).logMessage().contains("inputStatus=INVALID_JSON"));
        String huge = "x".repeat(65537);
        RiskAssessment result = observe(risk, "a", "0", huge, 0);
        assertTrue(result.logMessage().contains("inputStatus=TOO_LARGE"));
        assertFalse(result.logMessage().contains(huge));
    }

    @Test public void stateIsBoundedAndCapacityEvictionsAreVisible() throws Exception {
        RiskAnalyzer risk = analyzer(new AtomicLong());
        RiskAssessment result = null;
        for (int i = 0; i < 1001; i++) result = observe(risk, "user" + i, "0", SMALL, 0);
        assertEquals(1000, risk.trackedKeys());
        assertTrue(result.logMessage().contains("capacityEvictions=1"));
        risk.clear();
        for (int i = 0; i < 33; i++) result = observe(risk, "a", "0", page(0).replace("1000", Integer.toString(i)), 0);
        assertTrue(result.logMessage().contains("capacityEvictions=1"));
    }

    @Test public void concurrentRequestsDoNotLoseUserCounters() throws Exception {
        RiskAnalyzer risk = analyzer(new AtomicLong());
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            java.util.List<Future<?>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < 100; i++) tasks.add(executor.submit(() -> observe(risk, "a", "0", SMALL, 1)));
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertTrue(observe(risk, "a", "0", SMALL, 0).logMessage().contains("windowRequests=101"));
    }

    @Test public void disabledAndNonQueryRequestsHaveNoTelemetryOrState() throws Exception {
        RiskAnalyzer disabled = new RiskAnalyzer(RiskConfig.defaults());
        assertNull(observe(disabled, "a", "0", "{}", 0));
        RiskAnalyzer risk = analyzer(new AtomicLong());
        assertNull(risk.observe(new RestRequestContext("a", "", "0", "identify", "{}", "json", ""), 0, 0, RiskAssessment.Outcome.RETURNED));
        assertEquals(0, risk.trackedKeys());
    }

    @Test public void profilesAndInvalidRiskConfigurationAreValidated() throws Exception {
        assertEquals(60, config("riskProfile", "conservative").requestThreshold());
        assertEquals(15, config("riskProfile", "SENSITIVE").requestThreshold());
        for (String[] pair : new String[][]{{"riskProfile", "custom"}, {"riskEnabled", "tru"},
                {"riskLargeEnvelopeArea", "NaN"}, {"riskLargeEnvelopeArea", "-1"}, {"riskLargeEnvelopeArea", "Infinity"}}) {
            try { config(pair); fail("Invalid configuration accepted"); }
            catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains(pair[0])); }
        }
    }

    private static String page(int offset) { return "{\"where\":\"POP>1000\",\"returnGeometry\":false,\"resultOffset\":" + offset + "}"; }
    private static RestRequestContext ctx(String user, String layer, String input) {
        return new RestRequestContext(user, "", layer, "query", input, "json", "");
    }
    private static RiskAssessment observe(RiskAnalyzer analyzer, String user, String layer, String input, long bytes) {
        return analyzer.observe(ctx(user, layer, input), bytes, 1, RiskAssessment.Outcome.RETURNED);
    }
    private static RiskAnalyzer analyzer(AtomicLong clock, String... properties) throws Exception { return new RiskAnalyzer(config(properties), clock::get); }
    private static RiskConfig config(String... properties) throws Exception {
        Map<String, String> values = new HashMap<>();
        values.put("riskEnabled", "true");
        for (int i = 0; i < properties.length; i += 2) values.put(properties[i], properties[i + 1]);
        IPropertySet props = (IPropertySet) Proxy.newProxyInstance(IPropertySet.class.getClassLoader(), new Class<?>[]{IPropertySet.class},
                (proxy, method, args) -> values.get(args[0]));
        return RiskConfig.fromPropertySet(props);
    }
}
