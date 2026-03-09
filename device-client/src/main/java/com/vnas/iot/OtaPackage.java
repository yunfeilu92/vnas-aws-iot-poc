package com.vnas.iot;

/**
 * OTA 固件包信息，从 AWS IoT Job Document 解析。
 */
public class OtaPackage {

    private final String jobId;
    private final String version;
    private final String packageUrl;
    private final String checksum;
    private final String checksumType;

    public OtaPackage(String jobId, String version, String packageUrl,
                      String checksum, String checksumType) {
        this.jobId = jobId;
        this.version = version;
        this.packageUrl = packageUrl;
        this.checksum = checksum;
        this.checksumType = checksumType;
    }

    public String getJobId() { return jobId; }
    public String getVersion() { return version; }
    public String getPackageUrl() { return packageUrl; }
    public String getChecksum() { return checksum; }
    public String getChecksumType() { return checksumType; }

    @Override
    public String toString() {
        return "OtaPackage{jobId='" + jobId + "', version='" + version +
               "', checksumType='" + checksumType + "'}";
    }
}
