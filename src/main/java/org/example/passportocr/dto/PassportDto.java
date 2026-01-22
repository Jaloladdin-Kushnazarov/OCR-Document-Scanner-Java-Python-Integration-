package org.example.passportocr.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class PassportDto extends BaseDocumentDto {
    private String familiya;
    private String ism;
    private String tugilganSana;
    private String tugilganJoy;
    private String jinsi;
    private String fuqaroligi;
    private String passportRaqami;
    private String authority;
    private String berilganSana;
    private String amalQilishMuddati;
}