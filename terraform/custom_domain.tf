# ---------------------------------------------------------------------------
# OPTIONAL: custom domain endpoint for IoT Core.
# Disabled by default. Enable with enable_custom_domain = true and provide the
# ACM *server* certificate ARN (custom_domain_acm_cert_arn).
#
# Server certificate source:
#   A. ACM public-issued (DNS validation) — public domain, no validation cert.
#   B. Self-signed / private CA imported to ACM — also set
#      custom_domain_validation_cert_arn (a public-CA cert proving ownership).
#
# Device-side: connect to custom_domain_name (SDK sends SNI) and replace the
# server trust anchor (AmazonRootCA1.pem) with your server cert chain's root CA.
# ---------------------------------------------------------------------------

resource "aws_iot_domain_configuration" "custom" {
  count = var.enable_custom_domain ? 1 : 0

  name                    = "vnas-custom-domain"
  domain_name             = var.custom_domain_name
  service_type            = "DATA"
  server_certificate_arns = [var.custom_domain_acm_cert_arn]

  # Only for self-signed / private CA server certs (option B).
  validation_certificate_arn = var.custom_domain_validation_cert_arn != "" ? var.custom_domain_validation_cert_arn : null
}

# AWS-managed ATS endpoint — the CNAME target for the custom domain.
data "aws_iot_endpoint" "ats" {
  count         = var.enable_custom_domain && var.hosted_zone_id != "" ? 1 : 0
  endpoint_type = "iot:Data-ATS"
}

# Optional Route 53 record. Leave hosted_zone_id empty to manage DNS externally.
resource "aws_route53_record" "iot" {
  count   = var.enable_custom_domain && var.hosted_zone_id != "" ? 1 : 0
  zone_id = var.hosted_zone_id
  name    = var.custom_domain_name
  type    = "CNAME"
  ttl     = 300
  records = [data.aws_iot_endpoint.ats[0].endpoint_address]
}
