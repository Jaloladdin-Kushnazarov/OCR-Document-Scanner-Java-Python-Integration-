package org.example.passportocr.doc;

import lombok.RequiredArgsConstructor;
import org.example.passportocr.enums.DocumentType;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DocumentParserRegistry {

    private final IdCardParserAdapter idCard;
    private final PassportParserAdapter passport;
    private final DrivingLicenseParserAdapter dl;
    private final UnknownParser unknown;

    public DocumentParser parser(DocumentType type) {
        return switch (type) {
            case ID_CARD -> idCard;
            case PASSPORT -> passport;
            case DRIVER_LICENSE -> dl;
            default -> unknown;
        };
    }
}
