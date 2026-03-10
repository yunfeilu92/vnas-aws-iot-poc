package com.vnas.iot;

/**
 * OTA 固件包信息，从 AWS IoT Job Document 解析。
 *
 * 支持两种下载方式：
 * - S3 直接下载（s3Bucket + s3Key）：适用于 CONTINUOUS Job，无过期问题
 * - Presigned URL（packageUrl）：向后兼容，有过期时间限制
 */
public class OtaPackage {

    private final String jobId;
    private final String version;
    private final String s3Bucket;
    private final String s3Key;
    private final String packageUrl;
    private final String checksum;
    private final String checksumType;

    public OtaPackage(String jobId, String version, String s3Bucket, String s3Key,
                      String packageUrl, String checksum, String checksumType) {
        this.jobId = jobId;
        this.version = version;
        this.s3Bucket = s3Bucket;
        this.s3Key = s3Key;
        this.packageUrl = packageUrl;
        this.checksum = checksum;
        this.checksumType = checksumType;
    }

    public String getJobId() { return jobId; }
    public String getVersion() { return version; }
    public String getS3Bucket() { return s3Bucket; }
    public String getS3Key() { return s3Key; }
    public String getPackageUrl() { return packageUrl; }
    public String getChecksum() { return checksum; }
    public String getChecksumType() { return checksumType; }

    public boolean hasS3Location() {
        return s3Bucket != null && !s3Bucket.isEmpty()
                && s3Key != null && !s3Key.isEmpty();
    }

    @Override
    public String toString() {
        String location = hasS3Location()
                ? "s3://" + s3Bucket + "/" + s3Key
                : packageUrl;
        return "OtaPackage{jobId='" + jobId + "', version='" + version +
               "', location='" + location + "', checksumType='" + checksumType + "'}";
    }
}
