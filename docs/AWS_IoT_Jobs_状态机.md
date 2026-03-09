# AWS IoT Jobs 状态机完整指南

## 状态机图

```
┌──────────────────────────────────────────────────────────────────┐
│                        AWS IoT Jobs 状态机                        │
└──────────────────────────────────────────────────────────────────┘

                     ┌─────────────────┐
                     │  Job Created    │
                     └────────┬────────┘
                              │
                              ▼
                        ┌─────────┐
              ┌─────────│ QUEUED  │◄──────────┐
              │         └────┬────┘           │
              │              │                │
              │              │ 设备开始执行     │
              │              ▼                │
              │      ┌──────────────┐         │ 设备更新状态
              │      │ IN_PROGRESS  │─────────┤ (允许来回切换)
              │      └──────┬───────┘         │
              │             │                 │
  设备拒绝     │             │                 │
 执行任务      │             ├─────────────────┘
              │             │
              │             │ 设备选择结果
              │             │
              ▼             ▼
        ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐
        │ REJECTED │   │SUCCEEDED │   │  FAILED  │   │TIMED_OUT │
        └──────────┘   └──────────┘   └──────────┘   └─────┬────┘
              │             │               │               │
              └─────────────┴───────────────┴───────────────┘
                             │
                             ▼
                    ┌────────────────┐
                    │  终态 (Terminal)│
                    │  无法再转换     │
                    └────────────────┘

        管理操作触发的状态:

        任意状态 ──管理员取消──> CANCELED  (终态)
        任意状态 ──管理员移除──> REMOVED   (终态)
```

---

## 状态详解

### 1. 初始状态

| 状态 | 说明 | 触发方式 |
|------|------|---------|
| **QUEUED** | 队列中等待执行 | AWS IoT 创建 Job 时自动设置 |

---

### 2. 执行中状态

| 状态 | 说明 | 谁触发 | 可转换到 |
|------|------|--------|---------|
| **IN_PROGRESS** | 正在执行中 | 设备上报 | SUCCEEDED, FAILED, REJECTED, TIMED_OUT, QUEUED |

**特殊行为：**
- ✅ `IN_PROGRESS` ↔ `QUEUED` 可以来回切换
- 用于设备暂停/恢复执行的场景

---

### 3. 终态 (Terminal States)

终态特点：
- ❌ **无法转换到其他任何状态**
- ✅ Job execution 生命周期结束
- ✅ 从 pending 队列中移除

| 状态 | 说明 | 触发方式 | 典型场景 |
|------|------|---------|---------|
| **SUCCEEDED** | 成功完成 | 设备上报 | OTA 升级成功 |
| **FAILED** | 执行失败 | 设备上报 | 下载失败、校验失败、安装失败 |
| **REJECTED** | 设备拒绝执行 | 设备上报 | 设备资源不足、不支持该任务 |
| **TIMED_OUT** | 执行超时 | AWS IoT 自动设置或设备上报 | 超过 `stepTimeoutInMinutes` |
| **CANCELED** | 已取消 | 管理员操作 | 管理员调用 `cancel-job` API |
| **REMOVED** | 已移除 | 管理员操作 | 管理员调用 `delete-job-execution` |

---

## 状态转换规则矩阵

### 合法转换 (✅)

| 从 ↓ / 到 → | QUEUED | IN_PROGRESS | SUCCEEDED | FAILED | REJECTED | TIMED_OUT | CANCELED | REMOVED |
|------------|--------|-------------|-----------|--------|----------|-----------|----------|---------|
| **QUEUED** | N/A | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **IN_PROGRESS** | ✅ | N/A | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **SUCCEEDED** | ❌ | ❌ | N/A | ❌ | ❌ | ❌ | ❌ | ❌ |
| **FAILED** | ❌ | ❌ | ❌ | N/A | ❌ | ❌ | ❌ | ❌ |
| **REJECTED** | ❌ | ❌ | ❌ | ❌ | N/A | ❌ | ❌ | ❌ |
| **TIMED_OUT** | ❌ | ❌ | ❌ | ❌ | ❌ | N/A | ❌ | ❌ |
| **CANCELED** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | N/A | ❌ |
| **REMOVED** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | N/A |

**关键规则：**
- ✅ 非终态 → 任意状态（包括其他非终态）
- ❌ 终态 → 任意状态（抛出 `InvalidStateTransitionException`）

---

## 特殊转换场景

### 1. QUEUED ↔ IN_PROGRESS（可逆）

```
场景：设备支持暂停/恢复

设备开始执行：
  QUEUED → IN_PROGRESS

设备暂停执行（如等待用户确认）：
  IN_PROGRESS → QUEUED

设备恢复执行：
  QUEUED → IN_PROGRESS
```

---

### 2. QUEUED → SUCCEEDED（直接跳过 IN_PROGRESS）

```
场景：任务无需执行即可完成

示例：
  - 检查发现设备已是目标状态
  - 任务已被其他方式完成

QUEUED ──────> SUCCEEDED (✅ 合法)
```

---

### 3. 超时处理

**自动超时：**
```
创建 Job 时设置超时：
  --timeout-config inProgressTimeoutInMinutes=60

AWS IoT 检测到超时后：
  IN_PROGRESS ──自动──> TIMED_OUT
```

**手动上报超时：**
```java
// 设备主动上报超时
reportJobStatus(jobId, "TIMED_OUT", "operation timeout");
```

---

### 4. 取消和移除（管理员操作）

**取消 Job：**
```bash
aws iot cancel-job --job-id xxx --force

任意状态 ──────> CANCELED
```

