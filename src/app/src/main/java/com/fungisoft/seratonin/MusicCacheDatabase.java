package com.fungisoft.seratonin;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite database helper for caching music metadata and album art.
 * This improves app launch performance by avoiding repeated metadata extraction.
 * 
 * Album art is stored as files on disk (not in database) to avoid SQLiteBlobTooBigException.
 */
public class MusicCacheDatabase extends SQLiteOpenHelper {

    private static final String TAG = "MusicCacheDatabase";
    private static final String DATABASE_NAME = "music_cache.db";
    private static final int DATABASE_VERSION = 3; // Incremented for song vs album art separation

    // Singleton instance
    private static MusicCacheDatabase instance;
    
    // Context for file operations
    private Context appContext;
    
    // Album art cache directory name
    private static final String ART_CACHE_DIR = "album_art_cache";
    
    // Maximum disk cache size in bytes (100 MB)
    // This prevents unbounded cache growth and triggers LRU eviction
    private static final long MAX_CACHE_SIZE_BYTES = 100 * 1024 * 1024;
    
    // Eviction target: when cache exceeds max, evict down to this size (80 MB)
    // This provides hysteresis to avoid frequent eviction cycles
    private static final long EVICTION_TARGET_SIZE_BYTES = 80 * 1024 * 1024;
    
    // Maximum dimension (width/height) for cached album art (pixels)
    // This reduces storage usage by ~10x while preserving visual quality
    // 512px is sufficient for all display sizes in the app (PlayerActivity cover art ~500px max)
    private static final int MAX_ART_DIMENSION = 512;
    
    // JPEG quality for cached art (0-100)
    // 85 provides good quality/size tradeoff
    private static final int JPEG_QUALITY = 85;

    // Table names
    public static final String TABLE_SONGS = "songs";
    public static final String TABLE_ALBUM_ART = "album_art";

    // Songs table columns
    public static final String COLUMN_ID = "_id";
    public static final String COLUMN_PATH = "path";
    public static final String COLUMN_TITLE = "title";
    public static final String COLUMN_ARTIST = "artist";
    public static final String COLUMN_ALBUM = "album";
    public static final String COLUMN_ALBUM_ARTIST = "album_artist";
    public static final String COLUMN_YEAR = "year";
    public static final String COLUMN_DURATION = "duration";
    public static final String COLUMN_FILE_SIZE = "file_size";
    public static final String COLUMN_LAST_MODIFIED = "last_modified";

    // Album art table columns (now stores file path instead of BLOB)
    public static final String COLUMN_ART_ID = "_id";
    public static final String COLUMN_FOLDER_PATH = "folder_path";
    public static final String COLUMN_ART_FILE_PATH = "art_file_path"; // Path to cached art file
    public static final String COLUMN_ART_SOURCE = "art_source"; // "embedded" or "external"
    public static final String COLUMN_SONG_PATH = "song_path"; // Reference song for embedded art
    public static final String COLUMN_ART_LAST_MODIFIED = "last_modified";
    public static final String COLUMN_CACHE_KEY = "cache_key"; // Unique key: "song:path" or "album:folder"
    public static final String COLUMN_SOURCE_FILE_MODIFIED = "source_file_modified"; // Last modified time of source file

    // Create songs table SQL
    private static final String CREATE_SONGS_TABLE =
            "CREATE TABLE " + TABLE_SONGS + " (" +
                    COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_PATH + " TEXT UNIQUE NOT NULL, " +
                    COLUMN_TITLE + " TEXT, " +
                    COLUMN_ARTIST + " TEXT, " +
                    COLUMN_ALBUM + " TEXT, " +
                    COLUMN_ALBUM_ARTIST + " TEXT, " +
                    COLUMN_YEAR + " TEXT, " +
                    COLUMN_DURATION + " TEXT, " +
                    COLUMN_FILE_SIZE + " INTEGER, " +
                    COLUMN_LAST_MODIFIED + " INTEGER" +
                    ")";

