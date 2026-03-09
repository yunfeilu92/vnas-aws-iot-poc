# Register a Device (Thing) in AWS IoT Core

## 1. Create Thing

```bash
aws iot create-thing --thing-name MyDevice
```

## 2. Create Certificate & Keys

```bash
aws iot create-keys-and-certificate \
  --set-as-active \
  --certificate-pem-outfile device-cert.pem \
  --public-key-outfile public.key \
  --private-key-outfile private.key
```

> Save the `certificateArn` from the output — needed in steps 5 & 6.

## 3. Download Amazon Root CA

```bash
curl -o AmazonRootCA1.pem https://www.amazontrust.com/repository/AmazonRootCA1.pem
```

## 4. Create IoT Policy

```bash
aws iot create-policy \
  --policy-name MyDevicePolicy \
  --policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Action": ["iot:Connect", "iot:Publish", "iot:Subscribe", "iot:Receive"],
      "Resource": "*"
    }]
  }'
```

> Production: restrict `Resource` to specific client IDs and topics.

## 5. Attach Policy to Certificate

```bash
aws iot attach-policy \
  --policy-name MyDevicePolicy \
  --target <certificateArn>
```

## 6. Attach Certificate to Thing

```bash
aws iot attach-thing-principal \
  --thing-name MyDevice \
  --principal <certificateArn>
```

## 7. Get MQTT Endpoint

```bash
aws iot describe-endpoint --endpoint-type iot:Data-ATS
```

## Device Connection

The device needs 3 files + endpoint to connect via MQTT over TLS:

- `device-cert.pem` — device identity (server verifies device)
- `private.key` — proves ownership of the certificate
- `AmazonRootCA1.pem` — verifies server identity (TLS handshake)
- Endpoint from step 7
