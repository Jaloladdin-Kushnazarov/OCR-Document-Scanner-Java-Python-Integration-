package org.example.passportocr.servise;

import lombok.RequiredArgsConstructor;
import org.example.passportocr.dto.BaseDocumentDto;
import org.example.passportocr.dto.UnknownDocumentDto;
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
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OCRService {


    private final IDCardParserService idCardParserService;
    private final PassportParserService passportParserService;
    private final DrivingLicenseParserService driverLicenseParserService;
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

    public DocumentType identifyDocumentType(String text) {
        String upper = text.toUpperCase();

        if (upper.contains("SHAXS GUVOHNOMASI") || upper.contains("IDENTITY CARD")) return DocumentType.ID_CARD;
        if (upper.contains("PASSPORT") || upper.contains("PASSPORT NO")) return DocumentType.PASSPORT;
        if (upper.contains("HAYDOVCHILIK GUVOHNOMASI") || upper.contains("DRIVING LICENCE"))return DocumentType.DRIVER_LICENSE;

        return DocumentType.UNKNOWN;
    }

    public BaseDocumentDto parse(String text) {
        DocumentType type = identifyDocumentType(text);

        return switch (type) {
            case ID_CARD -> idCardParserService.parse(text);
            case PASSPORT -> passportParserService.parse(text);
            case DRIVER_LICENSE -> driverLicenseParserService.parse(text);
            default -> {
                BaseDocumentDto dto = new UnknownDocumentDto();
                dto.setDocumentType(DocumentType.UNKNOWN);
                dto.setText(text);
                yield dto;
            }
        };
    }
}

