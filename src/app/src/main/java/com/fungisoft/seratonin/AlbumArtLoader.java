package com.fungisoft.seratonin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asynchronous album art loader that avoids blocking the main thread.
 * Uses an LRU memory cache and background thread pool for loading.
 * 
 * This class is the PRIMARY fix for UI stuttering during album art loading.
 * All RecyclerView adapters should use this instead of calling AlbumArtHelper directly.
 */
public class AlbumArtLoader {
    
    private static final String TAG = "AlbumArtLoader";
    
    // Singleton instance
    private static volatile AlbumArtLoader instance;
    
    // Memory cache for decoded art (stores folder path -> art bytes)
    // Using 1/8th of available memory for cache
    private final LruCache<String, byte[]> memoryCache;
    
    // Track which folders are currently being loaded to avoid duplicate work
    private final ConcurrentHashMap<String, Future<?>> pendingLoads;
    
    // Background executor for art loading - using fixed pool for controlled concurrency
    private final ExecutorService executor;
    
    // Handler for posting to main thread
    private final Handler mainHandler;
    
    // Thumbnail size for grid views (reduces memory and decoding time)
    private static final int THUMBNAIL_SIZE = 300;
    
    // Maximum art file size to cache in memory (2MB)
    private static final int MAX_CACHEABLE_SIZE = 2 * 1024 * 1024;
    
