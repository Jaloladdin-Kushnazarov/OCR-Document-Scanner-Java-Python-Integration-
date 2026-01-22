package org.example.passportocr.servise;

import lombok.RequiredArgsConstructor;
import org.example.passportocr.dto.BaseDocumentDto;
import org.example.passportocr.dto.PassportDto;
import org.example.passportocr.enums.DocumentType;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Service
public class PassportParserService {

    public BaseDocumentDto parse(String text) {
        PassportDto dto = new PassportDto();
        String cleaned = normalize(text);
        System.out.println( text);
        System.out.println(cleaned);

        dto.setDocumentType(DocumentType.PASSPORT);
        dto.setFamiliya(extractFamiliya(cleaned));
        dto.setIsm(extractIsm(cleaned));
        dto.setFuqaroligi(cleaned.contains("O'ZBEKISTON") ? "O'ZBEKISTON" : null);
        dto.setJinsi(extractGender(cleaned));
        dto.setTugilganSana(extractBirthDate(cleaned));
        dto.setTugilganJoy(extractPlaceOfBirth(cleaned));
        dto.setPassportRaqami(extractPassportNumber(cleaned));
        dto.setBerilganSana(extractBerilganSana(cleaned));
        dto.setAmalQilishMuddati(extractDateAfterKeyword(cleaned, "DATE OF EXPIRY"));
        dto.setText(text);
        return dto;
    }

    private String normalize(String text) {
        if (text == null || text.isBlank()) return "";

        // 1. Standart normalize qilishlar
        String normalized = text.toUpperCase()
                .replaceAll("[‘’´`]", "'")
                .replaceAll("0'", "O'")
                .replaceAll("[_]+", ".")
                .replaceAll("\\.{2,}", ".")
                .replaceAll("(?<=\\d)\\.\\s+(?=\\d)", ".")
                .replaceAll("(?<!\\d)[/\\\\](?!\\d)", " - ")
                .replaceAll("(?<=\\d)[ _](?=\\d)", ".")
                .replaceAll("(?<=DATE OF)\\s+(BIRTH|ISSUE|EXPIRY)", " $1")
                .replaceAll("RAQAMI[-\\s\\/\\\\]*", "RAQAMI ")
                .replaceAll("\\s+", " ") // bo‘sh joylarni tekislash
                .trim();

        // 2. AUTHORITY blokini yaxshilash (MIA 332 2 3 → MIA 33223)
        Pattern authorityPattern = Pattern.compile("(AUTHORITY ISSUE\\s+(MIA|GUM|MOI|GUMVD|MVD|GUVD)\\s+)(\\d(?:\\s*\\d){2,6})");
        Matcher matcher = authorityPattern.matcher(normalized);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String label = matcher.group(1); // AUTHORITY ISSUE MIA
            String digits = matcher.group(3).replaceAll("\\s", ""); // raqamlarni birlashtirish
            matcher.appendReplacement(sb, label + digits);
        }
        matcher.appendTail(sb);
        normalized = sb.toString();

        // 3. Sanalarni to‘g‘rilash
        normalized = normalized
                .replaceAll("(\\d{1,2})\\s*\\.\\s*(\\d{1,2})\\s*\\.\\s*(\\d{2,4})", "$1.$2.$3");

