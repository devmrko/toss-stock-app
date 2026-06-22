package com.cloudhandson.tossstock.toss;

/** 토스 API 호출 실패. status = 상류 HTTP 상태(없으면 0). */
public class TossApiException extends RuntimeException {
    private final int status;

    public TossApiException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
