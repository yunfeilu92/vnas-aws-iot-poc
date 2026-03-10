package com.vnas.iot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private volatile String currentJobId = null;

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

        // Job 进入终态时清除 currentJobId（允许接收下一个 Job）
        otaService.setOnJobTerminalCallback(() -> currentJobId = null);

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
            System.out.println("[OtaClient] <<< NOTIFY-NEXT (passive push)");
            handleJobNotification(message, "notify-next");
        }).get();
        System.out.println("[OtaClient] Subscribed to: " + notifyTopic);

        // 订阅 $next/get/accepted（接收主动查询的响应）
        String getAcceptedTopic = "$aws/things/" + thingName + "/jobs/$next/get/accepted";
        connection.subscribe(getAcceptedTopic, QualityOfService.AT_LEAST_ONCE, (message) -> {
            System.out.println("[OtaClient] <<< GET-ACCEPTED (active pull)");
            handleJobNotification(message, "$next/get/accepted");
        }).get();
        System.out.println("[OtaClient] Subscribed to: " + getAcceptedTopic);

        // 主动请求获取 pending jobs（如果设备连接时已有 job 在队列中）
        requestPendingJobs();

        // 启动定期轮询（每 60 秒查询一次，作为 notify-next 推送失败的兜底）
        scheduler.scheduleAtFixedRate(() -> {
            try {
                System.out.println("[OtaClient] Polling for pending jobs (scheduled check)...");
                requestPendingJobs();
            } catch (Exception e) {
                System.err.println("[OtaClient] Failed to poll jobs: " + e.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS);
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
     * @param source 消息来源（"notify-next" 或 "$next/get/accepted"）
     */
    private void handleJobNotification(MqttMessage message, String source) {
        try {
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            JsonObject root = gson.fromJson(payload, JsonObject.class);

            // notify-next 的 payload 结构：{ "execution": { "jobId": "...", "status": "...", "jobDocument": {...} } }
            if (!root.has("execution") || root.get("execution").isJsonNull()) {
                System.out.println("[OtaClient] No pending job. (via " + source + ")");
                return;
            }

            JsonObject execution = root.getAsJsonObject("execution");
            String jobId = execution.has("jobId") ? execution.get("jobId").getAsString() : "unknown";

            // 检查 execution status，跳过已处于终态的 Job
            if (execution.has("status")) {
                String status = execution.get("status").getAsString();
                if ("SUCCEEDED".equals(status) || "FAILED".equals(status)
                        || "REJECTED".equals(status) || "REMOVED".equals(status)
                        || "CANCELED".equals(status)) {
                    System.out.println("[OtaClient] Skipping job in terminal state: " + status + " (via " + source + ")");
                    return;
                }
            }

            // 跳过当前正在处理的 Job（防止轮询/推送重复派发同一 Job）
            // 注意：不再跳过所有 IN_PROGRESS Job，这样重启后能恢复中断的升级
            if (jobId.equals(currentJobId)) {
                System.out.println("[OtaClient] Skipping job already being processed (jobId: " + jobId + ", via " + source + ")");
                return;
            }

            // 记录当前 Job ID，委托给 OtaService 处理
            currentJobId = jobId;
            System.out.println("[OtaClient] >>> Dispatching job " + jobId + " to OtaService (via " + source + ")");
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
        scheduler.shutdown();
        if (connection != null) {
            connection.disconnect().get();
            System.out.println("[OtaClient] Disconnected.");
        }
    }
}
