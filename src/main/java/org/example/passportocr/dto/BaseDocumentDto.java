package org.example.passportocr.dto;

import org.example.passportocr.enums.DocumentType;

import lombok.*;


@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseDocumentDto {
    private DocumentType documentType;
    private String text;
}
