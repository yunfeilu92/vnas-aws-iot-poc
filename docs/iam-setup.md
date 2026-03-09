# VNAS IoT PoC - IAM User 权限配置

## 概述

为 VNAS IoT PoC 项目创建一个 IAM User，具备以下权限：
- **IAM 管理**：创建/管理 Role、Policy（用于 IoT Job 等服务角色）
- **IoT OTA**：Job、Stream、Fleet Indexing、Code Signing
- **IoT Thing Group**：Static/Dynamic Thing Group 管理
- **IoT Secure Tunneling**：设备远程隧道访问
- **S3**：OTA 固件包存储
- **CloudWatch Logs**：日志监控

## 前置条件

```bash
# 设置变量（替换为你的实际值）
export AWS_ACCOUNT_ID="123456789012"
export AWS_REGION="ap-southeast-1"
export IAM_USER_NAME="vnas-iot-admin"
export POLICY_NAME="VnasIoTAdminPolicy"
export S3_BUCKET_NAME="vnas-iot-firmware"
```

## Step 1: 创建 IAM Policy

```bash
cat > /tmp/vnas-iot-policy.json << 'POLICY'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "IAMManagement",
      "Effect": "Allow",
      "Action": [
        "iam:CreateRole",
        "iam:DeleteRole",
        "iam:GetRole",
        "iam:ListRoles",
        "iam:UpdateRole",
        "iam:TagRole",
        "iam:UntagRole",
        "iam:AttachRolePolicy",
        "iam:DetachRolePolicy",
        "iam:PutRolePolicy",
        "iam:GetRolePolicy",
        "iam:DeleteRolePolicy",
        "iam:ListRolePolicies",
        "iam:ListAttachedRolePolicies",
        "iam:CreatePolicy",
        "iam:DeletePolicy",
        "iam:GetPolicy",
        "iam:GetPolicyVersion",
        "iam:ListPolicies",
        "iam:ListPolicyVersions",
        "iam:CreatePolicyVersion",
        "iam:DeletePolicyVersion",
        "iam:CreateInstanceProfile",
        "iam:DeleteInstanceProfile",
        "iam:AddRoleToInstanceProfile",
        "iam:RemoveRoleFromInstanceProfile",
        "iam:PassRole"
      ],
      "Resource": "*",
      "Condition": {
        "StringEquals": {
          "aws:RequestedRegion": "${AWS_REGION}"
        }
      }
    },
    {
      "Sid": "IAMPassRoleForIoT",
      "Effect": "Allow",
      "Action": "iam:PassRole",
      "Resource": "arn:aws:iam::${AWS_ACCOUNT_ID}:role/vnas-*",
      "Condition": {
        "StringEquals": {
          "iam:PassedToService": [
            "iot.amazonaws.com",
            "signer.amazonaws.com"
          ]
        }
      }
    },
    {
      "Sid": "IoTCoreFullAccess",
      "Effect": "Allow",
      "Action": [
        "iot:CreateThing",
        "iot:DeleteThing",
        "iot:DescribeThing",
        "iot:ListThings",
        "iot:UpdateThing",
        "iot:CreateThingType",
        "iot:DescribeThingType",
        "iot:ListThingTypes",
        "iot:RegisterThing",
        "iot:CreateKeysAndCertificate",
        "iot:CreateCertificateFromCsr",
        "iot:DeleteCertificate",
        "iot:UpdateCertificate",
        "iot:DescribeCertificate",
        "iot:ListCertificates",
        "iot:AttachThingPrincipal",
        "iot:DetachThingPrincipal",
        "iot:CreatePolicy",
        "iot:DeletePolicy",
        "iot:GetPolicy",
        "iot:ListPolicies",
        "iot:CreatePolicyVersion",
        "iot:DeletePolicyVersion",
        "iot:ListPolicyVersions",
        "iot:AttachPolicy",
        "iot:DetachPolicy",
        "iot:ListTargetsForPolicy"
      ],
      "Resource": "*"
    },
    {
      "Sid": "IoTThingGroup",
      "Effect": "Allow",
      "Action": [
        "iot:CreateThingGroup",
        "iot:DeleteThingGroup",
        "iot:UpdateThingGroup",
        "iot:DescribeThingGroup",
        "iot:ListThingGroups",
        "iot:ListThingGroupsForThing",
        "iot:AddThingToThingGroup",
        "iot:RemoveThingFromThingGroup",
        "iot:ListThingsInThingGroup",
        "iot:CreateDynamicThingGroup",
        "iot:DeleteDynamicThingGroup",
        "iot:UpdateDynamicThingGroup",
        "iot:DescribeIndex",
        "iot:GetIndexingConfiguration",
        "iot:UpdateIndexingConfiguration",
        "iot:SearchIndex"
      ],
      "Resource": "*"
    },
    {
      "Sid": "IoTOTAAndJobs",
      "Effect": "Allow",
      "Action": [
        "iot:CreateJob",
        "iot:DeleteJob",
        "iot:DescribeJob",
        "iot:GetJobDocument",
        "iot:ListJobs",
        "iot:UpdateJob",
        "iot:CancelJob",
        "iot:DescribeJobExecution",
        "iot:ListJobExecutionsForJob",
        "iot:ListJobExecutionsForThing",
        "iot:CreateOTAUpdate",
        "iot:DeleteOTAUpdate",
        "iot:GetOTAUpdate",
        "iot:ListOTAUpdates",
        "iot:CreateStream",
        "iot:DeleteStream",
        "iot:DescribeStream",
        "iot:ListStreams",
        "iot:UpdateStream"
      ],
      "Resource": "*"
    },
    {
      "Sid": "IoTSecureTunneling",
      "Effect": "Allow",
      "Action": [
        "iot:OpenTunnel",
        "iot:CloseTunnel",
        "iot:DescribeTunnel",
        "iot:ListTunnels",
        "iot:TagResource",
        "iot:ListTagsForResource",
        "iot:RotateTunnelAccessToken"
      ],
      "Resource": "*"
    },
    {
      "Sid": "IoTDeviceShadow",
      "Effect": "Allow",
      "Action": [
        "iot:GetThingShadow",
        "iot:UpdateThingShadow",
        "iot:DeleteThingShadow",
        "iot:ListNamedShadowsForThing"
      ],
      "Resource": "*"
    },
    {
      "Sid": "S3FirmwareBucket",
      "Effect": "Allow",
      "Action": [
        "s3:CreateBucket",
        "s3:DeleteBucket",
        "s3:ListBucket",
        "s3:GetBucketLocation",
        "s3:GetBucketVersioning",
        "s3:PutBucketVersioning",
        "s3:PutObject",
        "s3:GetObject",
        "s3:DeleteObject",
        "s3:GetObjectVersion"
      ],
      "Resource": [
        "arn:aws:s3:::${S3_BUCKET_NAME}",
        "arn:aws:s3:::${S3_BUCKET_NAME}/*"
      ]
    },
    {
      "Sid": "CodeSigning",
      "Effect": "Allow",
      "Action": [
        "signer:PutSigningProfile",
        "signer:GetSigningProfile",
        "signer:ListSigningProfiles",
        "signer:StartSigningJob",
        "signer:DescribeSigningJob",
        "signer:ListSigningJobs",
        "signer:CancelSigningProfile"
      ],
      "Resource": "*"
    },
    {
      "Sid": "CloudWatchLogs",
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:DescribeLogGroups",
        "logs:DescribeLogStreams",
        "logs:GetLogEvents",
        "logs:FilterLogEvents"
      ],
      "Resource": "arn:aws:logs:${AWS_REGION}:${AWS_ACCOUNT_ID}:*"
    },
    {
      "Sid": "IoTEndpointAndDescribe",
      "Effect": "Allow",
      "Action": [
        "iot:DescribeEndpoint",
        "iot:ListThingRegistrationTasks",
        "iot:DescribeEventConfigurations"
      ],
      "Resource": "*"
    }
  ]
}
POLICY
```

