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

public class AlbumDetailsAdapter extends RecyclerView.Adapter<AlbumDetailsAdapter.MyHolder> {

    private final Context mContext;
    static ArrayList<MusicFiles> albumFiles;
    View view;
    static byte[] passAlbumImage;
    static boolean checkAlbumPass;

    public AlbumDetailsAdapter(Context mContext, ArrayList<MusicFiles> albumFiles) {
        this.mContext = mContext;
        AlbumDetailsAdapter.albumFiles = albumFiles;
    }

    @NonNull
    @NotNull
    @Override
    public MyHolder onCreateViewHolder(@NonNull @NotNull ViewGroup parent, int viewType) {
        view = LayoutInflater.from(mContext).inflate(R.layout.album_music_items, parent, false);
        return new MyHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull @NotNull MyHolder holder, int position) {
        holder.album_name.setText(albumFiles.get(position).getTitle());
        holder.artist_name.setText(albumFiles.get(position).getArtist());
        int duration = Integer.parseInt(albumFiles.get(position).getDuration()) / 1000;
        holder.alb_duration.setText(formattedTime(duration));
        // Use song art loading: embedded first, then external fallback
        byte[] image = AlbumArtHelper.getAlbumArtForSong(mContext, albumFiles.get(position).getPath());
        checkAlbumPass = true;
        passAlbumImage = image;
        if (image != null){
            Glide.with(mContext).asBitmap()
                    .load(image)
                    .into(holder.album_image);
        }
        else {
            Glide.with(mContext)
                    .load(R.drawable.musicicon)
                    .into(holder.album_image);
        }
        holder.itemView.setOnClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition == RecyclerView.NO_POSITION) return;
//                Boolean isAlbumDetailsPlaying = mContext.getSharedPreferences("PreferenceAlbumPlay", MODE_PRIVATE).getBoolean("isAlbumPlaying", true);
//                if (isAlbumDetailsPlaying){
//                    NowPlayingFragmentBottom.setLayoutVisible();
////            getSharedPreferences("PreferenceAlbumPlay", MODE_PRIVATE).edit().putBoolean("isAlbumPlaying", false).apply();
//                }
//                Uri uri = Uri.parse(albumFiles.get(adapterPosition).getPath());
//                SharedPreferences.Editor editor = mContext.getSharedPreferences(MUSIC_LAST_PLAYED, MODE_PRIVATE).edit();
//                editor.putString(MUSIC_FILE, uri.toString());
//                editor.putString(ARTIST_NAME, albumFiles.get(adapterPosition).getArtist());
//                editor.putString(SONG_NAME, albumFiles.get(adapterPosition).getTitle());
//                editor.apply();
//                if (image != null){
//                    Glide.with(mContext).load(getAlbumArt(albumFiles.get(adapterPosition).getPath()))
//                            .into(NowPlayingFragmentBottom.albumArt);
//                }else{
////                Toast.makeText(musicService, "Art is NULL!!!", Toast.LENGTH_SHORT).show();
//                }
//                NowPlayingFragmentBottom.songName.setText(albumFiles.get(adapterPosition).getTitle());
//                NowPlayingFragmentBottom.artist.setText(albumFiles.get(adapterPosition).getArtist());
            Intent intent = new Intent(mContext, PlayerActivity.class);
            intent.putExtra("sender", "albumDetails");
            intent.putExtra("positionAlbum", adapterPosition);
            mContext.startActivity(intent);
//                Toast.makeText(mContext, "Album Clicked!!!!", Toast.LENGTH_SHORT).show();
        });
    }

    private String formattedTime(int albCurrentPosition) {
        String totalout;
        String totalNew;
        String seconds = String.valueOf(albCurrentPosition % 60);
        String minutes = String.valueOf(albCurrentPosition / 60);
        totalout = minutes + ":" + seconds;
        totalNew = minutes + ":" + "0" + seconds;
        if (seconds.length() == 1){
            return totalNew;
        }
        else{
            return totalout;
        }
    }

    @Override
    public int getItemCount() {
        return albumFiles.size();
    }

    public static class MyHolder extends RecyclerView.ViewHolder {

        ImageView album_image;
        TextView album_name, artist_name, alb_duration;
        public MyHolder(@NonNull @NotNull View itemView) {
            super(itemView);
            album_image = itemView.findViewById(R.id.music_img);
            album_name = itemView.findViewById(R.id.music_file_name);
            artist_name = itemView.findViewById(R.id.artis_name);
            alb_duration = itemView.findViewById(R.id.alb_duration);
        }
    }
}
