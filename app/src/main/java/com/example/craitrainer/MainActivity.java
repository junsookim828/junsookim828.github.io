package com.example.craitrainer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 4101;
    private static final int REQ_NOTIFICATION = 4102;

    private EditText deckInput;
    private EditText costInput;
    private EditText secInput;
    private TextView statusText;
    private MediaProjectionManager projectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        deckInput = findViewById(R.id.deckInput);
        costInput = findViewById(R.id.costInput);
        secInput = findViewById(R.id.secPerElixirInput);
        statusText = findViewById(R.id.statusText);

        deckInput.setText(AppConfig.deckRaw(this));
        costInput.setText(AppConfig.costsRaw(this));
        secInput.setText(String.valueOf(AppConfig.secondsPerElixir(this)));

        Button save = findViewById(R.id.saveButton);
        Button overlay = findViewById(R.id.overlayPermissionButton);
        Button capture = findViewById(R.id.startCaptureButton);
        Button stop = findViewById(R.id.stopButton);

        save.setOnClickListener(v -> saveSettings(true));
        overlay.setOnClickListener(v -> requestOverlayPermission());
        capture.setOnClickListener(v -> beginCapture());
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, CaptureOverlayService.class));
            statusText.setText("상태: 중지 요청됨");
        });

        requestNotificationPermissionIfNeeded();
    }

    private boolean saveSettings(boolean toast) {
        String deck = deckInput.getText().toString().trim();
        String costs = costInput.getText().toString().trim();
        float sec;
        try {
            sec = Float.parseFloat(secInput.getText().toString().trim());
        } catch (Exception e) {
            statusText.setText("상태: 엘릭서 시간이 숫자가 아님");
            return false;
        }
        AppConfig.save(this, deck, costs, sec);
        List<String> d = AppConfig.deck(this);
        Map<String, Integer> c = AppConfig.costs(this);
        if (d.size() != 8 || d.stream().distinct().count() != 8L) {
            statusText.setText("상태: 상대 덱은 서로 다른 8개 label이어야 함");
            return false;
        }
        for (String card : d) {
            if (!c.containsKey(card)) {
                statusText.setText("상태: 비용 누락: " + card);
                return false;
            }
        }
        if (toast) Toast.makeText(this, "설정 저장됨", Toast.LENGTH_SHORT).show();
        statusText.setText("상태: 설정 정상");
        return true;
    }

    private void requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "오버레이 권한이 이미 있음", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void beginCapture() {
        if (!saveSettings(false)) return;
        if (!Settings.canDrawOverlays(this)) {
            statusText.setText("상태: 먼저 '다른 앱 위에 표시' 권한을 허용해줘");
            requestOverlayPermission();
            return;
        }
        statusText.setText("상태: Android 화면 공유 권한 요청 중");
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            statusText.setText("상태: 화면 캡처 권한 거절됨");
            return;
        }
        Intent service = new Intent(this, CaptureOverlayService.class);
        service.putExtra(CaptureOverlayService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(CaptureOverlayService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
        statusText.setText("상태: 추적 시작됨. 게임으로 이동 후 HUD에서 START");
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        }
    }
}
