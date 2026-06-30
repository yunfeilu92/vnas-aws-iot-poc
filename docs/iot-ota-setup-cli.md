# vNAS IoT OTA — Setup Guide (CLI)

AWS resources for the OTA upgrade pipeline: device registration (shared certificate), Device Shadow, OTA jobs, and CloudWatch monitoring.

Firmware integrity = SHA-256 checksum (no Code Signing).

## 0. Prerequisites

```bash
export REGION=us-east-1
export ACCT=$(aws sts get-caller-identity --query Account --output text)
export BUCKET=vnas-iot-firmware-$ACCT
```

You also need a shared device certificate PEM (`shared-device.pem.crt`) — self-signed or third-party.

## 1. Shared device certificate (register without CA)

One shared X.509 cert for all devices. Devices isolate by `clientId`, not per-cert.

```bash
CERT_ARN=$(aws iot register-certificate-without-ca \
  --certificate-pem file://shared-device.pem.crt \
  --status ACTIVE --query certificateArn --output text)
```

> The shared cert is NOT attached to any Thing. Each device must connect with `clientId == its thingName`.

## 2. IoT policy (ClientId-based isolation)

`shared-policy.json` (replace `REGION` / `ACCT`):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": "iot:Connect",
      "Resource": "arn:aws:iot:REGION:ACCT:client/${iot:ClientId}" },
    { "Effect": "Allow", "Action": ["iot:Publish", "iot:Receive"],
      "Resource": "arn:aws:iot:REGION:ACCT:topic/$aws/things/${iot:ClientId}/*" },
    { "Effect": "Allow", "Action": "iot:Subscribe",
      "Resource": "arn:aws:iot:REGION:ACCT:topicfilter/$aws/things/${iot:ClientId}/*" }
  ]
}
```

```bash
aws iot create-policy --policy-name VnasSharedPolicy --policy-document file://shared-policy.json
aws iot attach-policy --policy-name VnasSharedPolicy --target $CERT_ARN
```

## 3. Things (one per device)

Shadow / OTA / dynamic grouping are all per-Thing, so each device still needs its own Thing.

```bash
for t in device-001 device-002 device-003; do
  aws iot create-thing --thing-name $t
done
```

> Do NOT run `attach-thing-principal` — the shared cert stays unbound to any Thing.

## 4. Fleet Indexing (required for dynamic groups)

```bash
aws iot update-indexing-configuration \
  --thing-indexing-configuration thingIndexingMode=REGISTRY_AND_SHADOW
```

> Device Shadow needs no creation — the first `$aws/things/{thing}/shadow/update` creates it.

## 5. Firmware S3 bucket + Job role

```bash
aws s3 mb s3://$BUCKET --region $REGION
aws s3 cp firmware.bin s3://$BUCKET/firmware/v1.0.3/firmware.bin

aws iam create-role --role-name vnas-iot-job-s3-role \
  --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"iot.amazonaws.com"},"Action":"sts:AssumeRole"}]}'

aws iam put-role-policy --role-name vnas-iot-job-s3-role --policy-name GetFirmware \
  --policy-document "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"arn:aws:s3:::$BUCKET/*\"}]}"
```

## 6. Dynamic Thing Group + OTA Job

```bash
aws iot create-dynamic-thing-group --thing-group-name ota-from-1-0-2 \
  --query-string "shadow.reported.firmwareVersion:1.0.2"

aws iot create-job --job-id ota-upgrade-$(date +%s) \
  --targets arn:aws:iot:$REGION:$ACCT:thinggroup/ota-from-1-0-2 \
  --document file://job-document-v1.0.3.json \
  --presigned-url-config "roleArn=arn:aws:iam::$ACCT:role/vnas-iot-job-s3-role,expiresInSec=3600"
```

## 7. CloudWatch monitoring (telemetry → metric → alarm)

Extract a value (e.g. CPU) from device telemetry, push to a custom metric, alarm on it.

```bash
# Rule role
aws iam create-role --role-name IoTRuleCWRole \
  --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"iot.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
aws iam put-role-policy --role-name IoTRuleCWRole --policy-name PutMetricData \
  --policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"cloudwatch:PutMetricData","Resource":"*"}]}'

# SNS topic + email subscription
SNS_ARN=$(aws sns create-topic --name vnas-iot-alerts --query TopicArn --output text)
aws sns subscribe --topic-arn $SNS_ARN --protocol email \
  --notification-endpoint you@example.com

# Topic rule: SELECT cpu, deviceId from topic segment → CloudWatch metric
# ${deviceId} / ${cpu} are IoT substitution templates — escaped with \$ for bash.
aws iot create-topic-rule --rule-name VnasCpuMetric --topic-rule-payload "{
  \"sql\": \"SELECT cpu, topic(3) AS deviceId FROM 'vnas/telemetry/+'\",
  \"awsIotSqlVersion\": \"2016-03-23\",
  \"actions\": [{
    \"cloudwatchMetric\": {
      \"roleArn\": \"arn:aws:iam::$ACCT:role/IoTRuleCWRole\",
      \"metricNamespace\": \"VnasIoT/Device\",
      \"metricName\": \"CPU_\${deviceId}\",
      \"metricValue\": \"\${cpu}\",
      \"metricUnit\": \"Percent\"
    }
  }]
}"

# Alarm per device
aws cloudwatch put-metric-alarm --alarm-name vnas-cpu-high-device-001 \
  --namespace VnasIoT/Device --metric-name CPU_device-001 \
  --statistic Maximum --period 300 --threshold 90 \
  --comparison-operator GreaterThanThreshold --evaluation-periods 2 \
  --treat-missing-data notBreaching --alarm-actions $SNS_ARN
```

## 8. (Optional) Custom domain endpoint

Skip this section to use the default `xxxx-ats.iot.<region>.amazonaws.com` endpoint.
Enable it to make devices connect to `iot.yourdomain.com`.

Server certificate source — pick one:
- **A. ACM public-issued** (DNS validation) — requires a public domain; no validation cert.
- **B. Self-signed / private CA** imported to ACM — also pass `--validation-certificate-arn`.

```bash
# 1. Server cert already in ACM (A: requested w/ DNS validation, or B: imported)
SERVER_CERT_ARN=arn:aws:acm:$REGION:$ACCT:certificate/xxxx

# 2. Domain configuration
aws iot create-domain-configuration \
  --domain-configuration-name vnas-custom-domain \
  --domain-name iot.yourdomain.com \
  --service-type DATA \
  --server-certificate-arns $SERVER_CERT_ARN
  # option B only: --validation-certificate-arn <public-ca-cert-arn>

# 3. DNS: CNAME your domain to the AWS-managed ATS endpoint
ATS=$(aws iot describe-endpoint --endpoint-type iot:Data-ATS --query endpointAddress --output text)
echo "Create DNS CNAME: iot.yourdomain.com -> $ATS"
```

Device-side changes:
- Connect to `iot.yourdomain.com` (the SDK sends SNI automatically).
- Replace the server trust anchor: `AmazonRootCA1.pem` → the root CA of your server cert
  chain (A: public root CA; B: your private root CA).

## Notes

- `clientId` must equal `thingName` — the shared cert relies on `${iot:ClientId}` for topic isolation.
- Topic rule has no update API — delete then recreate to change it.
- Dynamic Thing Group / IoT Job have no Terraform resource; created here via CLI.
- `topic(3)` assumes telemetry topic `vnas/telemetry/{deviceId}` (3 segments). Adjust if your topic differs.
- Custom metrics cost ~$0.30/metric/month — one metric per device. Fine for small fleets.
