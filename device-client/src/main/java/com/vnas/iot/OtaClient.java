package com.vnas.iot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

import java.nio.charset.StandardCharsets;

/**
 * AWS IoT MQTT 通信层 - 简化版。
 *
 * 职责：
 * - 订阅 Jobs notify-next 接收 OTA 任务
 * - 解析 MQTT 消息并委托给 OtaService 处理
 * - 提供 disconnect() 方法
 *
 * 注意：connection 由外部创建和管理（OtaDemo）
 */
public class OtaClient {

    private final String thingName;
    private final OtaService otaService;
    private final MqttClientConnection connection;
    private final Gson gson = new Gson();

    /**
     * @param thingName  AWS IoT Thing 名称
     * @param otaService OTA 业务服务层
     * @param connection MQTT 连接实例（已创建但未连接）
     */
    public OtaClient(String thingName, OtaService otaService, MqttClientConnection connection) {
        this.thingName = thingName;
        this.otaService = otaService;
        this.connection = connection;
    }

    /**
     * 连接 MQTT 并订阅 Job 通知。
     */
    public void connect() throws Exception {
        connection.connect().get();
        System.out.println("[OtaClient] MQTT connected: " + thingName);

        // 启动时上报当前版本到 Shadow
        if (otaService.getListener() != null) {
            String currentVersion = otaService.getListener().onQueryVersion();
            otaService.reportVersion(currentVersion);
        }

        // 订阅 Jobs notify-next
        subscribeJobNotifications();
    }

    /**
     * 订阅 Jobs 相关 topic，接收 Job 通知。
     */
    private void subscribeJobNotifications() throws Exception {
        // 订阅 notify-next（接收新 job 创建的被动通知）
        String notifyTopic = "$aws/things/" + thingName + "/jobs/notify-next";
        connection.subscribe(notifyTopic, QualityOfService.AT_LEAST_ONCE, (message) -> {
            handleJobNotification(message);
        }).get();
        System.out.println("[OtaClient] Subscribed to: " + notifyTopic);

        // 订阅 $next/get/accepted（接收主动查询的响应）
        String getAcceptedTopic = "$aws/things/" + thingName + "/jobs/$next/get/accepted";
        connection.subscribe(getAcceptedTopic, QualityOfService.AT_LEAST_ONCE, (message) -> {
            handleJobNotification(message);
        }).get();
        System.out.println("[OtaClient] Subscribed to: " + getAcceptedTopic);

        // 主动请求获取 pending jobs（如果设备连接时已有 job 在队列中）
        requestPendingJobs();
    }

    /**
     * 主动请求获取 pending jobs。
     * 发布到 $aws/things/{thingName}/jobs/$next/get，触发 $next/get/accepted 响应。
     */
    private void requestPendingJobs() throws Exception {
        String topic = "$aws/things/" + thingName + "/jobs/$next/get";
        String payload = "{}";

        connection.publish(new MqttMessage(topic, payload.getBytes(StandardCharsets.UTF_8),
                QualityOfService.AT_LEAST_ONCE, false)).get();

        System.out.println("[OtaClient] Requested pending jobs");
    }

    /**
     * 处理 Job 通知消息，解析后委托给 OtaService。
     */
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

            // 委托给 OtaService 处理
            otaService.onJobReceived(execution);

        } catch (Exception e) {
            System.err.println("[OtaClient] Error handling job notification: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 断开 MQTT 连接。
     */
    public void disconnect() throws Exception {
        if (connection != null) {
            connection.disconnect().get();
            System.out.println("[OtaClient] Disconnected.");
        }
    }
}
