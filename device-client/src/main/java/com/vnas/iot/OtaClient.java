package com.vnas.iot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AWS IoT OTA 客户端核心类。
 *
 * 职责：
 * - X.509 证书认证建立 MQTT 连接
 * - 订阅 Jobs notify-next 接收 OTA 任务
 * - 解析 Job Document → OtaPackage → 回调 listener
 * - 上报 Job 执行状态（IN_PROGRESS / SUCCEEDED / FAILED）
 * - 更新 Device Shadow reported.firmwareVersion
 */
public class OtaClient {

    private final String thingName;
    private final OtaListener listener;
    private final Gson gson = new Gson();
    private final Path downloadDir;
    private final AtomicBoolean upgrading = new AtomicBoolean(false);

    private AwsIotMqttConnectionBuilder builder;
    private MqttClientConnection connection;

    /**
     * @param thingName   AWS IoT Thing 名称
     * @param listener    OTA 回调
     * @param downloadDir 固件下载目录
     */
    public OtaClient(String thingName, OtaListener listener, Path downloadDir) {
        this.thingName = thingName;
        this.listener = listener;
        this.downloadDir = downloadDir;
    }

    /**
     * 建立 MQTT 连接并订阅 Job 通知。
     *
     * @param endpoint IoT Core endpoint (xxx-ats.iot.region.amazonaws.com)
     * @param certPath 设备证书路径
     * @param keyPath  设备私钥路径
     * @param caPath   Root CA 路径
     */
    public void connect(String endpoint, String certPath, String keyPath, String caPath) throws Exception {
        builder = AwsIotMqttConnectionBuilder.newMtlsBuilderFromPath(certPath, keyPath);
        builder.withEndpoint(endpoint)
                .withClientId(thingName)
                .withCertificateAuthorityFromPath(null, caPath);

        connection = builder.build();

        try {
            connection.connect().get();
            System.out.println("[OtaClient] MQTT connected: " + thingName);

            // 启动时上报当前版本到 Shadow
            reportVersion(listener.onQueryVersion());

            // 订阅 Jobs notify-next
            subscribeJobNotifications();
        } catch (Exception e) {
            connection.close();
            connection = null;
            throw e;
        }
    }

    /**
     * 订阅 $aws/things/{thingName}/jobs/notify-next，接收下一个待执行的 Job。
     */
    private void subscribeJobNotifications() throws Exception {
        String topic = "$aws/things/" + thingName + "/jobs/notify-next";

        connection.subscribe(topic, QualityOfService.AT_LEAST_ONCE, (message) -> {
            handleJobNotification(message);
        }).get();

        System.out.println("[OtaClient] Subscribed to: " + topic);
    }

    private void handleJobNotification(MqttMessage message) {
        try {
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            JsonObject root = gson.fromJson(payload, JsonObject.class);

            // notify-next 的 payload 结构：{ "execution": { "jobId": "...", "jobDocument": {...} } }
            if (!root.has("execution") || root.get("execution").isJsonNull()) {
                System.out.println("[OtaClient] No pending job.");
                return;
            }

            JsonObject execution = root.getAsJsonObject("execution");
            String jobId = execution.get("jobId").getAsString();
            JsonObject jobDoc = execution.getAsJsonObject("jobDocument");

            String version = jobDoc.get("version").getAsString();
            String packageUrl = jobDoc.get("packageUrl").getAsString();
            String checksum = jobDoc.has("checksum") ? jobDoc.get("checksum").getAsString() : "";
            String checksumType = jobDoc.has("checksumType") ? jobDoc.get("checksumType").getAsString() : "sha256";

            OtaPackage pkg = new OtaPackage(jobId, version, packageUrl, checksum, checksumType);
            System.out.println("[OtaClient] New OTA job received: " + pkg);

            listener.onNewPackage(pkg);

        } catch (Exception e) {
            System.err.println("[OtaClient] Error handling job notification: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 执行 OTA 升级：下载固件 → 校验 → 上报状态。
     * 通常在 listener.onNewPackage() 中调用。
     * 使用 AtomicBoolean 防止并发升级。
     */
    public void startUpgrade(OtaPackage pkg) {
        if (!upgrading.compareAndSet(false, true)) {
            System.out.println("[OtaClient] Upgrade already in progress, skipping: " + pkg);
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                // 上报 IN_PROGRESS
                reportJobStatus(pkg.getJobId(), "IN_PROGRESS", "downloading firmware");

                // 下载固件
                FirmwareDownloader downloader = new FirmwareDownloader(listener);
                Path outputPath = downloadDir.resolve("firmware-" + pkg.getVersion() + ".bin");
                boolean success = downloader.download(pkg, outputPath);

                if (success) {
                    listener.onProgress(-1, "firmware downloaded and verified, applying...");

                    // 上报 SUCCEEDED + 更新版本
                    reportJobStatus(pkg.getJobId(), "SUCCEEDED", "upgrade complete");
                    reportVersion(pkg.getVersion());
                    listener.onProgress(-1, "succeeded");
                } else {
                    reportJobStatus(pkg.getJobId(), "FAILED", "download or checksum failed");
                    listener.onProgress(-1, "failed: download or checksum failed");
                }

            } catch (Exception e) {
                System.err.println("[OtaClient] Upgrade error: " + e.getMessage());
                try {
                    reportJobStatus(pkg.getJobId(), "FAILED", e.getMessage());
                } catch (Exception reportErr) {
                    System.err.println("[OtaClient] Failed to report error status: " + reportErr.getMessage());
                }
                listener.onProgress(-1, "failed: " + e.getMessage());
            } finally {
                upgrading.set(false);
            }
        });
    }

    /**
     * 更新 Device Shadow reported.firmwareVersion。
     */
    public void reportVersion(String version) throws Exception {
        String topic = "$aws/things/" + thingName + "/shadow/update";
        String payload = "{\"state\":{\"reported\":{\"firmwareVersion\":\"" + version + "\"}}}";

        connection.publish(new MqttMessage(topic, payload.getBytes(StandardCharsets.UTF_8),
                QualityOfService.AT_LEAST_ONCE, false)).get();

        System.out.println("[OtaClient] Shadow updated: firmwareVersion=" + version);
    }

    /**
     * 更新 Job 执行状态。
     *
     * @param jobId  Job ID
     * @param status IN_PROGRESS / SUCCEEDED / FAILED
     * @param detail 状态详情
     */
    public void reportJobStatus(String jobId, String status, String detail) throws Exception {
        String topic = "$aws/things/" + thingName + "/jobs/" + jobId + "/update";

        JsonObject payload = new JsonObject();
        payload.addProperty("status", status);

        JsonObject statusDetails = new JsonObject();
        statusDetails.addProperty("detail", detail);
        payload.add("statusDetails", statusDetails);

        connection.publish(new MqttMessage(topic,
                gson.toJson(payload).getBytes(StandardCharsets.UTF_8),
                QualityOfService.AT_LEAST_ONCE, false)).get();

        System.out.println("[OtaClient] Job " + jobId + " status -> " + status);
    }

    /**
     * 断开 MQTT 连接，释放 CRT 资源。
     */
    public void disconnect() throws Exception {
        if (connection != null) {
            connection.disconnect().get();
            connection.close();
            connection = null;
            System.out.println("[OtaClient] Disconnected.");
        }
        if (builder != null) {
            builder.close();
            builder = null;
        }
    }
}
