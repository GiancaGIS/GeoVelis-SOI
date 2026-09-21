package it.giancagis.arcgis.geovelis;

import it.giancagis.arcgis.geovelis.config.GeoVelisConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConfigValidationTest {
    @Test public void absentPropertiesRetainDefaultsAndSupportedValuesAreAccepted() throws Exception {
        assertTrue(GeoVelisConfig.fromPropertySet(RestPipelineTest.properties()).isAuditMode());
        GeoVelisConfig config = GeoVelisConfig.fromPropertySet(RestPipelineTest.properties(
                "mode", " ENFORCE ", "responseMaskingFailPolicy", "BLOCK", "fieldGuardianEnabled", "yes",
                "geometryGuardMaxEnvelopeArea", "0", "layerPolicyOverrides", "0.maxRecordCount=10;0.geometryGuardMaxEnvelopeArea=1",
                "responseMaskingStrategy", "partialEmail", "responseMaskingFieldStrategies", "EMAIL:hash,PHONE:nullify"));
        assertTrue(config.isEnforceMode());
        assertTrue(config.isResponseMaskingFailPolicyBlock());
        assertTrue(config.isFieldGuardianEnabled());
        assertEquals(10, config.getMaxRecordCount("0"));
        assertEquals(1.0, config.getGeometryGuardMaxEnvelopeArea("0"), 0.0);
    }

    @Test public void invalidScalarSettingsAreRejectedWithoutEchoingTheirValues() throws Exception {
        String[][] invalid = {{"mode", "enfroce"}, {"responseMaskingFailPolicy", "blok"},
                {"fieldGuardianEnabled", "tru"}, {"maxRecordCount", "abc"}, {"maxRecordCount", "0"},
                {"maxLoggedInputLength", "-1"}, {"fileAuditRetentionDays", "0"},
                {"geometryGuardMaxEnvelopeArea", "NaN"}, {"geometryGuardMaxEnvelopeArea", "Infinity"},
                {"geometryGuardMaxEnvelopeArea", "-1"}, {"responseMaskingStrategy", "invalid-strategy"},
                {"responseMaskingFieldStrategies", "EMAIL:invalid-strategy"},
                {"responseMaskingFieldStrategies", "EMAIL"}, {"responseMaskingFieldStrategies", "EMAIL:"}};
        for (String[] pair : invalid) {
            try {
                GeoVelisConfig.fromPropertySet(RestPipelineTest.properties(pair));
                fail("Accepted invalid property " + pair[0]);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains(pair[0]));
            }
        }
        try {
            GeoVelisConfig.fromPropertySet(RestPipelineTest.properties("mode", "private@example.test"));
            fail("Accepted invalid mode");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().contains("private@example.test"));
        }
    }

    @Test public void malformedOrUnknownLayerOverridesAreRejected() throws Exception {
        for (String value : new String[]{"0.maxRecordCount", "maxRecordCount=10", "x.maxRecordCount=10",
                "0.maxRecordCount=0", "0.maxRecordCount=abc", "0.blockOutFieldsStar=tru",
                "0.geometryGuardMaxEnvelopeArea=NaN", "0.geometryGuardMaxEnvelopeArea=Infinity",
                "0.geometryGuardMaxEnvelopeArea=-1", "0.unsupported=10", "0.responseMaskingFields=|"}) {
            try {
                GeoVelisConfig.fromPropertySet(RestPipelineTest.properties("layerPolicyOverrides", value));
                fail("Accepted invalid override " + value);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("layerPolicyOverrides"));
            }
        }
    }
}
