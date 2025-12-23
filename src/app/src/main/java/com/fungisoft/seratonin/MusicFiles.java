package com.fungisoft.seratonin;

public class MusicFiles {
    private String path;
    private final String title;
    private String artist;
    private final String album;
    private final String duration;
    private String id;
//    private String genre;
//    private String trackNumber;
//    private String albumArtist;


    public MusicFiles(String path, String title, String artist, String album, String duration, String id) {
        this.path = path;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.duration = duration;
        this.id = id;
//        this.genre = genre;
//        this.trackNumber = trackNumber;
//        this.albumArtist = albumArtist;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getAlbum() {
        return album;
    }

    public String getDuration() {
        return duration;
    }


    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
}
