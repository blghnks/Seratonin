package com.fungisoft.seratonin;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import java.util.ArrayList;

import static com.fungisoft.seratonin.ApplicationClass.ACTION_NEXT;
import static com.fungisoft.seratonin.ApplicationClass.ACTION_PLAY;
import static com.fungisoft.seratonin.ApplicationClass.ACTION_PREVIOUS;
import static com.fungisoft.seratonin.ApplicationClass.ACTION_STOP;
import static com.fungisoft.seratonin.ApplicationClass.CHANNEL_ID_2;
import static com.fungisoft.seratonin.PlayerActivity.listSongs;

public class MusicService extends Service implements MediaPlayer.OnCompletionListener {

    private static final String TAG = "MusicService";
    
    IBinder mBinder = new MyBinder();
    MediaPlayer mediaPlayer;
    ArrayList<MusicFiles> musicFiles = new ArrayList<>();
    Uri uri;
    int position = -1;
    ActionPlaying actionPlaying;
    MediaSessionCompat mediaSessionCompat;
    
    public static final String MUSIC_LAST_PLAYED = "LAST_PLAYED";
    public static final String MUSIC_FILE = "STORED_MUSIC";
    public static final String ARTIST_NAME = "ARTIST NAME";
    public static final String SONG_NAME = "SONG NAME";
    static int passPosition;

