package org.example.passportocr.configuration;


import lombok.extern.slf4j.Slf4j;
import org.example.passportocr.exeption.OcrBusyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.SocketTimeoutException;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OcrBusyException.class)
    public ResponseEntity<?> busy(OcrBusyException e) {
        return ResponseEntity.status(429).body(Map.of(
                "error", "OCR_BUSY",
                "message", e.getMessage()
        ));
    }

    @ExceptionHandler({SocketTimeoutException.class})
    public ResponseEntity<?> timeout(Exception e) {
        return ResponseEntity.status(504).body(Map.of(
                "error", "OCR_TIMEOUT",
                "message", "OCR exceeded 20s limit"
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> other(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "INTERNAL_ERROR",
                "message", e.getMessage()
        ));
    }
}
