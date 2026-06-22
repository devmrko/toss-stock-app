package com.cloudhandson.tossstock.web;

import com.cloudhandson.tossstock.toss.TossApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 상류(토스) 실패는 502 Bad Gateway 로 변환. */
    @ExceptionHandler(TossApiException.class)
    public ResponseEntity<Map<String, Object>> handleToss(TossApiException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(
                        "error", "toss-upstream-error",
                        "upstreamStatus", ex.getStatus(),
                        "message", ex.getMessage()));
    }
}
