package org.example.passportocr.servise;

import lombok.RequiredArgsConstructor;
import org.example.passportocr.dto.BaseDocumentDto;
import org.example.passportocr.dto.PassportDto;
import org.example.passportocr.enums.DocumentType;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O'zbekiston xalqaro pasporti parseri.
 *
 * OCR xatolari hisobga olingan:
 * - "2 5 . 12 . 1998" → boʻshliqli sanalar
 * - "SURANAME" → "SURNAME" yoki "FAMILIYASI" nomi
 * - "M" jinsi — label bilan birga keladi
 * - Authority: "MIA 3 3 223" → raqamlar qo'shiladi
 * - MRZ satri: P<UZBQUSHNAZAROV<<JALOLADDIN... dan kuchli fallback
 */
@Service
@RequiredArgsConstructor
public class PassportParserService {

    // MRZ 1-satri: P<UZB FAMILIYA << ISM
    private static final Pattern MRZ_LINE1 = Pattern.compile(
            "P<UZB([A-Z]+)<<([A-Z]+)");

    // MRZ 2-satri: FA40892165UZB9812253M31101193...
    // Groups: 1=ppNo, 2=YY, 3=MM, 4=DD(birth), 5=gender, 6=YY, 7=MM, 8=DD(expiry)
    private static final Pattern MRZ_LINE2 = Pattern.compile(
            "([A-Z]{2}\\d{7,8})UZB(\\d{2})(\\d{2})(\\d{2})([MF])(\\d{2})(\\d{2})(\\d{2})");

    // Sana: "25.12.1998" yoki "25/12/1998"
    private static final Pattern DATE_CLEAN = Pattern.compile(
            "(\\d{2})[./\\-](\\d{2})[./\\-](\\d{4})");

    // Passport raqami: FA 4089216 yoki FA4089216
    private static final Pattern PASSPORT_NO = Pattern.compile(
            "\\b([A-Z]{2})\\s?(\\d{7,8})\\b");

    // Authority: MIA 33223
    private static final Pattern AUTHORITY_PAT = Pattern.compile(
            "\\b((?:MIA|GUM|MOI|GUMVD|MVD|GUVD)\\s*\\d{3,8})\\b");

    public BaseDocumentDto parse(String rawText) {
        PassportDto dto = new PassportDto();
        dto.setDocumentType(DocumentType.PASSPORT);
        dto.setText(rawText);

        String t = normalize(rawText);

        dto.setFamiliya(extractFamiliya(t));
        dto.setIsm(extractIsm(t));
        dto.setFuqaroligi(extractCitizenship(t));
        dto.setJinsi(extractGender(t));
        dto.setTugilganSana(extractBirthDate(t));
        dto.setTugilganJoy(extractPlaceOfBirth(t));
        dto.setPassportRaqami(extractPassportNumber(t));
        dto.setAuthority(extractAuthority(t));
        dto.setBerilganSana(extractIssuedDate(t));
        dto.setAmalQilishMuddati(extractExpiryDate(t));

        return dto;
    }

