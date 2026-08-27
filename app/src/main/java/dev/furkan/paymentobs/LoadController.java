package dev.furkan.paymentobs;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/load")
public class LoadController {

    private final LoadGenerator generator;

    public LoadController(LoadGenerator generator) {
        this.generator = generator;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(
            @RequestParam(defaultValue = "200") int rps,
            @RequestParam(defaultValue = "good") String mode,
            @RequestParam(defaultValue = "2000") int binPool) {

        boolean high = "bad".equalsIgnoreCase(mode);
        LoadGenerator.State state = generator.start(rps, high, binPool);

        Map<String, Object> body = describe(state);
        body.put("expectedSeriesForAuthorizationCounter", expectedSeries(high, binPool));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        return describe(generator.stop());
    }


    @PostMapping("/spike")
    public Map<String, Object> spike(
            @RequestParam(defaultValue = "60") int seconds,
            @RequestParam(defaultValue = "paybull") String facilitator) {

        generator.spike(seconds, facilitator);
        Map<String, Object> body = describe(generator.state());
        body.put("spikeSeconds", seconds);
        body.put("spikeTarget", facilitator);
        return body;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return describe(generator.state());
    }

    private Map<String, Object> describe(LoadGenerator.State state) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", state.running());
        body.put("rps", state.rps());
        body.put("mode", state.highCardinality() ? "bad" : "good");
        body.put("binPool", state.binPoolSize());
        body.put("spiking", generator.spiking());
        return body;
    }


    private long expectedSeries(boolean highCardinality, int binPool) {
        long base = 10L * 3 * 2 * 4;
        return highCardinality ? base * binPool : base;
    }
}
