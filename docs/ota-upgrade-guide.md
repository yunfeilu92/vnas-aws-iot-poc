# OTA Firmware Upgrade via AWS IoT Job

## Flow Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│  1. Preparation                                                     │
│                                                                     │
│   Operator                                                          │
│     │                                                               │
│     ├──① Upload firmware-v1.1.0.bin ──▶ [ S3 Bucket ]              │
│     │                                                               │
│     └──② Upload Job Document (JSON) ──▶ S3 /jobs/                  │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  2. Device Selection (Dynamic Thing Group)                          │
│                                                                     │
│   Thing-A (shadow fw:1.0.2) ─┐                                     │
│   Thing-B (shadow fw:1.0.2) ─┤                                     │
│   Thing-C (shadow fw:1.0.3) ─┼▶ [ Fleet Indexing ]                │
│   Thing-D (shadow fw:1.0.2) ─┤         │                           │
│   Thing-E (shadow fw:1.1.0) ─┘         │ query: shadow.reported    │
│                                         │   .firmwareVersion:1.0.2  │
│                                   ▼                                 │
│                        ┌─────────────────────┐                      │
│                        │ Dynamic Thing Group  │                      │
│                        │ "firmware-v1.0.2"    │                      │
│                        │                     │                      │
│                        │  Thing-A, B, D       │  ◀── auto-populated │
│                        └─────────────────────┘                      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  3. Job Dispatch                                                    │
│                                                                     │
│   Operator                                                          │
│     │                                                               │
│     └──③ create-job ──▶ [ IoT Core - IoT Job ]                    │
│                              │                                      │
│                              ├── target: Dynamic Thing Group        │
│                              ├── jobDocument: s3://bucket/job.json  │
│                              │                                      │
│                              ▼                                      │
│                    IoT Core resolves Job Document:                   │
│                    ${aws:iot:s3-presigned-url:...}                   │
│                         → generates presigned URL                   │
│                              │                                      │
│                              ▼                                      │
│               ┌──── MQTT $notify/next ────┐                        │
│               ▼              ▼             ▼                        │
│           Thing-A        Thing-B       Thing-D                      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  4. Device Execution                                                │
│                                                                     │
│   Device (e.g. Thing-A)                                             │
│     │                                                               │
│     ├──④ Receive Job notification (MQTT)                           │
│     │     → status: IN_PROGRESS                                     │
│     │                                                               │
│     ├──⑤ Download firmware via presigned URL from S3               │
│     │                                                               │
│     ├──⑥ Verify checksum (sha256)                                  │
│     │                                                               │
│     ├──⑦ Install firmware & reboot                                 │
│     │                                                               │
│     ├──⑧ Report new version via Shadow: firmwareVersion = "1.1.0"  │
│     │     (MQTT publish to $aws/things/{name}/shadow/update)       │
│     │                                                               │
│     └──⑨ Report Job status: SUCCEEDED                              │
│                                                                     │
│   Result:                                                           │
│     Thing-A auto-exits "firmware-v1.0.2" group                     │
│     (Fleet Indexing re-indexes → no longer matches query)          │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## Prerequisites

```bash
export AWS_REGION="ap-southeast-1"
export AWS_ACCOUNT_ID="123456789012"
export S3_BUCKET="vnas-iot-firmware"
export IOT_JOB_ROLE_NAME="vnas-iot-job-s3-role"
```

## 1. Create S3 Bucket

```bash
aws s3api create-bucket \
  --bucket "$S3_BUCKET" \
  --region "$AWS_REGION" \
  --create-bucket-configuration LocationConstraint="$AWS_REGION"
```

## 2. Create IAM Role for IoT Job

IoT Core needs this role to generate presigned URLs from S3.

