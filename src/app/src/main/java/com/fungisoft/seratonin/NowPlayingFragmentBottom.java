package com.fungisoft.seratonin;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import static android.content.Context.MODE_PRIVATE;
import static com.fungisoft.seratonin.MainActivity.ARTIST_TO_FRAG;
import static com.fungisoft.seratonin.MainActivity.PATH_TO_FRAG;
import static com.fungisoft.seratonin.MainActivity.SHOW_MINI_PLAYER;
import static com.fungisoft.seratonin.MainActivity.SONG_NAME_TO_FRAG;
import static com.fungisoft.seratonin.MusicService.MUSIC_LAST_PLAYED;
import static com.fungisoft.seratonin.MusicService.MUSIC_FILE;
import static com.fungisoft.seratonin.MusicService.ARTIST_NAME;
import static com.fungisoft.seratonin.MusicService.SONG_NAME;


public class NowPlayingFragmentBottom extends Fragment implements ServiceConnection {

    View view;
    ImageView nextBtn, prevBtn;
    // Note: Static for legacy cross-component access. Nulled in onDestroyView to prevent leaks.
    static ImageView albumArt;
    static TextView artist, songName;
    public static FloatingActionButton playPauseBtn;
    static ConstraintLayout bottom_bac_frag;
    MusicService musicService;
    Boolean bindservice = false;
    
    // Singleton-style instance for safe access from other components
    private static NowPlayingFragmentBottom instance;
    
    public static NowPlayingFragmentBottom getInstance() {
        return instance;
    }

    public NowPlayingFragmentBottom() {
        // Required empty public constructor
    }
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        view = inflater.inflate(R.layout.fragment_now_playing_bottom, container, false);
        bottom_bac_frag = view.findViewById(R.id.card_bottom_player);
        artist = view.findViewById(R.id.song_artist_miniPlayer);
        songName = view.findViewById(R.id.song_name_miniPlayer);
        albumArt = view.findViewById(R.id.bottom_album_art);
        nextBtn = view.findViewById(R.id.skip_next_bottom);
        prevBtn = view.findViewById(R.id.skip_prev_bottom);
        playPauseBtn = view.findViewById(R.id.play_pause_miniPlayer);
        nextBtn.setOnClickListener(v -> handleSkipButton(true));
        prevBtn.setOnClickListener(v -> handleSkipButton(false));
        playPauseBtn.setOnClickListener(v -> {
            if (musicService != null && musicService.musicFiles != null 
                    && !musicService.musicFiles.isEmpty() && musicService.position >= 0) {
                musicService.playPauseBtnClicked();
            }
        });

