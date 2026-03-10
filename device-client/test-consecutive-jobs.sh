#!/bin/bash

# 测试连续 OTA Job 执行流程
# 验证 terminal callback 机制是否正常工作
#
# 用法: ./test-consecutive-jobs.sh <thing-name>
#
# 测试场景：
#   1. 设备初始版本: 1.0.2
#   2. Job 1: 升级到 1.0.3
#   3. Job 2: 升级到 1.0.4
#   4. 验证设备能在 <1 秒内收到 Job 2（而非 60 秒轮询）

set -e

# ========== 配置参数 ==========
THING_NAME="${1:-ota-test-device}"
REGION="us-east-1"
S3_BUCKET="vnas-iot-firmware-497892281794"

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# ========== 检查参数 ==========
if [ -z "$THING_NAME" ]; then
    echo -e "${RED}错误: 请指定 Thing Name${NC}"
    echo "用法: $0 <thing-name>"
    exit 1
fi

echo -e "${GREEN}========== 连续 OTA Job 测试 ==========${NC}"
echo "Thing Name: $THING_NAME"
echo "Region: $REGION"
echo "测试路径: 1.0.2 → 1.0.3 → 1.0.4"
echo ""

# ========== Step 0: 检查设备当前版本 ==========
echo -e "${YELLOW}[0/5] 检查设备当前版本...${NC}"

CURRENT_VERSION=$(aws iot-data get-thing-shadow \
  --thing-name "$THING_NAME" \
  --region "$REGION" \
  /dev/stdout 2>/dev/null | jq -r '.state.reported.firmwareVersion // empty')

if [ -z "$CURRENT_VERSION" ]; then
    echo -e "${RED}错误: 无法读取设备版本，请确保设备已连接并上报了 firmwareVersion${NC}"
    exit 1
fi

echo "✓ 当前版本: $CURRENT_VERSION"

if [ "$CURRENT_VERSION" != "1.0.2" ]; then
    echo -e "${YELLOW}⚠ 警告: 设备版本不是 1.0.2，测试可能无法按预期进行${NC}"
    read -p "是否继续? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi
echo ""

# ========== Step 1: 创建第一个 Job (1.0.2 → 1.0.3) ==========
echo -e "${YELLOW}[1/5] 创建 Job 1: 1.0.2 → 1.0.3...${NC}"

# 使用现有脚本创建 job
if [ ! -f "./create-ota-job.sh" ]; then
    echo -e "${RED}错误: 找不到 create-ota-job.sh 脚本${NC}"
    exit 1
fi

# 自动回答删除临时文件的提示
echo "N" | ./create-ota-job.sh 1.0.2 1.0.3 2>&1 | tee /tmp/job1-creation.log

# 提取 Job ID（兼容 macOS BSD grep）
JOB1_ID=$(grep "Job ID:" /tmp/job1-creation.log | tail -1 | awk '{print $3}')

if [ -z "$JOB1_ID" ]; then
    echo -e "${RED}错误: 无法获取 Job 1 的 ID${NC}"
    exit 1
fi

echo "✓ Job 1 已创建: $JOB1_ID"
echo ""

# ========== Step 2: 等待 Job 1 完成 ==========
echo -e "${YELLOW}[2/5] 等待 Job 1 完成...${NC}"
echo -e "${BLUE}💡 提示: 观察设备终端，应该看到完整的升级流程${NC}"

MAX_WAIT=180  # 最多等待 3 分钟
WAIT_TIME=0
JOB1_STATUS=""

while [ $WAIT_TIME -lt $MAX_WAIT ]; do
    # 查询 job execution 状态
    JOB1_STATUS=$(aws iot describe-job-execution \
      --job-id "$JOB1_ID" \
      --thing-name "$THING_NAME" \
      --region "$REGION" \
      --query 'execution.status' \
      --output text 2>/dev/null || echo "UNKNOWN")

    if [ "$JOB1_STATUS" == "SUCCEEDED" ]; then
        echo ""
        echo "✅ Job 1 完成: SUCCEEDED"
        break
    elif [ "$JOB1_STATUS" == "FAILED" ] || [ "$JOB1_STATUS" == "REJECTED" ]; then
        echo ""
        echo -e "${RED}❌ Job 1 失败: $JOB1_STATUS${NC}"
        exit 1
    fi

    echo -n "."
    sleep 5
    WAIT_TIME=$((WAIT_TIME + 5))
done

if [ "$JOB1_STATUS" != "SUCCEEDED" ]; then
    echo ""
    echo -e "${RED}错误: Job 1 未在 ${MAX_WAIT}s 内完成（当前状态: $JOB1_STATUS）${NC}"
    exit 1
fi

# 记录 Job 1 完成时间（用于计算 Job 2 接收延迟）
JOB1_COMPLETE_TIME=$(date +%s)
echo ""

# ========== Step 3: 验证版本已更新到 1.0.3 ==========
echo -e "${YELLOW}[3/5] 验证设备版本已更新...${NC}"

# 等待 Shadow 更新（给 Fleet Indexing 一点时间）
sleep 3

NEW_VERSION=$(aws iot-data get-thing-shadow \
  --thing-name "$THING_NAME" \
  --region "$REGION" \
  /dev/stdout 2>/dev/null | jq -r '.state.reported.firmwareVersion // empty')

if [ "$NEW_VERSION" != "1.0.3" ]; then
    echo -e "${RED}错误: 设备版本未更新到 1.0.3（当前: $NEW_VERSION）${NC}"
    exit 1