    private AlbumArtLoader() {
        // Calculate cache size: 1/8 of available memory, max 32MB
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = Math.min(maxMemory / 8, 32 * 1024); // in KB
        
        memoryCache = new LruCache<String, byte[]>(cacheSize) {
            @Override
            protected int sizeOf(String key, byte[] value) {
                // Return size in KB
                return value.length / 1024;
            }
        };
        
        pendingLoads = new ConcurrentHashMap<>();
        
        // Use 2 threads - enough for smooth scrolling without overloading
        executor = Executors.newFixedThreadPool(2);
        
        mainHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * Get singleton instance.
     */
    public static AlbumArtLoader getInstance() {
        if (instance == null) {
            synchronized (AlbumArtLoader.class) {
                if (instance == null) {
                    instance = new AlbumArtLoader();
                }
            }
        }
        return instance;
    }
    
    /**
     * Load album art for a song/album into an ImageView asynchronously.
     * This is the main entry point for adapters.
     * 
     * @param context Application context
     * @param musicFilePath Path to the music file
     * @param imageView Target ImageView (weak referenced to avoid leaks)
     * @param placeholderResId Resource ID for placeholder image
     * @param forAlbum True for album-level art (external priority), false for song art (embedded priority)
     */
    public void loadAlbumArt(Context context, String musicFilePath, ImageView imageView, 
                             int placeholderResId, boolean forAlbum) {
        if (musicFilePath == null || imageView == null || context == null) {
            return;
        }
        
        // Get folder path for album-level caching
        File musicFile = new File(musicFilePath);
        File parentDir = musicFile.getParentFile();
        String folderPath = parentDir != null ? parentDir.getAbsolutePath() : musicFilePath;
        
        // Generate context-aware cache key:
        // - For songs: unique per track (to support individual embedded art)
        // - For albums: shared per folder (for consistent album covers)
        String cacheKey;
        if (forAlbum) {
            cacheKey = "album:" + folderPath;
        } else {
            cacheKey = "song:" + musicFilePath;
        }
        
        // Tag the view with current request to handle recycling
        imageView.setTag(R.id.album_art_tag, cacheKey);
        
        // Check memory cache first (instant, no threading needed)
        byte[] cachedArt = memoryCache.get(cacheKey);
        if (cachedArt != null) {
            loadIntoImageView(context, imageView, cachedArt, cacheKey, placeholderResId);
            return;
        }
        
        // Show placeholder immediately (with safety check)
        try {
            if (isValidContext(context)) {
                Glide.with(context)
                        .load(placeholderResId)
                        .into(imageView);
            }
        } catch (IllegalArgumentException e) {
            // Context destroyed - ignore
        }
        
        // Load in background
        loadAsync(context, musicFilePath, cacheKey, imageView, placeholderResId, forAlbum);
    }
    
    /**
     * Check if context is valid for Glide operations.
     */
    private boolean isValidContext(Context context) {
        if (context == null) return false;
        if (context instanceof android.app.Activity) {
            android.app.Activity activity = (android.app.Activity) context;
            return !activity.isFinishing() && !activity.isDestroyed();
        }
        return true;
    }
    
    /**
     * Load album art for grid view (album-centric, external art priority).
     */
    public void loadAlbumArtForGrid(Context context, String musicFilePath, ImageView imageView, 
                                     int placeholderResId) {
        loadAlbumArt(context, musicFilePath, imageView, placeholderResId, true);
    }
    
    /**
     * Load album art for song view (song-centric, embedded art priority).
     */
    public void loadAlbumArtForSong(Context context, String musicFilePath, ImageView imageView, 
                                     int placeholderResId) {
        loadAlbumArt(context, musicFilePath, imageView, placeholderResId, false);
    }
    
    /**
     * Perform async loading of album art.
     * 
     * @param context Application context
     * @param musicFilePath Path to the music file
     * @param cacheKey Context-aware cache key (song:path or album:folder)
     * @param imageView Target ImageView
     * @param placeholderResId Placeholder resource ID
     * @param forAlbum True for album-level art, false for song art
     */
    private void loadAsync(Context context, String musicFilePath, String cacheKey, 
                           ImageView imageView, int placeholderResId, boolean forAlbum) {
        
        // Use weak reference to avoid holding Activity/View in memory
        WeakReference<ImageView> imageViewRef = new WeakReference<>(imageView);
        WeakReference<Context> contextRef = new WeakReference<>(context);
        
        // Cancel any pending load for this cache key if view was recycled
        Future<?> existingTask = pendingLoads.get(cacheKey);
        if (existingTask != null && !existingTask.isDone()) {
            // Don't cancel - let it complete for caching benefit
        }
        
        Future<?> future = executor.submit(() -> {
            try {
                // Load art using AlbumArtHelper (now running off main thread!)
                byte[] artData;
                Context ctx = contextRef.get();
                if (ctx == null) return;
                
                if (forAlbum) {
                    artData = AlbumArtHelper.getAlbumArtForAlbum(ctx, musicFilePath);
                } else {
                    artData = AlbumArtHelper.getAlbumArtForSong(ctx, musicFilePath);
                }
                
                // Cache in memory if size is reasonable
                if (artData != null && artData.length <= MAX_CACHEABLE_SIZE) {
                    memoryCache.put(cacheKey, artData);
                }
                
                // Post to main thread
                final byte[] finalArt = artData;
                mainHandler.post(() -> {
                    ImageView iv = imageViewRef.get();
                    Context c = contextRef.get();
                    if (iv == null || c == null) return;
                    
                    // Check if view is still meant for this art (handles recycling)
                    Object tag = iv.getTag(R.id.album_art_tag);
                    if (!cacheKey.equals(tag)) {
                        return; // View was recycled, don't update
                    }
                    
                    loadIntoImageView(c, iv, finalArt, cacheKey, placeholderResId);
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Error loading album art for: " + musicFilePath, e);
            } finally {
                pendingLoads.remove(cacheKey);
            }
        });
        
        pendingLoads.put(cacheKey, future);
    }
    
    /**
     * Load art bytes into ImageView using Glide.
     * 
     * @param context Application context
     * @param imageView Target ImageView
     * @param artData Image bytes to load (or null for placeholder)
     * @param cacheKey Cache key (unused in this method but kept for potential logging)
     * @param placeholderResId Placeholder resource ID
     */
    private void loadIntoImageView(Context context, ImageView imageView, byte[] artData, 
                                    String cacheKey, int placeholderResId) {
        // Safety check - avoid Glide crashes on destroyed activities
        if (!isValidContext(context)) {
            return;
        }
        
        try {
            if (artData != null) {
                Glide.with(context)
                        .asBitmap()
                        .load(artData)
                        .apply(new RequestOptions()
                                .override(THUMBNAIL_SIZE, THUMBNAIL_SIZE)
                                .diskCacheStrategy(DiskCacheStrategy.NONE) // We have our own cache
                                .placeholder(placeholderResId))
                        .into(imageView);
            } else {
                Glide.with(context)
                        .load(placeholderResId)
                        .into(imageView);
            }
        } catch (IllegalArgumentException e) {
            // Context is destroyed - ignore
            Log.w(TAG, "Glide load skipped - context destroyed");
        }
    }
    
    /**
     * Get song art synchronously from cache only (for cases where we need immediate result).
     * Returns null if not in cache - caller must handle gracefully.
     * 
     * This uses song-level cache key (embedded-first priority).
     */
    public byte[] getFromCacheOnly(String musicFilePath) {
        if (musicFilePath == null) return null;
        
        // Use song-level cache key
        String cacheKey = "song:" + musicFilePath;
        return memoryCache.get(cacheKey);
    }
    
    /**
     * Get album art synchronously from cache only.
     * Uses album-level cache key (folder-based, external-first priority).
     */
    public byte[] getAlbumArtFromCacheOnly(String musicFilePath) {
        if (musicFilePath == null) return null;
        
        File musicFile = new File(musicFilePath);
        File parentDir = musicFile.getParentFile();
        if (parentDir == null) return null;
        
        String cacheKey = "album:" + parentDir.getAbsolutePath();
        return memoryCache.get(cacheKey);
    }
    
    /**
     * Pre-warm the cache for a list of music files (call from background thread).
     * This pre-loads album-level art for grid views.
     */
    public void preWarmCache(Context context, java.util.List<MusicFiles> musicFiles) {
        if (musicFiles == null || context == null) return;
        
        java.util.Set<String> processedFolders = new java.util.HashSet<>();
        
        for (MusicFiles music : musicFiles) {
            String path = music.getPath();
            if (path == null) continue;
            
            File musicFile = new File(path);
            File parentDir = musicFile.getParentFile();
            if (parentDir == null) continue;
            
            String folderPath = parentDir.getAbsolutePath();
            String albumCacheKey = "album:" + folderPath;
            
            // Skip if already processed or in cache
            if (processedFolders.contains(folderPath)) continue;
            if (memoryCache.get(albumCacheKey) != null) {
                processedFolders.add(folderPath);
                continue;
            }
            
            processedFolders.add(folderPath);
            
            // Load into cache (album-level)
            byte[] art = AlbumArtHelper.getAlbumArtForAlbum(context, path);
            if (art != null && art.length <= MAX_CACHEABLE_SIZE) {
                memoryCache.put(albumCacheKey, art);
            }
        }
        
        Log.d(TAG, "Pre-warmed cache for " + processedFolders.size() + " folders");
    }
    
    /**
     * Clear the memory cache.
     */
    public void clearCache() {
        memoryCache.evictAll();
        pendingLoads.clear();
    }
    
    /**
     * Invalidate cache for a specific folder (both song and album level entries).
     */
    public void invalidateFolder(String folderPath) {
        if (folderPath == null) return;
        
        // Invalidate album-level cache
        String albumCacheKey = "album:" + folderPath;
        memoryCache.remove(albumCacheKey);
        
        // Note: Song-level cache entries are keyed by full song path,
        // so we can't efficiently clear them without iterating.
        // They will be naturally invalidated by file modification time checks.
    }
    
    /**
     * Invalidate cache for a specific song file.
     */
    public void invalidateSong(String songPath) {
        if (songPath == null) return;
        
        String songCacheKey = "song:" + songPath;
        memoryCache.remove(songCacheKey);
        
        // Also invalidate the album-level cache for consistency
        File songFile = new File(songPath);
        File parentDir = songFile.getParentFile();
        if (parentDir != null) {
            invalidateFolder(parentDir.getAbsolutePath());
        }
    }
    
    /**
     * Shutdown the loader (call when app is destroyed).
     */
    public void shutdown() {
        executor.shutdownNow();
        clearCache();
    }
}
