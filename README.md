# 🎤 Sound Monitor - Automatic Sound-Triggered Recording

A powerful Android application that automatically records video when ambient sound levels exceed a configurable threshold. Perfect for security monitoring, event documentation, and capturing unexpected moments.

![Android](https://img.shields.io/badge/Android-API%2021+-3DDC84?style=flat&logo=android)
![License](https://img.shields.io/badge/License-MIT-blue.svg)
![Version](https://img.shields.io/badge/Version-1.0-green.svg)

## 🌟 Features

### 🎯 Core Functionality
- **Automatic Sound Detection**: Continuously monitors ambient audio levels
- **Smart Recording**: Automatically starts recording when sound exceeds threshold
- **Dual Recording Modes**: Video + Audio or Audio-only recording
- **Configurable Sensitivity**: Adjustable sound threshold (30-90 dB range)
- **Auto-Stop Timer**: Configurable timeout (1-30 seconds) after sound drops below threshold

### 📱 User Interface
- **Real-time dB Display**: Live audio level monitoring with color-coded indicators
- **Simple Controls**: One-button start/stop with intuitive status display
- **Camera Selection**: Switch between front and rear camera
- **Recording Mode Toggle**: Quick switch between video and audio-only modes

### 🔒 Hybrid Legal Verification System
- **Real-Time Timestamp Proof**: Creates verification BEFORE recording begins
- **RFC 3161 Certification**: Industry-standard legal timestamp certification
- **Network Time Enforcement**: Requires independent time verification (no local fallback)
- **Cryptographic Binding**: 256-bit seed links timestamp proof to video hash
- **GPS Location Proof**: Precise coordinates with accuracy metrics and Google Maps integration
- **Tamper-Proof Evidence**: Cryptographically impossible to forge timestamps post-recording
- **Court-Ready Documentation**: Comprehensive legal evidence files for proceedings
- **Fail-Safe Design**: Recording blocked if network time verification unavailable

### 📁 File Management
- **Public Storage Access**: Recordings automatically saved to Downloads/SoundTrigger/
- **MediaStore Integration**: Videos appear in device gallery automatically
- **USB/MTP Compatible**: Easy access when connected to computer
- **Organized Structure**: Each session creates separate folders with metadata

### 🛠️ Technical Features
- **Background Monitoring**: Foreground service for reliable operation
- **Permission Management**: Smart runtime permission requests
- **Network Testing**: Built-in connectivity and GPS diagnostics
- **Camera Preview**: Live camera stream for framing and testing
- **Scoped Storage**: Android 10+ compatible file storage

## 📋 Requirements

- **Android 5.0** (API level 21) or higher
- **Camera** access for video recording
- **Microphone** access for audio monitoring
- **Storage** access for saving recordings
- **Location** access for GPS verification (optional)
- **Network** access for timestamp verification (optional)

## 🚀 Installation

### From Source
1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/SoundMonitor.git
   cd SoundMonitor
   ```

2. Build and install:
   ```bash
   ./gradlew installDebug
   ```

### APK Installation
1. Download the latest APK from [Releases](https://github.com/your-username/SoundMonitor/releases)
2. Enable "Install from Unknown Sources" in Android settings
3. Install the APK file

## 📖 Usage Guide

### Initial Setup
1. **Grant Permissions**: Allow Camera, Microphone, Location, and Notification access
2. **Set Threshold**: Adjust the sound sensitivity slider (recommended: 50-70 dB)
3. **Choose Mode**: Select Video+Audio or Audio-only recording
4. **Select Camera**: Choose front or rear camera for video mode

### Basic Operation
1. **Start Monitoring**: Tap "Start Monitoring" button
2. **Automatic Recording**: App will automatically record when sound exceeds threshold
3. **Live Feedback**: Watch real-time dB levels and recording status
4. **Stop Monitoring**: Tap "Stop Monitoring" when done

### Recording Modes

#### 📹 Video Mode (Default)
- Records 1280x720 HD video at 30fps
- Includes audio track with clear sound quality
- 2Mbps bitrate for good quality/size balance
- Automatic timeout after sound level drops

#### 🎵 Audio-Only Mode
- Records high-quality audio files
- Smaller file sizes and longer battery life
- No automatic timeout (manual stop required)
- Perfect for voice recording or music capture

### Advanced Features

#### 🧪 Testing Tools
- **Network Test**: Verify internet connectivity for timestamp services
- **GPS Test**: Check location accuracy and provider status
- **Camera Preview**: Test camera and framing before recording

#### 📁 File Access
- **Recordings Button**: Quick access to recorded files
- **Downloads Folder**: Navigate to Downloads/SoundTrigger/
- **USB Transfer**: Connect to computer for easy file management

## 📂 File Structure

```
Downloads/SoundTrigger/
├── video_YYYYMMDD_HHMMSS.mp4          # Video recordings
├── video_YYYYMMDD_HHMMSS_timestamp.txt # Legal verification
├── audio_YYYYMMDD_HHMMSS.m4a          # Audio recordings
└── audio_YYYYMMDD_HHMMSS_timestamp.txt # Audio verification
```

### Hybrid Verification Files
Each recording includes comprehensive legal evidence documentation:
- **Recording Start Proof**: Created BEFORE video recording begins
- **Network Time Authority**: Independent time source verification (TimeAPI.io, etc.)
- **RFC 3161 Certificate**: Industry-standard legal timestamp certification  
- **Cryptographic Seed**: 256-bit hash binding timestamp to video
- **GPS Coordinates**: Precise location with accuracy metrics and Google Maps links
- **Video Verification**: Proof that video was recorded after timestamp creation
- **Legal Explanation**: Step-by-step verification process for court presentation
- **File Hash**: SHA-256 with cryptographic seed for tamper detection
- **Backward Compatibility**: Legacy timestamp format included

## ⚖️ Legal Evidence Features

### Hybrid Verification System
- **Pre-Recording Proof**: Timestamp created BEFORE any video data exists
- **Cryptographic Binding**: 256-bit seed mathematically links proof to video
- **Network Time Enforcement**: Zero tolerance for manipulatable local device time
- **RFC 3161 Certification**: Industry-standard legal timestamp servers
- **Fail-Safe Operation**: Recording blocked without independent time verification

### Cryptographic Verification
- **Seeded SHA-256 Hashing**: Video hash includes cryptographic seed from start proof
- **Tamper Detection**: Any modification changes the hash completely
- **Integrity Guarantee**: Mathematical proof video matches original timestamp
- **Post-Recording Verification**: Proves video was created after start proof

### Time Verification
- **HTTPS-Only Providers**: TimeAPI.io, IPGeolocation, TimezoneDB
- **20-Second Mobile Timeout**: Optimized for cellular networks
- **No Local Fallback**: Prevents "I changed my phone's clock" legal defense
- **Authority Documentation**: Records exact time source and certificate
- **Legal-Grade Standards**: Court-admissible timestamp certification

### Location Proof
- **Multi-Provider GPS**: GPS → Network → Passive location fallback
- **Accuracy Metrics**: Precision measurements with age verification
- **Google Maps Integration**: Direct links for location verification
- **Plus Codes**: Alternative location verification system
- **Provider Documentation**: Records GPS source and quality metrics

### Court Readiness
- **Expert Witness Support**: Technical documentation for testimony
- **Verification Steps**: Complete instructions for legal validation
- **Standards Compliance**: RFC 3161, SHA-256, GPS industry standards
- **Unbreakable Chain**: Cryptographically impossible to forge evidence
- **Clear Documentation**: Legal explanations for non-technical audiences

## 🔧 Technical Specifications

### Audio Processing
- **Sample Rate**: 44.1kHz (CD quality)
- **Format**: 16-bit PCM, Mono channel
- **Threshold Range**: 30-90 dB with 1 dB precision
- **Update Rate**: 100ms real-time monitoring
- **Source**: MediaRecorder.AudioSource.MIC

### Video Recording
- **Resolution**: 1280x720 (HD)
- **Frame Rate**: 30fps constant
- **Bitrate**: 2Mbps for quality/size balance
- **Format**: H.264/AAC in MP4 container
- **Camera API**: Legacy API for background compatibility

### Storage & Compatibility
- **Target SDK**: 33 (Android 13)
- **Minimum SDK**: 21 (Android 5.0)
- **Storage**: MediaStore API (Android 10+ compatible)
- **Permissions**: Runtime permission system
- **Background**: Foreground service for reliability

## 🔍 Troubleshooting

### Common Issues

#### Recording Not Starting
- Check microphone permissions
- Verify sound threshold isn't too high
- Ensure app isn't in battery optimization
- Test with Camera Preview first

#### Files Not Found
- Use "Open Recording Folder" button
- Check Downloads/SoundTrigger directory
- Verify storage permissions granted
- Try USB connection to computer

#### Poor Audio Quality
- Move closer to sound source
- Reduce background noise
- Check microphone isn't blocked
- Adjust threshold sensitivity

#### Network Time Verification Failed
- Check internet connectivity (WiFi + mobile data)
- Use Network Test feature to diagnose issues
- Recording will be BLOCKED without network time
- Ensure TimeAPI.io, IPGeolocation, or TimezoneDB are accessible
- Check firewall/proxy settings blocking HTTPS requests

### Performance Tips
- **Battery Optimization**: Disable for app in Android settings
- **Storage Space**: Ensure adequate free space available
- **Network Connection**: WiFi recommended for timestamp services
- **Device Performance**: Close unnecessary apps during recording

## 🛡️ Privacy & Security

- **Local Processing**: All audio analysis done on-device
- **No Cloud Storage**: Recordings stay on your device
- **Network Usage**: Only for timestamp verification (optional)
- **Permission Control**: Only requests necessary permissions
- **Data Protection**: Files stored in secure app directory

## 🔄 Updates & Changelog

### Version 1.0 (Current)
- ✅ Automatic sound-triggered recording
- ✅ Dual recording modes (Video + Audio-only)
- ✅ **Hybrid Timestamp Verification System** (NEW!)
- ✅ **RFC 3161 Legal Certification** (NEW!)
- ✅ **Network Time Enforcement** - No local fallback (NEW!)
- ✅ **Cryptographic Proof Binding** (NEW!)
- ✅ GPS location proof with accuracy metrics
- ✅ Real-time audio monitoring with color coding
- ✅ Configurable sensitivity and timeout
- ✅ Public storage with MediaStore API
- ✅ Background service operation
- ✅ Network and GPS testing tools
- ✅ Camera preview and selection
- ✅ HTTPS-only time providers for security

## 🤝 Contributing

Contributions are welcome! Please feel free to submit pull requests or open issues for bugs and feature requests.

### Development Setup
```bash
# Clone the repository
git clone https://github.com/your-username/SoundMonitor.git

# Build the project
./gradlew build

# Install on device
./gradlew installDebug

# Run tests
./gradlew test
```

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- **TimeAPI.io** for reliable HTTPS timestamp services
- **RFC 3161** timestamp server protocol for legal certification
- **Android MediaStore API** for modern storage access
- **Google Location Services** for GPS verification
- **SHA-256** cryptographic hashing for integrity
- **IPGeolocation** and **TimezoneDB** for backup time verification

## 📞 Support

For support, questions, or feature requests:
- Open an [Issue](https://github.com/your-username/SoundMonitor/issues)
- Check [Troubleshooting](#troubleshooting) section
- Review [Usage Guide](#usage-guide)

---

**Made with ❤️ for Android security and documentation needs**