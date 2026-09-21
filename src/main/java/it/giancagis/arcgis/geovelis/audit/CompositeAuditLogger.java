package it.giancagis.arcgis.geovelis.audit;

import it.giancagis.arcgis.geovelis.masking.ResponseMaskingResult;
import it.giancagis.arcgis.geovelis.rest.RestRequestContext;
import it.giancagis.arcgis.geovelis.rules.RuleResult;
import it.giancagis.arcgis.geovelis.risk.RiskAssessment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sends each audit event to multiple loggers.
 */
public final class CompositeAuditLogger implements AuditLogger {
    private final List<AuditLogger> loggers;

    public CompositeAuditLogger(List<AuditLogger> loggers) {
        List<AuditLogger> validLoggers = new ArrayList<>();
        if (loggers != null) {
            for (AuditLogger logger : loggers) {
                if (logger != null) {
                    validLoggers.add(logger);
                }
            }
        }
        this.loggers = Collections.unmodifiableList(validLoggers);
    }

    @Override
    public void requestIn(RestRequestContext ctx, String operationInputForLog) {
        for (AuditLogger logger : loggers) {
            logger.requestIn(ctx, operationInputForLog);
        }
    }

    @Override
    public void risk(RiskAssessment assessment) {
        for (AuditLogger logger : loggers) {
            try { logger.risk(assessment); } catch (Exception ignored) { /* Other sinks must still receive telemetry. */ }
        }
    }

    @Override
    public void rule(RestRequestContext ctx, RuleResult result) {
        for (AuditLogger logger : loggers) {
            logger.rule(ctx, result);
        }
    }

    @Override
    public void responseMasking(RestRequestContext ctx, ResponseMaskingResult result) {
        for (AuditLogger logger : loggers) {
            logger.responseMasking(ctx, result);
        }
    }

    @Override
    public void responseOut(RestRequestContext ctx, long elapsedMs, int responseBytes) {
        for (AuditLogger logger : loggers) {
            logger.responseOut(ctx, elapsedMs, responseBytes);
        }
    }

    @Override
    public void error(RestRequestContext ctx, Throwable throwable, long elapsedMs) {
        for (AuditLogger logger : loggers) {
            logger.error(ctx, throwable, elapsedMs);
        }
    }

    @Override
    public void info(String message) {
        for (AuditLogger logger : loggers) {
            logger.info(message);
        }
    }

    @Override
    public void warning(String message) {
        for (AuditLogger logger : loggers) {
            logger.warning(message);
        }
    }
}
