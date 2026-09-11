package com.omniflow.infrastructure.adapter.out.aws;

import com.omniflow.application.port.out.S3StoragePort;
import io.awspring.cloud.s3.S3Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

@Component
public class S3ReconciliationStorageAdapter implements S3StoragePort {

    private static final Logger log = LoggerFactory.getLogger(S3ReconciliationStorageAdapter.class);

    private final S3Template s3Template;

    public S3ReconciliationStorageAdapter(@Autowired(required = false) @Nullable S3Template s3Template) {
        this.s3Template = s3Template;
    }

    @Override
    public String uploadReport(String bucketName, String keyName, byte[] content, String contentType) {
        try {
            if (s3Template != null) {
                s3Template.upload(bucketName, keyName, new ByteArrayInputStream(content));
                String url = String.format("s3://%s/%s", bucketName, keyName);
                log.info("[AWS-S3] Uploaded reconciliation report to [{}]", url);
                return url;
            } else {
                String simulatedUrl = String.format("simulated://%s/%s", bucketName, keyName);
                log.info("[AWS-S3-SIMULATED] (No broker active) Uploaded {} bytes to {}", content.length, simulatedUrl);
                return simulatedUrl;
            }
        } catch (Exception e) {
            log.error("[AWS-S3] Failed uploading report to [{}]", bucketName, e);
            throw new RuntimeException("S3 report upload failure", e);
        }
    }
}
