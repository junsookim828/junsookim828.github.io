package com.example.craitrainer;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

public final class OverlayController {
    public interface Listener {
        void onStartMatch();
        void onReset();
        void onStopService();
    }

    private final Context context;
    private final WindowManager wm;
    private final LinearLayout root;
    private final TextView title;
    private final TextView elixirText;
    private final TextView handText;
    private final TextView eventText;
    private final TextView modelText;

    public OverlayController(Context context, Listener listener) {
        this.context = context;
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.setBackgroundColor(Color.argb(220, 17, 19, 24));

        title = text("TRAINING · CR AI", 13, Color.rgb(103, 232, 165));
        title.setTypeface(null, 1);
        root.addView(title);

        elixirText = text("Elixir: 5.0", 19, Color.WHITE);
        root.addView(elixirText);

        handText = text("Hand belief: waiting", 11, Color.rgb(220, 224, 232));
        handText.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(handText);

        eventText = text("Last: none", 10, Color.LTGRAY);
        root.addView(eventText);

        modelText = text("AI: loading", 10, Color.YELLOW);
        root.addView(modelText);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button start = new Button(context); start.setText("START");
        Button reset = new Button(context); reset.setText("RESET");
        Button stop = new Button(context); stop.setText("X");
        start.setOnClickListener(v -> listener.onStartMatch());
        reset.setOnClickListener(v -> listener.onReset());
        stop.setOnClickListener(v -> listener.onStopService());
        buttons.addView(start, new LinearLayout.LayoutParams(0, dp(42), 1f));
        buttons.addView(reset, new LinearLayout.LayoutParams(0, dp(42), 1f));
        buttons.addView(stop, new LinearLayout.LayoutParams(dp(52), dp(42)));
        root.addView(buttons);
    }

    public void show() {
        if (root.getParent() != null) return;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(300), WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(8);
        lp.y = dp(72);
        wm.addView(root, lp);
    }

    public void update(TrackerEngine.Snapshot s) {
        elixirText.setText(String.format(Locale.US, "Elixir  %.1f / 10   t=%.1fs", s.elixir, s.elapsed));
        handText.setText("HAND PROBABILITY\n" + TrackerEngine.formatHand(s.handProbabilities));
        eventText.setText("Last: " + s.lastEvent + "\nHypotheses: " + s.hypothesisCount + " · plays: " + s.detectedCount);
        title.setText(s.running ? "TRAINING · TRACKING" : "TRAINING · PRESS START");
    }

    public void setModelStatus(String text, boolean ok) {
        modelText.setText("AI: " + text);
        modelText.setTextColor(ok ? Color.rgb(103, 232, 165) : Color.rgb(255, 190, 80));
    }

    public void hide() {
        if (root.getParent() != null) wm.removeView(root);
    }

    private TextView text(String s, float sp, int color) {
        TextView tv = new TextView(context);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
