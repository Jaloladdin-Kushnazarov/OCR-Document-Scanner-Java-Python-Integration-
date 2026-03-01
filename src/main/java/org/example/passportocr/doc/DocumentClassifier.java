package org.example.passportocr.doc;

import org.example.passportocr.enums.DocumentType;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * OCR matndan hujjat turini aniqlaydi.
 *
 * Har bir hujjat turi uchun ball (score) hisoblanadi va
 * eng yuqori ball olgan tur qaytariladi.
 * Hech qaysi tur mablag' chegarasidan o'tmasa — UNKNOWN.
 *
 * Bu yondashuv qattiq contains() dan farqli o'laroq
 * OCR xatoliklariga chidamli.
 */
@Service
public class DocumentClassifier {

    // Minimum ball — bu chegaradan past bo'lsa UNKNOWN
    private static final double THRESHOLD = 2.0;

    // ---------------------------------------------------------------
    // ID Karta keywords
    // ---------------------------------------------------------------
    private static final String[] ID_REQUIRED = {
            "SHAXS GUVOHNOMASI", "IDENTITY CARD",
            "SHAXS GUVOHNOMAS", // OCR qisqa variant
    };
    private static final String[] ID_BONUS = {
            "KARTA RAQAMI", "CARD NUMBER",
            "FAMILIYASI", "SURNAME", "PATRONYMIC", "OTASINING ISMI",
            "ERKAK", "AYOL", "FUQAROLIGI",
    };

    // ---------------------------------------------------------------
    // Passport keywords
    // ---------------------------------------------------------------
    private static final String[] PASSPORT_REQUIRED = {
            "PASSPORT", "PASPORT",
            "P<UZB", // MRZ
    };
    private static final String[] PASSPORT_BONUS = {
            "PASSPORT NO", "PASSPORT RAQAMI",
            "COUNTRY CODE", "PLACE OF BIRTH", "AUTHORITY",
            "TUG'ILGAN JOYI", "TUGILGAN JOYI",
            "DATE OF EXPIRY", "DATE OF ISSUE",
            "FUQAROLIGI / CITIZENSHIP",
    };
    // MRZ pattern
    private static final Pattern MRZ_PATTERN = Pattern.compile("P<UZB[A-Z]+<<[A-Z]+");

    // ---------------------------------------------------------------
    // Haydovchilik guvohnomasi keywords
    // ---------------------------------------------------------------
    private static final String[] DL_REQUIRED = {
            "HAYDOVCHILIK GUVOHNOMASI", "DRIVING LICENCE", "DRIVING LICENSE",
            "HAYDOVCHILIK GUVOHNOMAS", // OCR qisqa variant
            "VODITELSKOE UDOSTOVERENIE",
            "ВОДИТЕЛЬСКОЕ УДОСТОВЕРЕНИЕ", // Kirill
    };
    private static final String[] DL_BONUS = {
            "HAYDOVCHILIK", "DRIVING", "LICENCE", "LICENSE",
            "VODITELSKOE", "UDOSTOVERENIE",
    };
    // PINFL (14 raqam) — haydovchilik uchun kuchli signal
    private static final Pattern PINFL_PATTERN = Pattern.compile("\\b\\d{14}\\b");

    // ---------------------------------------------------------------
    // classify() — asosiy metod
    // ---------------------------------------------------------------
    public DocumentType classify(String rawText) {
        if (rawText == null || rawText.isBlank())
            return DocumentType.UNKNOWN;

        String upper = rawText.toUpperCase()
                .replaceAll("[''´`ʼ]", "'")
                .replaceAll("0'(?=[A-Z])", "O'");

        double idScore = scoreIdCard(upper);
        double passportScore = scorePassport(upper);
        double dlScore = scoreDrivingLicense(upper);

        double maxScore = Math.max(idScore, Math.max(passportScore, dlScore));

        if (maxScore < THRESHOLD)
            return DocumentType.UNKNOWN;

        if (idScore >= passportScore && idScore >= dlScore)
            return DocumentType.ID_CARD;
        if (passportScore >= idScore && passportScore >= dlScore)
            return DocumentType.PASSPORT;
        return DocumentType.DRIVER_LICENSE;
    }

    // ---------------------------------------------------------------
    // Scoring metodlar
    // ---------------------------------------------------------------
    private double scoreIdCard(String t) {
        double score = 0;
        // Asosiy kalit so'zlar
        for (String kw : ID_REQUIRED) {
            if (t.contains(kw))
                score += 4.0;
        }
        // Qo'shimcha kalit so'zlar
        for (String kw : ID_BONUS) {
            if (t.contains(kw))
                score += 1.0;
        }
        return score;
    }

    private double scorePassport(String t) {
        double score = 0;
        for (String kw : PASSPORT_REQUIRED) {
            if (t.contains(kw))
                score += 4.0;
        }
        for (String kw : PASSPORT_BONUS) {
            if (t.contains(kw))
                score += 1.0;
        }
        // MRZ aniq topilsa juda kuchli signal
        if (MRZ_PATTERN.matcher(t).find())
            score += 6.0;
        return score;
    }

    private double scoreDrivingLicense(String t) {
        double score = 0;
        for (String kw : DL_REQUIRED) {
            if (t.contains(kw))
                score += 4.0;
        }
        for (String kw : DL_BONUS) {
            if (t.contains(kw))
                score += 1.0;
        }
        // PINFL (14 raqam) — haydovchilik uchun kuchli signal
        if (PINFL_PATTERN.matcher(t).find())
            score += 4.0;
        // "1. FAMILIYA 2. ISM" tuzilishi haydovchilik uchun xarakterli
        if (t.matches("(?s).*\\b1\\.\\s+[A-Z].+\\b2\\.\\s+[A-Z].*"))
            score += 2.0;
        return score;
    }
}
