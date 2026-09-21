package it.giancagis.arcgis.geovelis.audit;

import it.giancagis.arcgis.geovelis.masking.ResponseMaskingResult;
import it.giancagis.arcgis.geovelis.rest.RestRequestContext;
import it.giancagis.arcgis.geovelis.rules.RuleResult;
import it.giancagis.arcgis.geovelis.risk.RiskAssessment;

public interface AuditLogger {
    void risk(RiskAssessment assessment);

    void requestIn(RestRequestContext ctx, String operationInputForLog);

    void rule(RestRequestContext ctx, RuleResult result);

    void responseMasking(RestRequestContext ctx, ResponseMaskingResult result);

    void responseOut(RestRequestContext ctx, long elapsedMs, int responseBytes);

    void error(RestRequestContext ctx, Throwable throwable, long elapsedMs);

    void info(String message);

    void warning(String message);
}
