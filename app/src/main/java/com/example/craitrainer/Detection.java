package com.example.craitrainer;

public final class Detection {
    public final String label;
    public final float confidence;
    public final float cx;
    public final float cy;
    public final float w;
    public final float h;

    public Detection(String label, float confidence, float cx, float cy, float w, float h) {
        this.label = label;
        this.confidence = confidence;
        this.cx = cx;
        this.cy = cy;
        this.w = w;
        this.h = h;
    }
}
