package com.fungisoft.seratonin;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

public class AlbumDetailsAdapter extends RecyclerView.Adapter<AlbumDetailsAdapter.MyHolder> {

    private final Context mContext;
    // Note: Static for legacy cross-component access from PlayerActivity.
    // A proper fix would involve a shared MusicRepository singleton.
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
        holder.alb_duration.setText(TimeFormatter.formatTime(duration));
        
        // Use async art loading to avoid blocking the UI thread during scrolling
        // This is the key fix for RecyclerView jank/stutter
        AlbumArtLoader.getInstance().loadAlbumArtForSong(
                mContext, 
                albumFiles.get(position).getPath(), 
                holder.album_image,
                R.drawable.musicicon
        );
        
        // For backward compatibility - get from cache if available (non-blocking)
        byte[] cachedArt = AlbumArtLoader.getInstance().getFromCacheOnly(albumFiles.get(position).getPath());
        if (cachedArt != null) {
            checkAlbumPass = true;
            passAlbumImage = cachedArt;
        }
        
        holder.itemView.setOnClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition == RecyclerView.NO_POSITION) return;
            Intent intent = new Intent(mContext, PlayerActivity.class);
            intent.putExtra("sender", "albumDetails");
            intent.putExtra("positionAlbum", adapterPosition);
            mContext.startActivity(intent);
        });
        
        // More options menu button click handler
        holder.menuMore.setOnClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition == RecyclerView.NO_POSITION) return;
            showPopupMenu(v, adapterPosition);
        });
    }
    
    // Shows the popup menu for the song item.
    private void showPopupMenu(View anchor, int position) {
        PopupMenu popupMenu = new PopupMenu(mContext, anchor);
        popupMenu.inflate(R.menu.popup_album_details);
        
        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.edit_tags) {
                showEditTagsDialog(position);
                return true;
            }
            return false;
        });
        
        popupMenu.show();
    }
    
    // Shows the tag editing dialog for the selected song.
    private void showEditTagsDialog(int position) {
        MusicFiles musicFile = albumFiles.get(position);
        String filePath = musicFile.getPath();
        
        // Check if file format is supported
        if (!TagEditorHelper.isSupportedFormat(filePath)) {
            Toast.makeText(mContext, "Unsupported file format for tag editing", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Read current tags
        TagEditorHelper.AudioTags currentTags = TagEditorHelper.readTags(filePath);
        
        // Create and show dialog
        Dialog dialog = new Dialog(mContext);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_edit_tags);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        
        // Get dialog views
        TextInputEditText editSongTitle = dialog.findViewById(R.id.edit_song_title);
        TextInputEditText editAlbumTitle = dialog.findViewById(R.id.edit_album_title);
        TextInputEditText editYear = dialog.findViewById(R.id.edit_year);
        TextInputEditText editAlbumArtist = dialog.findViewById(R.id.edit_album_artist);
        TextInputEditText editSongArtist = dialog.findViewById(R.id.edit_song_artist);
        MaterialButton btnCancel = dialog.findViewById(R.id.btn_cancel);
        MaterialButton btnSave = dialog.findViewById(R.id.btn_save);
        
        // Pre-populate fields with current tags
        editSongTitle.setText(currentTags.songTitle);
        editAlbumTitle.setText(currentTags.albumTitle);
        editYear.setText(currentTags.year);
        editAlbumArtist.setText(currentTags.albumArtist);
        editSongArtist.setText(currentTags.songArtist);
        
        // Cancel button
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        
        // Save button
        btnSave.setOnClickListener(v -> {
            TagEditorHelper.AudioTags newTags = new TagEditorHelper.AudioTags();
            newTags.songTitle = editSongTitle.getText() != null ? editSongTitle.getText().toString() : "";
            newTags.albumTitle = editAlbumTitle.getText() != null ? editAlbumTitle.getText().toString() : "";
            newTags.year = editYear.getText() != null ? editYear.getText().toString() : "";
            newTags.albumArtist = editAlbumArtist.getText() != null ? editAlbumArtist.getText().toString() : "";
            newTags.songArtist = editSongArtist.getText() != null ? editSongArtist.getText().toString() : "";
            
            // Write tags in background thread
            new Thread(() -> {
                boolean success = TagEditorHelper.writeTags(filePath, newTags);
                ((android.app.Activity) mContext).runOnUiThread(() -> {
                    if (success) {
                        Toast.makeText(mContext, "Tags saved successfully", Toast.LENGTH_SHORT).show();
                        // Update the displayed info
                        notifyItemChanged(position);
                    } else {
                        Toast.makeText(mContext, "Failed to save tags", Toast.LENGTH_SHORT).show();
                    }
                    dialog.dismiss();
                });
            }).start();
        });
        
        dialog.show();
    }

    @Override
    public int getItemCount() {
        return albumFiles.size();
    }

    public static class MyHolder extends RecyclerView.ViewHolder {

        ImageView album_image;
        ImageView menuMore;
        TextView album_name, artist_name, alb_duration;
        public MyHolder(@NonNull @NotNull View itemView) {
            super(itemView);
            album_image = itemView.findViewById(R.id.music_img);
            album_name = itemView.findViewById(R.id.music_file_name);
            artist_name = itemView.findViewById(R.id.artis_name);
            alb_duration = itemView.findViewById(R.id.alb_duration);
            menuMore = itemView.findViewById(R.id.menuMore);
        }
    }
}
