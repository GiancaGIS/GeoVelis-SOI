package it.giancagis.arcgis.geovelis.risk;

import com.esri.arcgis.server.json.JSONArray;
import com.esri.arcgis.server.json.JSONObject;
import it.giancagis.arcgis.geovelis.rest.RestRequestContext;
import it.giancagis.arcgis.geovelis.util.JsonUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Observational only: bounded fixed windows, no request mutation or blocking API. */
public final class RiskAnalyzer {
    private final RiskConfig config;
    private final LongSupplier clockMillis;
    private boolean requestOnly;
    private final String instanceId = UUID.randomUUID().toString();
    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>(16, 0.75f, true);
    private long evictions;
    private long lastSweep = Long.MIN_VALUE;

    public RiskAnalyzer(RiskConfig config) {
        this(config, () -> System.nanoTime() / 1000000L);
    }

    public RiskAnalyzer(RiskConfig config, boolean requestOnly) {
        this(config);
        this.requestOnly = requestOnly;
    }

    RiskAnalyzer(RiskConfig config, LongSupplier clockMillis) {
        this.config = config;
        this.clockMillis = clockMillis;
    }

    /** Called once at completion, using ORIGINAL request parameters and the bytes actually returned. */
    public RiskAssessment observe(RestRequestContext ctx, long responseBytes, long elapsedMs,
                                  RiskAssessment.Outcome outcome) {
        if (!config.enabled() || !ctx.isOperation("query")) return null;
        Map<RiskSignal, Integer> signals = new EnumMap<>(RiskSignal.class);
        JSONObject input = null;
        String inputStatus = "OK";
        if (ctx.operationInput().length() > config.maxInputLength()) {
            inputStatus = "TOO_LARGE";
        } else {
            try {
                input = ctx.operationInput().trim().isEmpty() ? new JSONObject() : new JSONObject(ctx.operationInput());
            } catch (Exception ex) {
                inputStatus = "INVALID_JSON";
            }
        }
        long offset = -1;
        String fingerprint = null;
        if (input != null) {
            String where = JsonUtils.optString(input, "where", "").replaceAll("[\\s()]", "").toLowerCase(Locale.ROOT);
            boolean broad = where.isEmpty() || "1=1".equals(where) || "objectid=objectid".equals(where)
                    || "objectid>0".equals(where) || "fid=fid".equals(where);
            boolean all = "*".equals(JsonUtils.optString(input, "outFields", "").trim());
            boolean idsOnly = JsonUtils.optBoolean(input, "returnIdsOnly", false);
            boolean geometry = JsonUtils.optBoolean(input, "returnGeometry", true) && !idsOnly
                    && !JsonUtils.optBoolean(input, "returnCountOnly", false)
                    && !JsonUtils.optBoolean(input, "returnExtentOnly", false) && !hasStatistics(input);
            if (broad) add(signals, RiskSignal.BROAD_QUERY);
            if (all) add(signals, RiskSignal.ALL_FIELDS);
            if (geometry) add(signals, RiskSignal.GEOMETRY);
            if (idsOnly) add(signals, RiskSignal.IDS_ONLY);
            if (broad && all && geometry) add(signals, RiskSignal.BROAD_GEOMETRY_EXPORT);
            if (JsonUtils.optInt(input, "resultRecordCount", -1) >= config.largeRecordCount()) add(signals, RiskSignal.LARGE_PAGE);
            if (config.largeEnvelopeArea() > 0 && envelopeArea(input) >= config.largeEnvelopeArea()) add(signals, RiskSignal.LARGE_ENVELOPE);
            try {
                offset = Long.parseLong(JsonUtils.optString(input, "resultOffset", "-1"));
            } catch (NumberFormatException ignored) { }
            if (offset > 0) add(signals, RiskSignal.PAGING);
            if (offset >= 0) fingerprint = fingerprint(input);
        }

        String user = ctx.userName().trim();
        boolean anonymous = user.isEmpty() || "anonymous".equalsIgnoreCase(user)
                || "anonymous-or-unknown".equalsIgnoreCase(user);
        String layer = ctx.layerId();
        if (layer.length() > 10) layer = "";
        String subject = anonymous ? "anonymous" : digest(instanceId + ":" + user);
        String correlation = anonymous ? "UNAVAILABLE_ANONYMOUS" : "INSTANCE_USER_INFERRED_SESSION";
        if (requestOnly && !anonymous) correlation = "REDIS_ASYNC";
        Snapshot snapshot = anonymous || requestOnly ? new Snapshot() : update(subject, layer, offset, fingerprint, signals,
                outcome == RiskAssessment.Outcome.RETURNED ? Math.max(0, responseBytes) : 0);
        return new RiskAssessment(config, signals, snapshot.user, snapshot.session, instanceId, subject,
                layer.isEmpty() ? "unknown" : layer, snapshot.sessionId, correlation, inputStatus, outcome,
                responseBytes, elapsedMs, snapshot.windowRequests, snapshot.windowBytes,
                snapshot.sessionRequests, snapshot.sessionBytes, snapshot.pages, snapshot.evictions);
    }