    // ---------------------------------------------------------------
    // Normalize
    // ---------------------------------------------------------------
    private String normalize(String text) {
        if (text == null || text.isBlank())
            return "";

        String n = text.toUpperCase()
                .replaceAll("[''´`ʼ]", "'")
                .replaceAll("О'", "O'")
                .replaceAll("\\b0'(?=[A-Z])", "O'")
                // MRZ qatorini saqlash kerak — unga tegmaymiz
                // Boʻshliqli sanalarni normallashtirish: "2 5 . 1 2 . 1 9 9 8"
                // Har xil variantlar:
                .replaceAll("(\\d)\\s+\\.\\s+(\\d)", "$1.$2") // "2 5 . 12" → "25.12"
                .replaceAll("(\\d)\\s(\\d)(?=\\s*[./])", "$1$2") // "2 5." → "25."
                .replaceAll("(\\d{2})\\s*[./]\\s*(\\d{2})\\s*[./]\\s*(\\d{4})", "$1.$2.$3")
                // |, {, } ni olib tashlash
                .replace("|", " ")
                .replaceAll("[{}]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        // Authority inline fix (replaceAll lambda ishlamaydi — alohida qilamiz)
        n = fixAuthority(n);

        return n;
    }

    private String fixAuthority(String t) {
        // "MIA 3 3 2 2 3" → "MIA 33223"
        Matcher m = Pattern.compile("((?:MIA|GUM|MOI|GUMVD|MVD|GUVD))\\s+((?:\\s*\\d){3,10})").matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String org = m.group(1);
            String digits = m.group(2).replaceAll("\\s", "");
            m.appendReplacement(sb, org + " " + digits);
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    // ---------------------------------------------------------------
    // Familiya
    // ---------------------------------------------------------------
    private String extractFamiliya(String t) {
        // FAMILIYASI / SURANAME yoki SURNAME (OCR "SURANAME" deb o'qiydi)
        String[] patterns = {
                "FAMILIYASI\\s*/\\s*SURANAME\\s+([A-Z][A-Z'\\-]{1,35})",
                "FAMILIYASI\\s*/\\s*SURNAME\\s+([A-Z][A-Z'\\-]{1,35})",
                "FAMILIYASI\\s+([A-Z][A-Z'\\-]{1,35})",
                "SURANAME\\s+([A-Z][A-Z'\\-]{1,35})",
                "SURNAME\\s+([A-Z][A-Z'\\-]{1,35})"
        };
        for (String pat : patterns) {
            Matcher m = Pattern.compile(pat).matcher(t);
            if (m.find())
                return m.group(1).trim();
        }
        // MRZ fallback
        Matcher mrzM = MRZ_LINE1.matcher(t);
        if (mrzM.find())
            return mrzM.group(1);
        return null;
    }

    // ---------------------------------------------------------------
    // Ismi
    // ---------------------------------------------------------------
    private String extractIsm(String t) {
        String[] patterns = {
                "ISMI\\s*/\\s*GIVEN\\s+NAME\\S*\\s+([A-Z][A-Z'\\- ]{2,40}?)(?=\\s{2,}|\\s+[A-Z]{5,}\\s*/|$)",
                "GIVEN\\s+NAME\\S*\\s+([A-Z][A-Z'\\- ]{2,40}?)(?=\\s{2,}|\\s+[A-Z]{5,}\\s*/|$)",
                "ISMI\\s+([A-Z][A-Z]{3,20})(?=\\s+[A-Z]{4,}|$)"
        };
        for (String pat : patterns) {
            Matcher m = Pattern.compile(pat).matcher(t);
            if (m.find()) {
                String cand = m.group(1).trim();
                if (!cand.isEmpty())
                    return cand;
            }
        }
        // MRZ fallback
        Matcher mrzM = MRZ_LINE1.matcher(t);
        if (mrzM.find())
            return mrzM.group(2);
        return null;
    }

    // ---------------------------------------------------------------
    // Citizenship
    // ---------------------------------------------------------------
    private String extractCitizenship(String t) {
        String[] variants = { "O'ZBEKISTON", "OZBEKISTON", "UZBEKISTAN" };
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
    // Jinsi — passport: M/F, ID: ERKAK/AYOL
    // ---------------------------------------------------------------
    private String extractGender(String t) {
        // Jinsi / SEX dan keyin "M" yoki "F"
        int idx = t.indexOf("JINSI");
        if (idx == -1)
            idx = t.indexOf("/ SEX");
        if (idx == -1)
            idx = t.indexOf("SEX");
        if (idx != -1) {
            String slice = t.substring(idx, Math.min(t.length(), idx + 40));
            // "M XONQA" kabi — M dan keyin joy ismi kelsa ham "M" ni gender sifatida olish
            Matcher m = Pattern.compile("\\b([MF])\\b").matcher(slice);
            if (m.find())
                return m.group(1).equals("M") ? "Erkak" : "Ayol";
        }
        // MRZ fallback: ...9812253M31101193... → M
        Matcher mrzM = MRZ_LINE2.matcher(t);
        if (mrzM.find())
            return mrzM.group(5).equals("M") ? "Erkak" : "Ayol";

        if (t.contains("ERKAK"))
            return "Erkak";
        if (t.contains("AYOL"))
            return "Ayol";
        return null;
    }

    // ---------------------------------------------------------------
    // Tug'ilgan sana
    // ---------------------------------------------------------------
    private String extractBirthDate(String t) {
        // 1. Label asosida
        String[] labels = {
                "TUG'ILGAN SANASI / DATE OF BIRTH",
                "TUG'ILGAN SANASI/DATE OF BIRTH",
                "DATE OF BIRTH",
                "TUG'ILGAN SANASI",
                "TUGILGAN SANASI"
        };
        for (String lbl : labels) {
            int idx = t.indexOf(lbl);
            if (idx == -1)
                continue;
            String slice = t.substring(idx + lbl.length(), Math.min(t.length(), idx + lbl.length() + 60));
            Matcher dm = DATE_CLEAN.matcher(slice);
            if (dm.find())
                return formatDate(dm);
        }

        // 2. MRZ fallback — YYMMDD
        Matcher mrzM = MRZ_LINE2.matcher(t);
        if (mrzM.find()) {
            String yy = mrzM.group(2);
            String mm = mrzM.group(3);
            String dd = mrzM.group(4);
            return dd + "." + mm + "." + yyToYear(yy);
        }

        // 3. Barcha sanalardan tug'ilgan yilni topish
        Matcher dm = DATE_CLEAN.matcher(t);
        while (dm.find()) {
            try {
                int year = Integer.parseInt(dm.group(3));
                if (year >= 1930 && year <= LocalDate.now().getYear() - 10)
                    return formatDate(dm);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Tug'ilgan joy
    // ---------------------------------------------------------------
    private String extractPlaceOfBirth(String t) {
        String[] labels = {
                "TUG'ILGAN JOYI / PLACE OF BIRTH",
                "PLACE OF BIRTH",
                "TUG'ILGAN JOYI",
                "TUGILGAN JOYI"
        };
        for (String lbl : labels) {
            int idx = t.indexOf(lbl);
            if (idx == -1)
                continue;
            String slice = t.substring(idx + lbl.length(), Math.min(t.length(), idx + lbl.length() + 50)).trim();
            // Keyingi bo'limga qadar
            Matcher pm = Pattern.compile("([A-Z][A-Z'\\- ]{1,30}?)(?=\\s{2,}|\\s+BERIL|\\s+DATE|$)").matcher(slice);
            if (pm.find()) {
                String place = pm.group(1).trim();
                // "XONQA BERILGAN SANASI" kabi keraksiz davomni kesib tashlash
                if (place.contains("BERILGAN"))
                    place = place.substring(0, place.indexOf("BERILGAN")).trim();
                if (!place.isBlank())
                    return place;
            }
        }
        // "Jinsi / SEX M XONQA BERILGAN..." dan — M/F dan keyin joy nomi
        int sexIdx = t.indexOf("JINSI");
        if (sexIdx != -1) {
            String slice = t.substring(sexIdx, Math.min(t.length(), sexIdx + 60));
            // "M XONQA" yoki "F TOSHKENT"
            Matcher pm = Pattern.compile("\\b[MF]\\s+([A-Z][A-Z]{1,20})\\b").matcher(slice);
            if (pm.find())
                return pm.group(1);
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Passport raqami
    // ---------------------------------------------------------------
    private String extractPassportNumber(String t) {
        String[] labels = {
                "PASSPORT RAQAMI/ PASSPORT NO.",
                "PASSPORT RAQAMI/PASSPORT NO.",
                "PASSPORT RAQAMI/ PASSPORT NO",
                "PASSPORT NO.",
                "PASSPORT RAQAMI",
                "PASPORT RAQAMI"
        };
        for (String lbl : labels) {
            int idx = t.indexOf(lbl);
            if (idx != -1) {
                String slice = t.substring(idx, Math.min(t.length(), idx + 25));
                Matcher pm = PASSPORT_NO.matcher(slice);
                if (pm.find())
                    return pm.group(1) + pm.group(2);
            }
        }
        // MRZ fallback
        Matcher mrzM = MRZ_LINE2.matcher(t);
        if (mrzM.find())
            return mrzM.group(1);
        // Butun matn
        Matcher pm = PASSPORT_NO.matcher(t);
        if (pm.find())
            return pm.group(1) + pm.group(2);
        return null;
    }

    // ---------------------------------------------------------------
    // Authority / Kim tomonidan berilgan
    // ---------------------------------------------------------------
    private String extractAuthority(String t) {
        String[] labels = {
                "KIM TOMONIDAN BERILGAN / AUTHORITY",
                "KIM TOMONIDAN BERILGAN/AUTHORITY",
                "KIM TOMONIDAN BERILGAN",
                "AUTHORITY"
        };
        for (String lbl : labels) {
            int idx = t.indexOf(lbl);
            if (idx != -1) {
                String slice = t.substring(idx + lbl.length(), Math.min(t.length(), idx + lbl.length() + 30)).trim();
                Matcher am = AUTHORITY_PAT.matcher(slice);
                if (am.find())
                    return am.group(1).trim();
                // Fallback: keyingi 1-2 token
                if (!slice.isBlank()) {
                    String[] tokens = slice.split("\\s+");
                    if (tokens.length >= 2)
                        return tokens[0] + " " + tokens[1];
                    if (tokens.length == 1)
                        return tokens[0];
                }
            }
        }
        // Butun matndan topish
        Matcher am = AUTHORITY_PAT.matcher(t);
        if (am.find())
            return am.group(1).trim();
        return null;
    }

    // ---------------------------------------------------------------
    // Berilgan sana (DATE OF ISSUE / BERILGAN SANASI)
    // ---------------------------------------------------------------
    private String extractIssuedDate(String t) {
        String[] labels = {
                "BERILGAN SANASI / DATE OF", // "ISSUE" keyingi satrda bo'lishi mumkin
                "BERILGAN SANASI / DATE OF ISSUE",
                "BERILGAN SANASI/DATE OF ISSUE",
                "DATE OF ISSUE",
                "BERILGAN SANASI",
                "BERILGAN SANA"
        };
        for (String lbl : labels) {
            int idx = t.indexOf(lbl);
            if (idx == -1)
                continue;
            String slice = t.substring(idx + lbl.length(), Math.min(t.length(), idx + lbl.length() + 80));
            Matcher dm = DATE_CLEAN.matcher(slice);
            if (dm.find()) {
                // "DATE OF EXPIRY" dan oldin kelishini tekshiramiz
                try {
                    int year = Integer.parseInt(dm.group(3));
                    if (year <= LocalDate.now().getYear() && year >= 2000)
                        return formatDate(dm);
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Amal qilish muddati (DATE OF EXPIRY)
    // ---------------------------------------------------------------
    private String extractExpiryDate(String t) {
        String[] labels = { "AMAL QILISH MUDDATI / DATE OF EXPIRY",
                "DATE OF EXPIRY", "AMAL QILISH MUDDATI" };
        for (String lbl : labels) {
            int idx = t.indexOf(lbl);
            if (idx == -1)
                continue;
            String slice = t.substring(idx + lbl.length(), Math.min(t.length(), idx + lbl.length() + 40));
            Matcher dm = DATE_CLEAN.matcher(slice);
            if (dm.find())
                return formatDate(dm);
        }
        // MRZ fallback
        Matcher mrzM = MRZ_LINE2.matcher(t);
        if (mrzM.find()) {
            String yy = mrzM.group(6);
            String mm = mrzM.group(7);
            String dd = mrzM.group(8);
            return dd + "." + mm + "." + yyToYear(yy);
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Yordamchi metodlar
    // ---------------------------------------------------------------
    private String formatDate(Matcher dm) {
        return dm.group(1) + "." + dm.group(2) + "." + dm.group(3);
    }

    private String yyToYear(String yy) {
        int y = Integer.parseInt(yy);
        return (y > 30 ? "19" : "20") + yy;
    }
}