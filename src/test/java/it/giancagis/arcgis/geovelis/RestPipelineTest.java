package it.giancagis.arcgis.geovelis;

import com.esri.arcgis.server.json.JSONObject;
import com.esri.arcgis.system.ILog;
import com.esri.arcgis.system.IPropertySet;
import com.esri.arcgis.system.IRESTRequestHandler;
import it.giancagis.arcgis.geovelis.audit.ArcGisServerAuditLogger;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Exercises the real REST pipeline, replacing only native ArcGIS boundaries. */
public class RestPipelineTest {
    private static final String BODY = "{\"features\":[{\"attributes\":{\"EMAIL\":\"private@example.test\"}}]}";
    private static final String HEADERS = "{\"Content-Type\":\"application/json\",\"Content-Length\":999,"
            + "\"ETag\":\"original\",\"Access-Control-Allow-Origin\":\"https://client.test\"}";

    @Test
    public void maskedResponseInvalidatesOriginalHeaders() throws Exception {
        Harness soi = harness("mode", "enforce", "responseMaskingEnabled", "true");
        String[] headers = {null};
        byte[] response = request(soi, "{}", "json", headers);
        JSONObject properties = new JSONObject(headers[0]);
        assertFalse(new String(response, StandardCharsets.UTF_8).contains("private@example.test"));
        assertFalse("Old Content-Length survived masking", properties.has("Content-Length"));
        assertFalse(properties.has("ETag"));
        assertEquals("https://client.test", properties.opt("Access-Control-Allow-Origin"));
        assertEquals(1, soi.calls);
    }

    @Test
    public void requestAndRuleLogsDoNotExposeQueryValues() throws Exception {
        Harness soi = harness("mode", "audit");
        request(soi, "{\"where\":\"EMAIL='private@example.test'\",\"outFields\":\"*\","
                + "\"token\":\"secret-token\",\"resultRecordCount\":10}", "json", new String[1]);
        assertFalse("Query value leaked into logs", soi.logs.toString().contains("private@example.test"));
        assertFalse(soi.logs.toString().contains("secret-token"));
        assertTrue(soi.logs.toString().contains("[GeoVelis][RULE]"));
    }

