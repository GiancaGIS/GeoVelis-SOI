package it.giancagis.arcgis.geovelis.risk;

import com.esri.arcgis.system.IPropertySet;
import java.io.IOException;
import java.util.Locale;

/** Three public settings; bounded history and scoring weights belong to versioned profiles. */
public final class RiskConfig {
    private boolean enabled;
    private String profile = "balanced";
    private double largeEnvelopeArea;

    private RiskConfig() { }
    public static RiskConfig defaults() { return new RiskConfig(); }

    public static RiskConfig fromPropertySet(IPropertySet props) throws IOException {
        RiskConfig config = defaults();
        if (props == null) return config;
        switch (value(props, "riskEnabled", "false").toLowerCase(Locale.ROOT)) {
            case "true": case "yes": case "1": config.enabled = true; break;
            case "false": case "no": case "0": break;
            default: throw invalid("riskEnabled");
        }
        config.profile = value(props, "riskProfile", "balanced").toLowerCase(Locale.ROOT);
        if (!"balanced".equals(config.profile) && !"conservative".equals(config.profile)
                && !"sensitive".equals(config.profile)) throw invalid("riskProfile");
        try {
            config.largeEnvelopeArea = Double.parseDouble(value(props, "riskLargeEnvelopeArea", "0"));
        } catch (NumberFormatException ex) { throw invalid("riskLargeEnvelopeArea"); }
        if (!Double.isFinite(config.largeEnvelopeArea) || config.largeEnvelopeArea < 0) throw invalid("riskLargeEnvelopeArea");
        return config;
    }

    private static String value(IPropertySet props, String name, String fallback) throws IOException {
        Object raw = props.getProperty(name);
        return raw == null || raw.toString().trim().isEmpty() ? fallback : raw.toString().trim();
    }

    private static IllegalArgumentException invalid(String name) {
        return new IllegalArgumentException("Invalid GeoVelis property '" + name + "'. See risk configuration.");
    }

    public boolean enabled() { return enabled; }
    public String profile() { return profile; }
    public int windowSeconds() { return 60; }
    public int sessionIdleSeconds() { return 900; }
    public int sessionMaxSeconds() { return 28800; }
    public int maxTrackedKeys() { return 1000; }
    public int maxPagingQueries() { return 32; }
    public int requestThreshold() { return "sensitive".equals(profile) ? 15 : "conservative".equals(profile) ? 60 : 30; }
    public int pagingThreshold() { return "sensitive".equals(profile) ? 3 : "conservative".equals(profile) ? 8 : 5; }
    public long responseBytesThreshold() { return ("sensitive".equals(profile) ? 5L : "conservative".equals(profile) ? 25L : 10L) * 1048576; }
    public long sessionBytesThreshold() { return responseBytesThreshold() * 10; }
    public int largeRecordCount() { return "sensitive".equals(profile) ? 500 : "conservative".equals(profile) ? 5000 : 1000; }
    public double largeEnvelopeArea() { return largeEnvelopeArea; }
    public int mediumScore() { return 40; }
    public int highScore() { return 70; }
    public int maxInputLength() { return 65536; }
    public int weight(RiskSignal signal) { return signal.defaultWeight(); }

    @Override public String toString() {
        return "RiskConfig{enabled=" + enabled + ", profile=" + profile + ", largeEnvelopeArea=" + largeEnvelopeArea + "}";
    }
}
