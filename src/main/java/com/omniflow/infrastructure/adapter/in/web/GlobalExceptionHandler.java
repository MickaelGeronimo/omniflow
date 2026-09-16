package com.omniflow.infrastructure.adapter.in.web;

import com.omniflow.domain.exception.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InsufficientFundsException.class)
    public ProblemDetail handleInsufficientFunds(InsufficientFundsException ex) {
        log.warn("[EXCEPTION] Insufficient funds: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Insufficient Funds");
        problem.setType(URI.create("https://api.omniflow.com/errors/insufficient-funds"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(LedgerImbalanceException.class)
    public ProblemDetail handleLedgerImbalance(LedgerImbalanceException ex) {
        log.error("[EXCEPTION] Invariant Violation - Ledger Imbalance: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        problem.setTitle("Ledger Imbalance Detected");
        problem.setType(URI.create("https://api.omniflow.com/errors/ledger-imbalance"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(ConflictingPayloadException.class)
    public ProblemDetail handleConflictingPayload(ConflictingPayloadException ex) {
        log.warn("[EXCEPTION] Idempotency conflict: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Idempotency Payload Conflict");
        problem.setType(URI.create("https://api.omniflow.com/errors/idempotency-conflict"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(OptimisticConcurrencyException.class)
    public ProblemDetail handleOptimisticConcurrency(OptimisticConcurrencyException ex) {
        log.warn("[EXCEPTION] Optimistic locking conflict: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Optimistic Concurrency Conflict");
        problem.setType(URI.create("https://api.omniflow.com/errors/concurrency-conflict"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(InvalidTransactionStateException.class)
    public ProblemDetail handleInvalidState(InvalidTransactionStateException ex) {
        log.warn("[EXCEPTION] Invalid transaction state: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Transaction State");
        problem.setType(URI.create("https://api.omniflow.com/errors/invalid-state"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("[EXCEPTION] Illegal argument: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://api.omniflow.com/errors/bad-request"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        log.warn("[EXCEPTION] Validation failure: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed for request arguments");
        problem.setTitle("Validation Error");
        problem.setType(URI.create("https://api.omniflow.com/errors/validation-error"));

        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        problem.setProperty("invalidFields", fieldErrors);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ProblemDetail handleCurrencyMismatch(CurrencyMismatchException ex) {
        log.warn("[EXCEPTION] Currency mismatch: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Currency Mismatch");
        problem.setType(URI.create("https://api.omniflow.com/errors/currency-mismatch"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("[EXCEPTION] Malformed request body: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed JSON request body");
        problem.setTitle("Malformed Request Body");
        problem.setType(URI.create("https://api.omniflow.com/errors/malformed-body"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("[EXCEPTION] Method not allowed: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage());
        problem.setTitle("Method Not Allowed");
        problem.setType(URI.create("https://api.omniflow.com/errors/method-not-allowed"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException ex) {
        log.warn("[EXCEPTION] Missing required header: {}", ex.getHeaderName());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Required header '" + ex.getHeaderName() + "' must be provided.");
        problem.setTitle("Missing Required Header");
        problem.setType(URI.create("https://api.omniflow.com/errors/missing-header"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParam(MissingServletRequestParameterException ex) {
        log.warn("[EXCEPTION] Missing parameter: {}", ex.getParameterName());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Required parameter '" + ex.getParameterName() + "' must be provided.");
        problem.setTitle("Missing Parameter");
        problem.setType(URI.create("https://api.omniflow.com/errors/missing-parameter"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("[EXCEPTION] Type mismatch: {}", ex.getName());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Parameter '" + ex.getName() + "' is invalid.");
        problem.setTitle("Type Mismatch");
        problem.setType(URI.create("https://api.omniflow.com/errors/type-mismatch"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("[EXCEPTION] Data integrity conflict: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Database constraint violation or duplicate key conflict.");
        problem.setTitle("Data Integrity Conflict");
        problem.setType(URI.create("https://api.omniflow.com/errors/data-integrity-conflict"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneralException(Exception ex) {
        log.error("[EXCEPTION] Unhandled internal server error", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An internal error occurred processing your request.");
        problem.setTitle("Internal Server Error");
        problem.setType(URI.create("https://api.omniflow.com/errors/internal-server-error"));
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
