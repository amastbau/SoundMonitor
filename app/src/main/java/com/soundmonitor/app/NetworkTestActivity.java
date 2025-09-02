package com.soundmonitor.app;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import android.app.AlertDialog;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.Handler;
import android.os.Looper;

public class NetworkTestActivity extends Activity {
    private static final String TAG = "NetworkTest";
    
    // Time provider configurations
    private static class TimeProvider {
        final String url;
        final String name;
        final String description;
        
        TimeProvider(String url, String name, String description) {
            this.url = url;
            this.name = name;
            this.description = description;
        }
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Show loading dialog
        AlertDialog loadingDialog = new AlertDialog.Builder(this)
            .setTitle("Testing Network...")
            .setMessage("Checking internet connectivity...")
            .setCancelable(false)
            .create();
        loadingDialog.show();
        
        // Test network connections in background thread
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        
        executor.execute(() -> {
            String result = testTimeAPIs();
            handler.post(() -> {
                loadingDialog.dismiss();
                showDetailedResults(result);
                
                // Also show a quick toast
                String summary = result.contains("✅") ? "Network test: Connection OK" : "Network test: Connection failed";
                Toast.makeText(this, summary, Toast.LENGTH_SHORT).show();
            });
            executor.shutdown();
        });
    }
    
    private String testTimeAPIs() {
        StringBuilder report = new StringBuilder();
        report.append("🌐 NETWORK CONNECTIVITY TEST\n\n");
        
        Log.i(TAG, "Testing Google connectivity");
        report.append("📡 Google.com\n");
        report.append("   Basic internet connectivity test\n");
        
        try {
            TestResult result = testSingleAPI("https://www.google.com", "Google");
            
            if (result.success) {
                report.append("   ✅ SUCCESS (").append(result.responseTime).append("ms)\n");
                report.append("   🌐 Internet connection is working\n\n");
                
                report.append("📊 RESULT\n");
                report.append("✅ NETWORK IS WORKING\n");
                report.append("Internet connectivity confirmed.\n");
                report.append("App network features should work normally.");
            } else {
                report.append("   ❌ FAILED: ").append(result.error).append("\n\n");
                
                report.append("📊 RESULT\n");
                report.append("❌ NETWORK ISSUES DETECTED\n");
                report.append("Cannot reach Google.com\n");
                report.append("Check your internet connection, WiFi, or mobile data.");
            }
            
        } catch (Exception e) {
            report.append("   ❌ ERROR: ").append(e.getMessage()).append("\n\n");
            
            report.append("📊 RESULT\n");
            report.append("❌ NETWORK TEST FAILED\n");
            report.append("Error: ").append(e.getMessage()).append("\n");
            report.append("Check your internet connection settings.");
        }
        
        return report.toString();
    }
    
    private static class TestResult {
        final boolean success;
        final String error;
        final long responseTime;
        final boolean hasValidTime;
        
        TestResult(boolean success, String error, long responseTime, boolean hasValidTime) {
            this.success = success;
            this.error = error;
            this.responseTime = responseTime;
            this.hasValidTime = hasValidTime;
        }
    }
    
    private TestResult testSingleAPI(String urlString, String providerName) {
        long startTime = System.currentTimeMillis();
        
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            
            try {
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000); // 8 seconds
                connection.setReadTimeout(8000);
                connection.setUseCaches(false);
                connection.setRequestProperty("User-Agent", "SoundMonitor/1.0");
                
                int responseCode = connection.getResponseCode();
                long responseTime = System.currentTimeMillis() - startTime;
                
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    String response = readResponse(connection);
                    boolean hasValidTime = response.contains("datetime") || response.contains("dateTime") || 
                                         response.contains("time") || response.contains("<!DOCTYPE html");
                    
                    Log.i(TAG, providerName + " success in " + responseTime + "ms");
                    return new TestResult(true, null, responseTime, hasValidTime);
                } else {
                    Log.w(TAG, providerName + " returned HTTP " + responseCode);
                    return new TestResult(false, "HTTP " + responseCode, responseTime, false);
                }
                
            } finally {
                connection.disconnect();
            }
            
        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            Log.e(TAG, providerName + " failed: " + e.getMessage());
            return new TestResult(false, e.getClass().getSimpleName() + ": " + e.getMessage(), responseTime, false);
        }
    }
    
    private String readResponse(HttpURLConnection connection) {
        try (InputStream is = connection.getInputStream()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;
            int totalBytes = 0;
            
            // Limit reading to 5KB to avoid hanging on large responses
            while ((bytesRead = is.read(buffer)) != -1 && totalBytes < 5120) {
                baos.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }
            
            return baos.toString();
        } catch (Exception e) {
            return "Error reading response: " + e.getMessage();
        }
    }
    
    private void showDetailedResults(String report) {
        new AlertDialog.Builder(this)
            .setTitle("Network Test Results")
            .setMessage(report)
            .setPositiveButton("OK", (dialog, which) -> finish())
            .setNeutralButton("Copy", (dialog, which) -> {
                // Copy to clipboard
                android.content.ClipboardManager clipboard = 
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("Network Test", report);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "Network test results copied to clipboard", Toast.LENGTH_SHORT).show();
                finish();
            })
            .setOnDismissListener(dialog -> finish())
            .show();
    }
}