package com.example.craitrainer;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class YoloTfliteDetector implements AutoCloseable {
    private final Interpreter interpreter;
    private final List<String> labels;
    private final int inputW;
    private final int inputH;
    private final DataType inputType;
    private final int[] outputShape;
    private final DataType outputType;
    private final float confidenceThreshold;

    public YoloTfliteDetector(Context context, String modelAsset, String classesAsset, float confidenceThreshold) throws IOException {
        this.labels = readLabels(context, classesAsset);
        if (labels.isEmpty()) throw new IOException("classes.txt is empty");
        MappedByteBuffer model = loadModel(context, modelAsset);
        Interpreter.Options opts = new Interpreter.Options();
        opts.setNumThreads(Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 1)));
        opts.setUseXNNPACK(true);
        interpreter = new Interpreter(model, opts);
        Tensor in = interpreter.getInputTensor(0);
        int[] inShape = in.shape();
        if (inShape.length != 4) throw new IOException("Expected NHWC 4D input, got " + java.util.Arrays.toString(inShape));
        inputH = inShape[1];
        inputW = inShape[2];
        inputType = in.dataType();
        Tensor out = interpreter.getOutputTensor(0);
        outputShape = out.shape();
        outputType = out.dataType();
        if (outputType != DataType.FLOAT32) throw new IOException("Only FLOAT32 YOLO output is supported in this prototype");
        confidenceThreshold = Math.max(0.01f, Math.min(0.99f, confidenceThreshold));
    }

    public synchronized List<Detection> detect(Bitmap source) {
        Bitmap scaled = Bitmap.createScaledBitmap(source, inputW, inputH, true);
        ByteBuffer input = makeInputBuffer(scaled);
        int outElements = 1;
        for (int d : outputShape) outElements *= d;
        ByteBuffer output = ByteBuffer.allocateDirect(outElements * 4).order(ByteOrder.nativeOrder());
        interpreter.run(input, output);
        output.rewind();
        float[] raw = new float[outElements];
        output.asFloatBuffer().get(raw);
        if (scaled != source) scaled.recycle();
        return decodeYolo(raw, source.getWidth(), source.getHeight());
    }

    private ByteBuffer makeInputBuffer(Bitmap bitmap) {
        int bytesPerChannel = inputType == DataType.FLOAT32 ? 4 : 1;
        ByteBuffer buf = ByteBuffer.allocateDirect(inputW * inputH * 3 * bytesPerChannel).order(ByteOrder.nativeOrder());
        int[] pixels = new int[inputW * inputH];
        bitmap.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH);
        if (inputType == DataType.FLOAT32) {
            for (int px : pixels) {
                buf.putFloat(((px >> 16) & 0xFF) / 255f);
                buf.putFloat(((px >> 8) & 0xFF) / 255f);
                buf.putFloat((px & 0xFF) / 255f);
            }
        } else if (inputType == DataType.UINT8) {
            for (int px : pixels) {
                buf.put((byte) ((px >> 16) & 0xFF));
                buf.put((byte) ((px >> 8) & 0xFF));
                buf.put((byte) (px & 0xFF));
            }
        } else {
            throw new IllegalStateException("Unsupported input type: " + inputType);
        }
        buf.rewind();
        return buf;
    }

    private List<Detection> decodeYolo(float[] raw, int srcW, int srcH) {
        if (outputShape.length != 3 || outputShape[0] != 1) return new ArrayList<>();
        int a = outputShape[1];
        int b = outputShape[2];
        int featureCount = 4 + labels.size();
        boolean channelsFirst;
        int candidates;
        if (a == featureCount) {
            channelsFirst = true;
            candidates = b;
        } else if (b == featureCount) {
            channelsFirst = false;
            candidates = a;
        } else {
            return new ArrayList<>();
        }

        List<Detection> found = new ArrayList<>();
        for (int i = 0; i < candidates; i++) {
            float cx = value(raw, channelsFirst, candidates, i, 0, featureCount);
            float cy = value(raw, channelsFirst, candidates, i, 1, featureCount);
            float w = value(raw, channelsFirst, candidates, i, 2, featureCount);
            float h = value(raw, channelsFirst, candidates, i, 3, featureCount);
            int bestClass = -1;
            float best = -Float.MAX_VALUE;
            for (int c = 0; c < labels.size(); c++) {
                float score = value(raw, channelsFirst, candidates, i, 4 + c, featureCount);
                if (score > best) {
                    best = score;
                    bestClass = c;
                }
            }
            if (bestClass < 0 || best < confidenceThreshold) continue;

            boolean normalized = Math.max(Math.max(Math.abs(cx), Math.abs(cy)), Math.max(Math.abs(w), Math.abs(h))) <= 2.0f;
            if (normalized) {
                cx *= inputW; cy *= inputH; w *= inputW; h *= inputH;
            }
            float sx = srcW / (float) inputW;
            float sy = srcH / (float) inputH;
            found.add(new Detection(labels.get(bestClass), best, cx * sx, cy * sy, w * sx, h * sy));
        }
        return nms(found, 0.45f, 24);
    }

    private float value(float[] raw, boolean channelsFirst, int candidates, int candidate, int feature, int featureCount) {
        if (channelsFirst) return raw[feature * candidates + candidate];
        return raw[candidate * featureCount + feature];
    }

    private static List<Detection> nms(List<Detection> input, float iouThreshold, int max) {
        input.sort(Comparator.comparingDouble((Detection d) -> d.confidence).reversed());
        List<Detection> kept = new ArrayList<>();
        for (Detection d : input) {
            boolean suppress = false;
            for (Detection k : kept) {
                if (d.label.equals(k.label) && iou(d, k) > iouThreshold) {
                    suppress = true;
                    break;
                }
            }
            if (!suppress) {
                kept.add(d);
                if (kept.size() >= max) break;
            }
        }
        return kept;
    }

    private static float iou(Detection a, Detection b) {
        float ax1 = a.cx - a.w / 2f, ay1 = a.cy - a.h / 2f;
        float ax2 = a.cx + a.w / 2f, ay2 = a.cy + a.h / 2f;
        float bx1 = b.cx - b.w / 2f, by1 = b.cy - b.h / 2f;
        float bx2 = b.cx + b.w / 2f, by2 = b.cy + b.h / 2f;
        float ix1 = Math.max(ax1, bx1), iy1 = Math.max(ay1, by1);
        float ix2 = Math.min(ax2, bx2), iy2 = Math.min(ay2, by2);
        float inter = Math.max(0f, ix2 - ix1) * Math.max(0f, iy2 - iy1);
        float union = Math.max(1e-6f, a.w * a.h + b.w * b.h - inter);
        return inter / union;
    }

    private static MappedByteBuffer loadModel(Context context, String name) throws IOException {
        AssetFileDescriptor afd = context.getAssets().openFd(name);
        try (FileInputStream fis = new FileInputStream(afd.getFileDescriptor()); FileChannel ch = fis.getChannel()) {
            return ch.map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getDeclaredLength());
        } finally {
            afd.close();
        }
    }

    private static List<String> readLabels(Context context, String name) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(context.getAssets().open(name)))) {
            String line;
            while ((line = br.readLine()) != null) {
                String s = line.trim();
                if (!s.isEmpty() && !s.startsWith("#")) out.add(s);
            }
        }
        return out;
    }

    @Override public void close() { interpreter.close(); }
}
