# Security Policy

## Supported Versions

We support security updates for the following versions:

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | ✅ Current         |

## Critical Security Features

### Legal Evidence Integrity
- **Zero Local Time Policy**: Prevents timestamp manipulation that could invalidate legal evidence
- **Network Time Verification**: Requires independent time sources for court admissibility
- **Cryptographic Binding**: SHA-256 with seed prevents post-recording tampering
- **RFC 3161 Integration**: Industry-standard legal timestamp certification

### Data Protection
- **Local Storage Only**: No cloud storage or data transmission
- **Encrypted Storage**: Sensitive data encrypted at rest
- **Permission Minimization**: Only requests necessary Android permissions
- **Network Security**: HTTPS-only for time verification services

## Reporting a Vulnerability

### How to Report
1. **Email**: Send security reports to the project maintainers
2. **GitHub Issues**: For non-sensitive security improvements
3. **Private Contact**: For critical vulnerabilities requiring immediate attention

### Response Timeline
- **Acknowledgment**: Within 24 hours
- **Initial Assessment**: Within 48 hours  
- **Fix Development**: Within 7 days for critical issues
- **Patch Release**: Within 14 days for critical issues

### What to Include
- Description of the vulnerability
- Steps to reproduce the issue
- Potential impact assessment
- Suggested fix (if available)

## Security Considerations

### Legal Evidence Vulnerabilities
**CRITICAL**: Any vulnerability that could compromise legal evidence integrity is treated as maximum severity.

Examples:
- Local device time usage (timestamp manipulation)
- Cryptographic hash bypass
- File tampering possibilities
- Network time verification bypass

### Privacy Concerns
- GPS location data handling
- Audio/video data storage
- Metadata exposure
- Permission abuse

### Network Security
- Time server communication
- Man-in-the-middle attacks
- Certificate validation
- Data transmission security

## Security Testing

### Automated Checks
- CodeQL security scanning
- Dependency vulnerability scanning
- Permission usage analysis
- Network security validation

### Manual Testing
- Penetration testing for legal evidence integrity
- Privacy impact assessment
- Permission usage review
- Data flow analysis

## Security Best Practices

### For Users
1. Keep the app updated to latest version
2. Review and understand permissions requested
3. Ensure device has strong lock screen protection
4. Regular device security updates
5. Use strong WiFi security for network time verification

### For Developers
1. Follow zero local time policy strictly
2. Use only approved network time providers
3. Implement proper error handling for security failures
4. Regular security code reviews
5. Keep dependencies updated

## Threat Model

### Assets to Protect
- **Legal Evidence Integrity**: Primary concern
- **User Privacy**: Audio/video recordings
- **Location Data**: GPS coordinates
- **Timestamp Accuracy**: Time verification data

### Potential Attackers
- **Legal Adversaries**: Attempting to invalidate evidence
- **Privacy Invasive**: Seeking access to recordings
- **Malicious Apps**: Attempting to interfere with recording
- **Network Attackers**: MITM attacks on time verification

### Attack Vectors
- **Local Time Manipulation**: Changing device clock
- **File System Access**: Modifying recorded files
- **Network Interception**: Compromising time verification
- **Permission Abuse**: Excessive access requests

## Incident Response

### Critical Security Issues
1. **Immediate Response**: Disable affected functionality
2. **User Notification**: Alert users of potential risks
3. **Patch Development**: Emergency fix development
4. **Release Process**: Expedited security release
5. **Post-Incident Review**: Prevent future occurrences

### Non-Critical Issues
1. **Assessment**: Evaluate impact and priority
2. **Planning**: Include in next regular release
3. **Testing**: Thorough security testing
4. **Documentation**: Update security documentation

## Compliance

### Legal Evidence Standards
- **RFC 3161**: Timestamp protocol compliance
- **SHA-256**: Cryptographic hash standards
- **GPS Standards**: Location accuracy requirements
- **Court Admissibility**: Legal evidence requirements

### Privacy Regulations
- **Data Minimization**: Collect only necessary data
- **Purpose Limitation**: Use data only for stated purpose
- **Storage Limitation**: Delete data when no longer needed
- **Transparency**: Clear privacy practices

## Contact

For security-related questions or reports:
- **Issues**: GitHub Issues for general security questions
- **Email**: Direct contact for sensitive vulnerabilities
- **Documentation**: This security policy for guidelines

---

**Note**: This application is designed for legal evidence collection. Security is paramount to maintain court admissibility and user trust.