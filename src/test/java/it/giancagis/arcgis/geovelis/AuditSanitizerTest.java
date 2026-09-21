package it.giancagis.arcgis.geovelis;

import com.esri.arcgis.server.json.JSONObject;
import it.giancagis.arcgis.geovelis.util.AuditSanitizer;
import it.giancagis.arcgis.geovelis.audit.FileAuditLogger;
import it.giancagis.arcgis.geovelis.rest.RestRequestContext;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.stream.Stream;
import static org.junit.Assert.*;

public class AuditSanitizerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void onlyOperationalScalarsAreVisible() throws Exception {
        String input = "{\"WHERE\":\"EMAIL='private@example.test'\",\"token\":\"secret-token\","
                + "\"geometry\":{\"secret\":\"private@example.test\"},\"returnGeometry\":true,"
                + "\"resultRecordCount\":\"20\",\"private@example.test\":\"unknown\"}";
        String result = AuditSanitizer.operationInput(input);
        assertFalse(result.contains("private@example.test"));
        assertFalse(result.contains("secret-token"));
        JSONObject safe = new JSONObject(result);
        assertEquals(Boolean.TRUE, safe.opt("returngeometry"));
        assertEquals(20, safe.optInt("resultrecordcount"));
        assertEquals(1, safe.optInt("otherParameterCount"));
        assertEquals("[REDACTED]", safe.opt("where"));
    }

    @Test public void malformedAndUnexpectedControlValuesNeverLeak() {
        for (String input : new String[]{"{\"where\":\"private@example.test", "private@example.test",
                "{\"returnGeometry\":\"private@example.test\",\"resultRecordCount\":{\"EMAIL\":\"private@example.test\"}}"}) {
            assertFalse(AuditSanitizer.operationInput(input).contains("private@example.test"));
        }
    }

    @Test public void riskEventReachesFileEvenWhenAnotherSinkFails() throws Exception {
        Path directory = temporary.newFolder().toPath();
        FileAuditLogger file = new FileAuditLogger(directory.toString(), "test", 30);
        Class<?> type = it.giancagis.arcgis.geovelis.audit.AuditLogger.class;
        it.giancagis.arcgis.geovelis.audit.AuditLogger failing =
                (it.giancagis.arcgis.geovelis.audit.AuditLogger) java.lang.reflect.Proxy.newProxyInstance(
                        type.getClassLoader(), new Class<?>[]{type}, (p, m, args) -> { throw new IllegalStateException("sink failure"); });
        it.giancagis.arcgis.geovelis.risk.RiskAnalyzer analyzer = new it.giancagis.arcgis.geovelis.risk.RiskAnalyzer(
                it.giancagis.arcgis.geovelis.risk.RiskConfig.fromPropertySet(RestPipelineTest.properties("riskEnabled", "true")));
        it.giancagis.arcgis.geovelis.risk.RiskAssessment assessment = analyzer.observe(
                new RestRequestContext("private-user", "", "0", "query", "{}", "json", ""), 20, 1,
                it.giancagis.arcgis.geovelis.risk.RiskAssessment.Outcome.RETURNED);
        new it.giancagis.arcgis.geovelis.audit.CompositeAuditLogger(java.util.Arrays.asList(failing, file)).risk(assessment);
        try (Stream<Path> files = Files.list(directory)) {
            String text = new String(Files.readAllBytes(files.findFirst().get()), StandardCharsets.UTF_8);
            assertTrue(text.contains(assessment.logMessage()));
            assertFalse(text.contains("private-user"));
        }
    }

    @Test public void fileLoggerDoesNotWriteDelegateExceptionMessage() throws Exception {
        Path directory = temporary.newFolder().toPath();
        FileAuditLogger logger = new FileAuditLogger(directory.toString(), "test", 30);
        RestRequestContext ctx = new RestRequestContext("test", "", "0", "query", "{}", "json", "");
        logger.error(ctx, new IOException("private@example.test"), 1);
        try (Stream<Path> files = Files.list(directory)) {
            Path log = files.findFirst().get();
            String text = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            assertTrue(text.contains("IOException"));
            assertFalse(text.contains("private@example.test"));
        }
    }
}
