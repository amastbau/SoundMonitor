package com.soundmonitor.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
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

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 200;
    private Button startStopButton, testNetworkButton;
    private SeekBar thresholdSeekBar;
    private TextView thresholdText, statusText, currentDbText;
    private boolean isServiceRunning = false;
    private int soundThreshold = 50;
    private BroadcastReceiver decibelReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        setupListeners();
        setupDecibelReceiver();
        checkPermissions();
    }
    
    private void initViews() {
        startStopButton = findViewById(R.id.startStopButton);
        testNetworkButton = findViewById(R.id.testNetworkButton);
        thresholdSeekBar = findViewById(R.id.thresholdSeekBar);
        thresholdText = findViewById(R.id.thresholdText);
        statusText = findViewById(R.id.statusText);
        currentDbText = findViewById(R.id.currentDbText);
        
        thresholdSeekBar.setMax(100);
        thresholdSeekBar.setProgress(soundThreshold);
        thresholdText.setText("Threshold: " + soundThreshold + " dB");
    }
    
    private void setupListeners() {
        startStopButton.setOnClickListener(v -> toggleService());
        testNetworkButton.setOnClickListener(v -> testNetworkConnection());
        
        thresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                soundThreshold = progress;
                thresholdText.setText("Threshold: " + soundThreshold + " dB");
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
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
        Intent serviceIntent = new Intent(this, SoundMonitorService.class);
        serviceIntent.putExtra("threshold", soundThreshold);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        }
        isServiceRunning = true;
        startStopButton.setText("Stop Monitoring");
        statusText.setText("Status: Monitoring for sounds...");
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
    
    private void updateDecibelDisplay(double decibelLevel, boolean isRecording) {
        String dbText = String.format("Current: %.1f dB", decibelLevel);
        currentDbText.setText(dbText);
        
        // Change color based on threshold and recording status
        if (isRecording) {
            currentDbText.setTextColor(0xFFE91E63); // Pink for recording
            statusText.setText("Status: Recording...");
        } else if (decibelLevel > soundThreshold) {
            currentDbText.setTextColor(0xFFFF5722); // Red/orange for above threshold
            statusText.setText("Status: Monitoring (Above Threshold)");
        } else {
            currentDbText.setTextColor(0xFF4CAF50); // Green for normal
            if (isServiceRunning) {
                statusText.setText("Status: Monitoring");
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Register receiver when activity becomes visible
        LocalBroadcastManager.getInstance(this).registerReceiver(
            decibelReceiver, 
            new IntentFilter(SoundMonitorService.ACTION_DECIBEL_UPDATE)
        );
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        // Unregister receiver when activity is not visible
        LocalBroadcastManager.getInstance(this).unregisterReceiver(decibelReceiver);
    }
}
