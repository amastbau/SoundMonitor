package com.soundmonitor.app;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
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
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Test network connection in background thread
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        
        executor.execute(() -> {
            String result = testWorldTimeAPI();
            handler.post(() -> {
                Toast.makeText(this, "Network test: " + result, Toast.LENGTH_LONG).show();
                Log.i(TAG, "Network test result: " + result);
                finish(); // Close this test activity
            });
            executor.shutdown();
        });
    }
    
    private String testWorldTimeAPI() {
        try {
            URL url = new URL("https://worldtimeapi.org/api/timezone/UTC");
            Log.i(TAG, "Testing connection to: " + url.toString());
            
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000); // 10 seconds
            connection.setReadTimeout(10000);
            
            int responseCode = connection.getResponseCode();
            Log.i(TAG, "Response code: " + responseCode);
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (InputStream is = connection.getInputStream()) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    
                    while ((bytesRead = is.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                    
                    String response = baos.toString();
                    Log.i(TAG, "Response: " + response);
                    return "SUCCESS: Got response from WorldTimeAPI";
                }
            } else {
                return "FAILED: HTTP " + responseCode;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Network test failed", e);
            return "ERROR: " + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }
}