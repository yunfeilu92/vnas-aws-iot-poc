package com.vnas.iot;

import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

/**
 * OTA 客户端使用示例 - 重构版。
 *
 * 用法：
 *   java -jar device-client.jar <endpoint> <thingName> <certPath> <keyPath> <caPath>
 *
 * 示例：
 *   java -jar device-client.jar \
 *     a1b2c3d4e5f6g7-ats.iot.ap-southeast-1.amazonaws.com \
 *     device-001 \
 *     certs/device.pem.crt \
 *     certs/device.pem.key \
 *     certs/AmazonRootCA1.pem
 *
 * 重构说明：
 * - 用户在 onNewPackage() 中完全控制升级流程
 * - 通过 service.reportJobStatus() 主动上报各阶段状态
 * - 通过 service.reportVersion() 上报新版本
 * - 固件版本持久化到 firmware_version.txt，重启后自动恢复
 */
public class OtaDemo {

    private static final Path DOWNLOAD_DIR = Paths.get("downloads");
    private static final Path VERSION_FILE = Paths.get("firmware_version.txt");

    /**
     * 读取当前固件版本（从文件读取，如果文件不存在则使用默认值）
     */
    private static String getCurrentVersion() {
        try {
            if (java.nio.file.Files.exists(VERSION_FILE)) {
                return java.nio.file.Files.readString(VERSION_FILE).trim();
            }
        } catch (Exception e) {
            System.err.println("[Demo] Failed to read version file: " + e.getMessage());
        }
        return "1.0.2"; // 默认版本
    }