    NotificationManager notificationManager;
    private Handler progressHandler;
    private Runnable progressRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        
        // Initialize MediaSession with proper callbacks
        mediaSessionCompat = new MediaSessionCompat(getBaseContext(), "SeratoninMediaSession");
        mediaSessionCompat.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                if (!isPlaying() && mediaPlayer != null) {
                    start();
                    showNotification(R.drawable.ic_pause);
                    updatePlaybackState();
                }
            }

            @Override
            public void onPause() {
                if (isPlaying()) {
                    pause();
                    showNotification(R.drawable.ic_play);
                    updatePlaybackState();
                }
            }

            @Override
            public void onSkipToNext() {
                nextBtnClicked();
            }

            @Override
            public void onSkipToPrevious() {
                prevBtnClicked();
            }

            @Override
            public void onStop() {
                stopPlayback();
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo((int) pos);
                updatePlaybackState();
            }
        });
        
        mediaSessionCompat.setActive(true);
        
        // Initialize progress handler for updating notification seek bar
        progressHandler = new Handler(Looper.getMainLooper());
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (isPlaying()) {
                    updatePlaybackState();
                    progressHandler.postDelayed(this, 1000);
                }
            }
        };
        
        try {
            musicFiles = listSongs;
        } catch (NullPointerException e) {
            Log.e(TAG, "Error getting listSongs", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (progressHandler != null) {
            progressHandler.removeCallbacks(progressRunnable);
        }
        if (mediaSessionCompat != null) {
            mediaSessionCompat.setActive(false);
            mediaSessionCompat.release();
        }
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind");
        return mBinder;
    }

    public class MyBinder extends Binder {
        MusicService getService() {
            return MusicService.this;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        
        int myPosition = intent.getIntExtra("servicePosition", -1);
        String actionName = intent.getStringExtra("ActionName");
        
        if (myPosition != -1) {
            try {
                playMedia(myPosition);
            } catch (Exception e) {
                Log.e(TAG, "Error in playMedia", e);
            }
        }
        
        if (actionName != null) {
            switch (actionName) {
                case "playPause":
                    playPauseBtnClicked();
                    break;
                case "next":
                    nextBtnClicked();
                    break;
                case "previous":
                    prevBtnClicked();
                    break;
                case "stop":
                    stopPlayback();
                    break;
            }
        }
        return START_STICKY;
    }

    private void playMedia(int startPosition) {
        musicFiles = listSongs;
        position = startPosition;
        
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        
        if (musicFiles != null && position >= 0 && position < musicFiles.size()) {
            createMediaPlayer(position);
            if (mediaPlayer != null) {
                mediaPlayer.start();
                updateMediaSessionMetadata();
                updatePlaybackState();
                startProgressUpdates();
            }
        }
    }

    /**
     * Stops playback, removes notification, and stops the foreground service.
     */
    void stopPlayback() {
        if (progressHandler != null) {
            progressHandler.removeCallbacks(progressRunnable);
        }
        
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
        
        // Update playback state to stopped
        if (mediaSessionCompat != null) {
            mediaSessionCompat.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0, 0)
                    .build());
        }
        
        // Stop foreground and remove notification
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (notificationManager != null) {
            notificationManager.cancel(2);
        }
        
        stopSelf();
    }

    void start() {
        if (mediaPlayer != null) {
            mediaPlayer.start();
            startProgressUpdates();
        }
    }

    boolean isPlaying() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.isPlaying();
            } catch (IllegalStateException e) {
                return false;
            }
        }
        return false;
    }

    void stop() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            progressHandler.removeCallbacks(progressRunnable);
        }
    }

    void pause() {
        if (mediaPlayer != null) {
            mediaPlayer.pause();
            progressHandler.removeCallbacks(progressRunnable);
        }
    }

    void release() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    int getDuration() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.getDuration();
            } catch (IllegalStateException e) {
                return 0;
            }
        }
        return 0;
    }

    void seekTo(int position) {
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(position);
        }
    }

    int getCurrentPosition() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.getCurrentPosition();
            } catch (IllegalStateException e) {
                return 0;
            }
        }
        return 0;
    }

    void createMediaPlayer(int positionInner) {
        position = positionInner;
        
        if (musicFiles == null || position < 0 || position >= musicFiles.size()) {
            Log.e(TAG, "Invalid position or musicFiles");
            return;
        }
        
        try {
            uri = Uri.parse(musicFiles.get(position).getPath());
        } catch (Exception e) {
            Log.e(TAG, "Error parsing URI", e);
            return;
        }
        
        SharedPreferences.Editor editor = getSharedPreferences(MUSIC_LAST_PLAYED, MODE_PRIVATE).edit();
        editor.putString(MUSIC_FILE, uri.toString());
        editor.putString(ARTIST_NAME, musicFiles.get(position).getArtist());
        editor.putString(SONG_NAME, musicFiles.get(position).getTitle());
        editor.apply();
        
        mediaPlayer = MediaPlayer.create(getBaseContext(), uri);
        if (mediaPlayer != null) {
            mediaPlayer.setOnCompletionListener(this);
        }
    }

    void OnCompleted() {
        if (mediaPlayer != null) {
            mediaPlayer.setOnCompletionListener(this);
        }
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        if (actionPlaying != null) {
            actionPlaying.nextBtnClicked();
            if (mediaPlayer != null) {
                createMediaPlayer(position);
                mediaPlayer.start();
                updateMediaSessionMetadata();
                updatePlaybackState();
                startProgressUpdates();
            }
        }
    }

    void setCallBack(ActionPlaying actionPlaying) {
        this.actionPlaying = actionPlaying;
    }

    /**
     * Updates MediaSession metadata with current song info
     */
    private void updateMediaSessionMetadata() {
        if (musicFiles == null || position < 0 || position >= musicFiles.size()) {
            return;
        }
        
        MusicFiles currentSong = musicFiles.get(position);
        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentSong.getTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentSong.getArtist())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentSong.getAlbum());
        
        // Add duration
        try {
            long duration = Long.parseLong(currentSong.getDuration());
            builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Error parsing duration", e);
        }
        
        // Add album art - use song art loading: embedded first, then external fallback
        byte[] albumArt = AlbumArtHelper.getAlbumArtForSong(this, currentSong.getPath());
        if (albumArt != null) {
            Bitmap bitmap = BitmapFactory.decodeByteArray(albumArt, 0, albumArt.length);
            if (bitmap != null) {
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap);
            }
        }
        
        mediaSessionCompat.setMetadata(builder.build());
    }

    /**
     * Updates MediaSession playback state (for system UI and seek bar)
     */
    void updatePlaybackState() {
        int state = isPlaying() ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        long position = getCurrentPosition();
        float playbackSpeed = isPlaying() ? 1.0f : 0f;
        
        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                        PlaybackStateCompat.ACTION_PAUSE |
                        PlaybackStateCompat.ACTION_PLAY_PAUSE |
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackStateCompat.ACTION_STOP |
                        PlaybackStateCompat.ACTION_SEEK_TO)
                .setState(state, position, playbackSpeed);
        
        mediaSessionCompat.setPlaybackState(stateBuilder.build());
    }

    /**
     * Starts periodic updates for playback state (for notification seek bar)
     */
    private void startProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable);
        progressHandler.post(progressRunnable);
    }

    void showNotification(int playPauseBtn) {
        // Check if we have valid data before showing notification
        if (musicFiles == null || musicFiles.isEmpty() || position < 0 || position >= musicFiles.size()) {
            return;
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        Intent prevIntent = new Intent(this, NotificationReceiver.class)
                .setAction(ACTION_PREVIOUS);
        PendingIntent prevPending = PendingIntent.getBroadcast(this, 0, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent pauseIntent = new Intent(this, NotificationReceiver.class)
                .setAction(ACTION_PLAY);
        PendingIntent pausePending = PendingIntent.getBroadcast(this, 0, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent nextIntent = new Intent(this, NotificationReceiver.class)
                .setAction(ACTION_NEXT);
        PendingIntent nextPending = PendingIntent.getBroadcast(this, 0, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, NotificationReceiver.class)
                .setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getBroadcast(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Use song art loading: embedded first, then external fallback
        byte[] picture = AlbumArtHelper.getAlbumArtForSong(this, musicFiles.get(position).getPath());
        Bitmap thumb = null;
        if (picture != null) {
            thumb = BitmapFactory.decodeByteArray(picture, 0, picture.length);
        }
        if (thumb == null) {
            thumb = BitmapFactory.decodeResource(getResources(), R.drawable.musicicon);
        }
        
        passPosition = position;
        
        // Update MediaSession metadata
        updateMediaSessionMetadata();
        updatePlaybackState();

        // Determine play/pause icon and action text
        String playPauseActionText = isPlaying() ? "Pause" : "Play";
        
        // Build notification with MediaStyle
        // Actions: Previous (0), Play/Pause (1), Next (2), Stop (3)
        // Show indices 0, 1, 2 in compact view
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID_2)
                .setSmallIcon(R.drawable.ic_play)
                .setLargeIcon(thumb)
                .setContentTitle(musicFiles.get(position).getTitle())
                .setContentText(musicFiles.get(position).getArtist())
                .setSubText(musicFiles.get(position).getAlbum())
                .addAction(R.drawable.ic_skip_previous, "Previous", prevPending)
                .addAction(playPauseBtn, playPauseActionText, pausePending)
                .addAction(R.drawable.ic_skip_next, "Next", nextPending)
                .addAction(R.drawable.ic_close, "Close", stopPending)
                .setContentIntent(contentIntent)
                .setStyle(new MediaStyle()
                        .setMediaSession(mediaSessionCompat.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2)
                        .setShowCancelButton(true)
                        .setCancelButtonIntent(stopPending))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(isPlaying())
                .build();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(2, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(2, notification);
        }
        
        notificationManager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(2, notification);
    }

    void playPauseBtnClicked() {
        if (actionPlaying != null) {
            actionPlaying.playPauseBtnClicked();
        }
    }

    void nextBtnClicked() {
        if (actionPlaying != null) {
            actionPlaying.nextBtnClicked();
        }
    }

    void prevBtnClicked() {
        if (actionPlaying != null) {
            actionPlaying.prevBtnClicked();
        }
    }
}
