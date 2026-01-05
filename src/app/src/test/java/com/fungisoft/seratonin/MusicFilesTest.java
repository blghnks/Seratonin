package com.fungisoft.seratonin;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for MusicFiles model class.
 */
public class MusicFilesTest {

    @Test
    public void constructor_setsAllFields() {
        MusicFiles music = new MusicFiles(
                "/path/to/song.mp3",
                "Test Song",
                "Test Artist",
                "Test Album",
                "180000",
                "123"
        );

        assertEquals("/path/to/song.mp3", music.getPath());
        assertEquals("Test Song", music.getTitle());
        assertEquals("Test Artist", music.getArtist());
        assertEquals("Test Album", music.getAlbum());
        assertEquals("180000", music.getDuration());
        assertEquals("123", music.getId());
    }

    @Test
    public void setPath_updatesPath() {
        MusicFiles music = new MusicFiles("/old/path.mp3", "Title", "Artist", "Album", "1000", "1");
        music.setPath("/new/path.mp3");
        assertEquals("/new/path.mp3", music.getPath());
    }

    @Test
    public void setArtist_updatesArtist() {
        MusicFiles music = new MusicFiles("/path.mp3", "Title", "Old Artist", "Album", "1000", "1");
        music.setArtist("New Artist");
        assertEquals("New Artist", music.getArtist());
    }

    @Test
    public void setId_updatesId() {
        MusicFiles music = new MusicFiles("/path.mp3", "Title", "Artist", "Album", "1000", "old_id");
        music.setId("new_id");
        assertEquals("new_id", music.getId());
    }

    @Test
    public void nullFields_areAllowed() {
        MusicFiles music = new MusicFiles(null, null, null, null, null, null);
        
        assertNull(music.getPath());
        assertNull(music.getTitle());
        assertNull(music.getArtist());
        assertNull(music.getAlbum());
        assertNull(music.getDuration());
        assertNull(music.getId());
    }
}
