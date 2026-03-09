package com.vnas.iot;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * OTA 客户端使用示例。
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
 */
public class OtaDemo {

    private static final String CURRENT_VERSION = "1.0.2";
    private static volatile OtaClient client;

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

        Path downloadDir = Paths.get("downloads");

        // 实现 OtaListener 回调
        OtaListener listener = new OtaListener() {
            @Override
            public String onQueryVersion() {
                System.out.println("[Demo] Current firmware version: " + CURRENT_VERSION);
                return CURRENT_VERSION;
            }

            @Override
            public void onNewPackage(OtaPackage pkg) {
                System.out.println("[Demo] New firmware available: " + pkg);
                System.out.println("[Demo] Starting upgrade to version " + pkg.getVersion() + "...");
                client.startUpgrade(pkg);
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

        client = new OtaClient(thingName, listener, downloadDir);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                client.disconnect();
            } catch (Exception e) {
                System.err.println("Error during disconnect: " + e.getMessage());
            }
        }));

        System.out.println("[Demo] Connecting to " + endpoint + " as " + thingName + "...");
        client.connect(endpoint, certPath, keyPath, caPath);

        System.out.println("[Demo] Waiting for OTA job... (Ctrl+C to exit)");
        Thread.currentThread().join();
    }
}