```bash
# Trust policy — allow IoT to assume this role
cat > /tmp/iot-job-trust.json << 'EOF'
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Service": "iot.amazonaws.com" },
    "Action": "sts:AssumeRole"
  }]
}
EOF

aws iam create-role \
  --role-name "$IOT_JOB_ROLE_NAME" \
  --assume-role-policy-document file:///tmp/iot-job-trust.json

# Permission — allow reading firmware from S3
cat > /tmp/iot-job-s3-policy.json << EOF
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["s3:GetObject"],
    "Resource": "arn:aws:s3:::${S3_BUCKET}/*"
  }]
}
EOF

aws iam put-role-policy \
  --role-name "$IOT_JOB_ROLE_NAME" \
  --policy-name S3FirmwareReadAccess \
  --policy-document file:///tmp/iot-job-s3-policy.json
```

## 3. Enable Fleet Indexing

Required for Dynamic Thing Group to work.

```bash
aws iot update-indexing-configuration \
  --thing-indexing-configuration \
    thingIndexingMode=REGISTRY_AND_SHADOW,thingConnectivityIndexingMode=STATUS
```

> Wait ~30 seconds for indexing to initialize. Verify:

```bash
aws iot get-indexing-configuration
```

## 4. Register Thing & Initialize Shadow

Create thing with static attributes only:

```bash
aws iot create-thing \
  --thing-name "device-001" \
  --attribute-payload '{"attributes":{"deviceType":"vnas","hardwareRevision":"v2.1"}}'
```

Initialize device shadow with firmware version (simulating device first boot):

```bash
aws iot-data update-thing-shadow \
  --thing-name "device-001" \
  --cli-binary-format raw-in-base64-out \
  --payload '{"state":{"reported":{"firmwareVersion":"1.0.2"}}}' \
  /dev/stdout
```

> In production, the device itself reports `firmwareVersion` via MQTT on boot. This CLI call is for testing only.

Verify indexing (may take a few seconds):

```bash
aws iot search-index --query-string "shadow.reported.firmwareVersion:1.0.2"
```

## 5. Create Dynamic Thing Group

```bash
aws iot create-dynamic-thing-group \
  --thing-group-name "firmware-v1-0-2" \
  --query-string "shadow.reported.firmwareVersion:1.0.2"
```

> Thing group name only allows `[a-zA-Z0-9:_-]` — no dots.
> Devices matching the query are **auto-populated**. When a device updates its Shadow `firmwareVersion`, it automatically leaves this group.

Verify members:

```bash
aws iot list-things-in-thing-group --thing-group-name "firmware-v1-0-2"
```

## 6. Upload Firmware to S3

```bash
aws s3 cp firmware-v1.1.0.bin "s3://${S3_BUCKET}/firmware/firmware-v1.1.0.bin"
```

## 7. Create Job Document

```bash
cat > /tmp/job-document.json << EOF
{
  "operation": "firmwareUpdate",
  "version": "1.1.0",
  "packageUrl": "\${aws:iot:s3-presigned-url:https://s3.${AWS_REGION}.amazonaws.com/${S3_BUCKET}/firmware/firmware-v1.1.0.bin}",
  "checksum": "sha256:REPLACE_WITH_ACTUAL_SHA256",
  "checksumType": "sha256"
}
EOF
```

Generate checksum:

```bash
shasum -a 256 firmware-v1.1.0.bin
```

Upload to S3:

```bash
aws s3 cp /tmp/job-document.json "s3://${S3_BUCKET}/jobs/job-firmware-v1.1.0.json"
```

> `${aws:iot:s3-presigned-url:...}` is an IoT Core placeholder — it auto-generates a temporary download URL for each device. Devices do **not** need S3 credentials.

## 8. Create IoT Job

