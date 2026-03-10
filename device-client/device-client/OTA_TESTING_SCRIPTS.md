# OTA Testing Scripts

Automated scripts for creating and cleaning up AWS IoT OTA jobs.

## Scripts

### 1. `create-ota-job.sh` - Create OTA Job

Creates a complete OTA job automatically: firmware file → S3 upload → presigned URL → job creation.

**Usage:**
```bash
./create-ota-job.sh <target-version> [thing-name]
```

**Examples:**
```bash
# Upgrade to version 1.0.4 (default thing: ota-test-device)
./create-ota-job.sh 1.0.4

# Upgrade to version 1.0.5 for specific thing
./create-ota-job.sh 1.0.5 my-device-001

# Use default version (1.0.3) and default thing
./create-ota-job.sh
```

**What it does:**
1. Creates test firmware file with timestamp
2. Uploads to S3 bucket `vnas-iot-firmware-497892281794`
3. Generates presigned URL (1 hour expiry)
4. Calculates SHA-256 checksum
5. Creates job document JSON
6. Creates AWS IoT Job targeting the thing

**Output:**
- `test-firmware-v{version}.bin` - Test firmware file
- `job-document-v{version}.json` - Job document
- Job ID and helpful AWS CLI commands

---

### 2. `cleanup-ota-jobs.sh` - Clean Up Jobs

Cancels all IN_PROGRESS and QUEUED job executions for a thing.

**Usage:**
```bash
./cleanup-ota-jobs.sh [thing-name]
```

**Examples:**
```bash
# Clean up jobs for default thing (ota-test-device)
./cleanup-ota-jobs.sh

# Clean up jobs for specific thing
./cleanup-ota-jobs.sh my-device-001
```

**What it does:**
1. Lists all active job executions (IN_PROGRESS + QUEUED)
2. Prompts for confirmation
3. Cancels all matched executions with `--force`

---

## Testing Workflow

### Quick Test Loop

```bash
# Terminal 1: Start device client
java -jar target/device-client-1.0.0.jar \
  a10pn8i6q9mhvm-ats.iot.us-east-1.amazonaws.com \
  ota-test-device \
  certs/device.pem.crt \
  certs/device.pem.key \
  certs/AmazonRootCA1.pem

# Terminal 2: Create OTA jobs
./create-ota-job.sh 1.0.4   # First test
./create-ota-job.sh 1.0.5   # Second test
./create-ota-job.sh 1.0.6   # Third test
```

### Clean Up After Testing

```bash
# Cancel any stuck jobs
./cleanup-ota-jobs.sh

# Remove temporary files
rm -f test-firmware-v*.bin job-document-v*.json
```

---

## Troubleshooting

### Job Not Received by Device

Check job status:
```bash
aws iot list-job-executions-for-thing --thing-name ota-test-device
```

### Presigned URL Expired

Presigned URLs expire after 1 hour. Re-create the job:
```bash
./create-ota-job.sh <same-version>
```

### Multiple Jobs Queued

Cancel old jobs before creating new ones:
```bash
./cleanup-ota-jobs.sh
```

---

## Configuration

Edit scripts to change defaults:

**In `create-ota-job.sh`:**
```bash
S3_BUCKET="vnas-iot-firmware-497892281794"
REGION="us-east-1"
PRESIGNED_URL_EXPIRY=3600  # 1 hour
```

**In `cleanup-ota-jobs.sh`:**
```bash
REGION="us-east-1"
```

---

## Requirements

- AWS CLI v2 configured with credentials
- `shasum` (macOS/Linux) or `sha256sum` (Linux)
- S3 bucket with write permissions
- AWS IoT Thing created with certificates attached
