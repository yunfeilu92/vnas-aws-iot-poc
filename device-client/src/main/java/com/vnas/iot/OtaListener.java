package com.vnas.iot;

import java.nio.file.Path;

/**
 * OTA 回调接口 - 对标华为 IoT SDK OTAListener。
 *
 * 设备端实现此接口以响应 OTA 事件。
 *
 * 重要：onNewPackage() 语义变化
 * - 旧版本：仅是通知，OtaClient 自动执行升级
 * - 新版本：用户完全控制升级流程，需在此方法中：
 *   1. 检查版本 / 校验 Job Document
 *   2. 决定是否升级（可调用 service.reportJobStatus() 上报 REJECTED）
 *   3. 调用 FirmwareDownloader 下载
 *   4. 调用 onInstallFirmware() 安装
 *   5. 调用 service.reportJobStatus() 上报各阶段状态
 *   6. 调用 service.reportVersion() 上报新版本
 */
public interface OtaListener {

    /**
     * 注入 OtaService 引用，使 listener 能主动上报状态。
     *
     * 由 OtaService.setOtaListener() 自动调用，用户无需手动调用。
     *
     * @param service OtaService 实例
     */
    void setOtaService(OtaService service);

    /**
     * 查询当前固件版本。
     *
     * 在设备启动时调用，用于上报 Shadow reported.firmwareVersion。
     *
     * @return 当前固件版本号（如 "1.0.2"）
     */
    String onQueryVersion();

    /**
     * 收到新固件包通知 - 用户在此方法中完全控制升级流程。
     *
     * 重要：此方法可能在 MQTT 回调线程中执行，耗时操作应使用
     * CompletableFuture.runAsync() 异步处理。
     *
     * @param pkg OTA 包信息（包含 jobId、version、packageUrl、checksum）
     */
    void onNewPackage(OtaPackage pkg);

    /**
     * 固件安装回调（由用户在 onNewPackage 中调用）。
     *
     * @param firmwarePath 已下载的固件文件路径
     * @return true 表示安装成功（或即将重启），false 表示安装失败
     */
    boolean onInstallFirmware(Path firmwarePath);

    /**
     * 升级进度/状态变化回调（由 FirmwareDownloader 调用）。
     *
     * @param percent     下载进度百分比 (0-100)，-1 表示非下载阶段
     * @param description 状态描述（如 "downloading", "verifying checksum", "succeeded", "failed: ..."）
     */
    void onProgress(int percent, String description);
}
