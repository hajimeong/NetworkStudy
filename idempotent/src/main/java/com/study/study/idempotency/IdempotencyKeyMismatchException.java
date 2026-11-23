package com.study.study.idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 같은 Idempotency-Key 로 다른 내용의 요청이 들어왔을 때 → 422 Unprocessable Content */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class IdempotencyKeyMismatchException extends RuntimeException {

    public IdempotencyKeyMismatchException(String key) {
        super("같은 Idempotency-Key 로 다른 요청 본문이 전달되었습니다: " + key);
    }
}
