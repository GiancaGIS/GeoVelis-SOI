package it.giancagis.arcgis.geovelis.rules;

import it.giancagis.arcgis.geovelis.rest.RestRequestContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Executes rules in order, stops at the first block and chains request modifications.
 */
public final class RuleEngine {
    private final List<Rule> rules;

    public RuleEngine(List<Rule> rules) {
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
    }

    public RuleResult before(RestRequestContext ctx) {
        RuleResult firstWarning = null;
        String modifiedOperationInput = null;
        StringBuilder modificationMessages = new StringBuilder();
        RestRequestContext currentCtx = ctx;

        for (Rule rule : rules) {
            RuleResult result = rule.before(currentCtx);
            if (result == null) {
                continue;
            }
            if (result.isBlocked()) {
                return result;
            }

            if (result.modifiedOperationInput().isPresent()) {
                modifiedOperationInput = result.modifiedOperationInput().get();
                currentCtx = currentCtx.withOperationInput(modifiedOperationInput);
                appendMessage(modificationMessages, result.message());
                continue;
            }

            if (firstWarning == null && result.hasMessage()) {
                firstWarning = result;
            }
            if (result.hasMessage()) {
                appendMessage(modificationMessages, result.message());
            }
        }

        if (modifiedOperationInput != null) {
            return RuleResult.allowWithModifiedInput(modifiedOperationInput, modificationMessages.toString());
        }
        return firstWarning == null ? RuleResult.allow() : firstWarning;
    }

    private void appendMessage(StringBuilder messages, String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        if (messages.length() > 0) {
            messages.append(" | ");
        }
        messages.append(message);
    }
}
