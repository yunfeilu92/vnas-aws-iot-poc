# OTA 客户端代码优化说明

## 优化概览

本次优化主要增强了 `OtaClient.java` 的健壮性和状态管理，涵盖以下场景：
- ✅ 版本检查（防止重复执行）
- ✅ Job Document 校验（REJECTED 状态）
- ✅ 下载失败处理
- ✅ Checksum 校验状态上报
- ✅ 安装失败处理
- ✅ 详细的状态追踪

---

## 关键优化点

### 1. 版本检查（防止重复执行）

**场景：** 设备重启后，未完成的 Job 会再次推送

**优化前：**
```java
// 收到 Job 后直接开始下载，可能重复执行
listener.onNewPackage(pkg);
```

**优化后：**
```java
// handleJobNotification() 中添加版本检查
String currentVersion = listener.onQueryVersion();
if (version.equals(currentVersion)) {
    System.out.println("[OtaClient] Already running target version " + version);
    reportJobStatus(jobId, "SUCCEEDED", "already running target version " + version);
    return;  // 直接标记成功，不再下载
}
```

**状态流转：**
```
QUEUED ──版本检查──> SUCCEEDED (直接完成，不执行)
```

**收益：**
- 避免重复下载已安装的固件
- 节省带宽和时间
- Job 自动标记为成功

---

### 2. Job Document 校验（REJECTED 状态）

**场景：** Job Document 缺少必要字段或格式错误

**优化后：**
```java
// 校验 packageUrl
if (packageUrl == null || packageUrl.isEmpty()) {
    reportJobStatus(jobId, "REJECTED", "invalid job document: missing packageUrl");
    return;
}

// 校验 checksum
if (checksum.isEmpty()) {
    reportJobStatus(jobId, "REJECTED", "invalid job document: missing checksum for security");
    return;
}
```

**状态流转：**
```
QUEUED ──校验失败──> REJECTED (设备拒绝执行)
```

**REJECTED 使用场景：**
- Job Document 格式错误
- 缺少必要字段（packageUrl, checksum）
- 设备不支持该类型的任务
- 设备资源不足（可在 listener 中判断）

---

### 3. 详细的状态追踪

**优化：** 在每个阶段上报详细的状态信息

#### 阶段 1：下载固件

```java
currentPhase = "downloading";
reportJobStatus(jobId, "IN_PROGRESS",
    "downloading firmware v" + version + " from " + packageUrl);
```

**状态详情示例：**
```
status: IN_PROGRESS
detail: downloading firmware v1.0.3 from https://s3.amazonaws.com/...
```

#### 阶段 2：校验 Checksum

```java
currentPhase = "verifying";
reportJobStatus(jobId, "IN_PROGRESS",
    "verifying firmware checksum (" + checksumType + ")");
```

**状态详情示例：**
```
status: IN_PROGRESS
detail: verifying firmware checksum (sha256)
```

#### 阶段 3：安装固件

```java
currentPhase = "installing";
reportJobStatus(jobId, "IN_PROGRESS",
    "installing firmware v" + version);
```

**状态详情示例：**
```
status: IN_PROGRESS
detail: installing firmware v1.0.3
```

---

### 4. 下载失败处理

**场景：** 网络错误、HTTP 404、下载中断

**优化后：**
```java
boolean downloadSuccess = downloader.download(pkg, outputPath);

if (!downloadSuccess) {
    reportJobStatus(jobId, "FAILED",
        "firmware download failed: unable to download from " + packageUrl);
    listener.onProgress(-1, "failed: download error");
    return;  // 立即返回，不继续执行
}
```

**状态流转：**
```
QUEUED → IN_PROGRESS (downloading) → FAILED
```

**失败原因可能包括：**
- HTTP 404 Not Found
- 网络超时
- Presigned URL 过期
- S3 权限错误

---

### 5. Checksum 校验失败处理

**实现位置：** `FirmwareDownloader.java` 中

**流程：**
```java
// FirmwareDownloader 内部
1. 下载文件
2. 计算实际 checksum
3. 对比 Job Document 中的 checksum
4. 如果不匹配 → return false
```

**OtaClient 处理：**
```java
boolean downloadSuccess = downloader.download(pkg, outputPath);
// 如果 downloadSuccess = false，可能是下载失败或 checksum 失败

if (!downloadSuccess) {
    reportJobStatus(jobId, "FAILED", "download or checksum failed");
    return;
}
```

**状态流转：**
```
QUEUED → IN_PROGRESS (downloading) → IN_PROGRESS (verifying) → FAILED
```

---

### 6. 安装失败处理

**场景：** 磁盘空间不足、权限问题、签名验证失败

