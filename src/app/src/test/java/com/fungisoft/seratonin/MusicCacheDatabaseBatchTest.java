package com.fungisoft.seratonin;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for MusicCacheDatabase batch operations.
 * 
 * These tests verify the BatchCacheResult structure and progress callback interface.
 * Full integration tests require an Android environment with actual database operations.
 */
public class MusicCacheDatabaseBatchTest {

    @Test
    public void testBatchCacheResultDefaultValues() {
        MusicCacheDatabase.BatchCacheResult result = new MusicCacheDatabase.BatchCacheResult();
        
        assertEquals(0, result.totalProcessed);
        assertEquals(0, result.successCount);
        assertEquals(0, result.errorCount);
        assertEquals(0, result.timeoutCount);
        assertNotNull(result.failedFiles);
        assertTrue(result.failedFiles.isEmpty());
    }

    @Test
    public void testBatchCacheResultFailedFilesTracking() {
        MusicCacheDatabase.BatchCacheResult result = new MusicCacheDatabase.BatchCacheResult();
        
        // Simulate adding failed files
        result.failedFiles.add("/path/to/corrupt/file1.mp3");
        result.failedFiles.add("/path/to/corrupt/file2.flac");
        result.errorCount = 2;
        
        assertEquals(2, result.failedFiles.size());
        assertEquals(2, result.errorCount);
        assertTrue(result.failedFiles.contains("/path/to/corrupt/file1.mp3"));
        assertTrue(result.failedFiles.contains("/path/to/corrupt/file2.flac"));
    }

    @Test
    public void testBatchCacheResultProcessingCounts() {
        MusicCacheDatabase.BatchCacheResult result = new MusicCacheDatabase.BatchCacheResult();
        
        // Simulate processing
        result.totalProcessed = 100;
        result.successCount = 95;
        result.errorCount = 5;  // This includes timeout errors
        result.timeoutCount = 2; // A subset of errorCount that specifically timed out
        
        assertEquals(100, result.totalProcessed);
        assertEquals(95, result.successCount);
        assertEquals(5, result.errorCount);
        assertEquals(2, result.timeoutCount);
        
        // Verify that successCount + errorCount = totalProcessed
        assertEquals(result.totalProcessed, result.successCount + result.errorCount);
    }

    @Test
    public void testBatchProgressCallbackInterface() {
        // Verify the interface can be implemented
        final int[] callCount = {0};
        final int[] lastProcessed = {0};
        final int[] lastTotal = {0};
        final String[] lastFile = {null};
        
        MusicCacheDatabase.BatchProgressCallback callback = (processed, total, currentFile) -> {
            callCount[0]++;
            lastProcessed[0] = processed;
            lastTotal[0] = total;
            lastFile[0] = currentFile;
        };
        
        // Simulate progress calls
        callback.onProgress(0, 100, "file1.mp3");
        assertEquals(1, callCount[0]);
        assertEquals(0, lastProcessed[0]);
        assertEquals(100, lastTotal[0]);
        assertEquals("file1.mp3", lastFile[0]);
        
        callback.onProgress(50, 100, "file50.mp3");
        assertEquals(2, callCount[0]);
        assertEquals(50, lastProcessed[0]);
        assertEquals("file50.mp3", lastFile[0]);
        
        callback.onProgress(99, 100, null);
        assertEquals(3, callCount[0]);
        assertEquals(99, lastProcessed[0]);
        assertNull(lastFile[0]);
    }

    @Test
    public void testTagReaderInterface() {
        // Verify the TagReader interface can be implemented
        MusicCacheDatabase.TagReader reader = path -> {
            if (path == null) return null;
            return new String[]{"Album Artist", "2024"};
        };
        
        // Test with valid path
        String[] tags = reader.readExtendedTags("/path/to/file.mp3");
        assertNotNull(tags);
        assertEquals(2, tags.length);
        assertEquals("Album Artist", tags[0]);
        assertEquals("2024", tags[1]);
        
        // Test with null path
        String[] nullTags = reader.readExtendedTags(null);
        assertNull(nullTags);
    }
}