```bash
aws iot create-job \
  --job-id "ota-upgrade-to-v1.1.0-$(date +%Y%m%d%H%M%S)" \
  --targets "arn:aws:iot:${AWS_REGION}:${AWS_ACCOUNT_ID}:thinggroup/firmware-v1-0-2" \
  --document-source "https://s3.${AWS_REGION}.amazonaws.com/${S3_BUCKET}/jobs/job-firmware-v1.1.0.json" \
  --presigned-url-config "{\"roleArn\":\"arn:aws:iam::${AWS_ACCOUNT_ID}:role/${IOT_JOB_ROLE_NAME}\",\"expiresInSec\":3600}" \
  --target-selection SNAPSHOT \
  --description "Upgrade firmware from 1.0.2 to 1.1.0"
```

- `SNAPSHOT` — one-time job for current group members only.
- `CONTINUOUS` — also applies to devices that join the group later.

## 9. Monitor Job

```bash
# Job status
aws iot describe-job --job-id <job-id>

# Per-device execution status
aws iot list-job-executions-for-job --job-id <job-id>

# Specific device
aws iot describe-job-execution \
  --job-id <job-id> \
  --thing-name "device-001"
```

## Device-Side Flow

The device must implement these steps via MQTT:

1. **Subscribe** to `$aws/things/{thingName}/jobs/notify-next`
2. **Receive** job document with presigned URL
3. **Update** job status to `IN_PROGRESS`:
   - Publish to `$aws/things/{thingName}/jobs/{jobId}/update`
4. **Download** firmware via presigned URL (HTTPS GET)
5. **Verify** checksum (sha256)
6. **Install** firmware and reboot
7. **Update** Shadow with new firmware version:
   - Publish to `$aws/things/{thingName}/shadow/update`:
   ```json
   { "state": { "reported": { "firmwareVersion": "1.1.0" } } }
   ```
8. **Update** job status to `SUCCEEDED`:
   - Publish to `$aws/things/{thingName}/jobs/{jobId}/update`

> After step 7, Fleet Indexing picks up the Shadow change and the device **auto-exits** the `firmware-v1.0.2` Dynamic Thing Group.

## Device Client (Java)

The `device-client/` module implements the device-side OTA flow described above.

### Build

```bash
cd device-client
mvn clean package -DskipTests
```

### Run

```bash
java -jar target/device-client-1.0.0.jar \
  <iot-endpoint> \
  <thing-name> \
  <cert-path> \
  <key-path> \
  <ca-path>
```

Example:

```bash
java -jar target/device-client-1.0.0.jar \
  a1b2c3d4e5f6g7-ats.iot.ap-southeast-1.amazonaws.com \
  device-001 \
  certs/device.pem.crt \
  certs/device.pem.key \
  certs/AmazonRootCA1.pem
```

The client will:
1. Connect via MQTT (X.509 mutual TLS)
2. Report current `firmwareVersion` to Device Shadow
3. Subscribe to `$aws/things/{thingName}/jobs/notify-next`
4. On new Job: download firmware via presigned URL, verify SHA-256 checksum
5. Report `SUCCEEDED` + update Shadow with new version

### Custom Integration

Implement `OtaListener` to integrate with your own firmware install logic:

```java
OtaListener listener = new OtaListener() {
    public String onQueryVersion() { return "1.0.2"; }
    public void onNewPackage(OtaPackage pkg) { client.startUpgrade(pkg); }
    public void onProgress(int percent, String desc) { /* update UI */ }
};
OtaClient client = new OtaClient("device-001", listener, Paths.get("downloads"));
client.connect(endpoint, certPath, keyPath, caPath);
```

## Cleanup

```bash
# Cancel a running job
aws iot cancel-job --job-id <job-id>

# Delete job (must be CANCELED or COMPLETED)
aws iot delete-job --job-id <job-id> --force

# Delete Dynamic Thing Group
aws iot delete-dynamic-thing-group --thing-group-name "firmware-v1-0-2"
```

## Notes

- The Job Document schema is **custom** — cloud and device must agree on the format beforehand.
- `presigned-url-config.expiresInSec` max is 3600 (1 hour). Device must download within this window.
- For production, add **Job rollout config** (rate limiting) and **abort config** (auto-cancel on failure threshold).
