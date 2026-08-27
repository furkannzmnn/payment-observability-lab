package dev.furkan.paymentobs;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class LoadGenerator {

    private static final Logger log = LoggerFactory.getLogger(LoadGenerator.class);
    private static final int TICKS_PER_SECOND = 10;

    public record State(boolean running, int rps, boolean highCardinality, int binPoolSize) {
        static State stopped() {
            return new State(false, 0, false, 0);
        }
    }

    private final PaymentMetrics metrics;
    private final Random rnd = new Random(42);
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "load-generator");
                t.setDaemon(true);
                return t;
            });

    private final AtomicReference<State> state = new AtomicReference<>(State.stopped());
    private final AtomicReference<String> spikeTarget = new AtomicReference<>("paybull");
    private final AtomicLong spikeUntilEpochMs = new AtomicLong(0);
    private final AtomicLong outboxOldestPendingSeconds = new AtomicLong(0);
    private long tickCount = 0;

    public LoadGenerator(PaymentMetrics metrics, MeterRegistry registry) {
        this.metrics = metrics;
        Gauge.builder("outbox.oldest_pending.age.seconds", outboxOldestPendingSeconds, AtomicLong::get)
                .description("En eski yayinlanmamis outbox kaydinin yasi")
                .register(registry);
    }

    @PostConstruct
    void schedule() {
        scheduler.scheduleAtFixedRate(this::tick, 0, 1000 / TICKS_PER_SECOND, TimeUnit.MILLISECONDS);
        log.info("Load generator hazir. Baslatmak icin: POST /load/start");
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    public State start(int rps, boolean highCardinality, int binPoolSize) {
        State next = new State(true, Math.max(1, rps), highCardinality, binPoolSize);
        state.set(next);
        log.info("Yuk basladi: rps={} highCardinality={} binPool={}", rps, highCardinality, binPoolSize);
        return next;
    }

    public State stop() {
        state.set(State.stopped());
        spikeUntilEpochMs.set(0);
        log.info("Yuk durduruldu");
        return State.stopped();
    }

    public long spike(int seconds, String facilitator) {
        long until = System.currentTimeMillis() + seconds * 1000L;
        spikeTarget.set(facilitator);
        spikeUntilEpochMs.set(until);
        log.info("Latency spike: {} facilitator'i {} saniye boyunca yavas", facilitator, seconds);
        return until;
    }

    public State state() {
        return state.get();
    }

    public boolean spiking() {
        return System.currentTimeMillis() < spikeUntilEpochMs.get();
    }

    public String currentSpikeTarget() {
        return spiking() ? spikeTarget.get() : null;
    }

    /**
     * scheduleAtFixedRate, task bir kez throw ederse sessizce durur.
     * Bu yuzden govde komple try/catch icinde.
     */
    private void tick() {
        try {
            State s = state.get();
            tickCount++;

            if (!s.running()) {
                outboxOldestPendingSeconds.set(0);
                return;
            }

            boolean spiking = spiking();
            String target = spiking ? spikeTarget.get() : null;
            int perTick = Math.max(1, s.rps() / TICKS_PER_SECOND);

            for (int i = 0; i < perTick; i++) {
                metrics.recordAuthorization(rnd, s.highCardinality(), s.binPoolSize());
                metrics.recordFacilitatorCall(rnd, target);
                metrics.recordThreeDsFunnel(rnd);
            }

            if (tickCount % TICKS_PER_SECOND == 0) {
                if (spiking) {
                    outboxOldestPendingSeconds.incrementAndGet();
                } else {
                    outboxOldestPendingSeconds.updateAndGet(v -> Math.max(0, v - 3));
                }
            }
        } catch (RuntimeException e) {
            log.error("tick basarisiz", e);
        }
    }
}
