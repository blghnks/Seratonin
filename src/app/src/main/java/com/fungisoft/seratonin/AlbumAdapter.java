package com.fungisoft.seratonin;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

public class AlbumAdapter extends RecyclerView.Adapter<AlbumAdapter.MyHolder> {

    private final Context mContext;
    private final ArrayList<MusicFiles> albumFiles;

    public AlbumAdapter(Context mContext, ArrayList<MusicFiles> albumFiles) {
        this.mContext = mContext;
        this.albumFiles = albumFiles;
    }

    @NonNull
    @NotNull
    @Override
    public MyHolder onCreateViewHolder(@NonNull @NotNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(mContext).inflate(R.layout.album_item, parent, false);
        return new MyHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull @NotNull MyHolder holder, int position) {
        holder.album_name.setText(albumFiles.get(position).getAlbum());
        // Use Album Artist tag, with fallback to track artist, then "Unknown Artist"
        String albumArtist = albumFiles.get(position).getAlbumArtist();
        if (albumArtist == null || albumArtist.isEmpty()) {
            albumArtist = albumFiles.get(position).getArtist();
        }
        if (albumArtist == null || albumArtist.isEmpty()) {
            albumArtist = "Unknown Artist";
        }
        holder.artist_name.setText(albumArtist);
        
        // Store for use in click handler
        final String displayedArtist = albumArtist;
        
        // Use async art loading to avoid blocking the UI thread during scrolling
        // This is the key fix for RecyclerView jank/stutter
        AlbumArtLoader.getInstance().loadAlbumArtForGrid(
                mContext, 
                albumFiles.get(position).getPath(), 
                holder.album_image,
                R.drawable.musicicon
        );
        
        holder.itemView.setOnClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition != RecyclerView.NO_POSITION) {
                Intent intent = new Intent(mContext, AlbumDetails.class);
                intent.putExtra("albumName", albumFiles.get(adapterPosition).getAlbum());
                intent.putExtra("artistName", displayedArtist);
                mContext.startActivity(intent);
            }
        });
    }

    @Override
    public int getItemCount() {
        return albumFiles.size();
    }

    public static class MyHolder extends RecyclerView.ViewHolder {

        ImageView album_image;
        TextView album_name, artist_name;
        public MyHolder(@NonNull @NotNull View itemView) {
            super(itemView);
            album_image = itemView.findViewById(R.id.album_image);
            album_name = itemView.findViewById(R.id.album_name);
            artist_name = itemView.findViewById(R.id.artist_name);
        }
    }
}
