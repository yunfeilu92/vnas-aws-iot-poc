# Shared device certificate, registered without a CA.
# certificate_pem without ca_pem => RegisterCertificateWithoutCA.
resource "aws_iot_certificate" "shared" {
  active          = true
  certificate_pem = file(var.shared_certificate_pem_path)
}

# ClientId-based policy. Shared cert => isolate topics by ${iot:ClientId}
# (NOT ${iot:Connection.Thing.ThingName}, which needs a per-Thing cert binding).
# $${...} escapes the IoT substitution template from Terraform interpolation.
resource "aws_iot_policy" "shared" {
  name = "VnasSharedPolicy"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = "iot:Connect"
        Resource = "arn:aws:iot:${var.region}:${local.account_id}:client/$${iot:ClientId}"
      },
      {
        Effect   = "Allow"
        Action   = ["iot:Publish", "iot:Receive"]
        Resource = "arn:aws:iot:${var.region}:${local.account_id}:topic/$aws/things/$${iot:ClientId}/*"
      },
      {
        Effect   = "Allow"
        Action   = "iot:Subscribe"
        Resource = "arn:aws:iot:${var.region}:${local.account_id}:topicfilter/$aws/things/$${iot:ClientId}/*"
      }
    ]
  })
}

resource "aws_iot_policy_attachment" "shared" {
  policy = aws_iot_policy.shared.name
  target = aws_iot_certificate.shared.arn
}

# One Thing per device. Shadow / OTA / dynamic grouping are all per-Thing.
# No aws_iot_thing_principal_attachment — the shared cert stays unbound.
resource "aws_iot_thing" "devices" {
  for_each = toset(var.devices)
  name     = each.value
}
