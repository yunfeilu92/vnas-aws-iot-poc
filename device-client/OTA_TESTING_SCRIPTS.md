# OTA Testing Scripts

Automated scripts for creating and cleaning up AWS IoT OTA jobs using Dynamic Thing Groups.

## Scripts

### 1. `create-ota-job.sh` - Create OTA Job with Dynamic Thing Group

Creates OTA jobs targeting devices based on their firmware version using Dynamic Thing Groups.

**Usage:**
```bash
./create-ota-job.sh <from-version> <to-version>
```

**Examples:**
```bash
# Upgrade all 1.0.2 devices to 1.0.3
./create-ota-job.sh 1.0.2 1.0.3

# Upgrade all 1.0.3 devices to 1.0.4
./create-ota-job.sh 1.0.3 1.0.4

# Upgrade all 1.0.4 devices to 1.0.5
./create-ota-job.sh 1.0.4 1.0.5
```

**What it does:**
1. Checks if Dynamic Thing Group exists (e.g., `firmware-v1-0-2`)
2. Creates the group if it doesn't exist (query: `shadow.reported.firmwareVersion:1.0.2`)
3. Lists devices in the group
4. Creates test firmware file with timestamp
5. Uploads to S3 bucket `vnas-iot-firmware-497892281794`
6. Generates presigned URL (1 hour expiry)
7. Calculates SHA-256 checksum
8. Creates job document JSON
9. Creates AWS IoT Job targeting the Thing Group ARN

**Key Features:**
- ✅ Automatic device targeting based on firmware version
- ✅ Auto-creates Dynamic Thing Groups if missing
- ✅ Shows which devices will be upgraded before creating job
- ✅ Devices automatically leave the group after successful upgrade

**Output:**
- `test-firmware-v{to-version}.bin` - Test firmware file
- `job-document-v{to-version}.json` - Job document
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

### Quick Test Loop with Dynamic Thing Groups

```bash
# Terminal 1: Start device client (version 1.0.2)
cd device-client
java -jar target/device-client-1.0.0.jar \
  a10pn8i6q9mhvm-ats.iot.us-east-1.amazonaws.com \
  ota-test-device \
  certs/device.pem.crt \
  certs/device.pem.key \
  certs/AmazonRootCA1.pem

# Terminal 2: Create upgrade jobs
cd device-client

# Upgrade 1.0.2 → 1.0.3
./create-ota-job.sh 1.0.2 1.0.3
# Wait for upgrade to complete...

# Upgrade 1.0.3 → 1.0.4
./create-ota-job.sh 1.0.3 1.0.4
# Wait for upgrade to complete...

# Upgrade 1.0.4 → 1.0.5
./create-ota-job.sh 1.0.4 1.0.5
```

### Verify Dynamic Group Behavior

```bash
# Check devices in firmware-v1-0-2 group
aws iot list-things-in-thing-group --thing-group-name firmware-v1-0-2

# After upgrade completes, device should leave the group
sleep 10
aws iot list-things-in-thing-group --thing-group-name firmware-v1-0-2
# Should be empty

# Device should appear in new group
aws iot list-things-in-thing-group --thing-group-name firmware-v1-0-3
```

### Clean Up After Testing

```bash
# Cancel any stuck jobs
./cleanup-ota-jobs.sh

# Remove temporary files
rm -f test-firmware-v*.bin job-document-v*.json

# Delete test Thing Groups (optional)
aws iot delete-dynamic-thing-group --thing-group-name firmware-v1-0-2
aws iot delete-dynamic-thing-group --thing-group-name firmware-v1-0-3
aws iot delete-dynamic-thing-group --thing-group-name firmware-v1-0-4
```

---

## Dynamic Thing Group Details

### Naming Convention
- Format: `firmware-v{version-with-dashes}`
- Example: `1.0.2` → `firmware-v1-0-2`

### Query String
```
shadow.reported.firmwareVersion:{version}
```

### Behavior
- Devices automatically join when their Shadow matches the query
- Devices automatically leave when their Shadow changes
- Fleet Indexing must be enabled with Shadow indexing
- Index updates may take 5-10 seconds

---

## Troubleshooting

### Job Not Received by Device

Check if device is in the Thing Group:
```bash
aws iot list-things-in-thing-group --thing-group-name firmware-v1-0-2
```

If not in group, check Device Shadow:
```bash
aws iot-data get-thing-shadow --thing-name ota-test-device /tmp/shadow.json
cat /tmp/shadow.json | jq .state.reported.firmwareVersion
```

### Thing Group Empty

Fleet Indexing may need time to update (5-10 seconds). Wait and retry:
```bash
sleep 10
aws iot list-things-in-thing-group --thing-group-name firmware-v1-0-2
```

If still empty, verify Fleet Indexing is enabled:
```bash
aws iot get-indexing-configuration
```

Should show:
```json
{
  "thingIndexingConfiguration": {
    "thingIndexingMode": "REGISTRY_AND_SHADOW"
  }
}
```

### Presigned URL Expired

Presigned URLs expire after 1 hour. Re-create the job:
```bash
./create-ota-job.sh <same-from-version> <same-to-version>
```

### Device Doesn't Leave Group After Upgrade

Check if Shadow was updated:
```bash
aws iot-data get-thing-shadow --thing-name ota-test-device /tmp/shadow.json
cat /tmp/shadow.json | jq .state.reported.firmwareVersion
```

Fleet Indexing may be delayed. Wait 10 seconds:
```bash
sleep 10
aws iot list-things-in-thing-group --thing-group-name firmware-v1-0-2
```

---

## Configuration

Edit `create-ota-job.sh` to change defaults:

```bash
S3_BUCKET="vnas-iot-firmware-497892281794"
REGION="us-east-1"
PRESIGNED_URL_EXPIRY=3600  # 1 hour
CREATE_GROUP_IF_NOT_EXISTS=true  # Auto-create groups
```

Edit `cleanup-ota-jobs.sh` to change defaults:

```bash
REGION="us-east-1"
```

---

## Requirements

- AWS CLI v2 configured with credentials
- `shasum` (macOS/Linux) or `sha256sum` (Linux)
- S3 bucket with write permissions
- Fleet Indexing enabled with Shadow indexing
- AWS IoT Things with certificates attached
- Devices must report `firmwareVersion` in Device Shadow
