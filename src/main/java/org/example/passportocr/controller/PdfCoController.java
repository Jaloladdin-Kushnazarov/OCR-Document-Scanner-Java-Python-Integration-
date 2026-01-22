package org.example.passportocr.controller;

import lombok.RequiredArgsConstructor;
import org.example.passportocr.servise.PdfCoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
public class PdfCoController {

    private final PdfCoService pdfCoService;

    @PostMapping(value = "/scan-to-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<String>> convert(@RequestPart("file") MultipartFile file) {
        String outputName = "scanned-output.pdf";
        return pdfCoService.convertImageToPdf(file, outputName)
                .map(response -> ResponseEntity.ok().body(response))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Xatolik: " + e.getMessage())));
    }
}