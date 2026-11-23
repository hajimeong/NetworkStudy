package com.study.study.order;

public record OrderResponse(Long orderId, String productName, Integer quantity) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.getId(), order.getProductName(), order.getQuantity());
    }
}
