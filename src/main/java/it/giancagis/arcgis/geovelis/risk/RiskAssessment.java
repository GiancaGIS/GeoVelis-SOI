package it.giancagis.arcgis.geovelis.risk;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Contains no raw identity, SQL, token, coordinates, or response data. */
public final class RiskAssessment {
    public enum Outcome { RETURNED, RULE_BLOCKED, BEHAVIOR_BLOCKED, MASKING_BLOCKED, ERROR, NO_DELEGATE }

    private final Map<RiskSignal, Integer> contributions;
    private final int requestScore;
    private final int userScore;
    private final int sessionScore;
    private final String sessionId;
    private final String logMessage;

    RiskAssessment(RiskConfig config, Map<RiskSignal, Integer> request,
                   Map<RiskSignal, Integer> user, Map<RiskSignal, Integer> session,
                   String instanceId, String subject, String layer, String sessionId,
                   String correlation, String inputStatus, Outcome outcome,
                   long responseBytes, long elapsedMs, long windowRequests, long windowBytes,
                   long sessionRequests, long sessionBytes, long increasingPages, long evictions) {
        contributions = Collections.unmodifiableMap(new EnumMap<>(request));
        requestScore = score(request);
        userScore = user == null ? -1 : score(user);
        sessionScore = session == null ? -1 : score(session);
        this.sessionId = sessionId;
        logMessage = "[GeoVelis][RISK] scoreVersion=1, profile=" + config.profile()
                + ", decision=OBSERVE, scope=SOI_INSTANCE, instance=" + instanceId
                + ", subject=" + subject + ", layer=" + layer + ", session=" + sessionId
                + ", requestRiskScore=" + requestScore + ", requestRiskLevel=" + level(requestScore)
                + ", userRiskScore=" + display(userScore) + ", userRiskLevel=" + level(userScore)
                + ", sessionRiskScore=" + display(sessionScore) + ", sessionRiskLevel=" + level(sessionScore)
                + ", requestContributions=" + request + ", userContributions=" + (user == null ? "NA" : user)
                + ", sessionContributions=" + (session == null ? "NA" : session)
                + ", correlation=" + correlation + ", inputStatus=" + inputStatus + ", outcome=" + outcome
                + ", elapsedMs=" + Math.max(0, elapsedMs) + ", responseBytes=" + Math.max(0, responseBytes)
                + ", windowSeconds=" + config.windowSeconds() + ", windowRequests=" + windowRequests
                + ", windowResponseBytes=" + windowBytes + ", sessionRequests=" + sessionRequests
                + ", sessionResponseBytes=" + sessionBytes + ", increasingPages=" + increasingPages
                + ", capacityEvictions=" + evictions;
    }

    static int score(Map<RiskSignal, Integer> signals) {
        return Math.min(100, signals.values().stream().mapToInt(Integer::intValue).sum());
    }

    private static String display(int score) { return score < 0 ? "NA" : Integer.toString(score); }
    private static String level(int score) { return score < 0 ? "NA" : score >= 70 ? "HIGH" : score >= 40 ? "MEDIUM" : "LOW"; }
    public int score() { return requestScore; }
    public int userScore() { return userScore; }
    public int sessionScore() { return sessionScore; }
    public String sessionId() { return sessionId; }
    public String level() { return level(requestScore); }
    public Map<RiskSignal, Integer> contributions() { return contributions; }
    public String logMessage() { return logMessage; }
}
