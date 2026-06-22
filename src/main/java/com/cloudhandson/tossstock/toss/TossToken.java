package com.cloudhandson.tossstock.toss;

/** 메모리 캐시되는 액세스 토큰. expiresAtEpochSec = 발급시각 + expires_in. */
public record TossToken(String accessToken, String tokenType, long expiresAtEpochSec) {

    /** 만료 60초 여유(시계 오차·지연 대비)를 두고 만료 판정. */
    public boolean isExpired(long nowSec) {
        return expiresAtEpochSec - 60 <= nowSec;
    }
}
