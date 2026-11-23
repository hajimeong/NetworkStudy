package com.study.study.client;

import com.study.study.order.OrderRequest;
import com.study.study.order.OrderResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderClient {

    private static final int MAX_RETRIES = 2;                 // 실패 시 재시도 횟수
    private static final int MAX_ATTEMPTS = 1 + MAX_RETRIES;  // 최초 1회 + 재시도 2회 = 총 3회
    private static final long BACKOFF_MILLIS = 1500;          // 재시도 전 대기 시간

    private final RestClient orderRestClient;

    public OrderResponse createOrder(OrderRequest request) {
        // 하나의 "논리적 주문"에 키 1개. 재시도할 때도 같은 키를 보낸다.
        String idempotencyKey = UUID.randomUUID().toString();
        RestClientException lastException = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                sleep(BACKOFF_MILLIS);
            }
            try {
                log.info("[OrderClient] 요청 시도 {}/{} (Idempotency-Key={})", attempt, MAX_ATTEMPTS, idempotencyKey);
                OrderResponse response = orderRestClient.post()
                        .uri("/orders")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(OrderResponse.class);
                log.info("[OrderClient] 요청 성공 (시도 {}회차): {}", attempt, response);
                return response;
            } catch (RestClientException e) {
                if (!isRetryable(e)) {
                    throw e;
                }
                lastException = e;
                log.warn("[OrderClient] 요청 실패 (시도 {}/{}): {}", attempt, MAX_ATTEMPTS, describe(e));
            }
        }

        log.error("[OrderClient] {}회 모두 실패", MAX_ATTEMPTS);
        throw lastException;
    }

    /**
     * 재시도 대상:
     * - 타임아웃 등 I/O 오류 (SimpleClientHttpRequestFactory 는 RestClientException 으로 감싸서 줄 수 있어 cause 체인 확인)
     * - 409 Conflict: 같은 키 요청이 서버에서 아직 처리 중
     */
    private boolean isRetryable(RestClientException e) {
        if (e instanceof HttpClientErrorException http) {
            return http.getStatusCode() == HttpStatus.CONFLICT;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private String describe(Throwable e) {
        if (e instanceof HttpClientErrorException http) {
            return http.getStatusCode().toString();
        }
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
