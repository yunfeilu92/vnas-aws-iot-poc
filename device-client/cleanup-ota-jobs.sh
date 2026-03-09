#!/bin/bash

# OTA Job 清理脚本
# 用法: ./cleanup-ota-jobs.sh [thing-name]

set -e

THING_NAME="${1:-ota-test-device}"
REGION="us-east-1"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${GREEN}========== OTA Job 清理脚本 ==========${NC}"
echo "Thing Name: $THING_NAME"
echo "Region: $REGION"
echo ""

# ========== 列出所有 IN_PROGRESS 和 QUEUED 的 jobs ==========
echo -e "${YELLOW}查找 Thing 的活跃 Job Executions...${NC}"

JOB_EXECUTIONS=$(aws iot list-job-executions-for-thing \
  --thing-name "$THING_NAME" \
  --status IN_PROGRESS \
  --region "$REGION" \
  --query 'executionSummaries[*].jobId' \
  --output text 2>/dev/null || echo "")

QUEUED_EXECUTIONS=$(aws iot list-job-executions-for-thing \
  --thing-name "$THING_NAME" \
  --status QUEUED \
  --region "$REGION" \
  --query 'executionSummaries[*].jobId' \
  --output text 2>/dev/null || echo "")

ALL_EXECUTIONS="$JOB_EXECUTIONS $QUEUED_EXECUTIONS"

if [ -z "$ALL_EXECUTIONS" ] || [ "$ALL_EXECUTIONS" = " " ]; then
    echo "✓ 没有找到需要清理的 Job Executions"
    exit 0
fi

echo "找到以下 Job Executions:"
for JOB_ID in $ALL_EXECUTIONS; do
    STATUS=$(aws iot describe-job-execution \
      --job-id "$JOB_ID" \
      --thing-name "$THING_NAME" \
      --query 'execution.status' \
      --output text \
      --region "$REGION")
    echo "  - $JOB_ID ($STATUS)"
done
echo ""

# ========== 确认取消 ==========
read -p "是否取消这些 Job Executions? (y/N) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "已取消操作"
    exit 0
fi

# ========== 取消 Job Executions ==========
echo ""
echo -e "${YELLOW}取消 Job Executions...${NC}"

for JOB_ID in $ALL_EXECUTIONS; do
    echo -n "取消 $JOB_ID... "
    aws iot cancel-job-execution \
      --job-id "$JOB_ID" \
      --thing-name "$THING_NAME" \
      --force \
      --region "$REGION" \
      --output text &>/dev/null && echo -e "${GREEN}✓${NC}" || echo -e "${RED}失败${NC}"
done

echo ""
echo -e "${GREEN}========== 清理完成 ==========${NC}"
echo ""
echo -e "${YELLOW}查看当前 Job Executions:${NC}"
echo "  aws iot list-job-executions-for-thing --thing-name $THING_NAME"
