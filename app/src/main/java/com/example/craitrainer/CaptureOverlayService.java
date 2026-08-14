package com.example.craitrainer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CaptureOverlayService extends Service implements OverlayController.Listener {
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    private static final String CHANNEL_ID = "cr_ai_capture";
    private static final int NOTIFICATION_ID = 73;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private OverlayController overlay;
    private TrackerEngine engine;
    private YoloTfliteDetector detector;
    private final ExecutorService inferenceExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean inferenceBusy = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastInferenceMs = 0L;

    private final Runnable hudTicker = new Runnable() {
        @Override public void run() {
            if (overlay != null && engine != null) overlay.update(engine.snapshot());
            main.postDelayed(this, 100);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        if (projection != null) return START_NOT_STICKY;

        int resultCode = intent != null ? intent.getIntExtra(EXTRA_RESULT_CODE, ActivityResultCodes.CANCELED) : ActivityResultCodes.CANCELED;
        Intent resultData = intent != null ? getResultData(intent) : null;
        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            List<String> deck = AppConfig.deck(this);
            Map<String, Integer> costs = AppConfig.costs(this);
            engine = new TrackerEngine(AppConfig.secondsPerElixir(this), deck, costs);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }

        overlay = new OverlayController(this, this);
        overlay.show();
        try {
            detector = new YoloTfliteDetector(this, "best.tflite", "classes.txt", AppConfig.DETECTION_CONFIDENCE);
            overlay.setModelStatus("best.tflite loaded", true);
        } catch (Exception e) {
            detector = null;
            overlay.setModelStatus("MODEL MISSING / INVALID", false);
        }

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, resultData);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, main);
        startVirtualDisplay();
        main.post(hudTicker);
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Intent getResultData(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        return intent.getParcelableExtra(EXTRA_RESULT_DATA);
    }

    private void startVirtualDisplay() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect bounds = wm.getCurrentWindowMetrics().getBounds();
            dm.widthPixels = bounds.width();
            dm.heightPixels = bounds.height();
            dm.densityDpi = getResources().getDisplayMetrics().densityDpi;
        } else {
            wm.getDefaultDisplay().getRealMetrics(dm);
        }
        int width = dm.widthPixels;
        int height = dm.heightPixels;
        int density = dm.densityDpi;
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, null);
        virtualDisplay = projection.createVirtualDisplay(
                "CR-AI-Training-Capture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null);
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        long now = SystemClock.elapsedRealtime();
        long minGap = 1000L / AppConfig.INFERENCE_FPS;
        if (now - lastInferenceMs < minGap || detector == null || !inferenceBusy.compareAndSet(false, true)) {
            image.close();
            return;
        }
        lastInferenceMs = now;
        Bitmap full;
        try {
            full = imageToBitmap(image);
        } catch (Exception e) {
            image.close();
            inferenceBusy.set(false);
            return;
        }
        image.close();
        inferenceExecutor.execute(() -> {
            Bitmap crop = null;
            try {
                int top = Math.max(0, Math.round(full.getHeight() * AppConfig.CROP_TOP_RATIO));
                int bottom = Math.min(full.getHeight(), Math.round(full.getHeight() * AppConfig.CROP_BOTTOM_RATIO));
                crop = Bitmap.createBitmap(full, 0, top, full.getWidth(), Math.max(1, bottom - top));
                List<Detection> detections = detector.detect(crop);
                engine.onDetections(detections);
            } catch (Exception e) {
                main.post(() -> {
                    if (overlay != null) overlay.setModelStatus("inference error", false);
                });
            } finally {
                if (crop != null && crop != full && !crop.isRecycled()) crop.recycle();
                if (!full.isRecycled()) full.recycle();
                inferenceBusy.set(false);
            }
        });
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        int paddedWidth = image.getWidth() + rowPadding / pixelStride;
        Bitmap padded = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
        if (cropped != padded) padded.recycle();
        return cropped;
    }

    private void startAsForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("CR AI Trainer")
                .setContentText("봇전 훈련 화면을 분석 중")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "CR AI screen capture", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(channel);
    }

    @Override public void onStartMatch() { if (engine != null) engine.startMatch(); }
    @Override public void onReset() { if (engine != null) engine.reset(); }
    @Override public void onStopService() { stopSelf(); }

    @Override
    public void onDestroy() {
        main.removeCallbacks(hudTicker);
        if (overlay != null) overlay.hide();
        if (imageReader != null) imageReader.close();
        if (virtualDisplay != null) virtualDisplay.release();
        if (projection != null) projection.stop();
        if (detector != null) detector.close();
        inferenceExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static final class ActivityResultCodes {
        static final int CANCELED = android.app.Activity.RESULT_CANCELED;
    }
}
