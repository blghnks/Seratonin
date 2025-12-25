package com.fungisoft.seratonin;

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
import android.media.MediaMetadataRetriever;
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
import com.bumptech.glide.Glide;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.Random;

import static com.fungisoft.seratonin.AlbumDetailsAdapter.albumFiles;
import static com.fungisoft.seratonin.MainActivity.repeatBoolean;
import static com.fungisoft.seratonin.MainActivity.shuffleBoolean;
import static com.fungisoft.seratonin.MusicAdapter.mFiles;

public class PlayerActivity extends AppCompatActivity implements ActionPlaying, ServiceConnection {

    TextView song_name, artist_name, duration_played, duration_total, album_name, textNowplaying;
    ImageView cover_art, nextBtn, prevBtn, backBtn, shuffleBtn, repeatBtn;
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
        initViews();
        getIntenMethod();
//        PassingBottomFrag();
        if (shuffleBoolean && !repeatBoolean){
            shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
        }else if (!shuffleBoolean && repeatBoolean){
            repeatBtn.setImageResource(R.drawable.ic_repeat_on);
        }else if (shuffleBoolean){
            shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
            repeatBtn.setImageResource(R.drawable.ic_repeat_on);
        }
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
                if (shuffleBoolean)
                {
                    shuffleBoolean = false;
                    shuffleBtn.setImageResource(R.drawable.ic_shuffle_off);
                }else {
                    shuffleBoolean = true;
                    shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
                }
            }
        });
        repeatBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(repeatBoolean)
                {
                    repeatBoolean = false;
                    repeatBtn.setImageResource(R.drawable.ic_repeat_off);

                }else {
                    repeatBoolean = true;
                    repeatBtn.setImageResource(R.drawable.ic_repeat_on);
                }
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
            if (shuffleBoolean && !repeatBoolean){
                position = getRandom(listSongs.size() - 1);
                shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
            } else if (!shuffleBoolean && !repeatBoolean) {
                position = ((position - 1) < 0 ? (listSongs.size() - 1) : (position -1));
            }else if (!shuffleBoolean){
                repeatBtn.setImageResource(R.drawable.ic_repeat_on);
            }else {
                shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
                repeatBtn.setImageResource(R.drawable.ic_repeat_on);
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
            if (shuffleBoolean && !repeatBoolean){
                position = getRandom(listSongs.size() - 1);
                shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
            } else if (!shuffleBoolean && !repeatBoolean) {
                position = ((position - 1) < 0 ? (listSongs.size() - 1) : (position -1));
            }else if (!shuffleBoolean){
                repeatBtn.setImageResource(R.drawable.ic_repeat_on);
            }else {
                shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
                repeatBtn.setImageResource(R.drawable.ic_repeat_on);
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
            if (shuffleBoolean && !repeatBoolean){
                position = getRandom(listSongs.size() - 1);
                shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
            } else if (!shuffleBoolean && !repeatBoolean) {
                position = ((position + 1) % listSongs.size());
            }else if (!shuffleBoolean){
                repeatBtn.setImageResource(R.drawable.ic_repeat_on);
            }else {
                shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
                repeatBtn.setImageResource(R.drawable.ic_repeat_on);
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
            if (shuffleBoolean && !repeatBoolean){
                position = getRandom(listSongs.size() - 1);
                shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
            } else if (!shuffleBoolean && !repeatBoolean) {
                position = ((position + 1) % listSongs.size());
            }else if (!shuffleBoolean){
                repeatBtn.setImageResource(R.drawable.ic_repeat_on);
            }else {
                shuffleBtn.setImageResource(R.drawable.ic_shuffle_on);
                repeatBtn.setImageResource(R.drawable.ic_repeat_on);
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
//         = findViewById(R.id.);
//         = findViewById(R.id.);
//         = findViewById(R.id.);
    }
    public boolean isColorDark(int color){
        double darkness = 1-(0.299*Color.red(color) + 0.587*Color.green(color) + 0.114*Color.blue(color))/255;
        return !(darkness < 0.5);
    }
    private void metaData(Uri uri){
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(uri.toString());
            int durationTotal = Integer.parseInt(listSongs.get(position).getDuration()) / 1000;
            duration_total.setText (formattedTime(durationTotal));
            byte[] art = retriever.getEmbeddedPicture();
            artist_image = art;
            Bitmap bitmap = null;
            if (art != null){
                bitmap = BitmapFactory.decodeByteArray(art, 0, art.length);
            }
            if (bitmap != null){
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
            else{
                if (isValidContextForGlide(this)){
                    // Load image via Glide lib using context
                    Glide.with(this)
                            .asBitmap()
                            .load(R.drawable.musicicon)
                            .into(cover_art);
                }
                // No album art - use default dark theme colors
                setDefaultColors();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
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