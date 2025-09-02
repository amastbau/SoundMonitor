# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development Commands

### Building the Project
```bash
./gradlew build
```

### Running on Device/Emulator
```bash
./gradlew installDebug
```

### Clean Build
```bash
./gradlew clean
./gradlew build
```

### Generate APK
```bash
./gradlew assembleDebug      # Debug APK
./gradlew assembleRelease    # Release APK
```

### Uninstall and Reinstall (for permission changes)
```bash
./gradlew uninstallDebug
./gradlew installDebug
```

## Project Architecture

This is an Android application called "Sound Monitor" that records video when sound levels exceed a configurable threshold. The app uses a foreground service for continuous audio monitoring and automatically saves recordings to public storage for easy access.

### Core Components

**MainActivity** (`app/src/main/java/com/soundmonitor/app/MainActivity.java`)
- Main UI activity with threshold configuration (0-100 dB seekbar)
- Handles runtime permissions for RECORD_AUDIO, CAMERA, POST_NOTIFICATIONS
- Controls SoundMonitorService lifecycle

**SoundMonitorService** (`app/src/main/java/com/soundmonitor/app/SoundMonitorService.java`)
- Foreground service that continuously monitors audio levels using AudioRecord
- Automatically starts/stops video recording based on sound threshold
- Records video at 1280x720, 30fps using Camera API + MediaRecorder
- Implements 1-minute delay before stopping recording when sound drops below threshold
- Automatically copies recordings to public storage using MediaStore API

### Key Configuration
- **Target SDK**: 33, **Min SDK**: 21
- **Namespace**: `com.soundmonitor.app`
- **Audio Settings**: 44.1kHz sample rate, mono channel, 16-bit PCM
- **Video Output**: H.264/AAC in MP4 container
- **Video Resolution**: 1280x720 at 30fps, 2Mbps bitrate

### Permissions Required
- `RECORD_AUDIO` - For sound level monitoring
- `CAMERA` - For video recording  
- `FOREGROUND_SERVICE` - For background monitoring
- `WAKE_LOCK` - For keeping service active
- `POST_NOTIFICATIONS` - For foreground service notifications (Android 13+)
- `INTERNET` - For authoritative time verification
- `ACCESS_FINE_LOCATION` - For GPS location proof
- `ACCESS_COARSE_LOCATION` - For network-based location backup

### Hardware Features
- `android.hardware.camera` (required="false") - Camera access
- `android.hardware.microphone` (required="false") - Microphone access

### File Storage
- **Private Storage**: `Android/data/com.soundmonitor.app/files/Movies/SoundTrigger/{session_id}/`
- **Public Storage**: Session-organized folders using MediaStore API
  - **Videos**: `Movies/SoundTrigger/{session_id}/` (MP4 files)
  - **Timestamp Files**: `Documents/SoundTrigger/{session_id}/` (legal verification)
  - **Session Naming**: `MMDD_HHMM` format (e.g., `0902_1430` for Sep 2nd at 2:30pm)
- **File Format**: MP4 with H.264 video and AAC audio
- **Typical File Size**: 12MB+ for 1-minute recordings
- **Session Organization**: Each recording session creates its own folder for easy file management

### Camera Integration
- Uses deprecated Camera API (legacy) for background recording compatibility
- Implements SurfaceTexture for camera preview without UI surface
- Properly handles camera resource management and cleanup
- Unlocks camera for MediaRecorder usage

### Audio Monitoring
- Stops AudioRecord monitoring during video recording to prevent microphone conflicts
- Automatically restarts monitoring after recording stops
- Real-time decibel calculation and threshold detection
- Continuous 44.1kHz audio sampling at 100ms intervals
- Uses MediaRecorder.AudioSource.MIC for reliable audio recording in videos

### Video Recording Stability
- **Fixed Video Freezing**: Resolved 1-minute freeze issues with proper MediaRecorder limits
- **Automatic Segmentation**: 10-minute max duration, 100MB max file size per segment
- **Enhanced Camera Optimization**: Recording hint, video stabilization, continuous focus
- **Error Recovery**: Automatic restart on MediaRecorder errors
- **Resource Management**: Proper cleanup and timeout handling
- **Optimized Settings**: 1280x720@30fps, 2Mbps for stability and quality balance

### Hybrid Timestamp Verification System (Legal-Grade Evidence)
- **Real-Time Verification**: Creates recording start proof BEFORE video recording begins
- **Zero Local Time Policy**: COMPLETE elimination of local device time usage for legal compliance
- **Network Time Enforcement**: Recording BLOCKED if network verification fails (no fallback)
- **RFC 3161 Integration**: Industry-standard legal timestamp certification
- **Cryptographic Binding**: 256-bit seed links timestamp proof to video file hash
- **Enhanced Network Reliability**: 30s timeout, 2 retry attempts, 5 reliable HTTPS providers
- **Reliable Time Providers**: TimeAPI.io, IPGeolocation, TimezoneDB (WorldTimeAPI removed - unreliable)
- **GPS Location Proof**: Captures precise coordinates with provider information and accuracy metrics
- **Tamper-Proof Evidence**: Cryptographically impossible to forge timestamps post-recording
- **Court-Ready Documentation**: Generates comprehensive verification files for legal proceedings
- **Google Maps Integration**: Direct links to verify exact recording location
- **Fail-Safe Design**: Recording blocked completely if network time verification unavailable

