package it.giancagis.arcgis.geovelis.behavior;

import com.esri.arcgis.server.json.JSONArray;
import com.esri.arcgis.server.json.JSONObject;
import it.giancagis.arcgis.geovelis.rest.RestRequestContext;
import it.giancagis.arcgis.geovelis.risk.RiskAssessment;
import it.giancagis.arcgis.geovelis.risk.RiskSignal;
import it.giancagis.arcgis.geovelis.util.JsonUtils;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Immutable, compact event. Raw identity, SQL, tokens, object IDs and response bodies never enter the queue. */
final class BehaviorEvent {
    final String id = UUID.randomUUID().toString();
    final long createdNanos = System.nanoTime();
    final String key, subject, layer, query, batch, outcome, inputStatus;
    final long offset, requestedRecords, effectiveRecords, responseBytes, objectIdCount;
    final int requestRisk;
    final boolean broadIds, returned;
    final String signals;

    private BehaviorEvent(BehaviorConfig config, RestRequestContext ctx, String effectiveInput,
                          RiskAssessment risk, long bytes, RiskAssessment.Outcome result, String profile) throws Exception {
        subject = hmac(config.secret(), "subject:" + ctx.userName().trim());
        layer = ctx.layerId();
        key = scopeKey(config, ctx, profile);
        JSONObject input = parse(ctx.operationInput());
        JSONObject effective = parse(effectiveInput);
        inputStatus = input == null ? (ctx.operationInput().length() > 65536 ? "TOO_LARGE" : "INVALID_JSON") : "OK";
        offset = number(input, "resultOffset", -1);
        requestedRecords = Math.max(0, number(input, "resultRecordCount", 0));
        effectiveRecords = Math.max(0, number(effective, "resultRecordCount", 0));
        objectIdCount = idsCount(input);
        query = input == null ? "" : hmac(config.secret(), canonical(input));
        batch = objectIdCount == 0 ? "" : hmac(config.secret(), JsonUtils.optString(input, "objectIds", ""));
        requestRisk = risk.score();
        signals = risk.contributions().toString();
        outcome = result.name();
        returned = result == RiskAssessment.Outcome.RETURNED;
        responseBytes = returned ? Math.max(0, bytes) : 0;
        broadIds = returned && risk.contributions().containsKey(RiskSignal.IDS_ONLY)
                && risk.contributions().containsKey(RiskSignal.BROAD_QUERY);
    }

    static BehaviorEvent create(BehaviorConfig config, RestRequestContext ctx, String effectiveInput,
                                RiskAssessment risk, long bytes, RiskAssessment.Outcome outcome, String profile) throws Exception {
        if (!eligible(ctx)) return null;
        return new BehaviorEvent(config, ctx, effectiveInput, risk, bytes, outcome, profile);
    }

    static String scopeKey(BehaviorConfig config, RestRequestContext ctx, String profile) throws Exception {
        return "geovelis:behavior:v1:" + profile + ":" + hmac(config.secret(), "service:" + config.serviceKey())
                + ":" + hmac(config.secret(), "subject:" + ctx.userName().trim()) + ":" + ctx.layerId();
    }
    static String blockKey(String scopeKey) { return scopeKey.replace("geovelis:behavior:", "geovelis:blocked:"); }

    static boolean eligible(RestRequestContext ctx) {
        String user = ctx.userName().trim();
        if (!ctx.isOperation("query") || user.isEmpty() || "anonymous".equalsIgnoreCase(user)
                || "anonymous-or-unknown".equalsIgnoreCase(user)
                || ctx.layerId().isEmpty() || ctx.layerId().length() > 10) return false;
        return true;
    }

    private static JSONObject parse(String value) {
        if (value == null || value.length() > 65536) return null;
        try { return new JSONObject(value.trim().isEmpty() ? "{}" : value); }
        catch (Exception ex) { return null; }
    }
    private static long number(JSONObject input, String name, long fallback) {
        try {
            long value = Long.parseLong(JsonUtils.optString(input, name, Long.toString(fallback)));
            return value < 0 || value > Integer.MAX_VALUE ? fallback : value;
        } catch (Exception ex) { return fallback; }
    }
    private static long idsCount(JSONObject input) {
        if (input == null) return 0;
        String ids = JsonUtils.optString(input, "objectIds", "").trim();
        if (ids.isEmpty()) return 0;
        try {
            if (ids.startsWith("[")) return new JSONArray(ids).length();
            long count = 0;
            for (String id : ids.split(",")) {
                if (!id.trim().matches("-?\\d+")) return 0;
                count++;
            }
            return count;
        } catch (Exception ex) { return 0; }
    }
    private static String canonical(JSONObject input) throws Exception {
        List<String> names = new ArrayList<>();
        JSONArray keys = input.names();
        if (keys != null) for (int i = 0; i < keys.length(); i++) {
            String key = keys.getString(i);
            if (!"resultOffset".equalsIgnoreCase(key) && !"resultRecordCount".equalsIgnoreCase(key)
                    && !"token".equalsIgnoreCase(key) && !"f".equalsIgnoreCase(key)) names.add(key);
        }
        Collections.sort(names);
        StringBuilder result = new StringBuilder();
        for (String name : names) {
            String value = String.valueOf(input.opt(name));
            result.append(name.length()).append(':').append(name).append(value.length()).append(':').append(value);
        }
        return result.toString();
    }
    static String hmac(String secret, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder result = new StringBuilder(64);
        for (byte b : mac.doFinal(value.getBytes(StandardCharsets.UTF_8))) {
            result.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        return result.toString();
    }
}
