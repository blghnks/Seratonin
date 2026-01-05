package com.fungisoft.seratonin;

/**
 * Utility class for formatting time values for display.
 * Extracted for testability and reuse across the app.
 */
public class TimeFormatter {

    /**
     * Format seconds into a human-readable time string (mm:ss format).
     *
     * @param totalSeconds Time in seconds
     * @return Formatted string like "3:45" or "12:05"
     */
    public static String formatTime(int totalSeconds) {
        if (totalSeconds < 0) {
            return "0:00";
        }
        
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        
        if (seconds < 10) {
            return minutes + ":0" + seconds;
        } else {
            return minutes + ":" + seconds;
        }
    }
    
    /**
     * Format milliseconds into a human-readable time string (mm:ss format).
     *
     * @param milliseconds Time in milliseconds
     * @return Formatted string like "3:45" or "12:05"
     */
    public static String formatTimeFromMillis(long milliseconds) {
        if (milliseconds < 0) {
            return "0:00";
        }
        return formatTime((int) (milliseconds / 1000));
    }
}
