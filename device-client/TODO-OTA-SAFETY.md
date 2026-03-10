# OTA Safety Issues TODO

## 1. [HIGH] Presigned URL Expiration (CONTINUOUS Job)

**Problem:** Job Document contains a presigned S3 URL with 1-hour expiry. CONTINUOUS Jobs run for days/months. New devices joining the group after 1 hour will get HTTP 403 on download.

**Recommended fix:** Use IoT Credentials Provider - device exchanges X.509 cert for temporary IAM credentials, then downloads directly from S3 using S3 key stored in Job Document. No URL expiration issue.

**File:** `create-ota-job.sh`, `FirmwareDownloader.java`, `OtaPackage.java`

---

## 2. [LOW] Firmware Downgrade Not Blocked

**Problem:** Version check only uses `equals()`. If a Job targets version 1.0.2 and device is on 1.0.4, the device will downgrade.

**Fix:** Add semantic version comparison. Reject if `pkg.getVersion() < currentVersion` (unless a `forceDowngrade` flag is set in Job Document).

**File:** `OtaDemo.java` - `onNewPackage()`

---

## Resolved

- ~~Device Restart Job Stuck~~ - Fixed: replaced blanket IN_PROGRESS skip with in-memory `currentJobId` dedup. After restart, currentJobId is null so IN_PROGRESS jobs are re-processed. OtaDemo version check handles both cases (already upgraded vs need retry). Commit `88f4821`.
- ~~Concurrent Upgrade~~ - Not an issue: AWS IoT Jobs `$next` mechanism guarantees one job at a time.
- ~~Version File Tampering~~ - Not applicable for demo; production devices read version from firmware binary.
