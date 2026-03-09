# OTA 下载流程问题分析

## 问题 1：`parent` 为 null 导致 NPE 风险

**位置**：[FirmwareDownloader.java:59](../device-client/src/main/java/com/vnas/iot/FirmwareDownloader.java#L59)

```java
Files.createDirectories(outputPath.getParent());
```

**问题**：
- 如果 `outputPath` 没有父目录（如 `Paths.get("firmware.bin")`），`getParent()` 返回 `null`
- `Files.createDirectories(null)` 会抛出 `NullPointerException`

**触发条件**：
用户在 `OtaDemo` 中设置 `outputPath = Paths.get("firmware.bin")` 而非 `DOWNLOAD_DIR.resolve("firmware.bin")`

**修复建议**：
```java
// 方案 1：检查 null
Path parent = outputPath.getParent();
if (parent != null) {
    Files.createDirectories(parent);
}

// 方案 2：使用 toAbsolutePath() 确保有父目录
Files.createDirectories(outputPath.toAbsolutePath().getParent());
```

**影响等级**：🟡 中等（当前 OtaDemo 实现中不会触发，但 API 不够健壮）

---

## 问题 2：Checksum 为空导致 `substring()` 越界

**位置**：[FirmwareDownloader.java:88-90](../device-client/src/main/java/com/vnas/iot/FirmwareDownloader.java#L88-L90)

```java
listener.onProgress(-1, "failed: checksum mismatch (expected="
        + expectedHash.substring(0, 8) + "..., actual="
        + actualHash.substring(0, 8) + "...)");
```

**问题**：
- 如果 `expectedHash` 长度 < 8，`substring(0, 8)` 抛出 `StringIndexOutOfBoundsException`
- 虽然 SHA-256 hash 长度固定为 64 字符，但 `extractHash()` 可能返回空字符串

**触发条件**：
- Job Document 中 `checksum` 为空字符串或格式错误（如 `"sha256:"`）
- 当前 OtaDemo 在 Phase 2 已校验 `checksum.isEmpty()`，不会触发

**修复建议**：
```java
// 安全截取前 8 个字符
String expectedPrefix = expectedHash.length() >= 8 ?
    expectedHash.substring(0, 8) : expectedHash;
String actualPrefix = actualHash.length() >= 8 ?
    actualHash.substring(0, 8) : actualHash;

listener.onProgress(-1, "failed: checksum mismatch (expected="
        + expectedPrefix + "..., actual=" + actualPrefix + "...)");
```

**影响等级**：🟢 低（当前 OtaDemo 已有前置校验）

---

## 问题 3：下载中途失败不清理部分文件

**位置**：[FirmwareDownloader.java:60-78](../device-client/src/main/java/com/vnas/iot/FirmwareDownloader.java#L60-L78)

**问题**：
- HTTP 错误（statusCode != 200）时，直接返回 false，不删除已创建的空目录
- 下载中途网络中断时，部分文件残留在磁盘上
- 只有 checksum 不匹配时才调用 `Files.deleteIfExists(outputPath)`

**触发条件**：
- S3 presigned URL 过期返回 403
- 网络中断导致 `IOException`

**当前行为**：
```bash
# 假设下载失败
downloads/
  └── firmware-1.0.3.bin  (部分下载，大小 < 预期)
```

**修复建议**：
```java
public boolean download(OtaPackage pkg, Path outputPath) {
    boolean success = false;
    try {
        // ... 下载逻辑 ...
        success = true;  // 只有完全成功才设置为 true
        return true;
    } catch (...) {
        return false;
    } finally {
        // 下载失败时清理文件
        if (!success) {
            try {
                Files.deleteIfExists(outputPath);
            } catch (IOException ignored) {}
        }
    }
}
```

**影响等级**：🟡 中等（磁盘碎片，可能导致下次下载覆盖错误文件）

---

## 问题 4：HTTP 重定向未处理

**位置**：[FirmwareDownloader.java:22-23](../device-client/src/main/java/com/vnas/iot/FirmwareDownloader.java#L22-L23)

```java
this.httpClient = HttpClient.newHttpClient();
```

**问题**：
- 默认的 `HttpClient` 不跟随重定向
- S3 presigned URL 可能返回 302/307 重定向

**修复建议**：
```java
this.httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
```

**影响等级**：🟢 低（AWS S3 presigned URL 通常直接返回 200，不需要重定向）

---

## 问题 5：HEAD 请求重复发送

**位置**：[FirmwareDownloader.java:44](../device-client/src/main/java/com/vnas/iot/FirmwareDownloader.java#L44)

```java
long totalSize = getContentLength(pkg.getPackageUrl());
```

**问题**：
- 先发 HEAD 请求获取 `content-length`
- 再发 GET 请求下载文件
- 对于大文件，HEAD 请求增加延迟（虽然很小）

**是否需要修复**：
❌ 不需要。HEAD 请求只是为了获取文件大小以显示进度百分比，是合理的权衡。如果失败，返回 -1 并降级为不显示百分比，不影响功能。

**影响等级**：🟢 可接受的设计

---

## 验证建议

### 测试用例

1. **正常流程**：
   ```bash
   # 创建有效的 Job
   aws iot create-job \
     --job-id "ota-test-$(date +%s)" \
     --targets "arn:aws:iot:us-east-1:xxx:thing/device-001" \
     --document file://device-client/job-document.json
   ```

2. **Checksum 不匹配**：
   ```json
   {
     "version": "1.0.3",
     "packageUrl": "https://s3.../firmware.bin",
     "checksum": "sha256:0000000000000000000000000000000000000000000000000000000000000000",
     "checksumType": "sha256"
   }
   ```
   预期：下载后校验失败，文件被删除

3. **无效 URL**：
   ```json
   {
     "version": "1.0.3",
     "packageUrl": "https://example.com/not-found",
     "checksum": "sha256:abc123...",
     "checksumType": "sha256"
   }
   ```
   预期：HTTP 404，返回 FAILED

4. **Presigned URL 过期**：
   等待 URL 过期后再创建 Job
   预期：HTTP 403，返回 FAILED

---

## 修复优先级

| 问题 | 等级 | 是否需要立即修复 |
|------|------|------------------|
| 问题 1: parent 为 null | 🟡 中等 | 建议修复（提高 API 健壮性） |
| 问题 2: substring 越界 | 🟢 低 | 可选（当前有前置校验） |
| 问题 3: 部分文件残留 | 🟡 中等 | 建议修复（避免磁盘碎片） |
| 问题 4: 未跟随重定向 | 🟢 低 | 可选（S3 通常不需要） |
| 问题 5: HEAD 重复请求 | 🟢 可接受 | 不需要修复 |

---

## 总结

当前实现整体健壮，主要问题：
- ✅ 线程安全无问题
- ✅ 资源管理正确
- ✅ 错误处理完整
- ⚠️ 边界情况处理可优化（问题 1、3）

建议修复问题 1 和问题 3，使下载器更加健壮。
