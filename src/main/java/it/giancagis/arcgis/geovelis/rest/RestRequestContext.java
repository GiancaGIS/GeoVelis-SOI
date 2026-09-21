package it.giancagis.arcgis.geovelis.rest;

/**
 * Immutable REST request context passed to rules and audit logging.
 */
public final class RestRequestContext {
    private final String userName;
    private final String capabilities;
    private final String resourceName;
    private final String operationName;
    private final String operationInput;
    private final String outputFormat;
    private final String requestProperties;

    public RestRequestContext(
            String userName,
            String capabilities,
            String resourceName,
            String operationName,
            String operationInput,
            String outputFormat,
            String requestProperties
    ) {
        this.userName = safe(userName);
        this.capabilities = safe(capabilities);
        this.resourceName = safe(resourceName);
        this.operationName = safe(operationName);
        this.operationInput = safe(operationInput);
        this.outputFormat = safe(outputFormat);
        this.requestProperties = safe(requestProperties);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public String userName() {
        return userName;
    }

    public String capabilities() {
        return capabilities;
    }

    public String resourceName() {
        return resourceName;
    }

    public String operationName() {
        return operationName;
    }

    public String operationInput() {
        return operationInput;
    }

    public String outputFormat() {
        return outputFormat;
    }

    public String requestProperties() {
        return requestProperties;
    }

    public RestRequestContext withOperationInput(String newOperationInput) {
        return new RestRequestContext(
                userName,
                capabilities,
                resourceName,
                operationName,
                newOperationInput,
                outputFormat,
                requestProperties
        );
    }

    public boolean isOperation(String expectedOperationName) {
        return expectedOperationName != null && expectedOperationName.equalsIgnoreCase(operationName);
    }

    public boolean isJsonOutput() {
        return outputFormat.isEmpty() || "json".equalsIgnoreCase(outputFormat);
    }

    public String layerId() {
        if (resourceName.isEmpty()) {
            return "";
        }

        String normalized = resourceName.replace('\\', '/');
        String[] parts = normalized.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i] == null ? "" : parts[i].trim();
            if (part.matches("\\d+")) {
                return part;
            }
        }
        return "";
    }
}
