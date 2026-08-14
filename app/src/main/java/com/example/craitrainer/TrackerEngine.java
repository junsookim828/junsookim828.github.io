package com.example.craitrainer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TrackerEngine {
    public static final class Snapshot {
        public final boolean running;
        public final double elapsed;
        public final double elixir;
        public final Map<String, Double> handProbabilities;
        public final String lastEvent;
        public final int hypothesisCount;
        public final int detectedCount;

        Snapshot(boolean running, double elapsed, double elixir, Map<String, Double> handProbabilities,
                 String lastEvent, int hypothesisCount, int detectedCount) {
            this.running = running;
            this.elapsed = elapsed;
            this.elixir = elixir;
            this.handProbabilities = handProbabilities;
            this.lastEvent = lastEvent;
            this.hypothesisCount = hypothesisCount;
            this.detectedCount = detectedCount;
        }
    }

    private final ElixirSimulator elixir;
    private final HandBelief handBelief;
    private final Map<String, Integer> costs;
    private final DeploymentEventDebouncer debouncer;
    private String lastEvent = "none";
    private int detectedCount = 0;

    public TrackerEngine(double secondsPerElixir, List<String> deck, Map<String, Integer> costs) {
        this.elixir = new ElixirSimulator(secondsPerElixir, 5.0, 10.0);
        this.handBelief = new HandBelief(deck);
        this.costs = new LinkedHashMap<>(costs);
        this.debouncer = new DeploymentEventDebouncer(AppConfig.DEDUPE_MS, AppConfig.DEDUPE_DISTANCE_PX);
    }

    public synchronized void startMatch() {
        elixir.start();
        handBelief.reset();
        debouncer.reset();
        lastEvent = "match started";
        detectedCount = 0;
    }

    public synchronized void reset() {
        elixir.reset();
        handBelief.reset();
        debouncer.reset();
        lastEvent = "reset";
        detectedCount = 0;
    }

    public synchronized void onDetections(List<Detection> detections) {
        if (!elixir.isRunning()) return;
        List<DeploymentEventDebouncer.PlayEvent> events = debouncer.update(detections);
        for (DeploymentEventDebouncer.PlayEvent e : events) {
            Integer cost = costs.get(e.label);
            if (cost == null) continue;
            elixir.spend(cost);
            boolean consistent = handBelief.observe(e.label);
            detectedCount++;
            lastEvent = String.format(Locale.US, "%s (-%d) %.0f%% %s", e.label, cost, e.confidence * 100,
                    consistent ? "" : "[cycle?]");
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(elixir.isRunning(), elixir.elapsed(), elixir.value(), handBelief.probabilities(),
                lastEvent, handBelief.hypothesisCount(), detectedCount);
    }

    public static String formatHand(Map<String, Double> probs) {
        List<Map.Entry<String, Double>> entries = new ArrayList<>(probs.entrySet());
        entries.sort(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Double> e : entries) {
            if (shown >= 8) break;
            sb.append(String.format(Locale.US, "%-17s %3.0f%%\n", e.getKey(), 100 * e.getValue()));
            shown++;
        }
        return sb.toString();
    }
}
