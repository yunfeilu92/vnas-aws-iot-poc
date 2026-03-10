#!/bin/bash
# =============================================================================
# DataSync 批量任务创建脚本
# 为每个 source bucket 创建: source location + dest location + task
# dest bucket 命名规则: {source_bucket}{DEST_SUFFIX}
# =============================================================================

set -euo pipefail

# ─── 配置参数 ───
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
IAM_ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/admin"

SOURCE_REGION="me-central-1"
DEST_REGION="ap-northeast-2"
TASK_REGION="${DEST_REGION}"

LOG_GROUP_ARN="arn:aws:logs:${DEST_REGION}:${ACCOUNT_ID}:log-group:/aws/datasync:*"

# dest bucket = source bucket 名 + 后缀
DEST_SUFFIX="-kr-backup"

# ─── Source Bucket 列表（按需修改） ───
SOURCE_BUCKETS=(
  "test-batch-replication-yunfeilu-me-central-1"
  # TODO(human): 在此添加更多 source bucket
)

# ─── 用于存放 Task Report 的 bucket（需已存在） ───
REPORT_BUCKET="datasync-yunfeilu-kr"

# =============================================================================

echo "=== DataSync 批量创建开始 ==="
echo "Source Region: ${SOURCE_REGION}"
echo "Dest Region:   ${DEST_REGION}"
echo "Dest Suffix:   ${DEST_SUFFIX}"
echo "Bucket 数量:   ${#SOURCE_BUCKETS[@]}"
echo ""

for SOURCE_BUCKET in "${SOURCE_BUCKETS[@]}"; do
  DEST_BUCKET="${SOURCE_BUCKET}${DEST_SUFFIX}"

  echo "────────────────────────────────────────────"
  echo "处理: ${SOURCE_BUCKET} → ${DEST_BUCKET}"
  echo "────────────────────────────────────────────"

  # Step 1: 创建 Source Location
  echo "  [1/3] 创建 Source Location: s3://${SOURCE_BUCKET}/"
  SOURCE_LOCATION_ARN=$(aws datasync create-location-s3 \
    --region "${SOURCE_REGION}" \
    --s3-bucket-arn "arn:aws:s3:::${SOURCE_BUCKET}" \
    --s3-storage-class STANDARD \
    --s3-config "BucketAccessRoleArn=${IAM_ROLE_ARN}" \
    --query 'LocationArn' \
    --output text)
  echo "        ARN: ${SOURCE_LOCATION_ARN}"

  # Step 2: 创建 Destination Location
  echo "  [2/3] 创建 Destination Location: s3://${DEST_BUCKET}/"
  DEST_LOCATION_ARN=$(aws datasync create-location-s3 \
    --region "${DEST_REGION}" \
    --s3-bucket-arn "arn:aws:s3:::${DEST_BUCKET}" \
    --s3-storage-class STANDARD \
    --s3-config "BucketAccessRoleArn=${IAM_ROLE_ARN}" \
    --query 'LocationArn' \
    --output text)
  echo "        ARN: ${DEST_LOCATION_ARN}"

  # Step 3: 创建 DataSync Task
  echo "  [3/3] 创建 DataSync Task"
  TASK_ARN=$(aws datasync create-task \
    --region "${TASK_REGION}" \
    --source-location-arn "${SOURCE_LOCATION_ARN}" \
    --destination-location-arn "${DEST_LOCATION_ARN}" \
    --cloud-watch-log-group-arn "${LOG_GROUP_ARN}" \
    --task-mode ENHANCED \
    --options '{
      "VerifyMode": "ONLY_FILES_TRANSFERRED",
      "OverwriteMode": "ALWAYS",
      "Atime": "BEST_EFFORT",
      "Mtime": "PRESERVE",
      "Uid": "NONE",
      "Gid": "NONE",
      "PreserveDeletedFiles": "PRESERVE",
      "PreserveDevices": "NONE",
      "PosixPermissions": "NONE",
      "BytesPerSecond": -1,
      "TaskQueueing": "ENABLED",
      "LogLevel": "TRANSFER",
      "TransferMode": "CHANGED",
      "SecurityDescriptorCopyFlags": "NONE",
      "ObjectTags": "PRESERVE"
    }' \
    --task-report-config '{
      "Destination": {
        "S3": {
          "S3BucketArn": "arn:aws:s3:::'"${REPORT_BUCKET}"'",
          "BucketAccessRoleArn": "'"${IAM_ROLE_ARN}"'"
        }
      },
      "OutputType": "STANDARD",
      "ReportLevel": "ERRORS_ONLY",
      "ObjectVersionIds": "INCLUDE"
    }' \
    --query 'TaskArn' \
    --output text)
  echo "        Task ARN: ${TASK_ARN}"
  echo ""
done

echo "=== 全部完成 ==="
