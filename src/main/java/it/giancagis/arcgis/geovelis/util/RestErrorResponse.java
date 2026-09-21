package it.giancagis.arcgis.geovelis.util;

import com.esri.arcgis.server.json.JSONArray;
import com.esri.arcgis.server.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Produces ArcGIS-style REST JSON errors.
 */
public final class RestErrorResponse {
    private RestErrorResponse() {
    }

    /** Replace payload-specific headers when a delegate response becomes a JSON error. */
    public static void setJsonResponseProperties(String[] responseProperties) {
        RestResponseProperties.forJsonError(responseProperties);
    }

    public static byte[] badRequest(String message) {
        return error(400, message);
    }

    public static byte[] temporarilyBlocked(long ttlMs) {
        return error(429, "Temporary behavior block. Retry after " + Math.max(1, (ttlMs + 999) / 1000) + " seconds.");
    }

    private static byte[] error(int code, String message) {
        try {
            JSONObject error = new JSONObject()
                    .put("code", code)
                    .put("message", "GeoVelis blocked the request.")
                    .put("details", new JSONArray().put(message));

            return new JSONObject().put("error", error)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            String fallback = "{\"error\":{\"code\":" + code + ",\"message\":\"GeoVelis blocked the request.\",\"details\":[\""
                    + escape(message) + "\"]}}";
            return fallback.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
