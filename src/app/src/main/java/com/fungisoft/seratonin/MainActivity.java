package com.fungisoft.seratonin;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.fungisoft.seratonin.MusicService.MUSIC_LAST_PLAYED;
import static com.fungisoft.seratonin.MusicService.MUSIC_FILE;
import static com.fungisoft.seratonin.MusicService.ARTIST_NAME;
import static com.fungisoft.seratonin.MusicService.SONG_NAME;

public class MainActivity extends AppCompatActivity implements SearchView.OnQueryTextListener {
    private static final String TAG = "MainActivity";
    private static final String STATE_SELECTED_TAB = "tab";
    
    public static final int REQUEST_CODE = 1;
    static ArrayList<MusicFiles> musicFiles;
    static boolean shuffleBoolean = false, repeatBoolean = false;
    static ArrayList<MusicFiles> albums = new ArrayList<>();
    private String MY_SORT_PREF = "SortOrder";
    private static final String KEY_SONGS_SORTING = "songsSorting";
    private static final String KEY_ALBUMS_SORTING = "albumsSorting";
    public static boolean SHOW_MINI_PLAYER = false;
    public static String PATH_TO_FRAG = null;
    public static String ARTIST_TO_FRAG = null;
    public static String SONG_NAME_TO_FRAG = null;
    
    // Executor for background tasks
    private ExecutorService executor;
    
    // Progress dialog for caching
    private Dialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressMessage;
    private TextView progressPercent;
    
    // M3U import launcher
    private ActivityResultLauncher<Intent> m3uImportLauncher;
    