    /**
     * 保存新版本到文件
     */
    private static void saveVersion(String version) {
        try {
            java.nio.file.Files.writeString(VERSION_FILE, version);
            System.out.println("[Demo] Version saved to file: " + version);
        } catch (Exception e) {
            System.err.println("[Demo] Failed to save version: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("Usage: java -jar device-client.jar <endpoint> <thingName> <certPath> <keyPath> <caPath>");
            System.exit(1);
        }

        String endpoint = args[0];
        String thingName = args[1];
        String certPath = args[2];
        String keyPath = args[3];
        String caPath = args[4];

        // 1. 创建 MQTT 连接（未连接）
        AwsIotMqttConnectionBuilder builder = AwsIotMqttConnectionBuilder.newMtlsBuilderFromPath(certPath, keyPath);
        builder.withEndpoint(endpoint)
                .withClientId(thingName)
                .withCertificateAuthorityFromPath(null, caPath);
        MqttClientConnection connection = builder.build();

        // 2. 创建 OtaService
        OtaService otaService = new OtaService(connection, thingName);

        // 3. 创建 Listener 实现
        OtaListener listener = new OtaListener() {
            private OtaService service;

            @Override
            public void setOtaService(OtaService service) {
                this.service = service;
            }

            @Override
            public String onQueryVersion() {
                String currentVersion = getCurrentVersion();
                System.out.println("[Demo] Current firmware version: " + currentVersion);
                return currentVersion;
            }

            @Override
            public void onNewPackage(OtaPackage pkg) {
                // ✨ 完整控制升级流程（类似华为 SDK 示例）
                System.out.println("[Demo] New firmware package received: " + pkg);

                // ===== 1. 版本检查 =====
                String currentVersion = getCurrentVersion();
                if (pkg.getVersion().equals(currentVersion)) {
                    System.out.println("[Demo] Already running target version " + pkg.getVersion());
                    try {
                        service.reportJobStatus(pkg.getJobId(), "SUCCEEDED",
                                "already running target version " + pkg.getVersion());
                    } catch (Exception e) {
                        System.err.println("[Demo] Failed to report status: " + e.getMessage());
                    }
                    return;
                }

                // ===== 2. 校验 Job Document =====
                if (pkg.getPackageUrl() == null || pkg.getPackageUrl().isEmpty()) {
                    System.err.println("[Demo] Invalid job document: missing packageUrl");
                    try {
                        service.reportJobStatus(pkg.getJobId(), "REJECTED",
                                "invalid job document: missing packageUrl");
                    } catch (Exception e) {
                        System.err.println("[Demo] Failed to report status: " + e.getMessage());
                    }
                    return;
                }

                if (pkg.getChecksum() == null || pkg.getChecksum().isEmpty()) {
                    System.err.println("[Demo] Invalid job document: missing checksum");
                    try {
                        service.reportJobStatus(pkg.getJobId(), "REJECTED",
                                "invalid job document: missing checksum for security");
                    } catch (Exception e) {
                        System.err.println("[Demo] Failed to report status: " + e.getMessage());
                    }
                    return;
                }

                // ===== 3. 异步执行升级流程 =====
                // 注意：MQTT 回调线程不应阻塞，因此使用 CompletableFuture 异步处理
                CompletableFuture.runAsync(() -> {
                    String currentPhase = "initialization";
                    try {
                        // 阶段 1: 开始下载
                        currentPhase = "downloading";
                        System.out.println("[Demo] Starting firmware download...");
                        service.reportJobStatus(pkg.getJobId(), "IN_PROGRESS",
                                "downloading firmware v" + pkg.getVersion() + " from " + pkg.getPackageUrl());

                        FirmwareDownloader downloader = new FirmwareDownloader(this);
                        Path outputPath = DOWNLOAD_DIR.resolve("firmware-" + pkg.getVersion() + ".bin");

                        boolean downloadSuccess = downloader.download(pkg, outputPath);
                        if (!downloadSuccess) {
                            service.reportJobStatus(pkg.getJobId(), "FAILED",
                                    "firmware download failed: unable to download from " + pkg.getPackageUrl());
                            onProgress(-1, "failed: download error");
                            return;
                        }

                        // 阶段 2: 校验 checksum
                        currentPhase = "verifying";
                        System.out.println("[Demo] Firmware downloaded, verifying checksum...");
                        service.reportJobStatus(pkg.getJobId(), "IN_PROGRESS",
                                "verifying firmware checksum (" + pkg.getChecksumType() + ")");
                        // FirmwareDownloader 已在 download() 中完成校验

                        // 阶段 3: 安装固件
                        currentPhase = "installing";
                        System.out.println("[Demo] Checksum verified, installing firmware...");
                        service.reportJobStatus(pkg.getJobId(), "IN_PROGRESS",
                                "installing firmware v" + pkg.getVersion());

                        boolean installed = onInstallFirmware(outputPath);
                        if (!installed) {
                            service.reportJobStatus(pkg.getJobId(), "FAILED",
                                    "firmware installation failed: device reported installation error");
                            onProgress(-1, "failed: installation error");
                            return;
                        }

                        // 阶段 4: 安装成功，保存新版本到文件
                        currentPhase = "completed";
                        System.out.println("[Demo] Firmware installation completed successfully.");

                        // 先保存版本到文件（重启后生效）
                        saveVersion(pkg.getVersion());

                        // 上报 job 状态
                        service.reportJobStatus(pkg.getJobId(), "SUCCEEDED",
                                "firmware v" + pkg.getVersion() + " installed successfully, device may reboot");

                        // 更新 Device Shadow
                        service.reportVersion(pkg.getVersion());

                        onProgress(-1, "succeeded");

                    } catch (Exception e) {
                        System.err.println("[Demo] Upgrade error at phase '" + currentPhase + "': " + e.getMessage());
                        e.printStackTrace();
                        try {
                            String errorDetail = String.format(
                                    "upgrade failed at phase '%s': %s",
                                    currentPhase,
                                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
                            );
                            service.reportJobStatus(pkg.getJobId(), "FAILED", errorDetail);
                        } catch (Exception reportErr) {
                            System.err.println("[Demo] Failed to report error status: " + reportErr.getMessage());
                        }
                        onProgress(-1, "failed: " + e.getMessage());
                    }
                });
            }

            @Override
            public boolean onInstallFirmware(Path firmwarePath) {
                System.out.println("[Demo] Installing firmware: " + firmwarePath);

                try {
                    // 模拟固件安装流程
                    // 实际设备应实现：
                    // 1. 签名验证（如有）
                    // 2. 备份当前固件
                    // 3. 解压/替换二进制文件
                    // 4. 更新启动脚本或配置
                    // 5. 触发设备重启

                    System.out.println("[Demo] Verifying firmware signature...");
                    Thread.sleep(500);

                    System.out.println("[Demo] Backing up current firmware...");
                    Thread.sleep(300);

                    System.out.println("[Demo] Installing new firmware...");
                    Thread.sleep(1000);

                    System.out.println("[Demo] Firmware installation completed.");
                    System.out.println("[Demo] Device will reboot in 3 seconds...");

                    // 实际设备此处应触发重启：Runtime.getRuntime().exec("reboot");
                    // Demo 环境下仅打印日志
                    return true;

                } catch (Exception e) {
                    System.err.println("[Demo] Firmware installation failed: " + e.getMessage());
                    return false;
                }
            }

            @Override
            public void onProgress(int percent, String description) {
                if (percent >= 0) {
                    System.out.printf("[Demo] Progress: %d%% - %s%n", percent, description);
                } else {
                    System.out.println("[Demo] Status: " + description);
                }
            }
        };

        // 4. 注册 listener（会自动反向注入 service）
        otaService.setOtaListener(listener);

        // 5. 创建 OtaClient（传入 connection）
        OtaClient client = new OtaClient(thingName, otaService, connection);

        // 6. 添加 shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                client.disconnect();
                connection.close();
                builder.close();
            } catch (Exception e) {
                System.err.println("Error during disconnect: " + e.getMessage());
            }
        }));

        // 7. 连接
        System.out.println("[Demo] Connecting to " + endpoint + " as " + thingName + "...");
        client.connect();

        System.out.println("[Demo] Waiting for OTA job... (Ctrl+C to exit)");
        Thread.currentThread().join();
    }
}
