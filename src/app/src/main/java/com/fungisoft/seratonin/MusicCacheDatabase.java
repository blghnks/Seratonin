package com.fungisoft.seratonin;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

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
    private static final int DATABASE_VERSION = 2; // Incremented for schema change

    // Singleton instance
    private static MusicCacheDatabase instance;
    
    // Context for file operations
    private Context appContext;
    
    // Album art cache directory name
    private static final String ART_CACHE_DIR = "album_art_cache";

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
    private static final String CREATE_ALBUM_ART_TABLE =
            "CREATE TABLE " + TABLE_ALBUM_ART + " (" +
                    COLUMN_ART_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_FOLDER_PATH + " TEXT UNIQUE NOT NULL, " +
                    COLUMN_ART_FILE_PATH + " TEXT, " +
                    COLUMN_ART_SOURCE + " TEXT, " +
                    COLUMN_SONG_PATH + " TEXT, " +
                    COLUMN_ART_LAST_MODIFIED + " INTEGER" +
                    ")";

    // Index for faster lookups
    private static final String CREATE_PATH_INDEX =
            "CREATE INDEX idx_songs_path ON " + TABLE_SONGS + "(" + COLUMN_PATH + ")";
    private static final String CREATE_ALBUM_INDEX =
            "CREATE INDEX idx_songs_album ON " + TABLE_SONGS + "(" + COLUMN_ALBUM + ")";
    private static final String CREATE_FOLDER_INDEX =
            "CREATE INDEX idx_album_art_folder ON " + TABLE_ALBUM_ART + "(" + COLUMN_FOLDER_PATH + ")";

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
        }
        return instance;
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

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_SONGS_TABLE);
        db.execSQL(CREATE_ALBUM_ART_TABLE);
        db.execSQL(CREATE_PATH_INDEX);
        db.execSQL(CREATE_ALBUM_INDEX);
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
        Cursor cursor = db.query(TABLE_SONGS,
                null,
                COLUMN_PATH + " = ?",
                new String[]{path},
                null, null, null);

        CachedSongMetadata metadata = null;
        if (cursor != null && cursor.moveToFirst()) {
            metadata = cursorToMetadata(cursor);
            cursor.close();
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
     * Cache album art for a folder by saving to file and storing path in database.
     */
    public void cacheAlbumArt(String folderPath, byte[] artData, String source, String songPath) {
        if (artData == null || folderPath == null) {
            return;
        }

        try {
            // Save art data to file
            String filename = generateArtFilename(folderPath);
            File artFile = new File(getArtCacheDir(), filename);
            
            try (FileOutputStream fos = new FileOutputStream(artFile)) {
                fos.write(artData);
            }
            
            // Store file path in database
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(COLUMN_FOLDER_PATH, folderPath);
            values.put(COLUMN_ART_FILE_PATH, artFile.getAbsolutePath());
            values.put(COLUMN_ART_SOURCE, source);
            values.put(COLUMN_SONG_PATH, songPath);
            values.put(COLUMN_ART_LAST_MODIFIED, System.currentTimeMillis());

            db.insertWithOnConflict(TABLE_ALBUM_ART, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            
        } catch (IOException e) {
            Log.e(TAG, "Error saving album art to file", e);
        }
    }

    /**
     * Get cached album art for a folder by reading from file.
     */
    public byte[] getCachedAlbumArt(String folderPath) {
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor cursor = db.query(TABLE_ALBUM_ART,
                    new String[]{COLUMN_ART_FILE_PATH},
                    COLUMN_FOLDER_PATH + " = ?",
                    new String[]{folderPath},
                    null, null, null);

            byte[] artData = null;
            if (cursor != null && cursor.moveToFirst()) {
                String artFilePath = cursor.getString(0);
                cursor.close();
                
                if (artFilePath != null) {
                    artData = readArtFile(artFilePath);
                }
            } else if (cursor != null) {
                cursor.close();
            }
            return artData;
        } catch (Exception e) {
            // Handle case where old database schema exists or other errors
            Log.e(TAG, "Error getting cached album art, may need database upgrade", e);
            return null;
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
     * Check if album art is cached for a folder.
     */
    public boolean hasAlbumArtCache(String folderPath) {
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor cursor = db.query(TABLE_ALBUM_ART,
                    new String[]{COLUMN_ART_FILE_PATH},
                    COLUMN_FOLDER_PATH + " = ?",
                    new String[]{folderPath},
                    null, null, null);

            boolean exists = false;
            if (cursor != null && cursor.moveToFirst()) {
                String artFilePath = cursor.getString(0);
                // Also verify the file exists
                if (artFilePath != null) {
                    exists = new File(artFilePath).exists();
                }
                cursor.close();
            } else if (cursor != null) {
                cursor.close();
            }
            return exists;
        } catch (Exception e) {
            Log.e(TAG, "Error checking album art cache", e);
            return false;
        }
    }

    /**
     * Delete cached album art for a folder (both file and database entry).
     */
    public void deleteCachedAlbumArt(String folderPath) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            
            // First get the file path to delete the file
            Cursor cursor = db.query(TABLE_ALBUM_ART,
                    new String[]{COLUMN_ART_FILE_PATH},
                    COLUMN_FOLDER_PATH + " = ?",
                    new String[]{folderPath},
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
            db.delete(TABLE_ALBUM_ART, COLUMN_FOLDER_PATH + " = ?", new String[]{folderPath});
        } catch (Exception e) {
            Log.e(TAG, "Error deleting cached album art", e);
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
