# VNAS IoT OTA 升级方案 - AWS 资源清单

## 概述

基于 AWS IoT Core 实现设备 OTA 升级，支持按固件版本筛选目标设备，通过 IoT Job 下发安装包。

## 需要开启/配置的 AWS 资源

### 必须资源

| 资源 | 用途 | 是否需要显式开启 |
|------|------|------------------|
| IoT Thing | 代表每台设备 | 注册时创建 |
| Thing Attributes | 记录 `firmwareVersion` 等元数据 | 注册时写入 |
| X.509 Certificate | 设备身份认证 | 创建并关联到 Thing |
| IoT Policy | 控制设备 MQTT 权限 | 创建并关联到 Certificate |
| Fleet Indexing | 支持按属性查询设备 | **需显式开启** |
| Dynamic Thing Group | 按版本自动分组设备 | 创建时定义查询条件 |
| S3 Bucket | 存放安装包 + Job Document | 创建 |
| IoT Job | 下发升级任务到目标设备组 | 按需创建 |
| IAM Role (Job 执行) | IoT Job 访问 S3 生成 presigned URL | 创建 |
| Device Shadow | 设备状态同步（含版本上报） | 自动创建，无需开启 |

### 推荐资源

| 资源 | 用途 |
|------|------|
| AWS Signer / Signing Profile | 安装包签名验证 |
| S3 Versioning | 安装包版本管理 |
| CloudWatch Logs | Job 执行状态监控 |
| ACM Private CA | 自有 CA 签名（如需要） |

## 版本识别 & 设备筛选

### 问题

- IoT Thing Type 不支持控制台动态筛选
- 手动选择设备不可扩展

### 方案：Fleet Indexing + Dynamic Thing Group

```bash
# 1. 开启 Fleet Indexing
aws iot update-indexing-configuration \
  --thing-indexing-configuration thingIndexingMode=REGISTRY_AND_SHADOW

# 2. 创建 Dynamic Thing Group（按版本筛选）
aws iot create-dynamic-thing-group \
  --thing-group-name "firmware-v1.0.2" \
  --query-string "attributes.firmwareVersion:1.0.2"
```

设备版本变化后会**自动进出分组**，无需手动维护。

## IoT Job 升级流程

### Job Document 格式（需与设备端协商）

```json
{
  "operation": "firmwareUpdate",
  "version": "1.1.0",
  "packageUrl": "${aws:iot:s3-presigned-url:https://s3.amazonaws.com/BUCKET/firmware-v1.1.0.bin}",
  "checksum": "sha256:xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "checksumType": "sha256"
}
```

### 工作流

```
设备注册时写入 attributes.firmwareVersion
        │
Fleet Indexing 索引 ──▶ Dynamic Thing Group（version=1.0.2）
                              │
                         IoT Job 目标
                              │
                    Job Document（S3）
                    ├── downloadUrl: presigned URL
                    ├── checksum: sha256:xxx
                    └── version: 1.1.0
                              │
                    设备下载 ──▶ 安装 ──▶ 上报新版本号
                                          │
                              自动退出旧版本 Dynamic Group
```

### 包签名流程（推荐）

```bash
# 创建 Signing Profile
aws signer put-signing-profile \
  --profile-name vnas-firmware-signing \
  --platform-id AmazonFreeRTOS-Default

# 签名安装包
aws signer start-signing-job \
  --source 's3={bucketName=BUCKET,key=firmware-v1.1.0.bin,version=VERSION_ID}' \
  --destination 's3={bucketName=BUCKET,prefix=signed/}' \
  --profile-name vnas-firmware-signing
```

## 关键注意事项

1. **Fleet Indexing 是前置条件**：不开启则 Dynamic Thing Group 无法工作
2. **Job Document 格式自定义**：AWS 不规定字段，设备端和云端需提前约定 schema
3. **版本闭环**：设备升级完成后必须上报新版本号（通过 Shadow 或 Thing Attribute），自动退出旧版本 Group
4. **presigned URL**：Job Document 中使用 `${aws:iot:s3-presigned-url:...}` 占位符，IoT Core 会自动生成临时下载链接
