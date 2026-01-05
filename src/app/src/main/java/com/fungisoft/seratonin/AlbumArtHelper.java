package com.fungisoft.seratonin;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Centralized helper for album art retrieval.
 * Implements external artwork file priority before falling back to embedded metadata.
 * 
 * External artwork priority order:
 * 1. front.png, front.jpg, front.jpeg
 * 2. cover.png, cover.jpg, cover.jpeg
 * 3. folder.png, folder.jpg, folder.jpeg
 * 4. albumart.png, albumart.jpg, albumart.jpeg
 * 
 * On Android 13+ (API 33+), uses MediaStore to access images due to scoped storage.
 * On older versions, uses direct file system access.
 */
public class AlbumArtHelper {

    private static final String TAG = "AlbumArtHelper";

    /**
     * Priority list of base filenames (without extension) for external artwork.
     * Order matters - first match wins.
     */
    private static final String[] EXTERNAL_ART_BASE_NAMES = {
            "front", "cover", "folder", "albumart"
    };

    /**
     * Supported image extensions in priority order.
     */
    private static final String[] IMAGE_EXTENSIONS = {"png", "jpg", "jpeg"};

    /**
     * Set of valid artwork filenames (lowercase) for quick lookup.
     */
    private static final Set<String> VALID_ART_FILENAMES = new HashSet<>();
    static {
        for (String baseName : EXTERNAL_ART_BASE_NAMES) {
            for (String ext : IMAGE_EXTENSIONS) {
                VALID_ART_FILENAMES.add(baseName + "." + ext);
            }
        }
    }

    /**
     * Get album art for a single song/track with caching support.
     * Song case: checks cache first, then embedded artwork, then external folder art.
     * Use this for song lists, now playing, notifications, etc.
     * 
     * IMPORTANT: Song-level art uses embedded-first priority to show unique track art.
     * Cache key is song-specific to allow different art per track in the same folder.
     *
     * @param context Application context for MediaStore access
     * @param musicFilePath The absolute path to the music file
     * @return byte[] of the image data, or null if no artwork found
     */
    public static byte[] getAlbumArtForSong(Context context, String musicFilePath) {
        if (musicFilePath == null || musicFilePath.isEmpty()) {
            return null;
        }

        // Get folder path and file info for cache
        java.io.File musicFile = new java.io.File(musicFilePath);
        java.io.File parentDir = musicFile.getParentFile();
        String folderPath = parentDir != null ? parentDir.getAbsolutePath() : null;
        
        // Get file modification time for cache validation
        long fileModified = musicFile.exists() ? musicFile.lastModified() : 0;

        // Try song-level cache first (cache key is song-specific)
        MusicCacheDatabase cache = MusicCacheDatabase.getInstance(context);
        String songCacheKey = MusicCacheDatabase.getSongCacheKey(musicFilePath);
        byte[] cachedArt = cache.getCachedAlbumArtByKey(songCacheKey, fileModified);
        if (cachedArt != null) {
            return cachedArt;
        }

        // First, try embedded artwork (song-level priority)
        byte[] embeddedArt = getEmbeddedAlbumArt(musicFilePath);
        if (embeddedArt != null) {
            // Cache with song-specific key
            cache.cacheAlbumArtWithKey(songCacheKey, folderPath, embeddedArt, 
                    "embedded", musicFilePath, fileModified);
            return embeddedArt;
        }

        // Fallback to external artwork
        byte[] externalArt = getExternalAlbumArtUncached(context, musicFilePath);
        if (externalArt != null) {
            // Cache with song-specific key (even though source is external)
            cache.cacheAlbumArtWithKey(songCacheKey, folderPath, externalArt, 
                    "external", musicFilePath, fileModified);
        }
        return externalArt;
    }

    /**
     * Get album art for a single song/track without using cache.
     * Use this when you need fresh data or for cache population.
     *
     * @param context Application context for MediaStore access
     * @param musicFilePath The absolute path to the music file
     * @return byte[] of the image data, or null if no artwork found
     */
    public static byte[] getAlbumArtForSongUncached(Context context, String musicFilePath) {
        if (musicFilePath == null || musicFilePath.isEmpty()) {
            return null;
        }

        // First, try embedded artwork
        byte[] embeddedArt = getEmbeddedAlbumArt(musicFilePath);
        if (embeddedArt != null) {
            return embeddedArt;
        }

        // Fallback to external artwork
        return getExternalAlbumArtUncached(context, musicFilePath);
    }

