# AWS IoT OTA 升级流程总结

## 整体架构流程

```
┌─────────────────┐         ┌──────────────────┐         ┌─────────────────┐
│   管理员/平台    │         │   AWS IoT Core   │         │   IoT 设备      │
└────────┬────────┘         └────────┬─────────┘         └────────┬────────┘
         │                           │                            │
         │ 1. 上传固件到 S3          │                            │
         │───────────────────────────>                            │
         │                           │                            │
         │ 2. 创建 Dynamic Thing Group│                           │
         │    (查询条件：firmwareVersion:1.0.2)                   │
         │───────────────────────────>                            │
         │                           │                            │
         │ 3. 创建 IoT Job            │                            │
         │    (target: Thing Group)  │                            │
         │───────────────────────────>                            │
         │                           │                            │
         │                           │ 4. 推送 Job 通知           │
         │                           │   (jobs/notify-next)       │
         │                           │───────────────────────────>│
         │                           │                            │
         │                           │ 5. 设备上报 IN_PROGRESS    │
         │                           │<───────────────────────────│
         │                           │                            │
         │                           │ 6. 下载固件 (presigned URL)│
         │                           │                 ┌──────────┤
         │                           │                 │  S3      │
         │                           │                 └──────────>│
         │                           │                            │
         │                           │ 7. 校验 checksum           │
         │                           │                            │
         │                           │ 8. 安装固件 + 重启         │
         │                           │                            │
         │                           │ 9. 上报 SUCCEEDED          │
         │                           │<───────────────────────────│
         │                           │                            │
         │                           │ 10. 更新 Shadow            │
         │                           │     firmwareVersion=1.0.3  │
         │                           │<───────────────────────────│
         │                           │                            │
         │                           │ 11. Fleet Index 更新       │
         │                           │     设备自动离开旧 Group   │
         │                           │                            │
```

## 云端配置流程

### 1. 启用 Fleet Indexing

```bash
aws iot update-indexing-configuration \
  --thing-indexing-configuration \
    thingIndexingMode=REGISTRY_AND_SHADOW,\
    thingConnectivityIndexingMode=STATUS
```

**作用：** 索引 Device Shadow 的 `firmwareVersion` 字段，支持动态查询

### 2. 上传固件到 S3

```bash
# 创建固件文件
echo "Firmware v1.0.3" > firmware-v1.0.3.bin

# 上传到 S3
aws s3 cp firmware-v1.0.3.bin \
  s3://your-bucket/firmware/v1.0.3/firmware.bin
```

### 3. 创建 Dynamic Thing Group

```bash
aws iot create-dynamic-thing-group \
  --thing-group-name "firmware-v1-0-2" \
  --query-string "shadow.reported.firmwareVersion:1.0.2"
```

**作用：** 自动匹配当前版本为 1.0.2 的所有设备

### 4. 生成 Presigned URL 和 Checksum

```bash
# Presigned URL（有效期 2 小时）
PRESIGNED_URL=$(aws s3 presign \
  s3://your-bucket/firmware/v1.0.3/firmware.bin \
  --expires-in 7200)

# 计算 SHA256 checksum
CHECKSUM=$(shasum -a 256 firmware-v1.0.3.bin | awk '{print $1}')
```

### 5. 创建 Job Document

```bash
cat > job-document.json <<EOF
{
  "version": "1.0.3",
  "packageUrl": "$PRESIGNED_URL",
  "checksum": "$CHECKSUM",
  "checksumType": "sha256"
}
EOF
```

### 6. 创建 IoT Job

```bash
aws iot create-job \
  --job-id "ota-upgrade-$(date +%s)" \
  --targets "arn:aws:iot:region:account:thinggroup/firmware-v1-0-2" \
  --document file://job-document.json \
  --description "OTA upgrade to v1.0.3"
```

## 设备端流程

### 1. 连接 AWS IoT Core

```java
OtaClient client = new OtaClient(thingName, listener, downloadDir);
client.connect(endpoint, certPath, keyPath, caPath);
```

**步骤：**
- 使用 X.509 证书建立 MQTT 连接
- 上报当前版本到 Device Shadow
- 订阅 `jobs/notify-next` 和 `jobs/$next/get/accepted`
- 主动请求获取 pending jobs（处理连接前已创建的 job）

### 2. 接收 Job 通知

**两种方式：**

**方式 1：被动通知（新创建的 job）**
- Topic: `$aws/things/{thingName}/jobs/notify-next`
- 触发：Job 创建后自动推送

**方式 2：主动查询（pending 的 job）**
- 发布：`$aws/things/{thingName}/jobs/$next/get`
- 接收：`$aws/things/{thingName}/jobs/$next/get/accepted`

### 3. 解析 Job Document

```java
{
  "execution": {
    "jobId": "ota-upgrade-1773043762",
    "jobDocument": {
      "version": "1.0.3",
      "packageUrl": "https://...",
      "checksum": "bd851d293e82...",
      "checksumType": "sha256"
    }
  }
}
```

