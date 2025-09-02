package com.soundmonitor.app;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.io.IOException;
import java.util.List;

public class CameraPreviewActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "CameraPreview";
    
    private SurfaceView cameraPreview;
    private SurfaceHolder surfaceHolder;
    private Camera camera;
    private TextView recordingStatus;
    private TextView currentDbLevel;
    private TextView thresholdInfo;
    private Button backButton;
    private int selectedCameraId = 0; // Default to rear camera
    
    private BroadcastReceiver decibelReceiver;
    private BroadcastReceiver recordingStateReceiver;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_preview);
        
        initViews();
        setupCamera();
        setupReceivers();
    }
    
    private void initViews() {
        cameraPreview = findViewById(R.id.cameraPreview);
        recordingStatus = findViewById(R.id.recordingStatus);
        currentDbLevel = findViewById(R.id.currentDbLevel);
        thresholdInfo = findViewById(R.id.thresholdInfo);
        backButton = findViewById(R.id.backButton);
        
        backButton.setOnClickListener(v -> finish());
        
        // Set up surface holder
        surfaceHolder = cameraPreview.getHolder();
        surfaceHolder.addCallback(this);
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }
    
    private void setupCamera() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        // Get selected camera ID from intent
        Intent intent = getIntent();
        selectedCameraId = intent.getIntExtra("cameraId", 0);
        
        try {
            // Try to open selected camera first
            Log.d(TAG, "Trying to open camera " + selectedCameraId + " (" + (selectedCameraId == 0 ? "Rear" : "Front") + ")");
            camera = Camera.open(selectedCameraId);
            
            if (camera == null) {
                Log.w(TAG, "Selected camera " + selectedCameraId + " failed, trying default camera");
                camera = Camera.open(); // Fallback to default
            } else {
                Log.i(TAG, "✅ Successfully opened camera " + selectedCameraId);
            }
            
            if (camera == null) {
                Toast.makeText(this, "Cannot open camera", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            
            // Set camera parameters for preview
            Camera.Parameters params = camera.getParameters();
            
            // Set proper orientation for preview
            camera.setDisplayOrientation(90); // Portrait mode
            
            // Find best preview size
            List<Camera.Size> previewSizes = params.getSupportedPreviewSizes();
            Camera.Size bestSize = getBestPreviewSize(previewSizes, 640, 480);
            if (bestSize != null) {
                params.setPreviewSize(bestSize.width, bestSize.height);
                Log.d(TAG, "Setting preview size: " + bestSize.width + "x" + bestSize.height);
            }
            
            // Set focus mode for better preview
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            
            camera.setParameters(params);
            
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to open camera " + selectedCameraId + ": " + e.getMessage(), e);
            Toast.makeText(this, "Camera unavailable", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
    
    private Camera.Size getBestPreviewSize(List<Camera.Size> previewSizes, int targetWidth, int targetHeight) {
        if (previewSizes == null || previewSizes.isEmpty()) {
            return null;
        }
        
        Camera.Size bestSize = null;
        double targetRatio = (double) targetWidth / targetHeight;
        double minDiff = Double.MAX_VALUE;
        
        // Find size with closest aspect ratio and reasonable resolution
        for (Camera.Size size : previewSizes) {
            double ratio = (double) size.width / size.height;
            double ratioDiff = Math.abs(ratio - targetRatio);
            
            // Prefer sizes close to target resolution
            if (ratioDiff < minDiff && size.width <= 1280 && size.height <= 720) {
                minDiff = ratioDiff;
                bestSize = size;
            }
        }
        
        // If no good match found, use first available size
        if (bestSize == null && !previewSizes.isEmpty()) {
            bestSize = previewSizes.get(0);
        }
        
        return bestSize;
    }
    
    private void setupReceivers() {
        // Listen for decibel updates
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
        
        // Listen for recording state changes
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
    
    private void updateDecibelDisplay(double dbLevel, boolean isRecording) {
        currentDbLevel.setText(String.format("Current: %.1f dB", dbLevel));
        
        if (isRecording) {
            recordingStatus.setText("🔴 RECORDING");
            recordingStatus.setTextColor(0xFFFF0000); // Red
        } else {
            recordingStatus.setText("👂 MONITORING");
            recordingStatus.setTextColor(0xFF4CAF50); // Green
        }
    }
    
    private void updateRecordingStateDisplay(String state, int segmentNumber) {
        if (SoundMonitorService.STATE_RECORDING_STARTED.equals(state)) {
            recordingStatus.setText("🔴 RECORDING #" + segmentNumber);
            recordingStatus.setTextColor(0xFFFF0000); // Red
        } else if (SoundMonitorService.STATE_RECORDING_TIMEOUT.equals(state)) {
            recordingStatus.setText("⏸️ STOPPED (Timeout)");
            recordingStatus.setTextColor(0xFFFF9800); // Orange
        } else if (SoundMonitorService.STATE_MONITORING.equals(state)) {
            recordingStatus.setText("👂 MONITORING");
            recordingStatus.setTextColor(0xFF4CAF50); // Green
        }
    }
    
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "Surface created");
        if (camera != null) {
            try {
                camera.setPreviewDisplay(holder);
                camera.startPreview();
                Log.d(TAG, "Camera preview started");
            } catch (IOException e) {
                Log.e(TAG, "Error setting camera preview display", e);
            }
        }
    }
    
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "Surface changed: " + width + "x" + height);
        if (camera != null && surfaceHolder.getSurface() != null) {
            try {
                // Stop preview before changing
                camera.stopPreview();
                
                // Reset camera parameters
                Camera.Parameters params = camera.getParameters();
                List<Camera.Size> previewSizes = params.getSupportedPreviewSizes();
                Camera.Size bestSize = getBestPreviewSize(previewSizes, width, height);
                if (bestSize != null) {
                    params.setPreviewSize(bestSize.width, bestSize.height);
                    camera.setParameters(params);
                    Log.d(TAG, "Updated preview size to: " + bestSize.width + "x" + bestSize.height);
                }
                
                // Restart preview
                camera.setPreviewDisplay(holder);
                camera.startPreview();
                Log.d(TAG, "Camera preview restarted successfully");
            } catch (Exception e) {
                Log.e(TAG, "Error restarting camera preview: " + e.getMessage(), e);
            }
        }
    }
    
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "Surface destroyed");
        if (camera != null) {
            try {
                camera.stopPreview();
                Log.d(TAG, "Camera preview stopped for surface destruction");
            } catch (Exception e) {
                Log.w(TAG, "Error stopping preview in surfaceDestroyed: " + e.getMessage());
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        
        // Register broadcast receivers
        LocalBroadcastManager.getInstance(this).registerReceiver(
            decibelReceiver,
            new IntentFilter(SoundMonitorService.ACTION_DECIBEL_UPDATE)
        );
        LocalBroadcastManager.getInstance(this).registerReceiver(
            recordingStateReceiver,
            new IntentFilter(SoundMonitorService.ACTION_RECORDING_STATE)
        );
        
        // Get threshold, timeout, and camera info from intent or defaults
        Intent intent = getIntent();
        int threshold = intent.getIntExtra("threshold", 50);
        int timeout = intent.getIntExtra("timeout", 5);
        String cameraName = (selectedCameraId == 0) ? "Rear" : "Front";
        thresholdInfo.setText("📷 " + cameraName + " Camera | Threshold: " + threshold + " dB | Timeout: " + timeout + "s");
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        
        // Unregister broadcast receivers
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(decibelReceiver);
            LocalBroadcastManager.getInstance(this).unregisterReceiver(recordingStateReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Error unregistering receivers", e);
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.release();
                camera = null;
                Log.d(TAG, "Camera released");
            } catch (Exception e) {
                Log.e(TAG, "Error releasing camera", e);
            }
        }
    }
}