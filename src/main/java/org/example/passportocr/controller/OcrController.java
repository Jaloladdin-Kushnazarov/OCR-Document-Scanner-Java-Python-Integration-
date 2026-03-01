package org.example.passportocr.controller;


import lombok.RequiredArgsConstructor;
import org.example.passportocr.app.OcrApplicationService;
import org.example.passportocr.dto.BaseDocumentDto;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ocr")
public class OcrController {

    private final OcrApplicationService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BaseDocumentDto extract(@RequestPart("file") MultipartFile file) throws IOException {
        return service.extractAndParse(file);
    }
}
