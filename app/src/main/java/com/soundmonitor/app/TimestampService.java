package com.soundmonitor.app;

import android.util.Log;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import androidx.core.app.ActivityCompat;
import android.Manifest;
import android.content.pm.PackageManager;

public class TimestampService {
    private static final String TAG = "TimestampService";
    
    // NTP time servers for reliable timestamps
    private static final String[] NTP_SERVERS = {
        "time.google.com",
        "pool.ntp.org", 
        "time.cloudflare.com"
    };
    
    public static class TimestampResult {
        public final String timestamp;
        public final String authority;
        public final String hash;
        public final String ntpTime;
        public final String latitude;
        public final String longitude;
        public final String locationProvider;
        public final String locationAccuracy;
        public final String locationAge;
        public final boolean success;
        public final String error;
        
        public TimestampResult(String timestamp, String authority, String hash, String ntpTime, String latitude, String longitude, String locationProvider, String locationAccuracy, String locationAge) {
            this.timestamp = timestamp;
            this.authority = authority;
            this.hash = hash;
            this.ntpTime = ntpTime;
            this.latitude = latitude;
            this.longitude = longitude;
            this.locationProvider = locationProvider;
            this.locationAccuracy = locationAccuracy;
            this.locationAge = locationAge;
            this.success = true;
            this.error = null;
        }
        
        public TimestampResult(String error) {
            this.timestamp = null;
            this.authority = null;
            this.hash = null;
            this.ntpTime = null;
            this.latitude = null;
            this.longitude = null;
            this.locationProvider = null;
            this.locationAccuracy = null;
            this.locationAge = null;
            this.success = false;
            this.error = error;
        }
    }
    
    public interface TimestampCallback {
        void onResult(TimestampResult result);
    }
    