> **注意**：上面的 Policy JSON 中使用了 `${AWS_REGION}`、`${AWS_ACCOUNT_ID}`、`${S3_BUCKET_NAME}` 占位符。执行前需替换为实际值，或使用下方的 `envsubst` 命令自动替换。

```bash
# 替换变量并创建 policy
envsubst < /tmp/vnas-iot-policy.json > /tmp/vnas-iot-policy-resolved.json

aws iam create-policy \
  --policy-name "$POLICY_NAME" \
  --policy-document file:///tmp/vnas-iot-policy-resolved.json \
  --description "VNAS IoT PoC admin policy - IAM, IoT OTA, Thing Group, Tunnel"
```

## Step 2: 创建 IAM User 并附加 Policy

```bash
# 创建 IAM User
aws iam create-user --user-name "$IAM_USER_NAME"

# 附加 Policy
aws iam attach-user-policy \
  --user-name "$IAM_USER_NAME" \
  --policy-arn "arn:aws:iam::${AWS_ACCOUNT_ID}:policy/${POLICY_NAME}"

# 创建 Access Key（用于 CLI 或 SDK 调用）
aws iam create-access-key --user-name "$IAM_USER_NAME"
```

> 记录返回的 `AccessKeyId` 和 `SecretAccessKey`，后续无法再次查看。

