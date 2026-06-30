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

# Dynamic Thing Group and IoT Job are OTA runtime artifacts, NOT managed here.
# Their query condition (which source version to upgrade) changes every batch,
# so they don't belong in IaC. Create them at upgrade time with
#   device-client/create-ota-job.sh <from> <to>
# which names the group by source version and reuses it if it already exists.
