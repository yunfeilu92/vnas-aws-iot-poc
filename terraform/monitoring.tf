# IAM role for the topic rule to publish CloudWatch metrics.
resource "aws_iam_role" "rule_cw" {
  name = "IoTRuleCWRole"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "iot.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "rule_cw" {
  name = "PutMetricData"
  role = aws_iam_role.rule_cw.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = "cloudwatch:PutMetricData", Resource = "*" }]
  })
}

# Topic rule: extract cpu from telemetry, push as a custom metric.
# metric_name embeds deviceId (cloudwatchMetric has no dimensions field).
# $${...} escapes the IoT substitution template from Terraform.
resource "aws_iot_topic_rule" "cpu_metric" {
  name        = "VnasCpuMetric"
  enabled     = true
  sql         = "SELECT cpu, topic(3) AS deviceId FROM 'vnas/telemetry/+'"
  sql_version = "2016-03-23"

  cloudwatch_metric {
    role_arn         = aws_iam_role.rule_cw.arn
    metric_namespace = "VnasIoT/Device"
    metric_name      = "CPU_$${deviceId}"
    metric_value     = "$${cpu}"
    metric_unit      = "Percent"
  }
}

# Alert delivery
resource "aws_sns_topic" "alerts" {
  name = "vnas-iot-alerts"
}

resource "aws_sns_topic_subscription" "email" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

# One CPU alarm per device. each.value is a real Terraform interpolation.
resource "aws_cloudwatch_metric_alarm" "cpu_high" {
  for_each            = toset(var.devices)
  alarm_name          = "vnas-cpu-high-${each.value}"
  namespace           = "VnasIoT/Device"
  metric_name         = "CPU_${each.value}"
  statistic           = "Maximum"
  period              = 300
  threshold           = var.cpu_threshold
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
}
