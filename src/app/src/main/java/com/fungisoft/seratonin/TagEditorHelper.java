package com.fungisoft.seratonin;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import java.io.File;

/**
 * Helper class for reading and writing audio file tags.
 * Uses MediaMetadataRetriever for reading and JAudioTagger for writing.
 */
public class TagEditorHelper {
    
    private static final String TAG = "TagEditorHelper";
    
    /**
     * Container class for audio file metadata tags.
     */
    public static class AudioTags {
        public String songTitle;
        public String albumTitle;
        public String year;
        public String albumArtist;
        public String songArtist;
        
        public AudioTags() {
            songTitle = "";
            albumTitle = "";
            year = "";
            albumArtist = "";
            songArtist = "";
        }
    }
    
    /**
     * Read tags from an audio file using MediaMetadataRetriever.
     * 
     * @param filePath The absolute path to the audio file
     * @return AudioTags object containing the read metadata
     */
    public static AudioTags readTags(String filePath) {
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
