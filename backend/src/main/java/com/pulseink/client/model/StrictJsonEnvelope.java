package com.pulseink.client.model;

/** Removes only an exact, single Markdown JSON fence; all surrounding text remains invalid. */
public final class StrictJsonEnvelope {

    private StrictJsonEnvelope() {}

    public static String unwrap(String output) {
        if (output == null) return null;
        String text = output.strip();
        if (!text.startsWith("```")) return text;
        int newline = text.indexOf('\n');
        if (newline < 0 || !text.endsWith("```")) return text;
        String header = text.substring(0, newline).strip().toLowerCase(java.util.Locale.ROOT);
        if (!header.equals("```") && !header.equals("```json")) return text;
        String body = text.substring(newline + 1, text.length() - 3).strip();
        if (body.contains("```")) return text;
        return body;
    }
}
