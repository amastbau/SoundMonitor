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

### Legal Verification System
- **Authoritative Timestamps**: Uses WorldTimeAPI (worldtimeapi.org) for tamper-proof time verification
- **GPS Location Proof**: Captures precise coordinates with provider information (GPS/Network/Passive)
- **Cryptographic Integrity**: SHA-256 hash ensures video hasn't been modified
- **Court-Ready Documentation**: Generates detailed timestamp files for legal evidence
- **Google Maps Integration**: Direct links to verify exact recording location
- **Multi-Provider Fallback**: Tries GPS → Network → Passive location providers

### Public Storage Access
- Uses MediaStore API for Android 10+ compatibility
- No WRITE_EXTERNAL_STORAGE permission required
- **Session-Based Organization**: Each recording session gets its own folder
- Videos automatically appear in device gallery/media apps
- Timestamp files saved to Documents folder organized by session
- Easy access via file managers and USB/MTP connections
- **Improved File Management**: Related files grouped together by recording session

### Legal Evidence Components
Each recording session generates organized files for court use:
1. **Session Folder**: `{session_id}/` containing all related files
2. **Video Files**: `01.mp4`, `02.mp4`, etc. - Individual recording segments
3. **Final Merged Video**: `FINAL.mp4` - Complete session recording
4. **Verification Files**: `*_timestamp.txt` - Legal proof for each segment containing:
   - Authoritative timestamp from WorldTimeAPI
   - GPS coordinates with Google Maps link
   - SHA-256 hash for integrity verification
   - File size and recording metadata
   - Legal notice explaining verification process
5. **Session Documentation**: `README.txt` - Complete explanation of all files
6. **Metadata Files**: `*_META.txt` and `*_SUB.srt` - Technical details and subtitles