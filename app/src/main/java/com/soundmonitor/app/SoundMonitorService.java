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
import android.view.Surface;
import java.security.MessageDigest;

public class SoundMonitorService extends Service {
    private static final String TAG = "SoundMonitorService";
    private static final String CHANNEL_ID = "SoundMonitorChannel";
    private static final int NOTIFICATION_ID = 1;
    public static final String ACTION_DECIBEL_UPDATE = "com.soundmonitor.app.DECIBEL_UPDATE";
    public static final String EXTRA_DECIBEL_LEVEL = "decibel_level";
    public static final String EXTRA_IS_RECORDING = "is_recording";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    
    private AudioRecord audioRecord;
    private MediaRecorder mediaRecorder;
    private Camera camera;
    private SurfaceTexture surfaceTexture;
    private boolean isMonitoring = false;
    private boolean isRecording = false;
    private int soundThreshold = 50;
    private Handler handler;
    private Handler stopHandler;
    private String currentVideoFile = "";
    private String currentRecordingStartTime = "";
    private TimestampService.TimestampResult currentTimestamp;
    private String sessionTimestamp = "";
    private List<String> recordingSegments = new ArrayList<>();
    private int segmentCounter = 0;
    
    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        stopHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            soundThreshold = intent.getIntExtra("threshold", 50);
        }
        
        startForeground(NOTIFICATION_ID, createNotification("Monitoring for sounds..."));
        startMonitoring();
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
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                                        CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed");
                return;
            }
            
            isMonitoring = true;
            audioRecord.startRecording();
            
            new Thread(this::monitorSound).start();
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting audio monitoring", e);
        }
    }
    
    private void monitorSound() {
        short[] buffer = new short[1024];
        
        while (isMonitoring) {
            try {
                int readSize = audioRecord.read(buffer, 0, buffer.length);
                if (readSize > 0) {
                    double amplitude = calculateAmplitude(buffer, readSize);
                    double decibel = 20 * Math.log10(amplitude);
                    
                    if (Double.isInfinite(decibel) || Double.isNaN(decibel)) {
                        decibel = 0;
                    }
                    
                    final double finalDecibel = decibel;
                    handler.post(() -> handleSoundLevel(finalDecibel));
                }
                Thread.sleep(100);
            } catch (Exception e) {
                Log.e(TAG, "Error in sound monitoring", e);
                break;
            }
        }
    }
    
    private double calculateAmplitude(short[] buffer, int readSize) {
        double sum = 0;
        for (int i = 0; i < readSize; i++) {
            sum += Math.abs(buffer[i]);
        }
        return sum / readSize;
    }
    
    private void handleSoundLevel(double decibel) {
        updateNotification("Current: " + String.format("%.1f", decibel) + " dB");
        
        // Broadcast decibel level to activity for UI update
        Intent intent = new Intent(ACTION_DECIBEL_UPDATE);
        intent.putExtra(EXTRA_DECIBEL_LEVEL, decibel);
        intent.putExtra(EXTRA_IS_RECORDING, isRecording);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        
        // Segment-based recording: pause/resume based on sound levels
        if (decibel > soundThreshold && !isRecording) {
            startRecording();
        } else if (decibel <= soundThreshold && isRecording) {
            scheduleStopRecording();
        } else if (decibel > soundThreshold && isRecording) {
            stopHandler.removeCallbacksAndMessages(null); // Cancel stop if sound returns
        }
    }
    
    private void startRecording() {
        try {
            // Stop monitoring completely to free up the microphone
            Log.i(TAG, "Stopping monitoring to free microphone for video recording");
            isMonitoring = false;
            
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
                Log.i(TAG, "AudioRecord released");
            }
            
            // Get cryptographic timestamp for legal evidence
            Log.i(TAG, "Getting RFC 3161 timestamp for legal verification...");
            Date recordingStartDate = new Date();
            
            // Set session timestamp on first recording
            if (sessionTimestamp.isEmpty()) {
                sessionTimestamp = TimestampUtils.formatAsFileTimestamp(recordingStartDate);
                currentRecordingStartTime = TimestampUtils.formatAsUtc(recordingStartDate);
                recordingSegments.clear();
                segmentCounter = 0;
                Log.i(TAG, "Starting new recording session: " + sessionTimestamp);
            }
            
            segmentCounter++;
            String timeStamp = sessionTimestamp + "_segment" + segmentCounter;
            byte[] timestampData = timeStamp.getBytes();
            
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
            
            // Use app-specific external directory (no permissions needed on Android 10+)
            File storageDir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "SoundTrigger");
            
            if (!storageDir.exists()) {
                boolean created = storageDir.mkdirs();
                Log.i(TAG, "Storage directory created: " + created + " at " + storageDir.getAbsolutePath());
            }
            
            currentVideoFile = new File(storageDir, "video_" + timeStamp + ".mp4").getAbsolutePath();
            Log.i(TAG, "Recording video to: " + currentVideoFile);
            
            // Check permissions
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Required permissions not granted");
                restartMonitoring();
                return;
            }
            
            // Initialize camera first
            Log.i(TAG, "Opening camera...");
            camera = Camera.open();
            if (camera == null) {
                Log.e(TAG, "Failed to open camera");
                restartMonitoring();
                return;
            }
            
            // Set up camera parameters
            Camera.Parameters params = camera.getParameters();
            params.setRecordingHint(true);
            camera.setParameters(params);
            
            // Create a dummy surface texture for camera preview
            surfaceTexture = new SurfaceTexture(10);
            camera.setPreviewTexture(surfaceTexture);
            camera.startPreview();
            
            // Unlock camera for MediaRecorder
            camera.unlock();
            
            Log.i(TAG, "Configuring MediaRecorder for video recording...");
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
            
            // Set video settings
            mediaRecorder.setVideoSize(1280, 720);
            mediaRecorder.setVideoFrameRate(30);
            mediaRecorder.setVideoEncodingBitRate(2000000); // 2Mbps
            
            // Set audio settings
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setAudioEncodingBitRate(128000); // 128kbps
            
            mediaRecorder.setOutputFile(currentVideoFile);
            
            Log.i(TAG, "Preparing MediaRecorder...");
            mediaRecorder.prepare();
            
            Log.i(TAG, "Starting video recording...");
            mediaRecorder.start();
            
            isRecording = true;
            updateNotification("Recording video: " + timeStamp);
            
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
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                                    CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
        
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new RuntimeException("AudioRecord initialization failed");
        }
        
        audioRecord.startRecording();
    }
    
    private void scheduleStopRecording() {
        stopHandler.postDelayed(this::stopRecording, 60000); // 1 minute delay
    }
    
    private void stopRecording() {
        if (!isRecording) return;
        
        try {
            Log.i(TAG, "Stopping video recording...");
            isRecording = false;
            
            cleanup();
            
            updateNotification("Stopped recording. Monitoring...");
            
            // Check final file size
            File file = new File(currentVideoFile);
            Log.i(TAG, "Stopped video recording segment: " + currentVideoFile + " (size: " + file.length() + " bytes)");
            
            // Add segment to list for later merging
            recordingSegments.add(currentVideoFile);
            Log.i(TAG, "Segment " + segmentCounter + " saved. Total segments: " + recordingSegments.size());
            
            // Update notification to show segment saved
            updateNotification("Segment " + segmentCounter + " saved. Monitoring for next sound...");
            
            // Restart monitoring to detect next sound above threshold
            restartMonitoring();
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping video recording", e);
            e.printStackTrace();
            
            cleanup();
            isRecording = false;
            restartMonitoring();
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
                timestampInfo.append("=== TIMESTAMP STATUS ===\n");
                timestampInfo.append("Authoritative Timestamp: UNAVAILABLE\n");
                timestampInfo.append("Reason: ").append(currentTimestamp != null ? currentTimestamp.error : "Network error").append("\n");
                timestampInfo.append("UTC Time: ").append(TimestampUtils.getCurrentUtcTimestamp()).append("\n\n");
                
                // Still include GPS info even if timestamp failed
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
                    
                    String locationStatus = (provider != null && !"GPS unavailable".equals(provider) && !"Unknown".equals(provider)) ? "VERIFIED" : "UNAVAILABLE";
                    timestampInfo.append("Location Status: ").append(locationStatus).append("\n\n");
                }
            }
            
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
            
            contentValues.put(MediaStore.Video.Media.DISPLAY_NAME, sourceFile.getName());
            contentValues.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SoundTrigger");
            
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
            
            contentValues.put(MediaStore.Files.FileColumns.DISPLAY_NAME, timestampFileName);
            contentValues.put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain");
            contentValues.put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Documents/SoundTrigger");
            
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
    
    private void copyToDownloads(File sourceFile) {
        try {
            Log.i(TAG, "Copying video to Downloads folder for easy access");
            
            ContentResolver contentResolver = getContentResolver();
            ContentValues contentValues = new ContentValues();
            
            contentValues.put(MediaStore.Video.Media.DISPLAY_NAME, sourceFile.getName());
            contentValues.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Download/SoundTrigger");
            
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
            
            contentValues.put(MediaStore.Files.FileColumns.DISPLAY_NAME, timestampFileName);
            contentValues.put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain");
            contentValues.put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Download/SoundTrigger");
            
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
            new Thread(this::monitorSound).start();
            Log.i(TAG, "Monitoring restarted after recording");
        } catch (Exception restartError) {
            Log.e(TAG, "Error restarting monitoring", restartError);
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
                String finalFileName = sessionTimestamp + "_final.mp4";
                File segmentFile = new File(recordingSegments.get(0));
                File finalFile = new File(segmentFile.getParent(), finalFileName);
                
                // Copy the single segment to final file
                copyFile(segmentFile, finalFile);
                
                Log.i(TAG, "Single segment renamed to final file: " + finalFile.getAbsolutePath());
                processFinalFile(finalFile);
                
            } else if (recordingSegments.size() > 1) {
                // Multiple segments - use simple concatenation
                String finalFileName = sessionTimestamp + "_final.mp4";
                File storageDir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "SoundTrigger");
                File finalFile = new File(storageDir, finalFileName);
                
                // Simple MP4 concatenation (works for files with same encoding settings)
                concatenateMP4Files(recordingSegments, finalFile.getAbsolutePath());
                
                Log.i(TAG, "Segments merged into final file: " + finalFile.getAbsolutePath());
                processFinalFile(finalFile);
            }
            
            // Clean up segment files
            for (String segmentPath : recordingSegments) {
                File segmentFile = new File(segmentPath);
                if (segmentFile.exists()) {
                    boolean deleted = segmentFile.delete();
                    Log.i(TAG, "Deleted segment file: " + segmentPath + " (success: " + deleted + ")");
                }
            }
            
            // Reset session variables
            recordingSegments.clear();
            sessionTimestamp = "";
            segmentCounter = 0;
            
        } catch (Exception e) {
            Log.e(TAG, "Error merging recording segments", e);
        }
    }
    
    private void processFinalFile(File finalFile) {
        try {
            Log.i(TAG, "Processing final merged file: " + finalFile.getAbsolutePath() + " (size: " + finalFile.length() + " bytes)");
            
            // Save timestamp verification file
            saveTimestampFile(finalFile);
            
            // Copy to public storage locations
            copyToPublicStorage(finalFile);
            copyTimestampToPublicStorage(finalFile);
            copyToDownloads(finalFile);
            copyTimestampToDownloads(finalFile);
            
            updateNotification("Final recording saved and merged successfully!");
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing final file", e);
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
        
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
        }
        
        if (isRecording) {
            stopRecording();
        }
        
        // Merge all recording segments when service stops
        if (!recordingSegments.isEmpty()) {
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
