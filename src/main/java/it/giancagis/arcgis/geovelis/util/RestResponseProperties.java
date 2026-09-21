package it.giancagis.arcgis.geovelis.util;

import com.esri.arcgis.server.json.JSONArray;
import com.esri.arcgis.server.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Updates representation headers only when the interceptor replaces the body. */
public final class RestResponseProperties {
    private static final Set<String> STALE_HEADERS = new HashSet<>(Arrays.asList(
            "content-type", "content-length", "content-encoding", "etag", "content-md5",
            "digest", "content-digest", "repr-digest", "last-modified", "content-range", "accept-ranges"));

    private RestResponseProperties() { }

    public static void forMaskedResponse(String[] properties, String format) {
        rewrite(properties, "geojson".equalsIgnoreCase(format)
                ? "application/geo+json; charset=utf-8" : "application/json; charset=utf-8", false);
    }

    public static void forJsonError(String[] properties) {
        rewrite(properties, "application/json; charset=utf-8", true);
    }

    private static void rewrite(String[] properties, String contentType, boolean error) {
        if (properties == null || properties.length == 0) return;
        try {
            JSONObject headers = JsonUtils.parseObjectOrEmpty(properties[0]);
            JSONArray names = headers.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String name = names.getString(i);
                    String normalized = name.toLowerCase(Locale.ROOT);
                    if (STALE_HEADERS.contains(normalized) || (error && "content-disposition".equals(normalized))) {
                        headers.remove(name);
                    }
                }
            }
            headers.put("Content-Type", contentType);
            properties[0] = headers.toString();
        } catch (Exception ex) {
            properties[0] = "{\"Content-Type\":\"" + contentType + "\"}";
        }
    }
}
