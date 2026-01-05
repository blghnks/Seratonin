package com.fungisoft.seratonin;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for TimeFormatter utility class.
 */
public class TimeFormatterTest {

    @Test
    public void formatTime_zeroSeconds_returnsZero() {
        assertEquals("0:00", TimeFormatter.formatTime(0));
    }

    @Test
    public void formatTime_singleDigitSeconds_padWithZero() {
        assertEquals("0:01", TimeFormatter.formatTime(1));
        assertEquals("0:05", TimeFormatter.formatTime(5));
        assertEquals("0:09", TimeFormatter.formatTime(9));
    }

    @Test
    public void formatTime_doubleDigitSeconds_noPadding() {
        assertEquals("0:10", TimeFormatter.formatTime(10));
        assertEquals("0:30", TimeFormatter.formatTime(30));
        assertEquals("0:59", TimeFormatter.formatTime(59));
    }

    @Test
    public void formatTime_fullMinute_showsCorrectly() {
        assertEquals("1:00", TimeFormatter.formatTime(60));
        assertEquals("2:00", TimeFormatter.formatTime(120));
        assertEquals("10:00", TimeFormatter.formatTime(600));
    }

    @Test
    public void formatTime_mixedMinutesAndSeconds() {
        assertEquals("1:30", TimeFormatter.formatTime(90));
        assertEquals("3:45", TimeFormatter.formatTime(225));
        assertEquals("5:05", TimeFormatter.formatTime(305));
    }

    @Test
    public void formatTime_longDuration() {
        // 1 hour = 3600 seconds
        assertEquals("60:00", TimeFormatter.formatTime(3600));
        // 1 hour 30 minutes 45 seconds
        assertEquals("90:45", TimeFormatter.formatTime(5445));
    }

    @Test
    public void formatTime_negativeValue_returnsZero() {
        assertEquals("0:00", TimeFormatter.formatTime(-1));
        assertEquals("0:00", TimeFormatter.formatTime(-100));
    }

    @Test
    public void formatTimeFromMillis_convertsCorrectly() {
        assertEquals("0:00", TimeFormatter.formatTimeFromMillis(0));
        assertEquals("0:01", TimeFormatter.formatTimeFromMillis(1000));
        assertEquals("0:01", TimeFormatter.formatTimeFromMillis(1500)); // Truncates
        assertEquals("1:30", TimeFormatter.formatTimeFromMillis(90000));
        assertEquals("3:45", TimeFormatter.formatTimeFromMillis(225000));
    }

    @Test
    public void formatTimeFromMillis_negativeValue_returnsZero() {
        assertEquals("0:00", TimeFormatter.formatTimeFromMillis(-1000));
    }
}
