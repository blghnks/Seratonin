package com.fungisoft.seratonin;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.palette.graphics.Palette;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.preference.PreferenceManager;

import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

import static com.fungisoft.seratonin.AlbumDetailsAdapter.albumFiles;
import static com.fungisoft.seratonin.MainActivity.repeatBoolean;
import static com.fungisoft.seratonin.MainActivity.shuffleBoolean;
import static com.fungisoft.seratonin.MusicAdapter.mFiles;

public class PlayerActivity extends AppCompatActivity implements ActionPlaying, ServiceConnection,
        QueueAdapter.OnQueueItemClickListener {

    TextView song_name, artist_name, duration_played, duration_total, album_name, textNowplaying;
    ImageView cover_art, nextBtn, prevBtn, backBtn, shuffleBtn, repeatBtn;
    TextView nextSongTitle, queueCountBadge;
    static byte[] artist_image;
    FloatingActionButton playPauseBtn;
    SeekBar seekBar;

    int position = -1;
    static ArrayList<MusicFiles> listSongs = new ArrayList<>();
    static Uri uri;
//    static MediaPlayer mediaPlayer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    MusicService musicService;
    static MusicService passMusicService;
    
    // Queue database
    private QueueDatabase queueDatabase;
    
    // Queue bottom sheet
    private BottomSheetBehavior<View> queueBottomSheetBehavior;
    private RecyclerView queueRecyclerView;
    private QueueAdapter queueAdapter;
    private View emptyQueueView;
    private View queueBottomSheet;
    
    // Original queue order for restoring when shuffle is disabled
    private ArrayList<MusicFiles> originalQueueOrder = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_player);
        
        // Apply window insets for proper padding
        ConstraintLayout mContainer = findViewById(R.id.mContainer);
        ViewCompat.setOnApplyWindowInsetsListener(mContainer, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("playerActivitypass", true);
        editor.apply();
        NowPlayingFragmentBottom.setLayoutVisible();
        
        // Initialize queue database
        queueDatabase = QueueDatabase.getInstance(this);
        
        initViews();
        setupQueueBottomSheet();
        getIntenMethod();
        
        // Update shuffle/repeat button states
        updateShuffleButton();
        updateRepeatButton();
        
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (musicService != null && fromUser){
                    musicService.seekTo(progress * 1000);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
        PlayerActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (musicService != null){
                    int mCurrentPosition = musicService.getCurrentPosition() / 1000;
                    seekBar.setProgress(mCurrentPosition);
                    duration_played.setText(formattedTime(mCurrentPosition));
                }
                handler.postDelayed(this, 1000);
            }
        });
        shuffleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shuffleBoolean = !shuffleBoolean;
                updateShuffleButton();
                
                // If shuffle is turned on, shuffle the remaining queue
                if (shuffleBoolean && listSongs != null && listSongs.size() > 1) {
                    shuffleQueue();
                } else if (!shuffleBoolean) {
                    // Shuffle turned off - restore original queue order
                    restoreOriginalQueue();
                }
            }
        });
        repeatBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                repeatBoolean = !repeatBoolean;
                updateRepeatButton();
            }
        });
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
//                Intent intent_D = new Intent(getApplicationContext(), MainActivity.class);
//                intent_D.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
//                startActivity(intent_D);
                finish();
            }
        });

        // Register back press callback to replace deprecated onBackPressed()
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });
    }

    @Override
    protected void onResume() {
        Intent intent = new Intent(this, MusicService.class);
        bindService(intent, this, BIND_AUTO_CREATE);
        playThreadBtn();
        nextThreadBtn();
        prevThreadBtn();
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unbindService(this);
    }

    private void prevThreadBtn() {
        Thread prevThread = new Thread() {
            @Override
            public void run() {
                super.run();
                prevBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        prevBtnClicked();

                    }
                });
            }
        };
        prevThread.start();
    }

    public void prevBtnClicked() {
        if (musicService.isPlaying()){
            musicService.stop();
            musicService.release();
            // When shuffle is on, the queue is already shuffled, so just go to previous position
            // When repeat is on without shuffle, stay at same position
            if (repeatBoolean && !shuffleBoolean) {
                // Repeat current song
                repeatBtn.setImageResource(R.drawable.ic_repeat_on);
            } else if (repeatBoolean && shuffleBoolean) {
                // Both repeat and shuffle - move to previous in shuffled queue
                position = ((position - 1) < 0 ? (listSongs.size() - 1) : (position - 1));
                shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
                repeatBtn.setImageResource(R.drawable.ic_repeat_on);
            } else {
                // Normal progression (works for both shuffle on and off since queue is already shuffled)
                position = ((position - 1) < 0 ? (listSongs.size() - 1) : (position - 1));
                if (shuffleBoolean) {
                    shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
                }
            }
            uri = Uri.parse(listSongs.get(position).getPath());
            musicService.createMediaPlayer(position);
            metaData(uri);
            song_name.setText(listSongs.get(position).getTitle());
            artist_name.setText(listSongs.get(position).getArtist());
            album_name.setText(listSongs.get(position).getAlbum());
            seekBar.setMax(musicService.getDuration() / 1000);
            PlayerActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (musicService != null){
                        int mCurrentPosition = musicService.getCurrentPosition() / 1000;
                        seekBar.setProgress(mCurrentPosition);
                    }
                    handler.postDelayed(this, 1000);
                }
            });
            musicService.OnCompleted();
            musicService.showNotification(R.drawable.ic_pause);
            playPauseBtn.setBackgroundResource(R.drawable.ic_pause);
            musicService.start();
            passMusicService = musicService;

            byte[] art = artist_image;
            if (art != null){
                Glide.with(getBaseContext()).load(art)
                        .into(NowPlayingFragmentBottom.albumArt);
            }else{
//                Toast.makeText(musicService, "Its null", Toast.LENGTH_SHORT).show();
            }
            NowPlayingFragmentBottom.songName.setText(listSongs.get(position).getTitle());
            NowPlayingFragmentBottom.artist.setText(listSongs.get(position).getArtist());
        }
        else {
            musicService.stop();
            musicService.release();
            // When shuffle is on, the queue is already shuffled, so just go to previous position
            // When repeat is on without shuffle, stay at same position
            if (repeatBoolean && !shuffleBoolean) {
                // Repeat current song
                repeatBtn.setImageResource(R.drawable.ic_repeat_on);
            } else if (repeatBoolean && shuffleBoolean) {
                // Both repeat and shuffle - move to previous in shuffled queue
                position = ((position - 1) < 0 ? (listSongs.size() - 1) : (position - 1));
                shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
                repeatBtn.setImageResource(R.drawable.ic_repeat_on);
            } else {
                // Normal progression (works for both shuffle on and off since queue is already shuffled)
                position = ((position - 1) < 0 ? (listSongs.size() - 1) : (position - 1));
                if (shuffleBoolean) {
                    shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
                }
            }
            uri = Uri.parse(listSongs.get(position).getPath());
            musicService.createMediaPlayer(position);
            metaData(uri);
            song_name.setText(listSongs.get(position).getTitle());
            artist_name.setText(listSongs.get(position).getArtist());
            album_name.setText(listSongs.get(position).getAlbum());
            seekBar.setMax(musicService.getDuration() / 1000);
            PlayerActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (musicService != null){
                        int mCurrentPosition = musicService.getCurrentPosition() / 1000;
                        seekBar.setProgress(mCurrentPosition);
                    }
                    handler.postDelayed(this, 1000);
                }
            });
            musicService.OnCompleted();
            musicService.showNotification(R.drawable.ic_play);
            playPauseBtn.setBackgroundResource(R.drawable.ic_play);
            passMusicService = musicService;

            byte[] art = artist_image;
            if (art != null){
                Glide.with(getBaseContext()).load(art)
                        .into(NowPlayingFragmentBottom.albumArt);
            }else{
//                Toast.makeText(musicService, "Its null", Toast.LENGTH_SHORT).show();
            }
            NowPlayingFragmentBottom.songName.setText(listSongs.get(position).getTitle());
            NowPlayingFragmentBottom.artist.setText(listSongs.get(position).getArtist());
        }
        
        // Update queue UI to show correct next song
        updateQueueUI();
    }

    private void nextThreadBtn() {
        Thread nextThread = new Thread() {
            @Override
            public void run() {
                super.run();
                nextBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        nextBtnClicked();

                    }
                });
            }
        };
        nextThread.start();
    }

    public void nextBtnClicked() {
        if (musicService.isPlaying()){
            musicService.stop();
            musicService.release();
            // When shuffle is on, the queue is already shuffled, so just go to next position
            // When repeat is on without shuffle, stay at same position
            if (repeatBoolean && !shuffleBoolean) {
                // Repeat current song
                repeatBtn.setImageResource(R.drawable.ic_repeat_on);
            } else if (repeatBoolean && shuffleBoolean) {
                // Both repeat and shuffle - move to next in shuffled queue
                position = ((position + 1) % listSongs.size());
                shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
                repeatBtn.setImageResource(R.drawable.ic_repeat_on);
            } else {
                // Normal progression (works for both shuffle on and off since queue is already shuffled)
                position = ((position + 1) % listSongs.size());
                if (shuffleBoolean) {
                    shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
                }
            }
            //else position will be position
            uri = Uri.parse(listSongs.get(position).getPath());
            musicService.createMediaPlayer(position);
            metaData(uri);
            song_name.setText(listSongs.get(position).getTitle());
            artist_name.setText(listSongs.get(position).getArtist());
            album_name.setText(listSongs.get(position).getAlbum());
            seekBar.setMax(musicService.getDuration() / 1000);
            PlayerActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (musicService != null){
                        int mCurrentPosition = musicService.getCurrentPosition() / 1000;
                        seekBar.setProgress(mCurrentPosition);
                    }
                    handler.postDelayed(this, 1000);
                }
            });
            musicService.OnCompleted();
            musicService.showNotification(R.drawable.ic_pause);
            playPauseBtn.setBackgroundResource(R.drawable.ic_pause);
            musicService.start();
            passMusicService = musicService;

            byte[] art = artist_image;
            if (art != null){
                Glide.with(getBaseContext()).load(art)
                        .into(NowPlayingFragmentBottom.albumArt);
            }else{
//                Toast.makeText(musicService, "Its null", Toast.LENGTH_SHORT).show();
            }
            NowPlayingFragmentBottom.songName.setText(listSongs.get(position).getTitle());
            NowPlayingFragmentBottom.artist.setText(listSongs.get(position).getArtist());
        }
        else {
            musicService.stop();
            musicService.release();
            // When shuffle is on, the queue is already shuffled, so just go to next position
            // When repeat is on without shuffle, stay at same position
            if (repeatBoolean && !shuffleBoolean) {
                // Repeat current song
                repeatBtn.setImageResource(R.drawable.ic_repeat_on);
            } else if (repeatBoolean && shuffleBoolean) {
                // Both repeat and shuffle - move to next in shuffled queue
                position = ((position + 1) % listSongs.size());
                shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
                repeatBtn.setImageResource(R.drawable.ic_repeat_on);
            } else {
                // Normal progression (works for both shuffle on and off since queue is already shuffled)
                position = ((position + 1) % listSongs.size());
                if (shuffleBoolean) {
                    shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
                }
            }
            //else position will be position
//            position = ((position + 1) % listSongs.size());
            uri = Uri.parse(listSongs.get(position).getPath());
            musicService.createMediaPlayer(position);
            metaData(uri);
            song_name.setText(listSongs.get(position).getTitle());
            artist_name.setText(listSongs.get(position).getArtist());
            album_name.setText(listSongs.get(position).getAlbum());
            seekBar.setMax(musicService.getDuration() / 1000);
            PlayerActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (musicService != null){
                        int mCurrentPosition = musicService.getCurrentPosition() / 1000;
                        seekBar.setProgress(mCurrentPosition);
                    }
                    handler.postDelayed(this, 1000);
                }
            });
            musicService.OnCompleted();
            musicService.showNotification(R.drawable.ic_play);
            playPauseBtn.setBackgroundResource(R.drawable.ic_play);
            passMusicService = musicService;

            byte[] art = artist_image;
            if (art != null){
                Glide.with(getBaseContext()).load(art)
                        .into(NowPlayingFragmentBottom.albumArt);
            }else{
//                Toast.makeText(musicService, "Art is NULL!!!", Toast.LENGTH_SHORT).show();
            }
            NowPlayingFragmentBottom.songName.setText(listSongs.get(position).getTitle());
            NowPlayingFragmentBottom.artist.setText(listSongs.get(position).getArtist());
        }
        
        // Update queue UI to show correct next song
        updateQueueUI();
    }

    private int getRandom(int i) {
        Random random = new Random();
        return random.nextInt(i + 1);
    }

    private void playThreadBtn() {
        Thread playThread = new Thread() {
            @Override
            public void run() {
                super.run();
                playPauseBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        playPauseBtnClicked();

                    }
                });
            }
        };
        playThread.start();
    }

    public void playPauseBtnClicked() {
        if (musicService.isPlaying()){
            playPauseBtn.setImageResource(R.drawable.ic_play);
            musicService.showNotification(R.drawable.ic_play);
            NowPlayingFragmentBottom.playPauseBtn.setImageResource(R.drawable.ic_play);
            musicService.pause();
            if (shuffleBoolean && !repeatBoolean){
                shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
            }else if (!shuffleBoolean && repeatBoolean){
                repeatBtn.setImageResource(R.drawable.ic_repeat_on);
            }else if (shuffleBoolean){
                shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
                repeatBtn.setImageResource(R.drawable.ic_repeat_on);
            }
            seekBar.setMax(musicService.getDuration() / 1000);
            PlayerActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (musicService != null){
                        int mCurrentPosition = musicService.getCurrentPosition() / 1000;
                        seekBar.setProgress(mCurrentPosition);
                    }
                    handler.postDelayed(this, 1000);
                }
            });
        }
        else{
            musicService.showNotification(R.drawable.ic_pause);
            playPauseBtn.setImageResource(R.drawable.ic_pause);
            NowPlayingFragmentBottom.playPauseBtn.setImageResource(R.drawable.ic_pause);
            musicService.start();
            if (shuffleBoolean && !repeatBoolean){
                shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
            }else if (!shuffleBoolean && repeatBoolean){
                repeatBtn.setImageResource(R.drawable.ic_repeat_on);
            }else if (shuffleBoolean){
                shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
                repeatBtn.setImageResource(R.drawable.ic_repeat_on);
            }
            seekBar.setMax(musicService.getDuration() / 1000);
            PlayerActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (musicService != null){
                        int mCurrentPosition = musicService.getCurrentPosition() / 1000;
                        seekBar.setProgress(mCurrentPosition);
                    }
                    handler.postDelayed(this, 1000);
                }
            });
        }
        passMusicService = musicService;
    }

