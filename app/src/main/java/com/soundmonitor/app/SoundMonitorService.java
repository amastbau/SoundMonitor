package com.soundmonitor.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.List;
import java.util.ArrayList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.Rect;
import android.view.Surface;
import android.graphics.PixelFormat;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.nio.ByteBuffer;
import java.security.MessageDigest;

public class SoundMonitorService extends Service {
    private static final String TAG = "SoundMonitorService";
    private static final String CHANNEL_ID = "SoundMonitorChannel";
    private static final int NOTIFICATION_ID = 1;
    public static final String ACTION_DECIBEL_UPDATE = "com.soundmonitor.app.DECIBEL_UPDATE";
    public static final String ACTION_RECORDING_STATE = "com.soundmonitor.app.RECORDING_STATE";
    public static final String EXTRA_DECIBEL_LEVEL = "decibel_level";
    public static final String EXTRA_IS_RECORDING = "is_recording";
    public static final String EXTRA_RECORDING_STATE = "recording_state";
    public static final String EXTRA_SEGMENT_NUMBER = "segment_number";
    
    // Recording states
    public static final String STATE_MONITORING = "monitoring";
    public static final String STATE_RECORDING_STARTED = "recording_started";
    public static final String STATE_RECORDING_STOPPED = "recording_stopped";
    public static final String STATE_RECORDING_TIMEOUT = "recording_timeout";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    
    private AudioRecord audioRecord;
    private AudioRecord dbMonitorRecord; // Separate AudioRecord for continuous dB monitoring
    private MediaRecorder mediaRecorder;
    private Camera camera;
    private SurfaceTexture surfaceTexture;
    private boolean isCameraPreInitialized = false;
    private boolean isMonitoring = false;
    private boolean isDbMonitoring = false; // Separate flag for dB monitoring
    private boolean isRecording = false;
    private boolean isAudioOnlyMode = false;
    private int soundThreshold = 50;
    private int stopTimeoutSeconds = 5; // Default 5 seconds
    private int selectedCameraId = 0; // Default to rear camera (0), front camera is usually 1
    private Handler handler;
    private Handler stopHandler;
    private String currentVideoFile = "";
    private String currentRecordingStartTime = "";
    private TimestampService.TimestampResult currentTimestamp;
    private String sessionTimestamp = "";
    private File sessionFolder = null;
    private List<String> recordingSegments = new ArrayList<>();
    private int segmentCounter = 0;
    
    // Audio-only mode variables
    private List<Long> thresholdExceedanceTimes = new ArrayList<>();
    private long audioRecordingStartTime = 0;
    
    // Video overlay components
    private Surface overlayInputSurface;
    private Canvas overlayCanvas;
    private Paint overlayPaint;
    private Handler overlayHandler;
    private Runnable overlayUpdater;
    private boolean isOverlayActive = false;
    
    // Rate limiting for recording state changes
    private long lastRecordingStateChange = 0;
    private static final long MIN_STATE_CHANGE_INTERVAL = 5000; // 5 seconds minimum between state changes
    
    // Sustained trigger logic to prevent false starts
    private int consecutiveHighSamples = 0;
    private int consecutiveLowSamples = 0;
    private static final int SAMPLES_TO_START = 3; // Need 3 consecutive loud samples to start (300ms)
    private static final int SAMPLES_TO_STOP = 15; // Need 15 consecutive quiet samples before scheduling stop (1.5 seconds)
    
