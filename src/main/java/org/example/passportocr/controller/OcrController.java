package org.example.passportocr.controller;

import lombok.RequiredArgsConstructor;
import org.example.passportocr.dto.BaseDocumentDto;
import org.example.passportocr.servise.OCRService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ocr")
public class OcrController {

    private final OCRService ocrService;

    @PostMapping
    public BaseDocumentDto extract(@RequestParam MultipartFile file) throws IOException {
        String text = ocrService.extractTextFromImage(file);
        BaseDocumentDto parse = ocrService.parse(text);
        return parse;
    }
}
