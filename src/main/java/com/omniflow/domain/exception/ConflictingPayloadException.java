package com.omniflow.domain.exception;

public class ConflictingPayloadException extends RuntimeException {
    public ConflictingPayloadException(String message) {
        super(message);
    }
}
