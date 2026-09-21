package it.giancagis.arcgis.geovelis.behavior;

import it.giancagis.arcgis.geovelis.rest.RestRequestContext;
import java.util.concurrent.*;

/** Pre-delegate Redis check with a bounded worker pool and caller deadline. No cached allow/block decisions. */
public final class BehaviorBlockGate implements AutoCloseable {
    private static final String CHECK =
            "local v=redis.call('GET',KEYS[1]); if not v then return -2 end; "
            + "local ttl=redis.call('PTTL',KEYS[1]); "
            + "if v~='1' or ttl<0 or ttl>300000 then return -3 end; return ttl";
    private final BehaviorConfig config;
    private final String profile;
    private final boolean enforce;
    private final ThreadPoolExecutor workers;

    public BehaviorBlockGate(BehaviorConfig config, String profile, boolean enforce) {
        this.config = config; this.profile = profile; this.enforce = enforce;
        workers = new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),
                task -> { Thread thread = new Thread(task,"GeoVelis-BlockCheck"); thread.setDaemon(true); return thread; },
                new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
    }
    public Result check(RestRequestContext ctx) {
        if (!config.enabled() || config.blockThreshold() == 0 || !BehaviorEvent.eligible(ctx)) return Result.allowed();
        Future<Long> future = null;
        try {
            String key = BehaviorEvent.blockKey(BehaviorEvent.scopeKey(config,ctx,profile));
            future = workers.submit(() -> {
                try (RedisConnection redis = new RedisConnection(config,150)) {
                    return (Long) redis.command("EVAL",CHECK,"1",key);
                }
            });
            long ttl = future.get(250,TimeUnit.MILLISECONDS);
            if (ttl == -2 || ttl == 0) return Result.allowed();
            if (ttl < 0) return Result.unavailable("INVALID_BLOCK_KEY");
            return Result.active(key,ttl,enforce);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Result.unavailable("INTERRUPTED");
        } catch (Exception ex) {
            return Result.unavailable("REDIS_UNAVAILABLE_OR_BUSY");
        } finally {
            if (future != null && !future.isDone()) {
                future.cancel(true);
                workers.remove((Runnable) future);
            }
        }
    }
    @Override public void close() { workers.shutdownNow(); }

    public static final class Result {
        private final String decision, key, reason;
        private final long ttlMs;
        private Result(String decision, String key, long ttlMs, String reason) {
            this.decision=decision; this.key=key; this.ttlMs=ttlMs; this.reason=reason;
        }
        public static Result allowed() { return new Result("ALLOW","",0,""); }
        public static Result active(String key,long ttlMs,boolean enforce) {
            return new Result(enforce?"BLOCK":"AUDIT_ONLY",key,ttlMs,"TEMPORARY_BEHAVIOR_BLOCK");
        }
        private static Result unavailable(String reason) { return new Result("FAIL_OPEN","",0,reason); }
        public boolean blocked() { return "BLOCK".equals(decision); }
        public boolean reportable() { return !"ALLOW".equals(decision); }
        public long ttlMs() { return ttlMs; }
        public String logMessage() {
            return "[GeoVelis][BEHAVIOR_BLOCK] decision="+decision+", reason="+reason
                    +", ttlMs="+ttlMs+(key.isEmpty()?"":", blockKey="+key);
        }
    }
}
