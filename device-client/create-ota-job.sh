#!/bin/bash

# OTA Job 创建脚本（基于 Dynamic Thing Group）
# 用法: ./create-ota-job.sh <from-version> <to-version> [--continuous]
#
# 示例:
#   ./create-ota-job.sh 1.0.2 1.0.3              # Snapshot Job：将所有 1.0.2 设备升级到 1.0.3
#   ./create-ota-job.sh 1.0.3 1.0.4              # Snapshot Job：将所有 1.0.3 设备升级到 1.0.4
#   ./create-ota-job.sh 1.0.2 1.0.3 --continuous # Continuous Job：新加入组的设备也会自动升级

set -e  # 遇到错误立即退出

# ========== 配置参数 ==========
FROM_VERSION="$1"
TO_VERSION="$2"
JOB_TYPE="SNAPSHOT"  # 默认为 Snapshot Job
S3_BUCKET="vnas-iot-firmware-497892281794"
REGION="us-east-1"
PRESIGNED_URL_EXPIRY=3600  # 1 小时
CREATE_GROUP_IF_NOT_EXISTS=true  # 如果 Thing Group 不存在，是否自动创建

# 解析可选参数
shift 2 2>/dev/null || true
while [[ $# -gt 0 ]]; do
    case "$1" in
        --continuous)
            JOB_TYPE="CONTINUOUS"
            shift
            ;;
        *)
            echo -e "${RED}未知参数: $1${NC}"
            exit 1
            ;;
    esac
done

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# ========== 检查参数 ==========
if [ -z "$FROM_VERSION" ] || [ -z "$TO_VERSION" ]; then
    echo -e "${RED}错误: 请指定源版本和目标版本${NC}"
    echo "用法: $0 <from-version> <to-version>"
    echo ""
    echo "示例:"
    echo "  $0 1.0.2 1.0.3    # 将所有 1.0.2 设备升级到 1.0.3"
    echo "  $0 1.0.3 1.0.4    # 将所有 1.0.3 设备升级到 1.0.4"
    exit 1
fi

# 版本格式检查
if [[ "$FROM_VERSION" == "$TO_VERSION" ]]; then
    echo -e "${RED}错误: 源版本和目标版本相同 ($FROM_VERSION)${NC}"
    exit 1
fi

# 生成 Thing Group 名称（将点号替换为短横线）
GROUP_NAME="firmware-v${FROM_VERSION//./-}"

echo -e "${GREEN}========== OTA Job 创建脚本 ==========${NC}"
echo "源版本: $FROM_VERSION"
echo "目标版本: $TO_VERSION"
echo "Job 类型: $JOB_TYPE"
echo "Thing Group: $GROUP_NAME"
echo "S3 Bucket: $S3_BUCKET"
echo "Region: $REGION"
echo ""

# ========== 检查/创建 Dynamic Thing Group ==========
echo -e "${YELLOW}[0/6] 检查 Dynamic Thing Group...${NC}"

GROUP_ARN=$(aws iot describe-thing-group \
  --thing-group-name "$GROUP_NAME" \
  --query 'thingGroupArn' \
  --output text \
  --region "$REGION" 2>/dev/null || echo "")

if [ -z "$GROUP_ARN" ] || [ "$GROUP_ARN" == "None" ]; then
    if [ "$CREATE_GROUP_IF_NOT_EXISTS" = true ]; then
        echo "⚠ Thing Group '$GROUP_NAME' 不存在，正在创建..."
        GROUP_ARN=$(aws iot create-dynamic-thing-group \
          --thing-group-name "$GROUP_NAME" \
          --query-string "shadow.reported.firmwareVersion:$FROM_VERSION" \
          --query 'thingGroupArn' \
          --output text \
          --region "$REGION")
        echo "✓ 已创建: $GROUP_ARN"
        echo "⏳ 等待 5 秒让 Fleet Indexing 更新..."
        sleep 5
    else
        echo -e "${RED}错误: Thing Group '$GROUP_NAME' 不存在${NC}"
        echo "请先创建 Dynamic Thing Group 或设置 CREATE_GROUP_IF_NOT_EXISTS=true"
        exit 1
    fi
else
    echo "✓ Thing Group 已存在: $GROUP_ARN"
fi
echo ""

# ========== Step 1: 创建测试固件 ==========
echo -e "${YELLOW}[1/6] 创建测试固件文件...${NC}"
FIRMWARE_FILE="test-firmware-v${TO_VERSION}.bin"
echo "Firmware v${TO_VERSION} - Test at $(date)" > "$FIRMWARE_FILE"
echo "✓ 已创建: $FIRMWARE_FILE ($(wc -c < "$FIRMWARE_FILE") bytes)"
echo ""

# ========== Step 2: 上传到 S3 ==========
echo -e "${YELLOW}[2/6] 上传固件到 S3...${NC}"
S3_KEY="firmware/v${TO_VERSION}/firmware.bin"
S3_URI="s3://${S3_BUCKET}/${S3_KEY}"

