package org.example.passportocr.app;


import lombok.RequiredArgsConstructor;
import org.example.passportocr.doc.DocumentClassifier;
import org.example.passportocr.doc.DocumentParserRegistry;
import org.example.passportocr.dto.BaseDocumentDto;
import org.example.passportocr.enums.DocumentType;
import org.example.passportocr.ocr.OcrClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class OcrApplicationService {

    private final OcrClient ocrClient;
    private final DocumentClassifier classifier;
    private final DocumentParserRegistry registry;

    public BaseDocumentDto extractAndParse(MultipartFile file) throws IOException {
        String text = ocrClient.extractText(file);
        DocumentType type = classifier.classify(text);
        return registry.parser(type).parse(text);
    }
}
