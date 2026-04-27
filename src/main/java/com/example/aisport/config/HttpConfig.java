package com.example.aisport.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Configuration
public class HttpConfig {

    @Value("${ai.python.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${ai.python.timeout-ms:300000}")
    private int readTimeoutMs;

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        ClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        ((SimpleClientHttpRequestFactory) factory).setConnectTimeout(connectTimeoutMs);
        ((SimpleClientHttpRequestFactory) factory).setReadTimeout(readTimeoutMs);

        return builder
                .requestFactory(() -> factory)
                .build();
    }
}