    // Create album art table SQL (stores file path instead of BLOB)
    // COLUMN_CACHE_KEY is now the unique key: "song:/path/to/file.mp3" or "album:/path/to/folder"
    private static final String CREATE_ALBUM_ART_TABLE =
            "CREATE TABLE " + TABLE_ALBUM_ART + " (" +
                    COLUMN_ART_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_CACHE_KEY + " TEXT UNIQUE NOT NULL, " +
                    COLUMN_FOLDER_PATH + " TEXT, " +
                    COLUMN_ART_FILE_PATH + " TEXT, " +
                    COLUMN_ART_SOURCE + " TEXT, " +
                    COLUMN_SONG_PATH + " TEXT, " +
                    COLUMN_SOURCE_FILE_MODIFIED + " INTEGER, " +
                    COLUMN_ART_LAST_MODIFIED + " INTEGER" +
                    ")";

    // Index for faster lookups
    private static final String CREATE_PATH_INDEX =
            "CREATE INDEX idx_songs_path ON " + TABLE_SONGS + "(" + COLUMN_PATH + ")";
    private static final String CREATE_ALBUM_INDEX =
            "CREATE INDEX idx_songs_album ON " + TABLE_SONGS + "(" + COLUMN_ALBUM + ")";
    private static final String CREATE_CACHE_KEY_INDEX =
            "CREATE INDEX idx_album_art_cache_key ON " + TABLE_ALBUM_ART + "(" + COLUMN_CACHE_KEY + ")";
    private static final String CREATE_FOLDER_INDEX =
            "CREATE INDEX idx_album_art_folder ON " + TABLE_ALBUM_ART + "(" + COLUMN_FOLDER_PATH + ")";
    
    // Cache key prefixes for distinguishing song vs album art
    public static final String CACHE_KEY_SONG_PREFIX = "song:";
    public static final String CACHE_KEY_ALBUM_PREFIX = "album:";