### 4. 执行升级流程

```
1. 上报 IN_PROGRESS
   ↓
2. 下载固件（通过 presigned URL）
   ↓
3. 校验 SHA256 checksum
   ↓
4. 调用 onInstallFirmware() 回调
   - 签名验证（可选）
   - 备份当前固件（可选）
   - 解压/替换二进制文件
   - 更新启动脚本
   - 触发设备重启
   ↓
5. 上报 SUCCEEDED
   ↓
6. 更新 Device Shadow: firmwareVersion=1.0.3
```

### 5. 自动管理 Thing Group

- 升级成功后，Shadow 更新为 `1.0.3`
- Fleet Index 检测到变化，设备自动离开 `firmware-v1-0-2` group
- 如果有 `firmware-v1-0-3` group，设备自动加入

## 关键技术点

### 1. Fleet Indexing

**作用：**
- 索引 Device Shadow 和 Thing Registry 属性
- 支持基于属性的动态查询
- 延迟：几秒钟

**配置：**
```json
{
  "thingIndexingMode": "REGISTRY_AND_SHADOW",
  "thingConnectivityIndexingMode": "STATUS"
}
```

### 2. Dynamic Thing Group

**特点：**
- 基于查询条件自动匹配设备
- 设备属性变化时自动加入/离开
- 查询语法：`shadow.reported.firmwareVersion:1.0.2`

### 3. Presigned URL

**优点：**
- 临时授权，无需永久公开 S3 bucket
- 可设置过期时间（推荐 2-24 小时）
- 避免在设备端配置 AWS credentials

**注意：**
- URL 过期后无法下载，需重新生成
- 建议在 Job Document 中同时提供 S3 路径（供设备自行生成）

### 4. Jobs Topic 机制

**notify-next vs $next/get：**

| Topic | 触发时机 | 用途 |
|-------|---------|------|
| `jobs/notify-next` | Job 创建时推送 | 被动接收新 job |
| `jobs/$next/get` + `$next/get/accepted` | 设备主动请求 | 获取 pending jobs |

**最佳实践：** 同时订阅两个 topic，确保不漏任何 job

### 5. 去重机制（推荐实现）

**场景：** 设备重启后，未完成的 job 会再次下发

**方案：**
```java
String currentVersion = listener.onQueryVersion();
if (version.equals(currentVersion)) {
    // 已运行目标版本，直接标记 job 成功
    reportJobStatus(jobId, "SUCCEEDED", "already running target version");
    return;
}
```

## 完整代码结构

```
device-client/
├── src/main/java/com/vnas/iot/
│   ├── OtaClient.java          # MQTT 连接、Job 订阅、状态上报
│   ├── OtaListener.java        # 回调接口（版本查询、安装、进度）
│   ├── OtaPackage.java         # Job Document 数据模型
│   ├── FirmwareDownloader.java # 固件下载、checksum 校验
│   └── OtaDemo.java            # 示例程序
├── pom.xml                     # Maven 配置
└── certs/                      # X.509 证书
    ├── device.pem.crt
    ├── device.pem.key
    └── AmazonRootCA1.pem
```

## 测试验证清单

- [ ] 设备能成功连接 AWS IoT Core
- [ ] Device Shadow 正确上报 `firmwareVersion`
- [ ] 设备自动加入 Dynamic Thing Group
- [ ] 收到 Job 通知并开始下载
- [ ] Checksum 校验通过
- [ ] 安装回调被正确调用
- [ ] Job 状态正确上报为 SUCCEEDED
- [ ] Device Shadow 更新为新版本
- [ ] 设备自动离开旧 Thing Group
- [ ] 重启后不会重复下载已完成的 job

## 故障排查

| 问题 | 可能原因 | 解决方案 |
|------|---------|---------|
| 设备未收到 job | 未订阅 `$next/get/accepted` | 订阅该 topic 并调用 `requestPendingJobs()` |
| 下载失败 404 | Presigned URL 过期 | 重新生成 URL，延长有效期 |
| Checksum 校验失败 | 文件损坏或 checksum 错误 | 重新计算并更新 Job Document |
| 设备未加入 group | Fleet Index 未更新 | 等待几秒，检查 Shadow 是否正确上报 |
| 重复下载 | 未检查当前版本 | 添加版本检查逻辑 |

## 参考文档

- [AWS IoT Jobs](https://docs.aws.amazon.com/iot/latest/developerguide/iot-jobs.html)
- [Fleet Indexing](https://docs.aws.amazon.com/iot/latest/developerguide/iot-indexing.html)
- [Dynamic Thing Groups](https://docs.aws.amazon.com/iot/latest/developerguide/dynamic-thing-groups.html)
- [Device Shadow](https://docs.aws.amazon.com/iot/latest/developerguide/iot-device-shadows.html)
