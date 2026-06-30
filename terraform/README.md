# vNAS IoT OTA — Terraform

Provisions the OTA upgrade pipeline: device registration (shared certificate), Device Shadow indexing, firmware storage, and CloudWatch monitoring.

## Layout

| File | Resources |
|------|-----------|
| `device.tf` | Shared certificate (no CA), ClientId policy, Things (per device) |
| `indexing.tf` | Fleet Indexing (`REGISTRY_AND_SHADOW`) |
| `ota.tf` | Firmware S3 bucket, Job IAM role, Dynamic Thing Group (CLI) |
| `monitoring.tf` | Rule IAM role, topic rule, SNS, per-device CPU alarms |
| `custom_domain.tf` | **Optional** custom domain endpoint (off by default) |

## Prerequisites

- AWS CLI authenticated (the `null_resource` and provider use it).
- A shared device certificate PEM at `shared_certificate_pem_path` (default `shared-device.pem.crt`).

## Usage

```bash
terraform init
terraform plan
terraform apply
```

After apply, confirm the SNS email subscription and trigger an upgrade with
`device-client/create-ota-job.sh`.

## Not managed by Terraform

- **IoT Job** — one-shot operation, run `create-ota-job.sh`.
- **Dynamic Thing Group** — no native provider resource (issue #28575); created via `null_resource` + CLI in `ota.tf`.

## Optional: custom domain

Off by default. To enable, set in your tfvars:

```hcl
enable_custom_domain       = true
custom_domain_name         = "iot.yourdomain.com"
custom_domain_acm_cert_arn = "arn:aws:acm:...:certificate/xxxx"  # ACM server cert
hosted_zone_id             = "Z0123..."                          # omit to manage DNS externally
```

Server certificate source:
- **A. ACM public-issued** (DNS validation) — public domain, no validation cert.
- **B. Self-signed / private CA** imported to ACM — also set `custom_domain_validation_cert_arn`.

Device-side: connect to `custom_domain_name` (SDK sends SNI) and replace the server
trust anchor `AmazonRootCA1.pem` with your server cert chain's root CA.

## Constraints

- Devices must connect with `clientId == thingName` (shared cert relies on `${iot:ClientId}` topic isolation).
- The shared certificate is unbound to any Thing by design — no `aws_iot_thing_principal_attachment`.
- `topic(3)` in the rule assumes telemetry topic `vnas/telemetry/{deviceId}`. Adjust for your topic layout.
- One custom metric per device (~$0.30/metric/month). Fine for small fleets.
