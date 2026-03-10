#!/bin/bash

# IoT Credentials Provider 一次性配置脚本
# 用途：让设备使用 X.509 证书换取临时 AWS 凭证，直接从 S3 下载固件
# 运行一次即可，后续不需要重复执行
#
# 用法: ./setup-iot-credentials.sh

set -e

REGION="us-east-1"
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
S3_BUCKET="vnas-iot-firmware-${ACCOUNT_ID}"
ROLE_NAME="IoTDeviceFirmwareRole"
ROLE_ALIAS="FirmwareDownloadAlias"
IOT_POLICY_NAME="ota-test-device-policy"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${GREEN}========== IoT Credentials Provider Setup ==========${NC}"
echo "Account: ${ACCOUNT_ID}"
echo "Region: ${REGION}"
echo "S3 Bucket: ${S3_BUCKET}"
echo "IAM Role: ${ROLE_NAME}"
echo "Role Alias: ${ROLE_ALIAS}"
echo ""

# ========== Step 1: 创建 IAM Role ==========
echo -e "${YELLOW}[1/4] Creating IAM Role...${NC}"

ROLE_ARN=$(aws iam get-role --role-name "$ROLE_NAME" --query 'Role.Arn' --output text 2>/dev/null || echo "")

if [ -z "$ROLE_ARN" ] || [ "$ROLE_ARN" == "None" ]; then
    ROLE_ARN=$(aws iam create-role \
      --role-name "$ROLE_NAME" \
      --assume-role-policy-document '{
        "Version": "2012-10-17",
        "Statement": [{
          "Effect": "Allow",
          "Principal": {"Service": "credentials.iot.amazonaws.com"},
          "Action": "sts:AssumeRole"
        }]
      }' \
      --query 'Role.Arn' --output text)
    echo "Created: ${ROLE_ARN}"
else
    echo "Already exists: ${ROLE_ARN}"
fi

# ========== Step 2: 附加 S3 只读策略 ==========
echo -e "${YELLOW}[2/4] Attaching S3 download policy...${NC}"

aws iam put-role-policy \
  --role-name "$ROLE_NAME" \
  --policy-name "FirmwareS3DownloadPolicy" \
  --policy-document "{
    \"Version\": \"2012-10-17\",
    \"Statement\": [{
      \"Effect\": \"Allow\",
      \"Action\": [\"s3:GetObject\", \"s3:HeadObject\"],
      \"Resource\": \"arn:aws:s3:::${S3_BUCKET}/firmware/*\"
    }]
  }"
echo "Attached S3 policy (s3:GetObject on ${S3_BUCKET}/firmware/*)"

# ========== Step 3: 创建 IoT Role Alias ==========
echo -e "${YELLOW}[3/4] Creating IoT Role Alias...${NC}"

ALIAS_ARN=$(aws iot describe-role-alias --role-alias "$ROLE_ALIAS" --query 'roleAliasDescription.roleAliasArn' --output text --region "$REGION" 2>/dev/null || echo "")

if [ -z "$ALIAS_ARN" ] || [ "$ALIAS_ARN" == "None" ]; then
    ALIAS_ARN=$(aws iot create-role-alias \
      --role-alias "$ROLE_ALIAS" \
      --role-arn "$ROLE_ARN" \
      --query 'roleAliasArn' \
      --output text \
      --region "$REGION")
    echo "Created: ${ALIAS_ARN}"
else
    echo "Already exists: ${ALIAS_ARN}"
fi

# ========== Step 4: 更新 IoT Policy (添加 credentials provider 权限) ==========
echo -e "${YELLOW}[4/4] Updating IoT Policy...${NC}"

# 获取现有 policy 文档
EXISTING_POLICY=$(aws iot get-policy --policy-name "$IOT_POLICY_NAME" --query 'policyDocument' --output text --region "$REGION" 2>/dev/null || echo "")

if [ -n "$EXISTING_POLICY" ]; then
    # 检查是否已包含 credentials provider 权限
    if echo "$EXISTING_POLICY" | grep -q "iot:AssumeRoleWithCertificate"; then
        echo "IoT Policy already has credentials provider permission."
    else
        echo -e "${YELLOW}NOTE: You need to manually add this statement to your IoT Policy '${IOT_POLICY_NAME}':${NC}"
        echo ""
        echo "  {"
        echo "    \"Effect\": \"Allow\","
        echo "    \"Action\": \"iot:AssumeRoleWithCertificate\","
        echo "    \"Resource\": \"${ALIAS_ARN}\""
        echo "  }"
        echo ""
        echo "You can do this in the AWS IoT Console -> Security -> Policies -> ${IOT_POLICY_NAME}"
    fi
else
    echo -e "${YELLOW}IoT Policy '${IOT_POLICY_NAME}' not found. Make sure your device's IoT policy includes:${NC}"
    echo "  iot:AssumeRoleWithCertificate on ${ALIAS_ARN}"
fi

# ========== 输出配置信息 ==========
echo ""
CRED_ENDPOINT=$(aws iot describe-endpoint --endpoint-type iot:CredentialProvider --query endpointAddress --output text --region "$REGION")

echo -e "${GREEN}========== Setup Complete ==========${NC}"
echo ""
echo "IoT Credentials Provider endpoint:"
echo "  ${CRED_ENDPOINT}"
echo ""
echo "Role Alias:"
echo "  ${ROLE_ALIAS}"
echo ""
echo "Device startup command (S3 download mode):"
echo "  java -jar target/device-client-1.0.0.jar \\"
echo "    <iot-data-endpoint> <thingName> \\"
echo "    certs/device.pem.crt certs/device.pem.key certs/AmazonRootCA1.pem \\"
echo "    ${CRED_ENDPOINT} ${ROLE_ALIAS} ${REGION}"
echo ""
echo "Job Document format (S3 mode):"
echo '  {"version":"1.0.3","s3Bucket":"'${S3_BUCKET}'","s3Key":"firmware/v1.0.3/firmware.bin","checksum":"...","checksumType":"sha256"}'