**优化后：**
```java
boolean installed = listener.onInstallFirmware(outputPath);

if (!installed) {
    reportJobStatus(jobId, "FAILED",
        "firmware installation failed: device reported installation error");
    listener.onProgress(-1, "failed: installation error");
    return;
}
```

**状态流转：**
```
QUEUED → IN_PROGRESS (downloading) → IN_PROGRESS (verifying)
       → IN_PROGRESS (installing) → FAILED
```

---

### 7. 异常处理增强

**优化：** 记录异常发生的阶段

**优化前：**
```java
catch (Exception e) {
    reportJobStatus(jobId, "FAILED", e.getMessage());
}
```

**优化后：**
```java
String currentPhase = "initialization";  // 追踪当前阶段

try {
    currentPhase = "downloading";
    // ... 下载逻辑

    currentPhase = "verifying";
    // ... 校验逻辑

    currentPhase = "installing";
    // ... 安装逻辑

} catch (Exception e) {
    String errorDetail = String.format(
        "upgrade failed at phase '%s': %s",
        currentPhase,
        e.getMessage()
    );
    reportJobStatus(jobId, "FAILED", errorDetail);
}
```

**状态详情示例：**
```
status: FAILED
detail: upgrade failed at phase 'installing': Permission denied
```

**收益：**
- 快速定位问题发生的阶段
- 便于远程调试
- 提供详细的失败原因

---

## 完整状态流转图

### 正常流程

```
QUEUED
  ↓
  ├─ 版本检查 → SUCCEEDED (已是目标版本)
  │
  ├─ 校验失败 → REJECTED (Job Document 错误)
  │
  └─ 开始执行
      ↓
IN_PROGRESS (downloading)
      ↓
      ├─ 下载失败 → FAILED
      │
      └─ 下载成功
          ↓
    IN_PROGRESS (verifying checksum)
          ↓
          ├─ 校验失败 → FAILED
          │
          └─ 校验通过
              ↓
        IN_PROGRESS (installing)
              ↓
              ├─ 安装失败 → FAILED
              │
              └─ 安装成功 → SUCCEEDED
                  ↓
            更新 Shadow
```

---

## 使用场景对比

### 场景 1：正常升级

```
[OtaClient] New OTA job received: version=1.0.3
[OtaClient] Job status -> IN_PROGRESS (downloading firmware v1.0.3)
[Demo] Progress: 10% - downloading
[Demo] Progress: 50% - downloading
[Demo] Progress: 100% - downloading
[OtaClient] Job status -> IN_PROGRESS (verifying firmware checksum sha256)
[OtaClient] Job status -> IN_PROGRESS (installing firmware v1.0.3)
[Demo] Installing firmware: downloads/firmware-1.0.3.bin
[OtaClient] Job status -> SUCCEEDED (firmware v1.0.3 installed successfully)
[OtaClient] Shadow updated: firmwareVersion=1.0.3
```

---

### 场景 2：已是目标版本（优化后）

```
[OtaClient] New OTA job received: version=1.0.3
[OtaClient] Already running target version 1.0.3, marking job as succeeded
[OtaClient] Job status -> SUCCEEDED (already running target version 1.0.3)
```

**无需下载，直接完成！**

---

### 场景 3：Job Document 错误

```
[OtaClient] New OTA job received: version=1.0.3
[OtaClient] Invalid job document: missing checksum
[OtaClient] Job status -> REJECTED (invalid job document: missing checksum for security)
```

---

### 场景 4：下载失败

```
[OtaClient] New OTA job received: version=1.0.3
[OtaClient] Job status -> IN_PROGRESS (downloading firmware v1.0.3)
[Demo] Progress: 10% - downloading
[Demo] Status: failed: HTTP 404
[OtaClient] Job status -> FAILED (firmware download failed: unable to download from https://...)
```

---

### 场景 5：Checksum 失败

```
[OtaClient] New OTA job received: version=1.0.3
[OtaClient] Job status -> IN_PROGRESS (downloading firmware v1.0.3)
[Demo] Progress: 100% - downloading
[OtaClient] Job status -> IN_PROGRESS (verifying firmware checksum sha256)
[Demo] Status: failed: checksum mismatch
[OtaClient] Job status -> FAILED (firmware download failed: ...)
```

---

### 场景 6：安装失败

```
[OtaClient] New OTA job received: version=1.0.3
[OtaClient] Job status -> IN_PROGRESS (downloading firmware v1.0.3)
[Demo] Progress: 100% - downloading
[OtaClient] Job status -> IN_PROGRESS (verifying firmware checksum sha256)
[OtaClient] Job status -> IN_PROGRESS (installing firmware v1.0.3)
[Demo] Installing firmware failed: disk space insufficient
[OtaClient] Job status -> FAILED (firmware installation failed: device reported installation error)
```

