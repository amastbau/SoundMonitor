package com.soundmonitor.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class TimestampUtils {
    
    // Standard UTC timestamp format for legal verification
    public static final String UTC_TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss 'UTC'";
    
    // File naming timestamp format
    public static final String FILE_TIMESTAMP_FORMAT = "yyyyMMdd_HHmmss";
    
    /**
     * Creates a properly configured UTC SimpleDateFormat
     */
    public static SimpleDateFormat createUtcFormatter() {
        SimpleDateFormat formatter = new SimpleDateFormat(UTC_TIMESTAMP_FORMAT, Locale.getDefault());
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        return formatter;
    }
    
    /**
     * Creates a file naming timestamp formatter
     */
    public static SimpleDateFormat createFileTimestampFormatter() {
        return new SimpleDateFormat(FILE_TIMESTAMP_FORMAT, Locale.getDefault());
    }
    
    /**
     * Get current UTC timestamp as formatted string
     */
    public static String getCurrentUtcTimestamp() {
        return createUtcFormatter().format(new Date());
    }
    
    /**
     * Get current file naming timestamp
     */
    public static String getCurrentFileTimestamp() {
        return createFileTimestampFormatter().format(new Date());
    }
    
    /**
     * Format a given date as UTC timestamp
     */
    public static String formatAsUtc(Date date) {
        return createUtcFormatter().format(date);
    }
    
    /**
     * Format a given date as file timestamp
     */
    public static String formatAsFileTimestamp(Date date) {
        return createFileTimestampFormatter().format(date);
    }
}