package org.example.passportocr.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.example.passportocr.enums.DocumentType;

/**
 * Noma'lum hujjat yoki matn bor/yo'qligi uchun DTO.
 *
 * message maydoni:
 * - matn topilmasa: "Suratda matn aniqlanmadi"
 * - matn bor, hujjat turi aniqlanmasa: "Hujjat turi aniqlanmadi"
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UnknownDocumentDto extends BaseDocumentDto {

    /** Foydalanuvchiga ko'rsatiladigan ogohlantirish xabari */
    private String message;

    /** Matn bor yoki yo'qligini ko'rsatadi */
    private boolean hasText;

    public UnknownDocumentDto() {
        this.setDocumentType(DocumentType.UNKNOWN);
    }

    public UnknownDocumentDto(String rawText) {
        this.setDocumentType(DocumentType.UNKNOWN);
        this.setText(rawText);
        boolean textExists = rawText != null && !rawText.isBlank();
        this.hasText = textExists;
        if (textExists) {
            this.message = "Hujjat turi aniqlanmadi. Surat hujjat emasmi yoki sifat past bo'lishi mumkin.";
        } else {
            this.message = "Suratda matn aniqlanmadi. Iltimos, hujjatni aniqroq suratga oling.";
        }
    }
}