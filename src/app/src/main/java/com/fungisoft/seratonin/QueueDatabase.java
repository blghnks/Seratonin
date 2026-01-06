package com.fungisoft.seratonin;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;

/**
 * SQLite database helper for storing the player queue persistently.
 * The queue survives app restarts and can be exported to M3U files.
 */
public class QueueDatabase extends SQLiteOpenHelper {

    private static final String TAG = "QueueDatabase";
    private static final String DATABASE_NAME = "queue.db";
    private static final int DATABASE_VERSION = 1;

    // Singleton instance
    private static QueueDatabase instance;
    
    // Context for file operations
    private Context appContext;

    // Table name
    public static final String TABLE_QUEUE = "queue";

    // Queue table columns
    public static final String COLUMN_ID = "_id";
    public static final String COLUMN_POSITION = "position";
    public static final String COLUMN_PATH = "path";
    public static final String COLUMN_TITLE = "title";
    public static final String COLUMN_ARTIST = "artist";
    public static final String COLUMN_ALBUM = "album";
    public static final String COLUMN_DURATION = "duration";
    public static final String COLUMN_SONG_ID = "song_id";

    // Current playback position storage
    public static final String TABLE_PLAYBACK_STATE = "playback_state";
    public static final String COLUMN_CURRENT_INDEX = "current_index";
    public static final String COLUMN_CURRENT_POSITION_MS = "current_position_ms";

    // Create queue table SQL
    private static final String CREATE_QUEUE_TABLE =
            "CREATE TABLE " + TABLE_QUEUE + " (" +
                    COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_POSITION + " INTEGER NOT NULL, " +
                    COLUMN_PATH + " TEXT NOT NULL, " +
                    COLUMN_TITLE + " TEXT, " +
                    COLUMN_ARTIST + " TEXT, " +
                    COLUMN_ALBUM + " TEXT, " +
                    COLUMN_DURATION + " TEXT, " +
                    COLUMN_SONG_ID + " TEXT" +
                    ")";

    // Create playback state table SQL
    private static final String CREATE_PLAYBACK_STATE_TABLE =
            "CREATE TABLE " + TABLE_PLAYBACK_STATE + " (" +
                    COLUMN_ID + " INTEGER PRIMARY KEY, " +
                    COLUMN_CURRENT_INDEX + " INTEGER DEFAULT 0, " +
                    COLUMN_CURRENT_POSITION_MS + " INTEGER DEFAULT 0" +
                    ")";

    // Index for faster lookups
    private static final String CREATE_POSITION_INDEX =
            "CREATE INDEX idx_queue_position ON " + TABLE_QUEUE + "(" + COLUMN_POSITION + ")";