    @Test
    public void misspelledModeDoesNotReplaceEnforceConfiguration() throws Exception {
        Harness soi = harness("mode", "enforce", "fieldGuardianEnabled", "true", "fieldGuardianDeniedOutFields", "EMAIL");
        try {
            soi.construct(properties("mode", "enfroce"));
            fail("Invalid mode was accepted");
        } catch (IllegalArgumentException | java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("mode"));
        }
        request(soi, "{\"outFields\":\"*\"}", "json", new String[1]);
        assertEquals("Previous valid protection must remain active", 0, soi.calls);
    }

    @Test
    public void auditPreservesBodyHeadersAndInputExactly() throws Exception {
        Harness soi = harness("mode", "audit", "responseMaskingEnabled", "true", "maxRecordCount", "10");
        String input = "{\"resultRecordCount\":100}";
        String[] headers = new String[1];
        assertSame(soi.delegateResponse, request(soi, input, "json", headers));
        assertEquals(HEADERS, headers[0]);
        assertEquals(input, soi.effectiveInput);
        assertTrue(soi.logs.toString().contains("AUDIT_ONLY"));
    }

    @Test
    public void disabledInterceptorDelegatesWithoutChangesOrAudit() throws Exception {
        Harness soi = harness("enabled", "false", "mode", "enforce", "responseMaskingEnabled", "true");
        String[] headers = new String[1];
        assertSame(soi.delegateResponse, request(soi, "{}", "json", headers));
        assertEquals(HEADERS, headers[0]);
        assertEquals("", soi.logs.toString());
    }

    @Test
    public void enforceChainsRulesBeforeCallingDelegate() throws Exception {
        Harness soi = harness("mode", "enforce", "maxRecordCount", "10", "fieldGuardianEnabled", "true",
                "fieldGuardianDeniedOutFields", "EMAIL");
        request(soi, "{\"resultRecordCount\":100,\"outFields\":\"OBJECTID,EMAIL\"}", "json", new String[1]);
        JSONObject forwarded = new JSONObject(soi.effectiveInput);
        assertEquals(10, forwarded.optInt("resultRecordCount"));
        assertEquals("OBJECTID", forwarded.opt("outFields"));
        assertEquals(1, soi.calls);
    }

    @Test
    public void blockedRequestDoesNotCallDelegateAndReturnsJsonHeaders() throws Exception {
        Harness soi = harness("mode", "enforce", "fieldGuardianEnabled", "true", "fieldGuardianDeniedOutFields", "EMAIL");
        String[] headers = {"{\"Content-Type\":\"application/x-protobuf\"}"};
        byte[] body = request(soi, "{\"outFields\":\"*\"}", "pbf", headers);
        assertEquals(0, soi.calls);
        assertTrue(new JSONObject(new String(body, StandardCharsets.UTF_8)).has("error"));
        assertEquals("application/json; charset=utf-8", new JSONObject(headers[0]).opt("Content-Type"));
    }

    @Test
    public void strictUnsupportedResponseIsBlockedAfterDelegate() throws Exception {
        Harness soi = harness("mode", "enforce", "responseMaskingEnabled", "true", "responseMaskingFailPolicy", "block");
        soi.delegateResponse = new byte[]{0, 1, 2};
        soi.delegateHeaders = "{\"Content-Type\":\"application/x-protobuf\",\"Content-Length\":3,\"Content-Encoding\":\"gzip\"}";
        String[] headers = new String[1];
        assertTrue(new JSONObject(new String(request(soi, "{}", "pbf", headers), StandardCharsets.UTF_8)).has("error"));
        JSONObject result = new JSONObject(headers[0]);
        assertEquals("application/json; charset=utf-8", result.opt("Content-Type"));
        assertFalse(result.has("Content-Length"));
        assertFalse(result.has("Content-Encoding"));
        assertEquals(1, soi.calls);
    }

    @Test
    public void unmodifiedAndKeepOriginalResponsesPreserveHeaders() throws Exception {
        Harness soi = harness("mode", "enforce", "responseMaskingEnabled", "true", "responseMaskingFailPolicy", "keepOriginal");
        for (String body : Arrays.asList("{\"count\":1}", "{\"features\":[]}", "invalid")) {
            soi.delegateResponse = body.getBytes(StandardCharsets.UTF_8);
            String[] headers = new String[1];
            assertSame(soi.delegateResponse, request(soi, "{}", "json", headers));
            assertEquals(HEADERS, headers[0]);
        }
    }

    @Test
    public void geoJsonMaskingPreservesCorsAndDownloadButInvalidatesRepresentationMetadata() throws Exception {
        Harness soi = harness("mode", "enforce", "responseMaskingEnabled", "true");
        soi.delegateResponse = "{\"type\":\"FeatureCollection\",\"features\":[{\"properties\":{\"EMAIL\":\"private@example.test\"}}]}"
                .getBytes(StandardCharsets.UTF_8);
        soi.delegateHeaders = "{\"content-type\":\"application/geo+json\",\"content-length\":999,\"eTAG\":\"old\","
                + "\"Content-MD5\":\"old\",\"Last-Modified\":\"old\",\"Content-Disposition\":\"attachment; filename=data.geojson\","
                + "\"Access-Control-Allow-Origin\":\"https://client.test\"}";
        String[] headers = new String[1];
        assertFalse(new String(request(soi, "{}", "geojson", headers), StandardCharsets.UTF_8).contains("private@example.test"));
        JSONObject result = new JSONObject(headers[0]);
        assertEquals("application/geo+json; charset=utf-8", result.opt("Content-Type"));
        assertTrue(result.has("Content-Disposition"));
        assertEquals("https://client.test", result.opt("Access-Control-Allow-Origin"));
        for (String name : Arrays.asList("content-type", "content-length", "eTAG", "Content-MD5", "Last-Modified")) {
            assertFalse(name, result.has(name));
        }
    }

    @Test
    public void missingDelegateProducesErrorWithoutThrowing() throws Exception {
        Harness soi = harness("mode", "enforce");
        soi.missingDelegate = true;
        String[] headers = new String[1];
        assertTrue(new JSONObject(new String(request(soi, "{}", "json", headers), StandardCharsets.UTF_8)).has("error"));
        assertEquals(0, soi.calls);
        assertTrue(soi.logs.toString().contains("IllegalStateException"));
    }

    @Test
    public void delegateExceptionsPropagateWithoutLoggingTheirSensitiveMessage() throws Exception {
        Harness soi = harness("mode", "enforce");
        soi.failure = new IOException("Database error for private@example.test token=secret-token");
        try {
            request(soi, "{}", "json", new String[1]);
            fail("Expected delegate exception");
        } catch (IOException expected) {
            assertSame(soi.failure, expected);
        }
        assertTrue(soi.logs.toString().contains("IOException"));
        assertFalse(soi.logs.toString().contains("private@example.test"));
        assertFalse(soi.logs.toString().contains("secret-token"));
    }

    @Test
    public void fieldRuleDoesNotLogExpressionsAndDisabledInputLoggingStaysDisabled() throws Exception {
        Harness soi = harness("mode", "audit", "logOperationInput", "false", "fieldGuardianEnabled", "true",
                "fieldGuardianAllowedOutFields", "OBJECTID");
        request(soi, "{\"outFields\":\"'private@example.test' AS EMAIL\"}", "json", new String[1]);
        assertFalse(soi.logs.toString().contains("private@example.test"));
        assertTrue(soi.logs.toString().contains("[disabled]"));
        assertTrue(soi.logs.toString().contains("Field Guardian"));
    }

    @Test
    public void invalidPolicyDoesNotReplacePreviousConfiguration() throws Exception {
        Harness soi = harness("mode", "enforce", "responseMaskingEnabled", "true", "responseMaskingFailPolicy", "block");
        try {
            soi.construct(properties("responseMaskingFailPolicy", "blok"));
            fail("Invalid fail policy was accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("responseMaskingFailPolicy"));
        }
        soi.delegateResponse = new byte[]{0};
        assertTrue(new JSONObject(new String(request(soi, "{}", "pbf", new String[1]), StandardCharsets.UTF_8)).has("error"));
    }

    @Test
    public void highRiskIsObservedWithoutBlockingEvenInEnforce() throws Exception {
        Harness soi = harness("mode", "enforce", "riskEnabled", "true", "riskLargeEnvelopeArea", "100",
                "blockWhereOneEqualsOne", "true", "blockGenericGeometryQuery", "true", "blockOutFieldsStar", "true");
        String input = "{\"where\":\"1=1\",\"outFields\":\"*\",\"geometry\":\"0,0,100,100\","
                + "\"resultRecordCount\":1000,\"resultOffset\":1000}";
        assertSame(soi.delegateResponse, request(soi, input, "json", new String[1]));
        assertEquals(input, soi.effectiveInput);
        String risk = riskLine(soi);
        assertTrue(risk, risk.contains("requestRiskScore=75"));
        assertTrue(risk.contains("decision=OBSERVE"));
        assertTrue(risk.contains("outcome=RETURNED"));
        assertTrue(risk.contains("windowRequests=1"));
    }

    @Test
    public void riskUsesOriginalInputBeforeFieldAndCountRewrites() throws Exception {
        Harness soi = harness("mode", "enforce", "riskEnabled", "true", "maxRecordCount", "10",
                "fieldGuardianEnabled", "true", "fieldGuardianAllowedOutFields", "OBJECTID");
        request(soi, "{\"where\":\"1=1\",\"outFields\":\"*\",\"resultRecordCount\":1000}", "json", new String[1]);
        JSONObject forwarded = new JSONObject(soi.effectiveInput);
        assertEquals(10, forwarded.optInt("resultRecordCount"));
        assertEquals("OBJECTID", forwarded.opt("outFields"));
        String risk = riskLine(soi);
        assertTrue(risk.contains("ALL_FIELDS=10"));
        assertTrue(risk.contains("LARGE_PAGE=10"));
        assertTrue(risk.contains("requestRiskScore=50"));
    }

    @Test
    public void riskIsOptInAndRespectsGlobalDisableAndOperationScope() throws Exception {
        for (Harness soi : Arrays.asList(harness(), harness("enabled", "false", "riskEnabled", "true"))) {
            request(soi, "{}", "json", new String[1]);
            assertFalse(soi.logs.toString().contains("[RISK]"));
        }
        Harness soi = harness("riskEnabled", "true");
        soi.handleRESTRequest("", "0", "identify", "{}", "json", "{}", new String[1]);
        assertFalse(soi.logs.toString().contains("[RISK]"));
    }

    @Test
    public void riskRecordsEveryBlockedOrFailedOutcomeOnce() throws Exception {
        Harness blocked = harness("mode", "enforce", "riskEnabled", "true", "fieldGuardianEnabled", "true",
                "fieldGuardianDeniedOutFields", "EMAIL");
        request(blocked, "{\"outFields\":\"*\"}", "json", new String[1]);
        assertTrue(riskLine(blocked).contains("outcome=RULE_BLOCKED"));
        assertTrue(riskLine(blocked).contains("windowResponseBytes=0"));
        Harness masked = harness("mode", "enforce", "riskEnabled", "true", "responseMaskingEnabled", "true",
                "responseMaskingFailPolicy", "block");
        masked.delegateResponse = new byte[]{0, 1, 2};
        request(masked, "{}", "pbf", new String[1]);
        assertTrue(riskLine(masked).contains("outcome=MASKING_BLOCKED"));
        assertTrue(riskLine(masked).contains("windowResponseBytes=0"));
        Harness missing = harness("riskEnabled", "true");
        missing.missingDelegate = true;
        request(missing, "{}", "json", new String[1]);
        assertTrue(riskLine(missing).contains("outcome=NO_DELEGATE"));
        Harness failed = harness("riskEnabled", "true");
        failed.failure = new IOException("secret");
        try {
            request(failed, "{}", "json", new String[1]);
            fail("Expected delegate exception");
        } catch (IOException expected) { assertSame(failed.failure, expected); }
        assertTrue(riskLine(failed).contains("outcome=ERROR"));
        assertTrue(riskLine(failed).contains("windowResponseBytes=0"));
    }

    @Test
    public void riskLogsDoNotExposeUserOrQueryAndAnonymousHasNoAggregateScores() throws Exception {
        Harness soi = harness("riskEnabled", "true");
        request(soi, "{\"where\":\"EMAIL='private@example.test'\",\"token\":\"secret-token\"}", "json", new String[1]);
        String risk = riskLine(soi);
        for (String secret : Arrays.asList("test-user", "private@example.test", "secret-token")) {
            assertFalse(risk.contains(secret));
        }
        soi.logs.setLength(0);
        soi.user = "anonymous-or-unknown";
        request(soi, "{}", "json", new String[1]);
        risk = riskLine(soi);
        assertTrue(risk.contains("userRiskScore=NA"));
        assertTrue(risk.contains("sessionRiskScore=NA"));
    }

    @Test
    public void riskLoggingFailureCannotChangeResponseOrDelegateException() throws Exception {
        Harness soi = harness("riskEnabled", "true");
        Class<?> auditType = it.giancagis.arcgis.geovelis.audit.AuditLogger.class;
        Object failing = Proxy.newProxyInstance(auditType.getClassLoader(), new Class<?>[]{auditType}, (p, m, args) -> {
            if ("risk".equals(m.getName()) || "warning".equals(m.getName())) throw new IllegalStateException("sink failed");
            return null;
        });
        Field logger = GeoVelisSOI.class.getDeclaredField("auditLogger");
        logger.setAccessible(true);
        logger.set(soi, failing);
        assertSame(soi.delegateResponse, request(soi, "{}", "json", new String[1]));
        soi.failure = new IOException("delegate failure");
        try {
            request(soi, "{}", "json", new String[1]);
            fail("Expected delegate exception");
        } catch (IOException expected) { assertSame(soi.failure, expected); }
    }

    @Test
    public void positiveGeometryThresholdEnablesGuardIncludingLayerOverride() throws Exception {
        String input = "{\"where\":\"1=1\",\"geometry\":\"0,0,100,100\"}";
        Harness off = harness("mode", "enforce", "geometryGuardMaxEnvelopeArea", "0");
        assertSame(off.delegateResponse, request(off, input, "json", new String[1]));
        for (Harness soi : Arrays.asList(
                harness("mode", "enforce", "geometryGuardMaxEnvelopeArea", "100", "geometryGuardEnabled", "false"),
                harness("mode", "enforce", "layerPolicyOverrides", "0.geometryGuardMaxEnvelopeArea=100"))) {
            request(soi, input, "json", new String[1]);
            assertEquals(0, soi.calls);
        }
        Harness audit = harness("mode", "audit", "geometryGuardMaxEnvelopeArea", "100");
        assertSame(audit.delegateResponse, request(audit, input, "json", new String[1]));
        assertEquals(input, audit.effectiveInput);
    }

    private static String riskLine(Harness soi) {
        String[] events = soi.logs.toString().lines().filter(line -> line.contains("[GeoVelis][RISK]")).toArray(String[]::new);
        assertEquals("Expected exactly one risk event", 1, events.length);
        return events[0];
    }

    @Test
    public void redisBlockStopsDelegateAndReturns429WithJsonHeaders() throws Exception {
        Harness soi=harness("mode","enforce","riskEnabled","true");
        soi.blockResult=it.giancagis.arcgis.geovelis.behavior.BehaviorBlockGate.Result.active("test-key",20000,true);
        String[] headers={"{\"Content-Type\":\"application/x-protobuf\"}"};
        JSONObject body=new JSONObject(new String(request(soi,"{}","pbf",headers),StandardCharsets.UTF_8));
        assertEquals(429,body.getJSONObject("error").optInt("code"));
        assertEquals(0,soi.calls);
        assertTrue(headers[0].contains("application/json"));
        assertTrue(soi.logs.toString().contains("[BEHAVIOR_BLOCK] decision=BLOCK"));
        assertTrue(riskLine(soi).contains("outcome=BEHAVIOR_BLOCKED"));
    }

    @Test
    public void redisAuditDecisionPreservesResponseAndCallsDelegate() throws Exception {
        Harness soi=harness("mode","audit","riskEnabled","true");
        soi.blockResult=it.giancagis.arcgis.geovelis.behavior.BehaviorBlockGate.Result.active("test-key",20000,false);
        assertSame(soi.delegateResponse,request(soi,"{}","json",new String[1]));
        assertEquals(1,soi.calls);
        assertTrue(soi.logs.toString().contains("[BEHAVIOR_BLOCK] decision=AUDIT_ONLY"));
    }

    static Harness harness(String... values) throws Exception {
        Harness soi = new Harness();
        soi.construct(properties(values));
        ILog log = (ILog) Proxy.newProxyInstance(ILog.class.getClassLoader(), new Class<?>[]{ILog.class},
                (proxy, method, args) -> {
                    if ("addMessage".equals(method.getName())) {
                        soi.logs.append(args[2]).append('\n');
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        Field logger = GeoVelisSOI.class.getDeclaredField("auditLogger");
        logger.setAccessible(true);
        logger.set(soi, new ArcGisServerAuditLogger(log));
        return soi;
    }

    static IPropertySet properties(String... values) {
        Map<String, String> properties = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) properties.put(values[i], values[i + 1]);
        return (IPropertySet) Proxy.newProxyInstance(IPropertySet.class.getClassLoader(), new Class<?>[]{IPropertySet.class},
                (proxy, method, args) -> {
                    if ("getProperty".equals(method.getName())) return properties.get(args[0]);
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    static byte[] request(Harness soi, String input, String format, String[] headers) throws Exception {
        return soi.handleRESTRequest("Query", "0", "query", input, format, "{}", headers);
    }

    static class Harness extends GeoVelisSOI {
        int calls;
        String effectiveInput;
        String delegateHeaders = HEADERS;
        byte[] delegateResponse = BODY.getBytes(StandardCharsets.UTF_8);
        IOException failure;
        boolean missingDelegate;
        String user = "test-user";
        it.giancagis.arcgis.geovelis.behavior.BehaviorBlockGate.Result blockResult;

        @Override it.giancagis.arcgis.geovelis.behavior.BehaviorBlockGate.Result checkBehaviorBlock(
                it.giancagis.arcgis.geovelis.rest.RestRequestContext ctx) {
            return blockResult == null ? super.checkBehaviorBlock(ctx) : blockResult;
        }
        final StringBuilder logs = new StringBuilder();

        @Override String getCurrentUserNameSafe() { return user; }

        @Override IRESTRequestHandler findRestRequestHandlerDelegate() {
            if (missingDelegate) return null;
            return (IRESTRequestHandler) Proxy.newProxyInstance(IRESTRequestHandler.class.getClassLoader(),
                    new Class<?>[]{IRESTRequestHandler.class}, (proxy, method, args) -> {
                        if ("handleRESTRequest".equals(method.getName())) {
                            calls++;
                            if (failure != null) throw failure;
                            effectiveInput = (String) args[3];
                            String[] headers = (String[]) args[6];
                            if (headers != null && headers.length > 0) headers[0] = delegateHeaders;
                            return delegateResponse;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
