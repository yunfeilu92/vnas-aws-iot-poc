variable "region" {
  type    = string
  default = "us-east-1"
}

variable "devices" {
  type        = list(string)
  description = "Device / Thing names. Each device connects with clientId == its thingName."
  default     = ["device-001", "device-002", "device-003"]
}

variable "shared_certificate_pem_path" {
  type        = string
  description = "Path to the shared device X.509 certificate PEM (registered without CA)."
  default     = "shared-device.pem.crt"
}

variable "firmware_bucket_name" {
  type        = string
  description = "Firmware bucket name. Empty = derive vnas-iot-firmware-<account-id>."
  default     = ""
}

variable "ota_from_version" {
  type        = string
  description = "Source firmware version the dynamic group targets for upgrade."
  default     = "1.0.2"
}

variable "alert_email" {
  type    = string
  default = "you@example.com"
}

variable "cpu_threshold" {
  type        = number
  description = "CPU alarm threshold (percent)."
  default     = 90
}

# --- Optional: custom domain endpoint (see custom_domain.tf) ---

variable "enable_custom_domain" {
  type        = bool
  description = "Set true to expose IoT Core on a custom domain."
  default     = false
}

variable "custom_domain_name" {
  type        = string
  description = "FQDN devices connect to, e.g. iot.yourdomain.com."
  default     = ""
}

variable "custom_domain_acm_cert_arn" {
  type        = string
  description = "ACM ARN of the server certificate. Required when enable_custom_domain = true."
  default     = ""
}

variable "custom_domain_validation_cert_arn" {
  type        = string
  description = "ACM ARN of a public-CA validation cert. Only for self-signed / private-CA server certs (option B)."
  default     = ""
}

variable "hosted_zone_id" {
  type        = string
  description = "Route 53 hosted zone ID for the custom domain CNAME. Empty = manage DNS externally."
  default     = ""
}
