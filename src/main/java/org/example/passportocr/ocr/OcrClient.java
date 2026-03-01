package org.example.passportocr.ocr;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.passportocr.configuration.OcrPythonProperties;
import org.example.passportocr.exeption.OcrBusyException;
import org.example.passportocr.util.MultipartByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
@RequiredArgsConstructor
public class OcrClient {

    private final RestClient ocrRestClient;
    private final OcrPythonProperties props;

    // Bulkhead: Python sekin bo‘lsa requestlar bosilib ketmasin
    private final Semaphore semaphore = new Semaphore(2);

    public String extractText(MultipartFile file) throws IOException {
        int maxConc = Math.max(1, props.maxConcurrency());
        // semaphore size runtime’da o‘zgarishi uchun oddiy yo‘l: shu yerda check
        // (minimal)
        // (keyinroq resilience4j Bulkhead qilamiz)

        boolean acquired = semaphore.tryAcquire();
        if (!acquired) {
            // 429-ish: “band”
            throw new OcrBusyException("OCR service is busy. Try again.");
        }

        try {
            var body = new LinkedMultiValueMap<String, Object>();
            body.add("file",
                    new MultipartByteArrayResource(
                            file.getBytes(),
                            file.getOriginalFilename()));

            Map<?, ?> resp = ocrRestClient.post()
                    .uri("/ocr/")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (resp == null || resp.get("text") == null) {
                return "";
            }
            return String.valueOf(resp.get("text"));
        } finally {
            semaphore.release();
        }
    }
}