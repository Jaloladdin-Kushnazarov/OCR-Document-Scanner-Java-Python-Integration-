package org.example.passportocr.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ocr.python")
public record OcrPythonProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Duration responseTimeout,
        int maxConcurrency
) { }
