package it.giancagis.arcgis.geovelis;

import com.esri.arcgis.system.IPropertySet;
import com.esri.arcgis.server.json.JSONObject;
import it.giancagis.arcgis.geovelis.config.GeoVelisConfig;
import it.giancagis.arcgis.geovelis.masking.ResponseMasker;
import it.giancagis.arcgis.geovelis.masking.ResponseMaskingResult;
import it.giancagis.arcgis.geovelis.rest.RestRequestContext;
import it.giancagis.arcgis.geovelis.rules.QueryGuardianRule;
import it.giancagis.arcgis.geovelis.rules.FieldGuardianRule;
import it.giancagis.arcgis.geovelis.rules.RuleEngine;
import it.giancagis.arcgis.geovelis.rules.RuleResult;
import it.giancagis.arcgis.geovelis.util.RestErrorResponse;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Diagnostic regression cases: assertions express the intended protection.
 * Runs the real SDK JSON implementation without requiring an ArcGIS Server.
 * IPropertySet is replaced only at the configuration boundary.
 */
public class FilterVerificationTest {
    private static final String JSON_RESPONSE =
            "{\"features\":[{\"attributes\":{\"OBJECTID\":1,\"EMAIL\":\"private@example.test\"}}]}";
    private static final String GEOJSON_RESPONSE =
            "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\","
                    + "\"geometry\":null,\"properties\":{\"OBJECTID\":1,\"EMAIL\":\"private@example.test\"}}]}";

    @Test
    public void auditMustNotClampRecordCount() throws Exception {
        QueryGuardianRule rule = new QueryGuardianRule(config("mode", "audit", "maxRecordCount", "10"));
        RuleResult result = rule.before(context("{\"resultRecordCount\":100}", "json"));
        assertFalse("Audit changed the request: " + result.modifiedOperationInput(),
                result.modifiedOperationInput().isPresent());
    }

    @Test
    public void enforceClampsExplicitRecordCount() throws Exception {
        QueryGuardianRule rule = new QueryGuardianRule(config("mode", "enforce", "maxRecordCount", "10"));
        RuleResult result = rule.before(context("{\"resultRecordCount\":100}", "json"));
        assertTrue(result.modifiedOperationInput().isPresent());
        assertTrue(result.modifiedOperationInput().get().contains("\"resultRecordCount\":10"));
    }

    @Test
    public void broadWhereWithExplicitGeometryIsNoLongerBlocked() throws Exception {
        QueryGuardianRule rule = new QueryGuardianRule(config(
                "mode", "enforce", "blockWhereOneEqualsOne", "true"));
        RuleResult result = rule.before(context("{\"where\":\"1=1\",\"returnGeometry\":true}", "json"));
        assertFalse(result.isBlocked());
    }

    @Test
    public void broadWhereWithOmittedGeometryIsNoLongerBlocked() throws Exception {
        QueryGuardianRule rule = new QueryGuardianRule(config(
                "mode", "enforce", "blockWhereOneEqualsOne", "true"));
        RuleResult result = rule.before(context("{\"where\":\"1=1\"}", "json"));
        assertFalse(result.isBlocked());
    }

    @Test
    public void enforceMasksEsriJson() throws Exception {
        ResponseMasker masker = new ResponseMasker(maskingConfig("enforce"));
        ResponseMaskingResult result = masker.process(context("{}", "json"), bytes(JSON_RESPONSE));
        assertEquals(ResponseMaskingResult.Decision.MASKED, result.decision());
        assertFalse(text(result).contains("private@example.test"));
        assertTrue(text(result).contains("****"));
    }

    @Test
    public void strictMaskingMustNotReturnUnmaskedGeoJson() throws Exception {
        ResponseMasker masker = new ResponseMasker(maskingConfig("enforce"));
        ResponseMaskingResult result = masker.process(context("{}", "geojson"), bytes(GEOJSON_RESPONSE));
        assertEquals(ResponseMaskingResult.Decision.MASKED, result.decision());
        assertEquals(1, result.maskedAttributeCount());
        assertFalse("Sensitive GeoJSON returned with decision " + result.decision(),
                text(result).contains("private@example.test"));
    }

    @Test
    public void auditPreservesValidResponse() throws Exception {
        ResponseMasker masker = new ResponseMasker(maskingConfig("audit"));
        ResponseMaskingResult result = masker.process(context("{}", "json"), bytes(JSON_RESPONSE));
        assertEquals(ResponseMaskingResult.Decision.AUDIT_ONLY, result.decision());
        assertEquals(JSON_RESPONSE, text(result));
    }

