package it.giancagis.arcgis.geovelis.util;

public final class StringUtils {
    private StringUtils() {
    }

    public static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (maxLength <= 0) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...[TRUNCATED]";
    }
}
