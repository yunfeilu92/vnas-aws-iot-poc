"""IoT Vehicle Telemetry Data Pipeline - Architecture Diagram"""

from diagrams import Diagram, Cluster, Edge
from diagrams.aws.iot import IotCore, IotCar, IotShadow, IotRule, IotMqtt
from diagrams.aws.analytics import ManagedStreamingForKafka, KinesisDataAnalytics, Athena
from diagrams.aws.storage import S3

# Colors
C_MAIN = "#000000"
C_SUB = "#404040"
C_HIGHLIGHT = "#CC0000"

graph_attr = {
    "rankdir": "LR",
    "ranksep": "1.0",
    "nodesep": "0.5",
    "splines": "spline",
    "concentrate": "true",
    "pad": "0.4",
    "fontsize": "16",
    "labelloc": "t",
}

with Diagram(
    "IoT Vehicle Telemetry Data Pipeline",
    filename="/Users/yunfeilu/Documents/Github/vnas-aws-iot-poc/docs/iot-pipeline-arch",
    show=False,
    graph_attr=graph_attr,
    outformat="png",
    direction="LR",
):
    # Vehicle fleet
    with Cluster("Vehicle Fleet\n(5,000 vehicles)"):
        vehicles = IotCar("Vehicles\n128KB/msg, 1msg/s")

    # IoT Core
    with Cluster("AWS IoT Core"):
        mqtt = IotMqtt("MQTT Broker")
        rules = IotRule("Rules Engine")
        shadow = IotShadow("Device Shadow\n(Named Shadow)")

    # Streaming layer
    with Cluster("Streaming & Processing"):
        msk = ManagedStreamingForKafka("Amazon MSK\n9x kafka.m5.2xlarge")
        flink = KinesisDataAnalytics("Managed Flink\n32 KPUs")

    # Storage & Query
    with Cluster("Storage & Analytics"):
        s3 = S3("S3\n(Parquet)")
        athena = Athena("Amazon Athena")

    # Main data flow: telemetry
    vehicles >> Edge(label="MQTT 128KB", color=C_MAIN, style="bold") >> mqtt
    mqtt >> Edge(color=C_MAIN, style="bold") >> rules
    rules >> Edge(label="Action", color=C_MAIN, style="bold") >> msk
    msk >> Edge(label="Stream", color=C_MAIN, style="bold") >> flink
    flink >> Edge(label="Parquet", color=C_MAIN, style="bold") >> s3
    s3 >> Edge(label="Query", color=C_SUB, style="dashed") >> athena

    # Branch: device shadow for state sync
    vehicles >> Edge(label="State <8KB", color=C_HIGHLIGHT, style="dashed") >> shadow
