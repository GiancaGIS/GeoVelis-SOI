package it.giancagis.arcgis.geovelis.rules;

import com.esri.arcgis.server.json.JSONObject;
import it.giancagis.arcgis.geovelis.config.GeoVelisConfig;
import it.giancagis.arcgis.geovelis.rest.RestRequestContext;
import it.giancagis.arcgis.geovelis.util.JsonUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Controls requested outFields for REST query operations.
 */
public final class FieldGuardianRule implements Rule {
    private final GeoVelisConfig config;

    public FieldGuardianRule(GeoVelisConfig config) {
        this.config = config;
    }

    @Override
    public RuleResult before(RestRequestContext ctx) {
        if (!config.isFieldGuardianEnabled() || !ctx.isOperation("query")) {
            return RuleResult.allow();
        }

        List<String> allowedFields = config.getFieldGuardianAllowedOutFields();
        List<String> deniedFields = config.getFieldGuardianDeniedOutFields();
        if (allowedFields.isEmpty() && deniedFields.isEmpty()) {
            return RuleResult.allow();
        }

        JSONObject input = JsonUtils.parseObjectOrEmpty(ctx.operationInput());
        String outFields = JsonUtils.optString(input, "outFields", "");
        if (outFields.trim().isEmpty()) {
            return RuleResult.allow();
        }

        if (isAllFields(outFields)) {
            return handleAllFields(input, allowedFields, deniedFields);
        }

        List<String> requestedFields = parseOutFields(outFields);
        List<String> disallowedFields = findDisallowedFields(requestedFields, allowedFields, deniedFields);
        if (disallowedFields.isEmpty()) {
            return RuleResult.allow();
        }

        if (config.isAuditMode()) {
            return RuleResult.warning("Field Guardian: disallowed fields requested in outFields: "
                    + disallowedFields.size() + ". Audit mode: request allowed.");
        }

        if (config.isFieldGuardianBlockOnDeniedFields()) {
            return RuleResult.block("Field Guardian: disallowed fields requested in outFields: "
                    + disallowedFields.size() + ".");
        }

        List<String> sanitizedFields = sanitizeRequestedFields(requestedFields, allowedFields, deniedFields);
        if (sanitizedFields.isEmpty()) {
            return RuleResult.block("Field Guardian: no allowed fields remain in the requested outFields.");
        }

        return replaceOutFields(
                input,
                sanitizedFields,
                "Field Guardian: disallowed fields removed from outFields: "
                        + disallowedFields.size() + "."
        );
    }

    private RuleResult handleAllFields(JSONObject input, List<String> allowedFields, List<String> deniedFields) {
        if (!allowedFields.isEmpty()) {
            List<String> sanitizedAllowedFields = sanitizeRequestedFields(allowedFields, allowedFields, deniedFields);
            if (sanitizedAllowedFields.isEmpty()) {
                return config.isEnforceMode()
                        ? RuleResult.block("Field Guardian: outFields allowlist is empty after applying the denylist.")
                        : RuleResult.warning("Field Guardian: outFields=* requests disallowed fields. Audit mode: request allowed.");
            }

            if (config.isAuditMode()) {
                return RuleResult.warning("Field Guardian: outFields=* would be restricted to the configured allowlist. Audit mode: request allowed.");
            }

            return replaceOutFields(
                    input,
                    sanitizedAllowedFields,
                    "Field Guardian: outFields=* replaced with the configured allowlist."
            );
        }

        if (!deniedFields.isEmpty()) {
            String message = "Field Guardian: outFields=* is not allowed when a denylist is configured without an allowlist.";
            return config.isEnforceMode()
                    ? RuleResult.block(message)
                    : RuleResult.warning(message + " Audit mode: request allowed.");
        }

        return RuleResult.allow();
    }

    private RuleResult replaceOutFields(JSONObject input, List<String> fields, String message) {
        try {
            input.put("outFields", String.join(",", fields));
            return RuleResult.allowWithModifiedInput(input.toString(), message);
        } catch (Exception ex) {
            return RuleResult.warning("Field Guardian: unable to modify outFields (" + ex.getClass().getSimpleName() + ").");
        }
    }

    private List<String> sanitizeRequestedFields(List<String> requestedFields, List<String> allowedFields, List<String> deniedFields) {
        Set<String> allowed = lowerCaseSet(allowedFields);
        Set<String> denied = lowerCaseSet(deniedFields);
        List<String> sanitized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String field : requestedFields) {
            String normalized = normalize(field);
            if (normalized.isEmpty()) {
                continue;
            }
            if (!allowed.isEmpty() && !allowed.contains(normalized)) {
                continue;
            }
            if (denied.contains(normalized)) {
                continue;
            }
            if (seen.add(normalized)) {
                sanitized.add(field.trim());
            }
        }
        return sanitized;
    }

    private List<String> findDisallowedFields(List<String> requestedFields, List<String> allowedFields, List<String> deniedFields) {
        Set<String> allowed = lowerCaseSet(allowedFields);
        Set<String> denied = lowerCaseSet(deniedFields);
        List<String> disallowed = new ArrayList<>();

        for (String field : requestedFields) {
            String normalized = normalize(field);
            if (normalized.isEmpty()) {
                continue;
            }
            if ((!allowed.isEmpty() && !allowed.contains(normalized)) || denied.contains(normalized)) {
                disallowed.add(field.trim());
            }
        }
        return disallowed;
    }

    private List<String> parseOutFields(String outFields) {
        String[] parts = outFields.split(",");
        List<String> fields = new ArrayList<>();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                fields.add(trimmed);
            }
        }
        return fields;
    }

    private Set<String> lowerCaseSet(List<String> fields) {
        Set<String> values = new LinkedHashSet<>();
        for (String field : fields) {
            String normalized = normalize(field);
            if (!normalized.isEmpty()) {
                values.add(normalized);
            }
        }
        return values;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isAllFields(String outFields) {
        return outFields != null && "*".equals(outFields.trim());
    }
}