---

## 云端监控示例

### 查看 Job 状态

```bash
aws iot describe-job-execution \
  --job-id ota-upgrade-123 \
  --thing-name device-001
```

**正常执行中：**
```json
{
  "execution": {
    "jobId": "ota-upgrade-123",
    "status": "IN_PROGRESS",
    "statusDetails": {
      "detail": "installing firmware v1.0.3"
    },
    "queuedAt": "2026-03-09T08:00:00Z",
    "startedAt": "2026-03-09T08:01:00Z",
    "lastUpdatedAt": "2026-03-09T08:02:30Z"
  }
}
```

**下载失败：**
```json
{
  "execution": {
    "jobId": "ota-upgrade-123",
    "status": "FAILED",
    "statusDetails": {
      "detail": "firmware download failed: unable to download from https://..."
    },
    "queuedAt": "2026-03-09T08:00:00Z",
    "startedAt": "2026-03-09T08:01:00Z",
    "lastUpdatedAt": "2026-03-09T08:01:45Z"
  }
}
```

**已是目标版本：**
```json
{
  "execution": {
    "jobId": "ota-upgrade-123",
    "status": "SUCCEEDED",
    "statusDetails": {
      "detail": "already running target version 1.0.3"
    },
    "queuedAt": "2026-03-09T08:00:00Z",
    "lastUpdatedAt": "2026-03-09T08:00:05Z"
  }
}
```

---

## 代码改动总结

### 文件：OtaClient.java

**新增功能：**
1. ✅ 版本检查逻辑（第 147-152 行）
2. ✅ Job Document 校验（第 154-165 行）
3. ✅ 详细的阶段追踪（currentPhase 变量）
4. ✅ 分阶段状态上报（downloading → verifying → installing）
5. ✅ 增强的错误信息（包含失败阶段）

**状态使用：**
- `IN_PROGRESS` - 执行中（细分为 downloading/verifying/installing）
- `SUCCEEDED` - 成功完成或已是目标版本
- `FAILED` - 下载/校验/安装失败
- `REJECTED` - Job Document 校验失败

---

## 测试验证

### 1. 测试版本检查

```bash
# 1. 设备运行 v1.0.3
# 2. 创建升级到 v1.0.3 的 Job
aws iot create-job --job-id test-same-version \
  --targets "arn:aws:iot:region:account:thing/device-001" \
  --document '{"version":"1.0.3","packageUrl":"...","checksum":"..."}'

# 预期：设备立即标记 Job 为 SUCCEEDED，不下载
```

### 2. 测试 REJECTED 状态

```bash
# 创建缺少 checksum 的 Job
aws iot create-job --job-id test-invalid-job \
  --targets "arn:aws:iot:region:account:thing/device-001" \
  --document '{"version":"1.0.4","packageUrl":"https://..."}'

# 预期：设备标记 Job 为 REJECTED
```

### 3. 测试下载失败

```bash
# 创建指向无效 URL 的 Job
aws iot create-job --job-id test-download-fail \
  --targets "arn:aws:iot:region:account:thing/device-001" \
  --document '{"version":"1.0.4","packageUrl":"https://invalid-url","checksum":"abc"}'

# 预期：设备尝试下载，失败后标记 Job 为 FAILED
```

---

## 后续优化建议

### 1. 添加重试机制

```java
// 下载失败时自动重试
int maxRetries = 3;
for (int i = 0; i < maxRetries; i++) {
    boolean success = downloader.download(pkg, outputPath);
    if (success) break;
    Thread.sleep(5000);  // 等待 5 秒后重试
}
```

### 2. 支持断点续传

```java
// 下载中断后从断点继续
downloader.resumeDownload(pkg, outputPath, existingBytes);
```

### 3. 添加回滚机制

```java
// 安装失败后自动回滚到上一版本
if (!installed) {
    rollbackToPreviousVersion();
    reportJobStatus(jobId, "FAILED", "installation failed, rolled back");
}
```

---

## 总结

本次优化显著提升了 OTA 客户端的**健壮性**和**可维护性**：

✅ **防止重复执行** - 版本检查节省带宽和时间
✅ **明确拒绝场景** - REJECTED 状态标识不可执行的任务
✅ **详细状态追踪** - 便于远程调试和监控
✅ **完善错误处理** - 记录失败阶段和原因
✅ **符合 AWS 规范** - 正确使用 Job 状态机

代码改动最小化，向后兼容，无需修改 `OtaListener` 接口。
