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

import java.util.ArrayList;

public class ArtistAdapter extends RecyclerView.Adapter<ArtistAdapter.ArtistViewHolder> {

    private final Context mContext;
    private final ArrayList<String> artistList;

    public ArtistAdapter(Context mContext, ArrayList<String> artistList) {
        this.mContext = mContext;
        this.artistList = artistList;
    }

    @NonNull
    @Override
    public ArtistViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(mContext).inflate(R.layout.artist_item, parent, false);
        return new ArtistViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ArtistViewHolder holder, int position) {
        String artistName = artistList.get(position);
        holder.artistNameText.setText(artistName);
        
        // Count albums and songs for this artist
        int albumCount = countAlbumsForArtist(artistName);
        int songCount = countSongsForArtist(artistName);
        String subtitle = albumCount + " album" + (albumCount != 1 ? "s" : "") + 
                          " • " + songCount + " song" + (songCount != 1 ? "s" : "");
        holder.artistSubtitleText.setText(subtitle);
        
        holder.itemView.setOnClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition != RecyclerView.NO_POSITION) {
                Intent intent = new Intent(mContext, ArtistAlbumsActivity.class);
                intent.putExtra("artistName", artistList.get(adapterPosition));
                mContext.startActivity(intent);
            }
        });
    }

    @Override
    public int getItemCount() {
        return artistList.size();
    }

    private int countAlbumsForArtist(String artistName) {
        java.util.Set<String> albums = new java.util.HashSet<>();
        if (MainActivity.musicFiles != null) {
            for (MusicFiles music : MainActivity.musicFiles) {
                if (artistName.equals(music.getArtist())) {
                    String album = music.getAlbum();
                    if (album != null && !album.isEmpty()) {
                        albums.add(album);
                    }
                }
            }
        }
        return albums.size();
    }

    private int countSongsForArtist(String artistName) {
        int count = 0;
        if (MainActivity.musicFiles != null) {
            for (MusicFiles music : MainActivity.musicFiles) {
                if (artistName.equals(music.getArtist())) {
                    count++;
                }
            }
        }
        return count;
    }

    public void updateList(ArrayList<String> newList) {
        artistList.clear();
        artistList.addAll(newList);
        notifyDataSetChanged();
    }

    public static class ArtistViewHolder extends RecyclerView.ViewHolder {
        ImageView artistIcon;
        TextView artistNameText;
        TextView artistSubtitleText;

        public ArtistViewHolder(@NonNull View itemView) {
            super(itemView);
            artistIcon = itemView.findViewById(R.id.artist_icon);
            artistNameText = itemView.findViewById(R.id.artist_name);
            artistSubtitleText = itemView.findViewById(R.id.artist_subtitle);
        }
    }
}