//    public void PassingBottomFrag(){
//        uri = Uri.parse(listSongs.get(position).getPath());
//        metaData(uri);
//        byte[] artPhoto = artist_image;
//        if (artPhoto != null){
//            Glide.with(getBaseContext()).load(artPhoto)
//                    .into(NowPlayingFragmentBottom.albumArt);
//        }else{
////                Toast.makeText(musicService, "Art is NULL!!!", Toast.LENGTH_SHORT).show();
//        }
//        NowPlayingFragmentBottom.songName.setText(listSongs.get(position).getTitle());
//        NowPlayingFragmentBottom.artist.setText(listSongs.get(position).getArtist());
//    }

    private String formattedTime(int mCurrentPosition) {
        String totalout = "";
        String totalNew = "";
        String seconds = String.valueOf(mCurrentPosition % 60);
        String minutes = String.valueOf(mCurrentPosition / 60);
        totalout = minutes + ":" + seconds;
        totalNew = minutes + ":" + "0" + seconds;
        if (seconds.length() == 1){
            return totalNew;
        }
        else{
            return totalout;
        }
    }

    private void getIntenMethod() {
        String sender = getIntent().getStringExtra("sender");
        String musicAdapt = getIntent().getStringExtra("musicAdapter");
        boolean fromPlaylist = getIntent().getBooleanExtra("fromPlaylist", false);
        
        // Coming from M3U playlist import - use the queue that was already set
        if (fromPlaylist) {
            position = getIntent().getIntExtra("position", 0);
            // listSongs was already set by MainActivity before launching
            
            if (listSongs == null || listSongs.isEmpty()) {
                // Fallback: load from queue database
                QueueDatabase queueDb = QueueDatabase.getInstance(this);
                listSongs = queueDb.loadQueue();
                position = queueDb.getCurrentIndex();
            }
            
            if (listSongs == null || listSongs.isEmpty()) {
                finish();
                return;
            }
            
            // Clear original queue order when new playlist is loaded
            originalQueueOrder = null;
            
            // Update queue UI
            if (queueAdapter != null) {
                queueAdapter.updateQueue(listSongs);
                queueAdapter.setCurrentPlayingIndex(position);
                updateQueueUI();
            }
            
            NowPlayingFragmentBottom.playPauseBtn.setImageResource(R.drawable.ic_pause);
            playPauseBtn.setImageResource(R.drawable.ic_pause);
            uri = Uri.parse(listSongs.get(position).getPath());
            
            // Stop any existing playback and start new
            if (musicService != null) {
                musicService.stop();
                musicService.release();
            }
            
            Intent intent = new Intent(this, MusicService.class);
            intent.putExtra("servicePosition", position);
            startService(intent);
            return;
        }
        
        // Coming from now playing bar - use existing service state
        if (sender != null && sender.equals("nowPlayingBar")) {
            // Get position from intent (passed from NowPlayingFragmentBottom)
            int servicePosition = getIntent().getIntExtra("servicePosition", -1);
            
            if (passMusicService != null && passMusicService.musicFiles != null 
                    && passMusicService.position >= 0 
                    && passMusicService.position < passMusicService.musicFiles.size()) {
                position = passMusicService.position;
                listSongs = passMusicService.musicFiles;
            } else if (mFiles != null && servicePosition >= 0 && servicePosition < mFiles.size()) {
                // Fallback to mFiles with the passed position
                position = servicePosition;
                listSongs = mFiles;
            } else if (mFiles != null && MusicService.passPosition >= 0 && MusicService.passPosition < mFiles.size()) {
                // Last resort - use static passPosition from MusicService
                position = MusicService.passPosition;
                listSongs = mFiles;
            } else {
                // All fallbacks failed - cannot proceed
                finish();
                return;
            }
            
            uri = Uri.parse(listSongs.get(position).getPath());
            // Update play/pause button state
            if (passMusicService != null && passMusicService.isPlaying()) {
                playPauseBtn.setImageResource(R.drawable.ic_pause);
                NowPlayingFragmentBottom.playPauseBtn.setImageResource(R.drawable.ic_pause);
            } else {
                playPauseBtn.setImageResource(R.drawable.ic_play);
                NowPlayingFragmentBottom.playPauseBtn.setImageResource(R.drawable.ic_play);
            }
            // Don't restart the service, just bind to it
            Intent intent = new Intent(this, MusicService.class);
            bindService(intent, this, BIND_AUTO_CREATE);
            return;
        }
        
        if (sender != null && sender.equals("albumDetails")){
            position = getIntent().getIntExtra("positionAlbum",-1);
            listSongs = albumFiles;
        }
        else{
            position = getIntent().getIntExtra("positionMfiles",-1);
            listSongs = mFiles;
        }
        
        // Validate position before accessing list
        if (listSongs == null || position < 0 || position >= listSongs.size()) {
            // Cannot proceed without valid position - finish activity
            finish();
            return;
        }
        
        // Save queue to database
        saveQueueToDatabase();
        
        // Update queue UI with new songs
        if (queueAdapter != null) {
            queueAdapter.updateQueue(listSongs);
            queueAdapter.setCurrentPlayingIndex(position);
            updateQueueUI();
        }
        
        // Clear original queue order when new album/playlist is loaded
        originalQueueOrder = null;
        
        NowPlayingFragmentBottom.playPauseBtn.setImageResource(R.drawable.ic_pause);
        playPauseBtn.setImageResource(R.drawable.ic_pause);
        uri = Uri.parse(listSongs.get(position).getPath());


        //HERE IT IS!!

        if (musicService != null){
            musicService.stop();
            musicService.release();
        }

        //ABOVE BLOCK

        Intent intent = new Intent(this, MusicService.class);
        intent.putExtra("servicePosition", position);
        startService(intent);
    }

    private void initViews(){
        song_name = findViewById(R.id.song_name);
        artist_name = findViewById(R.id.song_artist);
        album_name = findViewById(R.id.song_album);
        duration_played = findViewById(R.id.durationPlayed);
        duration_total = findViewById(R.id.durationTotal);
        cover_art = findViewById(R.id.cover_art);
        nextBtn = findViewById(R.id.id_next);
        prevBtn = findViewById(R.id.id_prev);
        backBtn = findViewById(R.id.back_btn);
        shuffleBtn = findViewById(R.id.id_shuffle);
        repeatBtn = findViewById(R.id.id_repeat);
        playPauseBtn = findViewById(R.id.play_pause);
        seekBar = findViewById(R.id.seekBar);
        textNowplaying = findViewById(R.id.nowplaing);
        
        // Queue bottom sheet views
        queueBottomSheet = findViewById(R.id.queue_bottom_sheet);
        queueRecyclerView = findViewById(R.id.queue_recycler_view);
        emptyQueueView = findViewById(R.id.empty_queue_view);
        nextSongTitle = findViewById(R.id.next_song_title);
        queueCountBadge = findViewById(R.id.queue_count_badge);
        
        // Save/clear queue buttons
        ImageView saveQueueBtn = findViewById(R.id.save_queue_btn);
        ImageView clearQueueBtn = findViewById(R.id.clear_queue_btn);
        
        saveQueueBtn.setOnClickListener(v -> saveQueueToM3U());
        clearQueueBtn.setOnClickListener(v -> confirmClearQueue());
    }
    
    /**
     * Setup the queue bottom sheet with behavior and adapter.
     */
    private void setupQueueBottomSheet() {
        queueBottomSheetBehavior = BottomSheetBehavior.from(queueBottomSheet);
        queueBottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        queueBottomSheetBehavior.setPeekHeight(getResources().getDimensionPixelSize(R.dimen.queue_peek_height));
        
        // Get system bar heights for dynamic padding
        final int[] statusBarHeight = {0};
        final int[] navBarHeight = {0};
        View queueHeader = findViewById(R.id.queue_header);
        View queueDivider = findViewById(R.id.queue_divider);
        
        ViewCompat.setOnApplyWindowInsetsListener(queueBottomSheet, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            statusBarHeight[0] = insets.top;
            navBarHeight[0] = insets.bottom;
            // Initial padding will be set by onSlide callback
            return windowInsets;
        });
        
        // Apply bottom padding to RecyclerView for navbar
        ViewCompat.setOnApplyWindowInsetsListener(queueRecyclerView, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), insets.bottom);
            return windowInsets;
        });
        
        // Setup adapter
        queueAdapter = new QueueAdapter(this, listSongs, this);
        queueAdapter.setCurrentPlayingIndex(position);
        queueRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        queueRecyclerView.setAdapter(queueAdapter);
        
        // Setup drag and swipe
        ItemTouchHelper.Callback callback = new QueueAdapter.QueueItemTouchCallback(queueAdapter);
        ItemTouchHelper touchHelper = new ItemTouchHelper(callback);
        touchHelper.attachToRecyclerView(queueRecyclerView);
        queueAdapter.setItemTouchHelper(touchHelper);
        
        // Bottom sheet callback
        queueBottomSheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                // Update expanded content visibility based on state
                if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    queueDivider.setVisibility(View.GONE);
                    queueHeader.setVisibility(View.GONE);
                    queueRecyclerView.setVisibility(View.GONE);
                    emptyQueueView.setVisibility(View.GONE);
                }
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                // Dynamically adjust top padding based on slide offset (0 = collapsed, 1 = expanded)
                // slideOffset can be negative when hiding, clamp to 0-1 range
                float clampedOffset = Math.max(0f, Math.min(1f, slideOffset));
                int dynamicTopPadding = (int) (statusBarHeight[0] * clampedOffset);
                bottomSheet.setPadding(
                    bottomSheet.getPaddingLeft(),
                    dynamicTopPadding,
                    bottomSheet.getPaddingRight(),
                    0
                );
                
                // Show/hide expanded content based on slide offset
                if (clampedOffset > 0.05f) {
                    // Show expanded content once user starts pulling
                    queueDivider.setVisibility(View.VISIBLE);
                    queueHeader.setVisibility(View.VISIBLE);
                    if (listSongs != null && !listSongs.isEmpty()) {
                        queueRecyclerView.setVisibility(View.VISIBLE);
                        emptyQueueView.setVisibility(View.GONE);
                    } else {
                        queueRecyclerView.setVisibility(View.GONE);
                        emptyQueueView.setVisibility(View.VISIBLE);
                    }
                    
                    // Fade in the expanded content
                    float alpha = Math.min(1f, clampedOffset * 3f); // Fade in quickly
                    queueDivider.setAlpha(alpha);
                    queueHeader.setAlpha(alpha);
                    queueRecyclerView.setAlpha(alpha);
                    emptyQueueView.setAlpha(alpha);
                } else {
                    // Hide expanded content when nearly collapsed
                    queueDivider.setVisibility(View.GONE);
                    queueHeader.setVisibility(View.GONE);
                    queueRecyclerView.setVisibility(View.GONE);
                    emptyQueueView.setVisibility(View.GONE);
                }
            }
        });
        
        updateQueueUI();
    }
    
    /**
     * Toggle queue bottom sheet between expanded and collapsed.
     */
    private void toggleQueueBottomSheet() {
        if (queueBottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
            queueBottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        } else {
            queueBottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }
    
    /**
     * Update the queue UI (next song preview, count, empty state).
     * Note: Does not change visibility of expanded content - that's handled by onSlide callback.
     */
    private void updateQueueUI() {
        if (listSongs == null || listSongs.isEmpty()) {
            nextSongTitle.setText("No songs in queue");
            queueCountBadge.setVisibility(View.GONE);
        } else {
            // Update next song preview
            int nextIndex = position + 1;
            if (nextIndex < listSongs.size()) {
                MusicFiles nextSong = listSongs.get(nextIndex);
                nextSongTitle.setText(nextSong.getTitle() != null ? nextSong.getTitle() : "Unknown");
            } else if (repeatBoolean && listSongs.size() > 0) {
                // Will loop back to first song
                MusicFiles firstSong = listSongs.get(0);
                nextSongTitle.setText(firstSong.getTitle() != null ? firstSong.getTitle() : "Unknown");
            } else {
                nextSongTitle.setText("End of queue");
            }
            
            // Update count badge
            queueCountBadge.setVisibility(View.VISIBLE);
            queueCountBadge.setText(String.valueOf(listSongs.size()));
            
            // Update adapter
            queueAdapter.updateQueue(listSongs);
            queueAdapter.setCurrentPlayingIndex(position);
        }
    }
    
    /**
     * Update shuffle button appearance.
     */
    private void updateShuffleButton() {
        if (shuffleBoolean) {
            shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
            shuffleBtn.setImageTintList(ColorStateList.valueOf(getResources().getColor(R.color.accent_purple, null)));
        } else {
            shuffleBtn.setImageResource(R.drawable.ic_shuffle_off);
            shuffleBtn.setImageTintList(ColorStateList.valueOf(getResources().getColor(R.color.text_secondary_dark, null)));
        }
    }
    
    /**
     * Update repeat button appearance.
     */
    private void updateRepeatButton() {
        if (repeatBoolean) {
            repeatBtn.setImageResource(R.drawable.ic_repeat_on);
            repeatBtn.setImageTintList(ColorStateList.valueOf(getResources().getColor(R.color.accent_purple, null)));
        } else {
            repeatBtn.setImageResource(R.drawable.ic_repeat_off);
            repeatBtn.setImageTintList(ColorStateList.valueOf(getResources().getColor(R.color.text_secondary_dark, null)));
        }
    }
    
    /**
     * Shuffle the remaining queue (songs after current position).
     */
    private void shuffleQueue() {
        if (listSongs == null || position < 0 || position >= listSongs.size() - 1) return;
        
        // Save original queue order before shuffling (if not already saved)
        if (originalQueueOrder == null) {
            originalQueueOrder = new ArrayList<>(listSongs);
        }
        
        // Get the songs after current position
        ArrayList<MusicFiles> remainingSongs = new ArrayList<>(listSongs.subList(position + 1, listSongs.size()));
        Collections.shuffle(remainingSongs);
        
        // Rebuild the list
        ArrayList<MusicFiles> newList = new ArrayList<>();
        for (int i = 0; i <= position; i++) {
            newList.add(listSongs.get(i));
        }
        newList.addAll(remainingSongs);
        
        listSongs.clear();
        listSongs.addAll(newList);
        
        // Save to database and update UI
        saveQueueToDatabase();
        updateQueueUI();
        
        if (musicService != null) {
            musicService.musicFiles = listSongs;
        }
    }
    
    /**
     * Restore the original queue order when shuffle is turned off.
     */
    private void restoreOriginalQueue() {
        if (originalQueueOrder == null || listSongs == null) {
            updateQueueUI();
            return;
        }
        
        // Find the current song in the original queue
        MusicFiles currentSong = null;
        if (position >= 0 && position < listSongs.size()) {
            currentSong = listSongs.get(position);
        }
        
        // Restore original order
        listSongs.clear();
        listSongs.addAll(originalQueueOrder);
        
        // Find position of current song in restored queue
        if (currentSong != null) {
            for (int i = 0; i < listSongs.size(); i++) {
                if (listSongs.get(i).getPath().equals(currentSong.getPath())) {
                    position = i;
                    break;
                }
            }
        }
        
        // Clear original queue reference
        originalQueueOrder = null;
        
        // Save to database and update UI
        saveQueueToDatabase();
        updateQueueUI();
        
        if (musicService != null) {
            musicService.musicFiles = listSongs;
            musicService.position = position;
        }
    }
    
    /**
     * Save queue to M3U file.
     */
    private void saveQueueToM3U() {
        String musicFolderPath = FolderSelectionActivity.getMusicFolderPath(this);
        if (musicFolderPath == null) {
            android.widget.Toast.makeText(this, "No music folder set", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        
        String savedPath = queueDatabase.exportToM3UWithTimestamp(musicFolderPath);
        if (savedPath != null) {
            android.widget.Toast.makeText(this, "Queue saved to:\n" + savedPath, android.widget.Toast.LENGTH_LONG).show();
        } else {
            android.widget.Toast.makeText(this, "Failed to save queue", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Show confirmation dialog before clearing queue.
     */
    private void confirmClearQueue() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Clear Queue")
                .setMessage("Are you sure you want to clear the queue?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    listSongs.clear();
                    queueDatabase.clearQueue();
                    updateQueueUI();
                    
                    // Stop playback
                    if (musicService != null && musicService.isPlaying()) {
                        musicService.stop();
                    }
                    finish();
                })
                .show();
    }

    /**
     * Save current queue to database.
     */
    private void saveQueueToDatabase() {
        if (queueDatabase != null && listSongs != null) {
            queueDatabase.saveQueue(listSongs);
            queueDatabase.saveCurrentIndex(position);
        }
    }
    
    // QueueAdapter.OnQueueItemClickListener implementation
    
    @Override
    public void onItemClick(int selectedPosition) {
        if (selectedPosition >= 0 && selectedPosition < listSongs.size()) {
            position = selectedPosition;
            
            if (musicService != null) {
                musicService.stop();
                musicService.release();
            }
            
            uri = Uri.parse(listSongs.get(position).getPath());
            musicService.createMediaPlayer(position);
            metaData(uri);
            song_name.setText(listSongs.get(position).getTitle());
            artist_name.setText(listSongs.get(position).getArtist());
            album_name.setText(listSongs.get(position).getAlbum());
            seekBar.setMax(musicService.getDuration() / 1000);
            
            musicService.OnCompleted();
            musicService.showNotification(R.drawable.ic_pause);
            playPauseBtn.setImageResource(R.drawable.ic_pause);
            NowPlayingFragmentBottom.playPauseBtn.setImageResource(R.drawable.ic_pause);
            musicService.start();
            passMusicService = musicService;
            
            // Update now playing fragment
            byte[] art = artist_image;
            if (art != null) {
                Glide.with(getBaseContext()).load(art)
                        .into(NowPlayingFragmentBottom.albumArt);
            }
            NowPlayingFragmentBottom.songName.setText(listSongs.get(position).getTitle());
            NowPlayingFragmentBottom.artist.setText(listSongs.get(position).getArtist());
            
            // Save to database and update UI
            queueDatabase.saveCurrentIndex(position);
            updateQueueUI();
        }
    }
    
    @Override
    public void onRemoveClick(int removePosition) {
        if (removePosition >= 0 && removePosition < listSongs.size()) {
            // Don't allow removing currently playing song
            if (removePosition == position) {
                android.widget.Toast.makeText(this, "Cannot remove currently playing song", android.widget.Toast.LENGTH_SHORT).show();
                queueAdapter.notifyItemChanged(removePosition); // Reset swipe
                return;
            }
            
            queueAdapter.removeItem(removePosition);
            queueDatabase.removeFromQueue(removePosition);
            
            // Update current index if needed
            if (removePosition < position) {
                position--;
                queueAdapter.setCurrentPlayingIndex(position);
            }
            
            queueDatabase.saveCurrentIndex(position);
            updateQueueUI();
            
            if (musicService != null) {
                musicService.musicFiles = listSongs;
                musicService.position = position;
            }
        }
    }
    
    @Override
    public void onItemMoved(int fromPosition, int toPosition) {
        // Update database
        queueDatabase.saveQueue(listSongs);
        
        // Update current index
        position = queueAdapter.getCurrentPlayingIndex();
        queueDatabase.saveCurrentIndex(position);
        
        if (musicService != null) {
            musicService.musicFiles = listSongs;
            musicService.position = position;
        }
        
        updateQueueUI();
    }
    
    public boolean isColorDark(int color){
        double darkness = 1-(0.299*Color.red(color) + 0.587*Color.green(color) + 0.114*Color.blue(color))/255;
        return !(darkness < 0.5);
    }
    private void metaData(Uri uri){
        // Get duration from stored metadata
        int durationTotal = Integer.parseInt(listSongs.get(position).getDuration()) / 1000;
        duration_total.setText(formattedTime(durationTotal));
        
        // Use song art loading: embedded first, then external fallback
        byte[] art = AlbumArtHelper.getAlbumArtForSong(this, uri.toString());
        artist_image = art;
        Bitmap bitmap = null;
        if (art != null) {
            bitmap = BitmapFactory.decodeByteArray(art, 0, art.length);
        }
        if (bitmap != null) {
            ImageAnimation(this, cover_art, bitmap);
            final Bitmap finalBitmap = bitmap;
            Palette.from(bitmap).generate(new Palette.PaletteAsyncListener() {
                @Override
                public void onGenerated(@Nullable @org.jetbrains.annotations.Nullable Palette palette) {
                    assert palette != null;
                    Palette.Swatch swatch = palette.getDominantSwatch();
                    if (swatch != null)
                    {
                        ImageView gredient = findViewById(R.id.imageViewGredient);
                        ConstraintLayout mContainer = findViewById(R.id.mContainer);
                        gredient.setImageResource(R.drawable.gredient_bg);
                        mContainer.setBackgroundResource(R.drawable.main_bg);
                        GradientDrawable gradientDrawable = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                                new int[]{swatch.getRgb(), 0x00000000});
                        gredient.setBackground(gradientDrawable);
                        GradientDrawable mContainer_gradientDrawableBg = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                                new int[]{0x44444444, swatch.getRgb()});
                        mContainer.setBackground(mContainer_gradientDrawableBg);
                        
                        // Now Playing bar uses SOLID color (Material You requirement) - no gradient
                        int nowPlayingBgColor = getResources().getColor(R.color.now_playing_bg, getTheme());
                        NowPlayingFragmentBottom.bottom_bac_frag.setBackgroundColor(nowPlayingBgColor);

                        // Use high-contrast white text with shadow (defined in XML) for legibility on any background
                        int textPrimary = Color.WHITE;
                        int textSecondary = Color.parseColor("#E0E0E0");  // Light gray for secondary
                        int textTertiary = Color.parseColor("#BDBDBD");   // Medium gray for tertiary
                        
                        song_name.setTextColor(textPrimary);
                        artist_name.setTextColor(textSecondary);
                        album_name.setTextColor(textTertiary);
                        textNowplaying.setTextColor(textPrimary);
                        
                        // Now Playing bar text uses fixed readable colors (dark theme)
                        int nowPlayingTextPrimary = getResources().getColor(R.color.text_primary_dark, getTheme());
                        int nowPlayingTextSecondary = getResources().getColor(R.color.text_secondary_dark, getTheme());
                        NowPlayingFragmentBottom.songName.setTextColor(nowPlayingTextPrimary);
                        NowPlayingFragmentBottom.artist.setTextColor(nowPlayingTextSecondary);
                        
                        if (isColorDark(swatch.getRgb())){
                            int ColorValue = Color.parseColor("#FFFFFF");
                            ImageViewCompat.setImageTintList(playPauseBtn, ColorStateList.valueOf(ColorValue));
                            ImageViewCompat.setImageTintList(NowPlayingFragmentBottom.playPauseBtn, ColorStateList.valueOf(ColorValue));
                        }else{
                            int ColorValue = Color.parseColor("#1A1A1A");
                            ImageViewCompat.setImageTintList(playPauseBtn, ColorStateList.valueOf(ColorValue));
                            ImageViewCompat.setImageTintList(NowPlayingFragmentBottom.playPauseBtn, ColorStateList.valueOf(ColorValue));
                        }
                        playPauseBtn.setBackgroundTintList(ColorStateList.valueOf(swatch.getRgb()));
                        NowPlayingFragmentBottom.playPauseBtn.setBackgroundTintList(ColorStateList.valueOf(swatch.getRgb()));

                    }
                    else {
                        // No swatch - use default dark theme colors
                        setDefaultColors();
                    }
                }

            });
        }
        else {
            if (isValidContextForGlide(PlayerActivity.this)) {
                // Load image via Glide lib using context
                Glide.with(PlayerActivity.this)
                        .asBitmap()
                        .load(R.drawable.musicicon)
                        .into(cover_art);
            }
            // No album art - use default dark theme colors
            setDefaultColors();
        }
    }
    
    /**
     * Sets default dark theme colors for player and now playing bar.
     * Used when no album art is available or palette extraction fails.
     */
    private void setDefaultColors() {
        ImageView gradient = findViewById(R.id.imageViewGredient);
        ConstraintLayout mContainer = findViewById(R.id.mContainer);
        gradient.setImageResource(R.drawable.gredient_bg);
        mContainer.setBackgroundColor(getResources().getColor(R.color.background_dark, getTheme()));
        
        // Now Playing bar uses solid color (Material You)
        int nowPlayingBgColor = getResources().getColor(R.color.now_playing_bg, getTheme());
        NowPlayingFragmentBottom.bottom_bac_frag.setBackgroundColor(nowPlayingBgColor);
        
        // Player text - readable on dark background
        int textPrimary = getResources().getColor(R.color.text_primary_dark, getTheme());
        int textSecondary = getResources().getColor(R.color.text_secondary_dark, getTheme());
        int textTertiary = getResources().getColor(R.color.text_tertiary_dark, getTheme());
        
        song_name.setTextColor(textPrimary);
        artist_name.setTextColor(textSecondary);
        album_name.setTextColor(textTertiary);
        
        // Now Playing bar text - always readable
        NowPlayingFragmentBottom.songName.setTextColor(textPrimary);
        NowPlayingFragmentBottom.artist.setTextColor(textSecondary);
        
        // Default accent color for FAB
        int accentColor = getResources().getColor(R.color.accent_purple, getTheme());
        playPauseBtn.setBackgroundTintList(ColorStateList.valueOf(accentColor));
        NowPlayingFragmentBottom.playPauseBtn.setBackgroundTintList(ColorStateList.valueOf(accentColor));
        ImageViewCompat.setImageTintList(playPauseBtn, ColorStateList.valueOf(Color.WHITE));
        ImageViewCompat.setImageTintList(NowPlayingFragmentBottom.playPauseBtn, ColorStateList.valueOf(Color.WHITE));
    }

    public void ImageAnimation(Context context, ImageView imageView, Bitmap bitmap)
    {
        Animation animOut = AnimationUtils.loadAnimation(context, android.R.anim.fade_out);
        Animation animIn = AnimationUtils.loadAnimation(context, android.R.anim.fade_in);
        animOut.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {

            }

            @Override
            public void onAnimationEnd(Animation animation) {
                Glide.with(context).load(bitmap).into(imageView);
                animIn.setAnimationListener(new Animation.AnimationListener() {
                    @Override
                    public void onAnimationStart(Animation animation) {

                    }

                    @Override
                    public void onAnimationEnd(Animation animation) {

                    }

                    @Override
                    public void onAnimationRepeat(Animation animation) {

                    }
                });
                imageView.startAnimation(animIn);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
        imageView.startAnimation(animOut);
    }

    public static boolean isValidContextForGlide(final Context context) {
        if (context == null) {
            return false;
        }
        if (context instanceof Activity) {
            final Activity activity = (Activity) context;
            return !activity.isDestroyed() && !activity.isFinishing();
        }
        return true;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service)  {
        MusicService.MyBinder myBinder = (MusicService.MyBinder) service;
        musicService = myBinder.getService();
        musicService.setCallBack(this);
        
        // Sync position and listSongs from the service if available
        if (musicService.musicFiles != null && !musicService.musicFiles.isEmpty()) {
            listSongs = musicService.musicFiles;
            if (musicService.position >= 0 && musicService.position < listSongs.size()) {
                position = musicService.position;
                uri = Uri.parse(listSongs.get(position).getPath());
            }
        }
        
        // Check if mediaPlayer is ready before getting duration
        int duration = musicService.getDuration();
        if (duration > 0) {
            seekBar.setMax(duration / 1000);
        }
        
        // Update UI with current song info only if we have valid data
        if (listSongs != null && position >= 0 && position < listSongs.size()) {
            metaData(uri);
            song_name.setText(listSongs.get(position).getTitle());
            artist_name.setText(listSongs.get(position).getArtist());
            album_name.setText(listSongs.get(position).getAlbum());
            
            // Update NowPlayingFragmentBottom
            NowPlayingFragmentBottom.songName.setText(listSongs.get(position).getTitle());
            NowPlayingFragmentBottom.artist.setText(listSongs.get(position).getArtist());
            
            // Update play/pause button based on actual playback state
            if (musicService.isPlaying()) {
                playPauseBtn.setImageResource(R.drawable.ic_pause);
                NowPlayingFragmentBottom.playPauseBtn.setImageResource(R.drawable.ic_pause);
                musicService.showNotification(R.drawable.ic_pause);
            } else {
                playPauseBtn.setImageResource(R.drawable.ic_play);
                NowPlayingFragmentBottom.playPauseBtn.setImageResource(R.drawable.ic_play);
                musicService.showNotification(R.drawable.ic_play);
            }
            
            musicService.OnCompleted();
        }
        
        passMusicService = musicService;
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        musicService = null;
    }

}