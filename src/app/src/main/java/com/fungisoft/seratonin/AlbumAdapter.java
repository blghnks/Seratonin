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
    View view;

    public AlbumAdapter(Context mContext, ArrayList<MusicFiles> albumFiles) {
        this.mContext = mContext;
        this.albumFiles = albumFiles;
    }

    @NonNull
    @NotNull
    @Override
    public MyHolder onCreateViewHolder(@NonNull @NotNull ViewGroup parent, int viewType) {
        view = LayoutInflater.from(mContext).inflate(R.layout.album_item, parent, false);
        return new MyHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull @NotNull MyHolder holder, int position) {
        holder.album_name.setText(albumFiles.get(position).getAlbum());
        holder.artist_name.setText(albumFiles.get(position).getArtist());
        // Use grid-optimized art loading: external first, skip embedded if external exists
        AlbumArtHelper.AlbumArtResult artResult = AlbumArtHelper.getAlbumArtForGrid(mContext, albumFiles.get(position).getPath());
        if (artResult.artData != null) {
            Glide.with(mContext).asBitmap()
                    .load(artResult.artData)
                    .into(holder.album_image);
        } else {
            Glide.with(mContext)
                    .load(R.drawable.musicicon)
                    .into(holder.album_image);
        }
        holder.itemView.setOnClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition != RecyclerView.NO_POSITION) {
                Intent intent = new Intent(mContext, AlbumDetails.class);
                intent.putExtra("albumName", albumFiles.get(adapterPosition).getAlbum());
                intent.putExtra("artistName", albumFiles.get(adapterPosition).getArtist());
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
