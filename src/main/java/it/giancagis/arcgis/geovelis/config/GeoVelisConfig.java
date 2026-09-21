package it.giancagis.arcgis.geovelis.config;

import com.esri.arcgis.interop.AutomationException;
import com.esri.arcgis.system.IPropertySet;
import it.giancagis.arcgis.geovelis.risk.RiskConfig;
import it.giancagis.arcgis.geovelis.behavior.BehaviorConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime configuration for GeoVelis.
 *
 * The values are read from ArcGIS Server Manager SOI properties via IObjectConstruct.construct().
 */
public final class GeoVelisConfig {

    public enum Mode {
        AUDIT,
        ENFORCE;

        public static Mode fromString(String value) {
            if (value == null) {
                return AUDIT;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if ("enforce".equals(normalized)) {
                return ENFORCE;
            }
            if ("audit".equals(normalized)) {
                return AUDIT;
            }
            throw invalid("mode", "audit or enforce");
        }
    }

    private boolean enabled = true;
    private RiskConfig riskConfig = RiskConfig.defaults();
    private BehaviorConfig behaviorConfig = BehaviorConfig.disabled();

    public BehaviorConfig getBehaviorConfig() { return behaviorConfig; }
    private Mode mode = Mode.AUDIT;
    private boolean logOperationInput = true;
    private int maxLoggedInputLength = 2000;
    private boolean fileAuditEnabled = false;
    private String fileAuditDirectory = "";
    private String fileAuditPrefix = "geovelis-audit";
    private int fileAuditRetentionDays = 30;

    private int maxRecordCount = 5000;
    private double geometryGuardMaxEnvelopeArea = 0.0d;
    private String layerPolicyOverrides = "";
    private Map<String, LayerPolicy> layerPolicies = new LinkedHashMap<>();

    private boolean fieldGuardianEnabled = false;
    private List<String> fieldGuardianAllowedOutFields = new ArrayList<>();
    private List<String> fieldGuardianDeniedOutFields = new ArrayList<>();
    private boolean fieldGuardianBlockOnDeniedFields = false;

    private boolean responseMaskingEnabled = false;
    private List<String> responseMaskingFields = new ArrayList<>();
    private String responseMaskingReplacement = "****";
    private String responseMaskingStrategy = "full";
    private Map<String, String> responseMaskingFieldStrategies = new LinkedHashMap<>();
    private String responseMaskingFailPolicy = "keepOriginal";
    private boolean responseMaskingApplyToQueryOnly = true;

    public static GeoVelisConfig defaults() {
        GeoVelisConfig config = new GeoVelisConfig();
        config.responseMaskingFields = parseCsv("CODICE_FISCALE,EMAIL,TELEFONO");
        return config;
    }

