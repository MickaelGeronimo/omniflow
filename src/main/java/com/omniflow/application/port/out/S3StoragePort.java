package com.omniflow.application.port.out;

public interface S3StoragePort {

    String uploadReport(String bucketName, String keyName, byte[] content, String contentType);
}
