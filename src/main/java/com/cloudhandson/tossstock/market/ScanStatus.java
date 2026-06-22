package com.cloudhandson.tossstock.market;

import org.springframework.stereotype.Component;

import java.time.Instant;

/** 스캔 진행 상태(단일 인스턴스, 메모리). */
@Component
public class ScanStatus {

    public enum State { IDLE, RUNNING, DONE, ERROR }

    private volatile State state = State.IDLE;
    private volatile int scanned = 0;
    private volatile int total = 0;
    private volatile Instant startedAt;
    private volatile String message;

    public synchronized boolean startIfIdle(int total) {
        if (state == State.RUNNING) {
            return false;
        }
        this.state = State.RUNNING;
        this.total = total;
        this.scanned = 0;
        this.startedAt = Instant.now();
        this.message = null;
        return true;
    }

    public void incScanned() { this.scanned++; }

    public void done() { this.state = State.DONE; }

    public void error(String msg) { this.state = State.ERROR; this.message = msg; }

    public State getState() { return state; }
    public int getScanned() { return scanned; }
    public int getTotal() { return total; }
    public Instant getStartedAt() { return startedAt; }
    public String getMessage() { return message; }
    public boolean isRunning() { return state == State.RUNNING; }
}
