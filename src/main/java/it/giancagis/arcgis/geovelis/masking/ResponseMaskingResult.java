package it.giancagis.arcgis.geovelis.masking;

import java.util.Collections;
import java.util.List;

/**
 * Result produced by the response masking post-processor.
 */
public final class ResponseMaskingResult {

    public enum Decision {
        NOT_APPLICABLE,
        AUDIT_ONLY,
        MASKED,
        ERROR_KEEP_ORIGINAL,
        BLOCKED_ON_ERROR
    }

    private final Decision decision;
    private final byte[] response;
    private final int maskedFeatureCount;
    private final int maskedAttributeCount;
    private final List<String> maskedFields;
    private final String message;

    private ResponseMaskingResult(
            Decision decision,
            byte[] response,
            int maskedFeatureCount,
            int maskedAttributeCount,
            List<String> maskedFields,
            String message
    ) {
        this.decision = decision;
        this.response = response;
        this.maskedFeatureCount = maskedFeatureCount;
        this.maskedAttributeCount = maskedAttributeCount;
        this.maskedFields = maskedFields == null ? Collections.emptyList() : Collections.unmodifiableList(maskedFields);
        this.message = message;
    }

    public static ResponseMaskingResult notApplicable(byte[] response) {
        return new ResponseMaskingResult(Decision.NOT_APPLICABLE, response, 0, 0, Collections.emptyList(), null);
    }

    public static ResponseMaskingResult auditOnly(
            byte[] response,
            int maskedFeatureCount,
            int maskedAttributeCount,
            List<String> maskedFields
    ) {
        return new ResponseMaskingResult(
                Decision.AUDIT_ONLY,
                response,
                maskedFeatureCount,
                maskedAttributeCount,
                maskedFields,
                "Sensitive fields detected in the response but not masked because GeoVelis is in audit mode."
        );
    }

    public static ResponseMaskingResult masked(
            byte[] response,
            int maskedFeatureCount,
            int maskedAttributeCount,
            List<String> maskedFields
    ) {
        return new ResponseMaskingResult(
                Decision.MASKED,
                response,
                maskedFeatureCount,
                maskedAttributeCount,
                maskedFields,
                "Response masking applied."
        );
    }

    public static ResponseMaskingResult errorKeepOriginal(byte[] response, String message) {
        return new ResponseMaskingResult(Decision.ERROR_KEEP_ORIGINAL, response, 0, 0, Collections.emptyList(), message);
    }

    public static ResponseMaskingResult blockedOnError(byte[] response, String message) {
        return new ResponseMaskingResult(Decision.BLOCKED_ON_ERROR, response, 0, 0, Collections.emptyList(), message);
    }

    public Decision decision() {
        return decision;
    }

    public byte[] response() {
        return response;
    }

    public int maskedFeatureCount() {
        return maskedFeatureCount;
    }

    public int maskedAttributeCount() {
        return maskedAttributeCount;
    }

    public List<String> maskedFields() {
        return maskedFields;
    }

    public boolean hasAction() {
        return decision == Decision.AUDIT_ONLY
                || decision == Decision.MASKED
                || decision == Decision.ERROR_KEEP_ORIGINAL
                || decision == Decision.BLOCKED_ON_ERROR;
    }

    public String message() {
        return message;
    }
}