fi

echo "✓ 设备版本已更新: $NEW_VERSION"
echo ""

# ========== Step 4: 立即创建第二个 Job (1.0.3 → 1.0.4) ==========
echo -e "${YELLOW}[4/5] 创建 Job 2: 1.0.3 → 1.0.4...${NC}"
echo -e "${BLUE}💡 关键测试点: 观察设备是否能在 <1s 内收到 Job 2（而非 60s）${NC}"

# 记录 Job 2 创建时间
JOB2_CREATE_TIME=$(date +%s)

# 自动回答删除临时文件的提示
echo "N" | ./create-ota-job.sh 1.0.3 1.0.4 2>&1 | tee /tmp/job2-creation.log

# 提取 Job ID（兼容 macOS BSD grep）
JOB2_ID=$(grep "Job ID:" /tmp/job2-creation.log | tail -1 | awk '{print $3}')

if [ -z "$JOB2_ID" ]; then
    echo -e "${RED}错误: 无法获取 Job 2 的 ID${NC}"
    exit 1
fi

echo "✓ Job 2 已创建: $JOB2_ID"
echo ""

# ========== Step 5: 监控 Job 2 何时被设备接收 ==========
echo -e "${YELLOW}[5/5] 监控 Job 2 接收时间...${NC}"
echo -e "${BLUE}💡 提示: 查看设备终端，应该立即看到 '[OtaService] New OTA job received'${NC}"

MAX_WAIT=90
WAIT_TIME=0
JOB2_STATUS=""
JOB2_RECEIVED=false

while [ $WAIT_TIME -lt $MAX_WAIT ]; do
    # 查询 job execution 状态
    JOB2_STATUS=$(aws iot describe-job-execution \
      --job-id "$JOB2_ID" \
      --thing-name "$THING_NAME" \
      --region "$REGION" \
      --query 'execution.status' \
      --output text 2>/dev/null || echo "UNKNOWN")

    # 如果状态变为 IN_PROGRESS，说明设备已接收
    if [ "$JOB2_STATUS" == "IN_PROGRESS" ] && [ "$JOB2_RECEIVED" == false ]; then
        JOB2_RECEIVED=true
        JOB2_RECEIVE_TIME=$(date +%s)
        LATENCY=$((JOB2_RECEIVE_TIME - JOB1_COMPLETE_TIME))

        echo ""
        echo -e "${GREEN}✅ Job 2 已被设备接收（状态: IN_PROGRESS）${NC}"
        echo -e "${GREEN}⏱  延迟: ${LATENCY}s（从 Job 1 完成到 Job 2 接收）${NC}"

        if [ $LATENCY -lt 5 ]; then
            echo -e "${GREEN}🎉 测试通过！Terminal callback 机制正常工作（<5s）${NC}"
        elif [ $LATENCY -lt 60 ]; then
            echo -e "${YELLOW}⚠ 延迟在 5-60s 之间，可能有网络延迟或其他因素${NC}"
        else
            echo -e "${RED}❌ 延迟 >60s，可能 callback 机制未生效，依赖轮询${NC}"
        fi

        echo ""
        echo "继续等待 Job 2 完成..."
    fi

    if [ "$JOB2_STATUS" == "SUCCEEDED" ]; then
        echo ""
        echo "✅ Job 2 完成: SUCCEEDED"
        break
    elif [ "$JOB2_STATUS" == "FAILED" ] || [ "$JOB2_STATUS" == "REJECTED" ]; then
        echo ""
        echo -e "${RED}❌ Job 2 失败: $JOB2_STATUS${NC}"
        exit 1
    fi

    echo -n "."
    sleep 3
    WAIT_TIME=$((WAIT_TIME + 3))
done

if [ "$JOB2_STATUS" != "SUCCEEDED" ]; then
    echo ""
    echo -e "${RED}错误: Job 2 未在 ${MAX_WAIT}s 内完成（当前状态: $JOB2_STATUS）${NC}"
    exit 1
fi

# ========== 最终验证 ==========
echo ""
echo -e "${GREEN}========== 测试完成 ==========${NC}"

FINAL_VERSION=$(aws iot-data get-thing-shadow \
  --thing-name "$THING_NAME" \
  --region "$REGION" \
  /dev/stdout 2>/dev/null | jq -r '.state.reported.firmwareVersion // empty')

echo "最终版本: $FINAL_VERSION"

if [ "$FINAL_VERSION" == "1.0.4" ]; then
    echo -e "${GREEN}✅ 所有测试通过！设备成功完成连续两次 OTA 升级${NC}"
else
    echo -e "${RED}❌ 最终版本不正确（预期: 1.0.4，实际: $FINAL_VERSION）${NC}"
    exit 1
fi

echo ""
echo -e "${BLUE}查看测试结果:${NC}"
echo "  Job 1: $JOB1_ID → SUCCEEDED"
echo "  Job 2: $JOB2_ID → SUCCEEDED"
if [ "$JOB2_RECEIVED" == true ]; then
    echo "  Job 2 接收延迟: ${LATENCY}s"
fi
echo ""
echo -e "${BLUE}清理命令:${NC}"
echo "  aws iot delete-job --job-id $JOB1_ID --force"
echo "  aws iot delete-job --job-id $JOB2_ID --force"
echo "  aws iot-data update-thing-shadow --thing-name $THING_NAME --payload '{\"state\":{\"reported\":{\"firmwareVersion\":\"1.0.2\"}}}' /dev/stdout"
