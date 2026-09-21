package it.giancagis.arcgis.geovelis.util;

import com.esri.arcgis.server.json.JSONArray;
import com.esri.arcgis.server.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Logs operational controls, never arbitrary request values or parser messages. */
public final class AuditSanitizer {
    private static final Set<String> FLAGS = new HashSet<>(Arrays.asList(
            "returngeometry", "returncountonly", "returnidsonly", "returnextentonly",
            "returnz", "returnm", "returndistinctvalues"));
    private static final Set<String> NUMBERS = new HashSet<>(Arrays.asList(
            "resultrecordcount", "resultoffset", "geometryprecision", "maxallowableoffset"));
    private static final Set<String> REDACTED = new HashSet<>(Arrays.asList(
            "where", "outfields", "geometry", "objectids", "text", "token", "outstatistics",
            "having", "orderbyfields", "groupbyfieldsforstatistics", "layer", "time"));

    private AuditSanitizer() { }

    public static String operationInput(String input) {
        if (input == null || input.trim().isEmpty()) return "{}";
        try {
            JSONObject source = new JSONObject(input);
            JSONObject safe = new JSONObject();
            JSONArray names = source.names();
            int omitted = 0;
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String originalKey = names.getString(i);
                    String key = originalKey.toLowerCase(Locale.ROOT);
                    Object value = source.opt(originalKey);
                    if (FLAGS.contains(key)) {
                        String text = String.valueOf(value);
                        safe.put(key, "true".equalsIgnoreCase(text) ? Boolean.TRUE
                                : "false".equalsIgnoreCase(text) ? Boolean.FALSE : "[REDACTED]");
                    } else if (NUMBERS.contains(key)) {
                        try {
                            double number = Double.parseDouble(String.valueOf(value));
                            safe.put(key, Double.isFinite(number) ? number : "[REDACTED]");
                        } catch (NumberFormatException ex) {
                            safe.put(key, "[REDACTED]");
                        }
                    } else if (REDACTED.contains(key)) {
                        safe.put(key, "[REDACTED]");
                    } else {
                        omitted++;
                    }
                }
            }
            if (omitted > 0) safe.put("otherParameterCount", omitted);
            return safe.toString();
        } catch (Exception ex) {
            return "[REDACTED: invalid operationInput]";
        }
    }

    public static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }
}