    private synchronized Snapshot update(String subject, String layer, long offset, String fingerprint,
                                         Map<RiskSignal, Integer> signals, long bytes) {
        long now = clockMillis.getAsLong();
        long duration = config.windowSeconds() * 1000L;
        // Each subject owns one inferred session and a bounded set of query fingerprints.
        if (lastSweep == Long.MIN_VALUE || now - lastSweep >= Math.min(duration, 1000L)) {
            sessions.values().removeIf(session -> expired(session, now));
            lastSweep = now;
        }
        Session session = sessions.get(subject);
        if (session == null || expired(session, now)) {
            if (session == null && sessions.size() >= config.maxTrackedKeys()) {
                Iterator<String> oldest = sessions.keySet().iterator();
                oldest.next();
                oldest.remove();
                evictions = plus(evictions, 1);
            }
            session = new Session(now);
            sessions.put(subject, session);
        }
        session.lastSeen = now;
        if (now - session.window.started >= duration) session.window = new Window(now);
        Window window = session.window;
        window.requests = plus(window.requests, 1);
        window.bytes = plus(window.bytes, bytes);
        session.requests = plus(session.requests, 1);
        session.bytes = plus(session.bytes, bytes);
        window.peak = peak(window.peak, signals);
        session.peak = peak(session.peak, signals);
        long pages = 0;
        if (offset >= 0 && fingerprint != null && !layer.isEmpty()) {
            String query = layer + ":" + fingerprint;
            Paging paging = session.queries.get(query);
            if (paging == null) {
                if (session.queries.size() >= config.maxPagingQueries()) {
                    Iterator<String> oldest = session.queries.keySet().iterator();
                    oldest.next();
                    oldest.remove();
                    evictions = plus(evictions, 1);
                }
                paging = new Paging();
                session.queries.put(query, paging);
            }
            boolean increasing = paging.pages > 0 && offset > paging.offset && now - paging.lastSeen < duration;
            paging.pages = increasing ? plus(paging.pages, 1) : 1;
            paging.windowPages = increasing && paging.windowStarted == window.started ? plus(paging.windowPages, 1) : 1;
            paging.windowStarted = window.started;
            paging.lastSeen = now;
            paging.offset = offset;
            pages = paging.pages;
            window.repeatedPaging |= paging.windowPages >= config.pagingThreshold();
            session.repeatedPaging |= pages >= config.pagingThreshold();
        }
        Map<RiskSignal, Integer> userSignals = new EnumMap<>(window.peak);
        if (window.requests >= config.requestThreshold()) {
            add(userSignals, RiskSignal.HIGH_FREQUENCY);
            session.highFrequency = true;
        }
        if (window.repeatedPaging) add(userSignals, RiskSignal.REPEATED_PAGING);
        if (window.bytes >= config.responseBytesThreshold()) add(userSignals, RiskSignal.HIGH_RESPONSE_VOLUME);
        Map<RiskSignal, Integer> sessionSignals = new EnumMap<>(session.peak);
        if (session.repeatedPaging) add(sessionSignals, RiskSignal.REPEATED_PAGING);
        if (session.highFrequency) add(sessionSignals, RiskSignal.HIGH_FREQUENCY);
        if (session.bytes >= config.sessionBytesThreshold()) add(sessionSignals, RiskSignal.HIGH_RESPONSE_VOLUME);
        Snapshot result = new Snapshot();
        result.user = userSignals;
        result.session = sessionSignals;
        result.sessionId = session.id;
        result.windowRequests = window.requests;
        result.windowBytes = window.bytes;
        result.sessionRequests = session.requests;
        result.sessionBytes = session.bytes;
        result.pages = pages;
        result.evictions = evictions;
        return result;
    }

