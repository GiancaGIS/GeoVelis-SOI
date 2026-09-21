package it.giancagis.arcgis.geovelis.audit;

import it.giancagis.arcgis.geovelis.masking.ResponseMaskingResult;
import it.giancagis.arcgis.geovelis.rest.RestRequestContext;
import it.giancagis.arcgis.geovelis.rules.RuleResult;
import it.giancagis.arcgis.geovelis.util.AuditSanitizer;
import it.giancagis.arcgis.geovelis.risk.RiskAssessment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Append-only file audit logger with daily files and simple retention cleanup.
 */
public final class FileAuditLogger implements AuditLogger {
    private static final long CLEANUP_INTERVAL_MS = 60L * 60L * 1000L;

    private final Path directory;
    private final String prefix;
    private final int retentionDays;
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE;

    private long lastCleanupMs = 0L;

    public FileAuditLogger(String directory, String prefix, int retentionDays) {
        this.directory = Paths.get(directory);
        this.prefix = prefix;
        this.retentionDays = retentionDays;
    }

    @Override
    public void requestIn(RestRequestContext ctx, String operationInputForLog) {
        write("[GeoVelis][IN] "
                + "user=" + ctx.userName()
                + ", operation=" + ctx.operationName()
                + ", resource=" + ctx.resourceName()
                + ", outputFormat=" + ctx.outputFormat()
                + ", operationInput=" + operationInputForLog);
    }

    @Override
    public void risk(RiskAssessment assessment) { write(assessment.logMessage()); }

    @Override
    public void rule(RestRequestContext ctx, RuleResult result) {
        if (result == null || !result.hasMessage()) {
            return;
        }

        write("[GeoVelis][RULE] "
                + "user=" + ctx.userName()
                + ", operation=" + ctx.operationName()
                + ", resource=" + ctx.resourceName()
                + ", decision=" + result.decision()
                + ", severity=" + result.severity()
                + ", message=" + result.message());
    }

    @Override
    public void responseMasking(RestRequestContext ctx, ResponseMaskingResult result) {
        if (result == null || !result.hasAction()) {
            return;
        }

        write("[GeoVelis][MASKING] "
                + "user=" + ctx.userName()
                + ", operation=" + ctx.operationName()
                + ", resource=" + ctx.resourceName()
                + ", decision=" + result.decision()
                + ", maskedFeatures=" + result.maskedFeatureCount()
                + ", maskedAttributes=" + result.maskedAttributeCount()
                + ", maskedFields=" + String.join(",", result.maskedFields())
                + ", message=" + result.message());
    }

    @Override
    public void responseOut(RestRequestContext ctx, long elapsedMs, int responseBytes) {
        write("[GeoVelis][OUT] "
                + "user=" + ctx.userName()
                + ", operation=" + ctx.operationName()
                + ", resource=" + ctx.resourceName()
                + ", elapsedMs=" + elapsedMs
                + ", responseBytes=" + responseBytes);
    }

    @Override
    public void error(RestRequestContext ctx, Throwable throwable, long elapsedMs) {
        write("[GeoVelis][ERROR] "
                + "user=" + ctx.userName()
                + ", operation=" + ctx.operationName()
                + ", resource=" + ctx.resourceName()
                + ", elapsedMs=" + elapsedMs
                + ", error=" + AuditSanitizer.errorType(throwable));
    }

    @Override
    public void info(String message) {
        write("[GeoVelis] " + message);
    }

    @Override
    public void warning(String message) {
        write("[GeoVelis][WARN] " + message);
    }

    private synchronized void write(String message) {
        try {
            Files.createDirectories(directory);
            cleanupIfNeeded();
            String line = Instant.now().toString() + " " + sanitize(message) + System.lineSeparator();
            Files.write(
                    currentLogFile(),
                    line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
        } catch (Exception ignored) {
            // Audit file failures must never break ArcGIS service requests.
        }
    }

    private Path currentLogFile() {
        String date = LocalDate.now(ZoneId.systemDefault()).format(dateFormatter);
        return directory.resolve(prefix + "-" + date + ".log");
    }

    private void cleanupIfNeeded() throws IOException {
        long now = System.currentTimeMillis();
        if (now - lastCleanupMs < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanupMs = now;

        long cutoffMs = now - (retentionDays * 24L * 60L * 60L * 1000L);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, prefix + "-*.log")) {
            for (Path path : stream) {
                try {
                    if (Files.isRegularFile(path) && Files.getLastModifiedTime(path).toMillis() < cutoffMs) {
                        Files.deleteIfExists(path);
                    }
                } catch (Exception ignored) {
                    // Keep cleanup best-effort.
                }
            }
        }
    }

    private String sanitize(String message) {
        if (message == null) {
            return "";
        }
        return message.replace('\r', ' ').replace('\n', ' ');
    }
}
