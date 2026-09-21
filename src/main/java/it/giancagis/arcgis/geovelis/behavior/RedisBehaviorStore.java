package it.giancagis.arcgis.geovelis.behavior;

import it.giancagis.arcgis.geovelis.risk.RiskConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Java behavior engine backed by one atomic Redis script, with optional expiring decisions. */
final class RedisBehaviorStore implements AutoCloseable {
    private final BehaviorConfig config;
    private final RiskConfig risk;
    private final String script;
    private RedisConnection connection;
    private String scriptHash;
    private final boolean enforce;

    RedisBehaviorStore(BehaviorConfig config, RiskConfig risk) {
        this(config, risk, loadScript());
    }
    RedisBehaviorStore(BehaviorConfig config, RiskConfig risk, String script) {
        this(config, risk, script, false);
    }
    RedisBehaviorStore(BehaviorConfig config, RiskConfig risk, String script, boolean enforce) {
        this.config = config; this.risk = risk; this.script = script;
        this.enforce = enforce;
    }
    static String loadScript() {
        try (InputStream input = RedisBehaviorStore.class.getResourceAsStream("observe.lua")) {
            if (input == null) throw new IllegalStateException("Redis behavior script missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) { throw new IllegalStateException("Redis behavior script unreadable", ex); }
    }
    String observe(BehaviorEvent event) throws IOException {
        try {
            if (connection == null) {
                connection = new RedisConnection(config);
                scriptHash = (String) connection.command("SCRIPT", "LOAD", script);
            }
            long recordLimit = "sensitive".equals(risk.profile()) ? 10000 : "conservative".equals(risk.profile()) ? 100000 : 50000;
            String blockKey = BehaviorEvent.blockKey(event.key);
            String[] command = {"EVALSHA", scriptHash, "2", event.key, blockKey,
                    event.id, event.query, event.batch, Long.toString(event.offset),
                    Long.toString(event.requestedRecords), Long.toString(event.effectiveRecords),
                    Long.toString(event.responseBytes), Long.toString(event.objectIdCount),
                    event.broadIds ? "1" : "0", event.returned ? "1" : "0",
                    Integer.toString(risk.requestThreshold()), Integer.toString(risk.pagingThreshold()),
                    Long.toString(recordLimit), Long.toString(risk.responseBytesThreshold()),
                    Integer.toString(config.blockThreshold()), enforce ? "1" : "0"};
            Object reply;
            try { reply = connection.command(command); }
            catch (RedisConnection.MissingScriptException ex) {
                // NOSCRIPT guarantees that the observation did not run. Other errors are never retried.
                scriptHash = (String) connection.command("SCRIPT", "LOAD", script);
                command[1] = scriptHash;
                reply = connection.command(command);
            }
            if (!(reply instanceof List)) throw new IOException("Invalid behavior reply");
            List<?> values = (List<?>) reply;
            if (values.size() == 1 && "DUPLICATE".equals(values.get(0))) return null;
            if (values.size() != 14 || !"OK".equals(values.get(0))) throw new IOException("Invalid behavior result");
            return "[GeoVelis][BEHAVIOR] scoreVersion=1, decision=" + values.get(13) + ", scope=REDIS_SERVICE_LAYER"
                    + ", blockThreshold=" + config.blockThreshold() + ", blockKey=" + blockKey
                    + ", profile=" + risk.profile() + ", eventId=" + event.id + ", subject=" + event.subject
                    + ", layer=" + event.layer + ", serviceScope=" + BehaviorEvent.hmac(config.secret(), "service:" + config.serviceKey())
                    + ", requestRiskScore=" + event.requestRisk + ", requestContributions=" + event.signals
                    + ", behaviorRiskScore=" + values.get(1) + ", reasons=" + values.get(2)
                    + ", windowSeconds=60, windowPrecisionSeconds=1, windowRequests=" + values.get(3)
                    + ", windowRequestedRecords=" + values.get(4) + ", windowResponseBytes=" + values.get(5)
                    + ", sequentialTransitions=" + values.get(6) + ", distinctIdBatches=" + values.get(7)
                    + ", session=" + values.get(8) + ", sessionPeakBehaviorRisk=" + values.get(9)
                    + ", sessionRequests=" + values.get(10) + ", nonIncreasingPage=" + values.get(11)
                    + ", observedAtRedisSeconds=" + values.get(12) + ", outcome=" + event.outcome
                    + ", inputStatus=" + event.inputStatus + ", requestedRecords=" + event.requestedRecords
                    + ", effectiveRecords=" + event.effectiveRecords + ", objectIdCount=" + event.objectIdCount;
        } catch (Exception ex) {
            close();
            throw new IOException("Redis behavior observation failed (" + ex.getClass().getSimpleName() + ")");
        }
    }
    @Override public void close() {
        if (connection != null) try { connection.close(); } catch (IOException ignored) { }
        connection = null;
    }
}