    @Test
    public void auditMustPreserveResponseEvenIfMaskingParseFails() throws Exception {
        ResponseMasker masker = new ResponseMasker(maskingConfig("audit"));
        String invalidJson = "{\"features\":}";
        ResponseMaskingResult result = masker.process(context("{}", "json"), bytes(invalidJson));
        assertEquals("Audit replaced the response with " + result.decision(), invalidJson, text(result));
    }

    @Test
    public void explicitFalseAndSummaryQueriesAreNotGeometryQueries() throws Exception {
        QueryGuardianRule rule = new QueryGuardianRule(config("mode", "enforce",
                "blockWhereOneEqualsOne", "true", "blockGenericGeometryQuery", "true"));
        for (String option : Arrays.asList("\"returnGeometry\":false", "\"returnCountOnly\":true",
                "\"returnIdsOnly\":true", "\"returnExtentOnly\":true",
                "\"outStatistics\":[{\"statisticType\":\"count\",\"onStatisticField\":\"OBJECTID\","
                        + "\"outStatisticFieldName\":\"total\"}]")) {
            assertFalse(option, rule.before(context("{\"where\":\"1=1\"," + option + "}", "json")).isBlocked());
        }
    }

    @Test
    public void statisticsDoNotTriggerRemovedBroadQueryBlocks() throws Exception {
        QueryGuardianRule rule = new QueryGuardianRule(config("mode", "enforce", "blockGenericGeometryQuery", "true"));
        for (String value : Arrays.asList("[]", "null", "\"invalid\"")) {
            assertFalse(value, rule.before(context("{\"outStatistics\":" + value + "}", "json")).isBlocked());
        }
    }

    @Test
    public void genericQueriesAreAllowedWithOrWithoutSpatialFilter() throws Exception {
        QueryGuardianRule rule = new QueryGuardianRule(config("mode", "enforce", "blockGenericGeometryQuery", "true"));
        assertFalse(rule.before(context("{}", "json")).isBlocked());
        assertFalse(rule.before(context("{\"geometry\":\"0,0,1,1\"}", "json")).isBlocked());
    }

    @Test
    public void auditNeverChangesChainedFieldAndCountRules() throws Exception {
        GeoVelisConfig cfg = config("mode", "audit", "maxRecordCount", "10", "fieldGuardianEnabled", "true",
                "fieldGuardianDeniedOutFields", "EMAIL");
        RuleEngine engine = new RuleEngine(Arrays.asList(new FieldGuardianRule(cfg), new QueryGuardianRule(cfg)));
        RuleResult result = engine.before(context("{\"outFields\":\"OBJECTID,EMAIL\",\"resultRecordCount\":100}", "json"));
        assertFalse(result.isBlocked());
        assertFalse(result.modifiedOperationInput().isPresent());
    }

    @Test
    public void enforceChainsFieldRemovalAndLayerCountLimit() throws Exception {
        GeoVelisConfig cfg = config("mode", "enforce", "maxRecordCount", "50", "fieldGuardianEnabled", "true",
                "fieldGuardianDeniedOutFields", "EMAIL", "layerPolicyOverrides", "0.maxRecordCount=10");
        RuleEngine engine = new RuleEngine(Arrays.asList(new FieldGuardianRule(cfg), new QueryGuardianRule(cfg)));
        RuleResult result = engine.before(context("{\"outFields\":\"OBJECTID,EMAIL\",\"resultRecordCount\":100}", "json"));
        JSONObject input = new JSONObject(result.modifiedOperationInput().get());
        assertEquals("OBJECTID", input.opt("outFields"));
        assertEquals(10, input.optInt("resultRecordCount"));
    }

    @Test
    public void recordLimitAppliesDespiteLegacyToggleAndHonorsBoundary() throws Exception {
        QueryGuardianRule disabled = new QueryGuardianRule(config("mode", "enforce", "maxRecordCount", "10",
                "autoClampResultRecordCount", "false"));
        assertTrue(disabled.before(context("{\"resultRecordCount\":100}", "json")).modifiedOperationInput().isPresent());
        QueryGuardianRule enabled = new QueryGuardianRule(config("mode", "enforce", "maxRecordCount", "10"));
        assertFalse(enabled.before(context("{\"resultRecordCount\":10}", "json")).modifiedOperationInput().isPresent());
    }