    // ViewPager for tab restoration
    private ViewPager2 viewPager;
    private int savedTabPosition = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) savedTabPosition = savedInstanceState.getInt(STATE_SELECTED_TAB, 0);
        
        // Initialize M3U import launcher
        m3uImportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        importM3UPlaylist(uri);
                    }
                }
            }
        );
        
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
        
        final SharedPreferences mSharedPreference = PreferenceManager.getDefaultSharedPreferences(this);
        boolean passedPlayerAct = mSharedPreference.getBoolean("playerActivitypass", false);
        if (!passedPlayerAct) {
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
                AlbumArtLoader.getInstance().clearCache();
                
                updateProgress(10, 100, "Loading music files...");
                
                // Load music files
                ArrayList<MusicFiles> newMusicFiles = getAllAudioFromFolder(this, musicFolderPath);
                
                final int totalSongs = newMusicFiles.size();
                
                // Progress range: 20-60% for metadata caching (40% of progress bar)
                // Use new batch caching with progress callback to prevent hangs
                cache.cacheSongsBatchWithProgress(newMusicFiles, path -> {
                    TagEditorHelper.AudioTags tags = TagEditorHelper.readTags(path);
                    return new String[] { tags.albumArtist, tags.year };
                }, (processed, total, currentFile) -> {
                    // Calculate progress within the 20-60% range
                    int progressPercent = 20 + ((processed * 40) / Math.max(total, 1));
                    String message = currentFile != null 
                        ? "Caching: " + currentFile 
                        : "Caching metadata... (" + processed + "/" + total + ")";
                    updateProgress(progressPercent, 100, message);
                });
                
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
        
        // Hide sort options for Artists tab (tab 0)
        int currentTab = viewPager != null ? viewPager.getCurrentItem() : 2;
        Menu menu = popupMenu.getMenu();
        MenuItem sortItem = menu.findItem(R.id.sort_options);
        if (sortItem != null) {
            sortItem.setVisible(currentTab != 0); // Hide for Artists (tab 0)
        }
        
        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            SharedPreferences.Editor editor = getSharedPreferences(MY_SORT_PREF, MODE_PRIVATE).edit();
            // Determine which key to use: tab 1 = Albums, tab 2 = Songs
            String sortKey = (currentTab == 1) ? KEY_ALBUMS_SORTING : KEY_SONGS_SORTING;
            
            if (itemId == R.id.by_title) {
                editor.putString(sortKey, "sortByTitle");
                editor.apply();
                this.recreate();
                return true;
            } else if (itemId == R.id.by_date) {
                editor.putString(sortKey, "sortByDate");
                editor.apply();
                this.recreate();
                return true;
            } else if (itemId == R.id.by_size) {
                editor.putString(sortKey, "sortBySize");
                editor.apply();
                this.recreate();
                return true;
            } else if (itemId == R.id.rescan_folders) {
                rescanFolders();
                return true;
            } else if (itemId == R.id.import_m3u_playlist) {
                openM3UFilePicker();
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
     * Open file picker for selecting M3U playlist file.
     */
    private void openM3UFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        // M3U files might have various MIME types
        String[] mimeTypes = {"audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", 
                              "application/x-mpegurl", "text/plain", "*/*"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        
        try {
            m3uImportLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error launching file picker", e);
            Toast.makeText(this, "Could not open file picker", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Import M3U playlist and replace the current queue.
     */
    private void importM3UPlaylist(Uri uri) {
        showProgressDialog("Importing Playlist");
        
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }
        
        executor.execute(() -> {
            try {
                updateProgress(10, 100, "Reading playlist...");
                
                // Get base directory for relative paths (use music folder)
                String baseDir = FolderSelectionActivity.getMusicFolderPath(this);
                
                // Import songs from M3U
                QueueDatabase queueDb = QueueDatabase.getInstance(this);
                ArrayList<MusicFiles> importedSongs = queueDb.importFromM3UUri(uri, this, baseDir);
                
                if (importedSongs == null || importedSongs.isEmpty()) {
                    dismissProgressDialog();
                    runOnUiThread(() -> {
                        Toast.makeText(this, "No valid songs found in playlist", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                
                updateProgress(50, 100, "Replacing queue with " + importedSongs.size() + " songs...");
                
                // Clear existing queue and save new one
                queueDb.clearQueue();
                queueDb.saveQueue(importedSongs);
                queueDb.saveCurrentIndex(0);
                
                // Update PlayerActivity's listSongs
                runOnUiThread(() -> {
                    PlayerActivity.listSongs.clear();
                    PlayerActivity.listSongs.addAll(importedSongs);
                });
                
                updateProgress(100, 100, "Complete!");
                
                // Brief delay to show completion
                Thread.sleep(500);
                
                dismissProgressDialog();
                
                final int count = importedSongs.size();
                runOnUiThread(() -> {
                    Toast.makeText(this, "Loaded playlist with " + count + " songs", Toast.LENGTH_SHORT).show();
                    
                    // Launch PlayerActivity to start playing from the first song
                    Intent intent = new Intent(this, PlayerActivity.class);
                    intent.putExtra("position", 0);
                    intent.putExtra("fromPlaylist", true);
                    startActivity(intent);
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Error importing M3U playlist", e);
                dismissProgressDialog();
                runOnUiThread(() -> {
                    Toast.makeText(this, "Failed to import playlist", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
    
    /**
     * Rescan folders - clears cache and reloads music with progress.
     * 
     * This method has been improved to:
     * 1. Use timeout-protected metadata reading to prevent hangs on corrupt files
     * 2. Show granular progress during metadata caching
     * 3. Continue processing even if individual files fail
     * 4. Use batched transactions to prevent database lock issues
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
                AlbumArtLoader.getInstance().clearCache();
                
                updateProgress(10, 100, "Loading music files...");
                
                // Get music folder path
                String musicFolderPath = FolderSelectionActivity.getMusicFolderPath(this);
                if (musicFolderPath != null) {
                    // Reload music files
                    ArrayList<MusicFiles> newMusicFiles = getAllAudioFromFolder(this, musicFolderPath);
                    
                    final int totalSongs = newMusicFiles.size();
                    
                    // Progress range: 20-70% for metadata caching (50% of progress bar)
                    // Use new batch caching with progress callback to prevent hangs
                    MusicCacheDatabase.BatchCacheResult cacheResult = cache.cacheSongsBatchWithProgress(
                        newMusicFiles, 
                        path -> {
                            // This is now timeout-protected via TagEditorHelper.readTags()
                            TagEditorHelper.AudioTags tags = TagEditorHelper.readTags(path);
                            return new String[] { tags.albumArtist, tags.year };
                        }, 
                        (processed, total, currentFile) -> {
                            // Calculate progress within the 20-70% range
                            int progressPercent = 20 + ((processed * 50) / Math.max(total, 1));
                            String message = currentFile != null 
                                ? "Caching: " + currentFile 
                                : "Caching metadata... (" + processed + "/" + total + ")";
                            updateProgress(progressPercent, 100, message);
                        }
                    );
                    
                    // Log any issues for debugging
                    if (cacheResult.errorCount > 0) {
                        Log.w(TAG, "Rescan completed with " + cacheResult.errorCount + " errors");
                    }
                    
                    updateProgress(70, 100, "Caching album art...");
                    
                    // Cache album art with progress
                    cacheAlbumArtWithProgress(newMusicFiles, 70, 95);
                    
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
            
            // Skip if already cached (use album-level cache key)
            String albumCacheKey = MusicCacheDatabase.getAlbumCacheKey(folderPath);
            if (cache.hasCachedArtByKey(albumCacheKey)) continue;
            
            // Load and cache album art
            byte[] art = AlbumArtHelper.getAlbumArtForAlbum(this, path);
            
            int progress = startProgress + ((i * progressRange) / Math.max(total, 1));
            updateProgress(progress, 100, "Art: " + music.getAlbum());
        }
    }

    private void initViewPager() {
        viewPager = findViewById(R.id.viewpager);
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
        
        // Restore saved tab position after configuration change
        if (savedTabPosition > 0 && savedTabPosition < viewPagerAdapter.getItemCount()) {
            viewPager.setCurrentItem(savedTabPosition, false);
        }
    }
    
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (viewPager != null) outState.putInt(STATE_SELECTED_TAB, viewPager.getCurrentItem());
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
     * Songs and Albums are sorted independently based on their respective sort preferences.
     *
     * @param context Application context
     * @param folderPath The folder path to scan (selected by user)
     * @return List of music files found in the folder
     */
    public ArrayList<MusicFiles> getAllAudioFromFolder(Context context, String folderPath) {
        SharedPreferences preferences = getSharedPreferences(MY_SORT_PREF, MODE_PRIVATE);
        String songsSortOrder = preferences.getString(KEY_SONGS_SORTING, 
                preferences.getString("sorting", "sortByTitle")); // Migrate old key
        String albumsSortOrder = preferences.getString(KEY_ALBUMS_SORTING, "sortByTitle");
        
        ArrayList<MusicFiles> tempAudioList = new ArrayList<>();
        albums.clear();
        
        // Album aggregates: key = normalized album name, value = [totalSize, year, representative MusicFiles]
        HashMap<String, long[]> albumSizeMap = new HashMap<>();
        HashMap<String, String> albumYearMap = new HashMap<>();
        HashMap<String, String> albumArtistMap = new HashMap<>();
        HashMap<String, MusicFiles> albumRepresentative = new HashMap<>();
        
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        
        // Query without ORDER BY - we'll sort in-memory after enriching with cached metadata
        String[] projection = {
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.YEAR
        };
        
        String selection = MediaStore.Audio.Media.DATA + " LIKE ?";
        String[] selectionArgs = new String[]{ folderPath + "%" };
        
        Log.d(TAG, "Querying MediaStore for files in: " + folderPath);
        
        // Build a cache lookup map for year data
        MusicCacheDatabase cache = MusicCacheDatabase.getInstance(context);
        Map<String, MusicCacheDatabase.CachedSongMetadata> cachedMap = new HashMap<>();
        for (MusicCacheDatabase.CachedSongMetadata cached : cache.getAllCachedSongs()) {
            cachedMap.put(cached.path, cached);
        }
        
        Cursor cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, null);
        if (cursor != null) {
            Log.d(TAG, "Found " + cursor.getCount() + " audio files");
            while (cursor.moveToNext()) {
                String album = cursor.getString(0);
                String title = cursor.getString(1);
                String duration = cursor.getString(2);
                String path = cursor.getString(3);
                String artist = cursor.getString(4);
                String id = cursor.getString(5);
                long size = cursor.getLong(6);
                String year = cursor.getString(7);

                MusicFiles musicFile = new MusicFiles(path, title, artist, album, duration, id);
                musicFile.setSize(size);
                
                // Set year from MediaStore first (reliable, fast)
                if (year != null && !year.isEmpty() && !"0".equals(year)) {
                    musicFile.setYear(year);
                } else {
                    // Fallback to cache if MediaStore doesn't have year
                    MusicCacheDatabase.CachedSongMetadata cached = cachedMap.get(path);
                    if (cached != null && cached.year != null) {
                        musicFile.setYear(cached.year);
                    }
                }
                
                Log.d(TAG, "Found: " + title + " at " + path);
                tempAudioList.add(musicFile);
                
                // Aggregate album data
                String normalizedAlbum = StringNormalizer.normalizeForComparison(album);
                
                // Get album artist from cache if available
                MusicCacheDatabase.CachedSongMetadata cachedMeta = cachedMap.get(path);
                String trackAlbumArtist = (cachedMeta != null && cachedMeta.albumArtist != null) ? cachedMeta.albumArtist : "";
                
                long[] sizeArr = albumSizeMap.get(normalizedAlbum);
                if (sizeArr == null) {
                    albumSizeMap.put(normalizedAlbum, new long[]{size});
                    albumYearMap.put(normalizedAlbum, musicFile.getYear());
                    albumArtistMap.put(normalizedAlbum, trackAlbumArtist);
                    albumRepresentative.put(normalizedAlbum, musicFile);
                } else {
                    sizeArr[0] += size;
                    // Use earliest year for album (non-empty wins)
                    String existingYear = albumYearMap.get(normalizedAlbum);
                    String newYear = musicFile.getYear();
                    if ((existingYear == null || existingYear.isEmpty()) && newYear != null && !newYear.isEmpty()) {
                        albumYearMap.put(normalizedAlbum, newYear);
                    } else if (existingYear != null && !existingYear.isEmpty() && newYear != null && !newYear.isEmpty()) {
                        int existingYearInt = parseYear(existingYear);
                        int newYearInt = parseYear(newYear);
                        if (newYearInt > 0 && (existingYearInt == 0 || newYearInt < existingYearInt)) {
                            albumYearMap.put(normalizedAlbum, newYear);
                        }
                    }
                    // Use first non-empty album artist found
                    String existingAlbumArtist = albumArtistMap.get(normalizedAlbum);
                    if ((existingAlbumArtist == null || existingAlbumArtist.isEmpty()) && !trackAlbumArtist.isEmpty()) {
                        albumArtistMap.put(normalizedAlbum, trackAlbumArtist);
                    }
                }
            }
            cursor.close();
        } else {
            Log.e(TAG, "MediaStore query returned null cursor");
        }
        
        // Sort songs in-memory
        sortMusicFiles(tempAudioList, songsSortOrder);
        
        // Build and sort albums list
        ArrayList<MusicFiles> albumsList = new ArrayList<>(albumRepresentative.values());
        // Set aggregated size, year, and album artist on album representatives
        for (MusicFiles albumRep : albumsList) {
            String normalizedAlbum = StringNormalizer.normalizeForComparison(albumRep.getAlbum());
            long[] sizeArr = albumSizeMap.get(normalizedAlbum);
            if (sizeArr != null) {
                albumRep.setSize(sizeArr[0]);
            }
            String year = albumYearMap.get(normalizedAlbum);
            if (year != null) {
                albumRep.setYear(year);
            }
            String albumArtist = albumArtistMap.get(normalizedAlbum);
            if (albumArtist != null) {
                albumRep.setAlbumArtist(albumArtist);
            }
        }
        sortAlbums(albumsList, albumsSortOrder);
        albums.clear();
        albums.addAll(albumsList);
        
        return tempAudioList;
    }
    
    /**
     * Sort songs in-place based on sort order.
     */
    private void sortMusicFiles(ArrayList<MusicFiles> list, String sortOrder) {
        Comparator<MusicFiles> comparator;
        switch (sortOrder) {
            case "sortByDate":
                comparator = (a, b) -> {
                    int yearA = parseYear(a.getYear());
                    int yearB = parseYear(b.getYear());
                    // Descending by year (newest first)
                    int cmp = Integer.compare(yearB, yearA);
                    if (cmp == 0) {
                        // Group songs from the same album together
                        String albumA = a.getAlbum() != null ? a.getAlbum() : "";
                        String albumB = b.getAlbum() != null ? b.getAlbum() : "";
                        cmp = albumA.compareToIgnoreCase(albumB);
                    }
                    if (cmp == 0) {
                        // Within same album, sort by title
                        String titleA = a.getTitle() != null ? a.getTitle() : "";
                        String titleB = b.getTitle() != null ? b.getTitle() : "";
                        cmp = titleA.compareToIgnoreCase(titleB);
                    }
                    return cmp;
                };
                break;
            case "sortBySize":
                comparator = (a, b) -> {
                    // Descending by size
                    int cmp = Long.compare(b.getSize(), a.getSize());
                    if (cmp == 0) {
                        String titleA = a.getTitle() != null ? a.getTitle() : "";
                        String titleB = b.getTitle() != null ? b.getTitle() : "";
                        cmp = titleA.compareToIgnoreCase(titleB);
                    }
                    return cmp;
                };
                break;
            case "sortByTitle":
            default:
                comparator = (a, b) -> {
                    String titleA = a.getTitle() != null ? a.getTitle() : "";
                    String titleB = b.getTitle() != null ? b.getTitle() : "";
                    return titleA.compareToIgnoreCase(titleB);
                };
                break;
        }
        Collections.sort(list, comparator);
    }
    
    /**
     * Sort albums in-place based on sort order.
     * Uses album title (not song title) for title sorting.
     */
    private void sortAlbums(ArrayList<MusicFiles> list, String sortOrder) {
        Comparator<MusicFiles> comparator;
        switch (sortOrder) {
            case "sortByDate":
                comparator = (a, b) -> {
                    int yearA = parseYear(a.getYear());
                    int yearB = parseYear(b.getYear());
                    // Descending by year (newest first), fallback to album title
                    int cmp = Integer.compare(yearB, yearA);
                    if (cmp == 0) {
                        String albumA = a.getAlbum() != null ? a.getAlbum() : "";
                        String albumB = b.getAlbum() != null ? b.getAlbum() : "";
                        cmp = albumA.compareToIgnoreCase(albumB);
                    }
                    return cmp;
                };
                break;
            case "sortBySize":
                comparator = (a, b) -> {
                    // Descending by total album size
                    int cmp = Long.compare(b.getSize(), a.getSize());
                    if (cmp == 0) {
                        String albumA = a.getAlbum() != null ? a.getAlbum() : "";
                        String albumB = b.getAlbum() != null ? b.getAlbum() : "";
                        cmp = albumA.compareToIgnoreCase(albumB);
                    }
                    return cmp;
                };
                break;
            case "sortByTitle":
            default:
                comparator = (a, b) -> {
                    String albumA = a.getAlbum() != null ? a.getAlbum() : "";
                    String albumB = b.getAlbum() != null ? b.getAlbum() : "";
                    return albumA.compareToIgnoreCase(albumB);
                };
                break;
        }
        Collections.sort(list, comparator);
    }
    
    /**
     * Parse year string to integer. Returns 0 for invalid/missing years.
     * Handles formats like "2025", "2025-01-01", etc.
     */
    private int parseYear(String yearStr) {
        if (yearStr == null || yearStr.isEmpty()) {
            return 0;
        }
        try {
            // Extract first 4 digits if present
            String cleaned = yearStr.replaceAll("[^0-9]", "");
            if (cleaned.length() >= 4) {
                return Integer.parseInt(cleaned.substring(0, 4));
            } else if (!cleaned.isEmpty()) {
                return Integer.parseInt(cleaned);
            }
        } catch (NumberFormatException e) {
            // Ignore parse errors
        }
        return 0;
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
        // Determine which key to use based on current tab: tab 1 = Albums, tab 2 = Songs
        int currentTab = viewPager != null ? viewPager.getCurrentItem() : 2;
        String sortKey = (currentTab == 1) ? KEY_ALBUMS_SORTING : KEY_SONGS_SORTING;
        
        if (itemId == R.id.by_title) {
            editor.putString(sortKey, "sortByTitle");
            editor.apply();
            this.recreate();
        } else if (itemId == R.id.by_date) {
            editor.putString(sortKey, "sortByDate");
            editor.apply();
            this.recreate();
        } else if (itemId == R.id.by_size) {
            editor.putString(sortKey, "sortBySize");
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
        
        // Only show mini player if we have both display metadata AND a valid queue
        // This prevents showing a song that cannot be played
        boolean hasQueue = false;
        if (path != null) {
            // Check if queue exists in database (quick check, not full load)
            QueueDatabase queueDb = QueueDatabase.getInstance(this);
            hasQueue = queueDb.hasQueue();
        }
        
        if (path != null && hasQueue) {
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