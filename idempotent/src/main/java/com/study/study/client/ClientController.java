package com.study.study.client;

import com.study.study.order.OrderRequest;
import com.study.study.order.OrderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 클라이언트 동작 확인용 진입점.
 * POST /client/orders 를 호출하면 OrderClient 가 POST /orders 를 (타임아웃 + 재시도로) 호출한다.
 */
@RestController
@RequestMapping("/client/orders")
@RequiredArgsConstructor
public class ClientController {

    private final OrderClient orderClient;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody OrderRequest request) {
        return ResponseEntity.ok(orderClient.createOrder(request));
    }
}
