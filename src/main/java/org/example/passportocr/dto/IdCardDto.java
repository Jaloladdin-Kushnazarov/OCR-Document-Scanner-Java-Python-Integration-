package org.example.passportocr.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class IdCardDto extends BaseDocumentDto{
    private String familiya;
    private String ism;
    private String otasiningIsmi;
    private String tugilganSana;
    private String berilganSana;
    private String amalQilishMuddati;
    private String jinsi;
    private String fuqaroligi;
    private String kartaRaqami;
}
