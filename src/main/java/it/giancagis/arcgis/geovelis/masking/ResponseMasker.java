package it.giancagis.arcgis.geovelis.masking;

import com.esri.arcgis.server.json.JSONArray;
import com.esri.arcgis.server.json.JSONObject;
import it.giancagis.arcgis.geovelis.config.GeoVelisConfig;
import it.giancagis.arcgis.geovelis.rest.RestRequestContext;
import it.giancagis.arcgis.geovelis.util.JsonUtils;
import it.giancagis.arcgis.geovelis.util.RestErrorResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Post-processes ArcGIS REST JSON responses and masks configured sensitive attributes.
 *
 * v0.3 target shape:
 * {
 *   "features": [
 *     {"attributes": {"CODICE_FISCALE": "...", "EMAIL": "..."}, "geometry": {...}}
 *   ]
 * }
 *
 * GeoJSON FeatureCollection responses are supported through features[].properties.
 * The processor intentionally touches only feature attributes. It does not remove fields from the
 * service metadata, and it does not alter geometry.
 */
public final class ResponseMasker {

    private final GeoVelisConfig config;

    public ResponseMasker(GeoVelisConfig config) {
        this.config = config;
    }

    public ResponseMaskingResult process(RestRequestContext ctx, byte[] originalResponse) {
        if (originalResponse == null || originalResponse.length == 0) {
            return ResponseMaskingResult.notApplicable(originalResponse);
        }

        if (!config.isResponseMaskingEnabled()) {
            return ResponseMaskingResult.notApplicable(originalResponse);
        }

        if (config.isResponseMaskingApplyToQueryOnly() && !ctx.isOperation("query")) {
            return ResponseMaskingResult.notApplicable(originalResponse);
        }

        List<String> configuredFields = config.getResponseMaskingFields(ctx.layerId());
        if (configuredFields.isEmpty()) {
            return ResponseMaskingResult.notApplicable(originalResponse);
        }

        try {
            String responseText = new String(originalResponse, StandardCharsets.UTF_8);
            if (!looksLikeJsonObject(responseText)) {
                throw new IllegalArgumentException("Response format is not supported for masking.");
            }
            JSONObject root = new JSONObject(responseText);
            JSONArray features = JsonUtils.optJSONArray(root, "features");
            if (features == null) {
                if (root.has("features") || "FeatureCollection".equals(root.opt("type"))) {
                    throw new IllegalArgumentException("Invalid features array.");
                }
                if (!ctx.isOperation("query") || isSummaryOrError(root)) {
                    return ResponseMaskingResult.notApplicable(originalResponse);
                }
                throw new IllegalArgumentException("Query response structure is not supported for masking.");
            }

            boolean geoJson = "FeatureCollection".equals(root.opt("type"));
            MaskStats stats = maskFeatures(features, configuredFields, geoJson);
            if (stats.maskedAttributeCount == 0) {
                return ResponseMaskingResult.notApplicable(originalResponse);
            }

            if (config.isAuditMode()) {
                return ResponseMaskingResult.auditOnly(
                        originalResponse,
                        stats.maskedFeatureCount,
                        stats.maskedAttributeCount,
                        stats.maskedFields()
                );
            }

            byte[] maskedResponse = root.toString().getBytes(StandardCharsets.UTF_8);
            return ResponseMaskingResult.masked(
                    maskedResponse,
                    stats.maskedFeatureCount,
                    stats.maskedAttributeCount,
                    stats.maskedFields()
            );

        } catch (Exception ex) {
            if (config.isEnforceMode() && config.isResponseMaskingFailPolicyBlock()) {
                return ResponseMaskingResult.blockedOnError(
                        RestErrorResponse.badRequest("Response masking failed: response blocked by GeoVelis."),
                        "Response masking failed; response blocked (" + ex.getClass().getSimpleName() + ")."
                );
            }
            return ResponseMaskingResult.errorKeepOriginal(
                    originalResponse,
                    "Response masking failed; original response preserved (" + ex.getClass().getSimpleName() + ")."
            );
        }
    }

    private boolean isSummaryOrError(JSONObject root) {
        return JsonUtils.optJSONObject(root, "error") != null
                || root.opt("count") instanceof Number
                || JsonUtils.optJSONArray(root, "objectIds") != null
                || JsonUtils.optJSONObject(root, "extent") != null
                || (root.has("extent") && root.isNull("extent"));
    }