**删除 Job Execution：**
```bash
aws iot delete-job-execution --job-id xxx --thing-name device-001 --force

任意状态 ──────> REMOVED
```

---

## OTA 升级的典型状态流程

### 正常流程

```
1. QUEUED
   ↓ (设备收到 Job 通知)

2. IN_PROGRESS  (上报："downloading firmware")
   ↓ (下载完成)

3. IN_PROGRESS  (上报："verifying checksum")
   ↓ (校验通过)

4. IN_PROGRESS  (上报："installing firmware")
   ↓ (安装完成)

5. SUCCEEDED    (上报："firmware installed, device may reboot")
   + 更新 Shadow: firmwareVersion = "1.0.3"
```

---

### 下载失败流程

```
1. QUEUED
   ↓

2. IN_PROGRESS  (上报："downloading firmware")
   ↓ (网络错误 HTTP 404)

3. FAILED       (上报："download failed: HTTP 404")
```

---

### 校验失败流程

```
1. QUEUED
   ↓

2. IN_PROGRESS  (上报："downloading firmware")
   ↓ (下载完成)

3. IN_PROGRESS  (上报:"verifying checksum")
   ↓ (checksum 不匹配)

4. FAILED       (上报："checksum mismatch")
```

---

### 设备拒绝流程

```
场景：设备检查发现已是目标版本

1. QUEUED
   ↓ (设备检查当前版本)

2. REJECTED     (上报："already running target version 1.0.3")
   或
   SUCCEEDED    (直接标记成功)
```

---

## 代码实现对照

### 你的 OtaClient.java 状态转换

```java
public void startUpgrade(OtaPackage pkg) {
    try {
        // 1. 开始执行
        reportJobStatus(jobId, "IN_PROGRESS", "downloading firmware");
        //    QUEUED → IN_PROGRESS

        // 2. 下载固件
        boolean downloadSuccess = downloader.download(pkg, outputPath);

        if (!downloadSuccess) {
            // 3a. 下载失败
            reportJobStatus(jobId, "FAILED", "download or checksum failed");
            //    IN_PROGRESS → FAILED (终态)
            return;
        }

        // 3b. 开始安装
        boolean installed = listener.onInstallFirmware(outputPath);

        if (installed) {
            // 4a. 安装成功
            reportJobStatus(jobId, "SUCCEEDED", "firmware installed");
            //    IN_PROGRESS → SUCCEEDED (终态)
            reportVersion(pkg.getVersion());
        } else {
            // 4b. 安装失败
            reportJobStatus(jobId, "FAILED", "installation failed");
            //    IN_PROGRESS → FAILED (终态)
        }

    } catch (Exception e) {
        // 异常处理
        reportJobStatus(jobId, "FAILED", e.getMessage());
        //    IN_PROGRESS → FAILED (终态)
    }
}
```

**状态流转：**
```
QUEUED → IN_PROGRESS → {SUCCEEDED | FAILED}
                           ↓         ↓
                        (终态)    (终态)
```

---

## 常见错误和处理

### InvalidStateTransitionException

```
HTTP 409 Conflict

示例错误：
{
  "error": "InvalidStateTransitionException",
  "message": "An update attempted to change the job execution to a state
              that is invalid because of the job execution's current state
              (for example, an attempt to change a request in state SUCCESS
              to state IN_PROGRESS)"
}
```

**原因：** 尝试从终态转换到其他状态

**解决：**
- 检查当前状态再决定是否更新
- 实现版本检查避免重复执行已完成的 Job

---

### 防止重复执行（推荐实现）

```java
private void handleJobNotification(MqttMessage message) {
    JsonObject execution = ...;
    String jobId = execution.get("jobId").getAsString();
    JsonObject jobDoc = execution.getAsJsonObject("jobDocument");
    String targetVersion = jobDoc.get("version").getAsString();

    // ✅ 检查当前版本
    String currentVersion = listener.onQueryVersion();
    if (targetVersion.equals(currentVersion)) {
        // 已经是目标版本，直接标记成功
        reportJobStatus(jobId, "SUCCEEDED", "already running target version");
        return;
    }

    // 继续执行升级
    listener.onNewPackage(pkg);
}
```

---

## 参考文档

- [UpdateJobExecution API](https://docs.aws.amazon.com/iot/latest/apireference/API_iot-jobs-data_UpdateJobExecution.html)
- [JobExecution Data Type](https://docs.aws.amazon.com/iot/latest/apireference/API_JobExecution.html)
- [Job Notifications](https://docs.aws.amazon.com/iot/latest/developerguide/jobs-comm-notifications.html)

---

## 总结

### 核心规则

1. **非终态灵活，终态不可逆**
   - 非终态（QUEUED, IN_PROGRESS）可以相互转换
   - 终态（SUCCEEDED, FAILED, REJECTED, TIMED_OUT, CANCELED, REMOVED）无法转换

2. **设备主导状态转换**
   - 大部分状态转换由设备通过 MQTT 主动上报
   - AWS IoT 只在超时和管理操作时主动设置状态

3. **OTA 最佳实践**
   - 添加版本检查避免重复执行
   - 使用 statusDetails 提供详细信息
   - 妥善处理所有失败场景

### 你的代码状态维护

你的 OtaClient 实现**完全正确**：
- ✅ 开始时上报 IN_PROGRESS
- ✅ 成功时上报 SUCCEEDED + 更新 Shadow
- ✅ 失败时上报 FAILED
- ✅ 使用 AtomicBoolean 防止并发

**唯一建议：** 添加版本检查防止重复执行已完成的 Job。
