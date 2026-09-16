package com.omniflow.infrastructure.web;

import com.omniflow.domain.exception.*;
import com.omniflow.infrastructure.adapter.in.web.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("InsufficientFundsException should map to HTTP 422 Unprocessable Entity")
    void testInsufficientFundsException() {
        InsufficientFundsException ex = new InsufficientFundsException("Account balance insufficient");
        ProblemDetail problem = handler.handleInsufficientFunds(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        assertThat(problem.getTitle()).isEqualTo("Insufficient Funds");
        assertThat(problem.getDetail()).contains("Account balance insufficient");
    }

    @Test
    @DisplayName("CurrencyMismatchException should map to HTTP 422 Unprocessable Entity")
    void testCurrencyMismatchException() {
        CurrencyMismatchException ex = new CurrencyMismatchException("Cannot mix USD and BRL");
        ProblemDetail problem = handler.handleCurrencyMismatch(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        assertThat(problem.getTitle()).isEqualTo("Currency Mismatch");
        assertThat(problem.getDetail()).contains("Cannot mix USD and BRL");
    }

    @Test
    @DisplayName("HttpMessageNotReadableException should map to HTTP 400 Bad Request")
    void testHttpMessageNotReadableException() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("Malformed JSON syntax");
        ProblemDetail problem = handler.handleMessageNotReadable(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Malformed Request Body");
        assertThat(problem.getDetail()).isEqualTo("Malformed JSON request body");
    }

    @Test
    @DisplayName("HttpRequestMethodNotSupportedException should map to HTTP 405 Method Not Allowed")
    void testHttpRequestMethodNotSupportedException() {
        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException("DELETE");
        ProblemDetail problem = handler.handleMethodNotSupported(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
        assertThat(problem.getTitle()).isEqualTo("Method Not Allowed");
        assertThat(problem.getDetail()).contains("DELETE");
    }

    @Test
    @DisplayName("MissingRequestHeaderException should map to HTTP 400 Bad Request")
    void testMissingRequestHeaderException() {
        MissingRequestHeaderException ex = new MissingRequestHeaderException("Idempotency-Key", null);
        ProblemDetail problem = handler.handleMissingHeader(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Missing Required Header");
        assertThat(problem.getDetail()).contains("Idempotency-Key");
    }

    @Test
    @DisplayName("MissingServletRequestParameterException should map to HTTP 400 Bad Request")
    void testMissingServletRequestParameterException() {
        MissingServletRequestParameterException ex = new MissingServletRequestParameterException("cutoff", "String");
        ProblemDetail problem = handler.handleMissingParam(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Missing Parameter");
        assertThat(problem.getDetail()).contains("cutoff");
    }

    @Test
    @DisplayName("MethodArgumentTypeMismatchException should map to HTTP 400 Bad Request")
    void testMethodArgumentTypeMismatchException() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException("abc", Integer.class, "limit", null, null);
        ProblemDetail problem = handler.handleTypeMismatch(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Type Mismatch");
        assertThat(problem.getDetail()).contains("limit");
    }

    @Test
    @DisplayName("DataIntegrityViolationException should map to HTTP 409 Conflict")
    void testDataIntegrityViolationException() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException("Unique constraint violation: idx_tx_ref");
        ProblemDetail problem = handler.handleDataIntegrityViolation(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Data Integrity Conflict");
    }

    @Test
    @DisplayName("ConflictingPayloadException should map to HTTP 409 Conflict")
    void testConflictingPayloadException() {
        ConflictingPayloadException ex = new ConflictingPayloadException("Idempotency key re-used with different payload");
        ProblemDetail problem = handler.handleConflictingPayload(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Idempotency Payload Conflict");
    }

    @Test
    @DisplayName("OptimisticConcurrencyException should map to HTTP 409 Conflict")
    void testOptimisticConcurrencyException() {
        OptimisticConcurrencyException ex = new OptimisticConcurrencyException("Account modified concurrently");
        ProblemDetail problem = handler.handleOptimisticConcurrency(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Optimistic Concurrency Conflict");
    }

    @Test
    @DisplayName("Unhandled generic exception should map to HTTP 500 Internal Server Error without leaking traces")
    void testGenericExceptionFallback() {
        RuntimeException ex = new RuntimeException("Null pointer in some internal calculation");
        ProblemDetail problem = handler.handleGeneralException(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getTitle()).isEqualTo("Internal Server Error");
        assertThat(problem.getDetail()).isEqualTo("An internal error occurred processing your request.");
    }
}
