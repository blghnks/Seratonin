package com.fungisoft.seratonin;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for TagEditorHelper.
 * 
 * Note: These tests cannot fully test the timeout mechanism since that requires
 * an actual MediaMetadataRetriever which is only available on Android.
 * However, we can test the AudioTags structure and basic logic.
 */
public class TagEditorHelperTest {

    @Test
    public void testAudioTagsDefaultValues() {
        TagEditorHelper.AudioTags tags = new TagEditorHelper.AudioTags();
        
        // Verify default values
        assertEquals("", tags.songTitle);
        assertEquals("", tags.albumTitle);
        assertEquals("", tags.year);
        assertEquals("", tags.albumArtist);
        assertEquals("", tags.songArtist);
        assertFalse(tags.timedOut);
        assertFalse(tags.hadError);
    }

    @Test
    public void testAudioTagsErrorFlags() {
        TagEditorHelper.AudioTags tags = new TagEditorHelper.AudioTags();
        
        // Test that error flags can be set
        tags.timedOut = true;
        tags.hadError = true;
        
        assertTrue(tags.timedOut);
        assertTrue(tags.hadError);
    }

    @Test
    public void testReadTagsWithNullPath() {
        // Should return empty tags, not throw exception
        TagEditorHelper.AudioTags tags = TagEditorHelper.readTags(null);
        
        assertNotNull(tags);
        assertEquals("", tags.songTitle);
        assertEquals("", tags.albumArtist);
        assertFalse(tags.timedOut);
        assertFalse(tags.hadError);
    }

    @Test
    public void testReadTagsWithEmptyPath() {
        // Should return empty tags, not throw exception
        TagEditorHelper.AudioTags tags = TagEditorHelper.readTags("");
        
        assertNotNull(tags);
        assertEquals("", tags.songTitle);
        assertEquals("", tags.albumArtist);
        assertFalse(tags.timedOut);
        assertFalse(tags.hadError);
    }

    @Test
    public void testReadTagsWithTimeoutNullPath() {
        // Test timeout version with null path
        TagEditorHelper.AudioTags tags = TagEditorHelper.readTagsWithTimeout(null, 1000);
        
        assertNotNull(tags);
        assertEquals("", tags.songTitle);
        assertFalse(tags.timedOut);
        assertFalse(tags.hadError);
    }

    @Test
    public void testReadTagsWithTimeoutEmptyPath() {
        // Test timeout version with empty path
        TagEditorHelper.AudioTags tags = TagEditorHelper.readTagsWithTimeout("", 1000);
        
        assertNotNull(tags);
        assertEquals("", tags.songTitle);
        assertFalse(tags.timedOut);
        assertFalse(tags.hadError);
    }

    @Test
    public void testIsSupportedFormatMp3() {
        assertTrue(TagEditorHelper.isSupportedFormat("/path/to/file.mp3"));
        assertTrue(TagEditorHelper.isSupportedFormat("/path/to/file.MP3"));
    }

    @Test
    public void testIsSupportedFormatFlac() {
        assertTrue(TagEditorHelper.isSupportedFormat("/path/to/file.flac"));
        assertTrue(TagEditorHelper.isSupportedFormat("/path/to/file.FLAC"));
    }

    @Test
    public void testIsSupportedFormatOgg() {
        assertTrue(TagEditorHelper.isSupportedFormat("/path/to/file.ogg"));
        assertTrue(TagEditorHelper.isSupportedFormat("/path/to/file.OGG"));
    }

    @Test
    public void testIsSupportedFormatM4a() {
        assertTrue(TagEditorHelper.isSupportedFormat("/path/to/file.m4a"));
        assertTrue(TagEditorHelper.isSupportedFormat("/path/to/file.M4A"));
    }

    @Test
    public void testIsSupportedFormatWav() {
        assertTrue(TagEditorHelper.isSupportedFormat("/path/to/file.wav"));
        assertTrue(TagEditorHelper.isSupportedFormat("/path/to/file.WAV"));
    }

    @Test
    public void testIsSupportedFormatNull() {
        assertFalse(TagEditorHelper.isSupportedFormat(null));
    }

    @Test
    public void testIsSupportedFormatUnsupported() {
        assertFalse(TagEditorHelper.isSupportedFormat("/path/to/file.txt"));
        assertFalse(TagEditorHelper.isSupportedFormat("/path/to/file.pdf"));
        assertFalse(TagEditorHelper.isSupportedFormat("/path/to/file.jpg"));
    }
}