    @Test
    public void geoJsonPreservesGeometryAndUsesFieldStrategyCaseInsensitively() throws Exception {
        ResponseMasker masker = new ResponseMasker(config("mode", "enforce", "responseMaskingEnabled", "true",
                "responseMaskingFields", "email", "responseMaskingFieldStrategies", "EMAIL:partialEmail"));
        String response = GEOJSON_RESPONSE.replace("\"geometry\":null",
                "\"geometry\":{\"type\":\"Point\",\"coordinates\":[12,45]}");
        ResponseMaskingResult result = masker.process(context("{}", "geojson"), bytes(response));
        JSONObject feature = new JSONObject(text(result)).getJSONArray("features").getJSONObject(0);
        assertEquals("p***@example.test", feature.getJSONObject("properties").opt("EMAIL"));
        assertEquals(1, feature.getJSONObject("properties").optInt("OBJECTID"));
        assertEquals("[12,45]", feature.getJSONObject("geometry").getJSONArray("coordinates").toString());
    }

    @Test
    public void auditPreservesGeoJsonBytesWhileReportingSensitiveFields() throws Exception {
        byte[] original = bytes(" \n" + GEOJSON_RESPONSE + "\n");
        ResponseMaskingResult result = new ResponseMasker(maskingConfig("audit"))
                .process(context("{}", "geojson"), original);
        assertEquals(ResponseMaskingResult.Decision.AUDIT_ONLY, result.decision());
        assertSame(original, result.response());
        assertEquals(1, result.maskedAttributeCount());
    }

    @Test
    public void unsupportedFormatsFollowFailPolicyInEnforce() throws Exception {
        ResponseMasker strict = new ResponseMasker(maskingConfig("enforce"));
        ResponseMasker permissive = new ResponseMasker(config("mode", "enforce", "responseMaskingEnabled", "true",
                "responseMaskingFailPolicy", "keepOriginal"));
        for (String format : Arrays.asList("pbf", "html", "kmz")) {
            byte[] original = new byte[]{0, 1, 2, 3};
            assertEquals(ResponseMaskingResult.Decision.BLOCKED_ON_ERROR,
                    strict.process(context("{}", format), original).decision());
            ResponseMaskingResult preserved = permissive.process(context("{}", format), original);
            assertEquals(ResponseMaskingResult.Decision.ERROR_KEEP_ORIGINAL, preserved.decision());
            assertSame(original, preserved.response());
        }
    }

    @Test
    public void auditNeverBlocksUnsupportedFormats() throws Exception {
        byte[] original = new byte[]{0, 1, 2, 3};
        ResponseMaskingResult result = new ResponseMasker(maskingConfig("audit"))
                .process(context("{}", "pbf"), original);
        assertEquals(ResponseMaskingResult.Decision.ERROR_KEEP_ORIGINAL, result.decision());
        assertSame(original, result.response());
    }

    @Test
    public void truncatedJsonIsBlockedWithoutLoggingResponseValues() throws Exception {
        ResponseMaskingResult result = new ResponseMasker(maskingConfig("enforce"))
                .process(context("{}", "json"), bytes("{\"features\":[\"private@example.test\""));
        assertEquals(ResponseMaskingResult.Decision.BLOCKED_ON_ERROR, result.decision());
        assertFalse(result.message().contains("private@example.test"));
        assertFalse(text(result).contains("private@example.test"));
    }

    @Test
    public void parserErrorMessageDoesNotLeakOriginalValues() throws Exception {
        ResponseMaskingResult result = new ResponseMasker(maskingConfig("audit"))
                .process(context("{}", "json"), bytes("{\"EMAIL\":\"private@example.test\",\"features\":}"));
        assertEquals(ResponseMaskingResult.Decision.ERROR_KEEP_ORIGINAL, result.decision());
        assertFalse(result.message().contains("private@example.test"));
    }

    @Test
    public void malformedFeatureCollectionsApplyFailPolicyWithoutPartialResponse() throws Exception {
        ResponseMasker masker = new ResponseMasker(maskingConfig("enforce"));
        for (String response : Arrays.asList("{\"features\":{}}", "{\"features\":[123]}",
                "{\"type\":\"FeatureCollection\"}", "{\"features\":[{\"attributes\":\"invalid\"}]}",
                "{\"features\":[{\"properties\":{\"EMAIL\":\"private@example.test\"}}]}",
                JSON_RESPONSE.replace("}]}", "},123]}"), "{\"records\":[{\"EMAIL\":\"private@example.test\"}]}")) {
            ResponseMaskingResult result = masker.process(context("{}", "json"), bytes(response));
            assertEquals(response, ResponseMaskingResult.Decision.BLOCKED_ON_ERROR, result.decision());
            assertFalse(text(result).contains("private@example.test"));
        }
    }

