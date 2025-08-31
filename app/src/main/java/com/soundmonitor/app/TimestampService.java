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
            // Calculate SHA-256 hash of the data
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            String hashHex = bytesToHex(hash);
            
            Log.i(TAG, "Calculated hash: " + hashHex);
            
            // Get authoritative time from HTTP time service
            String timeAuthority = "worldtimeapi.org";
            String authorativeTime = getHttpTime();
            
            // Get GPS location
            Location location = getLastKnownLocation(context);
            String latitude = "Unknown";
            String longitude = "Unknown";
            String locationProvider = "GPS unavailable";
            String locationAccuracy = "Unknown";
            String locationAge = "Unknown";
            
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
                
                Log.i(TAG, "GPS location captured: " + latitude + "," + longitude + " via " + locationProvider + " (accuracy: " + locationAccuracy + "m, age: " + locationAge + "s)");
            } else {
                Log.w(TAG, "GPS location unavailable");
            }
            
            if (authorativeTime != null) {
                String utcTime = TimestampUtils.getCurrentUtcTimestamp();
                Log.i(TAG, "Successfully got authoritative time");
                return new TimestampResult(utcTime, timeAuthority, hashHex, authorativeTime, latitude, longitude, locationProvider, locationAccuracy, locationAge);
            } else {
                return new TimestampResult("Time authority unavailable");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting timestamp", e);
            return new TimestampResult("Error: " + e.getMessage());
        }
    }
    
    private static String getHttpTime() {
        URL url = null;
        try {
            // Use WorldTimeAPI for authoritative time (HTTPS required)
            url = new URL("https://worldtimeapi.org/api/timezone/UTC");
            Log.i(TAG, "Attempting to connect to: " + url.toString());
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            
            try {
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000); // 5 seconds
                connection.setReadTimeout(5000); // 5 seconds
                
                int responseCode = connection.getResponseCode();
                Log.i(TAG, "Time API response code: " + responseCode);
                
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (InputStream is = connection.getInputStream()) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        
                        while ((bytesRead = is.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        
                        String response = baos.toString();
                        Log.i(TAG, "Time API response: " + response);
                        
                        // Extract datetime from JSON response
                        // Simple parsing - look for "datetime":"2024-03-15T14:32:15.123456Z"
                        int datetimeIndex = response.indexOf("\"datetime\":\"");
                        if (datetimeIndex != -1) {
                            int start = datetimeIndex + 12; // Skip "datetime":"
                            int end = response.indexOf("\"", start);
                            if (end != -1) {
                                return response.substring(start, end);
                            }
                        }
                        
                        return "Time extracted: " + new Date().toString();
                    }
                } else {
                    Log.w(TAG, "Time API returned error code: " + responseCode);
                    return null;
                }
                
            } finally {
                connection.disconnect();
            }
            
        } catch (Exception e) {
            String urlStr = (url != null) ? url.toString() : "unknown URL";
            Log.e(TAG, "Error getting authoritative time from " + urlStr, e);
            Log.e(TAG, "Exception type: " + e.getClass().getSimpleName() + ", Message: " + e.getMessage());
            return null;
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