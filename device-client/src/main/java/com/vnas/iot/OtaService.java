package com.vnas.iot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

import java.nio.charset.StandardCharsets;

/**
 * OTA 业务服务层 - 对标华为 IoT SDK OTAService。
 *
 * 职责：
 * - 封装 MQTT 上报 API（reportJobStatus, reportVersion）
 * - 解析 AWS IoT Job Document 并路由到 OtaListener
 * - 连接 MQTT 通信层（OtaClient）和用户回调（OtaListener）
 *
 * 设计模式：
 * - Facade 模式：隐藏 MQTT publish 细节，提供简洁的上报 API
 * - Observer 模式：持有 listener 引用，事件到达时回调通知
 */
public class OtaService {

    private final MqttClientConnection connection;
    private final String thingName;
    private final Gson gson = new Gson();

    private OtaListener listener;
    private Runnable onJobTerminalCallback;

    /**
     * @param connection MQTT 连接实例（由 OtaClient 创建并传入）
     * @param thingName  AWS IoT Thing 名称
     */
    public OtaService(MqttClientConnection connection, String thingName) {
        this.connection = connection;
        this.thingName = thingName;
    }

    /**
     * 设置 OTA 监听器，并将自己反向注入到 listener。
     *
     * @param listener OTA 回调实现
     */
    public void setOtaListener(OtaListener listener) {
        this.listener = listener;
        if (listener != null) {
            listener.setOtaService(this);
        }
    }

    /**
     * 获取当前注册的 listener（供 OtaClient 调用 onQueryVersion）。
     */
    public OtaListener getListener() {
        return listener;
    }

    /**
     * 设置 Job 进入终态时的回调（供 OtaClient 注册，用于自动请求下一个 Job）。
     */
    public void setOnJobTerminalCallback(Runnable callback) {
        this.onJobTerminalCallback = callback;
    }

    /**
     * 上报 AWS IoT Job 执行状态。
     *
     * 发布到：$aws/things/{thingName}/jobs/{jobId}/update
     *
     * Payload 格式：
     * {
     *   "status": "IN_PROGRESS" | "SUCCEEDED" | "FAILED" | "REJECTED",
     *   "statusDetails": {
     *     "detail": "..."
     *   }
     * }
     *
     * @param jobId  Job ID
     * @param status IN_PROGRESS / SUCCEEDED / FAILED / REJECTED
     * @param detail 状态详情（如 "downloading firmware"）
     * @throws Exception MQTT publish 失败
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

        System.out.println("[OtaService] Job " + jobId + " status -> " + status + ": " + detail);

        // Job 进入终态时，通知 OtaClient 请求下一个 pending Job
        if (isTerminalStatus(status) && onJobTerminalCallback != null) {
            System.out.println("[OtaService] Job reached terminal state.");
            onJobTerminalCallback.run();
        }
    }

    private boolean isTerminalStatus(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "REJECTED".equals(status);
    }

    /**
     * 上报设备固件版本到 Device Shadow。
     *
     * 发布到：$aws/things/{thingName}/shadow/update
     *
     * Payload 格式：
     * {
     *   "state": {
     *     "reported": {
     *       "firmwareVersion": "1.0.3"
     *     }
     *   }
     * }
     *
     * @param version 固件版本号
     * @throws Exception MQTT publish 失败
     */
    public void reportVersion(String version) throws Exception {
        String topic = "$aws/things/" + thingName + "/shadow/update";
        String payload = "{\"state\":{\"reported\":{\"firmwareVersion\":\"" + version + "\"}}}";

        connection.publish(new MqttMessage(topic, payload.getBytes(StandardCharsets.UTF_8),
                QualityOfService.AT_LEAST_ONCE, false)).get();

        System.out.println("[OtaService] Shadow updated: firmwareVersion=" + version);
    }

    /**
     * 内部方法：由 OtaClient 调用，解析 Job execution 并路由到 listener。
     *
     * @param execution AWS IoT Jobs execution 对象（来自 notify-next 或 $next/get/accepted）
     */
    void onJobReceived(JsonObject execution) {
        if (listener == null) {
            System.out.println("[OtaService] No listener registered, ignoring job");
            return;
        }

        try {
            String jobId = execution.get("jobId").getAsString();
            JsonObject jobDoc = execution.getAsJsonObject("jobDocument");

            // 解析 Job Document
            String version = jobDoc.get("version").getAsString();
            String packageUrl = jobDoc.get("packageUrl").getAsString();
            String checksum = jobDoc.has("checksum") ? jobDoc.get("checksum").getAsString() : "";
            String checksumType = jobDoc.has("checksumType") ? jobDoc.get("checksumType").getAsString() : "sha256";

            OtaPackage pkg = new OtaPackage(jobId, version, packageUrl, checksum, checksumType);
            System.out.println("[OtaService] New OTA job received: " + pkg);

            // 路由到 listener（用户在这里完全控制升级流程）
            listener.onNewPackage(pkg);

        } catch (Exception e) {
            System.err.println("[OtaService] Error processing job: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
