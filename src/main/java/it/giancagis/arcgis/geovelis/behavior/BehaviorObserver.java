package it.giancagis.arcgis.geovelis.behavior;

import it.giancagis.arcgis.geovelis.rest.RestRequestContext;
import it.giancagis.arcgis.geovelis.risk.RiskAssessment;
import it.giancagis.arcgis.geovelis.risk.RiskConfig;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Bounded asynchronous delivery. No network I/O runs on the ArcGIS request thread. */
public final class BehaviorObserver implements AutoCloseable {
    private final BehaviorConfig config;
    private final RiskConfig risk;
    private final RedisBehaviorStore store;
    private final Consumer<String> logger, warning;
    private final ThreadPoolExecutor executor;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong nextWarning = new AtomicLong();
    private volatile long retryAfter;
    private volatile boolean closed;

    public BehaviorObserver(BehaviorConfig config, RiskConfig risk, Consumer<String> logger, Consumer<String> warning) {
        this(config,risk,false,logger,warning);
    }
    public BehaviorObserver(BehaviorConfig config, RiskConfig risk, boolean enforce, Consumer<String> logger, Consumer<String> warning) {
        this.config = config; this.risk = risk; this.logger = logger; this.warning = warning;
        this.store = new RedisBehaviorStore(config, risk, RedisBehaviorStore.loadScript(), enforce);
        executor = new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256),
                task -> { Thread thread = new Thread(task, "GeoVelis-Behavior"); thread.setDaemon(true); return thread; },
                new ThreadPoolExecutor.AbortPolicy()) {
            @Override protected void terminated() { store.close(); }
        };
        executor.allowCoreThreadTimeOut(true);
    }
    public void submit(RestRequestContext ctx, String effectiveInput, RiskAssessment assessment,
                       long bytes, RiskAssessment.Outcome outcome) {
        if (closed || outcome == RiskAssessment.Outcome.BEHAVIOR_BLOCKED) return;
        try {
            BehaviorEvent event = BehaviorEvent.create(config, ctx, effectiveInput, assessment, bytes, outcome, risk.profile());
            if (event == null) return;
            if (System.nanoTime() < retryAfter) { drop("REDIS_BACKOFF"); return; }
            executor.execute(() -> process(event));
        } catch (RejectedExecutionException ex) { drop("QUEUE_FULL_OR_CLOSED"); }
        catch (Exception ex) { drop("EVENT_UNAVAILABLE"); }
    }
    private void process(BehaviorEvent event) {
        if (closed) return;
        long now = System.nanoTime();
        if (now < retryAfter) { drop("REDIS_BACKOFF"); return; }
        if (now - event.createdNanos > TimeUnit.SECONDS.toNanos(30)) { drop("EVENT_EXPIRED"); return; }
        try {
            String message = store.observe(event);
            if (message != null) safe(logger, message + ", droppedEvents=" + dropped.get());
        } catch (Exception ex) {
            retryAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            drop("REDIS_UNAVAILABLE");
        }
    }
    private void drop(String reason) {
        long total = dropped.incrementAndGet();
        long now = System.nanoTime(), previous = nextWarning.get();
        if ((previous == 0 || now >= previous) && nextWarning.compareAndSet(previous, now + TimeUnit.SECONDS.toNanos(30))) {
            safe(warning, "[GeoVelis][BEHAVIOR] status=UNAVAILABLE, behaviorRiskScore=NA, reason="
                    + reason + ", droppedEvents=" + total + ", decision=OBSERVE");
        }
    }
    private static void safe(Consumer<String> sink, String message) {
        try { sink.accept(message); } catch (Exception ignored) { }
    }
    @Override public void close() {
        closed = true;
        dropped.addAndGet(executor.shutdownNow().size());
        // terminated() closes the connection after any current worker finishes or times out.
    }
}
