package com.fungisoft.seratonin;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.menu.MenuWrapperICS;
import androidx.appcompat.widget.SearchView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import androidx.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TableLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.tabs.TabLayout;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements SearchView.OnQueryTextListener {

    private static final String TAG = "MainActivity";
    public static final int REQUEST_CODE = 1;
    static ArrayList<MusicFiles> musicFiles;
    static boolean shuffleBoolean = false, repeatBoolean = false;
    static ArrayList<MusicFiles> albums = new ArrayList<>();
    private String MY_SORT_PREF = "SortOrder";
    public static final String MUSIC_LAST_PLAYED = "LAST_PLAYED";
    public static final String MUSIC_FILE = "STORED_MUSIC";
    public static boolean SHOW_MINI_PLAYER = false;
    public static String PATH_TO_FRAG = null;
    public static String ARTIST_TO_FRAG = null;
    public static String SONG_NAME_TO_FRAG = null;
    public static final String ARTIST_NAME = "ARTIST NAME";
    public static final String SONG_NAME = "SONG NAME";
    
    // Executor for background tasks
    private ExecutorService executor;
    
    // Progress dialog for caching
    private Dialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressMessage;
    private TextView progressPercent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Check if setup is complete (permission + folder selected)
        if (!FolderSelectionActivity.isSetupComplete(this)) {
            // Redirect to folder selection
            Intent intent = new Intent(this, FolderSelectionActivity.class);
            startActivity(intent);
            finish();
            return;
        }
        
        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        
        // Apply window insets for proper padding
        ConstraintLayout mainContainer = findViewById(R.id.main_container);
        ViewCompat.setOnApplyWindowInsetsListener(mainContainer, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        
        // Setup menu button
        ImageView menuButton = findViewById(R.id.menu_button);
        menuButton.setOnClickListener(this::showMainMenu);
        
        // Load music from selected folder
        boolean runFullScan = getIntent().getBooleanExtra("run_full_scan", false);
        boolean isFolderChange = getIntent().getBooleanExtra("is_folder_change", false);
        loadMusic(runFullScan, isFolderChange);
        
        final SharedPreferences mSharedPreference= PreferenceManager.getDefaultSharedPreferences(this);
        Boolean passedPlayerAct=(mSharedPreference.getBoolean("playerActivitypass", false));
        if (passedPlayerAct == false){
            NowPlayingFragmentBottom.setLayoutInvisible();
        }
        if (PlayerActivity.passMusicService != null){
            if (PlayerActivity.passMusicService.isPlaying()){
                NowPlayingFragmentBottom.playPauseBtn.setImageResource(R.drawable.ic_pause);
            }
            else{
                NowPlayingFragmentBottom.playPauseBtn.setImageResource(R.drawable.ic_play);
            }
        }
        
        // Initialize executor for background tasks
        executor = Executors.newSingleThreadExecutor();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }

    private void loadMusic(boolean runFullScan, boolean isFolderChange) {
        String musicFolderPath = FolderSelectionActivity.getMusicFolderPath(this);
        if (musicFolderPath != null) {
            Log.d(TAG, "Loading music from: " + musicFolderPath);
            
            if (runFullScan) {
                // Show progress dialog for initial scan or folder change
                String title = isFolderChange ? "Scanning New Folder" : "Scanning Music Library";
                loadMusicWithProgress(musicFolderPath, title);
            } else {
                // Quick load without progress dialog
                musicFiles = getAllAudioFromFolder(this, musicFolderPath);
                initViewPager();
                
                // Sync cache in background
                syncCacheInBackground();
            }
        } else {
            Log.e(TAG, "No music folder selected");
            Toast.makeText(this, "Please select a music folder", Toast.LENGTH_SHORT).show();
            // Redirect to folder selection
            Intent intent = new Intent(this, FolderSelectionActivity.class);
            startActivity(intent);
            finish();
        }
    }
    
    /**
     * Load music with progress dialog for initial scan or folder change.
     */
    private void loadMusicWithProgress(String musicFolderPath, String title) {
        showProgressDialog(title);
        
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }
        
        executor.execute(() -> {
            try {
                updateProgress(0, 100, "Clearing old cache...");
                
                // Clear existing caches for fresh start
                MusicCacheDatabase cache = MusicCacheDatabase.getInstance(this);
                cache.clearAllCaches();
                AlbumArtHelper.clearCache(this);
                
                updateProgress(10, 100, "Loading music files...");
                
                // Load music files
                ArrayList<MusicFiles> newMusicFiles = getAllAudioFromFolder(this, musicFolderPath);
                
                updateProgress(20, 100, "Caching metadata...");
                
                // Cache song metadata with progress
                int total = newMusicFiles.size();
                for (int i = 0; i < total; i++) {
                    MusicFiles song = newMusicFiles.get(i);
                    cacheSongMetadata(cache, song);
                    
                    int progress = 20 + ((i * 40) / Math.max(total, 1));
                    updateProgress(progress, 100, "Caching: " + song.getTitle());
                }
                
                updateProgress(60, 100, "Caching album art...");
                
                // Cache album art with progress
                cacheAlbumArtWithProgress(newMusicFiles, 60, 95);
                
                // Update static reference
                musicFiles = newMusicFiles;
                
                updateProgress(100, 100, "Complete!");
                
                // Brief delay to show completion
                Thread.sleep(500);
                
                dismissProgressDialog();
                
                // Initialize UI on main thread
                runOnUiThread(() -> {
                    initViewPager();
                    Toast.makeText(this, "Found " + musicFiles.size() + " songs", Toast.LENGTH_SHORT).show();
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Error loading music with progress", e);
                dismissProgressDialog();
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error loading music", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
    
    /**
     * Sync the music cache in background.
     * Detects file changes and updates the cache accordingly.
     */
    private void syncCacheInBackground() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }
        
        executor.execute(() -> {
            try {
                MusicCacheDatabase cache = MusicCacheDatabase.getInstance(this);
                
                // Get current cached song paths
                Map<String, Long> cachedPaths = cache.getCachedSongPaths();
                
                int newSongs = 0;
                int updatedSongs = 0;
                int deletedSongs = 0;
                
                // Check for new or modified songs
                if (musicFiles != null) {
                    for (MusicFiles song : musicFiles) {
                        String path = song.getPath();
                        if (path == null) continue;
                        
                        File file = new File(path);
                        if (!file.exists()) continue;
                        
                        Long cachedModified = cachedPaths.get(path);
                        if (cachedModified == null) {
                            // New song - add to cache
                            cacheSongMetadata(cache, song);
                            newSongs++;
                        } else if (file.lastModified() != cachedModified) {
                            // Modified song - update cache
                            cacheSongMetadata(cache, song);
                            // Invalidate album art cache for this folder
                            File parentDir = file.getParentFile();
                            if (parentDir != null) {
                                cache.deleteCachedAlbumArt(parentDir.getAbsolutePath());
                            }
                            updatedSongs++;
                        }
                        // Remove from map to track deleted songs
                        cachedPaths.remove(path);
                    }
                }
                
                // Delete songs that no longer exist
                for (String deletedPath : cachedPaths.keySet()) {
                    cache.deleteCachedSong(deletedPath);
                    deletedSongs++;
                }
                
                Log.d(TAG, "Cache sync complete - New: " + newSongs + ", Updated: " + updatedSongs + ", Deleted: " + deletedSongs);
                
                // Pre-populate album art cache for new albums in background
                if (newSongs > 0 && musicFiles != null) {
                    AlbumArtHelper.prePopulateCache(this, new ArrayList<>(musicFiles));
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error syncing cache", e);
            }
        });
    }
    
    /**
     * Cache song metadata with extended tag information.
     */
    private void cacheSongMetadata(MusicCacheDatabase cache, MusicFiles song) {
        // Read extended tags
        TagEditorHelper.AudioTags tags = TagEditorHelper.readTags(song.getPath());
        cache.cacheSong(song, tags.albumArtist, tags.year);
    }
    
    /**
     * Show the main popup menu.
     */
    private void showMainMenu(View anchor) {
        PopupMenu popupMenu = new PopupMenu(this, anchor);
        popupMenu.inflate(R.menu.popup_main);
        
        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            SharedPreferences.Editor editor = getSharedPreferences(MY_SORT_PREF, MODE_PRIVATE).edit();
            
            if (itemId == R.id.by_title) {
                editor.putString("sorting", "sortByTitle");
                editor.apply();
                this.recreate();
                return true;
            } else if (itemId == R.id.by_date) {
                editor.putString("sorting", "sortByDate");
                editor.apply();
                this.recreate();
                return true;
            } else if (itemId == R.id.by_size) {
                editor.putString("sorting", "sortBySize");
                editor.apply();
                this.recreate();
                return true;
            } else if (itemId == R.id.rescan_folders) {
                rescanFolders();
                return true;
            } else if (itemId == R.id.change_folder) {
                Intent intent = new Intent(this, FolderSelectionActivity.class);
                intent.putExtra("changing_folder", true);
                startActivity(intent);
                return true;
            }
            return false;
        });
        
        popupMenu.show();
    }
    
    /**
     * Show progress dialog for caching.
     */
    private void showProgressDialog(String title) {
        progressDialog = new Dialog(this);
        progressDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        progressDialog.setContentView(R.layout.dialog_progress);
        progressDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        progressDialog.setCancelable(false);
        
        TextView progressTitle = progressDialog.findViewById(R.id.progress_title);
        progressMessage = progressDialog.findViewById(R.id.progress_message);
        progressBar = progressDialog.findViewById(R.id.progress_bar);
        progressPercent = progressDialog.findViewById(R.id.progress_percent);
        
        progressTitle.setText(title);
        progressDialog.show();
    }
    
    /**
     * Update progress dialog.
     */
    private void updateProgress(int current, int total, String message) {
        runOnUiThread(() -> {
            if (progressDialog != null && progressDialog.isShowing()) {
                int percent = total > 0 ? (current * 100) / total : 0;
                progressBar.setProgress(percent);
                progressPercent.setText(percent + "%");
                progressMessage.setText(message);
            }
        });
    }
    
    /**
     * Dismiss progress dialog.
     */
    private void dismissProgressDialog() {
        runOnUiThread(() -> {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
                progressDialog = null;
            }
        });
    }
    
    /**
     * Rescan folders - clears cache and reloads music with progress.
     */
    public void rescanFolders() {
        showProgressDialog("Rescanning Library");
        
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }
        
        executor.execute(() -> {
            try {
                updateProgress(0, 100, "Clearing cache...");
                
                // Clear all caches
                MusicCacheDatabase cache = MusicCacheDatabase.getInstance(this);
                cache.clearAllCaches();
                AlbumArtHelper.clearCache(this);
                
                updateProgress(10, 100, "Loading music files...");
                
                // Get music folder path
                String musicFolderPath = FolderSelectionActivity.getMusicFolderPath(this);
                if (musicFolderPath != null) {
                    // Reload music files
                    ArrayList<MusicFiles> newMusicFiles = getAllAudioFromFolder(this, musicFolderPath);
                    
                    updateProgress(30, 100, "Caching metadata...");
                    
                    // Cache song metadata
                    int total = newMusicFiles.size();
                    for (int i = 0; i < total; i++) {
                        MusicFiles song = newMusicFiles.get(i);
                        cacheSongMetadata(cache, song);
                        
                        int progress = 30 + ((i * 40) / Math.max(total, 1));
                        updateProgress(progress, 100, "Caching: " + song.getTitle());
                    }
                    
                    updateProgress(70, 100, "Caching album art...");
                    
                    // Cache album art with progress
                    cacheAlbumArtWithProgress(newMusicFiles, 70, 100);
                    
                    // Update static reference
                    musicFiles = newMusicFiles;
                }
                
                updateProgress(100, 100, "Complete!");
                
                // Brief delay to show completion
                Thread.sleep(500);
                
                dismissProgressDialog();
                
                // Reload UI on main thread
                runOnUiThread(() -> {
                    initViewPager();
                    Toast.makeText(this, "Rescan complete", Toast.LENGTH_SHORT).show();
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Error during rescan", e);
                dismissProgressDialog();
                runOnUiThread(() -> {
                    Toast.makeText(this, "Rescan failed", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
    
    /**
     * Cache album art with progress updates.
     */
    private void cacheAlbumArtWithProgress(ArrayList<MusicFiles> songs, int startProgress, int endProgress) {
        if (songs == null || songs.isEmpty()) return;
        
        java.util.Set<String> processedFolders = new java.util.HashSet<>();
        MusicCacheDatabase cache = MusicCacheDatabase.getInstance(this);
        
        int total = songs.size();
        int progressRange = endProgress - startProgress;
        
        for (int i = 0; i < total; i++) {
            MusicFiles music = songs.get(i);
            String path = music.getPath();
            if (path == null) continue;
            
            java.io.File musicFile = new java.io.File(path);
            java.io.File parentDir = musicFile.getParentFile();
            if (parentDir == null) continue;
            
            String folderPath = parentDir.getAbsolutePath();
            
            // Skip if already processed
            if (processedFolders.contains(folderPath)) continue;
            processedFolders.add(folderPath);
            
            // Skip if already cached
            if (cache.hasAlbumArtCache(folderPath)) continue;
            
            // Load and cache album art
            byte[] art = AlbumArtHelper.getAlbumArtForAlbum(this, path);
            
            int progress = startProgress + ((i * progressRange) / Math.max(total, 1));
            updateProgress(progress, 100, "Art: " + music.getAlbum());
        }
    }

    private void initViewPager() {
        ViewPager2 viewPager = findViewById(R.id.viewpager);
        TabLayout tabLayout = findViewById(R.id.tab_layout);
        ViewPagerAdapter viewPagerAdapter = new ViewPagerAdapter(this);
        viewPagerAdapter.addFragments(new ArtistsFragment(), "Artists");
        viewPagerAdapter.addFragments(new AlbumFragment(), "Albums");
        viewPagerAdapter.addFragments(new SongsFragment(), "Songs");
        viewPager.setAdapter(viewPagerAdapter);
        // Keep all 3 tabs in memory for smooth transitions
        viewPager.setOffscreenPageLimit(2);
        // Use TabLayoutMediator for ViewPager2
        new com.google.android.material.tabs.TabLayoutMediator(tabLayout, viewPager,
                (tab, position) -> tab.setText(viewPagerAdapter.getTitle(position))
        ).attach();
    }

    public static class ViewPagerAdapter extends FragmentStateAdapter {

        private final ArrayList<Fragment> fragments = new ArrayList<>();
        private final ArrayList<String> titles = new ArrayList<>();

        public ViewPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
            super(fragmentActivity);
        }

        void addFragments(Fragment fragment, String title) {
            fragments.add(fragment);
            titles.add(title);
        }

        String getTitle(int position) {
            return titles.get(position);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return fragments.get(position);
        }

        @Override
        public int getItemCount() {
            return fragments.size();
        }
    }

    /**
     * Get all audio files from a specific folder and its subfolders.
     * This replaces the old getAllAudio() method that scanned the entire device.
     *
     * @param context Application context
     * @param folderPath The folder path to scan (selected by user)
     * @return List of music files found in the folder
     */
    public ArrayList<MusicFiles> getAllAudioFromFolder(Context context, String folderPath) {
        SharedPreferences preferences = getSharedPreferences(MY_SORT_PREF, MODE_PRIVATE);
        String sortOrder = preferences.getString("sorting", "sortByName");
        ArrayList<String> duplicate = new ArrayList<>();
        ArrayList<MusicFiles> tempAudioList = new ArrayList<>();
        albums.clear();
        
        String order = null;
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        switch (sortOrder) {
            case "sortByTitle":
                order = MediaStore.MediaColumns.TITLE + " ASC ";
                break;
            case "sortByDate":
                order = MediaStore.MediaColumns.DATE_ADDED + " DESC ";
                break;
            case "sortBySize":
                order = MediaStore.MediaColumns.SIZE + " DESC ";
                break;
        }
        
        String[] projection = {
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media._ID
        };
        
        // Filter by folder path - only get files under the selected folder
        // Use LIKE with wildcard to match all files in folder and subfolders
        String selection = MediaStore.Audio.Media.DATA + " LIKE ?";
        String[] selectionArgs = new String[]{ folderPath + "%" };
        
        Log.d(TAG, "Querying MediaStore for files in: " + folderPath);
        
        Cursor cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, order);
        if (cursor != null) {
            Log.d(TAG, "Found " + cursor.getCount() + " audio files");
            while (cursor.moveToNext()) {
                String album = cursor.getString(0);
                String title = cursor.getString(1);
                String duration = cursor.getString(2);
                String path = cursor.getString(3);
                String artist = cursor.getString(4);
                String id = cursor.getString(5);

                MusicFiles musicFiles = new MusicFiles(path, title, artist, album, duration, id);
                Log.d(TAG, "Found: " + title + " at " + path);
                tempAudioList.add(musicFiles);
                
                if (!duplicate.contains(album)) {
                    albums.add(musicFiles);
                    duplicate.add(album);
                }
            }
            cursor.close();
        } else {
            Log.e(TAG, "MediaStore query returned null cursor");
        }
        
        return tempAudioList;
    }

    /**
     * @deprecated Use getAllAudioFromFolder instead for folder-restricted scanning
     */
    @Deprecated
    public ArrayList<MusicFiles> getAllAudio(Context context)
    {
        SharedPreferences preferences = getSharedPreferences(MY_SORT_PREF, MODE_PRIVATE);
        String sortOrder = preferences.getString("sorting", "sortByName");
        ArrayList<String> duplicate = new ArrayList<>();
        ArrayList<MusicFiles> tempAudioList = new ArrayList<>();
        albums.clear();
        String order = null;
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        switch (sortOrder){
            case "sortByTitle":
                order = MediaStore.MediaColumns.TITLE + " ASC ";
                break;
            case "sortByDate":
                order = MediaStore.MediaColumns.DATE_ADDED + " DESC ";
                break;
            case "sortBySize":
                order = MediaStore.MediaColumns.SIZE + " DESC ";
                break;
        }
        String[] projection = {
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,                // --------FOR PATH--------
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media._ID
//                MediaStore.Audio.Media.GENRE,
//                MediaStore.Audio.Media.CD_TRACK_NUMBER,
//                MediaStore.Audio.Media.ALBUM_ARTIST
        };
        Cursor cursor = context.getContentResolver().query(uri, projection, null, null, order);
        if(cursor != null)
        {
            while (cursor.moveToNext()){
                String album = cursor.getString(0);
                String title = cursor.getString(1);
                String duration = cursor.getString(2);
                String path = cursor.getString(3);
                String artist = cursor.getString(4);
                String id = cursor.getString(5);
//                String genre = cursor.getString(5);
//                String trackNumber = cursor.getString(6);
//                String albumArtist = cursor.getString(7);

                MusicFiles musicFiles = new MusicFiles(path, title, artist, album, duration, id);
//                take log.e for check
                Log.e("Path: " + path, "Album: " + album);
                tempAudioList.add(musicFiles);
                if(!duplicate.contains(album)){
                    albums.add(musicFiles);
                    duplicate.add(album);
                }
            }
            cursor.close();
        }
        return tempAudioList;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.search, menu);
        MenuItem menuItem = menu.findItem(R.id.search_option);
        SearchView searchView = (SearchView) menuItem.getActionView();
        searchView.setOnQueryTextListener(this);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        return false;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        String userInput = newText.toLowerCase();
        ArrayList<MusicFiles> myFiles = new ArrayList<>();
        for (MusicFiles song : musicFiles){
            if (song.getTitle().toLowerCase().contains(userInput)){
                myFiles.add(song);
            }
        }
        SongsFragment.musicAdapter.updateList(myFiles);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        SharedPreferences.Editor editor = getSharedPreferences(MY_SORT_PREF, MODE_PRIVATE).edit();
        int itemId = item.getItemId();
        if (itemId == R.id.by_title) {
            editor.putString("sorting", "sortByTitle");
            editor.apply();
            this.recreate();
        } else if (itemId == R.id.by_date) {
            editor.putString("sorting", "sortByDate");
            editor.apply();
            this.recreate();
        } else if (itemId == R.id.by_size) {
            editor.putString("sorting", "sortBySize");
            editor.apply();
            this.recreate();
        } else if (itemId == R.id.rescan_folders) {
            // Rescan folders and refresh cache
            rescanFolders();
        } else if (itemId == R.id.change_folder) {
            // Open folder selection activity
            Intent intent = new Intent(this, FolderSelectionActivity.class);
            intent.putExtra("changing_folder", true);
            startActivity(intent);
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences preferences = getSharedPreferences(MUSIC_LAST_PLAYED, MODE_PRIVATE);
        String path = preferences.getString(MUSIC_FILE, null);
        String artist = preferences.getString(ARTIST_NAME, null);
        String  song_name = preferences.getString(SONG_NAME, null);
        if (path != null) {
            SHOW_MINI_PLAYER = true;
            PATH_TO_FRAG = path;
            ARTIST_TO_FRAG = artist;
            SONG_NAME_TO_FRAG = song_name;
        }
        else{
            SHOW_MINI_PLAYER = false;
            PATH_TO_FRAG = null;
            ARTIST_TO_FRAG = null;
            SONG_NAME_TO_FRAG = null;
        }
    }


}