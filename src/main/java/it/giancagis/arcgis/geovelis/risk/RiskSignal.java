package it.giancagis.arcgis.geovelis.risk;

/** Stable names for explainable, versioned risk telemetry. */
public enum RiskSignal {
    BROAD_QUERY(10), ALL_FIELDS(10), GEOMETRY(5), LARGE_ENVELOPE(20),
    IDS_ONLY(15), LARGE_PAGE(10), PAGING(5), BROAD_GEOMETRY_EXPORT(15),
    REPEATED_PAGING(20), HIGH_FREQUENCY(20), HIGH_RESPONSE_VOLUME(20);

    private final int defaultWeight;

    RiskSignal(int defaultWeight) { this.defaultWeight = defaultWeight; }

    public int defaultWeight() { return defaultWeight; }
}
