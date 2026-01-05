package com.fungisoft.seratonin;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Helper class for reading and writing audio file tags.
 * Uses MediaMetadataRetriever for reading and JAudioTagger for writing.
 * 
 * IMPORTANT: MediaMetadataRetriever.setDataSource() can hang indefinitely on
 * corrupt or malformed files. This class implements timeout protection to prevent
 * blocking the calling thread.
 */
public class TagEditorHelper {
    
    private static final String TAG = "TagEditorHelper";
    
    // Timeout for MediaMetadataRetriever operations (in milliseconds)
    // 5 seconds should be more than enough for any valid file
    private static final long METADATA_TIMEOUT_MS = 5000;
    
    // Thread pool for timeout-protected metadata operations
    // Using a small pool (2 threads) to handle cases where one thread is blocked
    // while still limiting resource usage
    private static final ExecutorService metadataExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "MetadataReader");
        t.setDaemon(true);  // Don't prevent JVM shutdown
        return t;
    });
    
    /**
     * Container class for audio file metadata tags.
     */
    public static class AudioTags {
        public String songTitle;
        public String albumTitle;
        public String year;
        public String albumArtist;
        public String songArtist;
        public boolean timedOut;  // Flag to indicate if reading timed out
        public boolean hadError;  // Flag to indicate if an error occurred
        
        public AudioTags() {
            songTitle = "";
            albumTitle = "";
            year = "";
            albumArtist = "";
            songArtist = "";
            timedOut = false;
            hadError = false;
        }
    }
    
    /**
     * Read tags from an audio file using MediaMetadataRetriever with timeout protection.
     * 
     * This method wraps the native MediaMetadataRetriever operations with a timeout
     * to prevent indefinite hangs on corrupt or malformed files.
     * 
     * @param filePath The absolute path to the audio file
     * @return AudioTags object containing the read metadata (may have empty fields on timeout/error)
     */
    public static AudioTags readTags(String filePath) {
        AudioTags tags = new AudioTags();
        
        if (filePath == null || filePath.isEmpty()) {
            return tags;
        }
        
        // Use timeout-protected reading to prevent hanging on corrupt files
        return readTagsWithTimeout(filePath, METADATA_TIMEOUT_MS);
    }
    
    /**
     * Read tags with a configurable timeout.
     * 
     * @param filePath The absolute path to the audio file
     * @param timeoutMs Timeout in milliseconds
     * @return AudioTags object containing the read metadata
     */
    public static AudioTags readTagsWithTimeout(String filePath, long timeoutMs) {
        AudioTags tags = new AudioTags();
        
        if (filePath == null || filePath.isEmpty()) {
            return tags;
        }
        
        // Reference to retriever for cleanup in case of timeout
        final AtomicReference<MediaMetadataRetriever> retrieverRef = new AtomicReference<>(null);
        
        Callable<AudioTags> task = () -> {
            AudioTags result = new AudioTags();
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retrieverRef.set(retriever);
            
            try {
                retriever.setDataSource(filePath);
                
                String title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                String album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                String year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR);
                String albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
                String artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                
                result.songTitle = title != null ? title : "";
                result.albumTitle = album != null ? album : "";
                result.year = year != null ? year : "";
                result.albumArtist = albumArtist != null ? albumArtist : "";
                result.songArtist = artist != null ? artist : "";
                
            } finally {
                try {
                    retriever.release();
                } catch (Exception e) {
                    // Ignore release exception
                }
                retrieverRef.set(null);
            }
            
            return result;
        };
        
        Future<AudioTags> future = null;
        try {
            future = metadataExecutor.submit(task);
            tags = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            Log.w(TAG, "Timeout reading tags from file (skipping): " + filePath);
            tags.timedOut = true;
            tags.hadError = true;
            
            // Cancel the task to prevent accumulation of blocked threads
            if (future != null) {
                future.cancel(true);  // Attempt to interrupt the thread
            }
            
            // Try to release the retriever if it's still held
            MediaMetadataRetriever retriever = retrieverRef.get();
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception ex) {
                    // Ignore - the thread may still be using it
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading tags from file: " + filePath, e);
            tags.hadError = true;
        }
        
        return tags;
    }
    
    /**
     * Read tags directly without timeout protection.
     * Use this only when you're certain the file is valid and accessible.
     * 
     * @param filePath The absolute path to the audio file
     * @return AudioTags object containing the read metadata
     */
    public static AudioTags readTagsDirect(String filePath) {
        AudioTags tags = new AudioTags();
        
        if (filePath == null || filePath.isEmpty()) {
            return tags;
        }
        
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(filePath);
            
            String title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            String year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR);
            String albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
            String artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            
            tags.songTitle = title != null ? title : "";
            tags.albumTitle = album != null ? album : "";
            tags.year = year != null ? year : "";
            tags.albumArtist = albumArtist != null ? albumArtist : "";
            tags.songArtist = artist != null ? artist : "";
            
        } catch (Exception e) {
            Log.e(TAG, "Error reading tags from file: " + filePath, e);
            tags.hadError = true;
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaMetadataRetriever", e);
            }
        }
        
        return tags;
    }
    
    /**
     * Write tags to an audio file using JAudioTagger library.
     * 
     * @param filePath The absolute path to the audio file
     * @param tags The AudioTags object containing the metadata to write
     * @return true if successful, false otherwise
     */
    public static boolean writeTags(String filePath, AudioTags tags) {
        if (filePath == null || filePath.isEmpty() || tags == null) {
            return false;
        }
        
        try {
            File file = new File(filePath);
            if (!file.exists() || !file.canWrite()) {
                Log.e(TAG, "File does not exist or is not writable: " + filePath);
                return false;
            }
            
            AudioFile audioFile = AudioFileIO.read(file);
            Tag tag = audioFile.getTagOrCreateAndSetDefault();
            
            // Set all the tag fields
            if (tags.songTitle != null && !tags.songTitle.isEmpty()) {
                tag.setField(FieldKey.TITLE, tags.songTitle);
            }
            if (tags.albumTitle != null && !tags.albumTitle.isEmpty()) {
                tag.setField(FieldKey.ALBUM, tags.albumTitle);
            }
            if (tags.year != null && !tags.year.isEmpty()) {
                tag.setField(FieldKey.YEAR, tags.year);
            }
            if (tags.albumArtist != null && !tags.albumArtist.isEmpty()) {
                tag.setField(FieldKey.ALBUM_ARTIST, tags.albumArtist);
            }
            if (tags.songArtist != null && !tags.songArtist.isEmpty()) {
                tag.setField(FieldKey.ARTIST, tags.songArtist);
            }
            
            // Write the changes back to the file
            audioFile.commit();
            
            Log.d(TAG, "Successfully wrote tags to: " + filePath);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Error writing tags to file: " + filePath, e);
            return false;
        }
    }
    
    /**
     * Check if a file is a supported audio format for tag editing.
     * 
     * @param filePath The absolute path to the audio file
     * @return true if the file format is supported
     */
    public static boolean isSupportedFormat(String filePath) {
        if (filePath == null) return false;
        
        String lowerPath = filePath.toLowerCase();
        return lowerPath.endsWith(".mp3") ||
               lowerPath.endsWith(".flac") ||
               lowerPath.endsWith(".ogg") ||
               lowerPath.endsWith(".m4a") ||
               lowerPath.endsWith(".wav") ||
               lowerPath.endsWith(".aiff") ||
               lowerPath.endsWith(".wma");
    }
}