### Public Storage Access
- Uses MediaStore API for Android 10+ compatibility
- No WRITE_EXTERNAL_STORAGE permission required
- **Session-Based Organization**: Each recording session gets its own folder
- Videos automatically appear in device gallery/media apps
- Timestamp files saved to Documents folder organized by session
- Easy access via file managers and USB/MTP connections
- **Improved File Management**: Related files grouped together by recording session

### Legal Evidence Components
Each recording session generates court-admissible evidence files:
1. **Session Folder**: `{session_id}/` containing all related files
2. **Video Files**: `01.mp4`, `02.mp4`, etc. - Individual recording segments
3. **Final Merged Video**: `FINAL.mp4` - Complete session recording
4. **Hybrid Verification Files**: `*_timestamp.txt` - Comprehensive legal proof containing:
   - **Recording Start Proof**: Created BEFORE video recording begins
   - **Network Time Authority**: Independent time source verification (TimeAPI.io, etc.)
   - **RFC 3161 Certificate**: Industry-standard legal timestamp certification
   - **Cryptographic Seed**: 256-bit hash binding timestamp to video
   - **GPS Coordinates**: Precise location with accuracy metrics and Google Maps links
   - **Video Verification**: Proof that video was recorded after timestamp creation
   - **Legal Explanation**: Step-by-step verification process for court presentation
5. **Integrity Verification**: SHA-256 hash with cryptographic seed for tamper detection
6. **Session Documentation**: Complete explanation of verification methodology
7. **Backward Compatibility**: Legacy timestamp format included for existing systems

### Core Services Architecture

**TimestampService** (`app/src/main/java/com/soundmonitor/app/TimestampService.java`)
- Network time verification with 5 reliable HTTPS providers (WorldTimeAPI removed)
- Enhanced reliability: 30s timeout, 2 retry attempts with 2s delay
- GPS location capture with accuracy and age metrics
- Detailed error logging and exception tracking
- Traditional timestamp generation for backward compatibility

**HybridTimestampService** (`app/src/main/java/com/soundmonitor/app/HybridTimestampService.java`) 
- Real-time recording start proof generation BEFORE any video recording
- RFC 3161 timestamp server integration for legal certification
- Cryptographic video verification against start proof (256-bit seed)
- Legal evidence document formatting with verification steps
- CRITICAL: Network time enforcement - recording BLOCKED without verification
- Zero tolerance for local device time (prevents legal challenges)

**SoundMonitorService** (`app/src/main/java/com/soundmonitor/app/SoundMonitorService.java`)
- Enhanced with hybrid verification integration and video stability fixes
- Two-phase recording: verification → recording (prevents weak evidence)
- Improved MediaRecorder configuration with limits and error handling
- Camera optimization with recording hints and stabilization

## Recent Critical Fixes (Latest Updates)

### CRITICAL SECURITY FIXES
- **Complete Local Time Elimination**: All local device time usage removed for legal compliance
- **Network Time Enforcement**: Recording now BLOCKS completely if network verification fails
- **WorldTimeAPI Removal**: Problematic WorldTimeAPI.org removed (unreliable service)
- **Enhanced Provider List**: Now uses 5 reliable HTTPS-only time providers

### VIDEO RECORDING STABILITY FIXES  
- **Video Freezing Resolved**: Fixed 1-minute freeze issues with proper MediaRecorder limits
- **Automatic Segmentation**: 10-minute duration and 100MB file size limits prevent resource exhaustion
- **Enhanced Error Handling**: MediaRecorder error listeners with automatic recovery
- **Camera Optimization**: Recording hints, video stabilization, and continuous focus mode

### NETWORK RELIABILITY IMPROVEMENTS
- **Increased Timeout**: 30-second timeout per provider (was 20s)
- **Retry Logic**: 2 attempts with 2-second delay between retries  
- **Better Error Logging**: Detailed exception tracking and response code logging
- **Provider Diversity**: Multiple endpoint formats (JSON, XML) for maximum reliability

### LEGAL EVIDENCE ENHANCEMENTS
- **Two-Phase Recording**: Verification must succeed before any recording begins
- **Cryptographic Binding**: 256-bit seed links timestamp proof to video hash
- **Court-Ready Documentation**: Comprehensive legal verification files
- **Zero Weak Evidence**: Better no evidence than challengeable evidence