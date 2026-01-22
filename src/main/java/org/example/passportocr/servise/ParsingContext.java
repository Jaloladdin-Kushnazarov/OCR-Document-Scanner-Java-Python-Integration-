package org.example.passportocr.servise;

public class ParsingContext {
    private String text;

    public ParsingContext(String text) {
        this.text = text;
    }

    public String get() {
        return text;
    }

    public void removeMatched(String matched) {
        if (matched != null && !matched.isBlank()) {
            this.text = this.text.replace(matched, " ");
            this.text = this.text.replaceAll("\\s+", " ").trim(); // normalize again
        }
    }
}