    /**
     * Get album art for album-level displays (album header, album details) with caching.
     * Album case: checks cache first, then external artwork, then embedded.
     * Use this when displaying art for an entire album, not a single song.
     * 
     * IMPORTANT: Album-level art uses external-first priority for consistent album covers.
     * Cache key is folder-specific so all tracks in the same album share the same art.
     *
     * @param context Application context for MediaStore access
     * @param musicFilePath The absolute path to a music file in the album's folder
     * @return byte[] of the image data, or null if no artwork found
     */
    public static byte[] getAlbumArtForAlbum(Context context, String musicFilePath) {
        if (musicFilePath == null || musicFilePath.isEmpty()) {
            return null;
        }

        // Get folder path for cache lookup
        java.io.File musicFile = new java.io.File(musicFilePath);
        java.io.File parentDir = musicFile.getParentFile();
        String folderPath = parentDir != null ? parentDir.getAbsolutePath() : null;
        
        if (folderPath == null) {
            return null;
        }

        // Try album-level cache first (cache key is folder-specific)
        MusicCacheDatabase cache = MusicCacheDatabase.getInstance(context);
        String albumCacheKey = MusicCacheDatabase.getAlbumCacheKey(folderPath);
        
        // For album cache, use folder modification time for validation (0 = no validation)
        byte[] cachedArt = cache.getCachedAlbumArtByKey(albumCacheKey, 0);
        if (cachedArt != null) {
            return cachedArt;
        }

        // First, try external artwork (album-level priority)
        byte[] externalArt = getExternalAlbumArtUncached(context, musicFilePath);
        if (externalArt != null) {
            // Cache with album-specific key
            cache.cacheAlbumArtWithKey(albumCacheKey, folderPath, externalArt, 
                    "external", musicFilePath, 0);
            return externalArt;
        }

        // Fallback to embedded artwork
        byte[] embeddedArt = getEmbeddedAlbumArt(musicFilePath);
        if (embeddedArt != null) {
            // Cache with album-specific key
            cache.cacheAlbumArtWithKey(albumCacheKey, folderPath, embeddedArt, 
                    "embedded", musicFilePath, 0);
        }
        return embeddedArt;
    }

    /**
     * Get album art for album grid views with caching.
     * Optimized case: checks cache first, then if external artwork exists, returns it.
     * This is more efficient for grid views with many items.
     * 
     * Uses album-level (folder-specific) caching with external-first priority.
     *
     * @param context Application context for MediaStore access
     * @param musicFilePath The absolute path to a music file in the album's folder
     * @return AlbumArtResult containing the art data and whether it came from external source
     */
    public static AlbumArtResult getAlbumArtForGrid(Context context, String musicFilePath) {
        if (musicFilePath == null || musicFilePath.isEmpty()) {
            return new AlbumArtResult(null, false);
        }

        // Get folder path for cache lookup
        java.io.File musicFile = new java.io.File(musicFilePath);
        java.io.File parentDir = musicFile.getParentFile();
        String folderPath = parentDir != null ? parentDir.getAbsolutePath() : null;
        
        if (folderPath == null) {
            return new AlbumArtResult(null, false);
        }

        // Try album-level cache first
        MusicCacheDatabase cache = MusicCacheDatabase.getInstance(context);
        String albumCacheKey = MusicCacheDatabase.getAlbumCacheKey(folderPath);
        byte[] cachedArt = cache.getCachedAlbumArtByKey(albumCacheKey, 0);
        if (cachedArt != null) {
            return new AlbumArtResult(cachedArt, true);
        }

        // First, try external artwork
        byte[] externalArt = getExternalAlbumArtUncached(context, musicFilePath);
        if (externalArt != null) {
            // Cache with album-specific key
            cache.cacheAlbumArtWithKey(albumCacheKey, folderPath, externalArt, 
                    "external", musicFilePath, 0);
            // External found - skip embedded extraction entirely
            return new AlbumArtResult(externalArt, true);
        }

        // No external art - fall back to embedded
        byte[] embeddedArt = getEmbeddedAlbumArt(musicFilePath);
        if (embeddedArt != null) {
            // Cache with album-specific key
            cache.cacheAlbumArtWithKey(albumCacheKey, folderPath, embeddedArt, 
                    "embedded", musicFilePath, 0);
        }
        return new AlbumArtResult(embeddedArt, false);
    }

