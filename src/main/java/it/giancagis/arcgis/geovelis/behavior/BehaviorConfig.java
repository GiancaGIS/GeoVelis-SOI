package it.giancagis.arcgis.geovelis.behavior;

import com.esri.arcgis.system.IPropertySet;
import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** Endpoint, service scope and optional block threshold; secrets come from the process environment. */
public final class BehaviorConfig {
    private final URI endpoint;
    private final String serviceKey, secret, username, password;
    private int blockThreshold;

    private BehaviorConfig(URI endpoint, String serviceKey, String secret, String username, String password) {
        this.endpoint = endpoint;
        this.serviceKey = serviceKey;
        this.secret = secret;
        this.username = username;
        this.password = password;
    }

    public static BehaviorConfig disabled() { return new BehaviorConfig(null, "", "", "", ""); }
    public static BehaviorConfig fromPropertySet(IPropertySet props) throws IOException {
        return fromPropertySet(props, System.getenv());
    }
    static BehaviorConfig fromPropertySet(IPropertySet props, Map<String, String> env) throws IOException {
        String value = property(props, "riskRedisEndpoint");
        String thresholdValue = property(props, "riskBlockThreshold");
        int threshold;
        try { threshold = thresholdValue.isEmpty() ? 0 : Integer.parseInt(thresholdValue); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("riskBlockThreshold must be an integer from 0 to 100."); }
        if (threshold < 0 || threshold > 100) throw new IllegalArgumentException("riskBlockThreshold must be from 0 to 100.");
        if (value.isEmpty()) {
            if (threshold > 0) throw new IllegalArgumentException("riskBlockThreshold requires riskRedisEndpoint.");
            return disabled();
        }
        URI endpoint;
        try {
            endpoint = URI.create(value);
            if (!("redis".equals(endpoint.getScheme()) || "rediss".equals(endpoint.getScheme()))
                    || endpoint.getHost() == null || endpoint.getUserInfo() != null
                    || endpoint.getQuery() != null || endpoint.getFragment() != null
                    || endpoint.getPort() == 0 || endpoint.getPort() > 65535
                    || !(endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath())))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid riskRedisEndpoint: use redis://host:port or rediss://host:port without credentials or database path.");
        }
        String scope = property(props, "riskServiceKey");
        if (!scope.matches("[A-Za-z0-9_./-]{1,160}"))
            throw new IllegalArgumentException("riskServiceKey is required for Redis: use a stable site/service identifier.");
        String secret = env.getOrDefault("GEOVELIS_RISK_HMAC_KEY", "");
        if (secret.length() < 32)
            throw new IllegalArgumentException("GEOVELIS_RISK_HMAC_KEY must contain at least 32 characters and be identical across service instances.");
        String user = env.getOrDefault("GEOVELIS_REDIS_USERNAME", "");
        String password = env.getOrDefault("GEOVELIS_REDIS_PASSWORD", "");
        if (!user.isEmpty() && password.isEmpty())
            throw new IllegalArgumentException("GEOVELIS_REDIS_PASSWORD is required when a Redis username is configured.");
        BehaviorConfig config = new BehaviorConfig(endpoint, scope, secret, user, password);
        config.blockThreshold = threshold;
        return config;
    }
    private static String property(IPropertySet props, String name) throws IOException {
        Object value = props == null ? null : props.getProperty(name);
        return value == null ? "" : value.toString().trim();
    }
    public boolean enabled() { return endpoint != null; }
    public int blockThreshold() { return blockThreshold; }
    URI endpoint() { return endpoint; }
    String serviceKey() { return serviceKey; }
    String secret() { return secret; }
    String username() { return username; }
    String password() { return password; }
    @Override public String toString() { return "BehaviorConfig{redisEnabled=" + enabled() + ", blockThreshold=" + blockThreshold + "}"; }
}
