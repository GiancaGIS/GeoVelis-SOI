package it.giancagis.arcgis.geovelis.util;

import com.esri.arcgis.server.json.JSONArray;
import com.esri.arcgis.server.json.JSONObject;

/**
 * Small helpers around ArcGIS Enterprise SDK JSONObject/JSONArray.
 */
public final class JsonUtils {
    private JsonUtils() {
    }

    public static JSONObject parseObjectOrEmpty(String json) {
        try {
            if (json == null || json.trim().isEmpty()) {
                return new JSONObject();
            }
            return new JSONObject(json);
        } catch (Exception ex) {
            return new JSONObject();
        }
    }

    public static String optString(JSONObject json, String key, String defaultValue) {
        try {
            Object value = json.opt(key);
            if (value == null) {
                return defaultValue;
            }
            return String.valueOf(value);
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    public static String optString(JSONArray array, int index, String defaultValue) {
        try {
            Object value = array.get(index);
            if (value == null) {
                return defaultValue;
            }
            return String.valueOf(value);
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    public static boolean optBoolean(JSONObject json, String key, boolean defaultValue) {
        String raw = optString(json, key, Boolean.toString(defaultValue));
        if ("true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw) || "0".equals(raw) || "no".equalsIgnoreCase(raw)) {
            return false;
        }
        return defaultValue;
    }

    public static int optInt(JSONObject json, String key, int defaultValue) {
        String raw = optString(json, key, Integer.toString(defaultValue));
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    public static double optDouble(JSONObject json, String key, double defaultValue) {
        String raw = optString(json, key, Double.toString(defaultValue));
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    public static boolean hasNonEmpty(JSONObject json, String key) {
        String raw = optString(json, key, "");
        return raw != null && !raw.trim().isEmpty();
    }

    public static JSONObject optJSONObject(JSONObject json, String key) {
        try {
            Object value = json.opt(key);
            if (value instanceof JSONObject) {
                return (JSONObject) value;
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    public static JSONObject optJSONObject(JSONArray array, int index) {
        try {
            Object value = array.get(index);
            if (value instanceof JSONObject) {
                return (JSONObject) value;
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    public static JSONArray optJSONArray(JSONObject json, String key) {
        try {
            Object value = json.opt(key);
            if (value instanceof JSONArray) {
                return (JSONArray) value;
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }
}
