package com.soundmonitor.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.util.Log;
import android.location.Location;
import android.location.LocationManager;
import android.app.AlertDialog;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 200;
    private Button startStopButton, testNetworkButton, testGpsButton, cameraPreviewButton;
    private SeekBar thresholdSeekBar, timeoutSeekBar;
    private TextView thresholdText, timeoutText, statusText, currentDbText;
    private RadioGroup recordingModeGroup, cameraSelectionGroup;
    private RadioButton videoModeRadio, audioModeRadio, rearCameraRadio, frontCameraRadio;
    private boolean isServiceRunning = false;
    private int soundThreshold = 0;
    private int stopTimeout = 5; // Default 5 seconds
    private boolean isAudioOnlyMode = false;
    private int selectedCameraId = 0; // Default to rear camera (0), front camera is usually 1
    private BroadcastReceiver decibelReceiver;
    private BroadcastReceiver recordingStateReceiver;
    
    // Local dB monitoring when service is not running
    private AudioRecord localAudioRecord;
    private boolean isLocalMonitoring = false;
    private Handler dbHandler;
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    
    // Threshold and timeout update debouncing
    private Handler thresholdHandler;
    private Runnable thresholdUpdateRunnable;
    private Runnable timeoutUpdateRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        dbHandler = new Handler();
        thresholdHandler = new Handler();
        initViews();
        setupListeners();
        setupDecibelReceiver();
        setupRecordingStateReceiver();
        checkPermissions();
    }
    
    private void initViews() {
        startStopButton = findViewById(R.id.startStopButton);
        testNetworkButton = findViewById(R.id.testNetworkButton);
        testGpsButton = findViewById(R.id.testGpsButton);
        cameraPreviewButton = findViewById(R.id.cameraPreviewButton);
        thresholdSeekBar = findViewById(R.id.thresholdSeekBar);
        timeoutSeekBar = findViewById(R.id.timeoutSeekBar);
        thresholdText = findViewById(R.id.thresholdText);
        timeoutText = findViewById(R.id.timeoutText);
        statusText = findViewById(R.id.statusText);
        currentDbText = findViewById(R.id.currentDbText);
        recordingModeGroup = findViewById(R.id.recordingModeGroup);
        videoModeRadio = findViewById(R.id.videoModeRadio);
        audioModeRadio = findViewById(R.id.audioModeRadio);
        cameraSelectionGroup = findViewById(R.id.cameraSelectionGroup);
        rearCameraRadio = findViewById(R.id.rearCameraRadio);
        frontCameraRadio = findViewById(R.id.frontCameraRadio);
        
        // Threshold slider: 30-90 dB range
        thresholdSeekBar.setMax(60); // 0-60 progress = 30-90 dB
        thresholdSeekBar.setProgress(Math.max(soundThreshold - 30, 0)); // Convert dB to 0-60 progress
        if (soundThreshold == 0) {
            soundThreshold = 50; // Default 50 dB
            thresholdSeekBar.setProgress(20); // 50-30 = 20
        }
        thresholdText.setText("Threshold: " + soundThreshold + " dB");
        
        // Timeout slider: 1-30 seconds range
        timeoutSeekBar.setMax(29); // 0-29 progress = 1-30 seconds
        timeoutSeekBar.setProgress(stopTimeout - 1); // Convert seconds to 0-29 progress
        timeoutText.setText("Stop Timeout: " + stopTimeout + " seconds");
    }
    
    private void setupListeners() {
        startStopButton.setOnClickListener(v -> toggleService());
        testNetworkButton.setOnClickListener(v -> testNetworkConnection());
        testGpsButton.setOnClickListener(v -> testGpsLocation());
        cameraPreviewButton.setOnClickListener(v -> openCameraPreview());
        
        thresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                soundThreshold = progress + 30; // Convert 0-60 progress to 30-90 dB
                thresholdText.setText("Threshold: " + soundThreshold + " dB");
                
                // Debounce threshold updates to prevent rapid service calls
                if (isServiceRunning && fromUser) {
                    debouncedUpdateThreshold(soundThreshold);
                }
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        timeoutSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                stopTimeout = progress + 1; // Convert 0-29 progress to 1-30 seconds
                timeoutText.setText("Stop Timeout: " + stopTimeout + " seconds");
                
                // Debounce timeout updates to prevent rapid service calls
                if (isServiceRunning && fromUser) {
                    debouncedUpdateTimeout(stopTimeout);
                }
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        recordingModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.audioModeRadio) {
                isAudioOnlyMode = true;
                updateTimeoutLabelForAudioMode();
            } else {
                isAudioOnlyMode = false;
                updateTimeoutLabelForVideoMode();
            }
            
            // Update the service with the new mode if it's running
            if (isServiceRunning) {
                updateServiceMode();
            }
        });
        
        cameraSelectionGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.frontCameraRadio) {
                selectedCameraId = 1; // Front camera
            } else {
                selectedCameraId = 0; // Rear camera (default)
            }
            
            // Update the service with the new camera selection if it's running
            if (isServiceRunning) {
                updateServiceCameraSelection();
            }
        });
    }
    
    private void updateTimeoutLabelForAudioMode() {
        timeoutText.setText("Audio Mode: No auto-stop");
        timeoutSeekBar.setEnabled(false);
    }
    
    private void updateTimeoutLabelForVideoMode() {
        timeoutText.setText("Stop Timeout: " + stopTimeout + " seconds");
        timeoutSeekBar.setEnabled(true);
    }
    
    private void updateServiceMode() {
        try {
            // Send updated mode to running service
            Intent updateIntent = new Intent(this, SoundMonitorService.class);
            updateIntent.setAction("UPDATE_THRESHOLD");
            updateIntent.putExtra("threshold", soundThreshold);
            updateIntent.putExtra("audioOnlyMode", isAudioOnlyMode);
            updateIntent.putExtra("cameraId", selectedCameraId);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(updateIntent);
            } else {
                startService(updateIntent);
            }
            Log.d("MainActivity", "Sent mode update: audioOnly=" + isAudioOnlyMode);
            
        } catch (Exception e) {
            Log.e("MainActivity", "Error updating service mode", e);
        }
    }
    
    private void updateServiceCameraSelection() {
        try {
            // Send updated camera selection to running service
            Intent updateIntent = new Intent(this, SoundMonitorService.class);
            updateIntent.setAction("UPDATE_THRESHOLD");
            updateIntent.putExtra("threshold", soundThreshold);
            updateIntent.putExtra("audioOnlyMode", isAudioOnlyMode);
            updateIntent.putExtra("cameraId", selectedCameraId);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(updateIntent);
            } else {
                startService(updateIntent);
            }
            Log.d("MainActivity", "Sent camera update: " + (selectedCameraId == 0 ? "Rear" : "Front") + " camera");
            
        } catch (Exception e) {
            Log.e("MainActivity", "Error updating service camera selection", e);
        }
    }
    
    private void debouncedUpdateThreshold(int newThreshold) {
        // Cancel any pending threshold update
        if (thresholdUpdateRunnable != null) {
            thresholdHandler.removeCallbacks(thresholdUpdateRunnable);
        }
        
        // Schedule new threshold update with 300ms delay
        thresholdUpdateRunnable = () -> updateServiceThreshold(newThreshold);
        thresholdHandler.postDelayed(thresholdUpdateRunnable, 300);
    }
    
    private void debouncedUpdateTimeout(int newTimeout) {
        // Cancel any pending timeout update
        if (timeoutUpdateRunnable != null) {
            thresholdHandler.removeCallbacks(timeoutUpdateRunnable);
        }
        
        // Schedule new timeout update with 300ms delay
        timeoutUpdateRunnable = () -> updateServiceTimeout(newTimeout);
        thresholdHandler.postDelayed(timeoutUpdateRunnable, 300);
    }
    
    private void updateServiceThreshold(int newThreshold) {
        try {
            // Send updated threshold to running service
            Intent updateIntent = new Intent(this, SoundMonitorService.class);
            updateIntent.setAction("UPDATE_THRESHOLD");
            updateIntent.putExtra("threshold", newThreshold);
            updateIntent.putExtra("audioOnlyMode", isAudioOnlyMode);
            updateIntent.putExtra("cameraId", selectedCameraId);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(updateIntent);
            } else {
                startService(updateIntent);
            }
            Log.d("MainActivity", "Sent threshold update: " + newThreshold + "dB");
            
        } catch (Exception e) {
            Log.e("MainActivity", "Error updating service threshold", e);
            // Don't crash the app, just log the error
        }
    }
    
    private void updateServiceTimeout(int newTimeout) {
        try {
            // Send updated timeout to running service
            Intent updateIntent = new Intent(this, SoundMonitorService.class);
            updateIntent.setAction("UPDATE_TIMEOUT");
            updateIntent.putExtra("timeout", newTimeout);
            updateIntent.putExtra("audioOnlyMode", isAudioOnlyMode);
            updateIntent.putExtra("cameraId", selectedCameraId);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(updateIntent);
            } else {
                startService(updateIntent);
            }
            Log.d("MainActivity", "Sent timeout update: " + newTimeout + " seconds");
            
        } catch (Exception e) {
            Log.e("MainActivity", "Error updating service timeout", e);
            // Don't crash the app, just log the error
        }
    }
    
    private void toggleService() {
        if (!isServiceRunning) {
            if (hasAllPermissions()) {
                startMonitoringService();
            } else {
                requestPermissions();
            }
        } else {
            stopMonitoringService();
        }
    }
    
    private void startMonitoringService() {
        // Stop local monitoring since service will handle it
        stopLocalDbMonitoring();
        
        Intent serviceIntent = new Intent(this, SoundMonitorService.class);
        serviceIntent.putExtra("threshold", soundThreshold);
        serviceIntent.putExtra("timeout", stopTimeout);
        serviceIntent.putExtra("audioOnlyMode", isAudioOnlyMode);
        serviceIntent.putExtra("cameraId", selectedCameraId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        }
        isServiceRunning = true;
        startStopButton.setText("Stop Monitoring");
        if (isAudioOnlyMode) {
            statusText.setText("Status: Audio-only monitoring...");
        } else {
            statusText.setText("Status: Monitoring for sounds...");
        }
        currentDbText.setText("Current: -- dB");
    }
    
    private void stopMonitoringService() {
        Intent serviceIntent = new Intent(this, SoundMonitorService.class);
        stopService(serviceIntent);
        isServiceRunning = false;
        startStopButton.setText("Start Monitoring");
        statusText.setText("Status: Stopped");
        currentDbText.setText("Current: -- dB");
        currentDbText.setTextColor(0xFF666666); // Gray when stopped
        
        // Restart local monitoring if we have permissions
        if (hasAllPermissions()) {
            startLocalDbMonitoring();
        }
    }
    
    private boolean hasAllPermissions() {
        boolean hasBasicPermissions = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        
        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return hasBasicPermissions && 
                   ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        
        return hasBasicPermissions;
    }
    
    private void checkPermissions() {
        if (!hasAllPermissions()) {
            requestPermissions();
        }
    }
    
    private void requestPermissions() {
        String[] permissions;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            };
        } else {
            permissions = new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            };
        }
        
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (hasAllPermissions()) {
                Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Permissions required for app to work!", Toast.LENGTH_LONG).show();
            }
        }
    }
    
    private void testNetworkConnection() {
        Intent testIntent = new Intent(this, NetworkTestActivity.class);
        startActivity(testIntent);
    }
    
    private void openCameraPreview() {
        if (!hasAllPermissions()) {
            Toast.makeText(this, "Camera permission required for preview!", Toast.LENGTH_LONG).show();
            requestPermissions();
            return;
        }
        
        Intent cameraIntent = new Intent(this, CameraPreviewActivity.class);
        cameraIntent.putExtra("threshold", soundThreshold);
        cameraIntent.putExtra("timeout", stopTimeout);
        cameraIntent.putExtra("cameraId", selectedCameraId);
        startActivity(cameraIntent);
    }
    
    private void testGpsLocation() {
        if (!hasLocationPermissions()) {
            Toast.makeText(this, "Location permissions required for GPS test!", Toast.LENGTH_LONG).show();
            requestPermissions();
            return;
        }
        
        // Show loading dialog
        AlertDialog loadingDialog = new AlertDialog.Builder(this)
            .setTitle("Checking GPS...")
            .setMessage("Getting location information...")
            .setCancelable(false)
            .create();
        loadingDialog.show();
        
        // Run GPS check in background thread
        new Thread(() -> {
            String gpsReport = generateGpsReport();
            
            // Update UI on main thread
            runOnUiThread(() -> {
                loadingDialog.dismiss();
                showGpsResults(gpsReport);
            });
        }).start();
    }
    
    private boolean hasLocationPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    
    private String generateGpsReport() {
        try {
            LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            StringBuilder report = new StringBuilder();
            
            // Check permissions first
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return "❌ LOCATION PERMISSIONS DENIED\n\nCannot access GPS without location permissions.";
            }
            
            // Check provider status
            boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            boolean passiveEnabled = locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER);
            
            report.append("📡 GPS PROVIDER STATUS\n");
            report.append("GPS Provider: ").append(gpsEnabled ? "✅ Enabled" : "❌ Disabled").append("\n");
            report.append("Network Provider: ").append(networkEnabled ? "✅ Enabled" : "❌ Disabled").append("\n");
            report.append("Passive Provider: ").append(passiveEnabled ? "✅ Enabled" : "❌ Disabled").append("\n\n");
            
            if (!gpsEnabled && !networkEnabled && !passiveEnabled) {
                report.append("❌ NO LOCATION PROVIDERS AVAILABLE\n\n");
                report.append("Please enable location services in device settings.");
                return report.toString();
            }
            
            // Try to get last known locations
            report.append("📍 LAST KNOWN LOCATIONS\n");
            
            // GPS location
            Location gpsLocation = null;
            if (gpsEnabled) {
                try {
                    gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (gpsLocation != null) {
                        report.append("GPS: ").append(formatLocationInfo(gpsLocation)).append("\n");
                    } else {
                        report.append("GPS: No recent location available\n");
                    }
                } catch (Exception e) {
                    report.append("GPS: Error - ").append(e.getMessage()).append("\n");
                }
            }
            
            // Network location
            Location networkLocation = null;
            if (networkEnabled) {
                try {
                    networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    if (networkLocation != null) {
                        report.append("Network: ").append(formatLocationInfo(networkLocation)).append("\n");
                    } else {
                        report.append("Network: No recent location available\n");
                    }
                } catch (Exception e) {
                    report.append("Network: Error - ").append(e.getMessage()).append("\n");
                }
            }
            
            // Passive location
            Location passiveLocation = null;
            if (passiveEnabled) {
                try {
                    passiveLocation = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
                    if (passiveLocation != null) {
                        report.append("Passive: ").append(formatLocationInfo(passiveLocation)).append("\n");
                    } else {
                        report.append("Passive: No recent location available\n");
                    }
                } catch (Exception e) {
                    report.append("Passive: Error - ").append(e.getMessage()).append("\n");
                }
            }
            
            // Best available location
            Location bestLocation = getBestLocation(gpsLocation, networkLocation, passiveLocation);
            if (bestLocation != null) {
                report.append("\n🎯 BEST AVAILABLE LOCATION\n");
                report.append(formatDetailedLocationInfo(bestLocation));
                
                // Google Maps link
                String mapsUrl = String.format(Locale.getDefault(), 
                    "https://www.google.com/maps?q=%.6f,%.6f", 
                    bestLocation.getLatitude(), bestLocation.getLongitude());
                report.append("\n🗺️ Google Maps: ").append(mapsUrl);
            } else {
                report.append("\n❌ NO USABLE LOCATION FOUND\n");
                report.append("Try going outside or near a window for better GPS signal.");
            }
            
            return report.toString();
            
        } catch (Exception e) {
            return "❌ GPS TEST FAILED\n\nError: " + e.getMessage();
        }
    }
    
    private Location getBestLocation(Location gps, Location network, Location passive) {
        // Prefer GPS if available and recent (within 5 minutes)
        long fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000);
        
        if (gps != null && gps.getTime() > fiveMinutesAgo) {
            return gps;
        }
        
        // Otherwise prefer network over passive
        if (network != null) {
            return network;
        }
        
        return passive; // May be null
    }
    
    private String formatLocationInfo(Location location) {
        long ageSeconds = (System.currentTimeMillis() - location.getTime()) / 1000;
        String accuracy = location.hasAccuracy() ? String.format("±%.1fm", location.getAccuracy()) : "unknown accuracy";
        return String.format(Locale.getDefault(), "%.6f, %.6f (%s, %ds ago)", 
            location.getLatitude(), location.getLongitude(), accuracy, ageSeconds);
    }
    
    private String formatDetailedLocationInfo(Location location) {
        StringBuilder info = new StringBuilder();
        
        info.append("Latitude: ").append(String.format("%.6f", location.getLatitude())).append("\n");
        info.append("Longitude: ").append(String.format("%.6f", location.getLongitude())).append("\n");
        info.append("Provider: ").append(location.getProvider()).append("\n");
        
        if (location.hasAccuracy()) {
            info.append("Accuracy: ±").append(String.format("%.1f", location.getAccuracy())).append(" meters\n");
        }
        
        if (location.hasAltitude()) {
            info.append("Altitude: ").append(String.format("%.1f", location.getAltitude())).append(" meters\n");
        }
        
        if (location.hasSpeed()) {
            info.append("Speed: ").append(String.format("%.1f", location.getSpeed())).append(" m/s\n");
        }
        
        if (location.hasBearing()) {
            info.append("Bearing: ").append(String.format("%.1f", location.getBearing())).append("°\n");
        }
        
        // Format timestamp
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        info.append("Timestamp: ").append(sdf.format(new Date(location.getTime()))).append("\n");
        
        long ageSeconds = (System.currentTimeMillis() - location.getTime()) / 1000;
        info.append("Age: ").append(ageSeconds).append(" seconds");
        
        return info.toString();
    }
    
    private void showGpsResults(String gpsReport) {
        new AlertDialog.Builder(this)
            .setTitle("GPS Location Check")
            .setMessage(gpsReport)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy", (dialog, which) -> {
                // Copy to clipboard
                android.content.ClipboardManager clipboard = 
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("GPS Report", gpsReport);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "GPS report copied to clipboard", Toast.LENGTH_SHORT).show();
            })
            .show();
    }
    
    private void setupDecibelReceiver() {
        decibelReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (SoundMonitorService.ACTION_DECIBEL_UPDATE.equals(intent.getAction())) {
                    double decibelLevel = intent.getDoubleExtra(SoundMonitorService.EXTRA_DECIBEL_LEVEL, 0.0);
                    boolean isRecording = intent.getBooleanExtra(SoundMonitorService.EXTRA_IS_RECORDING, false);
                    
                    updateDecibelDisplay(decibelLevel, isRecording);
                }
            }
        };
    }
    
    private void setupRecordingStateReceiver() {
        recordingStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (SoundMonitorService.ACTION_RECORDING_STATE.equals(intent.getAction())) {
                    String state = intent.getStringExtra(SoundMonitorService.EXTRA_RECORDING_STATE);
                    int segmentNumber = intent.getIntExtra(SoundMonitorService.EXTRA_SEGMENT_NUMBER, 0);
                    
                    updateRecordingStateDisplay(state, segmentNumber);
                }
            }
        };
    }
    
    private void updateRecordingStateDisplay(String state, int segmentNumber) {
        // Only update if service is actually running (avoid conflicts with local monitoring)
        if (!isServiceRunning) {
            return; // Ignore service broadcasts when service is stopped
        }
        
        if (SoundMonitorService.STATE_RECORDING_STARTED.equals(state)) {
            if (isAudioOnlyMode) {
                statusText.setText("🎵 RECORDING AUDIO");
                statusText.setTextColor(0xFF9C27B0); // Purple for audio
            } else {
                statusText.setText("🔴 RECORDING #" + segmentNumber);
                statusText.setTextColor(0xFFFF0000); // Bright red for video
            }
            statusText.setTextSize(24); // Large text
            currentDbText.setTextColor(isAudioOnlyMode ? 0xFF9C27B0 : 0xFFFF0000); // Purple or red dB text
        } else if (SoundMonitorService.STATE_RECORDING_TIMEOUT.equals(state)) {
            statusText.setText("⏸️ STOPPED (Timeout)");
            statusText.setTextColor(0xFFFF9800); // Orange
            statusText.setTextSize(20); // Medium text
            currentDbText.setTextColor(0xFF4CAF50); // Green for normal monitoring
        } else if (SoundMonitorService.STATE_RECORDING_STOPPED.equals(state)) {
            if (isAudioOnlyMode) {
                statusText.setText("🎵 Audio Recording Saved");
                statusText.setTextColor(0xFF9C27B0); // Purple
            } else {
                statusText.setText("🎬 Video Recording Saved");
                statusText.setTextColor(0xFF4CAF50); // Green
            }
            statusText.setTextSize(20); // Medium text
            currentDbText.setTextColor(0xFF4CAF50); // Green for normal monitoring
        } else if (SoundMonitorService.STATE_MONITORING.equals(state)) {
            if (isAudioOnlyMode) {
                statusText.setText("🎵 Audio Monitoring");
            } else {
                statusText.setText("👂 Monitoring");
            }
            statusText.setTextColor(0xFF4CAF50); // Green for monitoring
            statusText.setTextSize(18); // Normal text
            currentDbText.setTextColor(0xFF4CAF50); // Green for normal
        }
    }
    
    private void updateDecibelDisplay(double dbLevel, boolean isRecording) {
        // Only update if service is actually running (avoid conflicts with local monitoring)
        if (!isServiceRunning) {
            return; // Ignore service broadcasts when service is stopped
        }
        
        String dbText = String.format("%.1f dB", dbLevel);
        currentDbText.setText(dbText);
        
        // Very clear recording status
        if (isRecording) {
            if (isAudioOnlyMode) {
                statusText.setText("🎵 RECORDING AUDIO");
                statusText.setTextColor(0xFF9C27B0); // Purple for audio
                currentDbText.setTextColor(0xFF9C27B0); // Purple dB text
            } else {
                statusText.setText("🔴 RECORDING");
                statusText.setTextColor(0xFFFF0000); // Bright red for video
                currentDbText.setTextColor(0xFFFF0000); // Red dB text
            }
            statusText.setTextSize(24); // Larger text
        } else {
            statusText.setTextSize(18); // Normal size
            if (dbLevel > soundThreshold) {
                if (isAudioOnlyMode) {
                    statusText.setText("🎵 READY TO RECORD AUDIO");
                    statusText.setTextColor(0xFF9C27B0); // Purple
                    currentDbText.setTextColor(0xFF9C27B0); // Purple dB text
                } else {
                    statusText.setText("🔊 READY TO RECORD");
                    statusText.setTextColor(0xFFFF5722); // Orange
                    currentDbText.setTextColor(0xFFFF5722); // Orange dB text
                }
            } else {
                if (isAudioOnlyMode) {
                    statusText.setText("🎵 Audio Monitoring");
                } else {
                    statusText.setText("👂 Monitoring");
                }
                statusText.setTextColor(0xFF4CAF50); // Green
                currentDbText.setTextColor(0xFF4CAF50); // Green dB text
            }
        }
    }
    
    private double volumeToDb(double volumePercent) {
        // Convert 0-100% to realistic dB scale
        // Quiet: 30-40 dB, Normal: 50-70 dB, Loud: 80+ dB
        if (volumePercent <= 0) return 30;
        return 30 + (volumePercent * 0.6); // Scale to 30-90 dB range
    }
    
    private void startLocalDbMonitoring() {
        if (isLocalMonitoring || !hasAllPermissions()) return;
        
        try {
            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            
            localAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                                             CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
            
            if (localAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                return;
            }
            
            isLocalMonitoring = true;
            localAudioRecord.startRecording();
            
            new Thread(this::monitorLocalDb).start();
            
        } catch (Exception e) {
            // Silent fail for local monitoring
        }
    }
    
    private void stopLocalDbMonitoring() {
        isLocalMonitoring = false;
        
        if (localAudioRecord != null) {
            try {
                localAudioRecord.stop();
                localAudioRecord.release();
            } catch (Exception e) {
                // Silent fail
            }
            localAudioRecord = null;
        }
    }
    
    private void monitorLocalDb() {
        short[] buffer = new short[1024];
        
        while (isLocalMonitoring) {
            try {
                int readSize = localAudioRecord.read(buffer, 0, buffer.length);
                if (readSize > 0) {
                    double dbLevel = calculateDecibelLevel(buffer, readSize);
                    final double finalDbLevel = dbLevel;
                    dbHandler.post(() -> updateLocalDecibelDisplay(finalDbLevel));
                }
                Thread.sleep(100);
            } catch (Exception e) {
                break;
            }
        }
    }
    
    private double calculateDecibelLevel(short[] buffer, int readSize) {
        // Calculate RMS (Root Mean Square)
        double sumSquares = 0;
        for (int i = 0; i < readSize; i++) {
            double sample = buffer[i] / 32768.0; // Normalize to -1.0 to 1.0
            sumSquares += sample * sample;
        }
        double rms = Math.sqrt(sumSquares / readSize);
        
        // Convert RMS to dB
        // 20 * log10(rms) gives us dB relative to full scale
        // Add offset to get realistic dB levels (30-90 dB range)
        if (rms > 0) {
            double db = 20 * Math.log10(rms) + 90; // 90 dB offset for realistic range
            return Math.max(db, 30.0); // Minimum 30 dB (silence)
        } else {
            return 30.0; // Silence = 30 dB
        }
    }
    
    private void updateLocalDecibelDisplay(double dbLevel) {
        // Only update if service is not running (to avoid conflicts)
        if (!isServiceRunning) {
            String dbText = String.format("%.1f dB", dbLevel);
            currentDbText.setText(dbText);
            
            if (dbLevel > soundThreshold) {
                if (isAudioOnlyMode) {
                    currentDbText.setTextColor(0xFF9C27B0); // Purple for audio mode
                    statusText.setText("🎵 READY TO RECORD AUDIO");
                    statusText.setTextColor(0xFF9C27B0);
                } else {
                    currentDbText.setTextColor(0xFFFF5722); // Red/orange for video mode
                    statusText.setText("🔊 READY TO RECORD");
                    statusText.setTextColor(0xFFFF5722);
                }
            } else {
                currentDbText.setTextColor(0xFF4CAF50); // Green for normal
                if (isAudioOnlyMode) {
                    statusText.setText("🎵 Audio Stopped");
                } else {
                    statusText.setText("👂 Stopped");
                }
                statusText.setTextColor(0xFF4CAF50);
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Register receivers when activity becomes visible
        LocalBroadcastManager.getInstance(this).registerReceiver(
            decibelReceiver, 
            new IntentFilter(SoundMonitorService.ACTION_DECIBEL_UPDATE)
        );
        LocalBroadcastManager.getInstance(this).registerReceiver(
            recordingStateReceiver, 
            new IntentFilter(SoundMonitorService.ACTION_RECORDING_STATE)
        );
        
        // Start local monitoring if service is not running and we have permissions
        if (!isServiceRunning && hasAllPermissions()) {
            startLocalDbMonitoring();
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        // Unregister receivers when activity is not visible
        LocalBroadcastManager.getInstance(this).unregisterReceiver(decibelReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(recordingStateReceiver);
        
        // Stop local monitoring when activity is not visible
        stopLocalDbMonitoring();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopLocalDbMonitoring();
        
        // Clean up handlers
        if (thresholdHandler != null) {
            if (thresholdUpdateRunnable != null) {
                thresholdHandler.removeCallbacks(thresholdUpdateRunnable);
            }
            if (timeoutUpdateRunnable != null) {
                thresholdHandler.removeCallbacks(timeoutUpdateRunnable);
            }
        }
    }
}
