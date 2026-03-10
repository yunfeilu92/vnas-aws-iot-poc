# OTA Safety Issues TODO

## 1. [HIGH] Device Restart - Job Stuck in IN_PROGRESS

**Problem:** Device reports IN_PROGRESS, then crashes/restarts. After reconnection, the Job is still IN_PROGRESS in AWS. OtaClient skips IN_PROGRESS Jobs, so the Job is never retried.

**Fix:** Allow IN_PROGRESS Jobs to be re-processed (remove the skip logic, or add a "resume" mechanism). The version check in OtaDemo already prevents duplicate installation if the previous attempt actually succeeded before the crash.

**File:** `OtaClient.java` - `handleJobNotification()` lines 143-147

---

## 2. [HIGH] Presigned URL Expiration (CONTINUOUS Job)

**Problem:** Job Document contains a presigned S3 URL with 1-hour expiry. CONTINUOUS Jobs run for days/months. New devices joining the group after 1 hour will get HTTP 403 on download.

**Recommended fix:** Use IoT Credentials Provider - device exchanges X.509 cert for temporary IAM credentials, then downloads directly from S3 using S3 key stored in Job Document. No URL expiration issue.

**File:** `create-ota-job.sh`, `FirmwareDownloader.java`, `OtaPackage.java`

---

## 3. [LOW] Firmware Downgrade Not Blocked

**Problem:** Version check only uses `equals()`. If a Job targets version 1.0.2 and device is on 1.0.4, the device will downgrade.

**Fix:** Add semantic version comparison. Reject if `pkg.getVersion() < currentVersion` (unless a `forceDowngrade` flag is set in Job Document).

**File:** `OtaDemo.java` - `onNewPackage()`

---

## Resolved / Not Applicable

- ~~Concurrent Upgrade~~ - AWS IoT Jobs `$next` mechanism guarantees one job at a time, no device-side mutex needed.
- ~~Version File Tampering~~ - Demo uses plain text file; production devices read version from firmware binary, not applicable here.