    private MusicCacheDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.appContext = context.getApplicationContext();
    }

    /**
     * Get singleton instance of the database helper.
     */
    public static synchronized MusicCacheDatabase getInstance(Context context) {
        if (instance == null) {
            instance = new MusicCacheDatabase(context.getApplicationContext());
            // Enable WAL mode for better concurrent read/write performance
            // This must be done before any other database operations
            SQLiteDatabase db = instance.getWritableDatabase();
            db.enableWriteAheadLogging();
            
            // Set PRAGMA options for performance using rawQuery (required on Android)
            // These return result sets, so we must use rawQuery and close the cursor
            Cursor cursor;
            cursor = db.rawQuery("PRAGMA cache_size = 10000", null);
            cursor.close();
            cursor = db.rawQuery("PRAGMA synchronous = NORMAL", null);
            cursor.close();
        }
        return instance;
    }
    
    /**
     * Configure database for optimal performance.
     * Called when database is opened.
     */
    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        // WAL mode and PRAGMAs are set in getInstance() to avoid issues with onOpen
    }
    
    /**
     * Get the album art cache directory, creating it if necessary.
     */
    private File getArtCacheDir() {
        File cacheDir = new File(appContext.getCacheDir(), ART_CACHE_DIR);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        return cacheDir;
    }
    
    /**
     * Generate a unique filename for cached album art based on folder path.
     */
    private String generateArtFilename(String folderPath) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(folderPath.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString() + ".jpg";
        } catch (Exception e) {
            // Fallback to simple hash
            return "art_" + Math.abs(folderPath.hashCode()) + ".jpg";
        }
    }
    
    /**
     * Downscale album art to reduce storage usage while preserving visual quality.
     * Images larger than MAX_ART_DIMENSION in either dimension are resized proportionally.
     * Smaller images are not upscaled.
     * 
     * This reduces typical storage per album from ~2-5MB to ~50-150KB (10-30x reduction).
     * 
     * @param artData Original art data bytes
     * @return Downscaled JPEG bytes, or original if downscaling fails or is unnecessary
     */
    private byte[] downscaleArtIfNeeded(byte[] artData) {
        if (artData == null || artData.length == 0) {
            return artData;
        }
        
        try {
            // Decode to get dimensions without loading full bitmap into memory
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(artData, 0, artData.length, options);
            
            int width = options.outWidth;
            int height = options.outHeight;
            
            // Check if downscaling is needed
            if (width <= MAX_ART_DIMENSION && height <= MAX_ART_DIMENSION) {
                // Image is already small enough - return original
                return artData;
            }
            
            // Calculate scale factor to fit within MAX_ART_DIMENSION
            int inSampleSize = 1;
            if (height > MAX_ART_DIMENSION || width > MAX_ART_DIMENSION) {
                final int halfHeight = height / 2;
                final int halfWidth = width / 2;
                
                // Calculate the largest inSampleSize value that is a power of 2 and keeps both
                // height and width larger than the requested dimension
                while ((halfHeight / inSampleSize) >= MAX_ART_DIMENSION
                        && (halfWidth / inSampleSize) >= MAX_ART_DIMENSION) {
                    inSampleSize *= 2;
                }
            }
            
            // Decode with inSampleSize for efficient memory usage
            options.inSampleSize = inSampleSize;
            options.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeByteArray(artData, 0, artData.length, options);
            
            if (bitmap == null) {
                // Decoding failed - return original
                return artData;
            }
            
            // Calculate final dimensions maintaining aspect ratio
            int newWidth = bitmap.getWidth();
            int newHeight = bitmap.getHeight();
            
            if (newWidth > MAX_ART_DIMENSION || newHeight > MAX_ART_DIMENSION) {
                float scaleFactor = Math.min(
                        (float) MAX_ART_DIMENSION / newWidth,
                        (float) MAX_ART_DIMENSION / newHeight
                );
                newWidth = Math.round(newWidth * scaleFactor);
                newHeight = Math.round(newHeight * scaleFactor);
                
                // Create scaled bitmap
                Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
                if (bitmap != scaledBitmap) {
                    bitmap.recycle();
                }
                bitmap = scaledBitmap;
            }
            
            // Compress to JPEG
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
            bitmap.recycle();
            
            byte[] scaledData = baos.toByteArray();
            
            // Log reduction for debugging
            if (scaledData.length < artData.length) {
                int reduction = (int) (100.0 * (artData.length - scaledData.length) / artData.length);
                Log.d(TAG, "Downscaled art: " + width + "x" + height + " -> " + newWidth + "x" + newHeight + 
                           " (" + (artData.length / 1024) + "KB -> " + (scaledData.length / 1024) + "KB, " + reduction + "% reduction)");
            }
            
            return scaledData;
            
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Out of memory downscaling art, using original", e);
            return artData;
        } catch (Exception e) {
            Log.e(TAG, "Error downscaling art, using original", e);
            return artData;
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_SONGS_TABLE);
        db.execSQL(CREATE_ALBUM_ART_TABLE);
        db.execSQL(CREATE_PATH_INDEX);
        db.execSQL(CREATE_ALBUM_INDEX);
        db.execSQL(CREATE_CACHE_KEY_INDEX);
        db.execSQL(CREATE_FOLDER_INDEX);
        Log.d(TAG, "Database created successfully");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.d(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion);
        
        // Clear old album art cache files if they exist
        if (appContext != null) {
            File cacheDir = new File(appContext.getCacheDir(), ART_CACHE_DIR);
            if (cacheDir.exists()) {
                File[] files = cacheDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        file.delete();
                    }
                }
            }
        }
        
        // Recreate the tables with new schema
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_SONGS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_ALBUM_ART);
        onCreate(db);
        
        Log.d(TAG, "Database upgrade complete");
    }

    // ==================== Song Metadata Methods ====================

    /**
     * Cache song metadata from a MusicFiles object.
     */
    public void cacheSong(MusicFiles song, String albumArtist, String year) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_PATH, song.getPath());
        values.put(COLUMN_TITLE, song.getTitle());
        values.put(COLUMN_ARTIST, song.getArtist());
        values.put(COLUMN_ALBUM, song.getAlbum());
        values.put(COLUMN_ALBUM_ARTIST, albumArtist);
        values.put(COLUMN_YEAR, year);
        values.put(COLUMN_DURATION, song.getDuration());

        // Get file info for change detection
        File file = new File(song.getPath());
        if (file.exists()) {
            values.put(COLUMN_FILE_SIZE, file.length());
            values.put(COLUMN_LAST_MODIFIED, file.lastModified());
        }

        db.insertWithOnConflict(TABLE_SONGS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /**
     * Get cached song metadata by file path.
     */
    public CachedSongMetadata getCachedSong(String path) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        CachedSongMetadata metadata = null;
        try {
            cursor = db.query(TABLE_SONGS,
                    null,
                    COLUMN_PATH + " = ?",
                    new String[]{path},
                    null, null, null);

            if (cursor != null && cursor.moveToFirst()) {
                metadata = cursorToMetadata(cursor);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return metadata;
    }

    /**
     * Check if a song file has been modified since it was cached.
     */
    public boolean isSongCacheValid(String path) {
        CachedSongMetadata cached = getCachedSong(path);
        if (cached == null) {
            return false;
        }

        File file = new File(path);
        if (!file.exists()) {
            return false;
        }

        // Check if file has been modified
        return file.lastModified() == cached.lastModified && file.length() == cached.fileSize;
    }

    /**
     * Get all cached songs.
     */
    public List<CachedSongMetadata> getAllCachedSongs() {
        List<CachedSongMetadata> songs = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_SONGS, null, null, null, null, null, null);

        if (cursor != null) {
            while (cursor.moveToNext()) {
                songs.add(cursorToMetadata(cursor));
            }
            cursor.close();
        }
        return songs;
    }

    /**
     * Get all cached song paths for quick lookup.
     */
    public Map<String, Long> getCachedSongPaths() {
        Map<String, Long> paths = new HashMap<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_SONGS,
                new String[]{COLUMN_PATH, COLUMN_LAST_MODIFIED},
                null, null, null, null, null);

        if (cursor != null) {
            while (cursor.moveToNext()) {
                String path = cursor.getString(0);
                long lastModified = cursor.getLong(1);
                paths.put(path, lastModified);
            }
            cursor.close();
        }
        return paths;
    }

    /**
     * Delete cached song by path.
     */
    public void deleteCachedSong(String path) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_SONGS, COLUMN_PATH + " = ?", new String[]{path});
    }

    /**
     * Delete all cached songs.
     */
    public void clearSongsCache() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_SONGS, null, null);
        Log.d(TAG, "Songs cache cleared");
    }

    private CachedSongMetadata cursorToMetadata(Cursor cursor) {
        CachedSongMetadata metadata = new CachedSongMetadata();
        metadata.id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID));
        metadata.path = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PATH));
        metadata.title = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TITLE));
        metadata.artist = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ARTIST));
        metadata.album = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ALBUM));
        metadata.albumArtist = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ALBUM_ARTIST));
        metadata.year = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_YEAR));
        metadata.duration = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DURATION));
        metadata.fileSize = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_FILE_SIZE));
        metadata.lastModified = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_LAST_MODIFIED));
        return metadata;
    }

    // ==================== Album Art Methods ====================

    /**
     * Generate a cache key for song-level art (unique per track).
     * @param songPath Path to the music file
     * @return Cache key in format "song:/path/to/file.mp3"
     */
    public static String getSongCacheKey(String songPath) {
        return CACHE_KEY_SONG_PREFIX + songPath;
    }
    
    /**
     * Generate a cache key for album-level art (one per folder).
     * @param folderPath Path to the folder
     * @return Cache key in format "album:/path/to/folder"
     */
    public static String getAlbumCacheKey(String folderPath) {
        return CACHE_KEY_ALBUM_PREFIX + folderPath;
    }

    /**
     * Cache album art with a specific cache key.
     * This allows separate caching for song-level vs album-level art.
     * 
     * Art is automatically downscaled to MAX_ART_DIMENSION to reduce storage usage.
     * 
     * @param cacheKey Unique cache key (use getSongCacheKey or getAlbumCacheKey)
     * @param artData The art image data
     * @param source "embedded" or "external"
     * @param songPath Reference song path (for embedded art tracking)
     * @param sourceFileModified Last modified time of the source file (for invalidation)
     */
    public void cacheAlbumArtWithKey(String cacheKey, String folderPath, byte[] artData, 
                                      String source, String songPath, long sourceFileModified) {
        if (artData == null || cacheKey == null) {
            return;
        }

        try {
            // Downscale art to reduce storage usage (typically 10-30x reduction)
            byte[] scaledArtData = downscaleArtIfNeeded(artData);
            
            // Save art data to file using cache key for unique filename
            String filename = generateArtFilename(cacheKey);
            File artFile = new File(getArtCacheDir(), filename);
            
            try (FileOutputStream fos = new FileOutputStream(artFile)) {
                fos.write(scaledArtData);
            }
            
            // Store file path in database
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(COLUMN_CACHE_KEY, cacheKey);
            values.put(COLUMN_FOLDER_PATH, folderPath);
            values.put(COLUMN_ART_FILE_PATH, artFile.getAbsolutePath());
            values.put(COLUMN_ART_SOURCE, source);
            values.put(COLUMN_SONG_PATH, songPath);
            values.put(COLUMN_SOURCE_FILE_MODIFIED, sourceFileModified);
            values.put(COLUMN_ART_LAST_MODIFIED, System.currentTimeMillis());

            db.insertWithOnConflict(TABLE_ALBUM_ART, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            
            // Check if cache exceeds size limit and perform LRU eviction if needed
            // This runs periodically (not every write) for efficiency
            if (needsEviction()) {
                performLruEviction();
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Error saving album art to file", e);
        }
    }

    /**
     * Get cached album art by cache key.
     * 
     * @param cacheKey Unique cache key (use getSongCacheKey or getAlbumCacheKey)
     * @param expectedSourceModified Expected last modified time of source file (0 to skip validation)
     * @return Art data bytes, or null if not cached or cache is stale
     */
    public byte[] getCachedAlbumArtByKey(String cacheKey, long expectedSourceModified) {
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor cursor = db.query(TABLE_ALBUM_ART,
                    new String[]{COLUMN_ART_FILE_PATH, COLUMN_SOURCE_FILE_MODIFIED},
                    COLUMN_CACHE_KEY + " = ?",
                    new String[]{cacheKey},
                    null, null, null);

            byte[] artData = null;
            if (cursor != null && cursor.moveToFirst()) {
                String artFilePath = cursor.getString(0);
                long cachedSourceModified = cursor.getLong(1);
                cursor.close();
                
                // Validate cache freshness if expectedSourceModified is provided
                if (expectedSourceModified > 0 && cachedSourceModified != expectedSourceModified) {
                    // Cache is stale - source file has changed
                    deleteCachedAlbumArtByKey(cacheKey);
                    return null;
                }
                
                if (artFilePath != null) {
                    artData = readArtFile(artFilePath);
                    
                    // Update last_modified timestamp for LRU tracking (access = refresh)
                    // This ensures recently-accessed entries survive eviction
                    if (artData != null) {
                        touchCacheEntry(cacheKey);
                    }
                }
            } else if (cursor != null) {
                cursor.close();
            }
            return artData;
        } catch (Exception e) {
            Log.e(TAG, "Error getting cached album art by key", e);
            return null;
        }
    }
    
    /**
     * Update the last_modified timestamp of a cache entry (for LRU tracking).
     * Called when an entry is accessed to mark it as "recently used".
     */
    private void touchCacheEntry(String cacheKey) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(COLUMN_ART_LAST_MODIFIED, System.currentTimeMillis());
            db.update(TABLE_ALBUM_ART, values, COLUMN_CACHE_KEY + " = ?", new String[]{cacheKey});
        } catch (Exception e) {
            // Silently ignore - this is a best-effort optimization
        }
    }
    
    /**
     * Check if album art is cached for a specific cache key.
     */
    public boolean hasCachedArtByKey(String cacheKey) {
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor cursor = db.query(TABLE_ALBUM_ART,
                    new String[]{COLUMN_ART_FILE_PATH},
                    COLUMN_CACHE_KEY + " = ?",
                    new String[]{cacheKey},
                    null, null, null);

            boolean exists = false;
            if (cursor != null && cursor.moveToFirst()) {
                String artFilePath = cursor.getString(0);
                if (artFilePath != null) {
                    exists = new File(artFilePath).exists();
                }
                cursor.close();
            } else if (cursor != null) {
                cursor.close();
            }
            return exists;
        } catch (Exception e) {
            Log.e(TAG, "Error checking album art cache by key", e);
            return false;
        }
    }
    
    /**
     * Delete cached album art by cache key.
     */
    public void deleteCachedAlbumArtByKey(String cacheKey) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            
            // First get the file path to delete the file
            Cursor cursor = db.query(TABLE_ALBUM_ART,
                    new String[]{COLUMN_ART_FILE_PATH},
                    COLUMN_CACHE_KEY + " = ?",
                    new String[]{cacheKey},
                    null, null, null);
            
            if (cursor != null && cursor.moveToFirst()) {
                String artFilePath = cursor.getString(0);
                cursor.close();
                
                // Delete the file
                if (artFilePath != null) {
                    File artFile = new File(artFilePath);
                    if (artFile.exists()) {
                        artFile.delete();
                    }
                }
            } else if (cursor != null) {
                cursor.close();
            }
            
            // Delete database entry
            db.delete(TABLE_ALBUM_ART, COLUMN_CACHE_KEY + " = ?", new String[]{cacheKey});
        } catch (Exception e) {
            Log.e(TAG, "Error deleting cached album art by key", e);
        }
    }
    
    /**
     * Read album art bytes from a cached file.
     */
    private byte[] readArtFile(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            return null;
        }
        
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int totalRead = 0;
            while (totalRead < data.length) {
                int bytesRead = fis.read(data, totalRead, data.length - totalRead);
                if (bytesRead == -1) break;
                totalRead += bytesRead;
            }
            return totalRead > 0 ? data : null;
        } catch (IOException e) {
            Log.e(TAG, "Error reading art file: " + filePath, e);
            return null;
        }
    }

    /**
     * Delete cached album art for a folder (both file and database entry).
     * This deletes ALL cached art entries for the folder (both song and album level).
     */
    public void deleteCachedAlbumArt(String folderPath) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            
            // Get all art file paths for this folder
            Cursor cursor = db.query(TABLE_ALBUM_ART,
                    new String[]{COLUMN_ART_FILE_PATH, COLUMN_CACHE_KEY},
                    COLUMN_FOLDER_PATH + " = ?",
                    new String[]{folderPath},
                    null, null, null);
            
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String artFilePath = cursor.getString(0);
                    // Delete the file
                    if (artFilePath != null) {
                        File artFile = new File(artFilePath);
                        if (artFile.exists()) {
                            artFile.delete();
                        }
                    }
                }
                cursor.close();
            }
            
            // Delete all database entries for this folder
            db.delete(TABLE_ALBUM_ART, COLUMN_FOLDER_PATH + " = ?", new String[]{folderPath});
        } catch (Exception e) {
            Log.e(TAG, "Error deleting cached album art for folder", e);
        }
    }

    /**
     * Clear all album art cache (files and database entries).
     */
    public void clearAlbumArtCache() {
        SQLiteDatabase db = getWritableDatabase();
        
        // Delete all cached art files
        File cacheDir = getArtCacheDir();
        if (cacheDir.exists()) {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        }
        
        // Clear database table
        db.delete(TABLE_ALBUM_ART, null, null);
        Log.d(TAG, "Album art cache cleared");
    }
    
    // ==================== Cache Size Management ====================
    
    /**
     * Get the current size of the album art cache directory in bytes.
     */
    public long getCacheSize() {
        File cacheDir = getArtCacheDir();
        if (!cacheDir.exists()) {
            return 0;
        }
        
        long totalSize = 0;
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    totalSize += file.length();
                }
            }
        }
        return totalSize;
    }
    
    /**
     * Check if the cache exceeds the maximum size and needs eviction.
     */
    public boolean needsEviction() {
        return getCacheSize() > MAX_CACHE_SIZE_BYTES;
    }
    
    /**
     * Perform LRU eviction to bring cache size under the target.
     * Evicts oldest entries (by last_modified) until size is below EVICTION_TARGET_SIZE_BYTES.
     * 
     * @return Number of entries evicted
     */
    public int performLruEviction() {
        long currentSize = getCacheSize();
        if (currentSize <= MAX_CACHE_SIZE_BYTES) {
            return 0; // No eviction needed
        }
        
        Log.d(TAG, "Cache size " + (currentSize / 1024 / 1024) + "MB exceeds limit, starting LRU eviction");
        
        int evictedCount = 0;
        long bytesToEvict = currentSize - EVICTION_TARGET_SIZE_BYTES;
        long bytesEvicted = 0;
        
        SQLiteDatabase db = getWritableDatabase();
        
        // Get entries ordered by last_modified (oldest first = LRU)
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_ALBUM_ART,
                    new String[]{COLUMN_CACHE_KEY, COLUMN_ART_FILE_PATH},
                    null, null, null, null,
                    COLUMN_ART_LAST_MODIFIED + " ASC"); // Oldest first
            
            while (cursor.moveToNext() && bytesEvicted < bytesToEvict) {
                String cacheKey = cursor.getString(0);
                String artFilePath = cursor.getString(1);
                
                // Delete the file and track bytes freed
                if (artFilePath != null) {
                    File artFile = new File(artFilePath);
                    if (artFile.exists()) {
                        long fileSize = artFile.length();
                        if (artFile.delete()) {
                            bytesEvicted += fileSize;
                        }
                    }
                }
                
                // Delete database entry
                db.delete(TABLE_ALBUM_ART, COLUMN_CACHE_KEY + " = ?", new String[]{cacheKey});
                evictedCount++;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during LRU eviction", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        
        Log.d(TAG, "LRU eviction complete: evicted " + evictedCount + " entries, freed " + 
                   (bytesEvicted / 1024) + "KB");
        return evictedCount;
    }
    
    /**
     * Cleanup orphan files that exist in cache directory but have no database reference.
     * This can happen if the app crashes during cache write or database operations fail.
     * 
     * @return Number of orphan files deleted
     */
    public int cleanupOrphanFiles() {
        File cacheDir = getArtCacheDir();
        if (!cacheDir.exists()) {
            return 0;
        }
        
        // Get all files in cache directory
        File[] files = cacheDir.listFiles();
        if (files == null || files.length == 0) {
            return 0;
        }
        
        // Get all file paths referenced in database
        java.util.Set<String> referencedPaths = new java.util.HashSet<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_ALBUM_ART,
                    new String[]{COLUMN_ART_FILE_PATH},
                    null, null, null, null, null);
            
            while (cursor.moveToNext()) {
                String path = cursor.getString(0);
                if (path != null) {
                    referencedPaths.add(path);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        
        // Delete files not referenced in database
        int deletedCount = 0;
        for (File file : files) {
            if (file.isFile() && !referencedPaths.contains(file.getAbsolutePath())) {
                if (file.delete()) {
                    deletedCount++;
                }
            }
        }
        
        if (deletedCount > 0) {
            Log.d(TAG, "Cleaned up " + deletedCount + " orphan cache files");
        }
        return deletedCount;
    }

    // ==================== Sync Methods ====================

    /**
     * Clear all caches (both songs and album art).
     */
    public void clearAllCaches() {
        clearSongsCache();
        clearAlbumArtCache();
        Log.d(TAG, "All caches cleared");
    }

    /**
     * Remove entries for songs that no longer exist.
     */
    public int cleanupDeletedSongs() {
        List<CachedSongMetadata> cachedSongs = getAllCachedSongs();
        int deletedCount = 0;

        for (CachedSongMetadata song : cachedSongs) {
            File file = new File(song.path);
            if (!file.exists()) {
                deleteCachedSong(song.path);
                deletedCount++;
            }
        }

        Log.d(TAG, "Cleaned up " + deletedCount + " deleted songs from cache");
        return deletedCount;
    }
    
    // ==================== Batch Operations for Performance ====================
    
    /**
     * Batch size for transactions - balances performance with responsiveness.
     * Smaller batches allow for better progress updates and prevent long-held locks.
     */
    private static final int BATCH_SIZE = 100;
    
    /**
     * Result class for batch caching operations.
     */
    public static class BatchCacheResult {
        public int totalProcessed;
        public int successCount;
        public int errorCount;
        public int timeoutCount;
        public List<String> failedFiles;
        
        public BatchCacheResult() {
            totalProcessed = 0;
            successCount = 0;
            errorCount = 0;
            timeoutCount = 0;
            failedFiles = new ArrayList<>();
        }
    }
    
    /**
     * Progress callback interface for batch caching operations.
     */
    public interface BatchProgressCallback {
        /**
         * Called periodically during batch caching to report progress.
         * @param processed Number of files processed so far
         * @param total Total number of files to process
         * @param currentFile Name of the current file being processed (may be null)
         */
        void onProgress(int processed, int total, String currentFile);
    }

    /**
     * Cache multiple songs with progress reporting and robust error handling.
     * 
     * Key improvements over original:
     * 1. Uses smaller batched transactions (BATCH_SIZE files each) to prevent long-held locks
     * 2. Reports progress during caching via callback
     * 3. Continues processing even if individual files fail
     * 4. Returns detailed results including error counts
     * 
     * @param songs List of songs to cache
     * @param tagReader Function to read tags for each song (can be null to skip extended tags)
     * @param progressCallback Optional callback for progress updates (can be null)
     * @return BatchCacheResult with statistics about the operation
     */
    public BatchCacheResult cacheSongsBatchWithProgress(List<MusicFiles> songs, TagReader tagReader, 
                                                         BatchProgressCallback progressCallback) {
        BatchCacheResult result = new BatchCacheResult();
        
        if (songs == null || songs.isEmpty()) {
            return result;
        }
        
        int total = songs.size();
        int processed = 0;
        
        SQLiteDatabase db = getWritableDatabase();
        
        // Process in batches to allow progress updates and prevent long-held locks
        for (int batchStart = 0; batchStart < total; batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, total);
            
            db.beginTransaction();
            try {
                for (int i = batchStart; i < batchEnd; i++) {
                    MusicFiles song = songs.get(i);
                    String path = song.getPath();
                    
                    // Report progress
                    if (progressCallback != null) {
                        String filename = path != null ? new File(path).getName() : null;
                        progressCallback.onProgress(processed, total, filename);
                    }
                    
                    try {
                        ContentValues values = new ContentValues();
                        values.put(COLUMN_PATH, path);
                        values.put(COLUMN_TITLE, song.getTitle());
                        values.put(COLUMN_ARTIST, song.getArtist());
                        values.put(COLUMN_ALBUM, song.getAlbum());
                        values.put(COLUMN_DURATION, song.getDuration());
                        
                        // Read extended tags if reader provided
                        if (tagReader != null && path != null) {
                            try {
                                String[] extendedTags = tagReader.readExtendedTags(path);
                                if (extendedTags != null && extendedTags.length >= 2) {
                                    values.put(COLUMN_ALBUM_ARTIST, extendedTags[0]);
                                    values.put(COLUMN_YEAR, extendedTags[1]);
                                }
                            } catch (Exception e) {
                                // Tag reading failed - continue with basic info
                                Log.w(TAG, "Failed to read extended tags for: " + path);
                                result.errorCount++;
                                result.failedFiles.add(path);
                            }
                        }
                        
                        // Get file info for change detection
                        if (path != null) {
                            File file = new File(path);
                            if (file.exists()) {
                                values.put(COLUMN_FILE_SIZE, file.length());
                                values.put(COLUMN_LAST_MODIFIED, file.lastModified());
                            }
                        }
                        
                        db.insertWithOnConflict(TABLE_SONGS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
                        result.successCount++;
                        
                    } catch (Exception e) {
                        Log.e(TAG, "Error caching song: " + path, e);
                        result.errorCount++;
                        if (path != null) {
                            result.failedFiles.add(path);
                        }
                        // Continue with next file - don't abort the whole batch
                    }
                    
                    processed++;
                    result.totalProcessed = processed;
                }
                
                db.setTransactionSuccessful();
                
            } catch (Exception e) {
                Log.e(TAG, "Error in batch transaction", e);
                // Transaction will be rolled back, but we'll try the next batch
            } finally {
                db.endTransaction();
            }
        }
        
        Log.d(TAG, "Batch cached " + result.successCount + "/" + total + " songs" +
                   (result.errorCount > 0 ? " (" + result.errorCount + " errors)" : ""));
        
        return result;
    }
    
    /**
     * Interface for reading extended tags during batch operations.
     */
    public interface TagReader {
        /**
         * Read extended tags for a song file.
         * @param path Path to the music file
         * @return String array: [albumArtist, year] or null
         */
        String[] readExtendedTags(String path);
    }
    
    /**
     * Get cached album art file path only (without reading file contents).
     * This is faster when you only need to check if art exists or get the path.
     * 
     * @param folderPath The folder path to look up
     * @return The path to the cached art file, or null if not cached
     */
    public String getCachedAlbumArtPath(String folderPath) {
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor cursor = db.query(TABLE_ALBUM_ART,
                    new String[]{COLUMN_ART_FILE_PATH},
                    COLUMN_FOLDER_PATH + " = ?",
                    new String[]{folderPath},
                    null, null, null);

            String artFilePath = null;
            if (cursor != null && cursor.moveToFirst()) {
                artFilePath = cursor.getString(0);
                cursor.close();
                
                // Verify file exists
                if (artFilePath != null && !new File(artFilePath).exists()) {
                    artFilePath = null;
                }
            } else if (cursor != null) {
                cursor.close();
            }
            return artFilePath;
        } catch (Exception e) {
            Log.e(TAG, "Error getting cached album art path", e);
            return null;
        }
    }

    /**
     * Model class for cached song metadata.
     */
    public static class CachedSongMetadata {
        public long id;
        public String path;
        public String title;
        public String artist;
        public String album;
        public String albumArtist;
        public String year;
        public String duration;
        public long fileSize;
        public long lastModified;
    }
}
