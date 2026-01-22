package org.example.passportocr.servise;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PdfCoService {

    @Value("${pdfco.api.key}")
    private String apiKey;

    private final WebClient webClient = WebClient.builder()
            .baseUrl("https://api.pdf.co/v1")
            .defaultHeader("x-api-key", "SIZNING_API_KALITINGIZ")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();

    public Mono<String> convertImageToPdf(MultipartFile file, String outputName) {
        try {
            byte[] bytes = file.getBytes();
            String base64 = Base64.getEncoder().encodeToString(bytes);

            Map<String, Object> requestBody = Map.of(
                    "name", outputName,
                    "file", base64
            );

            return webClient.post()
                    .uri("/pdf/convert/from/image")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class);
        } catch (IOException e) {
            return Mono.error(new RuntimeException("Faylni o'qishda xatolik: " + e.getMessage()));
        }
    }
}