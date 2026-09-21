package it.giancagis.arcgis.geovelis.rules;

import com.esri.arcgis.server.json.JSONObject;
import it.giancagis.arcgis.geovelis.config.GeoVelisConfig;
import it.giancagis.arcgis.geovelis.rest.RestRequestContext;
import it.giancagis.arcgis.geovelis.util.JsonUtils;

import java.util.Locale;

/**
 * Caps explicit resultRecordCount in enforce mode and logs broad query patterns.
 * Broad where clauses and wildcard fields alone never block a request here.
 */
public final class QueryGuardianRule implements Rule {
    private final GeoVelisConfig config;

    public QueryGuardianRule(GeoVelisConfig config) {
        this.config = config;
    }

    @Override
    public RuleResult before(RestRequestContext ctx) {
        if (!ctx.isOperation("query")) {
            return RuleResult.allow();
        }

        JSONObject input = JsonUtils.parseObjectOrEmpty(ctx.operationInput());

        String where = JsonUtils.optString(input, "where", "");
        String outFields = JsonUtils.optString(input, "outFields", "");
        int resultRecordCount = JsonUtils.optInt(input, "resultRecordCount", -1);
        String layerId = ctx.layerId();
        int maxRecordCount = config.getMaxRecordCount(layerId);

        if (resultRecordCount > maxRecordCount) {
            if (config.isAuditMode()) {
                return RuleResult.warning("resultRecordCount exceeds the threshold of " + maxRecordCount
                        + ". Audit mode: request left unchanged.");
            }
            try {
                input.put("resultRecordCount", maxRecordCount);
                return RuleResult.allowWithModifiedInput(
                        input.toString(),
                        "resultRecordCount automatically reduced from " + resultRecordCount + " to " + maxRecordCount + "."
                );
            } catch (Exception ex) {
                return RuleResult.warning("Unable to reduce resultRecordCount (" + ex.getClass().getSimpleName() + ").");
            }
        }

        if (isOneEqualsOne(where) || isAllFields(outFields)) {
            return RuleResult.warning("Potentially broad query detected: generic condition or outFields wildcard.");
        }

        return RuleResult.allow();
    }

    private boolean isAllFields(String outFields) {
        return outFields != null && "*".equals(outFields.trim());
    }

    private boolean isOneEqualsOne(String where) {
        if (where == null) {
            return false;
        }

        String normalized = where
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("(", "")
                .replace(")", "");

        return "1=1".equals(normalized)
                || "objectid=objectid".equals(normalized)
                || "objectid>0".equals(normalized)
                || "fid=fid".equals(normalized);
    }
}
