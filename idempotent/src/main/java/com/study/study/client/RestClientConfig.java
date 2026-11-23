package com.study.study.client;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient orderRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 응답 대기 시간 1초 (서버는 3초 걸리므로 매번 타임아웃 발생)
        factory.setReadTimeout(Duration.ofSeconds(1));

        return RestClient.builder()
                .baseUrl("http://localhost:8080")
                .requestFactory(factory)
                .build();
    }
}