    private boolean expired(Session session, long now) {
        return now - session.lastSeen >= config.sessionIdleSeconds() * 1000L
                || now - session.started >= config.sessionMaxSeconds() * 1000L;
    }

    private static Map<RiskSignal, Integer> peak(Map<RiskSignal, Integer> previous, Map<RiskSignal, Integer> current) {
        return RiskAssessment.score(current) > RiskAssessment.score(previous) ? new EnumMap<>(current) : previous;
    }

    public synchronized void clear() {
        sessions.clear();
        evictions = 0;
        lastSweep = Long.MIN_VALUE;
    }

    synchronized int trackedKeys() { return sessions.size(); }

    private void add(Map<RiskSignal, Integer> signals, RiskSignal signal) {
        signals.put(signal, config.weight(signal));
    }

    private static long plus(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static boolean hasStatistics(JSONObject input) {
        try {
            return new JSONArray(JsonUtils.optString(input, "outStatistics", "[]")).length() > 0;
        } catch (Exception ex) { return false; }
    }

    private static double envelopeArea(JSONObject input) {
        try {
            Object geometry = input.opt("geometry");
            if (geometry == null) return -1;
            String text = geometry.toString().trim();
            double[] bounds = new double[4];
            if (text.startsWith("{")) {
                JSONObject envelope = geometry instanceof JSONObject ? (JSONObject) geometry : new JSONObject(text);
                String[] names = {"xmin", "ymin", "xmax", "ymax"};
                for (int i = 0; i < 4; i++) bounds[i] = JsonUtils.optDouble(envelope, names[i], Double.NaN);
            } else {
                String[] parts = text.split(",", -1);
                if (parts.length != 4) return -1;
                for (int i = 0; i < 4; i++) bounds[i] = Double.parseDouble(parts[i].trim());
            }
            for (double bound : bounds) if (!Double.isFinite(bound)) return -1;
            double area = Math.abs((bounds[2] - bounds[0]) * (bounds[3] - bounds[1]));
            return Double.isFinite(area) ? area : -1;
        } catch (Exception ex) { return -1; }
    }

    private static String fingerprint(JSONObject input) {
        try {
            JSONArray names = input.names();
            List<String> keys = new ArrayList<>();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String key = names.getString(i);
                if (!"resultOffset".equalsIgnoreCase(key) && !"resultRecordCount".equalsIgnoreCase(key)
                        && !"token".equalsIgnoreCase(key) && !"f".equalsIgnoreCase(key)) keys.add(key);
            }
            Collections.sort(keys);
            StringBuilder canonical = new StringBuilder();
            for (String key : keys) {
                String value = String.valueOf(input.opt(key));
                canonical.append(key.length()).append(':').append(key).append(value.length()).append(':').append(value);
            }
            return digest(canonical.toString());
        } catch (Exception ex) { return null; }
    }

    private static String digest(String text) {
        try {
            byte[] hashed = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : hashed) result.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Risk hashing unavailable");
        }
    }

    private static final class Window {
        private final long started;
        private long requests;
        private long bytes;
        private boolean repeatedPaging;
        private Map<RiskSignal, Integer> peak = new EnumMap<>(RiskSignal.class);
        private Window(long started) { this.started = started; }
    }

    private static final class Session {
        private final String id = UUID.randomUUID().toString();
        private final long started;
        private long lastSeen;
        private long requests;
        private long bytes;
        private boolean repeatedPaging;
        private boolean highFrequency;
        private Window window;
        private Map<RiskSignal, Integer> peak = new EnumMap<>(RiskSignal.class);
        private final LinkedHashMap<String, Paging> queries = new LinkedHashMap<>(16, 0.75f, true);
        private Session(long now) { started = lastSeen = now; window = new Window(now); }
    }

    private static final class Paging {
        private long offset = -1;
        private long lastSeen = Long.MIN_VALUE;
        private long windowStarted;
        private long pages;
        private long windowPages;
    }

    private static final class Snapshot {
        private Map<RiskSignal, Integer> user;
        private Map<RiskSignal, Integer> session;
        private String sessionId = "NA";
        private long windowRequests, windowBytes, sessionRequests, sessionBytes, pages, evictions;
    }
}
