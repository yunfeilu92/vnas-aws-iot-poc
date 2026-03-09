# OTA 设备客户端测试指南

## 前置准备

### 1. 启用 Fleet Indexing

确保 Fleet Indexing 已启用（包含 Device Shadow）：

```bash
# 检查当前配置
aws iot get-indexing-configuration

# 如果未启用，执行以下命令
aws iot update-indexing-configuration \
  --thing-indexing-configuration thingIndexingMode=REGISTRY_AND_SHADOW,thingConnectivityIndexingMode=STATUS
```

### 2. 编译设备客户端

```bash
cd device-client
mvn clean package
```

编译完成后，会生成：`target/device-client-1.0.0.jar`

### 3. 准备证书文件

在 `device-client/` 目录下创建 `certs/` 目录，放入以下文件：

```bash
mkdir -p certs
cd certs
```

需要的文件：
- `device.pem.crt` - 设备证书
- `device.pem.key` - 设备私钥
- `AmazonRootCA1.pem` - AWS Root CA

**获取 Root CA：**
```bash
curl -o AmazonRootCA1.pem https://www.amazontrust.com/repository/AmazonRootCA1.pem
```

**创建设备证书（如果还没有）：**
```bash
# 创建 Thing
aws iot create-thing --thing-name ota-test-device

# 创建证书
aws iot create-keys-and-certificate \
  --set-as-active \
  --certificate-pem-outfile device.pem.crt \
  --private-key-outfile device.pem.key

# 记录输出的 certificateArn，用于后续附加
```

**附加证书到 Thing 和 Policy：**
```bash
# 附加证书到 Thing
aws iot attach-thing-principal \
  --thing-name ota-test-device \
  --principal <CERTIFICATE_ARN>

# 创建并附加 Policy（如果还没有）
cat > ota-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "iot:Connect",
      "Resource": "arn:aws:iot:*:*:client/*"
    },
    {
      "Effect": "Allow",
      "Action": ["iot:Publish", "iot:Receive"],
      "Resource": "arn:aws:iot:*:*:topic/*"
    },
    {
      "Effect": "Allow",
      "Action": "iot:Subscribe",
      "Resource": "arn:aws:iot:*:*:topicfilter/*"
    }
  ]
}
EOF

aws iot create-policy \
  --policy-name ota-device-policy \
  --policy-document file://ota-policy.json

aws iot attach-policy \
  --policy-name ota-device-policy \
  --target <CERTIFICATE_ARN>
```

### 4. 获取 IoT Endpoint

```bash
aws iot describe-endpoint --endpoint-type iot:Data-ATS
```

输出示例：
```json
{
  "endpointAddress": "a1b2c3d4e5f6g7-ats.iot.us-east-1.amazonaws.com"
}
```

## 运行设备客户端

### 启动设备

```bash
cd device-client

java -jar target/device-client-1.0.0.jar \
  <ENDPOINT> \
  ota-test-device \
  certs/device.pem.crt \
  certs/device.pem.key \
  certs/AmazonRootCA1.pem
```

**实际示例：**
```bash
java -jar target/device-client-1.0.0.jar \
  a10pn8i6q9mhvm-ats.iot.us-east-1.amazonaws.com \
  ota-test-device \
  certs/device.pem.crt \
  certs/device.pem.key \
  certs/AmazonRootCA1.pem
```

**预期输出：**
```
[Demo] Connecting to a10pn8i6q9mhvm-ats.iot.us-east-1.amazonaws.com as ota-test-device...
[OtaClient] MQTT connected: ota-test-device
[Demo] Current firmware version: 1.0.2
[OtaClient] Shadow updated: firmwareVersion=1.0.2
[OtaClient] Subscribed to: $aws/things/ota-test-device/jobs/notify-next
[Demo] Waiting for OTA job... (Ctrl+C to exit)
```

### 验证设备 Shadow

```bash
aws iot-data get-thing-shadow --thing-name ota-test-device output.json
cat output.json | jq .state.reported.firmwareVersion
```

应返回：`"1.0.2"`

## 创建 Dynamic Thing Group

基于 Device Shadow 的 `firmwareVersion` 创建动态分组：

```bash
aws iot create-dynamic-thing-group \
  --thing-group-name "firmware-v1-0-2" \
  --query-string "shadow.reported.firmwareVersion:1.0.2"
```

**验证设备是否加入 Group：**
```bash
# 等待几秒让索引更新
sleep 5

# 查询 group 成员
aws iot list-things-in-thing-group --thing-group-name firmware-v1-0-2
```

应看到 `ota-test-device` 在列表中。

## 创建 OTA Job 测试

### 1. 准备测试固件

```bash
# 创建模拟固件文件
echo "Firmware v1.0.3 - $(date)" > test-firmware-v1.0.3.bin
```

### 2. 上传固件到 S3

```bash
# 创建 S3 bucket（如果还没有）
BUCKET_NAME="ota-firmware-$(date +%s)"
aws s3 mb s3://${BUCKET_NAME}

# 上传固件
aws s3 cp test-firmware-v1.0.3.bin s3://${BUCKET_NAME}/firmware/v1.0.3/firmware.bin
```

