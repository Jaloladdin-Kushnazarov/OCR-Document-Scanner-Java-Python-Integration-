package org.example.passportocr.doc;

import lombok.RequiredArgsConstructor;
import org.example.passportocr.dto.BaseDocumentDto;
import org.example.passportocr.servise.DrivingLicenseParserService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DrivingLicenseParserAdapter implements DocumentParser {
    private final DrivingLicenseParserService service;

    @Override
    public BaseDocumentDto parse(String text) {
        return service.parse(text);
    }
}
