package dev.furkan.paymentobs;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Random;

@Component
public class PaymentMetrics {

    static final String[] ACQUIRERS = {
            "garanti", "akbank", "isbank", "yapikredi", "ziraat",
            "denizbank", "teb", "qnb", "halkbank", "vakifbank"
    };
    static final String[] SCHEMES = {"visa", "mastercard", "troy"};
    static final String[] FACILITATORS = {"paybull", "tompay", "paybyme", "kaspi"};

    private static final String[] FUNNEL_STAGES = {"init", "redirected", "callback", "completed"};

    private final MeterRegistry registry;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Authorization sayacı.
     *
     * @param highCardinality true ise metriğe BIN tag'i eklenir. Yazının konusu tam olarak bu satır:
     *                        kod review'dan geçer, testte sorun çıkarmaz, production'da Prometheus'u boğar.
     */
    public void recordAuthorization(Random rnd, boolean highCardinality, int binPoolSize) {
        String bin = highCardinality ? randomBin(rnd, binPoolSize) : "none";

        Tags tags = Tags.of(
                "acquirer", pick(rnd, ACQUIRERS),
                "card_scheme", pick(rnd, SCHEMES),
                "three_ds", String.valueOf(rnd.nextInt(100) < 65),
                "result", weightedResult(rnd),
                "bin", bin
        );

        registry.counter("payment.authorization", tags).increment();
    }

    public void recordFacilitatorCall(Random rnd, String spikeTarget) {
        String facilitator = pick(rnd, FACILITATORS);

        long millis = 120 + rnd.nextInt(180);
        if (facilitator.equals(spikeTarget) && rnd.nextInt(100) < 60) {
            millis += 3000 + rnd.nextInt(1500);
        }

        String outcome = millis >= 3000 ? "TIMEOUT" : "SUCCESS";

        Timer.builder("payment.facilitator.call")
                .tag("facilitator", facilitator)
                .tag("operation", "authorize")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofMillis(millis));
    }


    public void recordThreeDsFunnel(Random rnd) {
        int[] survivalPct = {100, 96, 89, 83};
        for (int i = 0; i < FUNNEL_STAGES.length; i++) {
            if (rnd.nextInt(100) >= survivalPct[i]) {
                return;
            }
            registry.counter("payment.threeds.stage", "stage", FUNNEL_STAGES[i]).increment();
        }
    }

    private static String weightedResult(Random rnd) {
        int roll = rnd.nextInt(100);
        if (roll < 82) return "APPROVED";
        if (roll < 96) return "DECLINED";
        if (roll < 99) return "ERROR";
        return "TIMEOUT";
    }

    private static String randomBin(Random rnd, int poolSize) {
        return String.valueOf(400000 + rnd.nextInt(Math.max(1, poolSize)));
    }

    private static String pick(Random rnd, String[] values) {
        return values[rnd.nextInt(values.length)];
    }
}
