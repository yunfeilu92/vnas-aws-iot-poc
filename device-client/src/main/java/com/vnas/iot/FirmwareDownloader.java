package com.vnas.iot;

import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.crt.auth.credentials.Credentials;
import software.amazon.awssdk.crt.auth.credentials.X509CredentialsProvider;
import software.amazon.awssdk.crt.io.TlsContext;
import software.amazon.awssdk.crt.io.TlsContextOptions;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 固件下载器：支持 S3 直接下载（通过 IoT Credentials Provider）和 HTTP presigned URL 下载。
 */
public class FirmwareDownloader {

    private final OtaListener listener;

    // S3 下载所需的配置（通过 IoT Credentials Provider 获取临时凭证）
    private String credentialEndpoint;
    private String roleAlias;
    private String thingName;
    private String certPath;
    private String keyPath;
    private String caPath;
    private String region;

    public FirmwareDownloader(OtaListener listener) {
        this.listener = listener;
    }

    /**
     * 配置 IoT Credentials Provider 参数（用于 S3 直接下载）
     */
    public void configureS3(String credentialEndpoint, String roleAlias, String thingName,
                            String certPath, String keyPath, String caPath, String region) {
        this.credentialEndpoint = credentialEndpoint;
        this.roleAlias = roleAlias;
        this.thingName = thingName;
        this.certPath = certPath;
        this.keyPath = keyPath;
        this.caPath = caPath;
        this.region = region;
    }

    /**
     * 下载固件到本地路径，校验 checksum。
     * 自动选择下载方式：优先 S3 直接下载，回退到 presigned URL。
     */
    public boolean download(OtaPackage pkg, Path outputPath) {
        if (pkg.hasS3Location() && credentialEndpoint != null) {
            return downloadFromS3(pkg, outputPath);
        }
        return downloadFromUrl(pkg, outputPath);
    }

    /**
     * 通过 IoT Credentials Provider 获取临时凭证，直接从 S3 下载。
     * 无 URL 过期问题，适用于 CONTINUOUS Job。
     */
    private boolean downloadFromS3(OtaPackage pkg, Path outputPath) {
        try {
            listener.onProgress(0, "downloading from S3 via IoT Credentials Provider");

            // 1. 使用 X.509 证书通过 IoT Credentials Provider 获取临时 AWS 凭证
            System.out.println("[Downloader] Getting temporary credentials from IoT Credentials Provider...");
            AwsSessionCredentials awsCreds = getCredentialsFromIoT();
            System.out.println("[Downloader] Temporary credentials obtained successfully.");

            // 2. 使用临时凭证创建 S3 Client
            S3Client s3 = S3Client.builder()
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                    .build();

            // 3. 获取文件大小用于进度计算
            long totalSize = -1;
            try {
                totalSize = s3.headObject(HeadObjectRequest.builder()
                        .bucket(pkg.getS3Bucket()).key(pkg.getS3Key()).build())
                        .contentLength();
            } catch (Exception e) {
                System.out.println("[Downloader] Could not get content length, progress will be approximate.");
            }

            // 4. 下载文件，边下载边计算 checksum
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long downloaded = 0;
            int lastPercent = 0;

            Files.createDirectories(outputPath.getParent());

            try (InputStream in = s3.getObject(GetObjectRequest.builder()
                    .bucket(pkg.getS3Bucket()).key(pkg.getS3Key()).build());
                 var out = Files.newOutputStream(outputPath)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    digest.update(buffer, 0, bytesRead);
                    downloaded += bytesRead;

                    if (totalSize > 0) {
                        int percent = (int) (downloaded * 100 / totalSize);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            listener.onProgress(percent, "downloading");
                        }
                    }
                }
            }

            s3.close();
            listener.onProgress(100, "download complete");

            // 5. 校验 checksum
            return verifyChecksum(digest, pkg, outputPath);

        } catch (Exception e) {
            listener.onProgress(-1, "failed: S3 download error - " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 使用 CRT X509CredentialsProvider 通过 IoT Credentials Provider 获取临时 AWS 凭证。
     */
    private AwsSessionCredentials getCredentialsFromIoT() throws Exception {
        try (TlsContextOptions tlsOpts = TlsContextOptions.createWithMtlsFromPath(certPath, keyPath)) {
            tlsOpts.overrideDefaultTrustStoreFromPath(null, caPath);

            try (TlsContext tlsCtx = new TlsContext(tlsOpts);
                 X509CredentialsProvider provider = new X509CredentialsProvider.X509CredentialsProviderBuilder()
                         .withTlsContext(tlsCtx)
                         .withEndpoint(credentialEndpoint)
                         .withRoleAlias(roleAlias)
                         .withThingName(thingName)
                         .build()) {

                Credentials crtCreds = provider.getCredentials().get();

                return AwsSessionCredentials.create(
                        new String(crtCreds.getAccessKeyId()),
                        new String(crtCreds.getSecretAccessKey()),
                        new String(crtCreds.getSessionToken()));
            }
        }
    }

    /**
     * 从 presigned URL 下载（向后兼容，有过期限制）。
     */
    private boolean downloadFromUrl(OtaPackage pkg, Path outputPath) {
        try {
            listener.onProgress(0, "downloading from presigned URL");

            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(pkg.getPackageUrl()))
                    .GET()
                    .build();

            long totalSize = getContentLength(httpClient, pkg.getPackageUrl());

            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                listener.onProgress(-1, "failed: HTTP " + response.statusCode());
                return false;
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long downloaded = 0;
            int lastPercent = 0;

            Files.createDirectories(outputPath.getParent());
            try (InputStream in = response.body();
                 var out = Files.newOutputStream(outputPath)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    digest.update(buffer, 0, bytesRead);
                    downloaded += bytesRead;

                    if (totalSize > 0) {
                        int percent = (int) (downloaded * 100 / totalSize);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            listener.onProgress(percent, "downloading");
                        }
                    }
                }
            }

            listener.onProgress(100, "download complete");
            return verifyChecksum(digest, pkg, outputPath);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onProgress(-1, "failed: interrupted");
            return false;
        } catch (IOException | NoSuchAlgorithmException e) {
            listener.onProgress(-1, "failed: " + e.getMessage());
            return false;
        }
    }

    private boolean verifyChecksum(MessageDigest digest, OtaPackage pkg, Path outputPath) throws IOException {
        listener.onProgress(-1, "verifying checksum");
        String actualHash = bytesToHex(digest.digest());
        String expectedHash = extractHash(pkg.getChecksum());

        if (!actualHash.equalsIgnoreCase(expectedHash)) {
            listener.onProgress(-1, "failed: checksum mismatch (expected="
                    + expectedHash.substring(0, 8) + "..., actual="
                    + actualHash.substring(0, 8) + "...)");
            Files.deleteIfExists(outputPath);
            return false;
        }

        listener.onProgress(-1, "checksum verified");
        return true;
    }

    private long getContentLength(HttpClient httpClient, String url) {
        try {
            HttpRequest head = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> resp = httpClient.send(head,
                    HttpResponse.BodyHandlers.discarding());
            return resp.headers().firstValueAsLong("content-length").orElse(-1);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String extractHash(String checksum) {
        if (checksum == null) return "";
        int idx = checksum.indexOf(':');
        return idx >= 0 ? checksum.substring(idx + 1) : checksum;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
