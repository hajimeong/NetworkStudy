package com.study.study.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.study.study.idempotency.IdempotencyKey;
import com.study.study.idempotency.IdempotencyKeyRepository;
import com.study.study.idempotency.IdempotencyStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 가상 스레드 100개가 동일한 Idempotency-Key + 동일한 요청 본문으로
 * 동시에 POST /orders 를 호출했을 때 주문이 1건만 저장되는지 검증한다.
 *
 * 실제 서버(랜덤 포트) + 실제 MySQL(docker) 을 사용하는 통합 테스트.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderIdempotencyConcurrencyTest {

    private static final int THREAD_COUNT = 100;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        // 타임아웃 없는 클라이언트 (서버의 3초 처리 시간을 그대로 기다림)
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
    }

    @Test
    void 동일한_멱등성키로_100개_동시요청시_주문은_1건만_저장된다() throws Exception {
        // given
        String idempotencyKey = UUID.randomUUID().toString();               // 동일한 멱등성 키
        OrderRequest request = new OrderRequest("Keyboard", 1);  // 동일한 요청 본문
        long ordersBefore = orderRepository.count();

        CountDownLatch ready = new CountDownLatch(THREAD_COUNT); // 100개 스레드가 모두 준비될 때까지 대기
        CountDownLatch start = new CountDownLatch(1);            // 동시에 출발시키는 신호
        Map<Integer, AtomicInteger> statusCounts = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();

        // when: 가상 스레드 100개 생성 → 동시에 POST /orders 호출
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < THREAD_COUNT; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    int status = postOrder(idempotencyKey, request);
                    statusCounts.computeIfAbsent(status, s -> new AtomicInteger()).incrementAndGet();
                    return null;
                }));
            }
            ready.await();
            start.countDown(); // 출발!
            for (Future<?> f : futures) {
                f.get(); // 스레드 안에서 예외가 났으면 여기서 테스트 실패
            }
        }

        // then
        System.out.println("[동시성 테스트] 응답 상태코드별 개수: " + statusCounts);
        long ordersAfter = orderRepository.count();
        System.out.println("[동시성 테스트] orders: " + ordersBefore + " → " + ordersAfter);

        // 1) 정확히 1개만 처리(200), 나머지 99개는 처리 중 409
        assertThat(count(statusCounts, 200)).isEqualTo(1);
        assertThat(count(statusCounts, 409)).isEqualTo(THREAD_COUNT - 1);

        // 2) 주문은 1건만 저장
        assertThat(ordersAfter - ordersBefore).isEqualTo(1);

        // 3) 멱등성 키는 1건, COMPLETED 상태
        IdempotencyKey saved = idempotencyKeyRepository.findByKey(idempotencyKey).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
    }

    private int postOrder(String idempotencyKey, OrderRequest request) {
        return restClient.post()
                .uri("/orders")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                // 4xx/5xx 도 예외 없이 상태코드만 받기
                .exchange((req, res) -> res.getStatusCode().value());
    }

    private int count(Map<Integer, AtomicInteger> statusCounts, int status) {
        AtomicInteger c = statusCounts.get(status);
        return c == null ? 0 : c.get();
    }
}
