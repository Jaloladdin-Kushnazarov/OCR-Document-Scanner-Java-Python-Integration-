package org.example.passportocr.servise;

import lombok.RequiredArgsConstructor;
import org.example.passportocr.dto.BaseDocumentDto;
import org.example.passportocr.dto.IdCardDto;
import org.example.passportocr.enums.DocumentType;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * O'zbekiston shaxs guvohnomasi (ID karta) parseri.
 *
 * Asosiy qiyinchiliklar:
 * 1. OCR label bilan qiymatni ba'zan `|`, `'`, `}` bilan ajratadi
 * 2. Sana vergul bilan: "25,12.1998" → normalizedan o'tkaziladi
 * 3. Familiya boshi buzilishi mumkin: "00'SHNAZAROV" → fallback kerak
 */
@Service
@RequiredArgsConstructor
public class IDCardParserService {

    // Sana formati — vergul, nuqta, slash, chiziq va bo'shliq kabi variantlar
    private static final Pattern DATE_PAT = Pattern.compile(
            "(\\d{2})[.,/\\- ](\\d{2})[.,/\\- ](\\d{4})");

    // Karta raqami: AE2211557 yoki AA 1234567 kabi
    private static final Pattern CARD_NO_PAT = Pattern.compile(
            "\\b([A-Z]{2})\\s?(\\d{7})\\b");

    // Patronymic suffixlari
    private static final Pattern PATRONYMIC_SUFFIX = Pattern.compile(
            "\\b(?:O['`]G['`]LI|QIZI|OVICH|EVICH|OVNA|EVNA|UGLI|KIZI)\\b");

    public BaseDocumentDto parse(String rawText) {
        IdCardDto dto = new IdCardDto();
        dto.setDocumentType(DocumentType.ID_CARD);
        dto.setText(rawText);

        String t = normalize(rawText);

        dto.setFamiliya(extractFamiliya(t));
        dto.setIsm(extractIsm(t));
        dto.setOtasiningIsmi(extractOtasiningIsmi(t));
        dto.setTugilganSana(extractDate(t, "TUGILGAN SANASI", "DATE OF BIRTH", "DATE OF BINTH"));
        dto.setBerilganSana(extractDate(t, "BERILGAN SANASI", "DATE OF ISSUE"));
        dto.setAmalQilishMuddati(extractDate(t, "AMAL QILISH MUDDATI", "DATE OF EXPIRY", "DATE OF EXPITY"));
        dto.setJinsi(extractGender(t));
        dto.setFuqaroligi(extractCitizenship(t));
        dto.setKartaRaqami(extractCardNumber(t));

        return dto;
    }

    // ---------------------------------------------------------------
    // Normalize — OCR xatolarini tuzatish
    // ---------------------------------------------------------------
    private String normalize(String text) {
        if (text == null)
            return "";
        return text.toUpperCase()
                // apostrof variantlarini birlashtirish
                .replaceAll("[''´`ʼ]", "'")
                // Kirill O → Latin O, raqam 0 → harf O (apostrof oldidan)
                .replaceAll("О'", "O'")
                .replaceAll("\\b0'(?=[A-Z])", "O'")
                // OCR tez-tez "|" ni separator sifatida ishlatadi → " "
                .replace("|", " ")
                // OCR ba'zan "}" ni qo'yadi → o'chiramiz
                .replaceAll("[{}]", " ")
                // "00'S..." → "QO'S..." kabi boshini to'g'rilash (OCR QO → 00 ko'rinishida)
                .replaceAll("\\b00'(?=S|N|Z)", "QO'")
                // Sana normalizatsiya: "25,12.1998" → "25.12.1998"; "25 12 1998"
                .replaceAll("(\\d{2})[,](\\d{2})[.,/](\\d{4})", "$1.$2.$3")
                .replaceAll("(\\d{2})[./](\\d{2})[./](\\d{4})", "$1.$2.$3")
                // Bo'shliqli sana: "2 5 . 12 . 1 9 9 8" kabi hatolarni qo'lda yozdim
                .replaceAll("(\\d)\\s*\\.\\s*(\\d{2})[./](\\d{2})[./](\\d{4})", "$1. $2.$3.$4")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // ---------------------------------------------------------------
    // Familiya — label dan keyin birinchi so'z
    // ---------------------------------------------------------------
    private String extractFamiliya(String t) {
        // Label variantlari: `Surname`, `Surnaimg`, `SURNAME`, `FAMILIYASI`,
        // `Fumniliyasi`, va h.k.
        // OCR ko'p xato kiritadi shuning uchun "SURNA" → kamida 5 harf bilan qidirish
        String[] regexLabels = {
                // Standart
                "(?:FAMILIYASI\\s*/\\s*SURNAME|FAMILIYASI/SURNAME|FAMILIYASI\\s+SURNAME)",
                // OCR xato variantlar
                "(?:FUMNILIYASI|FAMNILIYASI|FAMILYASI)\\s*[}/|]?\\s*SURNA[A-Z]*",
                "SURNA[A-Z]*\\s*(?:QO['`]SHNAZAROV|[A-Z0-9'\\-]{3,})",
                "SURNAME",
                "FAMILIYASI",
                "SURNA[A-Z]*"
        };

        for (String regex : regexLabels) {
            Pattern p = Pattern.compile(regex + "\\s+([A-Z][A-Z0-9'\\-]{1,35})");
            Matcher m = p.matcher(t);
            if (m.find()) {
                String cand = m.group(1).trim();
                // Raqam bilan boshlansa skip
                if (!cand.matches("\\d.*"))
                    return capitalizeWords(cand);
            }
        }

        // Fallback: OCR yuqori satrdagi "Surname" keyin katta harfli so'zni olish
        Pattern fallback = Pattern.compile("SURNA[A-Z]*\\s+([A-Z][A-Z'\\-]{2,30})");
        Matcher fm = fallback.matcher(t);
        if (fm.find())
            return capitalizeWords(fm.group(1).trim());

        return null;
    }

    // ---------------------------------------------------------------
    // Ismi — "Ismi / Given name(s)" dan keyin qiymat
    // OCR ba'zan "name(s)'" yoki "naiels)" deb o'qiydi
    // ---------------------------------------------------------------
    private String extractIsm(String t) {
        // Variant 1: ISMI / GIVEN NAME(S) JALOLADDIN
        // Variant 2: ISMI | GIVEN NAME(S) JALOLADDIN → | ni normalize da olib tashladik
        // Variant 3: ISM | Given naiels) JALOLADDIN

        String[] patterns = {
                // Standart
                "ISMI\\s*/\\s*GIVEN\\s+NAME\\S*\\s+([A-Z][A-Z'\\- ]{1,30}?)(?=\\s{2,}|\\s+[A-Z]{4,}\\s*/|\\s*ATAS|$)",
                // OCR "GIVEN" o'rniga boshqa narsa yozsa ham "NAME" bor
                "ISMI\\s+GIVEN\\s+NAME\\S*\\s+([A-Z][A-Z'\\- ]{1,30}?)(?=\\s{2,}|\\s+[A-Z]{4,}\\s*/|$)",
                "GIVEN\\s+NAME\\S*\\s+([A-Z][A-Z'\\- ]{1,30}?)(?=\\s{2,}|\\s+[A-Z]{4,}\\s*/|\\s*ATAS|$)",
                // OCR "naiels)" yoki "namels)" kabi xatolar
                "ISMI\\s+[A-Z]+\\s+[A-Z]+\\S*\\s+([A-Z][A-Z'\\- ]{1,30}?)(?=\\s+[A-Z]{4,}|$)"
        };

        for (String pat : patterns) {
            Pattern p = Pattern.compile(pat);
            Matcher m = p.matcher(t);
            if (m.find()) {
                String cand = m.group(1).trim();
                // Bir so'z bo'lishi kerak — patronymic suffix bo'lmasin
                if (!PATRONYMIC_SUFFIX.matcher(cand).find())
                    return capitalizeWords(cand);
            }
        }

        // Fallback: "ISMI" labelidan keyingi birinchi katta so'z (SHORT)
        int ismiIdx = t.indexOf("ISMI");
        if (ismiIdx != -1) {
            String slice = t.substring(ismiIdx, Math.min(t.length(), ismiIdx + 100));
            // Birinchi ALL-CAPS so'z (min 4 harf, patronymic suffix emas)
            Matcher wm = Pattern.compile("\\b([A-Z]{4,25})\\b").matcher(slice.substring(4));
            while (wm.find()) {
                String cand = wm.group(1);
                // Uzun label so'zlarini o'tkaz
                if (!PATRONYMIC_SUFFIX.matcher(cand).find()
                        && !cand.matches("GIVEN|NAME|ISMI|NAMES|ATAS|OTASI")) {
                    return capitalizeWords(cand);
                }
            }
        }

        return null;
    }

    // ---------------------------------------------------------------
    // Otasining ismi — patronymic
    // ---------------------------------------------------------------
    private String extractOtasiningIsmi(String t) {
        String[] labels = {
                "OTASINING ISMI / PATRONYMIC", "OTASINING ISMI/PATRONYMIC",
                "PATRONYMIC", "OTASINING ISMI", "ATASINING ISMI"
        };

        for (String lbl : labels) {
            int idx = t.indexOf(lbl);
            if (idx != -1) {
                String slice = t.substring(idx + lbl.length(), Math.min(t.length(), idx + lbl.length() + 80)).trim();
                // "KOMIL O'G'LI" yoki "SHODIEVA"
                Pattern p = Pattern.compile("([A-Z][A-Z'\\- ]{1,40})");
                Matcher m = p.matcher(slice);
                if (m.find())
                    return capitalizeWords(m.group(1).trim());
            }
        }

        // Fallback: patronymic suffix bilan so'zni qidirish
        Pattern suffixPat = Pattern.compile("([A-Z]{3,25}\\s+(?:O'G'LI|QIZI|OVICH|EVICH|OVNA|EVNA|UGLI|KIZI))");
        Matcher sm = suffixPat.matcher(t);
        if (sm.find())
            return capitalizeWords(sm.group(1));

        return null;
    }

    // ---------------------------------------------------------------
    // Sana — keyword dan keyin qiymat
    // ---------------------------------------------------------------
    private String extractDate(String t, String... keywords) {
        for (String kw : keywords) {
            int idx = t.indexOf(kw.toUpperCase());
            if (idx == -1)
                continue;
            String slice = t.substring(idx + kw.length(), Math.min(t.length(), idx + kw.length() + 80));
            Matcher dm = DATE_PAT.matcher(slice);
            if (dm.find())
                return formatDate(dm);
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Jinsi
    // ---------------------------------------------------------------
    private String extractGender(String t) {
        if (t.contains("ERKAK"))
            return "Erkak";
        if (t.contains("AYOL"))
            return "Ayol";
        int idx = t.indexOf("JINSI");
        if (idx == -1)
            idx = t.indexOf("SEX");
        if (idx != -1) {
            String slice = t.substring(idx, Math.min(t.length(), idx + 30));
            Matcher m = Pattern.compile("\\b([MF])\\b").matcher(slice);
            if (m.find())
                return m.group(1).equals("M") ? "Erkak" : "Ayol";
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Fuqaroligi
    // ---------------------------------------------------------------
    private String extractCitizenship(String t) {
        String[] variants = { "O'ZBEKISTON", "OZBEKISTON", "UZBEKISTAN", "O`ZBEKISTON", "0'ZBEKISTON" };
        int idx = t.indexOf("FUQAROLIGI");
        if (idx == -1)
            idx = t.indexOf("CITIZENSHIP");
        if (idx != -1) {
            String slice = t.substring(idx, Math.min(t.length(), idx + 60));
            for (String v : variants)
                if (slice.contains(v))
                    return "O'ZBEKISTON";
        }
        for (String v : variants)
            if (t.contains(v))
                return "O'ZBEKISTON";
        return null;
    }

    // ---------------------------------------------------------------
    // Karta raqami
    // ---------------------------------------------------------------
    private String extractCardNumber(String t) {
        String[] labels = { "KARTA RAQAMI", "CARD NUMBER" };
        for (String lbl : labels) {
            int idx = t.indexOf(lbl);
            if (idx != -1) {
                String slice = t.substring(idx, Math.min(t.length(), idx + 40));
                Matcher m = CARD_NO_PAT.matcher(slice);
                if (m.find())
                    return m.group(1) + m.group(2);
            }
        }
        Matcher m = CARD_NO_PAT.matcher(t);
        while (m.find()) {
            String full = m.group(1) + m.group(2);
            if (full.length() == 9)
                return full;
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Yordamchi
    // ---------------------------------------------------------------
    private String formatDate(Matcher dm) {
        return dm.group(1) + "." + dm.group(2) + "." + dm.group(3);
    }

    private String capitalizeWords(String input) {
        if (input == null || input.isBlank())
            return null;
        return Arrays.stream(input.toLowerCase().split("\\s+"))
                .filter(w -> !w.isBlank())
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(Collectors.joining(" "));
    }
}
