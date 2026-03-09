# 华为 IoT Device SDK — OTAListener 实现详解

## 概述

华为云 IoT Device SDK 提供了 OTA（Over-The-Air）空中升级能力，允许设备通过云端下发的升级包进行固件（FOTA）和软件（SOTA）升级。`OTAListener` 是 OTA 模块的核心接口，开发者通过实现该接口来定制升级流程。

**SDK 仓库**: [huaweicloud-iot-device-sdk-java](https://github.com/huaweicloud/huaweicloud-iot-device-sdk-java)

---

## 1. OTAListener 的存在形式

OTAListener **不是**一个独立运行的组件或服务，而是一个**回调接口（Callback Interface）**，通过 Observer 模式注入到 SDK 内部的事件分发链路中。

### 架构定位

```
┌─────────────────────────────────────────────────────────┐
│ IoTDevice                                               │
│                                                         │
│   ┌──────────────┐    MQTT     ┌────────────────────┐   │
│   │ DeviceClient │ ─────────► │ AbstractService     │   │
│   │ (MQTT 客户端) │   onEvent  │   ├── OTAService    │   │
│   └──────────────┘            │   ├── TimeSyncService│   │
│                               │   └── ...            │   │
│                               └────────┬───────────┘   │
│                                        │                │
│                          ExecutorService│异步分发        │
│                                        ▼                │
│                               ┌────────────────┐        │
│                               │  OTAListener   │ ← 你的实现
│                               │  (回调接口)     │        │
│                               └────────────────┘        │
└─────────────────────────────────────────────────────────┘
```

### 关键设计要点

| 维度 | 说明 |
|------|------|
| **模式** | Observer / Callback — 不是独立进程或线程 |
| **注册方式** | `otaService.setOtaListener(myListener)` 一次性注入 |
| **生命周期** | 跟随 `IoTDevice` 实例，设备断开则不再触发 |
| **线程模型** | `onQueryVersion` 同步执行；`onNewPackage` / `onNewPackageV2` 通过 `Executors.newSingleThreadExecutor()` 异步串行执行 |
| **与 MQTT 的关系** | MQTT 消息到达 → DeviceClient 解析 → 路由到 `OTAService.onEvent()` → 分发到 listener |

> 简言之：你只需实现接口 + 注册，消息解析和路由完全由 SDK 内部的 `OTAService` 处理。

### OTAService 与 DeviceClient 的关系

#### 整体对象关系

```
IoTDevice (用户入口，门面类)
  │
  ├── extends AbstractDevice
  │     │
  │     ├── DeviceClient client          ← MQTT 通信层
  │     │     │
  │     │     ├── Connection (Paho MQTT)  ← 底层连接
  │     │     ├── functionMap              ← topic → handler 路由表
  │     │     └── reportEvent()            ← 上行消息发布
  │     │
  │     ├── Map<String, IService> services ← serviceId → service 映射
  │     │     │
  │     │     ├── "$ota"        → OTAService
  │     │     ├── "$time_sync"  → TimeSyncService
  │     │     ├── "$log"        → LogService
  │     │     └── "user_svc"    → 用户自定义 Service
  │     │
  │     └── OTAService otaService          ← 快捷引用（getOtaService()）
  │
  └── init()  → client.connect()           ← 启动 MQTT 连接
```

#### 初始化流程（AbstractDevice 构造函数）

```java
// 1. 创建 DeviceClient，双向绑定
this.client = new DeviceClient(clientConf, this);  // this = AbstractDevice

// 2. 初始化系统服务
private void initSysServices() {
    this.otaService = new OTAService();
    this.addService("$ota", otaService);   // 注册到 services map
    // ... 其他系统服务
}

// 3. addService 内部：将 service 绑定回设备
public void addService(String serviceId, AbstractService deviceService) {
    deviceService.setIotDevice(this);       // service → device 反向引用
    deviceService.setServiceId(serviceId);  // service 知道自己的 ID
    services.putIfAbsent(serviceId, deviceService);
}
```

> **双向绑定**：`DeviceClient` 持有 `AbstractDevice` 引用；`OTAService` 通过 `getIotDevice()` 拿到 `AbstractDevice`，再通过 `getClient()` 拿到 `DeviceClient`。

#### 下行事件分发链路（平台 → 设备）

```
IoT 平台
  │
  │ MQTT publish: $oc/devices/{deviceId}/sys/events/down
  ▼
DeviceClient.onMessageReceived(RawMessage)
  │
  │ executorService.schedule(异步)
  │ topic 匹配 → functionMap → EventDownHandler
  ▼
AbstractDevice.onEvent(DeviceEvents)
  │
  │ for (DeviceEvent event : deviceEvents.getServices()) {
  │     IService svc = getService(event.getServiceId());  // "$ota"
  │     svc.onEvent(event);                                // 分发
  │ }
  ▼
OTAService.onEvent(DeviceEvent)
  │
  │ 解析 eventType → 转换 JSON → 调用 listener
  ▼
OTAListener.onNewPackage(OTAPackage)  ← 你的代码
```

#### 上行事件发布链路（设备 → 平台）

```
OTAListener 实现中调用:
  otaService.reportOtaStatus(result, progress, version, desc)
    │
    ▼
  OTAService.reportOtaStatus()
    │ 构建 DeviceEvent (serviceId="$ota", eventType="upgrade_progress_report")
    │
    │ getIotDevice().getClient().reportEvent(event, listener)
    ▼
  DeviceClient.reportEvent()
    │ 封装 DeviceEvents
    │ topic = "$oc/devices/{deviceId}/sys/events/up"
    │ connection.publishMessage(rawMessage, listener)
    ▼
  MQTT publish → IoT 平台
```

#### 关键设计总结

| 组件 | 职责 | 关系 |
|------|------|------|
| **IoTDevice** | 用户入口（门面模式） | 继承 AbstractDevice |
| **AbstractDevice** | 持有 client + services，负责事件路由 | 创建并持有 DeviceClient 和所有 Service |
| **DeviceClient** | MQTT 通信层，收发消息 | 持有 AbstractDevice 反向引用 |
| **OTAService** | OTA 业务逻辑，事件解析 + 状态上报 | 通过 `getIotDevice().getClient()` 访问通信能力 |
| **OTAListener** | 用户回调，处理具体升级逻辑 | 被 OTAService 持有并调用 |

> **一句话概括**：`DeviceClient` 是通信管道（收发 MQTT），`OTAService` 是业务路由器（解析 OTA 事件 + 提供上报 API），`OTAListener` 是你挂上去的钩子。三者通过 `AbstractDevice` 串联，形成 **MQTT ↔ Service ↔ Listener** 的三层架构。

---

## 2. OTAListener 接口定义

**路径**: `iot-device-sdk-java/src/main/java/com/huaweicloud/sdk/iot/device/ota/OTAListener.java`

```java
package com.huaweicloud.sdk.iot.device.ota;

public interface OTAListener {
    /**
     * 接收平台查询版本通知
     */
    void onQueryVersion(OTAQueryInfo queryInfo);

    /**
     * 接收 V1 新版本升级包通知
     */
    void onNewPackage(OTAPackage pkg);

    /**
     * 接收 V2 新版本升级包通知
     */
    void onNewPackageV2(OTAPackageV2 pkg);
}
```

### 回调方法说明

| 方法 | 触发时机 | 参数 |
|------|----------|------|
| `onQueryVersion` | 平台主动查询设备当前版本 | `OTAQueryInfo` — 查询上下文 |
| `onNewPackage` | 平台下发 V1 格式升级包 | `OTAPackage` — 包含下载 URL + access_token |
| `onNewPackageV2` | 平台下发 V2 格式升级包 | `OTAPackageV2` — 包含下载 URL + 签名 |

---

## 3. 数据模型

### OTAPackageV2（基础类）

**路径**: `iot-device-sdk-java/src/main/java/com/huaweicloud/sdk/iot/device/ota/OTAPackageV2.java`

继承自 `OTABase`，包含以下字段：

| 字段 | JSON 映射 | 类型 | 说明 |
|------|-----------|------|------|
| `url` | `url` | String | 升级包下载地址（OBS） |
| `version` | `version` | String | 目标版本号 |
| `fileSize` | `file_size` | long | 文件大小（字节） |
| `fileName` | `file_name` | String | 文件名 |
| `expires` | `expires` | int | 下载链接过期时间（秒） |
| `sign` | `sign` | String | SHA-256 签名，用于完整性校验 |
| `customInfo` | `custom_info` | String | 自定义扩展信息 |

### OTAPackage（V1，继承 OTAPackageV2）

**路径**: `iot-device-sdk-java/src/main/java/com/huaweicloud/sdk/iot/device/ota/OTAPackage.java`

在 V2 基础上增加：

| 字段 | JSON 映射 | 类型 | 说明 |
|------|-----------|------|------|
| `token` | `access_token` | String | 下载认证 token |

---

## 4. OTAService — 服务层核心（完整源码分析）

**路径**: `iot-device-sdk-java/src/main/java/com/huaweicloud/sdk/iot/device/ota/OTAService.java`

`OTAService` 继承 `AbstractService`，是 OTA 功能的服务层封装。`AbstractService` 通过注解反射机制（`@Property`、`@DeviceCommand`）自动注册到 `IoTDevice` 的事件总线上。

### 4.1 完整源码

```java
@Slf4j
public class OTAService extends AbstractService {

    // ========== 状态码常量 ==========
    public static final int OTA_CODE_SUCCESS = 0;           // 成功
    public static final int OTA_CODE_BUSY = 1;              // 设备使用中
    public static final int OTA_CODE_SIGNAL_BAD = 2;        // 信号质量差
    public static final int OTA_CODE_NO_NEED = 3;           // 已经是最新版本
    public static final int OTA_CODE_LOW_POWER = 4;         // 电量不足
    public static final int OTA_CODE_LOW_SPACE = 5;         // 剩余空间不足
    public static final int OTA_CODE_DOWNLOAD_TIMEOUT = 6;  // 下载超时
    public static final int OTA_CODE_CHECK_FAIL = 7;        // 升级包校验失败
    public static final int OTA_CODE_UNKNOWN_TYPE = 8;      // 升级包类型不支持
    public static final int OTA_CODE_LOW_MEMORY = 9;        // 内存不足
    public static final int OTA_CODE_INSTALL_FAIL = 10;     // 安装升级包失败
    public static final int OTA_CODE_INNER_ERROR = 255;     // 内部异常

    private OTAListener otaListener;
    private ExecutorService executorService;

    /**
     * 设置OTA监听器 — 懒初始化单线程 executor
     */
    public void setOtaListener(OTAListener otaListener) {
        if (executorService == null) {
            executorService = Executors.newSingleThreadExecutor();
        }
        this.otaListener = otaListener;
    }

    /**
     * 上报升级状态
     * @param result      升级结果（使用上面的 OTA_CODE_* 常量）
     * @param progress    升级进度 0-100
     * @param version     当前版本
     * @param description 失败原因（可选）
     */
    public void reportOtaStatus(int result, int progress,
                                String version, String description) {
        Map<String, Object> node = new HashMap<>();
        node.put("result_code", result);
        node.put("progress", progress);
        if (description != null) {
            node.put("description", description);
        }
        node.put("version", version);

        DeviceEvent deviceEvent = new DeviceEvent();
        deviceEvent.setEventType("upgrade_progress_report");
        deviceEvent.setParas(node);
        deviceEvent.setServiceId("$ota");
        deviceEvent.setEventTime(IotUtil.getTimeStamp());

        getIotDevice().getClient().reportEvent(deviceEvent,
            new DefaultActionListenerImpl("reportOtaStatus"));
    }

    /**
     * 上报固件版本 — 注意：fw_version 和 sw_version 使用同一个 version 参数
     */
    public void reportVersion(String version) {
        Map<String, Object> node = new HashMap<>();
        node.put("fw_version", version);
        node.put("sw_version", version);

        DeviceEvent deviceEvent = new DeviceEvent();
        deviceEvent.setEventType("version_report");
        deviceEvent.setParas(node);
        deviceEvent.setServiceId("$ota");
        deviceEvent.setEventTime(IotUtil.getTimeStamp());

        getIotDevice().getClient().reportEvent(deviceEvent,
            new DefaultActionListenerImpl("reportVersion"));
    }

    /**
     * 核心事件分发 — 覆写 AbstractService.onEvent()
     */
    @Override
    public void onEvent(DeviceEvent deviceEvent) {
        if (otaListener == null) {
            log.info("otaListener is null");
            return;
        }

        // 1. 版本查询 — 同步执行（不走 executor）
        if (deviceEvent.getEventType().equalsIgnoreCase("version_query")) {
            OTAQueryInfo queryInfo = JsonUtil.convertMap2Object(
                deviceEvent.getParas(), OTAQueryInfo.class);
            otaListener.onQueryVersion(queryInfo);
            return;
        }

        Future<String> success = null;

        // 2. V1 升级（固件 or 软件）— 异步执行
        if (deviceEvent.getEventType().equalsIgnoreCase("firmware_upgrade")
            || deviceEvent.getEventType().equalsIgnoreCase("software_upgrade")) {

            OTAPackage pkg = JsonUtil.convertMap2Object(
                deviceEvent.getParas(), OTAPackage.class);
            success = executorService.submit(
                () -> otaListener.onNewPackage(pkg), "success");

        // 3. V2 升级（固件 or 软件）— 异步执行
        } else if (deviceEvent.getEventType().equalsIgnoreCase("firmware_upgrade_v2")
            || deviceEvent.getEventType().equalsIgnoreCase("software_upgrade_v2")) {

            OTAPackageV2 pkgV2 = JsonUtil.convertMap2Object(
                deviceEvent.getParas(), OTAPackageV2.class);
            success = executorService.submit(
                () -> otaListener.onNewPackageV2(pkgV2), "success");
        }

        // 4. 阻塞等待回调执行完成
        try {
            if (success != null) {
                success.get();  // 阻塞直到升级流程结束
            }
        } catch (Exception e) {
            log.error("get submit result failed " + e.getMessage());
        }
    }
}
```

### 4.2 源码分析要点

#### 线程模型

```
MQTT 线程 (Paho)
  └→ onEvent() 被调用
       ├── version_query → 同步调用 onQueryVersion()，直接返回
       └── firmware/software_upgrade
            └→ executorService.submit(回调)
                 └→ success.get() 阻塞等待
                      → 升级回调在 executor 线程中执行
                      → MQTT 线程被阻塞直到升级完成
```

> **注意**：`success.get()` 会阻塞调用线程（MQTT 线程），直到升级流程完全结束。这意味着在升级期间，该 MQTT 连接的其他事件处理会被暂停。

#### 事件类型路由

| eventType | 处理方式 | 回调方法 |
|-----------|----------|----------|
| `version_query` | 同步 | `onQueryVersion(OTAQueryInfo)` |
| `firmware_upgrade` | 异步 + 阻塞等待 | `onNewPackage(OTAPackage)` |
| `software_upgrade` | 异步 + 阻塞等待 | `onNewPackage(OTAPackage)` |
| `firmware_upgrade_v2` | 异步 + 阻塞等待 | `onNewPackageV2(OTAPackageV2)` |
| `software_upgrade_v2` | 异步 + 阻塞等待 | `onNewPackageV2(OTAPackageV2)` |

#### 上报机制

- 所有上报都通过 `DeviceEvent` 封装，`serviceId` 固定为 `"$ota"`
- 使用 `DefaultActionListenerImpl` 处理 MQTT 发布的 ACK
- `reportOtaStatus` 的 `eventType` 为 `"upgrade_progress_report"`
- `reportVersion` 的 `eventType` 为 `"version_report"`

#### AbstractService 注册机制

`OTAService` 继承的 `AbstractService` 通过注解驱动的反射机制完成服务注册：
- `@Property` 注解的字段自动注册为可读/可写属性
- `@DeviceCommand` 注解的方法自动注册为平台可调用命令
- `setIotDevice()` 绑定设备实例，获取 MQTT client 能力
- 子类只需覆写 `onEvent()` 即可接收平台下发的事件

---

## 5. 示例实现

### 5.1 OTASample（V1）

**路径**: `iot-device-demo/src/main/java/com/huaweicloud/sdk/iot/device/demo/device/OTASample.java`

```java
public class OTASample implements OTAListener {

    @Override
    public void onQueryVersion(OTAQueryInfo queryInfo) {
        // 上报当前固件和软件版本
        otaService.reportVersion(fwVersion, swVersion);
    }

    @Override
    public void onNewPackage(OTAPackage pkg) {
        // 完整升级流程
        // 1. preCheck()        — 检查版本、存储、电量、信号
        // 2. downloadPackage() — OkHttpClient + TLS 1.2 下载
        // 3. checkPackage()    — SHA-256 完整性校验
        // 4. installPackage()  — 执行安装（用户自行实现）
        // 5. reportOtaStatus() — 上报升级结果
    }

    @Override
    public void onNewPackageV2(OTAPackageV2 pkg) {
        // V1 Sample 不处理 V2 包
    }
}
```

### 5.2 OTAV2Sample（V2）

**路径**: `iot-device-demo/src/main/java/com/huaweicloud/sdk/iot/device/demo/device/OTAV2Sample.java`

```java
public class OTAV2Sample implements OTAListener {

    @Override
    public void onQueryVersion(OTAQueryInfo queryInfo) {
        otaService.reportVersion(fwVersion, swVersion);
    }

    @Override
    public void onNewPackage(OTAPackage pkg) {
        // V2 Sample 不处理 V1 包
    }

    @Override
    public void onNewPackageV2(OTAPackageV2 pkgV2) {
        // 1. preCheck()        — 版本兼容性 + 存储 + 电量 + 信号
        // 2. downloadPackage() — OkHttpClient + SSL, 实时进度跟踪
        // 3. checkPackage()    — 下载时流式计算 SHA-256 并校验
        // 4. installPackage()  — 占位方法，需用户实现固件刷写
        // 5. reportOtaStatus() — 上报成功 / 失败 / 超时
    }
}
```

---

## 6. 典型升级流程

```
┌─────────┐          ┌──────────┐          ┌──────────┐
│ IoT 平台 │          │ OTAService│          │ 设备实现  │
└────┬────┘          └────┬─────┘          └────┬─────┘
     │  version_query      │                     │
     │ ──────────────────► │  onQueryVersion()   │
     │                     │ ──────────────────► │
     │                     │  reportVersion()    │
     │ ◄─────────────────  │ ◄────────────────── │
     │                     │                     │
     │  firmware_upgrade   │                     │
     │ ──────────────────► │  onNewPackage()     │
     │                     │ ──────────────────► │
     │                     │                     │ 1. preCheck()
     │                     │                     │ 2. downloadPackage()
     │                     │  reportOtaStatus()  │ 3. checkPackage()
     │ ◄─────────────────  │ ◄──── (progress) ── │ 4. installPackage()
     │                     │  reportOtaStatus()  │
     │ ◄─────────────────  │ ◄──── (result) ──── │
     │                     │                     │
```

---

## 7. 集成要点

### 初始化

```java
// 创建设备实例
IoTDevice device = new IoTDevice(serverUri, deviceId, secret);
device.init();

// 获取 OTAService 并注册监听器
OTAService otaService = device.getOtaService();
otaService.setOtaListener(new MyOTAListener());
```

### 开发者需要实现的部分

1. **preCheck()** — 根据业务判断是否满足升级条件
2. **downloadPackage()** — SDK 示例已提供 HTTP 下载参考，可直接复用
3. **checkPackage()** — SHA-256 校验，示例已提供
4. **installPackage()** — **必须自行实现**，执行实际的固件/软件刷写

### 注意事项

- 下载使用 TLS 1.2，需配置信任 OBS 证书
- V1 使用 `access_token` 认证下载，V2 使用 URL 签名（无需 token）
- 升级过程中应持续上报 `progress`（0-100），让平台感知进度
- 升级超时、失败等异常场景需上报对应错误码
- 支持网关模式（v1.2.1+），网关可代理子设备升级

---

## 参考资料

- [华为云 IoT 设备接入 SDK（Java）](https://github.com/huaweicloud/huaweicloud-iot-device-sdk-java)
- [华为云 IoT 设备接入 SDK（Android）](https://github.com/huaweicloud/huaweicloud-iot-device-sdk-Android)
- [IoT Device SDK 参考文档](https://support.huaweicloud.com/intl/en-us/sdkreference-iothub/iot_02_0092.html)
- [设备侧开发指南](https://support.huaweicloud.com/intl/en-us/devg-iothub/iot_02_0170.html)
