package com.study.study.order;

import com.study.study.idempotency.IdempotencyConflictException;
import com.study.study.idempotency.IdempotencyKey;
import com.study.study.idempotency.IdempotencyKeyMismatchException;
import com.study.study.idempotency.IdempotencyKeyRepository;
import com.study.study.idempotency.IdempotencyStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final TransactionTemplate transactionTemplate;
    private final JsonMapper jsonMapper;

    public OrderResponse createOrder(String idempotencyKey, OrderRequest request) {
        String requestHash = hash(request);

        // 1. 키 선점: PROCESSING 상태로 INSERT (UNIQUE 위반이면 이미 들어온 요청)
        IdempotencyKey reserved;
        try {
            reserved = idempotencyKeyRepository.saveAndFlush(IdempotencyKey.processing(idempotencyKey, requestHash));
        } catch (DataIntegrityViolationException e) {
            return handleDuplicate(idempotencyKey, requestHash);
        }
        log.info("[OrderService] 키 선점 성공, 주문 처리 시작: {}", idempotencyKey);

        try {
            // 2. 처리 지연 시뮬레이션 (3초)
            sleep(3000);

            // 3. 주문 저장 + 응답 저장(COMPLETED)을 하나의 트랜잭션으로
            return transactionTemplate.execute(status -> {
                Order saved = orderRepository.save(new Order(request.productName(), request.quantity()));
                OrderResponse response = OrderResponse.from(saved);
                reserved.complete(HttpStatus.OK.value(), jsonMapper.writeValueAsString(response));
                idempotencyKeyRepository.save(reserved);
                return response;
            });
        } catch (RuntimeException e) {
            // 처리 실패 시 키를 지워서 같은 키로 다시 시도할 수 있게 한다
            idempotencyKeyRepository.delete(reserved);
            throw e;
        }
    }

    private OrderResponse handleDuplicate(String idempotencyKey, String requestHash) {
        IdempotencyKey existing = idempotencyKeyRepository.findByKey(idempotencyKey)
                .orElseThrow(() -> new IdempotencyConflictException(idempotencyKey));

        if (!existing.getRequestHash().equals(requestHash)) {
            log.info("[OrderService] 같은 키, 다른 요청 본문 → 422: {}", idempotencyKey);
            throw new IdempotencyKeyMismatchException(idempotencyKey);
        }

        if (existing.getStatus() == IdempotencyStatus.PROCESSING) {
            log.info("[OrderService] 중복 요청 - 아직 처리 중 → 409: {}", idempotencyKey);
            throw new IdempotencyConflictException(idempotencyKey);
        }

        log.info("[OrderService] 중복 요청 - 이미 완료 → 저장된 응답 반환: {}", idempotencyKey);
        return jsonMapper.readValue(existing.getResponseBody(), OrderResponse.class);
    }

    private String hash(OrderRequest request) {
        try {
            String canonical = request.productName() + ":" + request.quantity();
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("주문 처리 중 인터럽트가 발생했습니다.", e);
        }
    }
}