    public static GeoVelisConfig fromPropertySet(IPropertySet props) throws IOException, AutomationException {
        GeoVelisConfig config = defaults();

        if (props == null) {
            return config;
        }

        config.enabled = getBoolean(props, "enabled", config.enabled);
        config.riskConfig = RiskConfig.fromPropertySet(props);
        config.behaviorConfig = BehaviorConfig.fromPropertySet(props);
        if (config.behaviorConfig.blockThreshold() > 0 && !config.riskConfig.enabled())
            throw new IllegalArgumentException("riskBlockThreshold requires riskEnabled=true.");
        config.mode = Mode.fromString(getString(props, "mode", config.mode.name().toLowerCase(Locale.ROOT)));
        config.logOperationInput = getBoolean(props, "logOperationInput", config.logOperationInput);
        config.maxLoggedInputLength = getInt(props, "maxLoggedInputLength", config.maxLoggedInputLength);
        config.fileAuditEnabled = getBoolean(props, "fileAuditEnabled", config.fileAuditEnabled);
        config.fileAuditDirectory = getString(props, "fileAuditDirectory", config.fileAuditDirectory);
        config.fileAuditPrefix = getString(props, "fileAuditPrefix", config.fileAuditPrefix);
        config.fileAuditRetentionDays = getInt(props, "fileAuditRetentionDays", config.fileAuditRetentionDays);

        config.maxRecordCount = getInt(props, "maxRecordCount", config.maxRecordCount);
        config.geometryGuardMaxEnvelopeArea = getDouble(props, "geometryGuardMaxEnvelopeArea", config.geometryGuardMaxEnvelopeArea);
        config.layerPolicyOverrides = getString(props, "layerPolicyOverrides", config.layerPolicyOverrides);
        config.layerPolicies = parseLayerPolicies(config.layerPolicyOverrides);

        config.fieldGuardianEnabled = getBoolean(props, "fieldGuardianEnabled", config.fieldGuardianEnabled);
        config.fieldGuardianAllowedOutFields = parseCsv(getString(
                props,
                "fieldGuardianAllowedOutFields",
                String.join(",", config.fieldGuardianAllowedOutFields)
        ));
        config.fieldGuardianDeniedOutFields = parseCsv(getString(
                props,
                "fieldGuardianDeniedOutFields",
                String.join(",", config.fieldGuardianDeniedOutFields)
        ));
        config.fieldGuardianBlockOnDeniedFields = getBoolean(
                props,
                "fieldGuardianBlockOnDeniedFields",
                config.fieldGuardianBlockOnDeniedFields
        );

        config.responseMaskingEnabled = getBoolean(props, "responseMaskingEnabled", config.responseMaskingEnabled);
        config.responseMaskingFields = parseCsv(getString(
                props,
                "responseMaskingFields",
                String.join(",", config.responseMaskingFields)
        ));
        config.responseMaskingReplacement = getString(props, "responseMaskingReplacement", config.responseMaskingReplacement);
        config.responseMaskingStrategy = getString(props, "responseMaskingStrategy", config.responseMaskingStrategy);
        config.responseMaskingFieldStrategies = parseFieldStrategies(getString(
                props,
                "responseMaskingFieldStrategies",
                formatFieldStrategies(config.responseMaskingFieldStrategies)
        ));
        config.responseMaskingFailPolicy = getString(props, "responseMaskingFailPolicy", config.responseMaskingFailPolicy);
        config.responseMaskingApplyToQueryOnly = getBoolean(
                props,
                "responseMaskingApplyToQueryOnly",
                config.responseMaskingApplyToQueryOnly
        );

        if (config.maxLoggedInputLength < 0) {
            throw invalid("maxLoggedInputLength", "a non-negative integer");
        }
        if (config.fileAuditPrefix == null || config.fileAuditPrefix.trim().isEmpty()) {
            config.fileAuditPrefix = "geovelis-audit";
        }
        if (config.fileAuditRetentionDays < 1) {
            throw invalid("fileAuditRetentionDays", "a positive integer");
        }
        if (config.maxRecordCount < 1) {
            throw invalid("maxRecordCount", "a positive integer");
        }
        if (!Double.isFinite(config.geometryGuardMaxEnvelopeArea) || config.geometryGuardMaxEnvelopeArea < 0.0d) {
            throw invalid("geometryGuardMaxEnvelopeArea", "a finite non-negative number");
        }
        if (config.responseMaskingReplacement == null) {
            config.responseMaskingReplacement = "";
        }
        if (config.responseMaskingStrategy == null || config.responseMaskingStrategy.trim().isEmpty()) {
            config.responseMaskingStrategy = "full";
        }
        validateStrategy(config.responseMaskingStrategy, "responseMaskingStrategy");
        if (!"block".equalsIgnoreCase(config.responseMaskingFailPolicy)
                && !"keepOriginal".equalsIgnoreCase(config.responseMaskingFailPolicy)) {
            throw invalid("responseMaskingFailPolicy", "block or keepOriginal");
        }

        return config;
    }

    private static String getString(IPropertySet props, String key, String defaultValue) throws IOException, AutomationException {
        Object value = props.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? defaultValue : text;
    }