aws s3 cp "$FIRMWARE_FILE" "$S3_URI" --region "$REGION"
echo "✓ 已上传到: $S3_URI"
echo ""

# ========== Step 3: 生成 Presigned URL ==========
echo -e "${YELLOW}[3/6] 生成 Presigned URL...${NC}"
PRESIGNED_URL=$(aws s3 presign "$S3_URI" --expires-in "$PRESIGNED_URL_EXPIRY" --region "$REGION")
echo "✓ URL 有效期: $((PRESIGNED_URL_EXPIRY / 3600)) 小时"
echo ""

# ========== Step 4: 计算 Checksum ==========
echo -e "${YELLOW}[4/6] 计算 SHA-256 checksum...${NC}"
if command -v shasum &> /dev/null; then
    CHECKSUM=$(shasum -a 256 "$FIRMWARE_FILE" | awk '{print $1}')
elif command -v sha256sum &> /dev/null; then
    CHECKSUM=$(sha256sum "$FIRMWARE_FILE" | awk '{print $1}')
else
    echo -e "${RED}错误: 找不到 shasum 或 sha256sum 命令${NC}"
    exit 1
fi
echo "✓ Checksum: $CHECKSUM"
echo ""

# ========== Step 5: 创建 Job Document ==========
echo -e "${YELLOW}[5/6] 创建 Job Document...${NC}"
JOB_DOCUMENT_FILE="job-document-v${TO_VERSION}.json"

cat > "$JOB_DOCUMENT_FILE" <<EOF
{
  "version": "$TO_VERSION",
  "packageUrl": "$PRESIGNED_URL",
  "checksum": "$CHECKSUM",
  "checksumType": "sha256"
}
EOF

echo "✓ 已创建: $JOB_DOCUMENT_FILE"
echo ""

# ========== Step 6: 确认 Thing Group 有成员 ==========
echo -e "${YELLOW}[6/7] 确认 Thing Group 成员...${NC}"

# 使用 search-index 查询（比 list-things-in-thing-group 更实时）
echo "查询运行 v$FROM_VERSION 的设备..."
MATCHING_THINGS=$(aws iot search-index \
  --query-string "shadow.reported.firmwareVersion:$FROM_VERSION" \
  --region "$REGION" \
  --query 'things[*].thingName' \
  --output text 2>/dev/null || echo "")

if [ -z "$MATCHING_THINGS" ]; then
    echo -e "${RED}错误: 没有找到运行 v$FROM_VERSION 的设备${NC}"
    echo "请确保设备已启动并在 Device Shadow 中上报了 firmwareVersion"
    echo ""
    echo "调试命令:"
    echo "  # 查看设备 Shadow"
    echo "  aws iot-data get-thing-shadow --thing-name <device-name> | jq .state.reported.firmwareVersion"
    echo ""
    echo "  # 检查 Fleet Indexing 是否启用"
    echo "  aws iot get-indexing-configuration"
    exit 1
fi

echo "✓ 找到 $(echo $MATCHING_THINGS | wc -w | tr -d ' ') 个设备:"
for thing in $MATCHING_THINGS; do
    echo "  - $thing"
done
echo ""

# ========== Step 7: 创建 OTA Job ==========
echo -e "${YELLOW}[7/7] 创建 AWS IoT Job...${NC}"

# 生成唯一 Job ID
JOB_ID="ota-upgrade-$(date +%s)"

# 创建 Job
aws iot create-job \
  --job-id "$JOB_ID" \
  --targets "$GROUP_ARN" \
  --document file://"$JOB_DOCUMENT_FILE" \
  --description "OTA upgrade from v${FROM_VERSION} to v${TO_VERSION} via Dynamic Thing Group ($JOB_TYPE)" \
  --target-selection "$JOB_TYPE" \
  --region "$REGION" \
  --output table

echo ""
echo -e "${GREEN}========== Job 创建成功 ==========${NC}"
echo "Job ID: $JOB_ID"
echo "Job 类型: $JOB_TYPE"
echo "Thing Group: $GROUP_NAME"
echo "Upgrade Path: $FROM_VERSION → $TO_VERSION"
echo ""
echo -e "${YELLOW}查看 Job 状态:${NC}"
echo "  aws iot describe-job --job-id $JOB_ID --query 'job.status'"
echo ""
echo -e "${YELLOW}查看 Thing Group 成员:${NC}"
echo "  aws iot list-things-in-thing-group --thing-group-name $GROUP_NAME"
echo ""
echo -e "${YELLOW}查看设备升级后是否离开 Group:${NC}"
echo "  sleep 10 && aws iot list-things-in-thing-group --thing-group-name $GROUP_NAME"
echo ""

# ========== 清理临时文件（可选） ==========
read -p "是否删除本地临时文件? (y/N) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    rm -f "$FIRMWARE_FILE" "$JOB_DOCUMENT_FILE"
    echo "✓ 已删除临时文件"
else
    echo "✓ 临时文件保留在当前目录"
fi