        bottom_bac_frag.setOnClickListener(v -> {
            Intent playerIntent = new Intent(getContext(), PlayerActivity.class);
            playerIntent.putExtra("sender", "nowPlayingBar");
            // Pass the current position from the service
            if (musicService != null) {
                playerIntent.putExtra("servicePosition", musicService.position);
            } else {
                playerIntent.putExtra("servicePosition", MusicService.passPosition);
            }
            startActivity(playerIntent);
        });
        return view;
    }
    
    /**
     * Handle next/previous button click with unified logic.
     * @param isNext true for next, false for previous
     */
    private void handleSkipButton(boolean isNext) {
        // Ensure service has valid playable state before allowing skip
        if (musicService == null || musicService.musicFiles == null 
                || musicService.musicFiles.isEmpty() || musicService.position < 0) {
            return;
        }
        
        if (isNext) {
            musicService.nextBtnClicked();
        } else {
            musicService.prevBtnClicked();
        }
        
        if (getActivity() == null) return;
        
        SharedPreferences preferences = getActivity().getSharedPreferences(MUSIC_LAST_PLAYED, MODE_PRIVATE);
        String path = preferences.getString(MUSIC_FILE, null);
        String artistName = preferences.getString(ARTIST_NAME, null);
        String song_name_pref = preferences.getString(SONG_NAME, null);
        
        if (path != null) {
            SHOW_MINI_PLAYER = true;
            PATH_TO_FRAG = path;
            ARTIST_TO_FRAG = artistName;
            SONG_NAME_TO_FRAG = song_name_pref;
            
            // Update mini player UI
            AlbumArtLoader.getInstance().loadAlbumArtForSong(
                    requireContext(), 
                    PATH_TO_FRAG, 
                    albumArt,
                    R.drawable.musicicon
            );
            songName.setText(SONG_NAME_TO_FRAG);
            artist.setText(ARTIST_TO_FRAG);
        } else {
            SHOW_MINI_PLAYER = false;
            PATH_TO_FRAG = null;
            ARTIST_TO_FRAG = null;
            SONG_NAME_TO_FRAG = null;
        }
    }

    public static void setLayoutInvisible() {
        if (bottom_bac_frag != null && bottom_bac_frag.getVisibility() == View.VISIBLE) {
            bottom_bac_frag.setVisibility(View.GONE);
        }
    }
    public static void setLayoutVisible() {
        if (bottom_bac_frag != null && bottom_bac_frag.getVisibility() == View.GONE) {
            bottom_bac_frag.setVisibility(View.VISIBLE);
        }
    }


    @Override
    public void onResume() {
        super.onResume();
        if (SHOW_MINI_PLAYER){
            if (PATH_TO_FRAG != null){
                // Use async art loading to avoid blocking the UI thread
                AlbumArtLoader.getInstance().loadAlbumArtForSong(
                        requireContext(), 
                        PATH_TO_FRAG, 
                        albumArt,
                        R.drawable.musicicon
                );
                songName.setText(SONG_NAME_TO_FRAG);
                artist.setText(ARTIST_TO_FRAG);
                Intent intent = new Intent(getContext(), MusicService.class);
                if(getContext() != null){
                    getContext().bindService(intent, this, Context.BIND_AUTO_CREATE);
                    bindservice = true;
                }
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getContext() != null){
            if(bindservice) {
                getContext().unbindService(this);
                bindservice = false;
            }
        }
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Null out static references to prevent memory leaks
        albumArt = null;
        artist = null;
        songName = null;
        playPauseBtn = null;
        bottom_bac_frag = null;
        view = null;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (instance == this) {
            instance = null;
        }
    }
    
    // ========== Public API methods for updating UI from other components ==========
    
    /**
     * Updates the play/pause button icon.
     * @param isPlaying true to show pause icon, false to show play icon
     */
    public void updatePlayPauseButton(boolean isPlaying) {
        if (playPauseBtn != null) {
            playPauseBtn.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        }
    }
    
    /**
     * Updates the mini player with song information.
     * @param title song title
     * @param artistName artist name
     * @param artBytes album art bytes (can be null)
     */
    public void updateSongInfo(String title, String artistName, byte[] artBytes) {
        if (songName != null) {
            songName.setText(title);
        }
        if (artist != null) {
            artist.setText(artistName);
        }
        if (albumArt != null && getContext() != null) {
            if (artBytes != null) {
                Glide.with(getContext())
                        .load(artBytes)
                        .into(albumArt);
            } else {
                albumArt.setImageResource(R.drawable.musicicon);
            }
        }
    }
    
    /**
     * Shows the mini player layout.
     */
    public void showMiniPlayer() {
        if (bottom_bac_frag != null && bottom_bac_frag.getVisibility() == View.GONE) {
            bottom_bac_frag.setVisibility(View.VISIBLE);
        }
    }
    
    /**
     * Hides the mini player layout.
     */
    public void hideMiniPlayer() {
        if (bottom_bac_frag != null && bottom_bac_frag.getVisibility() == View.VISIBLE) {
            bottom_bac_frag.setVisibility(View.GONE);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        MusicService.MyBinder binder = (MusicService.MyBinder) service;
        musicService = binder.getService();
        
        // Restore playable state from database if service has no queue (cold start)
        if ((musicService.musicFiles == null || musicService.musicFiles.isEmpty()) 
                && getContext() != null) {
            QueueDatabase queueDb = QueueDatabase.getInstance(getContext());
            ArrayList<MusicFiles> restoredQueue = queueDb.loadQueue();
            
            if (restoredQueue != null && !restoredQueue.isEmpty()) {
                int restoredIndex = queueDb.getCurrentIndex();
                // Validate index bounds
                if (restoredIndex < 0 || restoredIndex >= restoredQueue.size()) {
                    restoredIndex = 0;
                }
                
                // Set service state
                musicService.musicFiles = restoredQueue;
                musicService.position = restoredIndex;
                
                // Prepare media player (does not auto-play)
                musicService.createMediaPlayer(restoredIndex);
                
                // Sync static references for PlayerActivity consistency
                PlayerActivity.listSongs = restoredQueue;
                
                // Update play button to show play icon (paused state)
                if (playPauseBtn != null) {
                    playPauseBtn.setImageResource(R.drawable.ic_play);
                }
            } else {
                // No queue to restore - hide mini player
                SHOW_MINI_PLAYER = false;
                if (bottom_bac_frag != null) {
                    bottom_bac_frag.setVisibility(View.GONE);
                }
            }
        } else if (musicService.musicFiles != null && !musicService.musicFiles.isEmpty()) {
            // Service already has playable state - update play/pause icon
            if (playPauseBtn != null) {
                playPauseBtn.setImageResource(
                    musicService.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
            }
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        musicService = null;
    }
}