    private QueueDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.appContext = context.getApplicationContext();
    }

    /**
     * Get singleton instance of QueueDatabase.
     */
    public static synchronized QueueDatabase getInstance(Context context) {
        if (instance == null) {
            instance = new QueueDatabase(context.getApplicationContext());
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_QUEUE_TABLE);
        db.execSQL(CREATE_PLAYBACK_STATE_TABLE);
        db.execSQL(CREATE_POSITION_INDEX);
        
        // Initialize playback state row
        ContentValues values = new ContentValues();
        values.put(COLUMN_ID, 1);
        values.put(COLUMN_CURRENT_INDEX, 0);
        values.put(COLUMN_CURRENT_POSITION_MS, 0);
        db.insert(TABLE_PLAYBACK_STATE, null, values);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_QUEUE);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_PLAYBACK_STATE);
        onCreate(db);
    }

    /**
     * Save the current queue to database.
     * This replaces any existing queue.
     */
    public void saveQueue(ArrayList<MusicFiles> queue) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            // Clear existing queue
            db.delete(TABLE_QUEUE, null, null);
            
            // Insert new queue
            for (int i = 0; i < queue.size(); i++) {
                MusicFiles song = queue.get(i);
                ContentValues values = new ContentValues();
                values.put(COLUMN_POSITION, i);
                values.put(COLUMN_PATH, song.getPath());
                values.put(COLUMN_TITLE, song.getTitle());
                values.put(COLUMN_ARTIST, song.getArtist());
                values.put(COLUMN_ALBUM, song.getAlbum());
                values.put(COLUMN_DURATION, song.getDuration());
                values.put(COLUMN_SONG_ID, song.getId());
                db.insert(TABLE_QUEUE, null, values);
            }
            
            db.setTransactionSuccessful();
            Log.d(TAG, "Saved queue with " + queue.size() + " songs");
        } catch (Exception e) {
            Log.e(TAG, "Error saving queue", e);
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Load the queue from database.
     */
    public ArrayList<MusicFiles> loadQueue() {
        ArrayList<MusicFiles> queue = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        
        Cursor cursor = null;
        try {
            cursor = db.query(
                    TABLE_QUEUE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    COLUMN_POSITION + " ASC"
            );
            
            while (cursor.moveToNext()) {
                String path = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PATH));
                String title = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TITLE));
                String artist = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ARTIST));
                String album = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ALBUM));
                String duration = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DURATION));
                String songId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SONG_ID));
                
                // Create MusicFiles object
                MusicFiles song = new MusicFiles(path, title, artist, album, duration, songId);
                queue.add(song);
            }
            
            Log.d(TAG, "Loaded queue with " + queue.size() + " songs");
        } catch (Exception e) {
            Log.e(TAG, "Error loading queue", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        
        return queue;
    }

    /**
     * Save current playback index.
     */
    public void saveCurrentIndex(int index) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_CURRENT_INDEX, index);
        db.update(TABLE_PLAYBACK_STATE, values, COLUMN_ID + " = 1", null);
    }

    /**
     * Get current playback index.
     */
    public int getCurrentIndex() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_PLAYBACK_STATE, new String[]{COLUMN_CURRENT_INDEX},
                    COLUMN_ID + " = 1", null, null, null, null);
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting current index", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return 0;
    }

    /**
     * Save current playback position in milliseconds.
     */
    public void savePlaybackPosition(int positionMs) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_CURRENT_POSITION_MS, positionMs);
        db.update(TABLE_PLAYBACK_STATE, values, COLUMN_ID + " = 1", null);
    }

    /**
     * Get current playback position in milliseconds.
     */
    public int getPlaybackPosition() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_PLAYBACK_STATE, new String[]{COLUMN_CURRENT_POSITION_MS},
                    COLUMN_ID + " = 1", null, null, null, null);
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting playback position", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return 0;
    }

    /**
     * Clear the queue.
     */
    public void clearQueue() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_QUEUE, null, null);
        saveCurrentIndex(0);
        savePlaybackPosition(0);
        Log.d(TAG, "Queue cleared");
    }

    /**
     * Remove a song from the queue at specified position.
     */
    public void removeFromQueue(int position) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            // Delete the song at position
            db.delete(TABLE_QUEUE, COLUMN_POSITION + " = ?", new String[]{String.valueOf(position)});
            
            // Update positions of subsequent songs
            db.execSQL("UPDATE " + TABLE_QUEUE + " SET " + COLUMN_POSITION + " = " + COLUMN_POSITION + " - 1 " +
                    "WHERE " + COLUMN_POSITION + " > ?", new Object[]{position});
            
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Export the queue to an M3U file.
     * @param outputPath The path to save the M3U file
     * @return true if export was successful
     */
    public boolean exportToM3U(String outputPath) {
        ArrayList<MusicFiles> queue = loadQueue();
        if (queue.isEmpty()) {
            Log.w(TAG, "Queue is empty, nothing to export");
            return false;
        }
        
        File outputFile = new File(outputPath);
        BufferedWriter writer = null;
        
        try {
            writer = new BufferedWriter(new FileWriter(outputFile));
            
            // Write M3U header
            writer.write("#EXTM3U");
            writer.newLine();
            
            // Write each song
            for (MusicFiles song : queue) {
                // Write extended info line
                int durationSeconds = 0;
                try {
                    if (song.getDuration() != null && !song.getDuration().isEmpty()) {
                        // Duration might be in milliseconds or formatted string
                        String duration = song.getDuration().replaceAll("[^0-9]", "");
                        if (!duration.isEmpty()) {
                            long durationMs = Long.parseLong(duration);
                            durationSeconds = (int) (durationMs / 1000);
                        }
                    }
                } catch (NumberFormatException e) {
                    durationSeconds = -1; // Unknown duration
                }
                
                String artist = song.getArtist() != null ? song.getArtist() : "Unknown Artist";
                String title = song.getTitle() != null ? song.getTitle() : "Unknown Title";
                
                writer.write("#EXTINF:" + durationSeconds + "," + artist + " - " + title);
                writer.newLine();
                
                // Write file path
                writer.write(song.getPath());
                writer.newLine();
            }
            
            writer.flush();
            Log.d(TAG, "Exported queue to: " + outputPath);
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "Error exporting to M3U", e);
            return false;
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing writer", e);
                }
            }
        }
    }

    /**
     * Export queue to M3U with auto-generated filename.
     * @param musicFolderPath The music folder path to save the M3U file
     * @return The path of the exported file, or null if export failed
     */
    public String exportToM3UWithTimestamp(String musicFolderPath) {
        if (musicFolderPath == null) {
            Log.e(TAG, "Music folder path is null");
            return null;
        }
        
        // Generate filename with timestamp
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        String filename = "Seratonin_Queue_" + timestamp + ".m3u";
        String outputPath = musicFolderPath + File.separator + filename;
        
        if (exportToM3U(outputPath)) {
            return outputPath;
        }
        return null;
    }
    
    /**
     * Import M3U playlist from a file path and add songs to the queue.
     * @param m3uPath The path to the M3U file
     * @return The list of imported songs, or null if import failed
     */
    public ArrayList<MusicFiles> importFromM3U(String m3uPath) {
        if (m3uPath == null) {
            Log.e(TAG, "M3U path is null");
            return null;
        }
        
        File m3uFile = new File(m3uPath);
        if (!m3uFile.exists() || !m3uFile.canRead()) {
            Log.e(TAG, "M3U file does not exist or cannot be read: " + m3uPath);
            return null;
        }
        
        ArrayList<MusicFiles> importedSongs = new ArrayList<>();
        BufferedReader reader = null;
        
        try {
            reader = new BufferedReader(new FileReader(m3uFile));
            String line;
            String extInfo = null;
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                // Skip empty lines
                if (line.isEmpty()) continue;
                
                // Parse EXTINF line for metadata
                if (line.startsWith("#EXTINF:")) {
                    extInfo = line;
                    continue;
                }
                
                // Skip other comment lines
                if (line.startsWith("#")) continue;
                
                // This is a file path - resolve it
                String filePath = resolveM3UPath(line, m3uFile.getParent());
                if (filePath == null) continue;
                
                File audioFile = new File(filePath);
                if (!audioFile.exists()) {
                    Log.w(TAG, "Audio file not found: " + filePath);
                    continue;
                }
                
                // Create MusicFiles object from the file
                MusicFiles song = createMusicFileFromPath(filePath, extInfo);
                if (song != null) {
                    importedSongs.add(song);
                }
                
                extInfo = null; // Reset for next track
            }
            
            Log.d(TAG, "Imported " + importedSongs.size() + " songs from M3U: " + m3uPath);
            return importedSongs;
            
        } catch (IOException e) {
            Log.e(TAG, "Error reading M3U file", e);
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing reader", e);
                }
            }
        }
    }
    
    /**
     * Import M3U playlist from a content URI (for SAF/picker).
     * @param uri The content URI of the M3U file
     * @param context Context for content resolver
     * @param baseDir Optional base directory for resolving relative paths
     * @return The list of imported songs, or null if import failed
     */
    public ArrayList<MusicFiles> importFromM3UUri(Uri uri, Context context, String baseDir) {
        if (uri == null) {
            Log.e(TAG, "URI is null");
            return null;
        }
        
        ArrayList<MusicFiles> importedSongs = new ArrayList<>();
        BufferedReader reader = null;
        
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                Log.e(TAG, "Could not open input stream for URI: " + uri);
                return null;
            }
            
            reader = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            String extInfo = null;
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                // Skip empty lines
                if (line.isEmpty()) continue;
                
                // Parse EXTINF line for metadata
                if (line.startsWith("#EXTINF:")) {
                    extInfo = line;
                    continue;
                }
                
                // Skip other comment lines
                if (line.startsWith("#")) continue;
                
                // This is a file path - resolve it
                String filePath = resolveM3UPath(line, baseDir);
                if (filePath == null) continue;
                
                File audioFile = new File(filePath);
                if (!audioFile.exists()) {
                    Log.w(TAG, "Audio file not found: " + filePath);
                    continue;
                }
                
                // Create MusicFiles object from the file
                MusicFiles song = createMusicFileFromPath(filePath, extInfo);
                if (song != null) {
                    importedSongs.add(song);
                }
                
                extInfo = null; // Reset for next track
            }
            
            Log.d(TAG, "Imported " + importedSongs.size() + " songs from M3U URI");
            return importedSongs;
            
        } catch (IOException e) {
            Log.e(TAG, "Error reading M3U from URI", e);
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing reader", e);
                }
            }
        }
    }
    
    /**
     * Resolve a path from M3U file (can be relative or absolute).
     */
    private String resolveM3UPath(String path, String baseDir) {
        if (path == null || path.isEmpty()) return null;
        
        // Handle absolute paths
        if (path.startsWith("/")) {
            return path;
        }
        
        // Handle file:// URIs
        if (path.startsWith("file://")) {
            return path.substring(7);
        }
        
        // Relative path - resolve against base directory
        if (baseDir != null) {
            File resolved = new File(baseDir, path);
            try {
                return resolved.getCanonicalPath();
            } catch (IOException e) {
                return resolved.getAbsolutePath();
            }
        }
        
        return path;
    }
    
    /**
     * Create a MusicFiles object from a file path, reading metadata if available.
     */
    private MusicFiles createMusicFileFromPath(String filePath, String extInfo) {
        String title = null;
        String artist = null;
        String album = null;
        String duration = null;
        
        // Try to parse EXTINF if available
        if (extInfo != null && extInfo.startsWith("#EXTINF:")) {
            try {
                // Format: #EXTINF:duration,Artist - Title
                String info = extInfo.substring(8);
                int commaPos = info.indexOf(',');
                if (commaPos > 0) {
                    String durationStr = info.substring(0, commaPos);
                    try {
                        int seconds = Integer.parseInt(durationStr.trim());
                        duration = String.valueOf(seconds * 1000); // Convert to ms
                    } catch (NumberFormatException ignored) {}
                    
                    String artistTitle = info.substring(commaPos + 1).trim();
                    int dashPos = artistTitle.indexOf(" - ");
                    if (dashPos > 0) {
                        artist = artistTitle.substring(0, dashPos).trim();
                        title = artistTitle.substring(dashPos + 3).trim();
                    } else {
                        title = artistTitle;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse EXTINF: " + extInfo);
            }
        }
        
        // Read metadata from file if not available from EXTINF
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(filePath);
            
            if (title == null || title.isEmpty()) {
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            }
            if (artist == null || artist.isEmpty()) {
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            }
            if (album == null || album.isEmpty()) {
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            }
            if (duration == null || duration.isEmpty()) {
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read metadata from: " + filePath);
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception ignored) {}
            }
        }
        
        // Use filename as title fallback
        if (title == null || title.isEmpty()) {
            File file = new File(filePath);
            title = file.getName();
            int dotPos = title.lastIndexOf('.');
            if (dotPos > 0) {
                title = title.substring(0, dotPos);
            }
        }
        
        // Default values
        if (artist == null) artist = "Unknown Artist";
        if (album == null) album = "Unknown Album";
        if (duration == null) duration = "0";
        
        // Generate a simple ID from the path
        String id = String.valueOf(filePath.hashCode());
        
        return new MusicFiles(filePath, title, artist, album, duration, id);
    }
}