    @Test
    public void validResponsesWithoutSensitiveAttributesRemainUnchanged() throws Exception {
        ResponseMasker masker = new ResponseMasker(maskingConfig("enforce"));
        for (String response : Arrays.asList("{\"features\":[]}", "{\"count\":12}",
                "{\"objectIdFieldName\":\"OBJECTID\",\"objectIds\":[1,2]}", "{\"extent\":null}",
                "{\"extent\":{\"xmin\":0,\"ymin\":0,\"xmax\":1,\"ymax\":1}}",
                "{\"error\":{\"code\":400,\"message\":\"Invalid query\"}}",
                "{\"features\":[{\"geometry\":{\"x\":12,\"y\":45}}]}",
                "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":null,\"geometry\":null}]}")) {
            ResponseMaskingResult result = masker.process(context("{}", "json"), bytes(response));
            assertEquals(response, ResponseMaskingResult.Decision.NOT_APPLICABLE, result.decision());
            assertEquals(response, text(result));
        }
    }

    @Test
    public void disabledMaskingAndNonQueryScopePreserveResponse() throws Exception {
        byte[] original = bytes(JSON_RESPONSE);
        assertSame(original, new ResponseMasker(config("mode", "enforce"))
                .process(context("{}", "json"), original).response());
        RestRequestContext identify = new RestRequestContext("test", "", "", "identify", "{}", "json", "");
        assertEquals(ResponseMaskingResult.Decision.NOT_APPLICABLE,
                new ResponseMasker(maskingConfig("enforce")).process(identify, original).decision());
    }

    private static GeoVelisConfig maskingConfig(String mode) throws Exception {
        return config("mode", mode, "responseMaskingEnabled", "true",
                "responseMaskingFields", "EMAIL", "responseMaskingFailPolicy", "block");
    }

    @Test
    public void blockedResponseReplacesBinaryHeadersAndPreservesCors() throws Exception {
        String[] headers = {"{\"content-type\":\"application/x-protobuf\",\"Content-Length\":123,"
                + "\"Content-Encoding\":\"gzip\",\"ETag\":\"old\",\"Content-Disposition\":\"attachment\","
                + "\"Access-Control-Allow-Origin\":\"https://example.test\"}"};
        RestErrorResponse.setJsonResponseProperties(headers);
        JSONObject properties = new JSONObject(headers[0]);
        assertEquals("application/json; charset=utf-8", properties.opt("Content-Type"));
        assertEquals("https://example.test", properties.opt("Access-Control-Allow-Origin"));
        for (String removed : Arrays.asList("content-type", "Content-Length", "Content-Encoding", "ETag", "Content-Disposition")) {
            assertFalse(removed, properties.has(removed));
        }
    }

    @Test
    public void errorHeadersTolerateMissingOrInvalidResponseProperties() throws Exception {
        RestErrorResponse.setJsonResponseProperties(null);
        RestErrorResponse.setJsonResponseProperties(new String[0]);
        for (String previous : Arrays.asList(null, "", "invalid")) {
            String[] headers = {previous};
            RestErrorResponse.setJsonResponseProperties(headers);
            assertEquals("application/json; charset=utf-8", new JSONObject(headers[0]).opt("Content-Type"));
        }
    }

    private static GeoVelisConfig config(String... properties) throws Exception {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < properties.length; i += 2) {
            values.put(properties[i], properties[i + 1]);
        }
        IPropertySet propertySet = (IPropertySet) Proxy.newProxyInstance(
                IPropertySet.class.getClassLoader(), new Class<?>[]{IPropertySet.class},
                (proxy, method, args) -> {
                    if ("getProperty".equals(method.getName())) {
                        return values.get(args[0]);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return GeoVelisConfig.fromPropertySet(propertySet);
    }

    private static RestRequestContext context(String input, String format) {
        return new RestRequestContext("local-verification", "", "0", "query", input, format, "");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(ResponseMaskingResult result) {
        return new String(result.response(), StandardCharsets.UTF_8);
    }
}
