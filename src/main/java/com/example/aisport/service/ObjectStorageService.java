package com.example.aisport.service;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

@Service
public class ObjectStorageService {

    @Value("${app.object-storage.enabled:false}")
    private boolean enabled;

    @Value("${app.object-storage.provider:cos}")
    private String provider;

    @Value("${app.object-storage.region:ap-guangzhou}")
    private String region;

    @Value("${app.object-storage.bucket:}")
    private String bucket;

    @Value("${app.object-storage.access-key:}")
    private String accessKey;

    @Value("${app.object-storage.secret-key:}")
    private String secretKey;

    @Value("${app.object-storage.public-base-url:}")
    private String publicBaseUrl;

    @Value("${app.object-storage.endpoint-suffix:}")
    private String endpointSuffix;

    private COSClient cosClient;

    @PostConstruct
    public void init() {
        if (!enabled) {
            return;
        }
        if (!"cos".equalsIgnoreCase(provider)) {
            throw new IllegalStateException("Unsupported object storage provider: " + provider);
        }
        if (isBlank(bucket) || isBlank(accessKey) || isBlank(secretKey)) {
            throw new IllegalStateException("COS config missing: bucket/access-key/secret-key are required");
        }

        COSCredentials creds = new BasicCOSCredentials(accessKey, secretKey);
        ClientConfig clientConfig = new ClientConfig(new Region(region));
        if (!isBlank(endpointSuffix)) {
            clientConfig.setEndPointSuffix(endpointSuffix);
        }
        this.cosClient = new COSClient(creds, clientConfig);
    }

    @PreDestroy
    public void close() {
        if (cosClient != null) {
            cosClient.shutdown();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getBucket() {
        return bucket;
    }

    public String uploadFile(Path localFile, String objectKey, String contentType) throws IOException {
        if (!enabled) {
            throw new IllegalStateException("Object storage disabled");
        }
        String finalType = isBlank(contentType) ? Files.probeContentType(localFile) : contentType;
        if (isBlank(finalType)) {
            finalType = "application/octet-stream";
        }
        PutObjectRequest req = new PutObjectRequest(bucket, objectKey, localFile.toFile());
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(finalType);
        req.setMetadata(metadata);
        cosClient.putObject(req);
        return toPublicUrl(objectKey);
    }

    public Path downloadToTemp(String objectKey, String fileSuffix) throws IOException {
        if (!enabled) {
            throw new IllegalStateException("Object storage disabled");
        }
        String suffix = isBlank(fileSuffix) ? ".bin" : fileSuffix;
        Path tmp = Files.createTempFile("aisport-cos-", suffix);
        GetObjectRequest req = new GetObjectRequest(bucket, objectKey);
        cosClient.getObject(req, tmp.toFile());
        return tmp;
    }

    public void deleteObject(String objectKey) {
        if (!enabled || isBlank(objectKey)) {
            return;
        }
        try {
            cosClient.deleteObject(bucket, objectKey);
        } catch (Exception ignored) {
        }
    }

    public String toCosUri(String objectKey) {
        return "cos://" + bucket + "/" + objectKey;
    }

    public String keyFromStoredPath(String storedPath) {
        if (isBlank(storedPath)) {
            return null;
        }
        String prefix = "cos://" + bucket + "/";
        if (storedPath.startsWith(prefix)) {
            return storedPath.substring(prefix.length());
        }
        if (storedPath.startsWith("http://") || storedPath.startsWith("https://")) {
            int idx = storedPath.indexOf(bucket + "/");
            if (idx >= 0) {
                return storedPath.substring(idx + bucket.length() + 1);
            }
        }
        return null;
    }

    public String toPublicUrl(String objectKey) {
        String base = publicBaseUrl;
        if (isBlank(base)) {
            base = "https://" + bucket + ".cos." + region + ".myqcloud.com";
        }
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/" + objectKey;
    }

    public String buildVideoObjectKey(Long userId, String fileName) {
        return "videos/" + safe(userId) + "/" + LocalDate.now() + "/" + fileName;
    }

    public String buildReportObjectKey(Object videoId, String fileName) {
        return "reports/" + safe(videoId) + "/" + fileName;
    }

    private String safe(Object val) {
        return val == null ? "unknown" : String.valueOf(val).replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
