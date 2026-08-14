package com.example.craitrainer;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AppConfig {
    private static final String PREF = "cr_ai_trainer";
    public static final String DEFAULT_DECK = "hog_rider,musketeer,ice_golem,ice_spirit,skeletons,cannon,fireball,the_log";
    public static final String DEFAULT_COSTS = "hog_rider=4,musketeer=4,ice_golem=2,ice_spirit=1,skeletons=1,cannon=3,fireball=4,the_log=2";

    public static final float DETECTION_CONFIDENCE = 0.55f;
    public static final int INFERENCE_FPS = 6;
    public static final float CROP_TOP_RATIO = 0.06f;
    public static final float CROP_BOTTOM_RATIO = 0.80f;
    public static final long DEDUPE_MS = 1300L;
    public static final float DEDUPE_DISTANCE_PX = 100f;

    private AppConfig() {}

    public static void save(Context context, String deck, String costs, float secPerElixir) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString("deck", deck)
                .putString("costs", costs)
                .putFloat("secPerElixir", secPerElixir)
                .apply();
    }

    public static String deckRaw(Context context) {
        return prefs(context).getString("deck", DEFAULT_DECK);
    }

    public static String costsRaw(Context context) {
        return prefs(context).getString("costs", DEFAULT_COSTS);
    }

    public static float secondsPerElixir(Context context) {
        return prefs(context).getFloat("secPerElixir", 2.8f);
    }

    public static List<String> deck(Context context) {
        List<String> out = new ArrayList<>();
        for (String token : deckRaw(context).split(",")) {
            String s = token.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public static Map<String, Integer> costs(Context context) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String token : costsRaw(context).split(",")) {
            String[] p = token.trim().split("=");
            if (p.length != 2) continue;
            try {
                out.put(p[0].trim(), Integer.parseInt(p[1].trim()));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
