package com.study.study.idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 같은 Idempotency-Key 요청이 아직 처리 중일 때 → 409 Conflict */
@ResponseStatus(HttpStatus.CONFLICT)
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String key) {
        super("같은 Idempotency-Key 요청이 처리 중입니다: " + key);
    }
}