        return normalized;
    }

    private String extractFamiliya(String text) {
        // 1. FAMILIYASI SURANAME dan olishga urinamiz
        Pattern pattern = Pattern.compile("FAMILIYASI SURANAME\\s+([A-Z']+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        // 2. Agar topilmasa — MRZ'dan olishga urinamiz
        return extractFromMRZ(text, "familiya");
    }
    private String extractIsm(String text) {
        Pattern pattern = Pattern.compile("ISMI CIVEN NAME\\(S\\)\\s+([A-Z']+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }

        return extractFromMRZ(text, "ism");
    }
    private String extractFromMRZ(String text, String field) {
        Pattern pattern = Pattern.compile("P<UZB([A-Z]+)<<([A-Z]+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            if (field.equals("familiya")) return matcher.group(1);
            if (field.equals("ism")) return matcher.group(2);
        }
        return null;
    }

    private String extractBirthDate(String text) {
        // 1. OCR Label asosida — TUG'ILGAN SANASI yoki DATE OF BIRTH
        Pattern labelPattern = Pattern.compile(
                "(TUG['`]ILGAN SANASI|DATE OF BIRTH)[^\\d]{0,10}(\\d{1,2})[\\s_./-]?(\\d{1,2})[\\s_./-]?(\\d{2,4})"
        );
        Matcher matcher = labelPattern.matcher(text);
        if (matcher.find()) {
            String day = padZero(matcher.group(2));
            String month = padZero(matcher.group(3));
            String year = normalizeYear(matcher.group(4));
            return String.format("%s.%s.%s", day, month, year);
        }

        // 2. Fallback — faqat sana qidirish
        Pattern dateOnly = Pattern.compile("(\\d{2})[\\s_./-]?(\\d{2})[\\s_./-]?(\\d{4})");
        Matcher fallback = dateOnly.matcher(text);
        while (fallback.find()) {
            String d = fallback.group(1), m = fallback.group(2), y = fallback.group(3);
            int year = Integer.parseInt(y);
            if (year > 1900 && year <= LocalDate.now().getYear()) {
                return String.format("%s.%s.%s", d, m, y);
            }
        }

        // 3. MRZ fallback
        Pattern mrzPattern = Pattern.compile("([A-Z0-9<]+)(\\d{2})(\\d{2})(\\d{2})M"); // M erkak
        Matcher mrz = mrzPattern.matcher(text);
        if (mrz.find()) {
            String yy = mrz.group(2);
            String mm = mrz.group(3);
            String dd = mrz.group(4);
            String fullYear = yyToFullYear(yy);
            return String.format("%s.%s.%s", dd, mm, fullYear);
        }

        return null; // topilmadi
    }
    private String padZero(String input) {
        return input.length() == 1 ? "0" + input : input;
    }

    private String normalizeYear(String year) {
        if (year.length() == 2) {
            int y = Integer.parseInt(year);
            return y > 30 ? "19" + year : "20" + year;
        }
        return year;
    }

    private String yyToFullYear(String yy) {
        int y = Integer.parseInt(yy);
        return (y > 30 ? "19" : "20") + yy;
    }

    private String extractGender(String text) {
        // Har doim katta harfli bo'lishini ta'minlaymiz
        String upper = text.toUpperCase();

        // Trigger joylashuvini topamiz
        int jinsIndex = upper.indexOf("JINSI");
        int sexIndex = upper.indexOf("SEX");

        // Eng yaqin pozitsiyani olish
        int index = jinsIndex != -1 ? jinsIndex : sexIndex;

        if (index != -1) {
            // 1. 0 dan 60 belgigacha bo‘lgan oraliqni tekshiramiz
            String slice = upper.substring(index, Math.min(index + 60, upper.length()));

            // 2. Oraliqdan " M " yoki " F " ni ajratamiz
            Pattern pattern = Pattern.compile("\\b([MF])\\b");
            Matcher matcher = pattern.matcher(slice);
            if (matcher.find()) {
                String code = matcher.group(1);
                return code.equals("M") ? "Erkak" : code.equals("F") ? "Ayol" : null;
            }

            // 3. Yoki oxirgi so‘z sifatida kelganini ushlaymiz
            String[] words = slice.split("\\s+");
            for (String word : words) {
                if (word.equals("M")) return "Erkak";
                if (word.equals("F")) return "Ayol";
            }
        }

        return null;
    }


    private String extractPassportNumber(String text) {
        // 1. Normalize qilingan matn (keraksiz belgilarni olib tashlaymiz)
        String normalized = text.toUpperCase()
                .replaceAll("['‘’`]", "")
                .replaceAll("[^A-Z0-9\\s]", "")
                .replaceAll("\\s+", " ");

        // 2. PASSPORT so‘zlari atrofidan qidiramiz (label asosida)
        Pattern labelPattern = Pattern.compile("(PASPORT|PASSPORT)[^A-Z0-9]{0,20}([A-Z]{2}\\s?[0-9]{7,9})");
        Matcher labelMatcher = labelPattern.matcher(normalized);
        if (labelMatcher.find()) {
            return labelMatcher.group(2).replace(" ", "");
        }

        // 3. MRZ qismidan qidiramiz: "P<UZB...AA1234567..."
        Pattern mrzPattern = Pattern.compile("P<UZB.*?([A-Z]{2}[0-9]{7,9})");
        Matcher mrzMatcher = mrzPattern.matcher(normalized);
        if (mrzMatcher.find()) {
            return mrzMatcher.group(1);
        }

        // 4. Fallback — oddiy regex orqali birinchi variantni olish
        Pattern fallback = Pattern.compile("\\b[A-Z]{2}\\s?[0-9]{7,9}\\b");
        Matcher fallbackMatcher = fallback.matcher(normalized);
        if (fallbackMatcher.find()) {
            return fallbackMatcher.group().replace(" ", "");
        }

        return null;
    }
    private String extractBerilganSana(String text) {
        if (text == null || text.isBlank()) return null;

        String normalized = text.toUpperCase()
                .replaceAll("[^A-Z0-9./\\s-]", " ")           // no kerakli belgilardan tozalash
                .replaceAll("(\\d)\\s*\\.\\s*(\\d)", "$1.$2") // 10 .2021 → 10.2021
                .replaceAll("\\s+", " ");                     // ko'p probellarni bitta qilish

        // Faqat "BERILGAN SANASI" yoki "DATE OF ISSUE" dan so'ng 10 ta so'zgacha ichida sana bo‘lishi mumkin
        Pattern pattern = Pattern.compile(
                "(BERILGAN SANASI|DATE OF ISSUE|AUTHORITY ISSUE)" +
                        "(?:\\s+[A-Z]+)*" +               // optional MIA
                        "(?:\\s+\\d{3,6})*" +             // optional 33223
                        "\\s+(\\d{1,2})[\\s./\\\\-]+(\\d{1,2})[\\s./\\\\-]+(\\d{2,4})"
        );

        Matcher matcher = pattern.matcher(normalized);
        if (matcher.find()) {
            try {
                int day = Integer.parseInt(matcher.group(1));
                int month = Integer.parseInt(matcher.group(2));
                String year = matcher.group(3);
                if (year.length() == 2) year = "20" + year;
                return String.format("%02d.%02d.%s", day, month, year);
            } catch (NumberFormatException e) {
                System.err.println("⚠️ Date parsing error: " + e.getMessage());
            }
        }

        return null;
    }

    private String extractDateAfterKeyword(String text, String keyword) {
        int index = text.indexOf(keyword);
        if (index != -1) {
            String after = text.substring(index, Math.min(index + 100, text.length()));
            Matcher matcher = Pattern.compile("\\d{2}[.\\-/]\\d{2}[.\\-/]\\d{4}").matcher(after);
            if (matcher.find()) return matcher.group();
        }
        return null;
    }

    private String extractPlaceOfBirth(String text) {
        // Trigger so‘zlar
        String[] triggers = {
                "PLACE OF BIRTH", "TUG'ILGAN JOYI"
        };

        for (String trigger : triggers) {
            int index = text.indexOf(trigger);
            if (index != -1) {
                // 1. Keyingi 20-40 belgidan substring olamiz
                String slice = text.substring(index, Math.min(index + 50, text.length()));

                // 2. "M XONQA" ko‘rinishidagi ketma-ketlikni ushlaymiz
                Pattern genderPlacePattern = Pattern.compile("SEX\\s+([MF])\\s+([A-Z'`´]{3,20})");
                Matcher matcher = genderPlacePattern.matcher(slice);
                if (matcher.find()) {
                    return matcher.group(2); // faqat joy
                }

                // 3. Agar yuqoridagisi topilmasa, oddiy keyingi so‘zni olishga harakat qilamiz
                Pattern fallback = Pattern.compile(trigger + "[\\s\\-:/\\\\]*[MF]?\\s*([A-Z'`´]{3,20})");
                Matcher fallbackMatcher = fallback.matcher(slice);
                if (fallbackMatcher.find()) {
                    return fallbackMatcher.group(1);
                }
            }
        }

        return null;
    }
}
// berilgan sanada muammo