### 3. 生成 Presigned URL

```bash
PRESIGNED_URL=$(aws s3 presign s3://${BUCKET_NAME}/firmware/v1.0.3/firmware.bin --expires-in 3600)
echo "Presigned URL: $PRESIGNED_URL"
```

### 4. 计算 Checksum 并创建 Job Document

```bash
# 计算 SHA256
CHECKSUM=$(shasum -a 256 test-firmware-v1.0.3.bin | awk '{print $1}')
echo "Checksum: $CHECKSUM"

# 创建 job document
cat > job-document.json <<EOF
{
  "version": "1.0.3",
  "packageUrl": "$PRESIGNED_URL",
  "checksum": "$CHECKSUM",
  "checksumType": "sha256"
}
EOF

cat job-document.json
```

### 5. 创建 OTA Job（使用 Dynamic Thing Group）

```bash
# 获取 Thing Group ARN
GROUP_ARN=$(aws iot describe-thing-group --thing-group-name firmware-v1-0-2 --query 'thingGroupArn' --output text)

# 创建 Job
aws iot create-job \
  --job-id "ota-upgrade-$(date +%s)" \
  --targets "$GROUP_ARN" \
  --document file://job-document.json \
  --description "OTA upgrade from v1.0.2 to v1.0.3"
```

## 验证 OTA 流程

### 预期日志输出

设备客户端应输出以下日志：

```
[OtaClient] New OTA job received: OtaPackage{jobId='...', version='1.0.3', ...}
[Demo] New firmware available: ...
[Demo] Starting upgrade to version 1.0.3...
[OtaClient] Job ... status -> IN_PROGRESS
[Demo] Progress: 10% - downloading
[Demo] Progress: 50% - downloading
[Demo] Progress: 100% - downloading
[Demo] Status: firmware downloaded and verified, installing...
[Demo] Installing firmware: downloads/firmware-1.0.3.bin
[Demo] Verifying firmware signature...
[Demo] Backing up current firmware...
[Demo] Installing new firmware...
[Demo] Firmware installation completed.
[Demo] Device will reboot in 3 seconds...
[OtaClient] Job ... status -> SUCCEEDED
[OtaClient] Shadow updated: firmwareVersion=1.0.3
[Demo] Status: succeeded
```

### 验证 Shadow 更新

```bash
aws iot-data get-thing-shadow --thing-name ota-test-device output.json
cat output.json | jq .state.reported.firmwareVersion
```

应返回：`"1.0.3"`

### 验证 Job 状态

```bash
# 列出所有 jobs
aws iot list-jobs --max-results 5

# 查看特定 job execution
aws iot describe-job-execution \
  --job-id <JOB_ID> \
  --thing-name ota-test-device
```

`status` 应为 `"SUCCEEDED"`

### 验证设备自动离开旧 Group

设备升级后，Shadow 中的 `firmwareVersion` 变为 `1.0.3`，应自动离开 `firmware-v1-0-2` group：

```bash
# 等待索引更新
sleep 5

# 查询 group 成员
aws iot list-things-in-thing-group --thing-group-name firmware-v1-0-2
```

应看到 `ota-test-device` 已不在列表中。

## 故障排查

### 设备无法连接

1. 检查证书路径是否正确
2. 检查证书是否已激活：
   ```bash
   aws iot describe-certificate --certificate-id <CERT_ID>
   ```
3. 检查证书是否附加了 Policy，Policy 是否允许连接和订阅

### 未收到 Job 通知

1. 检查设备是否成功订阅了 Jobs topic（查看日志中的 `Subscribed to` 消息）
2. 检查设备是否在 Thing Group 中：
   ```bash
   aws iot list-things-in-thing-group --thing-group-name firmware-v1-0-2
   ```
3. 检查 Fleet Indexing 是否正确索引了 Shadow：
   ```bash
   aws iot search-index --query-string "shadow.reported.firmwareVersion:1.0.2"
   ```

### 下载失败

1. 检查 presigned URL 是否有效（未过期）：
   ```bash
   curl -I "$PRESIGNED_URL"
   ```
2. 检查网络连接
3. 检查 S3 bucket 权限

### Checksum 校验失败

1. 确认 job document 中的 checksum 与实际文件匹配：
   ```bash
   shasum -a 256 test-firmware-v1.0.3.bin
   cat job-document.json | jq .checksum
   ```

### Fleet Index 未更新

Fleet Indexing 有延迟（通常几秒），等待后重试：

```bash
# 强制等待
sleep 10

# 再次查询
aws iot search-index --query-string "thingName:ota-test-device"
```

## 清理资源

```bash
# 停止设备客户端（Ctrl+C）

# 删除测试文件
rm -rf downloads/
rm test-firmware-v1.0.3.bin
rm job-document.json
rm output.json

# 删除 S3 bucket
aws s3 rb s3://${BUCKET_NAME} --force

# 删除 Dynamic Thing Group
aws iot delete-dynamic-thing-group --thing-group-name firmware-v1-0-2

# 删除 Job（可选）
# aws iot delete-job --job-id <JOB_ID> --force
```
