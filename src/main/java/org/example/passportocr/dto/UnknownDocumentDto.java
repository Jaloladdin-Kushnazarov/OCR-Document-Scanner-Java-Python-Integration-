package org.example.passportocr.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.example.passportocr.enums.DocumentType;

@Data
@EqualsAndHashCode(callSuper = true)
public class UnknownDocumentDto extends BaseDocumentDto {

    public UnknownDocumentDto(String text) {
        this.setDocumentType(DocumentType.UNKNOWN);
        this.setText(text);
    }

    public UnknownDocumentDto() {
        this.setDocumentType(DocumentType.UNKNOWN);
    }
}