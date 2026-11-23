package com.study.study.idempotency;

public enum IdempotencyStatus {
    PROCESSING, // 요청 처리 중 (키 선점)
    COMPLETED   // 처리 완료 (응답 저장됨)
}