    /**
     * Search for external album art file in the same directory as the music file.
     * This is the cached version that checks the database first.
     * 
     * Note: Files like "albumart.jpg", "cover.jpg", "folder.jpg" are intentionally
     * excluded from MediaStore by Android (they're hidden from gallery). Therefore,
     * we prioritize direct file access which works with READ_MEDIA_IMAGES permission.
     *
     * @param context Application context for MediaStore access
     * @param musicFilePath The absolute path to the music file
     * @return byte[] of the external image data, or null if not found
     */
    public static byte[] getExternalAlbumArt(Context context, String musicFilePath) {
        if (musicFilePath == null || musicFilePath.isEmpty()) {
            return null;
        }

        // Get folder path for cache lookup
        java.io.File musicFile = new java.io.File(musicFilePath);
        java.io.File parentDir = musicFile.getParentFile();
        String folderPath = parentDir != null ? parentDir.getAbsolutePath() : null;

        // Try cache first
        if (folderPath != null) {
            MusicCacheDatabase cache = MusicCacheDatabase.getInstance(context);
            byte[] cachedArt = cache.getCachedAlbumArt(folderPath);
            if (cachedArt != null) {
                return cachedArt;
            }
        }

        // No cache - load from file system
        byte[] artData = getExternalAlbumArtUncached(context, musicFilePath);
        
        // Cache the result
        if (artData != null && folderPath != null) {
            MusicCacheDatabase cache = MusicCacheDatabase.getInstance(context);
            cache.cacheAlbumArt(folderPath, artData, "external", musicFilePath);
        }
        
        return artData;
    }

    /**
     * Search for external album art file without using cache.
     * Use this for cache population or when fresh data is needed.
     *
     * @param context Application context for MediaStore access
     * @param musicFilePath The absolute path to the music file
     * @return byte[] of the external image data, or null if not found
     */
    public static byte[] getExternalAlbumArtUncached(Context context, String musicFilePath) {
        if (musicFilePath == null || musicFilePath.isEmpty()) {
            return null;
        }

        // Try direct file access first - this works with MANAGE_EXTERNAL_STORAGE permission
        // This is the primary method because album art files are often excluded from MediaStore
        byte[] result = getExternalAlbumArtViaDirectRead(musicFilePath);
        if (result != null) {
            return result;
        }

        // Fallback: try file system access with exists() check (may work on some paths)
        result = getExternalAlbumArtViaFileSystem(musicFilePath);
        if (result != null) {
            return result;
        }

        // Last resort: try MediaStore (in case the files ARE indexed for some reason)
        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return getExternalAlbumArtViaMediaStore(context, musicFilePath);
        }