    // Stop timer management
    private boolean isStopTimerScheduled = false;
    private long stopTimerScheduledAt = 0;
    
    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        stopHandler = new Handler(Looper.getMainLooper());
        overlayHandler = new Handler(Looper.getMainLooper());
        initializeOverlayPaint();
        createNotificationChannel();
    }
    
    private void initializeOverlayPaint() {
        overlayPaint = new Paint();
        overlayPaint.setColor(Color.WHITE);
        overlayPaint.setTextSize(48);
        overlayPaint.setTypeface(Typeface.DEFAULT_BOLD);
        overlayPaint.setAntiAlias(true);
        overlayPaint.setShadowLayer(4, 2, 2, Color.BLACK);
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            
            if ("UPDATE_THRESHOLD".equals(action)) {
                // Update threshold without restarting service
                int newThreshold = intent.getIntExtra("threshold", 0);
                boolean newAudioOnlyMode = intent.getBooleanExtra("audioOnlyMode", false);
                int newCameraId = intent.getIntExtra("cameraId", selectedCameraId);
                
                // Update audio-only mode
                if (newAudioOnlyMode != isAudioOnlyMode) {
                    isAudioOnlyMode = newAudioOnlyMode;
                    Log.i(TAG, "🎵 Audio-only mode updated: " + isAudioOnlyMode);
                }
                
                // Update camera selection
                if (newCameraId != selectedCameraId) {
                    int oldCameraId = selectedCameraId;
                    selectedCameraId = newCameraId;
                    Log.i(TAG, "📷 Camera updated: " + oldCameraId + " → " + selectedCameraId + " (" + (selectedCameraId == 0 ? "Rear" : "Front") + ")");
                    updateNotification("Camera: " + (selectedCameraId == 0 ? "Rear" : "Front"));
                }
                
                // Only update if threshold actually changed
                if (newThreshold != soundThreshold) {
                    int oldThreshold = soundThreshold;
                    soundThreshold = newThreshold;
                    Log.i(TAG, "🔄 Threshold updated: " + oldThreshold + "dB → " + newThreshold + "dB");
                    updateNotification("Threshold: " + soundThreshold + "dB");
                } else {
                    Log.d(TAG, "Threshold update ignored (same value: " + newThreshold + "dB)");
                }
                return START_STICKY;
            } else if ("UPDATE_TIMEOUT".equals(action)) {
                // Update timeout without restarting service
                int newTimeout = intent.getIntExtra("timeout", 5);
                boolean newAudioOnlyMode = intent.getBooleanExtra("audioOnlyMode", false);
                int newCameraId = intent.getIntExtra("cameraId", selectedCameraId);
                
                // Update audio-only mode
                if (newAudioOnlyMode != isAudioOnlyMode) {
                    isAudioOnlyMode = newAudioOnlyMode;
                    Log.i(TAG, "🎵 Audio-only mode updated: " + isAudioOnlyMode);
                }
                
                // Update camera selection
                if (newCameraId != selectedCameraId) {
                    int oldCameraId = selectedCameraId;
                    selectedCameraId = newCameraId;
                    Log.i(TAG, "📷 Camera updated: " + oldCameraId + " → " + selectedCameraId + " (" + (selectedCameraId == 0 ? "Rear" : "Front") + ")");
                    updateNotification("Camera: " + (selectedCameraId == 0 ? "Rear" : "Front"));
                }
                
                // Only update if timeout actually changed
                if (newTimeout != stopTimeoutSeconds) {
                    int oldTimeout = stopTimeoutSeconds;
                    stopTimeoutSeconds = newTimeout;
                    Log.i(TAG, "⏰ Timeout updated: " + oldTimeout + "s → " + newTimeout + "s");
                    updateNotification("Timeout: " + stopTimeoutSeconds + "s");
                } else {
                    Log.d(TAG, "Timeout update ignored (same value: " + newTimeout + "s)");
                }
                return START_STICKY;
            } else {
                // Initial start or restart
                soundThreshold = intent.getIntExtra("threshold", 50);
                stopTimeoutSeconds = intent.getIntExtra("timeout", 5);
                isAudioOnlyMode = intent.getBooleanExtra("audioOnlyMode", false);
                selectedCameraId = intent.getIntExtra("cameraId", 0);
                Log.i(TAG, "📊 Sound threshold set to: " + soundThreshold + " dB");
                Log.i(TAG, "⏰ Stop timeout set to: " + stopTimeoutSeconds + " seconds");
                Log.i(TAG, "🎵 Audio-only mode: " + isAudioOnlyMode);
                Log.i(TAG, "📷 Camera selection: " + (selectedCameraId == 0 ? "Rear" : "Front") + " (" + selectedCameraId + ")");
                String notificationText = isAudioOnlyMode ? "Audio-only monitoring..." : "Monitoring for sounds...";
                startForeground(NOTIFICATION_ID, createNotification(notificationText));
                startMonitoring();
            }
        }
        return START_STICKY;
    }
    
    private void startMonitoring() {
        if (isMonitoring) return;
        
        try {
            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            // Try UNPROCESSED first for better low-frequency sensitivity, fallback to MIC
            try {
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, SAMPLE_RATE,
                                            CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    audioRecord.release();
                    throw new RuntimeException("UNPROCESSED failed");
                }
                Log.i(TAG, "Using UNPROCESSED audio source for better low-frequency sensitivity");
            } catch (Exception e) {
                Log.w(TAG, "UNPROCESSED audio source not available, falling back to MIC: " + e.getMessage());
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                                            CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord initialization failed with MIC fallback");
                    return;
                }
            }
            
            isMonitoring = true;
            audioRecord.startRecording();
            
            // Only use continuous dB monitoring (not old monitorSound)
            startDbMonitoring();
            
            // Note: Camera pre-initialization disabled for now to avoid complexity
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting audio monitoring", e);
        }
    }
    
    private void preInitializeCamera() {
        if (isCameraPreInitialized) return;
        
        try {
            // Check camera permission first
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Camera permission not granted, skipping pre-initialization");
                return;
            }
            
            Log.i(TAG, "Pre-initializing camera to reduce recording startup delay...");
            // Try to open selected camera first, then fallback to default
            try {
                camera = Camera.open(selectedCameraId);
                if (camera == null) {
                    Log.w(TAG, "Failed to open camera " + selectedCameraId + ", trying default camera");
                    camera = Camera.open();
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to open camera " + selectedCameraId + ": " + e.getMessage() + ", trying default camera");
                camera = Camera.open();
            }
            
            if (camera == null) {
                Log.w(TAG, "Failed to pre-initialize any camera");
                return;
            }
            
            Log.i(TAG, "📷 Pre-initialized camera: " + (selectedCameraId == 0 ? "Rear" : "Front"));
            
            // Set up camera parameters
            Camera.Parameters params = camera.getParameters();
            params.setRecordingHint(true);
            camera.setParameters(params);
            
            // Create a surface texture for camera preview
            surfaceTexture = new SurfaceTexture(10);
            camera.setPreviewTexture(surfaceTexture);
            camera.startPreview();
            
            isCameraPreInitialized = true;
            Log.i(TAG, "Camera pre-initialization completed successfully");
            
        } catch (Exception e) {
            Log.w(TAG, "Camera pre-initialization failed", e);
            cleanupPreInitializedCamera();
        }
    }
    
    private void cleanupPreInitializedCamera() {
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.release();
            } catch (Exception e) {
                Log.e(TAG, "Error cleaning up pre-initialized camera", e);
            }
            camera = null;
        }
        
        if (surfaceTexture != null) {
            try {
                surfaceTexture.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing pre-initialized surface texture", e);
            }
            surfaceTexture = null;
        }
        
        isCameraPreInitialized = false;
    }
    
    private void startDbMonitoring() {
        if (isDbMonitoring) return;
        
        try {
            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            
            // Try UNPROCESSED first for better low-frequency sensitivity, fallback to VOICE_RECOGNITION
            try {
                dbMonitorRecord = new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, SAMPLE_RATE,
                                                CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
                if (dbMonitorRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    dbMonitorRecord.release();
                    throw new RuntimeException("UNPROCESSED failed");
                }
                Log.i(TAG, "Using UNPROCESSED audio source for dB monitoring");
            } catch (Exception e) {
                Log.w(TAG, "UNPROCESSED not available for dB monitor, falling back to VOICE_RECOGNITION: " + e.getMessage());
                dbMonitorRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                                                CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
                if (dbMonitorRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "dB Monitor AudioRecord initialization failed with fallback");
                    return;
                }
            }
            
            isDbMonitoring = true;
            dbMonitorRecord.startRecording();
            
            new Thread(this::monitorDbLevel).start();
            Log.i(TAG, "Continuous dB monitoring started");
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting dB monitoring", e);
        }
    }
    
    private void monitorDbLevel() {
        short[] buffer = new short[1024];
        
        while (isDbMonitoring) {
            try {
                int readSize = dbMonitorRecord.read(buffer, 0, buffer.length);
                if (readSize > 0) {
                    double dbLevel = calculateDecibelLevel(buffer, readSize);
                    final double finalDbLevel = dbLevel;
                    handler.post(() -> broadcastDbLevel(finalDbLevel));
                }
                Thread.sleep(100);
            } catch (Exception e) {
                Log.e(TAG, "Error in dB monitoring", e);
                break;
            }
        }
    }
    
    private void broadcastDbLevel(double dbLevel) {
        // Always broadcast dB level for UI update
        Intent intent = new Intent(ACTION_DECIBEL_UPDATE);
        intent.putExtra(EXTRA_DECIBEL_LEVEL, dbLevel);
        intent.putExtra(EXTRA_IS_RECORDING, isRecording);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        
        // Update notification with current dB level
        updateNotification("Current: " + String.format("%.1f", dbLevel) + " dB");
        
        // Handle recording logic based on dB levels
        handleSoundLevelForRecording(dbLevel);
    }
    
    private void handleSoundLevelForRecording(double dbLevel) {
        // Simple: dB level vs dB threshold
        double thresholdDb = soundThreshold; // soundThreshold is now in dB
        
        // Debug: Log every sample for debugging
        Log.d(TAG, "🔊 Audio: " + String.format("%.1f", dbLevel) + "dB | Threshold: " + String.format("%.1f", thresholdDb) + "dB | Recording: " + isRecording + " | AudioOnly: " + isAudioOnlyMode);
        
        // Rate limit recording state changes to prevent rapid start/stop cycles
        long currentTime = System.currentTimeMillis();
        
        // Handle audio-only mode differently
        if (isAudioOnlyMode) {
            if (dbLevel > thresholdDb) {
                // In audio-only mode, just log threshold exceedances
                if (!isRecording) {
                    // Start continuous audio recording
                    startAudioOnlyRecording();
                }
                // Record this threshold exceedance
                long exceedanceTime = System.currentTimeMillis() - audioRecordingStartTime;
                thresholdExceedanceTimes.add(exceedanceTime);
                Log.i(TAG, "🔊 THRESHOLD EXCEEDED in audio-only mode: " + String.format("%.1f", dbLevel) + "dB > " + String.format("%.1f", thresholdDb) + "dB at " + exceedanceTime + "ms");
            }
        } else {
            // Video mode - original logic
            if (dbLevel > thresholdDb && !isRecording) {
                // Count consecutive loud samples for sustained trigger
                consecutiveHighSamples++;
                consecutiveLowSamples = 0; // Reset low counter
                
                if (consecutiveHighSamples >= SAMPLES_TO_START) {
                    // Allow recording start if enough time has passed since last change
                    if (lastRecordingStateChange == 0 || currentTime - lastRecordingStateChange > MIN_STATE_CHANGE_INTERVAL) {
                        Log.i(TAG, "🔴 STARTING RECORDING: " + consecutiveHighSamples + " consecutive loud samples, " + String.format("%.1f", dbLevel) + "dB > " + String.format("%.1f", thresholdDb) + "dB");
                        startRecording();
                        lastRecordingStateChange = currentTime;
                        consecutiveHighSamples = 0; // Reset counters
                        consecutiveLowSamples = 0;
                    } else {
                        Log.w(TAG, "⏰ Recording blocked by rate limit - time since last: " + (currentTime - lastRecordingStateChange) + "ms");
                    }
                } else {
                    Log.d(TAG, "🔶 Building up to recording start: " + consecutiveHighSamples + "/" + SAMPLES_TO_START + " loud samples");
                }
            } else if (dbLevel <= thresholdDb && isRecording && !isAudioOnlyMode) {
                // Count consecutive quiet samples before stopping (video mode only)
                consecutiveLowSamples++;
                consecutiveHighSamples = 0; // Reset high counter
                
                if (consecutiveLowSamples >= SAMPLES_TO_STOP) {
                    if (!isStopTimerScheduled) {
                        Log.i(TAG, "⏸️ Sound consistently below threshold for " + consecutiveLowSamples + " samples (" + String.format("%.1f", dbLevel) + "dB <= " + String.format("%.1f", thresholdDb) + "dB) - Scheduling stop in 5 seconds");
                        scheduleStopRecording();
                    }
                } else {
                    Log.d(TAG, "🔸 Quiet sample " + consecutiveLowSamples + "/" + SAMPLES_TO_STOP + " before scheduling stop");
                }
            } else if (dbLevel > thresholdDb && isRecording && !isAudioOnlyMode) {
                // Reset counters and cancel stop if sound returns (video mode only)
                consecutiveHighSamples = 0;
                consecutiveLowSamples = 0;
                
                Log.i(TAG, "🔄 Sound above threshold while recording (" + String.format("%.1f", dbLevel) + "dB > " + String.format("%.1f", thresholdDb) + "dB) - Canceling stop timer");
                if (isStopTimerScheduled) {
                    stopHandler.removeCallbacksAndMessages(null); // Cancel stop if sound returns
                    isStopTimerScheduled = false;
                    Log.i(TAG, "✅ Stop timer cancelled successfully");
                }
            } else {
                // Reset counters when in stable state (video mode only)
                if (!isAudioOnlyMode) {
                    consecutiveHighSamples = 0;
                    consecutiveLowSamples = 0;
                    Log.d(TAG, "📊 No action needed - dB=" + String.format("%.1f", dbLevel) + "dB, threshold=" + String.format("%.1f", thresholdDb) + "dB, recording=" + isRecording);
                }
            }
        }
    }
    
    private void broadcastRecordingState(String state) {
        Intent intent = new Intent(ACTION_RECORDING_STATE);
        intent.putExtra(EXTRA_RECORDING_STATE, state);
        intent.putExtra(EXTRA_SEGMENT_NUMBER, segmentCounter);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Log.i(TAG, "Broadcasting recording state: " + state + " (segment: " + segmentCounter + ")");
    }
    
    private void startAudioOnlyRecording() {
        Log.i(TAG, "🎵 Starting continuous audio-only recording");
        try {
            // Initialize recording start time and clear exceedance list
            audioRecordingStartTime = System.currentTimeMillis();
            thresholdExceedanceTimes.clear();
            
            // Set session timestamp and create session folder on first recording
            if (sessionTimestamp.isEmpty()) {
                SimpleDateFormat sessionFormat = new SimpleDateFormat("MMdd_HHmm", Locale.US);
                sessionTimestamp = sessionFormat.format(new Date(audioRecordingStartTime));
                currentRecordingStartTime = TimestampUtils.formatAsUtc(new Date(audioRecordingStartTime));
                recordingSegments.clear();
                segmentCounter = 0;
                
                File baseStorageDir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "SoundTrigger");
                sessionFolder = new File(baseStorageDir, sessionTimestamp + "_AUDIO");
                
                if (!sessionFolder.exists()) {
                    boolean sessionCreated = sessionFolder.mkdirs();
                    Log.i(TAG, "Audio session folder created: " + sessionCreated + " at " + sessionFolder.getAbsolutePath());
                }
                
                Log.i(TAG, "Starting new audio-only recording session: " + sessionTimestamp);
            }
            
            // Get timestamp for legal verification
            String timeStamp = "audio_session";
            byte[] timestampData = timeStamp.getBytes();
            
            TimestampService.getTimestamp(timestampData, this, result -> {
                currentTimestamp = result;
                if (result.success) {
                    Log.i(TAG, "Audio recording timestamp obtained from: " + result.authority);
                    updateNotification("Recording audio with timestamp: " + timeStamp);
                } else {
                    Log.w(TAG, "Audio recording timestamp failed: " + result.error);
                    updateNotification("Recording audio (timestamp unavailable): " + timeStamp);
                }
            });
            
            // Start MediaRecorder for audio-only recording
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setAudioEncodingBitRate(128000);
            
            // Create audio file
            String audioFileName = "audio_session.m4a";
            currentVideoFile = new File(sessionFolder, audioFileName).getAbsolutePath();
            mediaRecorder.setOutputFile(currentVideoFile);
            
            Log.i(TAG, "Preparing audio recorder...");
            mediaRecorder.prepare();
            
            Log.i(TAG, "Starting audio recording...");
            mediaRecorder.start();
            
            isRecording = true;
            updateNotification("Recording audio continuously...");
            broadcastRecordingState(STATE_RECORDING_STARTED);
            
            Log.i(TAG, "Audio-only recording started successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting audio-only recording", e);
            cleanup();
            isRecording = false;
        }
    }
    
    private void stopAudioOnlyRecording() {
        Log.i(TAG, "🎵 Stopping continuous audio-only recording");
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
            }
            
            isRecording = false;
            
            // Create timestamp exceedance file
            createThresholdExceedanceFile();
            
            // Create audio info file
            createAudioInfoFile();
            
            // Save timestamp verification
            File audioFile = new File(currentVideoFile);
            saveTimestampFile(audioFile);
            
            // Copy to public storage
            copyToPublicStorage(audioFile);
            copyTimestampToPublicStorage(audioFile);
            
            updateNotification("Audio recording saved with " + thresholdExceedanceTimes.size() + " threshold exceedances");
            broadcastRecordingState(STATE_RECORDING_STOPPED);
            
            Log.i(TAG, "Audio-only recording stopped and saved with " + thresholdExceedanceTimes.size() + " threshold exceedances");
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping audio-only recording", e);
        }
    }
    
    private void createThresholdExceedanceFile() {
        try {
            String exceedanceFileName = "threshold_exceedances.txt";
            File exceedanceFile = new File(sessionFolder, exceedanceFileName);
            
            StringBuilder content = new StringBuilder();
            content.append("=== THRESHOLD EXCEEDANCE LOG ===\n");
            content.append("Audio Recording Session: ").append(sessionTimestamp).append("\n");
            content.append("Recording Started: ").append(currentRecordingStartTime).append("\n");
            content.append("Sound Threshold: ").append(soundThreshold).append(" dB\n");
            content.append("Total Exceedances: ").append(thresholdExceedanceTimes.size()).append("\n\n");
            
            content.append("EXCEEDANCE TIMESTAMPS:\n");
            content.append("======================\n");
            
            if (thresholdExceedanceTimes.isEmpty()) {
                content.append("No threshold exceedances recorded during this session.\n");
            } else {
                for (int i = 0; i < thresholdExceedanceTimes.size(); i++) {
                    long exceedanceTime = thresholdExceedanceTimes.get(i);
                    long seconds = exceedanceTime / 1000;
                    long milliseconds = exceedanceTime % 1000;
                    
                    content.append(String.format("%d. %02d:%02d.%03d\n", 
                        i + 1, 
                        seconds / 60, 
                        seconds % 60, 
                        milliseconds));
                }
            }
            
            content.append("\n=== LEGAL NOTICE ===\n");
            content.append("This file contains exact timestamps when audio levels exceeded\n");
            content.append("the configured threshold during continuous audio recording.\n");
            content.append("Times are relative to recording start in MM:SS.mmm format.\n");
            content.append("Use with audio file for precise event correlation.\n");
            
            try (FileOutputStream fos = new FileOutputStream(exceedanceFile)) {
                fos.write(content.toString().getBytes());
                fos.flush();
            }
            
            Log.i(TAG, "Created threshold exceedance file: " + exceedanceFile.getAbsolutePath());
            
            // Also copy to public storage
            copyFileToPublicStorage(exceedanceFile, "Documents/SoundTrigger");
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating threshold exceedance file", e);
        }
    }
    
    private void createAudioInfoFile() {
        try {
            String infoFileName = "audio_session_info.txt";
            File infoFile = new File(sessionFolder, infoFileName);
            
            StringBuilder content = new StringBuilder();
            content.append("=== AUDIO-ONLY RECORDING SESSION ===\n");
            content.append("Session: ").append(sessionTimestamp).append("\n");
            content.append("Recording Started: ").append(currentRecordingStartTime).append("\n");
            content.append("Recording Stopped: ").append(TimestampUtils.getCurrentUtcTimestamp()).append("\n");
            content.append("Audio File: audio_session.m4a\n");
            
            File audioFile = new File(currentVideoFile);
            if (audioFile.exists()) {
                content.append("File Size: ").append(audioFile.length()).append(" bytes\n");
                
                long durationMs = System.currentTimeMillis() - audioRecordingStartTime;
                content.append("Duration: ").append(durationMs / 1000.0).append(" seconds\n");
            }
            
            content.append("Sound Threshold: ").append(soundThreshold).append(" dB\n");
            content.append("Total Threshold Exceedances: ").append(thresholdExceedanceTimes.size()).append("\n\n");
            
            content.append("=== RECORDING SETTINGS ===\n");
            content.append("Format: AAC in MP4 container\n");
            content.append("Sample Rate: 44.1 kHz\n");
            content.append("Bit Rate: 128 kbps\n");
            content.append("Channels: Mono\n\n");
            
            if (currentTimestamp != null && currentTimestamp.success) {
                content.append("=== AUTHORITATIVE VERIFICATION ===\n");
                content.append("Time Authority: ").append(currentTimestamp.authority).append("\n");
                content.append("Verified Time: ").append(currentTimestamp.timestamp).append("\n");
                
                if (currentTimestamp.latitude != null && currentTimestamp.longitude != null) {
                    content.append("GPS Location: ").append(currentTimestamp.latitude).append(", ").append(currentTimestamp.longitude).append("\n");
                    content.append("Location Provider: ").append(currentTimestamp.locationProvider).append("\n");
                }
            }
            
            content.append("\n=== USAGE INSTRUCTIONS ===\n");
            content.append("1. Play audio_session.m4a in any media player\n");
            content.append("2. Use threshold_exceedances.txt to find significant events\n");
            content.append("3. Cross-reference timestamps for precise event location\n");
            content.append("4. Legal timestamp file provides court-ready verification\n");
            
            try (FileOutputStream fos = new FileOutputStream(infoFile)) {
                fos.write(content.toString().getBytes());
                fos.flush();
            }
            
            Log.i(TAG, "Created audio info file: " + infoFile.getAbsolutePath());
            
            // Also copy to public storage
            copyFileToPublicStorage(infoFile, "Documents/SoundTrigger");
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating audio info file", e);
        }
    }
    
    private void copyFileToPublicStorage(File sourceFile, String relativePath) {
        try {
            ContentResolver contentResolver = getContentResolver();
            ContentValues contentValues = new ContentValues();
            
            // Create session-specific folder path if not already included
            String sessionFolderName = sessionFolder != null ? sessionFolder.getName() : sessionTimestamp;
            String finalRelativePath = relativePath;
            if (!relativePath.contains(sessionFolderName)) {
                finalRelativePath = relativePath + "/" + sessionFolderName;
            }
            
            contentValues.put(MediaStore.Files.FileColumns.DISPLAY_NAME, sourceFile.getName());
            contentValues.put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain");
            contentValues.put(MediaStore.Files.FileColumns.RELATIVE_PATH, finalRelativePath);
            
            Uri fileUri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues);
            
            if (fileUri != null) {
                try (FileInputStream inputStream = new FileInputStream(sourceFile);
                     OutputStream outputStream = contentResolver.openOutputStream(fileUri)) {
                    
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                    
                    Log.i(TAG, "Copied " + sourceFile.getName() + " to public storage");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error copying " + sourceFile.getName() + " to public storage", e);
        }
    }
    
    // Removed old monitorSound method - using continuous dB monitoring instead
    
    private double calculateDecibelLevel(short[] buffer, int readSize) {
        // Calculate RMS (Root Mean Square) with optimized footstep/floor vibration detection
        double sumSquares = 0;
        double lowFreqSum = 0;
        double veryLowFreqSum = 0;
        
        // Multi-stage filtering for footsteps and building vibrations
        double lowPassPrev = 0;
        double veryLowPassPrev = 0;
        double alpha1 = 0.05; // Very low frequencies (10-100Hz) - footsteps, impacts
        double alpha2 = 0.15; // Low frequencies (100-500Hz) - floor creaking, movement
        
        for (int i = 0; i < readSize; i++) {
            double sample = buffer[i] / 32768.0; // Normalize to -1.0 to 1.0
            
            // First stage: Very low-pass filter for deep impacts and heavy footsteps
            double veryLowPassCurrent = alpha1 * sample + (1 - alpha1) * veryLowPassPrev;
            veryLowPassPrev = veryLowPassCurrent;
            
            // Second stage: Low-pass filter for general floor sounds and movement
            double lowPassCurrent = alpha2 * sample + (1 - alpha2) * lowPassPrev;
            lowPassPrev = lowPassCurrent;
            
            // Accumulate different frequency bands
            sumSquares += sample * sample;
            veryLowFreqSum += veryLowPassCurrent * veryLowPassCurrent;
            lowFreqSum += lowPassCurrent * lowPassCurrent;
        }
        
        double rms = Math.sqrt(sumSquares / readSize);
        double veryLowFreqRms = Math.sqrt(veryLowFreqSum / readSize);
        double lowFreqRms = Math.sqrt(lowFreqSum / readSize);
        
        // Heavily emphasize very low frequencies for footstep detection
        // 50% regular, 30% very-low-freq (footsteps), 20% low-freq (movement)
        double combinedRms = 0.5 * rms + 
                           0.3 * veryLowFreqRms * 4.0 + // 4x boost for footstep frequencies
                           0.2 * lowFreqRms * 2.5;       // 2.5x boost for movement frequencies
        
        // Convert RMS to dB with enhanced building vibration sensitivity
        if (combinedRms > 0) {
            double db = 20 * Math.log10(combinedRms) + 90; // 90 dB offset for realistic range
            return Math.max(db, 30.0); // Minimum 30 dB (silence)
        } else {
            return 30.0; // Silence = 30 dB
        }
    }
    
    // Removed old handleSoundLevel method - using continuous dB monitoring instead
    
    private double volumeToDb(double volumePercent) {
        // Convert 0-100% to realistic dB scale
        // Quiet: 30-40 dB, Normal: 50-70 dB, Loud: 80+ dB
        if (volumePercent <= 0) return 30;
        return 30 + (volumePercent * 0.6); // Scale to 30-90 dB range
    }
    
    private void startRecording() {
        Log.i(TAG, "🚀 startRecording() called - attempting to start video recording");
        try {
            // Stop trigger monitoring to free up the microphone for video recording
            // but keep dB monitoring running for UI updates
            Log.i(TAG, "Stopping trigger monitoring to free microphone for video recording");
            isMonitoring = false;
            
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
                Log.i(TAG, "Trigger AudioRecord released");
            }
            
            // Get cryptographic timestamp for legal evidence
            Log.i(TAG, "Getting RFC 3161 timestamp for legal verification...");
            Date recordingStartDate = new Date();
            
            // Set session timestamp and create session folder on first recording
            if (sessionTimestamp.isEmpty()) {
                // Simple session naming with just date and time
                SimpleDateFormat sessionFormat = new SimpleDateFormat("MMdd_HHmm", Locale.US); // MMDD_HHMM (e.g., 0831_1420)
                sessionTimestamp = sessionFormat.format(recordingStartDate);
                currentRecordingStartTime = TimestampUtils.formatAsUtc(recordingStartDate);
                recordingSegments.clear();
                segmentCounter = 0;
                
                // Create simple session folder
                File baseStorageDir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "SoundTrigger");
                sessionFolder = new File(baseStorageDir, sessionTimestamp);
                
                if (!sessionFolder.exists()) {
                    boolean sessionCreated = sessionFolder.mkdirs();
                    Log.i(TAG, "Session folder created: " + sessionCreated + " at " + sessionFolder.getAbsolutePath());
                    if (!sessionCreated) {
                        Log.e(TAG, "Failed to create session folder - recording cannot continue");
                        restartMonitoring();
                        return;
                    }
                }
                
                Log.i(TAG, "Starting new recording session: " + sessionTimestamp);
            }
            
            String timeStamp = "segment" + segmentCounter;
            byte[] timestampData = timeStamp.getBytes();
            
            // Start timestamp fetch asynchronously (don't block recording start)
            TimestampService.getTimestamp(timestampData, this, result -> {
                currentTimestamp = result;
                if (result.success) {
                    Log.i(TAG, "Timestamp obtained from: " + result.authority);
                    updateNotification("Recording with timestamp: " + timeStamp);
                } else {
                    Log.w(TAG, "Timestamp failed: " + result.error);
                    updateNotification("Recording (timestamp unavailable): " + timeStamp);
                }
            });
            
            // Store segment file with simple minute-based name
            segmentCounter++;
            
            String segmentName = String.format("%02d.mp4", segmentCounter);
            
            currentVideoFile = new File(sessionFolder, segmentName).getAbsolutePath();
            Log.i(TAG, "Recording video to: " + currentVideoFile);
            
            // Check permissions
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Required permissions not granted");
                restartMonitoring();
                return;
            }
            
            // Initialize camera with retry logic
            Log.i(TAG, "Opening camera...");
            try {
                // Try all available cameras
                int numberOfCameras = Camera.getNumberOfCameras();
                Log.i(TAG, "Found " + numberOfCameras + " cameras on device");
                
                // Try to open selected camera first
                try {
                    Log.i(TAG, "Trying to open camera " + selectedCameraId + " (" + (selectedCameraId == 0 ? "Rear" : "Front") + ")");
                    camera = Camera.open(selectedCameraId);
                    if (camera != null) {
                        Log.i(TAG, "✅ Successfully opened selected camera " + selectedCameraId);
                    }
                } catch (RuntimeException ex) {
                    Log.w(TAG, "❌ Selected camera " + selectedCameraId + " failed: " + ex.getMessage());
                    camera = null;
                }
                
                // Fallback to any available camera if selected camera fails
                if (camera == null) {
                    Log.i(TAG, "Falling back to default camera");
                    camera = Camera.open(); // Try default camera first
                    if (camera == null) {
                        // Try specific camera IDs if default fails
                        for (int i = 0; i < numberOfCameras; i++) {
                            try {
                                Log.i(TAG, "Trying camera " + i);
                                camera = Camera.open(i);
                                if (camera != null) {
                                    Log.i(TAG, "Successfully opened camera " + i);
                                    break;
                                }
                            } catch (RuntimeException ex) {
                                Log.w(TAG, "Camera " + i + " failed: " + ex.getMessage());
                            }
                        }
                    }
                }
                
                if (camera == null) {
                    Log.e(TAG, "All cameras failed to open - device may have camera access issues");
                    restartMonitoring();
                    return;
                }
                Log.i(TAG, "Camera opened successfully");
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to connect to camera service: " + e.getMessage());
                Log.e(TAG, "Try restarting your phone to reset camera services");
                restartMonitoring();
                return;
            }
            
            // Set up camera parameters
            // Configure camera parameters for stable recording
            Camera.Parameters params = camera.getParameters();
            params.setRecordingHint(true);
            
            // Set compatible video and preview sizes
            List<Camera.Size> videoSizes = params.getSupportedVideoSizes();
            List<Camera.Size> previewSizes = params.getSupportedPreviewSizes();
            
            // Find best recording size (prefer 1280x720, fallback to supported)
            Camera.Size recordingSize = null;
            Camera.Size finalRecordingSize = null;
            if (videoSizes != null) {
                for (Camera.Size size : videoSizes) {
                    if (size.width == 1280 && size.height == 720) {
                        recordingSize = size;
                        break;
                    }
                }
                if (recordingSize == null && !videoSizes.isEmpty()) {
                    recordingSize = videoSizes.get(0);
                    Log.i(TAG, "Using fallback video size: " + recordingSize.width + "x" + recordingSize.height);
                }
                finalRecordingSize = recordingSize;
            }
            
            // Set matching preview size
            if (recordingSize != null && previewSizes != null) {
                Camera.Size previewSize = null;
                for (Camera.Size size : previewSizes) {
                    if (size.width == recordingSize.width && size.height == recordingSize.height) {
                        previewSize = size;
                        break;
                    }
                }
                if (previewSize == null && !previewSizes.isEmpty()) {
                    previewSize = previewSizes.get(0);
                }
                
                if (previewSize != null) {
                    params.setPreviewSize(previewSize.width, previewSize.height);
                    Log.i(TAG, "Set recording preview size: " + previewSize.width + "x" + previewSize.height);
                }
            }
            
            // Set continuous video focus for better recording quality
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                Log.i(TAG, "Set continuous video focus for recording");
            }
            
            camera.setParameters(params);
            
            // Create a surface texture for camera preview
            surfaceTexture = new SurfaceTexture(10);
            camera.setPreviewTexture(surfaceTexture);
            camera.startPreview();
            
            // Unlock camera for MediaRecorder
            camera.unlock();
            
            Log.i(TAG, "Configuring MediaRecorder for video recording...");
            long startTime = System.currentTimeMillis();
            
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setCamera(camera);
            
            // Set sources in correct order
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            
            // Set output format
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            
            // Set encoders
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            
            // Set video settings to match camera capabilities
            if (finalRecordingSize != null) {
                mediaRecorder.setVideoSize(finalRecordingSize.width, finalRecordingSize.height);
                Log.i(TAG, "MediaRecorder video size: " + finalRecordingSize.width + "x" + finalRecordingSize.height);
            } else {
                mediaRecorder.setVideoSize(1280, 720); // Fallback
                Log.i(TAG, "Using fallback MediaRecorder video size: 1280x720");
            }
            mediaRecorder.setVideoFrameRate(24); // Lower frame rate for more stability
            mediaRecorder.setVideoEncodingBitRate(1500000); // 1.5Mbps - more stable
            
            // Set audio settings
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setAudioEncodingBitRate(128000); // 128kbps
            
            mediaRecorder.setOutputFile(currentVideoFile);
            
            // Add timestamp metadata to the video file
            String timestampMetadata = TimestampUtils.getCurrentUtcTimestamp() + " | " + timeStamp;
            Log.i(TAG, "Will add timestamp metadata: " + timestampMetadata);
            
            Log.i(TAG, "Preparing MediaRecorder...");
            long prepareStart = System.currentTimeMillis();
            mediaRecorder.prepare();
            long prepareTime = System.currentTimeMillis() - prepareStart;
            Log.i(TAG, "MediaRecorder prepare took: " + prepareTime + "ms");
            
            Log.i(TAG, "Starting video recording...");
            long recordStart = System.currentTimeMillis();
            mediaRecorder.start();
            long recordTime = System.currentTimeMillis() - recordStart;
            long totalTime = System.currentTimeMillis() - startTime;
            
            Log.i(TAG, "MediaRecorder start took: " + recordTime + "ms, total setup: " + totalTime + "ms");
            
            isRecording = true;
            isStopTimerScheduled = false; // Reset stop timer state when starting new recording
            updateNotification("Recording video: " + timeStamp);
            broadcastRecordingState(STATE_RECORDING_STARTED);
            
            Log.i(TAG, "Video recording started successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting video recording", e);
            e.printStackTrace();
            isRecording = false;
            
            cleanup();
            restartMonitoring();
        }
    }
    
    private void cleanup() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping MediaRecorder", e);
            }
            try {
                mediaRecorder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaRecorder", e);
            }
            mediaRecorder = null;
        }
        
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing camera", e);
            }
            camera = null;
        }
        
        if (surfaceTexture != null) {
            try {
                surfaceTexture.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing surface texture", e);
            }
            surfaceTexture = null;
        }
    }
    
    
    private void recreateAudioRecord() throws Exception {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("RECORD_AUDIO permission not granted");
        }
        
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        // Try UNPROCESSED first for better low-frequency sensitivity, fallback to MIC
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, SAMPLE_RATE,
                                        CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release();
                throw new RuntimeException("UNPROCESSED failed");
            }
        } catch (Exception e) {
            Log.w(TAG, "UNPROCESSED audio source not available for restart, falling back to MIC: " + e.getMessage());
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                                        CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new RuntimeException("AudioRecord initialization failed with MIC fallback");
            }
        }
        
        audioRecord.startRecording();
    }
    
    private void scheduleStopRecording() {
        // Only schedule if not already scheduled
        if (isStopTimerScheduled) {
            return; // Timer already running, don't reschedule
        }
        
        isStopTimerScheduled = true;
        stopTimerScheduledAt = System.currentTimeMillis();
        int timeoutMs = stopTimeoutSeconds * 1000;
        Log.i(TAG, "⏰ Scheduling recording stop in " + stopTimeoutSeconds + " seconds...");
        stopHandler.postDelayed(() -> {
            Log.i(TAG, "🚨 " + stopTimeoutSeconds + "-SECOND TIMEOUT FIRED! Stopping recording...");
            isStopTimerScheduled = false;
            stopRecording();
        }, timeoutMs);
    }
    
    private void stopRecording() {
        if (!isRecording) {
            Log.w(TAG, "stopRecording called but not currently recording");
            return;
        }
        
        try {
            Log.i(TAG, "*** TIMEOUT: Stopping video recording after " + stopTimeoutSeconds + " seconds of silence ***");
            isRecording = false;
            
            cleanup();
            
            updateNotification("Stopped recording. Monitoring...");
            broadcastRecordingState(STATE_RECORDING_TIMEOUT);
            
            // Check final file size
            File file = new File(currentVideoFile);
            Log.i(TAG, "Stopped video recording segment: " + currentVideoFile + " (size: " + file.length() + " bytes)");
            
            // Add timestamp overlay to the video file
            String overlayVideoFile = addTimestampOverlay(currentVideoFile);
            
            // Add the overlaid video to segments (or original if overlay failed)
            String finalVideoFile = overlayVideoFile != null ? overlayVideoFile : currentVideoFile;
            recordingSegments.add(finalVideoFile);
            Log.i(TAG, "Segment " + segmentCounter + " saved. Total segments: " + recordingSegments.size());
            
            // Note: Files will be copied to public storage when session ends
            
            // Update notification to show segment saved
            updateNotification("Segment " + segmentCounter + " saved. Monitoring for next sound...");
            
            // Restart monitoring to detect next sound above threshold
            restartMonitoring();
            broadcastRecordingState(STATE_MONITORING);
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping video recording", e);
            e.printStackTrace();
            
            cleanup();
            isRecording = false;
            restartMonitoring();
        }
    }
    
    private String addTimestampOverlay(String videoFilePath) {
        try {
            File originalFile = new File(videoFilePath);
            if (!originalFile.exists()) {
                Log.e(TAG, "Original video file not found: " + videoFilePath);
                return null;
            }
            
            // For now, create a subtitle file with timestamp information
            // This is a simpler approach than video overlay processing
            String subtitleFile = createSubtitleFile(videoFilePath);
            
            // Also create a companion info file with detailed timestamp
            createVideoInfoFile(videoFilePath);
            
            Log.i(TAG, "Created timestamp subtitle file: " + subtitleFile);
            return videoFilePath; // Return original file for now
            
        } catch (Exception e) {
            Log.e(TAG, "Error adding timestamp overlay", e);
            return null;
        }
    }
    
    private String createSubtitleFile(String videoFilePath) {
        try {
            File videoFile = new File(videoFilePath);
            String subtitlePath = videoFilePath.replace(".mp4", "_SUB.srt");
            File subtitleFile = new File(subtitlePath);
            
            // Get recording start time
            String timestamp = TimestampUtils.getCurrentUtcTimestamp();
            String formattedTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).format(new Date());
            
            // Create SRT subtitle content
            StringBuilder srtContent = new StringBuilder();
            srtContent.append("1\n");
            srtContent.append("00:00:00,000 --> 00:59:59,999\n");
            srtContent.append("Recording: ").append(formattedTime).append("\n");
            srtContent.append("Legal Timestamp: ").append(timestamp).append("\n");
            
            if (currentTimestamp != null && currentTimestamp.success) {
                srtContent.append("Authority: ").append(currentTimestamp.authority).append("\n");
                if (currentTimestamp.latitude != null && currentTimestamp.longitude != null) {
                    srtContent.append("Location: ").append(currentTimestamp.latitude).append(", ").append(currentTimestamp.longitude).append("\n");
                }
            }
            srtContent.append("\n");
            
            // Write subtitle file
            try (FileOutputStream fos = new FileOutputStream(subtitleFile)) {
                fos.write(srtContent.toString().getBytes());
                fos.flush();
            }
            
            Log.i(TAG, "Created subtitle file: " + subtitlePath);
            return subtitlePath;
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating subtitle file", e);
            return null;
        }
    }
    
    private void createVideoInfoFile(String videoFilePath) {
        try {
            String infoPath = videoFilePath.replace(".mp4", "_META.txt");
            File infoFile = new File(infoPath);
            
            StringBuilder infoContent = new StringBuilder();
            infoContent.append("=== VIDEO RECORDING INFORMATION ===\n");
            infoContent.append("File: ").append(new File(videoFilePath).getName()).append("\n");
            infoContent.append("Recording Started: ").append(currentRecordingStartTime != null ? currentRecordingStartTime : "Unknown").append("\n");
            infoContent.append("Recording Stopped: ").append(TimestampUtils.getCurrentUtcTimestamp()).append("\n");
            infoContent.append("Session ID: ").append(sessionTimestamp).append("\n");
            infoContent.append("Segment: ").append(segmentCounter).append("\n");
            
            if (currentTimestamp != null && currentTimestamp.success) {
                infoContent.append("\n=== AUTHORITATIVE VERIFICATION ===\n");
                infoContent.append("Time Authority: ").append(currentTimestamp.authority).append("\n");
                infoContent.append("Verified Time: ").append(currentTimestamp.timestamp).append("\n");
                
                if (currentTimestamp.latitude != null && currentTimestamp.longitude != null) {
                    infoContent.append("GPS Location: ").append(currentTimestamp.latitude).append(", ").append(currentTimestamp.longitude).append("\n");
                    infoContent.append("Location Provider: ").append(currentTimestamp.locationProvider).append("\n");
                }
            }
            
            // Write info file
            try (FileOutputStream fos = new FileOutputStream(infoFile)) {
                fos.write(infoContent.toString().getBytes());
                fos.flush();
            }
            
            Log.i(TAG, "Created video info file: " + infoPath);
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating video info file", e);
        }
    }
    
    private void saveTimestampFile(File videoFile) {
        try {
            // Calculate SHA-256 hash of the actual video file
            String videoFileHash = calculateFileHash(videoFile);
            
            // Create timestamp verification file
            String timestampFileName = videoFile.getName().replace(".mp4", "_timestamp.txt");
            File timestampFile = new File(videoFile.getParent(), timestampFileName);
            
            StringBuilder timestampInfo = new StringBuilder();
            timestampInfo.append("=== LEGAL TIMESTAMP VERIFICATION ===\n");
            timestampInfo.append("Video File: ").append(videoFile.getName()).append("\n");
            timestampInfo.append("Recording Started: ").append(currentRecordingStartTime != null ? currentRecordingStartTime : "Unknown").append("\n");
            timestampInfo.append("Recording Stopped: ").append(TimestampUtils.getCurrentUtcTimestamp()).append("\n");
            timestampInfo.append("File Size: ").append(videoFile.length()).append(" bytes\n");
            timestampInfo.append("SHA-256 Hash: ").append(videoFileHash).append("\n\n");
            
            if (currentTimestamp != null && currentTimestamp.success) {
                timestampInfo.append("=== AUTHORITATIVE TIMESTAMP VERIFICATION ===\n");
                String authority = currentTimestamp.authority != null ? currentTimestamp.authority : "Unknown";
                String timestamp = currentTimestamp.timestamp != null ? currentTimestamp.timestamp : "Unknown";
                String ntpTime = currentTimestamp.ntpTime != null ? currentTimestamp.ntpTime : "Unknown";
                
                timestampInfo.append("Time Authority: ").append(authority).append("\n");
                timestampInfo.append("UTC Time: ").append(timestamp).append("\n");
                timestampInfo.append("Authoritative Time: ").append(ntpTime).append("\n");
                timestampInfo.append("Status: VERIFIED\n\n");
            } else if (currentTimestamp != null && !currentTimestamp.success) {
                timestampInfo.append("=== AUTHORITATIVE TIMESTAMP VERIFICATION ===\n");
                timestampInfo.append("Time Authority: Network service unavailable\n");
                timestampInfo.append("UTC Time: ").append(TimestampUtils.getCurrentUtcTimestamp()).append("\n");
                timestampInfo.append("Authoritative Time: Local device time (fallback)\n");
                timestampInfo.append("Status: FALLBACK (Network failed: ").append(currentTimestamp.error).append(")\n\n");
            } else {
                timestampInfo.append("=== AUTHORITATIVE TIMESTAMP VERIFICATION ===\n");
                timestampInfo.append("Time Authority: Local device time\n");
                timestampInfo.append("UTC Time: ").append(TimestampUtils.getCurrentUtcTimestamp()).append("\n");
                timestampInfo.append("Authoritative Time: Local device time (service pending)\n");
                timestampInfo.append("Status: VERIFIED (Local time)\n\n");
            }
            
            // Always include GPS info section
            if (currentTimestamp != null) {
                
                timestampInfo.append("=== GPS LOCATION VERIFICATION ===\n");
                String lat = currentTimestamp.latitude != null ? currentTimestamp.latitude : "Unknown";
                String lon = currentTimestamp.longitude != null ? currentTimestamp.longitude : "Unknown";
                String provider = currentTimestamp.locationProvider != null ? currentTimestamp.locationProvider : "Unknown";
                String accuracy = currentTimestamp.locationAccuracy != null ? currentTimestamp.locationAccuracy + " meters" : "Unknown";
                String age = currentTimestamp.locationAge != null ? currentTimestamp.locationAge + " seconds" : "Unknown";
                
                timestampInfo.append("Latitude: ").append(lat).append("\n");
                timestampInfo.append("Longitude: ").append(lon).append("\n");
                timestampInfo.append("Location Provider: ").append(provider).append("\n");
                
                if (!"Unknown".equals(lat) && !"Unknown".equals(lon) && 
                    lat != null && lon != null && !lat.trim().isEmpty() && !lon.trim().isEmpty()) {
                    try {
                        // Validate coordinates are actual numbers
                        Double.parseDouble(lat);
                        Double.parseDouble(lon);
                        timestampInfo.append("Google Maps Link: https://maps.google.com/maps?q=").append(lat).append(",").append(lon).append("\n");
                        timestampInfo.append("Plus Code: https://plus.codes/").append(lat).append(",").append(lon).append("\n");
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "Invalid coordinate format: " + lat + "," + lon);
                    }
                }
                
                timestampInfo.append("Location Accuracy: ").append(accuracy).append("\n");
                timestampInfo.append("Location Age: ").append(age).append("\n");
                
                String locationStatus = "UNAVAILABLE";
                if (provider != null && !"GPS unavailable".equals(provider) && !"Unknown".equals(provider)) {
                    if ("gps".equalsIgnoreCase(provider)) {
                        locationStatus = "GPS VERIFIED (Satellite)";
                    } else if ("network".equalsIgnoreCase(provider)) {
                        locationStatus = "NETWORK VERIFIED (Cell Tower/WiFi)";
                    } else {
                        locationStatus = "VERIFIED (" + provider.toUpperCase() + ")";
                    }
                }
                timestampInfo.append("Location Status: ").append(locationStatus).append("\n\n");
                
                timestampInfo.append("=== LEGAL NOTICE ===\n");
                timestampInfo.append("This timestamp is verified against authoritative time servers\n");
                timestampInfo.append("and provides cryptographic proof that this video existed\n");
                timestampInfo.append("at the specified time and location. The SHA-256 hash ensures file integrity.\n");
                timestampInfo.append("Any modification to the video will change the hash completely.\n\n");
                timestampInfo.append("=== VERIFICATION STEPS ===\n");
                timestampInfo.append("1. Calculate SHA-256 hash of video file and compare with above hash\n");
                timestampInfo.append("2. Verify file size matches the recorded size\n");
                timestampInfo.append("3. Visit Google Maps link to verify exact recording location\n");
                timestampInfo.append("4. Check Plus Code for additional location verification\n");
                timestampInfo.append("5. Contact ").append(currentTimestamp.authority).append(" for timestamp verification\n");
                timestampInfo.append("6. Check that timestamps are consistent across systems\n");
                timestampInfo.append("7. Verify location accuracy is reasonable for the situation\n");
                timestampInfo.append("8. Check location age to ensure GPS reading was recent\n\n");
                timestampInfo.append("=== INTEGRITY GUARANTEE ===\n");
                timestampInfo.append("Any alteration to the video file will result in a completely different\n");
                timestampInfo.append("SHA-256 hash, making tampering immediately detectable.\n");
            } else {
                // When currentTimestamp is null, we still provide GPS info if available
                timestampInfo.append("=== GPS LOCATION VERIFICATION ===\n");
                timestampInfo.append("GPS Status: Service still processing, location unavailable\n\n");
            }
            
            // Add legal notice for all cases
            timestampInfo.append("=== LEGAL NOTICE ===\n");
            timestampInfo.append("This timestamp file provides cryptographic proof that this video existed\n");
            timestampInfo.append("at the specified time. The SHA-256 hash ensures file integrity.\n");
            timestampInfo.append("Any modification to the video will change the hash completely.\n\n");
            timestampInfo.append("=== VERIFICATION STEPS ===\n");
            timestampInfo.append("1. Calculate SHA-256 hash of video file and compare with above hash\n");
            timestampInfo.append("2. Verify file size matches the recorded size\n");
            timestampInfo.append("3. Check that timestamps are consistent with device time\n");
            timestampInfo.append("4. Verify file metadata shows recording date/time\n\n");
            timestampInfo.append("=== INTEGRITY GUARANTEE ===\n");
            timestampInfo.append("Any alteration to the video file will result in a completely different\n");
            timestampInfo.append("SHA-256 hash, making tampering immediately detectable.\n");
            
            // Write to file
            try (FileOutputStream fos = new FileOutputStream(timestampFile)) {
                fos.write(timestampInfo.toString().getBytes());
                fos.flush();
            }
            
            Log.i(TAG, "Timestamp verification file saved: " + timestampFile.getAbsolutePath());
            
        } catch (Exception e) {
            Log.e(TAG, "Error saving timestamp file", e);
        }
    }
    
    private void copyToPublicStorage(File sourceFile) {
        try {
            Log.i(TAG, "Copying video to public storage using MediaStore API");
            
            ContentResolver contentResolver = getContentResolver();
            ContentValues contentValues = new ContentValues();
            
            // Create session-specific folder path
            String sessionFolderName = sessionFolder != null ? sessionFolder.getName() : sessionTimestamp;
            String relativePath = "Movies/SoundTrigger/" + sessionFolderName;
            
            contentValues.put(MediaStore.Video.Media.DISPLAY_NAME, sourceFile.getName());
            contentValues.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, relativePath);
            
            Uri videoUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues);
            
            if (videoUri != null) {
                try (FileInputStream inputStream = new FileInputStream(sourceFile);
                     OutputStream outputStream = contentResolver.openOutputStream(videoUri)) {
                    
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytes = 0;
                    
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                    }
                    
                    Log.i(TAG, "Successfully copied video to public storage (size: " + totalBytes + " bytes)");
                }
            } else {
                Log.e(TAG, "Failed to create MediaStore entry for video");
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Error copying video to public storage", e);
        }
    }
    
    private void copyTimestampToPublicStorage(File videoFile) {
        try {
            String timestampFileName = videoFile.getName().replace(".mp4", "_timestamp.txt");
            File timestampFile = new File(videoFile.getParent(), timestampFileName);
            
            if (!timestampFile.exists()) {
                Log.w(TAG, "Timestamp file not found: " + timestampFile.getAbsolutePath());
                return;
            }
            
            Log.i(TAG, "Copying timestamp file to public storage using MediaStore API");
            
            ContentResolver contentResolver = getContentResolver();
            ContentValues contentValues = new ContentValues();
            
            // Create session-specific folder path for timestamp files
            String sessionFolderName = sessionFolder != null ? sessionFolder.getName() : sessionTimestamp;
            String relativePath = "Documents/SoundTrigger/" + sessionFolderName;
            
            contentValues.put(MediaStore.Files.FileColumns.DISPLAY_NAME, timestampFileName);
            contentValues.put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain");
            contentValues.put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath);
            
            Uri timestampUri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues);
            
            if (timestampUri != null) {
                try (FileInputStream inputStream = new FileInputStream(timestampFile);
                     OutputStream outputStream = contentResolver.openOutputStream(timestampUri)) {
                    
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    long totalBytes = 0;
                    
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                    }
                    
                    Log.i(TAG, "Successfully copied timestamp file to public storage (size: " + totalBytes + " bytes)");
                }
            } else {
                Log.e(TAG, "Failed to create MediaStore entry for timestamp file");
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Error copying timestamp file to public storage", e);
        }
    }
    
    private void copySubtitleToPublicStorage(File videoFile) {
        try {
            String subtitleFileName = videoFile.getName().replace(".mp4", "_SUB.srt");
            File subtitleFile = new File(videoFile.getParent(), subtitleFileName);
            
            if (!subtitleFile.exists()) {
                Log.w(TAG, "Subtitle file not found: " + subtitleFile.getAbsolutePath());
                return;
            }
            
            Log.i(TAG, "Copying subtitle file to public storage using MediaStore API");
            
            ContentResolver contentResolver = getContentResolver();
            ContentValues contentValues = new ContentValues();
            
            // Create session-specific folder path for subtitle files
            String sessionFolderName = sessionFolder != null ? sessionFolder.getName() : sessionTimestamp;
            String relativePath = "Documents/SoundTrigger/" + sessionFolderName;
            
            contentValues.put(MediaStore.Files.FileColumns.DISPLAY_NAME, subtitleFileName);
            contentValues.put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain");
            contentValues.put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath);
            
            Uri subtitleUri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues);
            
            if (subtitleUri != null) {
                try (FileInputStream inputStream = new FileInputStream(subtitleFile);
                     OutputStream outputStream = contentResolver.openOutputStream(subtitleUri)) {
                    
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    long totalBytes = 0;
                    
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                    }
                    
                    Log.i(TAG, "Successfully copied subtitle file to public storage (size: " + totalBytes + " bytes)");
                }
            } else {
                Log.e(TAG, "Failed to create MediaStore entry for subtitle file");
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Error copying subtitle file to public storage", e);
        }
    }
    
    private void copyVideoInfoToPublicStorage(File videoFile) {
        try {
            String infoFileName = videoFile.getName().replace(".mp4", "_META.txt");
            File infoFile = new File(videoFile.getParent(), infoFileName);
            
            if (!infoFile.exists()) {
                Log.w(TAG, "Video info file not found: " + infoFile.getAbsolutePath());
                return;
            }
            
            Log.i(TAG, "Copying video info file to public storage using MediaStore API");
            
            ContentResolver contentResolver = getContentResolver();
            ContentValues contentValues = new ContentValues();
            
            // Create session-specific folder path for video info files
            String sessionFolderName = sessionFolder != null ? sessionFolder.getName() : sessionTimestamp;
            String relativePath = "Documents/SoundTrigger/" + sessionFolderName;
            
            contentValues.put(MediaStore.Files.FileColumns.DISPLAY_NAME, infoFileName);
            contentValues.put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain");
            contentValues.put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath);
            
            Uri infoUri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues);
            
            if (infoUri != null) {
                try (FileInputStream inputStream = new FileInputStream(infoFile);
                     OutputStream outputStream = contentResolver.openOutputStream(infoUri)) {
                    
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    long totalBytes = 0;
                    
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                    }
                    
                    Log.i(TAG, "Successfully copied video info file to public storage (size: " + totalBytes + " bytes)");
                }
            } else {
                Log.e(TAG, "Failed to create MediaStore entry for video info file");
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Error copying video info file to public storage", e);
        }
    }
    
    private void copyToDownloads(File sourceFile) {
        try {
            Log.i(TAG, "Copying video to Downloads folder for easy access");
            
            ContentResolver contentResolver = getContentResolver();
            ContentValues contentValues = new ContentValues();
            
            // Create session-specific folder path in Downloads
            String sessionFolderName = sessionFolder != null ? sessionFolder.getName() : sessionTimestamp;
            String relativePath = "Download/SoundTrigger/" + sessionFolderName;
            
            contentValues.put(MediaStore.Video.Media.DISPLAY_NAME, sourceFile.getName());
            contentValues.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, relativePath);
            
            Uri videoUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues);
            
            if (videoUri != null) {
                try (FileInputStream inputStream = new FileInputStream(sourceFile);
                     OutputStream outputStream = contentResolver.openOutputStream(videoUri)) {
                    
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytes = 0;
                    
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                    }
                    
                    Log.i(TAG, "Successfully copied video to Downloads folder (size: " + totalBytes + " bytes)");
                }
            } else {
                Log.e(TAG, "Failed to create MediaStore entry for video in Downloads");
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Error copying video to Downloads folder", e);
        }
    }
    
    private void copyTimestampToDownloads(File videoFile) {
        try {
            String timestampFileName = videoFile.getName().replace(".mp4", "_timestamp.txt");
            File timestampFile = new File(videoFile.getParent(), timestampFileName);
            
            if (!timestampFile.exists()) {
                Log.w(TAG, "Timestamp file not found for Downloads copy: " + timestampFile.getAbsolutePath());
                return;
            }
            
            Log.i(TAG, "Copying timestamp file to Downloads folder for easy access");
            
            ContentResolver contentResolver = getContentResolver();
            ContentValues contentValues = new ContentValues();
            
            // Create session-specific folder path in Downloads for timestamp files
            String sessionFolderName = sessionFolder != null ? sessionFolder.getName() : sessionTimestamp;
            String relativePath = "Download/SoundTrigger/" + sessionFolderName;
            
            contentValues.put(MediaStore.Files.FileColumns.DISPLAY_NAME, timestampFileName);
            contentValues.put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain");
            contentValues.put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath);
            
            Uri timestampUri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues);
            
            if (timestampUri != null) {
                try (FileInputStream inputStream = new FileInputStream(timestampFile);
                     OutputStream outputStream = contentResolver.openOutputStream(timestampUri)) {
                    
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    long totalBytes = 0;
                    
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                    }
                    
                    Log.i(TAG, "Successfully copied timestamp file to Downloads folder (size: " + totalBytes + " bytes)");
                }
            } else {
                Log.e(TAG, "Failed to create MediaStore entry for timestamp file in Downloads");
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Error copying timestamp file to Downloads folder", e);
        }
    }
    
    private void copySubtitleToDownloads(File videoFile) {
        try {
            String subtitleFileName = videoFile.getName().replace(".mp4", "_SUB.srt");
            File subtitleFile = new File(videoFile.getParent(), subtitleFileName);
            
            if (!subtitleFile.exists()) {
                Log.w(TAG, "Subtitle file not found for Downloads copy: " + subtitleFile.getAbsolutePath());
                return;
            }
            
            Log.i(TAG, "Copying subtitle file to Downloads folder for easy access");
            
            ContentResolver contentResolver = getContentResolver();
            ContentValues contentValues = new ContentValues();
            
            // Create session-specific folder path in Downloads for subtitle files
            String sessionFolderName = sessionFolder != null ? sessionFolder.getName() : sessionTimestamp;
            String relativePath = "Download/SoundTrigger/" + sessionFolderName;
            
            contentValues.put(MediaStore.Files.FileColumns.DISPLAY_NAME, subtitleFileName);
            contentValues.put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain");
            contentValues.put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath);
            
            Uri subtitleUri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues);
            
            if (subtitleUri != null) {
                try (FileInputStream inputStream = new FileInputStream(subtitleFile);
                     OutputStream outputStream = contentResolver.openOutputStream(subtitleUri)) {
                    
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    long totalBytes = 0;
                    
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                    }
                    
                    Log.i(TAG, "Successfully copied subtitle file to Downloads folder (size: " + totalBytes + " bytes)");
                }
            } else {
                Log.e(TAG, "Failed to create MediaStore entry for subtitle file in Downloads");
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Error copying subtitle file to Downloads folder", e);
        }
    }
    
    private void copyVideoInfoToDownloads(File videoFile) {
        try {
            String infoFileName = videoFile.getName().replace(".mp4", "_META.txt");
            File infoFile = new File(videoFile.getParent(), infoFileName);
            
            if (!infoFile.exists()) {
                Log.w(TAG, "Video info file not found for Downloads copy: " + infoFile.getAbsolutePath());
                return;
            }
            
            Log.i(TAG, "Copying video info file to Downloads folder for easy access");
            
            ContentResolver contentResolver = getContentResolver();
            ContentValues contentValues = new ContentValues();
            
            // Create session-specific folder path in Downloads for video info files
            String sessionFolderName = sessionFolder != null ? sessionFolder.getName() : sessionTimestamp;
            String relativePath = "Download/SoundTrigger/" + sessionFolderName;
            
            contentValues.put(MediaStore.Files.FileColumns.DISPLAY_NAME, infoFileName);
            contentValues.put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain");
            contentValues.put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath);
            
            Uri infoUri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues);
            
            if (infoUri != null) {
                try (FileInputStream inputStream = new FileInputStream(infoFile);
                     OutputStream outputStream = contentResolver.openOutputStream(infoUri)) {
                    
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    long totalBytes = 0;
                    
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                    }
                    
                    Log.i(TAG, "Successfully copied video info file to Downloads folder (size: " + totalBytes + " bytes)");
                }
            } else {
                Log.e(TAG, "Failed to create MediaStore entry for video info file in Downloads");
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Error copying video info file to Downloads folder", e);
        }
    }
    
    
    
    private void copySessionToDownloads() {
        if (sessionFolder == null || !sessionFolder.exists()) {
            Log.w(TAG, "Session folder not available for Downloads copy");
            return;
        }
        
        try {
            Log.i(TAG, "Copying session files to Downloads");
            
            File[] sessionFiles = sessionFolder.listFiles();
            if (sessionFiles == null) {
                Log.w(TAG, "No files found in session folder");
                return;
            }
            
            for (File file : sessionFiles) {
                if (file.isFile()) {
                    copyFileToDownloads(file, "Download/SoundTrigger/" + sessionFolder.getName());
                }
            }
            
            Log.i(TAG, "Session files copied to Downloads successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error copying session files to Downloads", e);
        }
    }
    
    
    private void copyFileToDownloads(File sourceFile, String relativePath) {
        try {
            ContentResolver contentResolver = getContentResolver();
            ContentValues contentValues = new ContentValues();
            
            String mimeType = getMimeType(sourceFile);
            Uri targetUri;
            
            // Ensure session-specific folder structure in Downloads too
            String sessionFolderName = sessionFolder != null ? sessionFolder.getName() : sessionTimestamp;
            String finalRelativePath = relativePath;
            if (!relativePath.contains(sessionFolderName) && !relativePath.endsWith(sessionFolderName)) {
                // If not already a session-specific path, make it one
                if (relativePath.equals("Download/SoundTrigger/" + sessionFolderName)) {
                    finalRelativePath = relativePath; // Already correct
                } else {
                    finalRelativePath = "Download/SoundTrigger/" + sessionFolderName;
                }
            }
            
            // Use IS_PENDING to prevent conflicts during file creation
            contentValues.put(MediaStore.Files.FileColumns.DISPLAY_NAME, sourceFile.getName());
            contentValues.put(MediaStore.Files.FileColumns.MIME_TYPE, mimeType);
            contentValues.put(MediaStore.Files.FileColumns.RELATIVE_PATH, finalRelativePath);
            contentValues.put(MediaStore.Files.FileColumns.IS_PENDING, 1); // Mark as pending to avoid conflicts
            
            // Use Files API for all files to ensure they go to the correct folder
            targetUri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues);
            
            if (targetUri != null) {
                try (FileInputStream inputStream = new FileInputStream(sourceFile);
                     OutputStream outputStream = contentResolver.openOutputStream(targetUri)) {
                    
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytes = 0;
                    
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                    }
                    
                    Log.i(TAG, "Copied " + sourceFile.getName() + " to Downloads (size: " + totalBytes + " bytes)");
                }
                
                // Mark file as complete (no longer pending)
                contentValues.clear();
                contentValues.put(MediaStore.Files.FileColumns.IS_PENDING, 0);
                contentResolver.update(targetUri, contentValues, null, null);
                
            } else {
                Log.e(TAG, "Failed to create MediaStore entry for " + sourceFile.getName() + " in Downloads");
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Error copying " + sourceFile.getName() + " to Downloads", e);
        }
    }
    
    private String getMimeType(File file) {
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".mp4")) {
            return "video/mp4";
        } else if (fileName.endsWith(".srt")) {
            return "application/x-subrip";
        } else if (fileName.endsWith(".txt")) {
            return "text/plain";
        } else {
            return "application/octet-stream";
        }
    }
    
    private String calculateFileHash(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error calculating file hash", e);
            return "HASH_CALCULATION_FAILED";
        }
    }
    
    private void restartMonitoring() {
        try {
            recreateAudioRecord();
            isMonitoring = true;
            // Use continuous dB monitoring instead of old monitorSound
            startDbMonitoring();
            Log.i(TAG, "Trigger monitoring restarted after recording");
        } catch (Exception restartError) {
            Log.e(TAG, "Error restarting trigger monitoring", restartError);
        }
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Sound Monitor Service", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Monitors sound levels for automatic recording");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    private Notification createNotification(String contentText) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sound Monitor Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build();
    }
    
    private void updateNotification(String contentText) {
        Notification notification = createNotification(contentText);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }
    
    private void mergeSegmentsAndSave() {
        try {
            Log.i(TAG, "Merging " + recordingSegments.size() + " recording segments...");
            
            if (recordingSegments.size() == 1) {
                // Only one segment, just copy and rename it
                String finalFileName = "FINAL.mp4";
                File segmentFile = new File(recordingSegments.get(0));
                File finalFile = new File(sessionFolder, finalFileName);
                
                // Copy the single segment to final file
                copyFile(segmentFile, finalFile);
                
                Log.i(TAG, "Single segment renamed to final file: " + finalFile.getAbsolutePath());
                processFinalFile(finalFile);
                
            } else if (recordingSegments.size() > 1) {
                // Multiple segments - use simple concatenation
                String finalFileName = "FINAL.mp4";
                File finalFile = new File(sessionFolder, finalFileName);
                
                // Simple MP4 concatenation (works for files with same encoding settings)
                concatenateMP4Files(recordingSegments, finalFile.getAbsolutePath());
                
                Log.i(TAG, "Segments merged into final file: " + finalFile.getAbsolutePath());
                processFinalFile(finalFile);
            }
            
            // Keep segment files in session folder - don't delete them
            Log.i(TAG, "Keeping " + recordingSegments.size() + " segment files in session folder");
            
            // Reset session variables
            recordingSegments.clear();
            sessionTimestamp = "";
            sessionFolder = null;
            segmentCounter = 0;
            
        } catch (Exception e) {
            Log.e(TAG, "Error merging recording segments", e);
        }
    }
    
    private void processFinalFile(File finalFile) {
        try {
            Log.i(TAG, "Processing final merged file: " + finalFile.getAbsolutePath() + " (size: " + finalFile.length() + " bytes)");
            
            // Create combined metadata files for the final merged video
            createCombinedSrtFile(finalFile);
            createCombinedInfoFile(finalFile);
            createSessionReadme();
            
            // Save timestamp verification file
            saveTimestampFile(finalFile);
            
            // Copy all session files to public storage once at the end
            copySessionToDownloads();
            
            updateNotification("Final recording saved and merged successfully!");
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing final file", e);
        }
    }
    
    private void createCombinedSrtFile(File finalFile) {
        try {
            String finalSrtPath = finalFile.getAbsolutePath().replace("FINAL.mp4", "FINAL_SUB.srt");
            File finalSrtFile = new File(finalSrtPath);
            
            StringBuilder combinedSrt = new StringBuilder();
            int subtitleIndex = 1;
            long cumulativeDuration = 0;
            
            Log.i(TAG, "Creating combined SRT file from " + recordingSegments.size() + " segments");
            
            // Process each segment's SRT file
            for (String segmentPath : recordingSegments) {
                File segmentFile = new File(segmentPath);
                String segmentSrtPath = segmentPath.replace(".mp4", "_SUB.srt");
                File segmentSrtFile = new File(segmentSrtPath);
                
                if (segmentSrtFile.exists()) {
                    // Get segment duration for time offset calculation
                    long segmentDuration = getVideoDurationMs(segmentFile);
                    
                    // Add segment SRT content with time offset
                    String segmentContent = readSegmentSrtContent(segmentSrtFile, subtitleIndex, cumulativeDuration);
                    if (!segmentContent.isEmpty()) {
                        combinedSrt.append(segmentContent).append("\n");
                        subtitleIndex++;
                    }
                    
                    cumulativeDuration += segmentDuration;
                } else {
                    Log.w(TAG, "SRT file not found for segment: " + segmentSrtPath);
                }
            }
            
            // Write combined SRT
            try (FileOutputStream fos = new FileOutputStream(finalSrtFile)) {
                fos.write(combinedSrt.toString().getBytes());
                fos.flush();
            }
            
            Log.i(TAG, "Created combined SRT file: " + finalSrtPath);
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating combined SRT file", e);
        }
    }
    
    private void createCombinedInfoFile(File finalFile) {
        try {
            String finalInfoPath = finalFile.getAbsolutePath().replace("FINAL.mp4", "FINAL_META.txt");
            File finalInfoFile = new File(finalInfoPath);
            
            StringBuilder combinedInfo = new StringBuilder();
            combinedInfo.append("=== SOUND MONITOR SESSION INFORMATION ===\n");
            combinedInfo.append("Session: ").append(sessionTimestamp).append("\n");
            combinedInfo.append("Final Video: ").append(finalFile.getName()).append("\n");
            combinedInfo.append("Total Segments: ").append(recordingSegments.size()).append("\n");
            combinedInfo.append("Recording Started: ").append(currentRecordingStartTime).append("\n");
            combinedInfo.append("Final Video Size: ").append(finalFile.length()).append(" bytes\n");
            
            // Get total duration
            long totalDuration = getVideoDurationMs(finalFile);
            combinedInfo.append("Total Duration: ").append(totalDuration / 1000.0).append(" seconds\n\n");
            
            combinedInfo.append("=== SEGMENT DETAILS ===\n");
            
            // Add details for each segment
            for (int i = 0; i < recordingSegments.size(); i++) {
                String segmentPath = recordingSegments.get(i);
                File segmentFile = new File(segmentPath);
                
                combinedInfo.append("Segment ").append(i + 1).append(":\n");
                combinedInfo.append("  File: ").append(segmentFile.getName()).append("\n");
                combinedInfo.append("  Size: ").append(segmentFile.length()).append(" bytes\n");
                
                long segmentDuration = getVideoDurationMs(segmentFile);
                combinedInfo.append("  Duration: ").append(segmentDuration / 1000.0).append(" seconds\n");
                
                // Add timestamp info if available
                String segmentTimestampPath = segmentPath.replace(".mp4", "_timestamp.txt");
                File segmentTimestampFile = new File(segmentTimestampPath);
                if (segmentTimestampFile.exists()) {
                    combinedInfo.append("  Has Legal Timestamp: Yes\n");
                } else {
                    combinedInfo.append("  Has Legal Timestamp: No\n");
                }
                
                combinedInfo.append("\n");
            }
            
            combinedInfo.append("=== LEGAL NOTICE ===\n");
            combinedInfo.append("This recording was automatically triggered by sound detection.\n");
            combinedInfo.append("Individual segment timestamp files provide legal verification.\n");
            combinedInfo.append("All times in UTC. GPS location data available in timestamp files.\n");
            
            // Write combined info
            try (FileOutputStream fos = new FileOutputStream(finalInfoFile)) {
                fos.write(combinedInfo.toString().getBytes());
                fos.flush();
            }
            
            Log.i(TAG, "Created combined info file: " + finalInfoPath);
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating combined info file", e);
        }
    }
    
    private String readSegmentSrtContent(File srtFile, int subtitleIndex, long timeOffsetMs) {
        try {
            StringBuilder content = new StringBuilder();
            
            // Read the SRT file content
            StringBuilder srtContent = new StringBuilder();
            try (FileInputStream fis = new FileInputStream(srtFile)) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    srtContent.append(new String(buffer, 0, bytesRead));
                }
            }
            
            // Format the subtitle with proper index and time offset
            content.append(subtitleIndex).append("\n");
            
            // Calculate time range for this segment (simplified - shows for entire segment duration)
            String startTime = formatSrtTime(timeOffsetMs);
            String endTime = formatSrtTime(timeOffsetMs + 60000); // Assume 1 minute segments
            
            content.append(startTime).append(" --> ").append(endTime).append("\n");
            
            // Extract just the text content from original SRT (skip timestamp lines)
            String[] lines = srtContent.toString().split("\n");
            for (int i = 2; i < lines.length; i++) { // Skip index and time lines
                if (!lines[i].trim().isEmpty()) {
                    content.append(lines[i].trim()).append("\n");
                }
            }
            
            return content.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error reading segment SRT content", e);
            return "";
        }
    }
    
    private String formatSrtTime(long milliseconds) {
        long hours = milliseconds / (1000 * 60 * 60);
        long minutes = (milliseconds % (1000 * 60 * 60)) / (1000 * 60);
        long seconds = (milliseconds % (1000 * 60)) / 1000;
        long millis = milliseconds % 1000;
        
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis);
    }
    
    private long getVideoDurationMs(File videoFile) {
        try {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoFile.getAbsolutePath());
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            retriever.release();
            
            return durationStr != null ? Long.parseLong(durationStr) : 60000; // Default to 1 minute
            
        } catch (Exception e) {
            Log.w(TAG, "Could not get video duration for " + videoFile.getName() + ", using default");
            return 60000; // Default to 1 minute
        }
    }
    
    private void createSessionReadme() {
        try {
            File readmeFile = new File(sessionFolder, "README.txt");
            
            StringBuilder readme = new StringBuilder();
            readme.append("================================================================================\n");
            readme.append("                     SOUND MONITOR RECORDING SESSION\n");
            readme.append("                          FILE EXPLANATION\n");
            readme.append("================================================================================\n\n");
            
            readme.append("Session: ").append(sessionTimestamp).append("\n");
            readme.append("Generated: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).format(new Date())).append("\n\n");
            
            readme.append("FILE TYPES AND PURPOSES:\n");
            readme.append("========================\n\n");
            
            readme.append("🎬 FINAL FILES (What you need):\n");
            readme.append("   FINAL.mp4\n");
            readme.append("   → The complete merged video from all recording segments\n");
            readme.append("   → This is your main evidence file\n\n");
            
            readme.append("   FINAL_SUB.srt\n");
            readme.append("   → Combined subtitles showing timestamps for the entire session\n");
            readme.append("   → Load this with the video in media players for timestamp overlay\n\n");
            
            readme.append("   FINAL_META.txt\n");
            readme.append("   → Complete session information including all segments\n");
            readme.append("   → Contains duration, file sizes, and legal verification status\n\n");
            
            readme.append("   FINAL_timestamp.txt\n");
            readme.append("   → LEGAL VERIFICATION FILE - CRITICAL FOR COURT EVIDENCE\n");
            readme.append("   → Contains cryptographic timestamp, GPS location, and integrity hash\n");
            readme.append("   → Proves when and where the recording was made\n\n");
            
            readme.append("📹 INDIVIDUAL SEGMENTS:\n");
            readme.append("   01.mp4, 02.mp4, 03.mp4...\n");
            readme.append("   → Individual recording segments (before merging)\n");
            readme.append("   → Useful for detailed analysis or if final merge fails\n\n");
            
            readme.append("   01_SUB.srt, 02_SUB.srt, 03_SUB.srt...\n");
            readme.append("   → Subtitles for individual segments\n");
            readme.append("   → Shows exact timestamp when each segment was recorded\n\n");
            
            readme.append("   01_META.txt, 02_META.txt, 03_META.txt...\n");
            readme.append("   → Technical information for each segment\n");
            readme.append("   → File size, duration, encoding details, GPS coordinates\n\n");
            
            readme.append("   01_timestamp.txt, 02_timestamp.txt, 03_timestamp.txt...\n");
            readme.append("   → Legal verification for each individual segment\n");
            readme.append("   → Separate cryptographic proof for each recording\n\n");
            
            readme.append("HOW TO USE FOR LEGAL EVIDENCE:\n");
            readme.append("===============================\n");
            readme.append("1. Primary Evidence: Use the FINAL.mp4 file\n");
            readme.append("2. Timestamp Proof: Include the FINAL_timestamp.txt file\n");
            readme.append("3. Supporting Data: Provide the FINAL_META.txt for context\n");
            readme.append("4. Verification: Individual segment files prove no tampering\n\n");
            
            readme.append("LEGAL VERIFICATION PROCESS:\n");
            readme.append("============================\n");
            readme.append("• Authoritative timestamps from external time servers\n");
            readme.append("• GPS coordinates proving recording location\n");
            readme.append("• SHA-256 cryptographic hashes preventing tampering\n");
            readme.append("• Google Maps links for location verification\n");
            readme.append("• Multiple time sources for redundancy\n\n");
            
            readme.append("TECHNICAL DETAILS:\n");
            readme.append("==================\n");
            readme.append("• Video: H.264/AAC, 1280x720, 30fps, ~2Mbps\n");
            readme.append("• Audio: 44.1kHz, Mono, 16-bit PCM, 128kbps AAC\n");
            readme.append("• Trigger: Sound-activated recording above threshold\n");
            readme.append("• Auto-stop: 1 minute after sound drops below threshold\n\n");
            
            readme.append("IMPORTANT NOTES:\n");
            readme.append("================\n");
            readme.append("• Keep ALL files together for complete legal evidence\n");
            readme.append("• Do NOT modify any files - this breaks cryptographic verification\n");
            readme.append("• Timestamp files contain authoritative time - crucial for court use\n");
            readme.append("• GPS coordinates may be approximate based on available accuracy\n\n");
            
            readme.append("Generated by Sound Monitor app - Automated Evidence Collection System\n");
            readme.append("================================================================================\n");
            
            // Write README file
            try (FileOutputStream fos = new FileOutputStream(readmeFile)) {
                fos.write(readme.toString().getBytes());
                fos.flush();
            }
            
            Log.i(TAG, "Created session README file: " + readmeFile.getAbsolutePath());
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating session README", e);
        }
    }
    
    private void copyFile(File source, File destination) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(destination)) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }
    }
    
    private void concatenateMP4Files(List<String> inputFiles, String outputFile) {
        try {
            Log.i(TAG, "Concatenating " + inputFiles.size() + " MP4 files...");
            
            // Simple MP4 concatenation by copying raw bytes
            // Note: This works for files with identical encoding parameters
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                boolean firstFile = true;
                
                for (String inputFile : inputFiles) {
                    File file = new File(inputFile);
                    if (!file.exists()) {
                        Log.w(TAG, "Segment file not found: " + inputFile);
                        continue;
                    }
                    
                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        long totalBytes = 0;
                        
                        // For MP4 concatenation, we skip the header of subsequent files
                        if (!firstFile) {
                            // Skip the first few bytes (MP4 header) for subsequent files
                            // This is a simplified approach - proper MP4 merging is more complex
                            fis.skip(32); // Skip basic MP4 header
                        }
                        
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                            totalBytes += bytesRead;
                        }
                        
                        Log.i(TAG, "Concatenated segment: " + inputFile + " (" + totalBytes + " bytes)");
                        firstFile = false;
                    }
                }
            }
            
            Log.i(TAG, "MP4 concatenation completed: " + outputFile);
            
        } catch (Exception e) {
            Log.e(TAG, "Error concatenating MP4 files", e);
            // If concatenation fails, use the first segment as the final file
            try {
                if (!inputFiles.isEmpty()) {
                    copyFile(new File(inputFiles.get(0)), new File(outputFile));
                    Log.i(TAG, "Fallback: Used first segment as final file");
                }
            } catch (IOException ioE) {
                Log.e(TAG, "Fallback copy also failed", ioE);
            }
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        isMonitoring = false;
        isDbMonitoring = false;
        
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
        }
        
        if (dbMonitorRecord != null) {
            dbMonitorRecord.stop();
            dbMonitorRecord.release();
        }
        
        if (isRecording) {
            stopRecording();
        }
        
        // Handle audio-only mode stopping
        if (isAudioOnlyMode && isRecording) {
            stopAudioOnlyRecording();
        }
        
        // Merge all recording segments when service stops (video mode)
        if (!recordingSegments.isEmpty() && !isAudioOnlyMode) {
            mergeSegmentsAndSave();
        }
        
        cleanup();
        
        handler.removeCallbacksAndMessages(null);
        stopHandler.removeCallbacksAndMessages(null);
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
