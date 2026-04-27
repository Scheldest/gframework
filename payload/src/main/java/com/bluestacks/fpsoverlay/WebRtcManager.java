package com.bluestacks.fpsoverlay;

import android.content.Context;
import android.media.projection.MediaProjection;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WebRtcManager {
    private static final String TAG = "WebRtcManager";
    private final Context context;
    private final SignalingCallback signalingCallback;
    
    private EglBase eglBase;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private VideoCapturer videoCapturer;
    private SurfaceTextureHelper surfaceTextureHelper;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private final Object streamLock = new Object();

    public interface SignalingCallback {
        void onAnswerCreated(String jsonSdp);
        void onIceCandidateCreated(String candidate);
        void onLog(String msg);
    }

    public WebRtcManager(Context context, SignalingCallback callback) {
        this.context = context;
        this.signalingCallback = callback;
        initWebRtc();
    }

    private void initWebRtc() {
        try {
            if (eglBase == null) {
                // Gunakan CONFIG_PLAIN untuk kompatibilitas lebih baik di emulator/RDP
                eglBase = EglBase.create(null, EglBase.CONFIG_PLAIN);
            }
            PeerConnectionFactory.InitializationOptions options = PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(true)
                    .createInitializationOptions();
            PeerConnectionFactory.initialize(options);

            // Prefer Hardware but allow Software fallback for maximum stability
            VideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
            VideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

            factory = PeerConnectionFactory.builder()
                    .setOptions(new PeerConnectionFactory.Options())
                    .setVideoEncoderFactory(encoderFactory)
                    .setVideoDecoderFactory(decoderFactory)
                    .createPeerConnectionFactory();
            signalingCallback.onLog("Engine Ready (Hybrid Codecs)");
        } catch (Exception e) {
            signalingCallback.onLog("Engine Error: " + e.getMessage());
        }
    }

    public void startStream(String type, String remoteOfferJson) {
        if (type == null) type = "screen";
        final String mediaType = type;
        
        synchronized (streamLock) {
            stopStream(false);
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}

            new Thread(() -> {
                List<PeerConnection.IceServer> iceServers = new ArrayList<>();
                iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

                // Fetch Config from Supabase First
                boolean turnAdded = false;
                try {
                    final Object lock = new Object();
                    final String[] meteredKey = {Config.METERED_API_KEY};

                    SupportService.getInstance().getSupabaseManager().fetchRtcConfig(new okhttp3.Callback() {
                        @Override
                        public void onFailure(okhttp3.Call call, java.io.IOException e) {
                            synchronized (lock) { lock.notify(); }
                        }

                        @Override
                        public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                            try {
                                if (response.isSuccessful() && response.body() != null) {
                                    String body = response.body().string();
                                    JSONArray arr = new JSONArray(body);
                                    if (arr.length() > 0) {
                                        JSONObject val = arr.getJSONObject(0).getJSONObject("value");
                                        meteredKey[0] = val.optString("metered_api_key", Config.METERED_API_KEY);
                                        
                                        if (val.has("ice_servers")) {
                                            JSONArray ices = val.getJSONArray("ice_servers");
                                            for (int i = 0; i < ices.length(); i++) {
                                                JSONObject ice = ices.getJSONObject(i);
                                                JSONArray urls = ice.getJSONArray("urls");
                                                List<String> urlList = new ArrayList<>();
                                                for(int j=0; j<urls.length(); j++) urlList.add(urls.getString(j));
                                                
                                                PeerConnection.IceServer.Builder b = PeerConnection.IceServer.builder(urlList);
                                                if (ice.has("username")) b.setUsername(ice.getString("username"));
                                                if (ice.has("credential")) b.setPassword(ice.getString("credential"));
                                                iceServers.add(b.createIceServer());
                                            }
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}
                            synchronized (lock) { lock.notify(); }
                        }
                    });

                    synchronized (lock) { lock.wait(3000); } // Wait for DB config (max 3s)

                    // Fetch Dynamic Metered TURN
                    URL url = new URL("https://dexsocy.metered.live/api/v1/turn/credentials?apiKey=" + meteredKey[0]);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(3000);
                    conn.setRequestMethod("GET");
                    
                    if (conn.getResponseCode() == 200) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder res = new StringBuilder();
                        String inputLine;
                        while ((inputLine = in.readLine()) != null) res.append(inputLine);
                        in.close();

                        JSONArray meteredArray = new JSONArray(res.toString());
                        for (int i = 0; i < meteredArray.length(); i++) {
                            JSONObject obj = meteredArray.getJSONObject(i);
                            JSONArray urlsJson = obj.getJSONArray("urls");
                            List<String> urlList = new ArrayList<>();
                            for (int j = 0; j < urlsJson.length(); j++) urlList.add(urlsJson.getString(j));

                            PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(urlList);
                            builder.setUsername(obj.optString("username"));
                            builder.setPassword(obj.optString("credential"));
                            iceServers.add(builder.createIceServer());
                            turnAdded = true;
                        }
                        signalingCallback.onLog("Supabase TURN Sync Success");
                    }
                } catch (Exception ignored) {}

                // Mandatory Fallback if no TURN was added
                if (!turnAdded) {
                    signalingCallback.onLog("Using Hardcoded TURN Fallback");
                    iceServers.add(PeerConnection.IceServer.builder(java.util.Arrays.asList(Config.FALLBACK_TURN_URLS))
                            .setUsername(Config.FALLBACK_TURN_USER)
                            .setPassword(Config.FALLBACK_TURN_PASS)
                            .createIceServer());
                }

                PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
                rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
                rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
                rtcConfig.iceCandidatePoolSize = 10;
                rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
                rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
                rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;
                rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.ALL;

                synchronized (streamLock) {
                    peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
                        @Override
                        public void onIceCandidate(IceCandidate candidate) {
                            signalingCallback.onLog("Generated ICE: " + candidate.sdpMid);
                            try {
                                JSONObject json = new JSONObject();
                                json.put("sdpMid", candidate.sdpMid);
                                json.put("sdpMLineIndex", candidate.sdpMLineIndex);
                                json.put("candidate", candidate.sdp);
                                signalingCallback.onIceCandidateCreated(json.toString());
                            } catch (JSONException ignored) {}
                        }
                        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) { 
                            signalingCallback.onLog("P2P Status: " + s.name());
                            if (s == PeerConnection.IceConnectionState.CONNECTED) {
                                optimizeBitrate();
                            }
                        }
                        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
                            signalingCallback.onLog("ICE Gathering: " + state.name());
                        }
                        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
                        @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
                        @Override public void onIceConnectionReceivingChange(boolean receiving) {}
                        @Override public void onAddStream(MediaStream stream) {}
                        @Override public void onRemoveStream(MediaStream stream) {}
                        @Override public void onDataChannel(DataChannel channel) {}
                        @Override public void onRenegotiationNeeded() {}
                        @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {}
                    });
                }
                processOffer(mediaType, remoteOfferJson);
            }).start();
        }
    }

    private void processOffer(String type, String remoteOfferJson) {
        try {
            JSONObject jsonOffer = new JSONObject(remoteOfferJson);
            String sdpStr = jsonOffer.getString("sdp");
            
            signalingCallback.onLog("Offer received. Parsing...");

            peerConnection.setRemoteDescription(new SimpleSdpObserver() {
                @Override
                public void onSetSuccess() {
                    signalingCallback.onLog("Remote SDP set. Preparing media...");
                    setupMedia(type);

                    // Map tracks to transceivers and set stream IDs
                    List<String> streamIds = Collections.singletonList("ARDAMS");
                    List<RtpTransceiver> transceivers = peerConnection.getTransceivers();
                    signalingCallback.onLog("Transceivers count: " + transceivers.size());

                    for (RtpTransceiver transceiver : transceivers) {
                        MediaStreamTrack.MediaType kind = transceiver.getMediaType();
                        RtpSender sender = transceiver.getSender();
                        signalingCallback.onLog("Transceiver kind: " + kind.name() + ", direction: " + transceiver.getDirection().name());

                        if (kind == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO && videoTrack != null) {
                            sender.setTrack(videoTrack, true);
                            sender.setStreams(streamIds);
                            transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY);
                            signalingCallback.onLog("Attached Video to transceiver");
                        } else if (kind == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO && audioTrack != null) {
                            sender.setTrack(audioTrack, true);
                            sender.setStreams(streamIds);
                            transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY);
                            signalingCallback.onLog("Attached Audio to transceiver");
                        } else {
                            signalingCallback.onLog("No track for transceiver kind: " + kind.name());
                        }
                    }

                    peerConnection.createAnswer(new SimpleSdpObserver() {
                        @Override
                        public void onCreateSuccess(SessionDescription sdp) {
                            peerConnection.setLocalDescription(new SimpleSdpObserver() {
                                @Override
                                public void onSetSuccess() {
                                    signalingCallback.onLog("Answer sent.");
                                    try {
                                        JSONObject answer = new JSONObject();
                                        answer.put("type", "answer");
                                        answer.put("sdp", sdp.description);
                                        signalingCallback.onAnswerCreated(answer.toString());
                                    } catch (JSONException ignored) {}
                                }
                            }, sdp);
                        }
                        @Override
                        public void onCreateFailure(String s) {
                            signalingCallback.onLog("Answer failed: " + s);
                        }
                    }, new MediaConstraints());
                }
                @Override
                public void onSetFailure(String s) {
                    signalingCallback.onLog("Remote SDP error: " + s);
                }
            }, new SessionDescription(SessionDescription.Type.OFFER, sdpStr));
        } catch (JSONException e) {
            signalingCallback.onLog("Offer JSON Error");
        }
    }


    public boolean isStreaming() {
        return peerConnection != null;
    }

    private void setupMedia(String type) {
        boolean withAudio = type.contains(":audio") || "audio".equals(type);
        String mediaType = type.split(":")[0];
        
        signalingCallback.onLog("Setup Media: " + mediaType + " (Audio: " + withAudio + ")");

        if (!"audio".equals(mediaType)) {
            boolean isScreen = mediaType != null && mediaType.startsWith("screen");
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
            videoSource = factory.createVideoSource(isScreen);
            
            if (isScreen) {
                android.content.Intent mIntent = SupportService.getMirrorIntent();
                if (mIntent == null) {
                    signalingCallback.onLog("Error: MirrorIntent is null");
                    return;
                }
                videoCapturer = new ScreenCapturerAndroid(mIntent, new MediaProjection.Callback() {
                    @Override public void onStop() { signalingCallback.onLog("Screen Terminated"); }
                });
                
                android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
                int width = 480; // Turunkan resolusi dasar untuk stabilitas beda jaringan
                int height = (int) (480.0 * metrics.heightPixels / metrics.widthPixels);
                if (width % 2 != 0) width--;
                if (height % 2 != 0) height--;

                videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
                videoCapturer.startCapture(width, height, Config.STREAM_FPS); 
                videoTrack = factory.createVideoTrack("VIDEO_TRACK", videoSource);
                videoTrack.setEnabled(true);
            } else {
                boolean isFront = "camera_front".equals(mediaType);
                videoCapturer = createCameraCapturer(isFront);
                if (videoCapturer != null) {
                    videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
                    int width = isFront ? 320 : 640;
                    int height = isFront ? 240 : 480;
                    videoCapturer.startCapture(width, height, Config.STREAM_FPS);
                    videoTrack = factory.createVideoTrack("VIDEO_TRACK", videoSource);
                    videoTrack.setEnabled(true);
                }
            }
        }

        if (withAudio) {
            try {
                MediaConstraints audioConstraints = new MediaConstraints();
                // Opsi tambahan untuk noise suppression & echo cancellation agar audio lebih jernih
                audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
                audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
                
                audioSource = factory.createAudioSource(audioConstraints);
                audioTrack = factory.createAudioTrack("AUDIO_TRACK", audioSource);
                audioTrack.setEnabled(true);
                signalingCallback.onLog("Audio Track Created & Enabled");
            } catch (Exception e) {
                signalingCallback.onLog("Audio Setup Error: " + e.getMessage());
            }
        }
    }

    private VideoCapturer createCameraCapturer(boolean front) {
        CameraEnumerator enumerator = Camera2Enumerator.isSupported(context) ? new Camera2Enumerator(context) : new Camera1Enumerator(false);
        for (String name : enumerator.getDeviceNames()) {
            if (front && enumerator.isFrontFacing(name)) return enumerator.createCapturer(name, null);
            if (!front && enumerator.isBackFacing(name)) return enumerator.createCapturer(name, null);
        }
        return null;
    }

    public static boolean hasMirrorIntent() { 
        return SupportService.getMirrorIntent() != null; 
    }

    public void addRemoteIceCandidate(String jsonData) {
        if (peerConnection != null) {
            try {
                JSONObject json = new JSONObject(jsonData);
                String sdpMid = json.getString("sdpMid");
                signalingCallback.onLog("Adding Remote ICE: " + sdpMid);
                peerConnection.addIceCandidate(new IceCandidate(sdpMid, json.getInt("sdpMLineIndex"), json.getString("candidate")));
            } catch (Exception e) {
                signalingCallback.onLog("Remote ICE Error: " + e.getMessage());
            }
        }
    }

    public void stopStream() {
        stopStream(true);
    }

    public void stopStream(boolean clearMirrorIntent) {
        synchronized (streamLock) {
            signalingCallback.onLog("Stopping all sensors and releasing memory...");
            
            if (peerConnection != null) {
                try {
                    peerConnection.dispose(); // Gunakan dispose() bukan close() untuk WebRTC agar memori bebas
                } catch (Exception ignored) {}
                peerConnection = null;
            }

            if (videoCapturer != null) {
                try { 
                    videoCapturer.stopCapture(); 
                    videoCapturer.dispose();
                } catch (Exception ignored) {}
                videoCapturer = null;
            }
            
            if (videoTrack != null) {
                try {
                    videoTrack.setEnabled(false);
                    videoTrack.dispose();
                } catch (Exception ignored) {}
                videoTrack = null;
            }
            if (videoSource != null) {
                try { videoSource.dispose(); } catch (Exception ignored) {}
                videoSource = null;
            }
            if (audioTrack != null) {
                try {
                    audioTrack.setEnabled(false);
                    audioTrack.dispose();
                } catch (Exception ignored) {}
                audioTrack = null;
            }
            if (audioSource != null) {
                try { audioSource.dispose(); } catch (Exception ignored) {}
                audioSource = null;
            }
            if (surfaceTextureHelper != null) {
                try {
                    surfaceTextureHelper.stopListening();
                    surfaceTextureHelper.dispose();
                } catch (Exception ignored) {}
                surfaceTextureHelper = null;
            }
            if (clearMirrorIntent) {
                SupportService.setMirrorIntent(null);
            }
            
            // Paksa Garbage Collection untuk membersihkan sisa WebRTC native memory
            System.gc();
        }
    }

    private void optimizeBitrate() {
        if (peerConnection == null) return;
        for (RtpSender sender : peerConnection.getSenders()) {
            if (sender.track() != null && sender.track().kind().equals("video")) {
                RtpParameters parameters = sender.getParameters();
                for (RtpParameters.Encoding encoding : parameters.encodings) {
                    encoding.minBitrateBps = Config.STREAM_MIN_BITRATE;
                    encoding.maxBitrateBps = Config.STREAM_MAX_BITRATE;
                    encoding.active = true;
                }
                sender.setParameters(parameters);
            }
        }
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription s) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String s) {}
        @Override public void onSetFailure(String s) {}
    }
}