        return null;
    }

    /**
     * Try to load album art by directly attempting to read expected file paths.
     * This bypasses File.exists() checks which can fail on Android 13+ scoped storage.
     *
     * @param musicFilePath The absolute path to the music file
     * @return byte[] of the image data, or null if not found
     */
    private static byte[] getExternalAlbumArtViaDirectRead(String musicFilePath) {
        File musicFile = new File(musicFilePath);
        File parentDir = musicFile.getParentFile();
        if (parentDir == null) {
            return null;
        }

        // Try each expected filename in priority order by directly attempting to read
        for (String baseName : EXTERNAL_ART_BASE_NAMES) {
            for (String ext : IMAGE_EXTENSIONS) {
                String filename = baseName + "." + ext;
                byte[] data = tryDirectRead(parentDir, filename);
                if (data != null) {
                    return data;
                }
            }
        }

        return null;
    }

    /**
     * Try to directly read a file without checking exists() first.
     * Tries common case variations of the filename.
     *
     * @param parentDir The directory containing the art file
     * @param filename The expected filename (lowercase)
     * @return byte[] of the image data, or null if file doesn't exist or can't be read
     */
    private static byte[] tryDirectRead(File parentDir, String filename) {
        // Try common case variations
        String[] variations = {
                filename,                                           // albumart.jpg
                filename.toUpperCase(),                             // ALBUMART.JPG
                Character.toUpperCase(filename.charAt(0)) +         // Albumart.jpg
                        filename.substring(1)
        };

        for (String variant : variations) {
            File artFile = new File(parentDir, variant);
            // Don't check exists() - just try to read directly
            byte[] data = readFileToBytesDirectly(artFile);
            if (data != null) {
                Log.d(TAG, "Found external art: " + artFile.getName() + " (" + data.length + " bytes)");
                return data;
            }
        }
        return null;
    }

    /**
     * Read file to bytes without checking exists() first.
     * This works better on Android 13+ where exists() may return false for accessible files.
     *
     * @param file The file to read
     * @return byte[] of file contents, or null on any error
     */
    private static byte[] readFileToBytesDirectly(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            // Get file size - if we can open it, we can read it
            long length = file.length();
            if (length <= 0 || length > 50 * 1024 * 1024) { // Sanity check: max 50MB
                // Try reading without knowing size
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] data = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(data)) != -1) {
                    buffer.write(data, 0, bytesRead);
                    // Safety limit
                    if (buffer.size() > 50 * 1024 * 1024) {
                        return null;
                    }
                }
                return buffer.size() > 0 ? buffer.toByteArray() : null;
            }
            
            byte[] data = new byte[(int) length];
            int totalRead = 0;
            while (totalRead < data.length) {
                int bytesRead = fis.read(data, totalRead, data.length - totalRead);
                if (bytesRead == -1) break;
                totalRead += bytesRead;
            }
            return totalRead > 0 ? data : null;
        } catch (IOException e) {
            // File doesn't exist or can't be read - this is expected for most attempts
            return null;
        } catch (SecurityException e) {
            // No permission to read this file
            Log.w(TAG, "readFileToBytesDirectly: SecurityException for " + file.getName() + ": " + e.getMessage());
            return null;
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "readFileToBytesDirectly: OutOfMemoryError for " + file.getName());
            return null;
        }
    }

    /**
     * Find and load external album art using MediaStore.
     * This is a fallback method for when direct file access fails.
     * Uses multiple query strategies for maximum compatibility.
     *
     * @param context Application context
     * @param musicFilePath The absolute path to the music file
     * @return byte[] of the image data, or null if not found
     */
    private static byte[] getExternalAlbumArtViaMediaStore(Context context, String musicFilePath) {
        File musicFile = new File(musicFilePath);
        File parentDir = musicFile.getParentFile();
        if (parentDir == null) {
            return null;
        }

        String folderPath = parentDir.getAbsolutePath();
        String folderName = parentDir.getName();
        
        // Try to get relative path for precise matching
        String relativePath = null;
        String externalStorage = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        if (folderPath.startsWith(externalStorage)) {
            relativePath = folderPath.substring(externalStorage.length());
            if (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }
            if (!relativePath.endsWith("/")) {
                relativePath = relativePath + "/";
            }
        }

        // Strategy 1: Try RELATIVE_PATH (most precise)
        if (relativePath != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            byte[] result = queryMediaStoreByRelativePath(context, relativePath);
            if (result != null) {
                return result;
            }
        }

        // Strategy 2: Try BUCKET_DISPLAY_NAME (folder name)
        byte[] result = queryMediaStoreByBucketName(context, folderName);
        if (result != null) {
            return result;
        }

        // Strategy 3: Search by DISPLAY_NAME (artwork filenames) and verify path
        return queryMediaStoreByDisplayName(context, folderPath);
    }

    /**
     * Query MediaStore by RELATIVE_PATH.
     */
    private static byte[] queryMediaStoreByRelativePath(Context context, String relativePath) {
        ContentResolver resolver = context.getContentResolver();
        Uri imagesUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME
        };

        String selection = MediaStore.Images.Media.RELATIVE_PATH + " = ?";
        String[] selectionArgs = new String[]{ relativePath };

        try (Cursor cursor = resolver.query(imagesUri, projection, selection, selectionArgs, null)) {
            return findArtworkFromCursor(context, cursor, imagesUri);
        } catch (Exception e) {
            Log.e(TAG, "Error querying MediaStore by RELATIVE_PATH", e);
        }

        return null;
    }

    /**
     * Query MediaStore by BUCKET_DISPLAY_NAME.
     */
    private static byte[] queryMediaStoreByBucketName(Context context, String folderName) {
        ContentResolver resolver = context.getContentResolver();
        Uri imagesUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME
        };

        String selection = MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " = ?";
        String[] selectionArgs = new String[]{ folderName };

        try (Cursor cursor = resolver.query(imagesUri, projection, selection, selectionArgs, null)) {
            return findArtworkFromCursor(context, cursor, imagesUri);
        } catch (Exception e) {
            Log.e(TAG, "Error querying MediaStore by BUCKET_DISPLAY_NAME", e);
        }

        return null;
    }

    /**
     * Query MediaStore by exact DATA path.
     * This tries to find images at specific expected paths with case variations.
     */
    private static byte[] queryMediaStoreByExactPath(Context context, String folderPath) {
        ContentResolver resolver = context.getContentResolver();
        Uri imagesUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME
        };

        // Try each artwork filename in priority order with case variations
        for (String baseName : EXTERNAL_ART_BASE_NAMES) {
            for (String ext : IMAGE_EXTENSIONS) {
                // Generate case variations for each filename
                String lowercaseFilename = baseName + "." + ext;
                String uppercaseFilename = lowercaseFilename.toUpperCase();
                String capitalizedFilename = Character.toUpperCase(baseName.charAt(0)) + 
                        baseName.substring(1) + "." + ext;
                
                String[] variations = { lowercaseFilename, uppercaseFilename, capitalizedFilename };
                
                for (String filename : variations) {
                    String expectedPath = folderPath + "/" + filename;
                    
                    String selection = MediaStore.Images.Media.DATA + " = ?";
                    String[] selectionArgs = new String[]{ expectedPath };

                    try (Cursor cursor = resolver.query(imagesUri, projection, selection, selectionArgs, null)) {
                        if (cursor != null && cursor.moveToFirst()) {
                            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                            long id = cursor.getLong(idColumn);
                            Uri imageUri = ContentUris.withAppendedId(imagesUri, id);
                            Log.d(TAG, "queryMediaStoreByExactPath: Found " + filename + " via MediaStore");
                            byte[] result = loadImageFromUri(context, imageUri);
                            if (result != null) {
                                return result;
                            }
                        }
                    } catch (Exception e) {
                        // Continue to next filename
                    }
                }
            }
        }

        return null;
    }

    /**
     * Query MediaStore by DISPLAY_NAME (searching for artwork filenames directly).
     * This is a fallback when folder-based queries fail.
     */
    private static byte[] queryMediaStoreByDisplayName(Context context, String expectedFolderPath) {
        ContentResolver resolver = context.getContentResolver();
        Uri imagesUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        // Strategy 3a: Try querying by exact DATA path first (most reliable if indexed)
        byte[] result = queryMediaStoreByExactPath(context, expectedFolderPath);
        if (result != null) {
            return result;
        }

        // Strategy 3b: Search by DISPLAY_NAME and verify path
        // Build a query for any of our artwork filenames
        StringBuilder selectionBuilder = new StringBuilder();
        String[] artworkNames = new String[EXTERNAL_ART_BASE_NAMES.length * IMAGE_EXTENSIONS.length];
        int index = 0;
        
        for (String baseName : EXTERNAL_ART_BASE_NAMES) {
            for (String ext : IMAGE_EXTENSIONS) {
                if (index > 0) {
                    selectionBuilder.append(" OR ");
                }
                selectionBuilder.append(MediaStore.Images.Media.DISPLAY_NAME + " = ?");
                artworkNames[index++] = baseName + "." + ext;
            }
        }

        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATA  // Need DATA to verify path
        };

        try (Cursor cursor = resolver.query(imagesUri, projection, selectionBuilder.toString(), artworkNames, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }

            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int dataColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATA);

            // Collect images that are in our target folder
            Map<String, Long> imageFiles = new HashMap<>();
            do {
                String name = cursor.getString(nameColumn);
                long id = cursor.getLong(idColumn);
                
                // Verify the image is in the correct folder
                if (dataColumn >= 0) {
                    String imagePath = cursor.getString(dataColumn);
                    if (imagePath != null) {
                        File imageFile = new File(imagePath);
                        File imageParent = imageFile.getParentFile();
                        if (imageParent != null && imageParent.getAbsolutePath().equals(expectedFolderPath)) {
                            if (name != null) {
                                imageFiles.put(name.toLowerCase(), id);
                            }
                        }
                    }
                } else if (name != null) {
                    // No DATA column, just use the filename (less precise)
                    imageFiles.put(name.toLowerCase(), id);
                }
            } while (cursor.moveToNext());

            // Find best matching artwork in priority order
            Long imageId = findBestArtwork(imageFiles);
            if (imageId != null) {
                Uri imageUri = ContentUris.withAppendedId(imagesUri, imageId);
                return loadImageFromUri(context, imageUri);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying MediaStore by DISPLAY_NAME", e);
        }

        return null;
    }

    /**
     * Extract artwork from a cursor result.
     */
    private static byte[] findArtworkFromCursor(Context context, Cursor cursor, Uri imagesUri) {
        if (cursor == null || !cursor.moveToFirst()) {
            return null;
        }

        int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
        int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);

        // Collect all image files
        Map<String, Long> imageFiles = new HashMap<>();
        do {
            String name = cursor.getString(nameColumn);
            long id = cursor.getLong(idColumn);
            if (name != null) {
                imageFiles.put(name.toLowerCase(), id);
            }
        } while (cursor.moveToNext());

        // Find best matching artwork in priority order
        Long imageId = findBestArtwork(imageFiles);
        if (imageId != null) {
            Uri imageUri = ContentUris.withAppendedId(imagesUri, imageId);
            return loadImageFromUri(context, imageUri);
        }

        return null;
    }

    /**
     * Find the best matching artwork file from a map of filenames to IDs.
     *
     * @param imageFiles Map of lowercase filename to MediaStore ID
     * @return The ID of the best matching artwork, or null if none found
     */
    private static Long findBestArtwork(Map<String, Long> imageFiles) {
        // Check in priority order: front, cover, folder, albumart
        // For each base name, check png, jpg, jpeg
        for (String baseName : EXTERNAL_ART_BASE_NAMES) {
            for (String ext : IMAGE_EXTENSIONS) {
                String filename = baseName + "." + ext;
                Long id = imageFiles.get(filename);
                if (id != null) {
                    return id;
                }
            }
        }
        return null;
    }

    /**
     * Find and load external album art using direct file system access.
     * Only works on Android 12 and below.
     *
     * @param musicFilePath The absolute path to the music file
     * @return byte[] of the image data, or null if not found
     */
    private static byte[] getExternalAlbumArtViaFileSystem(String musicFilePath) {
        try {
            File musicFile = new File(musicFilePath);
            File parentDir = musicFile.getParentFile();

            if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory()) {
                return null;
            }

            // Try each expected filename in priority order
            for (String baseName : EXTERNAL_ART_BASE_NAMES) {
                for (String ext : IMAGE_EXTENSIONS) {
                    byte[] data = tryLoadArtFile(parentDir, baseName + "." + ext);
                    if (data != null) {
                        return data;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error searching for external art via file system", e);
        }

        return null;
    }

    /**
     * Try to load an art file with case-insensitive matching.
     *
     * @param parentDir The directory containing the art file
     * @param filename The expected filename (lowercase)
     * @return byte[] of the image data, or null if not found
     */
    private static byte[] tryLoadArtFile(File parentDir, String filename) {
        // Try common case variations
        String[] variations = {
                filename,                                           // front.jpg
                filename.toUpperCase(),                             // FRONT.JPG
                Character.toUpperCase(filename.charAt(0)) +         // Front.jpg
                        filename.substring(1)
        };

        for (String variant : variations) {
            File artFile = new File(parentDir, variant);
            if (artFile.exists() && artFile.isFile() && artFile.canRead()) {
                byte[] data = readFileToBytes(artFile);
                if (data != null) {
                    return data;
                }
            }
        }
        return null;
    }

    /**
     * Load image data from a content URI.
     *
     * @param context Application context
     * @param uri The content URI of the image
     * @return byte[] of the image data, or null on error
     */
    private static byte[] loadImageFromUri(Context context, Uri uri) {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) {
                return null;
            }
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] data = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(data)) != -1) {
                buffer.write(data, 0, bytesRead);
            }
            return buffer.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Error loading image from URI: " + uri, e);
            return null;
        }
    }

    /**
     * Extract embedded album art from audio file metadata.
     *
     * @param musicFilePath The absolute path to the music file
     * @return byte[] of the embedded image data, or null if not found
     */
    public static byte[] getEmbeddedAlbumArt(String musicFilePath) {
        if (musicFilePath == null || musicFilePath.isEmpty()) {
            return null;
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(musicFilePath);
            return retriever.getEmbeddedPicture();
        } catch (Exception e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                // Ignore release exception
            }
        }
    }

    /**
     * Read a file into a byte array.
     *
     * @param file The file to read
     * @return byte[] of file contents, or null on error
     */
    private static byte[] readFileToBytes(File file) {
        if (!file.exists() || !file.canRead()) {
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
            return data;
        } catch (IOException e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * Get the path to an external album art file if one exists.
     * Note: On Android 13+, this may return null even if the file exists
     * due to scoped storage restrictions.
     *
     * @param musicFilePath The absolute path to the music file
     * @return The path to the external artwork file, or null if not found
     * @deprecated Use {@link #getExternalAlbumArt(Context, String)} instead
     */
    @Deprecated
    public static String getExternalAlbumArtPath(String musicFilePath) {
        if (musicFilePath == null || musicFilePath.isEmpty()) {
            return null;
        }

        // On Android 13+, direct file access doesn't work for images
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return null;
        }

        try {
            File musicFile = new File(musicFilePath);
            File parentDir = musicFile.getParentFile();

            if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory()) {
                return null;
            }

            // Try each expected filename in priority order
            for (String baseName : EXTERNAL_ART_BASE_NAMES) {
                for (String ext : IMAGE_EXTENSIONS) {
                    String filename = baseName + "." + ext;
                    // Try common case variations
                    String[] variations = { filename, filename.toUpperCase(),
                            Character.toUpperCase(filename.charAt(0)) + filename.substring(1) };
                    
                    for (String variant : variations) {
                        File artFile = new File(parentDir, variant);
                        if (artFile.exists() && artFile.isFile() && artFile.canRead()) {
                            return artFile.getAbsolutePath();
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error searching for external art path", e);
        }

        return null;
    }

    /**
     * Result class for album grid artwork retrieval.
     * Indicates whether art was found externally (allowing skip of embedded extraction).
     */
    public static class AlbumArtResult {
        public final byte[] artData;
        public final boolean isExternal;

        public AlbumArtResult(byte[] artData, boolean isExternal) {
            this.artData = artData;
            this.isExternal = isExternal;
        }
    }

    // ==================== Cache Management Methods ====================

    /**
     * Clear all album art cache.
     * Use this when rescanning folders or resetting the app.
     *
     * @param context Application context
     */
    public static void clearCache(Context context) {
        MusicCacheDatabase cache = MusicCacheDatabase.getInstance(context);
        cache.clearAlbumArtCache();
        Log.d(TAG, "Album art cache cleared");
    }

    /**
     * Invalidate cache for a specific folder.
     * Use this when album art in a folder has changed.
     *
     * @param context Application context
     * @param folderPath The folder path to invalidate
     */
    public static void invalidateCacheForFolder(Context context, String folderPath) {
        if (folderPath == null) return;
        MusicCacheDatabase cache = MusicCacheDatabase.getInstance(context);
        cache.deleteCachedAlbumArt(folderPath);
        Log.d(TAG, "Cache invalidated for folder: " + folderPath);
    }

    /**
     * Pre-populate the cache for a list of music files.
     * Run this in a background thread for better performance.
     *
     * @param context Application context
     * @param musicFiles List of music files to cache art for
     */
    public static void prePopulateCache(Context context, java.util.List<MusicFiles> musicFiles) {
        if (musicFiles == null || musicFiles.isEmpty()) return;

        java.util.Set<String> processedFolders = new java.util.HashSet<>();
        MusicCacheDatabase cache = MusicCacheDatabase.getInstance(context);

        for (MusicFiles music : musicFiles) {
            String path = music.getPath();
            if (path == null) continue;

            java.io.File musicFile = new java.io.File(path);
            java.io.File parentDir = musicFile.getParentFile();
            if (parentDir == null) continue;

            String folderPath = parentDir.getAbsolutePath();

            // Skip if already processed or cached
            if (processedFolders.contains(folderPath)) continue;
            if (cache.hasAlbumArtCache(folderPath)) {
                processedFolders.add(folderPath);
                continue;
            }

            // Try to get and cache album art
            byte[] art = getAlbumArtForAlbum(context, path);
            processedFolders.add(folderPath);
        }

        Log.d(TAG, "Pre-populated cache for " + processedFolders.size() + " folders");
    }
}