    private MaskStats maskFeatures(JSONArray features, List<String> configuredFields, boolean geoJson) throws Exception {
        Set<String> normalizedFields = normalize(configuredFields);
        MaskStats stats = new MaskStats();

        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = JsonUtils.optJSONObject(features, i);
            if (feature == null) {
                throw new IllegalArgumentException("Invalid feature in the response.");
            }

            String attributeKey = geoJson ? "properties" : "attributes";
            JSONObject attributes = JsonUtils.optJSONObject(feature, attributeKey);
            if (attributes == null) {
                if (!geoJson && feature.has("properties")) {
                    throw new IllegalArgumentException("Unrecognized feature structure.");
                }
                if (feature.has(attributeKey) && !feature.isNull(attributeKey)) {
                    throw new IllegalArgumentException("Invalid feature attributes.");
                }
                if (geoJson && !feature.has(attributeKey)) {
                    throw new IllegalArgumentException("Missing GeoJSON properties.");
                }
                continue;
            }

            int maskedInFeature = maskAttributes(attributes, normalizedFields, stats);
            if (maskedInFeature > 0) {
                stats.maskedFeatureCount++;
                stats.maskedAttributeCount += maskedInFeature;
            }
        }

        return stats;
    }

    private int maskAttributes(JSONObject attributes, Set<String> normalizedFields, MaskStats stats) throws Exception {
        int masked = 0;
        JSONArray names = attributes.names();
        if (names == null) {
            return 0;
        }

        for (int i = 0; i < names.length(); i++) {
            String actualName = JsonUtils.optString(names, i, null);
            if (actualName == null) {
                continue;
            }

            String normalizedName = normalize(actualName);
            if (normalizedFields.contains(normalizedName)) {
                Object originalValue = attributes.opt(actualName);
                attributes.put(actualName, maskValue(actualName, originalValue));
                stats.maskedFieldNames.add(actualName);
                masked++;
            }
        }

        return masked;
    }

    private Object maskValue(String fieldName, Object originalValue) {
        String strategy = strategyForField(fieldName);
        String text = originalValue == null ? "" : String.valueOf(originalValue);

        switch (strategy) {
            case "hash":
                return hash(text);
            case "partialemail":
                return partialEmail(text);
            case "partialphone":
                return partialPhone(text);
            case "partialfiscalcode":
                return partialFiscalCode(text);
            case "nullify":
                return null;
        }
        return config.getResponseMaskingReplacement();
    }

    private String strategyForField(String fieldName) {
        String normalizedFieldName = normalize(fieldName);
        for (Map.Entry<String, String> entry : config.getResponseMaskingFieldStrategies().entrySet()) {
            if (normalize(entry.getKey()).equals(normalizedFieldName)) {
                return normalize(entry.getValue());
            }
        }
        return normalize(config.getResponseMaskingStrategy());
    }

    private String partialEmail(String value) {
        int at = value.indexOf('@');
        if (at <= 0 || at == value.length() - 1) {
            return config.getResponseMaskingReplacement();
        }
        String local = value.substring(0, at);
        String domain = value.substring(at + 1);
        return keepPrefix(local, 1) + "***@" + domain;
    }

    private String partialPhone(String value) {
        if (value.length() <= 4) {
            return config.getResponseMaskingReplacement();
        }
        int prefixLength = Math.min(3, value.length() - 2);
        int suffixLength = Math.min(3, value.length() - prefixLength);
        return value.substring(0, prefixLength) + "****" + value.substring(value.length() - suffixLength);
    }

    private String partialFiscalCode(String value) {
        if (value.length() <= 6) {
            return config.getResponseMaskingReplacement();
        }
        return keepPrefix(value, 3) + "***********";
    }

    private String keepPrefix(String value, int count) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.substring(0, Math.min(count, value.length()));
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            return config.getResponseMaskingReplacement();
        }
    }

    private Set<String> normalize(List<String> fields) {
        Set<String> result = new HashSet<>();
        for (String field : fields) {
            String normalized = normalize(field);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean looksLikeJsonObject(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}");
    }

    private static final class MaskStats {
        private int maskedFeatureCount;
        private int maskedAttributeCount;
        private final Set<String> maskedFieldNames = new LinkedHashSet<>();

        private List<String> maskedFields() {
            return new ArrayList<>(maskedFieldNames);
        }
    }
}