## Step 3: 配置 AWS CLI Profile（可选）

```bash
aws configure --profile vnas-iot
# 输入 Access Key ID
# 输入 Secret Access Key
# 输入 Region: ap-southeast-1
# 输入 Output format: json
```

## 权限说明

| 权限模块 | 覆盖的操作 | 用途 |
|---------|-----------|------|
| IAM Management | CreateRole, CreatePolicy, PassRole 等 | 创建 IoT Job/Signer 所需的 Service Role |
| IoT Core | Thing, Certificate, IoT Policy | 设备注册和认证管理 |
| Thing Group | Static/Dynamic Group, Fleet Indexing | 按固件版本分组设备 |
| OTA & Jobs | Job, OTAUpdate, Stream | 下发 OTA 升级任务 |
| Secure Tunneling | OpenTunnel, CloseTunnel 等 | 远程调试设备 |
| Device Shadow | Get/Update Shadow | 设备状态同步 |
| S3 | 限定 bucket 范围 | 固件包存储 |
| Code Signing | Signer Profile, Signing Job | 固件签名验证 |
| CloudWatch | Log Group/Stream | 监控和日志查询 |

## 安全建议

1. **最小权限原则**：IAM 权限中的 `Resource: "*"` 可进一步限定为具体 ARN（如 `arn:aws:iot:region:account:thinggroup/vnas-*`）
2. **PassRole 限制**：已限定只能传递 `vnas-*` 前缀的 Role 给 IoT/Signer 服务
3. **S3 限制**：已限定到具体 bucket
4. **启用 MFA**：建议为该 User 启用 MFA
5. **定期轮换 Access Key**：建议 90 天轮换一次

## 清理命令

```bash
# 删除 Access Key（先列出 key ID）
aws iam list-access-keys --user-name "$IAM_USER_NAME"
aws iam delete-access-key --user-name "$IAM_USER_NAME" --access-key-id <KEY_ID>

# 分离 Policy
aws iam detach-user-policy \
  --user-name "$IAM_USER_NAME" \
  --policy-arn "arn:aws:iam::${AWS_ACCOUNT_ID}:policy/${POLICY_NAME}"

# 删除 User 和 Policy
aws iam delete-user --user-name "$IAM_USER_NAME"
aws iam delete-policy --policy-arn "arn:aws:iam::${AWS_ACCOUNT_ID}:policy/${POLICY_NAME}"
```
