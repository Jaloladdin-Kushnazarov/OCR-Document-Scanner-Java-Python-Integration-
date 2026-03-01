package org.example.passportocr.doc;

import lombok.RequiredArgsConstructor;
import org.example.passportocr.dto.BaseDocumentDto;
import org.example.passportocr.servise.IDCardParserService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IdCardParserAdapter implements DocumentParser {
    private final IDCardParserService service;

    @Override
    public BaseDocumentDto parse(String text) {
        return service.parse(text);
    }
}