    private static boolean getBoolean(IPropertySet props, String key, boolean defaultValue) throws IOException, AutomationException {
        String value = getString(props, key, Boolean.toString(defaultValue));
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value) || "no".equalsIgnoreCase(value)) {
            return false;
        }
        throw invalid(key, "true/false, yes/no or 1/0");
    }

    private static int getInt(IPropertySet props, String key, int defaultValue) throws IOException, AutomationException {
        String value = getString(props, key, Integer.toString(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw invalid(key, "an integer");
        }
    }

    private static double getDouble(IPropertySet props, String key, double defaultValue) throws IOException, AutomationException {
        String value = getString(props, key, Double.toString(defaultValue));
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            throw invalid(key, "a number");
        }
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String[] parts = csv.split(",");
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return Collections.unmodifiableList(values);
    }

    private static Map<String, String> parseFieldStrategies(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return Collections.emptyMap();
        }

        String[] parts = csv.split(",");
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            String[] pair = trimmed.split(":", 2);
            if (pair.length != 2) {
                throw invalid("responseMaskingFieldStrategies", "FIELD:strategy entries");
            }

            String field = pair[0].trim();
            String strategy = pair[1].trim();
            if (field.isEmpty() || strategy.isEmpty()) {
                throw invalid("responseMaskingFieldStrategies", "non-empty field and strategy");
            }
            validateStrategy(strategy, "responseMaskingFieldStrategies");
            values.put(field, strategy);
        }
        return Collections.unmodifiableMap(values);
    }

    private static String formatFieldStrategies(Map<String, String> strategies) {
        if (strategies == null || strategies.isEmpty()) {
            return "";
        }

        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : strategies.entrySet()) {
            parts.add(entry.getKey() + ":" + entry.getValue());
        }
        return String.join(",", parts);
    }

    private static Map<String, LayerPolicy> parseLayerPolicies(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, LayerPolicy> policies = new LinkedHashMap<>();
        String[] entries = text.split(";");
        for (String entry : entries) {
            if (entry == null || entry.trim().isEmpty()) {
                continue;
            }

            String[] keyValue = entry.trim().split("=", 2);
            if (keyValue.length != 2) {
                throw invalid("layerPolicyOverrides", "layerId.property=value entries");
            }

            String[] layerAndKey = keyValue[0].trim().split("\\.", 2);
            if (layerAndKey.length != 2) {
                throw invalid("layerPolicyOverrides", "layerId.property=value entries");
            }

            String layerId = layerAndKey[0].trim();
            String key = layerAndKey[1].trim();
            String value = keyValue[1].trim();
            if (!layerId.matches("\\d+") || key.isEmpty() || value.isEmpty()) {
                throw invalid("layerPolicyOverrides", "numeric layer ID, property and non-empty value");
            }

            LayerPolicy policy = policies.computeIfAbsent(layerId, ignored -> new LayerPolicy());
            policy.apply(key, value);
        }
        return Collections.unmodifiableMap(policies);
    }

    private LayerPolicy layerPolicy(String layerId) {
        if (layerId == null || layerId.trim().isEmpty()) {
            return null;
        }
        return layerPolicies.get(layerId.trim());
    }

    private static IllegalArgumentException invalid(String property, String expected) {
        return new IllegalArgumentException("Invalid GeoVelis property '" + property + "': expected " + expected + ".");
    }

    private static void validateStrategy(String strategy, String property) {
        switch (strategy.toLowerCase(Locale.ROOT)) {
            case "full": case "partialemail": case "partialphone": case "partialfiscalcode": case "hash": case "nullify":
                return;
            default:
                throw invalid(property, "full, partialEmail, partialPhone, partialFiscalCode, hash or nullify");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public RiskConfig getRiskConfig() { return riskConfig; }

    public Mode getMode() {
        return mode;
    }

    public boolean isAuditMode() {
        return mode == Mode.AUDIT;
    }

    public boolean isEnforceMode() {
        return mode == Mode.ENFORCE;
    }

    public boolean isLogOperationInput() {
        return logOperationInput;
    }

    public int getMaxLoggedInputLength() {
        return maxLoggedInputLength;
    }

    public boolean isFileAuditEnabled() {
        return fileAuditEnabled;
    }

    public String getFileAuditDirectory() {
        return fileAuditDirectory;
    }

    public String getFileAuditPrefix() {
        return fileAuditPrefix;
    }

    public int getFileAuditRetentionDays() {
        return fileAuditRetentionDays;
    }

    public int getMaxRecordCount() {
        return maxRecordCount;
    }

    public int getMaxRecordCount(String layerId) {
        LayerPolicy policy = layerPolicy(layerId);
        return policy != null && policy.maxRecordCount != null ? policy.maxRecordCount : maxRecordCount;
    }

    public double getGeometryGuardMaxEnvelopeArea() {
        return geometryGuardMaxEnvelopeArea;
    }

    public double getGeometryGuardMaxEnvelopeArea(String layerId) {
        LayerPolicy policy = layerPolicy(layerId);
        return policy != null && policy.geometryGuardMaxEnvelopeArea != null
                ? policy.geometryGuardMaxEnvelopeArea
                : geometryGuardMaxEnvelopeArea;
    }

    public String getLayerPolicyOverrides() {
        return layerPolicyOverrides;
    }

    public boolean isFieldGuardianEnabled() {
        return fieldGuardianEnabled;
    }

    public List<String> getFieldGuardianAllowedOutFields() {
        return fieldGuardianAllowedOutFields;
    }

    public List<String> getFieldGuardianDeniedOutFields() {
        return fieldGuardianDeniedOutFields;
    }

    public boolean isFieldGuardianBlockOnDeniedFields() {
        return fieldGuardianBlockOnDeniedFields;
    }

    public boolean isResponseMaskingEnabled() {
        return responseMaskingEnabled;
    }

    public List<String> getResponseMaskingFields() {
        return responseMaskingFields;
    }

    public List<String> getResponseMaskingFields(String layerId) {
        LayerPolicy policy = layerPolicy(layerId);
        return policy != null && policy.responseMaskingFields != null ? policy.responseMaskingFields : responseMaskingFields;
    }

    public String getResponseMaskingReplacement() {
        return responseMaskingReplacement;
    }

    public String getResponseMaskingStrategy() {
        return responseMaskingStrategy;
    }

    public Map<String, String> getResponseMaskingFieldStrategies() {
        return responseMaskingFieldStrategies;
    }

    public String getResponseMaskingFailPolicy() {
        return responseMaskingFailPolicy;
    }

    public boolean isResponseMaskingFailPolicyBlock() {
        return "block".equalsIgnoreCase(responseMaskingFailPolicy);
    }

    public boolean isResponseMaskingApplyToQueryOnly() {
        return responseMaskingApplyToQueryOnly;
    }

    @Override
    public String toString() {
        return "GeoVelisConfig{" +
                "enabled=" + enabled +
                ", risk=" + riskConfig +
                ", behavior=" + behaviorConfig +
                ", mode=" + mode +
                ", logOperationInput=" + logOperationInput +
                ", maxLoggedInputLength=" + maxLoggedInputLength +
                ", fileAuditEnabled=" + fileAuditEnabled +
                ", fileAuditDirectory='" + fileAuditDirectory + '\'' +
                ", fileAuditPrefix='" + fileAuditPrefix + '\'' +
                ", fileAuditRetentionDays=" + fileAuditRetentionDays +
                ", maxRecordCount=" + maxRecordCount +
                ", geometryGuardMaxEnvelopeArea=" + geometryGuardMaxEnvelopeArea +
                ", layerPolicyOverrides='" + layerPolicyOverrides + '\'' +
                ", fieldGuardianEnabled=" + fieldGuardianEnabled +
                ", fieldGuardianAllowedOutFields=" + fieldGuardianAllowedOutFields +
                ", fieldGuardianDeniedOutFields=" + fieldGuardianDeniedOutFields +
                ", fieldGuardianBlockOnDeniedFields=" + fieldGuardianBlockOnDeniedFields +
                ", responseMaskingEnabled=" + responseMaskingEnabled +
                ", responseMaskingFields=" + responseMaskingFields +
                ", responseMaskingReplacement='" + responseMaskingReplacement + '\'' +
                ", responseMaskingStrategy='" + responseMaskingStrategy + '\'' +
                ", responseMaskingFieldStrategies=" + responseMaskingFieldStrategies +
                ", responseMaskingFailPolicy='" + responseMaskingFailPolicy + '\'' +
                ", responseMaskingApplyToQueryOnly=" + responseMaskingApplyToQueryOnly +
                '}';
    }

    private static final class LayerPolicy {
        private Integer maxRecordCount;
        private Double geometryGuardMaxEnvelopeArea;
        private List<String> responseMaskingFields;

        private void apply(String key, String value) {
            if ("maxRecordCount".equalsIgnoreCase(key)) {
                maxRecordCount = parsePositiveInt(value);
            } else if ("geometryGuardMaxEnvelopeArea".equalsIgnoreCase(key)) {
                geometryGuardMaxEnvelopeArea = parseNonNegativeDouble(value);
            } else if ("responseMaskingFields".equalsIgnoreCase(key)) {
                responseMaskingFields = parseCsv(value.replace('|', ','));
                if (responseMaskingFields.isEmpty()) throw invalid("layerPolicyOverrides", "non-empty masking fields");
            } else {
                throw invalid("layerPolicyOverrides", "a supported layer property");
            }
        }

        private Integer parsePositiveInt(String value) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed > 0) return parsed;
                throw invalid("layerPolicyOverrides.maxRecordCount", "a positive integer");
            } catch (NumberFormatException ex) {
                throw invalid("layerPolicyOverrides.maxRecordCount", "a positive integer");
            }
        }

        private Double parseNonNegativeDouble(String value) {
            try {
                double parsed = Double.parseDouble(value);
                if (Double.isFinite(parsed) && parsed >= 0.0d) return parsed;
                throw invalid("layerPolicyOverrides.geometryGuardMaxEnvelopeArea", "a finite non-negative number");
            } catch (NumberFormatException ex) {
                throw invalid("layerPolicyOverrides.geometryGuardMaxEnvelopeArea", "a finite non-negative number");
            }
        }

    }
}
