package com.vnas.iot;

/**
 * OTA 回调接口 — 对标华为 IoT SDK OTAListener。
 * 设备端实现此接口以响应 OTA 事件。
 */
public interface OtaListener {

    /**
     * 平台查询当前固件版本，设备返回版本号。
     * 在设备启动时调用，用于上报 Shadow reported.firmwareVersion。
     */
    String onQueryVersion();

    /**
     * 收到新固件包通知。设备可在此回调中决定是否开始升级。
     */
    void onNewPackage(OtaPackage pkg);

    /**
     * 升级进度/状态变化回调。
     *
     * @param percent     下载进度百分比 (0-100)，-1 表示非下载阶段
     * @param description 状态描述（如 "downloading", "verifying checksum", "succeeded", "failed: ..."）
     */
    void onProgress(int percent, String description);
}
