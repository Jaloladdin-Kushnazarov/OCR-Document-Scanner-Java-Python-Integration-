//package org.example.passportocr.configuration;
//
//import org.springframework.beans.factory.annotation.Configurable;
//import org.springframework.boot.web.client.RestTemplateBuilder;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.web.client.RestTemplate;
//
//import java.time.Duration;
//
//@Configuration
//public class WebConfig {
//
//    @Bean
//    public RestTemplate restTemplate(RestTemplateBuilder builder) {
//        return builder
//                .connectTimeout(Duration.ofSeconds(5))
//                .readTimeout(Duration.ofSeconds(120))
//                .build();
//    }
//}
