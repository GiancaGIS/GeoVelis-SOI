package it.giancagis.arcgis.geovelis.audit;

import com.esri.arcgis.system.ILog;
import it.giancagis.arcgis.geovelis.masking.ResponseMaskingResult;
import it.giancagis.arcgis.geovelis.rest.RestRequestContext;
import it.giancagis.arcgis.geovelis.rules.RuleResult;
import it.giancagis.arcgis.geovelis.util.AuditSanitizer;
import it.giancagis.arcgis.geovelis.risk.RiskAssessment;

/**
 * Audit logger backed by ArcGIS Server logs.
 */
public final class ArcGisServerAuditLogger implements AuditLogger {

    private static final int LOG_ERROR = 1;
    private static final int LOG_WARNING = 2;
    private static final int LOG_INFO = 3;

    private final ILog serverLog;

    public ArcGisServerAuditLogger(ILog serverLog) {
        this.serverLog = serverLog;
    }

    @Override
    public void requestIn(RestRequestContext ctx, String operationInputForLog) {
        add(LOG_INFO, 200,
                "[GeoVelis][IN] "
                        + "user=" + ctx.userName()
                        + ", operation=" + ctx.operationName()
                        + ", resource=" + ctx.resourceName()
                        + ", outputFormat=" + ctx.outputFormat()
                        + ", operationInput=" + operationInputForLog);
    }

    @Override
    public void risk(RiskAssessment assessment) {
        int peak = Math.max(assessment.score(), Math.max(assessment.userScore(), assessment.sessionScore()));
        add(peak >= 70 ? LOG_WARNING : LOG_INFO, 204, assessment.logMessage());
    }

    @Override
    public void rule(RestRequestContext ctx, RuleResult result) {
        if (result == null || !result.hasMessage()) {
            return;
        }

        int level = result.severity() == RuleResult.Severity.ERROR ? LOG_ERROR
                : result.severity() == RuleResult.Severity.WARNING ? LOG_WARNING
                : LOG_INFO;

        add(level, result.isBlocked() ? 403 : 202,
                "[GeoVelis][RULE] "
                        + "user=" + ctx.userName()
                        + ", operation=" + ctx.operationName()
                        + ", resource=" + ctx.resourceName()
                        + ", decision=" + result.decision()
                        + ", message=" + result.message());
    }

    @Override
    public void responseMasking(RestRequestContext ctx, ResponseMaskingResult result) {
        if (result == null || !result.hasAction()) {
            return;
        }

        int level = result.decision() == ResponseMaskingResult.Decision.BLOCKED_ON_ERROR ? LOG_ERROR
                : result.decision() == ResponseMaskingResult.Decision.ERROR_KEEP_ORIGINAL ? LOG_WARNING
                : result.decision() == ResponseMaskingResult.Decision.AUDIT_ONLY ? LOG_WARNING
                : LOG_INFO;

        add(level, 203,
                "[GeoVelis][MASKING] "
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
        add(LOG_INFO, 200,
                "[GeoVelis][OUT] "
                        + "user=" + ctx.userName()
                        + ", operation=" + ctx.operationName()
                        + ", resource=" + ctx.resourceName()
                        + ", elapsedMs=" + elapsedMs
                        + ", responseBytes=" + responseBytes);
    }

    @Override
    public void error(RestRequestContext ctx, Throwable throwable, long elapsedMs) {
        add(LOG_ERROR, 500,
                "[GeoVelis][ERROR] "
                        + "user=" + ctx.userName()
                        + ", operation=" + ctx.operationName()
                        + ", resource=" + ctx.resourceName()
                        + ", elapsedMs=" + elapsedMs
                        + ", error=" + AuditSanitizer.errorType(throwable));
    }

    @Override
    public void info(String message) {
        add(LOG_INFO, 200, "[GeoVelis] " + message);
    }

    @Override
    public void warning(String message) {
        add(LOG_WARNING, 202, "[GeoVelis][WARN] " + message);
    }

    private void add(int level, int code, String message) {
        try {
            if (serverLog != null) {
                serverLog.addMessage(level, code, message);
            }
        } catch (Exception ignored) {
            // Do not let audit logging break service requests.
        }
    }
}
