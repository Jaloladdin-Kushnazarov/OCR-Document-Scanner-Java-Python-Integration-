package org.example.passportocr.doc;

import org.example.passportocr.dto.BaseDocumentDto;
import org.example.passportocr.dto.UnknownDocumentDto;
import org.springframework.stereotype.Service;

/**
 * Hujjat turi aniqlanmagan yoki matn yo'q bo'lgan holatlar uchun parser.
 * Foydalanuvchiga o'zbek tilida ogohlantirish xabari qaytaradi.
 */
@Service
public class UnknownParser implements DocumentParser {

    @Override
    public BaseDocumentDto parse(String text) {
        return new UnknownDocumentDto(text);
    }
}
