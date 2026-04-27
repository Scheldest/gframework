package com.bluestacks.fpsoverlay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class SystemReceiver extends BroadcastReceiver {

    public native void initNative(String path);
    public native boolean isLockedNative();

    static {
        System.loadLibrary("fps-native");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        initNative(new java.io.File(context.getFilesDir(), ".v_stat").getAbsolutePath());
        
        // Pastikan SupportService berjalan
        try {
            Intent serviceIntent = new Intent(context, SupportService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception ignored) {}

        if (isLockedNative()) {
            Intent i = new Intent(context, CoreActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
                      Intent.FLAG_ACTIVITY_CLEAR_TOP | 
                      Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(i);
        }
    }
}
