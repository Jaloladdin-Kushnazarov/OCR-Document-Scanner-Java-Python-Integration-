package org.example.passportocr.exeption;


public class OcrBusyException extends RuntimeException {
    public OcrBusyException(String message) {
        super(message);
    }
}
