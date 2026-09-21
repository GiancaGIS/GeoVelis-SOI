package it.giancagis.arcgis.geovelis.rules;

import com.esri.arcgis.server.json.JSONObject;
import it.giancagis.arcgis.geovelis.config.GeoVelisConfig;
import it.giancagis.arcgis.geovelis.rest.RestRequestContext;
import it.giancagis.arcgis.geovelis.util.JsonUtils;

/**
 * Blocks or warns on query geometries whose envelope area exceeds a configured threshold.
 */
public final class GeometryGuardianRule implements Rule {
    private final GeoVelisConfig config;

    public GeometryGuardianRule(GeoVelisConfig config) {
        this.config = config;
    }

    @Override
    public RuleResult before(RestRequestContext ctx) {
        if (!ctx.isOperation("query")) {
            return RuleResult.allow();
        }

        double maxArea = config.getGeometryGuardMaxEnvelopeArea(ctx.layerId());
        if (maxArea <= 0.0d) {
            return RuleResult.allow();
        }

        JSONObject input = JsonUtils.parseObjectOrEmpty(ctx.operationInput());
        Envelope envelope = extractEnvelope(input);
        if (envelope == null) {
            return RuleResult.allow();
        }

        double area = envelope.area();
        if (area > maxArea) {
            return blockOrWarn("Geometry Guard: envelope area " + area + " exceeds the threshold of " + maxArea + ".");
        }

        return RuleResult.allow();
    }

    private RuleResult blockOrWarn(String message) {
        if (config.isEnforceMode()) {
            return RuleResult.block(message);
        }
        return RuleResult.warning(message + " Audit mode: request allowed.");
    }

    private Envelope extractEnvelope(JSONObject input) {
        try {
            Object geometry = input.opt("geometry");
            if (geometry instanceof JSONObject) {
                return envelopeFromJson((JSONObject) geometry);
            }
            if (geometry != null) {
                String text = String.valueOf(geometry).trim();
                if (text.startsWith("{") && text.endsWith("}")) {
                    return envelopeFromJson(new JSONObject(text));
                }
                return envelopeFromCsv(text);
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private Envelope envelopeFromJson(JSONObject geometry) {
        double xmin = JsonUtils.optDouble(geometry, "xmin", Double.NaN);
        double ymin = JsonUtils.optDouble(geometry, "ymin", Double.NaN);
        double xmax = JsonUtils.optDouble(geometry, "xmax", Double.NaN);
        double ymax = JsonUtils.optDouble(geometry, "ymax", Double.NaN);
        if (Double.isNaN(xmin) || Double.isNaN(ymin) || Double.isNaN(xmax) || Double.isNaN(ymax)) {
            return null;
        }
        return new Envelope(xmin, ymin, xmax, ymax);
    }

    private Envelope envelopeFromCsv(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        String[] parts = text.split(",");
        if (parts.length != 4) {
            return null;
        }

        try {
            return new Envelope(
                    Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()),
                    Double.parseDouble(parts[3].trim())
            );
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static final class Envelope {
        private final double xmin;
        private final double ymin;
        private final double xmax;
        private final double ymax;

        private Envelope(double xmin, double ymin, double xmax, double ymax) {
            this.xmin = xmin;
            this.ymin = ymin;
            this.xmax = xmax;
            this.ymax = ymax;
        }

        private double area() {
            return Math.abs((xmax - xmin) * (ymax - ymin));
        }
    }
}
