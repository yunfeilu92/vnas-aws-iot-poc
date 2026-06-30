# Firmware storage
resource "aws_s3_bucket" "firmware" {
  bucket = local.firmware_bucket
}

# IAM role for IoT Job to fetch firmware (managed presigned URL).
resource "aws_iam_role" "job_s3" {
  name = "vnas-iot-job-s3-role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "iot.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "job_s3" {
  name = "GetFirmware"
  role = aws_iam_role.job_s3.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "s3:GetObject"
      Resource = "${aws_s3_bucket.firmware.arn}/*"
    }]
  })
}

# Dynamic Thing Group — no native Terraform resource (provider issue #28575).
# Wrap the CLI. Recreated when ota_from_version changes.
resource "null_resource" "ota_dynamic_group" {
  triggers = {
    from_version = var.ota_from_version
    region       = var.region
  }

  provisioner "local-exec" {
    command = "aws iot create-dynamic-thing-group --region ${var.region} --thing-group-name ota-from-${replace(var.ota_from_version, ".", "-")} --query-string \"shadow.reported.firmwareVersion:${var.ota_from_version}\""
  }

  depends_on = [aws_iot_indexing_configuration.fleet]
}

# IoT Job is a one-shot operation, intentionally NOT managed here.
# Trigger it with device-client/create-ota-job.sh after apply.
