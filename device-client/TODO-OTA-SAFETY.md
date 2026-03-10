# OTA Safety Issues TODO

## 1. [HIGH] Concurrent Upgrade - No Mutex Lock

**Problem:** Two Jobs arriving close together can trigger two `CompletableFuture.runAsync()` simultaneously. Both download and install in parallel, result is unpredictable.

**Fix:** Add `AtomicBoolean upgrading` in OtaDemo's `onNewPackage()`. If already upgrading, reject the new Job.

**File:** `OtaDemo.java` - `onNewPackage()`

---

## 2. [HIGH] Device Restart - Job Stuck in IN_PROGRESS

**Problem:** Device reports IN_PROGRESS, then crashes/restarts. After reconnection, the Job is still IN_PROGRESS in AWS. OtaClient skips IN_PROGRESS Jobs, so the Job is never retried.

**Fix:** Allow IN_PROGRESS Jobs to be re-processed (remove the skip logic, or add a "resume" mechanism). The version check in OtaDemo already prevents duplicate installation if the previous attempt actually succeeded before the crash.

**File:** `OtaClient.java` - `handleJobNotification()` lines 143-147

---

## 3. [MEDIUM] Presigned URL Expiration (CONTINUOUS Job)

**Problem:** Job Document contains a presigned S3 URL with 1-hour expiry. CONTINUOUS Jobs run for days/months. New devices joining the group after 1 hour will get HTTP 403 on download.

**Fix Options:**
- a) Use S3 key in Job Document + generate presigned URL on device side (requires IAM credentials)
- b) Use CloudFront signed URL with longer expiry
- c) Use S3 bucket policy with public read for firmware prefix (simplest but less secure)
- d) Store S3 key in Job Document, have a Lambda generate fresh presigned URL on demand

**File:** `create-ota-job.sh`, `FirmwareDownloader.java`

---

## 4. [LOW] Firmware Downgrade Not Blocked

**Problem:** Version check only uses `equals()`. If a Job targets version 1.0.2 and device is on 1.0.4, the device will downgrade.

**Fix:** Add semantic version comparison. Reject if `pkg.getVersion() < currentVersion` (unless a `forceDowngrade` flag is set in Job Document).

**File:** `OtaDemo.java` - `onNewPackage()` lines 104-115

---

## 5. [LOW] Version File Tampering

**Problem:** `firmware_version.txt` is a plain text file. If manually modified, version check becomes unreliable. Device may skip a needed upgrade or re-install an already-installed version.

**Fix:** For production: use signed version metadata, secure storage, or derive version from actual firmware binary hash. For demo: acceptable as-is.

**File:** `OtaDemo.java` - `getCurrentVersion()`, `saveVersion()`
