package net.voicecontrol.audio;

public class Sanitizer {
    public static String sanitize(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        
        // Remove file extension
        int lastDot = filename.lastIndexOf('.');
        String nameWithoutExt = lastDot > 0 ? filename.substring(0, lastDot) : filename;
        
        // Convert to lowercase, replace non-alphanumeric (except underscores and hyphens) with underscores
        String sanitized = nameWithoutExt.toLowerCase()
                .replaceAll("[^a-z0-9_\\-]", "_")
                .replaceAll("_{2,}", "_")
                .replaceAll("-{2,}", "-");
        
        // Remove leading/trailing separators
        while (sanitized.startsWith("_") || sanitized.startsWith("-")) {
            sanitized = sanitized.substring(1);
        }
        while (sanitized.endsWith("_") || sanitized.endsWith("-")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        
        return sanitized;
    }
}
