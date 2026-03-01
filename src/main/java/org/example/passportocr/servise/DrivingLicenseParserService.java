package org.example.passportocr.servise;

import lombok.RequiredArgsConstructor;
import org.example.passportocr.dto.BaseDocumentDto;
import org.example.passportocr.dto.DrivingLicenseDto;
import org.example.passportocr.enums.DocumentType;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * O'zbekiston haydovchilik guvohnomasidan ma'lumot ajratuvchi servis.
 *
 * Hujjat maydoni tuzilishi (raqamli belgilar):
 * 1. Familiya
 * 2. Ism + otasining ismi (keyingi satrda bo'lishi mumkin)
 * 3. Tug'ilgan joyi va sanasi "XONQA 25.12.1998"
 * 4a. Berilgan sanasi
 * 4b. Amal qilish muddati
 * 4c. Berilgan joyi (viloyat / muassasa)
 * 4d. PINFL — 14 raqam
 * 5. Guvohnoma raqami (AF2709057 kabi)
 * 8. Yashash manzili
 * 9. Toifa (A, B, C ...)
 *
 * OCR xatolari:
 * - "4а"/"4с"/"4d" → Kirill harflari → normalizatsiyada Latin ga almashtiriladi
 * - "0'G'LI" → raqam sifr, O' harfi emas → almashtirish
 * - viloyat nomlari field 2 va 8 ga aralashadi → olib tashlanadi
 * - "25.12 1998" → bo'shliqli sana → normalizatsiyada tuzatiladi
 * - "В" (Kirill) → "B" (Latin) → toifa uchun
 */
@Service
@RequiredArgsConstructor
public class DrivingLicenseParserService {

    // Sana — 01.01.2024 yoki 01/01/2024 yoki "25.12 1998" (bo'shliq bilan)
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{2})[./\\-](\\d{2})[./\\- ](\\d{4})");

    // PINFL — aniq 14 raqam
    private static final Pattern PINFL_PATTERN = Pattern.compile("\\b(\\d{14})\\b");

    // Guvohnoma raqami: 2 harf + 7 raqam
    private static final Pattern LICENSE_PATTERN = Pattern.compile("\\b([A-Z]{2}\\s?\\d{7})\\b");

    // Patronymic suffix — `0'G'LI` (sifr bilan) ham qamrab oladi
    private static final Pattern PATRONYMIC_PAT = Pattern.compile(
            "([A-Z][A-Z']{2,20}\\s+(?:O'G'LI|0'G'LI|QIZI|OVICH|EVICH|OVNA|EVNA|UGLI|KIZI))\\s*$",
            Pattern.CASE_INSENSITIVE);

    // O'zbekiston viloyatlari va tumanlari — ISM va MANZIL dan olib tashlash uchun
    private static final Pattern REGION_PAT = Pattern.compile(
            "\\b(?:QORAQALPOG'ISTON|ANDIJON|BUXORO|JIZZAX|QASHQADARYO|NAVOIY(?:VILOYATI)?|NAMANGAN|" +
                    "SAMARQAND|SIRDARYO|SURXONDARYO|TOSHKENT|FARG'ONA|XORAZM|RESPUBLIKASI|" +
                    "ANDWDN|WILUYATI|BUXIRQ|WILIYATI|ISTON|DLOOO\\w+)" +
                    "(?:\\s+(?:VILOYATI|WILOYATI|SHAHRI|TUMANI|QISMI|RESPUBLIKASI))?",
            Pattern.CASE_INSENSITIVE);

    public BaseDocumentDto parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            DrivingLicenseDto dto = new DrivingLicenseDto();
            dto.setDocumentType(DocumentType.DRIVER_LICENSE);
            return dto;
        }

        String t = normalize(rawText);

        DrivingLicenseDto dto = new DrivingLicenseDto();
        dto.setDocumentType(DocumentType.DRIVER_LICENSE);
        dto.setText(rawText);

        dto.setFamiliya(extractField1(t));
        dto.setIsm(extractIsm(t));
        dto.setOtasiningIsmi(extractPatronymic(t));
        dto.setTugilganJoy(extractBirthPlace(t));
        dto.setTugilganSana(extractBirthDate(t));
        dto.setBerilganSana(extractField4a(t));
        dto.setAmalQilishMuddati(extractField4b(t));
        dto.setBerilganJoy(extractField4c(t));
        dto.setPinfl(extractPinfl(t));
        dto.setGuvohnomaRaqami(extractLicenseNumber(t));
        dto.setManzil(extractField8(t));
        dto.setToifa(extractField9(t));

        return dto;
    }

    // ---------------------------------------------------------------
    // Normalize — barcha Kirill harflarini va OCR xatolarini tuzatish
    // ---------------------------------------------------------------
    private String normalize(String text) {
        return text
                // Kirill field harflarini Latin ga o'tkazish: а→a, с→c, d→d (u allaqachon
                // Latin)
                .replace("4а", "4a").replace("4А", "4a") // Kirill а
                .replace("4с", "4c").replace("4С", "4c") // Kirill с
                .replace("4d", "4d").replace("4D", "4d") // harfni standard qilish
                .replace("4b", "4b").replace("4B", "4b")
                // Kirill "В" → Latin "B" (toifa uchun); "9 В" → "9 B"
                .replaceAll("(?<=\\b9[.\\s]\\s*)В\\b", "B")
                .replaceAll("\\bВ\\b", "B")
                // Kirill "С" → "C", "А" → "A" (toifa kategoriyalari uchun)
                .replaceAll("\\bС\\b", "C").replaceAll("\\bА\\b", "A")
                // Raqam 0 → harf O' (apostrof bilan)
                .replace("0'G'LI", "O'G'LI")
                .replace("0`G`LI", "O'G'LI")
                .replaceAll("\\b0'(?=[A-Z])", "O'")
                // Bo'shliqli sana: "25.12 1998" → "25.12.1998"
                .replaceAll("(\\d{2}[./]\\d{2})\\s+(\\d{4})", "$1.$2")
                // Sana yopishib ketganda: "25.121998" → "25.12.1998"
                .replaceAll("(\\d{2}[./]\\d{2})(\\d{4})", "$1.$2")
                // Bo'shliqlarni normallashtirish
                .replaceAll("\\s+", " ")
                .trim()
                .toUpperCase()
                .replaceAll("[''´`ʼ]", "'")
                .replaceAll("О'", "O'");
    }

    // ---------------------------------------------------------------
    // Field 1 — Familiya
    // ---------------------------------------------------------------
    private String extractField1(String t) {
        Pattern p = Pattern.compile("\\b1[.\\s]+([A-Z][A-Z'\\-]{2,30})\\b");
        Matcher m = p.matcher(t);
        if (m.find())
            return capitalizeWords(m.group(1));

        // "SURNAME" label fallback
        Matcher fb = Pattern.compile("(?:SURNAME|FAMILIYASI)\\s+([A-Z][A-Z'\\-]{2,30})").matcher(t);
        if (fb.find())
            return capitalizeWords(fb.group(1));
        return null;
    }

    // ---------------------------------------------------------------
    // Ism — Field 2 dan FAQAT ism (patronymic va viloyat nomlarisiz)
    // ---------------------------------------------------------------
    private String extractIsm(String t) {
        String raw = getRawField2(t);
        if (raw == null || raw.isBlank())
            return null;

        // Patronymicni olib tashlaymiz
        Matcher pm = PATRONYMIC_PAT.matcher(raw);
        if (pm.find())
            raw = raw.substring(0, pm.start()).trim();

        // Viloyat nomlarini tozalaymiz
        raw = REGION_PAT.matcher(raw).replaceAll("").trim();

        // Faqat birinchi so'zni olish (ism bir so'z bo'ladi)
        String[] words = raw.split("\\s+");
        if (words.length > 0 && !words[0].isBlank())
            return capitalizeWords(words[0]);
        return null;
    }

    // ---------------------------------------------------------------
    // Otasining ismi — Field 2 dan patronymicni ajratib chiqarish
    // ---------------------------------------------------------------
    private String extractPatronymic(String t) {
        String raw = getRawField2(t);
        if (raw == null)
            return null;

        // "KOMIL O'G'LI" yoki "KOMIL 0'G'LI" shaklida
        Matcher pm = PATRONYMIC_PAT.matcher(raw);
        if (pm.find())
            return capitalizeWords(pm.group(1).trim().replace("0'G'LI", "O'G'LI"));

        // Suffix topilmasa — butun matnda qidirish
        Matcher gm = Pattern.compile("([A-Z][A-Z']{2,20}\\s+(?:O'G'LI|QIZI|OVICH|EVICH|OVNA|EVNA|UGLI|KIZI))\\b")
                .matcher(t);
        if (gm.find())
            return capitalizeWords(gm.group(1).trim());
        return null;
    }

    // ---------------------------------------------------------------
    // Field 2 xom matni — field 2 dan field 3 gacha
    // ---------------------------------------------------------------
    private String getRawField2(String t) {
        int start2 = fieldStart(t, "2");
        if (start2 == -1)
            return null;
        int start3 = fieldStart(t, "3");
        int end = (start3 != -1 && start3 > start2) ? start3 : Math.min(t.length(), start2 + 100);
        String slice = t.substring(start2, end).replaceFirst("^2[.\\s]+", "").trim();
        return slice.isBlank() ? null : slice;
    }

    // ---------------------------------------------------------------
    // Tug'ilgan joy — "3. XONQA 25.12.1998" dan joy qismini
    // ---------------------------------------------------------------
    private String extractBirthPlace(String t) {
        // "3. XONQA 25.12.1998" yoki "3 XONQA TUMANI 25.12.1998"
        Pattern p = Pattern.compile("\\b3[.\\s]+([A-Z][A-Z'\\- ]{1,30}?)(?:\\s+TUMANI)?\\s+(\\d{2}[./])");
        Matcher m = p.matcher(t);
        if (m.find()) {
            String place = m.group(1).trim();
            // Viloyat nomlarini tozalaymiz
            place = REGION_PAT.matcher(place).replaceAll("").trim();
            if (!place.isBlank())
                return capitalizeWords(place);
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Tug'ilgan sana — FAQAT field 3 dan, field 4a emas
    // ---------------------------------------------------------------
    private String extractBirthDate(String t) {
        int idx3 = fieldStart(t, "3");
        // field 3 va 4a orasidagi sana
        int idx4a = fieldStart(t, "4A");
        if (idx3 == -1)
            idx4a = -1; // field 3 yo'q bo'lsa skip

        String slice;
        if (idx3 != -1) {
            int end = (idx4a != -1 && idx4a > idx3) ? idx4a : Math.min(t.length(), idx3 + 80);
            slice = t.substring(idx3, end);
        } else {
            return null;
        }

        Matcher dm = DATE_PATTERN.matcher(slice);
        if (dm.find()) {
            // Sana yili: tug'ilgan (1930-2015) bo'lishi kerak
            String result = formatDate(dm);
            try {
                int year = Integer.parseInt(result.substring(6));
                if (year >= 1930 && year <= 2015)
                    return result;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    // ---------------------------------------------------------------
    // 4a — Berilgan sanasi (Kirill "а" normalized bo'lgan)
    // ---------------------------------------------------------------
    private String extractField4a(String t) {
        // Normalize ichida "4а"→"4A" o'tkazilgan
        int idx4a = fieldStart(t, "4A");
        if (idx4a == -1)
            return null;
        // 4b gacha
        int idx4b = fieldStart(t, "4B");
        int end = (idx4b != -1 && idx4b > idx4a) ? idx4b : Math.min(t.length(), idx4a + 30);
        String slice = t.substring(idx4a, end);
        Matcher dm = DATE_PATTERN.matcher(slice);
        if (dm.find())
            return formatDate(dm);
        return null;
    }

    // ---------------------------------------------------------------
    // 4b — Amal qilish muddati
    // ---------------------------------------------------------------
    private String extractField4b(String t) {
        int idx4b = fieldStart(t, "4B");
        if (idx4b == -1)
            return null;
        int idx4c = fieldStart(t, "4C");
        int end = (idx4c != -1 && idx4c > idx4b) ? idx4c : Math.min(t.length(), idx4b + 30);
        String slice = t.substring(idx4b, end);
        Matcher dm = DATE_PATTERN.matcher(slice);
        if (dm.find())
            return formatDate(dm);
        return null;
    }

    // ---------------------------------------------------------------
    // 4c — Berilgan joyi (Kirill "с" normalized bo'lgan)
    // ---------------------------------------------------------------
    private String extractField4c(String t) {
        int idx4c = fieldStart(t, "4C");
        if (idx4c == -1)
            return null;
        int idx4d = fieldStart(t, "4D");
        int end = (idx4d != -1 && idx4d > idx4c) ? idx4d : Math.min(t.length(), idx4c + 80);
        String slice = t.substring(idx4c, end).replaceFirst("^4C[.\\s]+", "").trim();
        if (!slice.isBlank())
            return capitalizeWords(slice);
        return null;
    }

    // ---------------------------------------------------------------
    // 4d — PINFL
    // ---------------------------------------------------------------
    private String extractPinfl(String t) {
        int idx4d = fieldStart(t, "4D");
        if (idx4d != -1) {
            String slice = t.substring(idx4d, Math.min(t.length(), idx4d + 30));
            Matcher m = PINFL_PATTERN.matcher(slice);
            if (m.find())
                return m.group(1);
        }
        // Butun matnda qidirish
        Matcher m = PINFL_PATTERN.matcher(t);
        if (m.find())
            return m.group(1);
        return null;
    }

    // ---------------------------------------------------------------
    // 5 — Guvohnoma raqami
    // ---------------------------------------------------------------
    private String extractLicenseNumber(String t) {
        int idx5 = fieldStart(t, "5");
        if (idx5 != -1) {
            String slice = t.substring(idx5, Math.min(t.length(), idx5 + 25));
            Matcher m = LICENSE_PATTERN.matcher(slice);
            if (m.find())
                return m.group(1).replace(" ", "");
        }
        // Butun matnda — PINFL bilan aralashmasligi uchun
        Matcher m = LICENSE_PATTERN.matcher(t);
        while (m.find()) {
            String f = m.group(1).replace(" ", "");
            if (f.length() == 9)
                return f; // 2 harf + 7 raqam = 9
        }
        return null;
    }

    // ---------------------------------------------------------------
    // 8 — Manzil (faqat field 8 dan 9 gacha oraliq)
    // ---------------------------------------------------------------
    private String extractField8(String t) {
        int idx8 = fieldStart(t, "8");
        if (idx8 == -1)
            return null;
        int idx9 = fieldStart(t, "9");
        int end = (idx9 != -1 && idx9 > idx8) ? idx9 : Math.min(t.length(), idx8 + 120);
        String slice = t.substring(idx8, end).replaceFirst("^8[.\\s]+", "").trim();
        // "DL000..." kabi seriya raqamlarini olib tashlaymiz
        slice = slice.replaceAll("\\bDL0*\\d{7,}\\b", "").trim();
        if (!slice.isBlank())
            return capitalizeWords(slice);
        return null;
    }

    // ---------------------------------------------------------------
    // 9 — Toifa
    // Kirill В → Latin B allaqachon normalizatsiyada almashtirilgan
    // ---------------------------------------------------------------
    private String extractField9(String t) {
        // "9. B" yoki "9 B"
        Pattern p = Pattern.compile("\\b9[.\\s]+([A-E](?:[,\\s]+[A-E])*)\\b");
        Matcher m = p.matcher(t);
        if (m.find())
            return m.group(1).trim().replaceAll("\\s+", "").replace(",", ", ");
        return null;
    }

    // ---------------------------------------------------------------
    // Yordamchi metodlar
    // ---------------------------------------------------------------

    /**
     * "N." yoki "4A." kabi field boshlanish indexini topadi.
     * Normalize ichida "4а"→"4A" allaqachon qilingan.
     */
    private int fieldStart(String t, String field) {
        // "1.", "2.", "4A.", "4B." kabi
        Pattern p = Pattern.compile("\\b" + Pattern.quote(field) + "[.\\s]");
        Matcher m = p.matcher(t);
        if (m.find())
            return m.start();
        return -1;
    }

    private String formatDate(Matcher dm) {
        // GROUP 1=DD, 2=MM, 3=YYYY
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
