package com.vnas.iot;

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
 * HTTP 下载器：从 presigned URL 下载固件，校验 checksum，回调下载进度。
 */
public class FirmwareDownloader {

    private final HttpClient httpClient;
    private final OtaListener listener;

    public FirmwareDownloader(OtaListener listener) {
        this.httpClient = HttpClient.newHttpClient();
        this.listener = listener;
    }

    /**
     * 下载固件到本地路径，校验 checksum。
     *
     * @param pkg        OTA 包信息
     * @param outputPath 本地保存路径
     * @return true 下载并校验成功
     */
    public boolean download(OtaPackage pkg, Path outputPath) {
        try {
            listener.onProgress(0, "downloading");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(pkg.getPackageUrl()))
                    .GET()
                    .build();

            // 先发 HEAD 获取 content-length 用于进度计算
            long totalSize = getContentLength(pkg.getPackageUrl());

            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                listener.onProgress(-1, "failed: HTTP " + response.statusCode());
                return false;
            }

            // 边下载边计算 checksum
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

            // 校验 checksum
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

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onProgress(-1, "failed: interrupted");
            return false;
        } catch (IOException | NoSuchAlgorithmException e) {
            listener.onProgress(-1, "failed: " + e.getMessage());
            return false;
        }
    }

    private long getContentLength(String url) {
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

    /**
     * 从 "sha256:abc123..." 格式中提取 hash 值，
     * 如果没有前缀则原样返回。
     */
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
