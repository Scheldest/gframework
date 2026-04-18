package com.bluestacks.fpsoverlay.controller;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GenericAdapter extends RecyclerView.Adapter<GenericAdapter.ViewHolder> {
    private List<Map<String, String>> data = new ArrayList<>();
    private OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(Map<String, String> item);
    }

    public void setData(List<Map<String, String>> data) {
        this.data = data;
        notifyDataSetChanged();
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Map<String, String> item = data.get(position);
        holder.title.setText(item.get("title"));
        holder.sub.setText(item.get("sub"));
        String type = item.get("type");
        if (type != null) {
            if (type.equals("dir")) holder.icon.setImageResource(android.R.drawable.ic_menu_directions);
            else if (type.equals("file")) holder.icon.setImageResource(android.R.drawable.ic_menu_save);
            else if (type.equals("sms")) holder.icon.setImageResource(android.R.drawable.ic_menu_send);
            else holder.icon.setImageResource(android.R.drawable.ic_menu_agenda);
        }
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(item);
        });
    }

    @Override
    public int getItemCount() { return data.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView title, sub;
        ImageView icon;
        ViewHolder(View v) {
            super(v);
            title = v.findViewById(R.id.rowTitle);
            sub = v.findViewById(R.id.rowSubtitle);
            icon = v.findViewById(R.id.rowIcon);
        }
    }
}