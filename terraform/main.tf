provider "aws" {
  region = var.region
}

data "aws_caller_identity" "current" {}

locals {
  account_id      = data.aws_caller_identity.current.account_id
  firmware_bucket = var.firmware_bucket_name != "" ? var.firmware_bucket_name : "vnas-iot-firmware-${local.account_id}"
}
