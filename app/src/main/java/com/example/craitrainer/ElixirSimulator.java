package com.example.craitrainer;

public final class ElixirSimulator {
    private static long nowMs() { return System.nanoTime() / 1_000_000L; }
    private final double secondsPerElixirX1;
    private final double startingElixir;
    private final double maxElixir;
    private double value;
    private long startedAtMs = -1L;
    private long lastUpdateMs = -1L;

    public ElixirSimulator(double secondsPerElixirX1, double startingElixir, double maxElixir) {
        this.secondsPerElixirX1 = secondsPerElixirX1;
        this.startingElixir = startingElixir;
        this.maxElixir = maxElixir;
        this.value = startingElixir;
    }

    public synchronized void start() { start(0.0); }

    public synchronized void start(double elapsedSeconds) {
        long now = nowMs();
        startedAtMs = now - (long) (Math.max(0.0, elapsedSeconds) * 1000.0);
        lastUpdateMs = now;
        value = startingElixir;
        if (elapsedSeconds > 0) integrate(0.0, elapsedSeconds);
    }

    public synchronized void reset() {
        value = startingElixir;
        startedAtMs = -1L;
        lastUpdateMs = -1L;
    }

    public synchronized boolean isRunning() { return startedAtMs >= 0L; }

    public synchronized double tick() {
        if (!isRunning()) return value;
        long now = nowMs();
        double a = (lastUpdateMs - startedAtMs) / 1000.0;
        double b = (now - startedAtMs) / 1000.0;
        integrate(a, b);
        lastUpdateMs = now;
        return value;
    }

    public synchronized void spend(double cost) {
        tick();
        value = Math.max(0.0, value - cost);
    }

    public synchronized double value() { return tick(); }

    public synchronized double elapsed() {
        if (!isRunning()) return 0.0;
        return Math.max(0.0, (nowMs() - startedAtMs) / 1000.0);
    }

    public static double multiplier(double elapsed) {
        if (elapsed < 120.0) return 1.0;
        if (elapsed < 240.0) return 2.0;
        return 3.0;
    }

    private void integrate(double startElapsed, double endElapsed) {
        if (endElapsed <= startElapsed) return;
        double[] boundaries = {120.0, 240.0, endElapsed};
        double cur = startElapsed;
        for (double boundary : boundaries) {
            double segEnd = Math.min(endElapsed, boundary);
            if (segEnd > cur) {
                double gain = (segEnd - cur) * multiplier(cur) / secondsPerElixirX1;
                value = Math.min(maxElixir, value + gain);
                cur = segEnd;
            }
            if (cur >= endElapsed) break;
        }
    }
}
