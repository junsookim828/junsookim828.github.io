package com.example.craitrainer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class DeploymentEventDebouncer {
    public static final class PlayEvent {
        public final String label;
        public final float confidence;
        public final long timeMs;
        public final float cx;
        public final float cy;

        PlayEvent(String label, float confidence, long timeMs, float cx, float cy) {
            this.label = label;
            this.confidence = confidence;
            this.timeMs = timeMs;
            this.cx = cx;
            this.cy = cy;
        }
    }

    private final long dedupeMs;
    private final float dedupeDistancePx;
    private final List<PlayEvent> recent = new ArrayList<>();

    public DeploymentEventDebouncer(long dedupeMs, float dedupeDistancePx) {
        this.dedupeMs = dedupeMs;
        this.dedupeDistancePx = dedupeDistancePx;
    }

    public synchronized List<PlayEvent> update(List<Detection> detections) {
        long now = System.nanoTime() / 1_000_000L;
        Iterator<PlayEvent> it = recent.iterator();
        while (it.hasNext()) {
            if (now - it.next().timeMs >= dedupeMs) it.remove();
        }
        List<PlayEvent> out = new ArrayList<>();
        for (Detection d : detections) {
            boolean duplicate = false;
            for (PlayEvent e : recent) {
                if (!e.label.equals(d.label)) continue;
                double dist = Math.hypot(e.cx - d.cx, e.cy - d.cy);
                if (dist <= dedupeDistancePx) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                PlayEvent event = new PlayEvent(d.label, d.confidence, now, d.cx, d.cy);
                recent.add(event);
                out.add(event);
            }
        }
        return out;
    }

    public synchronized void reset() { recent.clear(); }
}