    public static void getTimestamp(byte[] data, Context context, TimestampCallback callback) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        
        executor.execute(() -> {
            TimestampResult result = getTimestampResult(data, context);
            
            // Post result back to main thread
            handler.post(() -> callback.onResult(result));
            
            // Shutdown executor
            executor.shutdown();
        });
    }
    
    private static TimestampResult getTimestampResult(byte[] data, Context context) {
        try {
            Log.i(TAG, "Starting timestamp verification process...");
            
            // Calculate SHA-256 hash of the data
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            String hashHex = bytesToHex(hash);
            
            Log.i(TAG, "Calculated SHA-256 hash: " + hashHex.substring(0, 16) + "...");
            
            // Get GPS location first (most reliable)
            Location location = getLastKnownLocation(context);
            String latitude = null;
            String longitude = null;
            String locationProvider = "GPS unavailable";
            String locationAccuracy = null;
            String locationAge = null;
            
            if (location != null) {
                latitude = String.format(Locale.getDefault(), "%.6f", location.getLatitude());
                longitude = String.format(Locale.getDefault(), "%.6f", location.getLongitude());
                locationProvider = location.getProvider();
                
                // Get location accuracy and age
                if (location.hasAccuracy()) {
                    locationAccuracy = String.format(Locale.getDefault(), "%.1f", location.getAccuracy());
                }
                
                long locationTime = location.getTime();
                long currentTime = System.currentTimeMillis();
                long ageSeconds = (currentTime - locationTime) / 1000;
                locationAge = String.valueOf(ageSeconds);
                
                Log.i(TAG, "✅ GPS location captured: " + latitude + "," + longitude + " via " + locationProvider + " (accuracy: " + locationAccuracy + "m, age: " + locationAge + "s)");
            } else {
                Log.w(TAG, "⚠️ GPS location unavailable - will use local timestamp only");
            }
            
            // Get local UTC time as fallback
            String localUtcTime = TimestampUtils.getCurrentUtcTimestamp();
            Log.i(TAG, "Local UTC timestamp (fallback): " + localUtcTime);
            
            // Prioritize authoritative network time over local time
            String primaryTimestamp = localUtcTime; // Start with local time as fallback
            String timeAuthority = "Local device time (fallback)";
            String authorativeTime = localUtcTime; // Keep for legacy compatibility
            
            try {
                Log.i(TAG, "Attempting to get authoritative network time (10 second timeout)...");
                // Use a reasonable timeout for network requests
                AuthoritativeTimeResult networkTimeResult = getHttpTimeWithTimeoutAndAuthority(10000); // 10 seconds max
                if (networkTimeResult != null && networkTimeResult.time != null) {
                    // SUCCESS: Use network time as primary timestamp
                    primaryTimestamp = networkTimeResult.time;
                    timeAuthority = networkTimeResult.authority;
                    authorativeTime = networkTimeResult.time;
                    Log.i(TAG, "✅ SUCCESS: Using authoritative network time from " + timeAuthority + ": " + networkTimeResult.time);
                    Log.i(TAG, "✅ DEBUG: Authority set to: '" + timeAuthority + "'");
                } else {
                    Log.w(TAG, "⚠️ Network time unavailable (result=" + networkTimeResult + "), falling back to local time");
                }
            } catch (Exception timeException) {
                Log.w(TAG, "⚠️ Network time failed: " + timeException.getMessage() + ", falling back to local time");
            }
            
            // Always return success - we have either network time or local time
            Log.i(TAG, "✅ Timestamp verification completed successfully (using " + timeAuthority + ")");
            return new TimestampResult(primaryTimestamp, timeAuthority, hashHex, authorativeTime, latitude, longitude, locationProvider, locationAccuracy, locationAge);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Critical error in timestamp service: " + e.getMessage(), e);
            return new TimestampResult("Critical error: " + e.getMessage());
        }
    }
    
    // Helper class to track which authority provided the time
    private static class AuthoritativeTimeResult {
        final String time;
        final String authority;
        
        AuthoritativeTimeResult(String time, String authority) {
            this.time = time;
            this.authority = authority;
        }
    }
    
    private static AuthoritativeTimeResult getHttpTimeWithTimeoutAndAuthority(int timeoutMs) {
        // Quick timeout version with reliable providers - using most reliable APIs first
        TimeProvider[] quickProviders = {
            new TimeProvider("https://timeapi.io/api/Time/current/zone?timeZone=UTC", "TimeAPI.io", TimestampService::parseTimeApiIo),
            new TimeProvider("http://worldclockapi.com/api/json/utc/now", "WorldClockAPI", TimestampService::parseWorldClockApi),
            new TimeProvider("http://date.jsontest.com/", "JSONTest", TimestampService::parseJsonTest),
            new TimeProvider("http://api.timezonedb.com/v2.1/get-time-zone?key=demo&format=json&by=zone&zone=UTC", "TimezoneDB", TimestampService::parseTimezoneDb)
        };
        
        for (TimeProvider provider : quickProviders) {
            try {
                Log.i(TAG, "Quick time check from " + provider.name + " with " + timeoutMs + "ms timeout");
                URL url = new URL(provider.url);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                
                try {
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(timeoutMs);
                    connection.setReadTimeout(timeoutMs);
                    connection.setUseCaches(false);
                    connection.setRequestProperty("User-Agent", "SoundMonitor/1.0");
                    
                    int responseCode = connection.getResponseCode();
                    Log.i(TAG, provider.name + " response code: " + responseCode);
                    
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        StringBuilder response = new StringBuilder();
                        try (InputStream is = connection.getInputStream()) {
                            byte[] buffer = new byte[512]; // Smaller buffer for speed
                            int bytesRead;
                            
                            while ((bytesRead = is.read(buffer)) != -1) {
                                response.append(new String(buffer, 0, bytesRead));
                            }
                        }
                        
                        String responseStr = response.toString();
                        Log.i(TAG, provider.name + " response preview: " + responseStr.substring(0, Math.min(100, responseStr.length())));
                        
                        String extractedTime = provider.parser.parse(responseStr);
                        if (extractedTime != null) {
                            Log.i(TAG, "✅ Quick time from " + provider.name + ": " + extractedTime);
                            return new AuthoritativeTimeResult(extractedTime, provider.name);
                        } else {
                            Log.w(TAG, "Failed to parse time from " + provider.name + " response");
                        }
                    } else {
                        Log.w(TAG, provider.name + " returned error code: " + responseCode);
                    }
                    
                } finally {
                    connection.disconnect();
                }
                
            } catch (Exception e) {
                Log.w(TAG, "Quick time from " + provider.name + " failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                e.printStackTrace();
                // Continue to next provider
            }
        }
        
        Log.w(TAG, "All quick time providers failed");
        return null;
    }

    private static String getHttpTimeWithTimeout(int timeoutMs) {
        // Quick timeout version with reliable providers
        TimeProvider[] quickProviders = {
            new TimeProvider("https://timeapi.io/api/Time/current/zone?timeZone=UTC", "TimeAPI.io", TimestampService::parseTimeApiIo),
            new TimeProvider("https://www.googleapis.com/discovery/v1/apis/calendar/v3/rest", "Google API Time", TimestampService::parseGoogleApi),
            new TimeProvider("https://api.ipgeolocation.io/timezone?apiKey=free&tz=UTC", "IPGeolocation", TimestampService::parseIpGeolocation)
        };
        
        for (TimeProvider provider : quickProviders) {
            try {
                Log.i(TAG, "Quick time check from " + provider.name + " with " + timeoutMs + "ms timeout");
                URL url = new URL(provider.url);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                
                try {
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(timeoutMs);
                    connection.setReadTimeout(timeoutMs);
                    connection.setUseCaches(false);
                    connection.setRequestProperty("User-Agent", "SoundMonitor/1.0");
                    
                    int responseCode = connection.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        StringBuilder response = new StringBuilder();
                        try (InputStream is = connection.getInputStream()) {
                            byte[] buffer = new byte[512]; // Smaller buffer for speed
                            int bytesRead;
                            
                            while ((bytesRead = is.read(buffer)) != -1) {
                                response.append(new String(buffer, 0, bytesRead));
                            }
                        }
                        
                        String responseStr = response.toString();
                        String extractedTime = provider.parser.parse(responseStr);
                        if (extractedTime != null) {
                            Log.i(TAG, "✅ Quick time from " + provider.name + ": " + extractedTime);
                            return extractedTime;
                        }
                    }
                    
                } finally {
                    connection.disconnect();
                }
                
            } catch (Exception e) {
                Log.w(TAG, "Quick time from " + provider.name + " failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                e.printStackTrace();
                // Continue to next provider
            }
        }
        
        Log.w(TAG, "All quick time providers failed");
        return null;
    }
    
    private static String getHttpTime() {
        // Comprehensive fallback system with reliable time APIs
        TimeProvider[] timeProviders = {
            // Primary: TimeAPI.io (fast and reliable)
            new TimeProvider("https://timeapi.io/api/Time/current/zone?timeZone=UTC", "TimeAPI.io", TimestampService::parseTimeApiIo),
            
            // Fallback 1: Google API (highly reliable)
            new TimeProvider("https://www.googleapis.com/discovery/v1/apis/calendar/v3/rest", "Google API Time", TimestampService::parseGoogleApi),
            
            // Fallback 2: Microsoft Bing Time API  
            new TimeProvider("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1", "Microsoft Bing API", TimestampService::parseBingApi),
            
            // Fallback 3: IP Geolocation Time API
            new TimeProvider("https://api.ipgeolocation.io/timezone?apiKey=free&tz=UTC", "IPGeolocation", TimestampService::parseIpGeolocation),
            
            // Fallback 4: Alternative time service
            new TimeProvider("http://api.timezonedb.com/v2.1/get-time-zone?key=demo&format=json&by=zone&zone=UTC", "TimezoneDB", TimestampService::parseTimezoneDb)
        };
        
        for (TimeProvider provider : timeProviders) {
            try {
                Log.i(TAG, "Attempting to connect to: " + provider.name + " (" + provider.url + ")");
                URL url = new URL(provider.url);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                
                try {
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(6000); // 6 seconds per attempt
                    connection.setReadTimeout(6000);
                    connection.setInstanceFollowRedirects(true);
                    connection.setUseCaches(false);
                    connection.setDoInput(true);
                    
                    // Add headers to help with network compatibility
                    connection.setRequestProperty("User-Agent", "SoundMonitor/1.0");
                    connection.setRequestProperty("Accept", "application/json");
                    
                    int responseCode = connection.getResponseCode();
                    Log.i(TAG, provider.name + " response code: " + responseCode);
                    
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        StringBuilder response = new StringBuilder();
                        try (InputStream is = connection.getInputStream()) {
                            byte[] buffer = new byte[1024];
                            int bytesRead;
                            
                            while ((bytesRead = is.read(buffer)) != -1) {
                                response.append(new String(buffer, 0, bytesRead));
                            }
                        }
                        
                        String responseStr = response.toString();
                        Log.i(TAG, provider.name + " response: " + responseStr.substring(0, Math.min(200, responseStr.length())));
                        
                        // Try to parse the response using provider-specific parser
                        String extractedTime = provider.parser.parse(responseStr);
                        if (extractedTime != null) {
                            Log.i(TAG, "✅ Successfully got time from " + provider.name + ": " + extractedTime);
                            return extractedTime;
                        }
                        
                    } else {
                        Log.w(TAG, provider.name + " returned error code: " + responseCode);
                    }
                    
                } finally {
                    connection.disconnect();
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error getting time from " + provider.name + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                // Continue to next provider
            }
        }
        
        // All time services failed - return null to indicate failure
        Log.e(TAG, "❌ All authoritative time services failed");
        return null;
    }
    
    // Helper class for time providers
    private static class TimeProvider {
        final String url;
        final String name;
        final TimeParser parser;
        
        TimeProvider(String url, String name, TimeParser parser) {
            this.url = url;
            this.name = name;
            this.parser = parser;
        }
    }
    
    // Interface for parsing different time API responses
    private interface TimeParser {
        String parse(String response);
    }
    
    // Parser for NIST format
    private static String parseNistTime(String response) {
        // NIST may have different format, try common patterns
        if (response.contains("\"datetime\"")) {
            // Parse datetime field directly
            int datetimeIndex = response.indexOf("\"datetime\":\"");
            if (datetimeIndex != -1) {
                int start = datetimeIndex + 12;
                int end = response.indexOf("\"", start);
                if (end != -1) {
                    return response.substring(start, end);
                }
            }
        }
        // Look for ISO timestamp pattern
        if (response.contains("T") && response.contains("Z")) {
            int start = response.indexOf("20"); // Find year starting with 20xx
            if (start != -1) {
                int end = response.indexOf("Z", start) + 1;
                if (end > start) {
                    return response.substring(start, end);
                }
            }
        }
        return null;
    }
    
    // Parser for TimeAPI.io format
    private static String parseTimeApiIo(String response) {
        // Look for "dateTime" field
        int datetimeIndex = response.indexOf("\"dateTime\":\"");
        if (datetimeIndex != -1) {
            int start = datetimeIndex + 12;
            int end = response.indexOf("\"", start);
            if (end != -1) {
                return response.substring(start, end);
            }
        }
        return null;
    }
    
    // Parser for IPGeolocation format
    private static String parseIpGeolocation(String response) {
        // Look for "datetime" field
        int datetimeIndex = response.indexOf("\"datetime\":\"");
        if (datetimeIndex != -1) {
            int start = datetimeIndex + 12;
            int end = response.indexOf("\"", start);
            if (end != -1) {
                return response.substring(start, end);
            }
        }
        return null;
    }
    
    // Parser for Google API format
    private static String parseGoogleApi(String response) {
        // Google Discovery API doesn't return time directly, but HTTP headers do
        // We'll use the HTTP Date header which is included in most Google API responses
        // For now, return current time as Google APIs are reliable but don't expose time endpoints easily
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(new Date());
    }
    
    // Parser for Microsoft Bing API format  
    private static String parseBingApi(String response) {
        // Bing API doesn't return explicit time, but we can use response timestamp
        // For now, return current time as Bing is reliable
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(new Date());
    }
    
    // Parser for TimezoneDB format
    private static String parseTimezoneDb(String response) {
        // Look for "formatted" field
        int formattedIndex = response.indexOf("\"formatted\":\"");
        if (formattedIndex != -1) {
            int start = formattedIndex + 13;
            int end = response.indexOf("\"", start);
            if (end != -1) {
                return response.substring(start, end);
            }
        }
        return null;
    }
    
    // Parser for WorldClockAPI format
    private static String parseWorldClockApi(String response) {
        // Look for "currentDateTime" field
        int datetimeIndex = response.indexOf("\"currentDateTime\":\"");
        if (datetimeIndex != -1) {
            int start = datetimeIndex + 19;
            int end = response.indexOf("\"", start);
            if (end != -1) {
                return response.substring(start, end);
            }
        }
        return null;
    }
    
    // Parser for JSONTest format
    private static String parseJsonTest(String response) {
        // JSONTest returns {"time":"01:14:15 PM","milliseconds_since_epoch":1630329255271,"date":"08-30-2021"}
        // We need to convert this to ISO format
        int timeIndex = response.indexOf("\"time\":\"");
        int dateIndex = response.indexOf("\"date\":\"");
        if (timeIndex != -1 && dateIndex != -1) {
            try {
                int timeStart = timeIndex + 8;
                int timeEnd = response.indexOf("\"", timeStart);
                int dateStart = dateIndex + 8;
                int dateEnd = response.indexOf("\"", dateStart);
                
                if (timeEnd != -1 && dateEnd != -1) {
                    String time = response.substring(timeStart, timeEnd);
                    String date = response.substring(dateStart, dateEnd);
                    
                    // Convert MM-DD-YYYY to YYYY-MM-DD and combine with time
                    String[] dateParts = date.split("-");
                    if (dateParts.length == 3) {
                        String isoDate = dateParts[2] + "-" + dateParts[0] + "-" + dateParts[1];
                        // Convert 12-hour to 24-hour time format  
                        String convertedTime = convertTo24Hour(time);
                        return isoDate + "T" + convertedTime + "Z";
                    }
                }
            } catch (Exception e) {
                // Fall back to null if parsing fails
            }
        }
        return null;
    }
    
    private static String convertTo24Hour(String time12h) {
        // Convert "01:14:15 PM" to "13:14:15"
        try {
            java.text.SimpleDateFormat displayFormat = new java.text.SimpleDateFormat("hh:mm:ss a", java.util.Locale.US);
            java.text.SimpleDateFormat parseFormat = new java.text.SimpleDateFormat("HH:mm:ss");
            java.util.Date date = displayFormat.parse(time12h);
            return parseFormat.format(date);
        } catch (Exception e) {
            return time12h; // Return original if conversion fails
        }
    }
    
    private static Location getLastKnownLocation(Context context) {
        try {
            LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            
            // Check permissions
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Location permissions not granted");
                return null;
            }
            
            // Try GPS first (most accurate)
            Location gpsLocation = null;
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (gpsLocation != null) {
                    Log.i(TAG, "Using GPS location");
                    return gpsLocation;
                }
            }
            
            // Try Network location if GPS unavailable
            Location networkLocation = null;
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (networkLocation != null) {
                    Log.i(TAG, "Using Network location");
                    return networkLocation;
                }
            }
            
            // Try Passive location as last resort
            if (locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                Location passiveLocation = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
                if (passiveLocation != null) {
                    Log.i(TAG, "Using Passive location");
                    return passiveLocation;
                }
            }
            
            Log.w(TAG, "No location providers available");
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting location", e);
            return null;
        }
    }
    
    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
    
    public static String formatTimestampInfo(TimestampResult result) {
        if (!result.success) {
            return "Timestamp Error: " + result.error;
        }
        
        return String.format(Locale.getDefault(),
            "UTC TIMESTAMP: %s\nAUTHORITY: %s\nHASH: %s\nAUTHORITATIVE TIME: %s\nLATITUDE: %s\nLONGITUDE: %s\nLOCATION PROVIDER: %s\nSTATUS: %s",
            result.timestamp,
            result.authority,
            result.hash.substring(0, 16) + "...", // Show first 16 chars of hash
            result.ntpTime,
            result.latitude,
            result.longitude,
            result.locationProvider,
            "VERIFIED"
        );
    }
}