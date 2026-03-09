# Import Self-Signed Certificate to ACM & Bind to ALB

## 1. Prepare Files (PEM format)

- `certificate.crt` — certificate
- `private.key` — private key (unencrypted)
- `chain.pem` — CA chain (optional)

## 2. Import to ACM

```bash
aws acm import-certificate \
  --certificate fileb://certificate.crt \
  --private-key fileb://private.key \
  --certificate-chain fileb://chain.pem \
  --region us-east-1
```

> Use `fileb://` prefix, otherwise you'll get `Invalid base64` error.

## 3. Bind to ALB

In ALB HTTPS Listener, select the imported certificate from **"Choose a certificate from ACM"**.

## Notes

- Imported certificates do **not** auto-renew — you must re-import before expiry.
- Self-signed certificates are not trusted by browsers — suitable for internal services, IoT, or testing.
