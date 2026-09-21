package it.giancagis.arcgis.geovelis.rules;

import java.util.Optional;

/**
 * Result produced by a GeoVelis rule.
 */
public final class RuleResult {

    public enum Decision {
        ALLOW,
        ALLOW_WITH_MODIFIED_INPUT,
        BLOCK
    }

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    private final Decision decision;
    private final Severity severity;
    private final String message;
    private final String modifiedOperationInput;

    private RuleResult(Decision decision, Severity severity, String message, String modifiedOperationInput) {
        this.decision = decision;
        this.severity = severity;
        this.message = message;
        this.modifiedOperationInput = modifiedOperationInput;
    }

    public static RuleResult allow() {
        return new RuleResult(Decision.ALLOW, Severity.INFO, null, null);
    }

    public static RuleResult warning(String message) {
        return new RuleResult(Decision.ALLOW, Severity.WARNING, message, null);
    }

    public static RuleResult block(String message) {
        return new RuleResult(Decision.BLOCK, Severity.ERROR, message, null);
    }

    public static RuleResult allowWithModifiedInput(String modifiedOperationInput, String message) {
        return new RuleResult(Decision.ALLOW_WITH_MODIFIED_INPUT, Severity.WARNING, message, modifiedOperationInput);
    }

    public Decision decision() {
        return decision;
    }

    public Severity severity() {
        return severity;
    }

    public boolean isBlocked() {
        return decision == Decision.BLOCK;
    }

    public boolean hasMessage() {
        return message != null && !message.trim().isEmpty();
    }

    public String message() {
        return message;
    }

    public Optional<String> modifiedOperationInput() {
        return Optional.ofNullable(modifiedOperationInput);
    }
}
