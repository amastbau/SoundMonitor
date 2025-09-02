# Pull Request

## Description
Brief description of changes made.

## Type of Change
- [ ] Bug fix (non-breaking change which fixes an issue)
- [ ] New feature (non-breaking change which adds functionality)
- [ ] Breaking change (fix or feature that would cause existing functionality to not work as expected)
- [ ] Documentation update
- [ ] Performance improvement
- [ ] Security enhancement

## Critical Regression Prevention Checklist
**MANDATORY:** All items must be checked for legal evidence integrity.

### 🚨 Zero Local Time Policy
- [ ] No usage of `TimestampUtils.getCurrentUtcTimestamp()` in critical paths
- [ ] No `Local device time` references in timestamp-related code
- [ ] No `fallback` to local time in any scenario
- [ ] All timestamps use network-verified time sources only

### 🌐 Network Time Requirements  
- [ ] Only approved providers: TimeAPI.io, IPGeolocation, TimezoneDB
- [ ] No WorldTimeAPI.org usage (unreliable)
- [ ] Network timeout properly configured (30+ seconds for mobile)
- [ ] Retry logic implemented for network failures
- [ ] Recording blocked without network time verification

### 🎥 Video Stability
- [ ] MediaRecorder `setMaxDuration()` configured (prevents freezing)
- [ ] MediaRecorder `setMaxFileSize()` configured (prevents freezing) 
- [ ] Conservative bitrate settings (≤ 2Mbps)
- [ ] Error listeners implemented for MediaRecorder
- [ ] Proper camera resource cleanup

### 📁 File Organization
- [ ] Session-based folder structure maintained
- [ ] Unique file naming patterns (MMDD_HHMM format)
- [ ] No duplicate file creation patterns
- [ ] Companion files properly named (_timestamp.txt, _SUB.srt, _META.txt)
- [ ] MediaStore API used correctly for public storage

### ⚖️ Legal Evidence Standards
- [ ] Cryptographic hash binding maintained
- [ ] Recording start proof created BEFORE video recording
- [ ] RFC 3161 integration preserved (if applicable)
- [ ] GPS location proof included
- [ ] Legal documentation explains verification process

## Testing
- [ ] Critical regression prevention tests pass
- [ ] All unit tests pass  
- [ ] Integration tests pass (if applicable)
- [ ] Manual testing completed
- [ ] No new test failures introduced

## Code Quality
- [ ] Code follows existing patterns and conventions
- [ ] No hardcoded values (use constants/configuration)
- [ ] Proper error handling implemented
- [ ] Logging added for debugging (if needed)
- [ ] Comments added for complex logic

## Security
- [ ] No sensitive data logged or exposed
- [ ] Permissions properly handled
- [ ] Network requests use HTTPS only
- [ ] Input validation implemented (if applicable)
- [ ] No security vulnerabilities introduced

## Performance
- [ ] No memory leaks introduced
- [ ] Efficient resource usage
- [ ] Background processing optimized
- [ ] Battery usage considerations addressed

## Documentation
- [ ] Code changes documented (if needed)
- [ ] README updated (if applicable)
- [ ] API documentation updated (if applicable)
- [ ] Test coverage documentation updated

## Manual Testing Checklist
- [ ] App starts and stops correctly
- [ ] Audio monitoring works as expected
- [ ] Video recording starts/stops properly
- [ ] Files are saved to correct locations
- [ ] Timestamp verification works
- [ ] Network time verification enforced
- [ ] Error scenarios handled gracefully

## Breaking Changes
List any breaking changes and migration steps needed.

## Additional Notes
Any additional information, concerns, or considerations for reviewers.

---

**⚠️ CRITICAL:** This PR will be automatically tested for all regression patterns. Failure of critical tests will block merge.

**📋 Reviewer Notes:**
- Verify all checkboxes are completed
- Run critical regression tests locally
- Test on physical device if possible
- Check for any new patterns that could cause regressions