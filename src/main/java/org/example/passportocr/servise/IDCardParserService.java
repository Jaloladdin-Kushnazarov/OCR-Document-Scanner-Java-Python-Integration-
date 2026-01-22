package org.example.passportocr.servise;

import lombok.RequiredArgsConstructor;
import org.example.passportocr.dto.BaseDocumentDto;
import org.example.passportocr.dto.IdCardDto;
import org.example.passportocr.enums.DocumentType;
import org.example.passportocr.util.MultipartInputStreamFileResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IDCardParserService {
    private final RestTemplate restTemplate;

    public String extractTextFromImage(MultipartFile file) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new MultipartInputStreamFileResource(file.getInputStream(), file.getOriginalFilename()));

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "http://localhost:8000/ocr/",  // Python tarafdagi API
                request,
                Map.class
        );
        System.out.println(response.getBody().get("text").toString());
        return response.getBody().get("text").toString();
    }

    public BaseDocumentDto parse(String text) {
        IdCardDto dto = new IdCardDto();
        String t = text.toUpperCase().replaceAll("\\s+", " ");

        dto.setDocumentType(DocumentType.ID_CARD);
        dto.setFamiliya(extractFamiliya(t));
        dto.setIsm(extractIsm(t));
        dto.setOtasiningIsmi(extractOtasiningIsmi(t));
        dto.setBerilganSana(extractDateAfter(t, "BERILGAN SANASI"));
        dto.setAmalQilishMuddati(extractDateAfter(t, "AMAL QILISH MUDDATI"));
        dto.setTugilganSana(extractTugilganSana(t));
        dto.setJinsi(t.contains("ERKAK") ? "Erkak" : t.contains("AYOL") ? "Ayol" : null);
        dto.setFuqaroligi(extractCitizenship(t));
        dto.setKartaRaqami(extractCardNumber(t));
        dto.setText(text);

        return dto;
    }

    private String extractFamiliya(String text) {
        String[] possiblePatterns = {
                "SURNAME", "FAMILIYASI"
        };

        for (String label : possiblePatterns) {
            Pattern pattern = Pattern.compile(label + "\\s+([A-Z'\\-]+)");
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) return matcher.group(1).trim();
        }
        return null;
    }

    private String extractIsm(String text) {
        String[] possiblePatterns = {
                "GIVEN NAME\\(S\\)", "GIVEN NAME", "NAME\\(S\\)", "NAME", "ISMI"
        };

        for (String patternStr : possiblePatterns) {
            Pattern pattern = Pattern.compile(patternStr + "\\s+([A-Z'\\-]+)");
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        return null;
    }

    private String extractOtasiningIsmi(String text) {
        String fixed = text.toUpperCase()
                .replace("0'", "O'")
                .replace("’", "'");

        int start = fixed.indexOf("PATRONYMIC");
        if (start == -1) return null;

        int end = Math.min(fixed.length(), start + 60);
        String afterLabel = fixed.substring(start, end);
        Pattern pattern = Pattern.compile("([A-Z]{3,}\\s+(O'G'LI|QIZI|OVICH|EVICH|ICH|VNA))");
        Matcher matcher = pattern.matcher(afterLabel);

        if (matcher.find()) {
            return capitalizeWords(matcher.group(1));
        }

        return null;
    }

    private String extractTugilganSana(String text) {
        String[] keywords = {
                "TUGILGAN SANASI", "DATE OF BIRTH", "TUG'ILGAN SANASI"
        };

        for (String keyword : keywords) {
            int index = text.indexOf(keyword);
            if (index != -1) {
                String after = text.substring(index, Math.min(text.length(), index + 80));
                Matcher matcher = Pattern.compile("\\d{2}[./-]\\d{2}[./-]\\d{4}").matcher(after);
                if (matcher.find()) return matcher.group();
            }
        }

        return null;
    }

    private String extractCitizenship(String text) {
        String cleaned = text.toUpperCase()
                .replace("0'", "O'")
                .replaceAll("['‘’`]", "'")
                .replaceAll("[^A-Z'\\s\\d]", "");  // noto‘g‘ri belgilarni tozalaymiz

        String[] keywords = { "FUQAROLIGI", "CITIZENSHIP" };

        for (String keyword : keywords) {
            int index = cleaned.indexOf(keyword);
            if (index != -1) {
                String after = cleaned.substring(index, Math.min(cleaned.length(), index + 80));
                Pattern p = Pattern.compile("O'ZBEKISTON");
                Matcher m = p.matcher(after);
                if (m.find()) return "O'ZBEKISTON";
            }
        }

        return null;
    }

    private String extractCardNumber(String text) {
        Pattern pattern = Pattern.compile("AE\\s?\\d{7}");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group().replaceAll("\\s+", ""); // bo‘sh joylarni olib tashlaymiz
        }
        return null;
    }

    private String capitalizeWords(String input) {
        return Arrays.stream(input.toLowerCase().split("\\s+"))
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(Collectors.joining(" "));
    }

    private String extractDateAfter(String text, String keyword) {
        int keywordIndex = text.indexOf(keyword.toUpperCase());
        if (keywordIndex == -1) return null;

        String after = text.substring(keywordIndex, Math.min(text.length(), keywordIndex + 100)); // 100 belgigacha
        Pattern pattern = Pattern.compile("\\d{2}[./-]\\d{2}[./-]\\d{4}");
        Matcher matcher = pattern.matcher(after);

        if (matcher.find()) return matcher.group();
        return null;
    }
}
