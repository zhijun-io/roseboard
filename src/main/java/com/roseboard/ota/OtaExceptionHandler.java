package com.roseboard.ota;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class OtaExceptionHandler {

    @ExceptionHandler(OtaPackageException.class)
    public ResponseEntity<Map<String, Object>> handle(OtaPackageException exception) {
        HttpStatus status = switch (exception.errorCode()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT, DUPLICATE_PACKAGE -> HttpStatus.CONFLICT;
            case QUOTA_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of(
                "status", status.value(),
                "message", exception.getMessage() == null ? "" : exception.getMessage(),
                "errorCode", exception.errorCode().name()));
    }
}
