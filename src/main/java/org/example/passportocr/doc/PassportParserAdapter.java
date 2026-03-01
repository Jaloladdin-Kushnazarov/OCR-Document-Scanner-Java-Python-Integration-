package org.example.passportocr.doc;

import lombok.RequiredArgsConstructor;
import org.example.passportocr.dto.BaseDocumentDto;
import org.example.passportocr.servise.PassportParserService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PassportParserAdapter implements DocumentParser {
    private final PassportParserService service;

    @Override
    public BaseDocumentDto parse(String text) {
        return service.parse(text);
    }
}
