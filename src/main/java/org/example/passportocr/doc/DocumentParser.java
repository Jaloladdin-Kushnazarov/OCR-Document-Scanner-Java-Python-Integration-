package org.example.passportocr.doc;

import org.example.passportocr.dto.BaseDocumentDto;

public interface DocumentParser {
    BaseDocumentDto parse(String text);
}
