package net.voicecontrol.audio;

public class Sanitizer {
    public static String sanitize(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return "";
        }
        
        // Normalize path separators
        String normalized = relativePath.replace('\\', '/');
        
        // Remove file extension
        int lastDot = normalized.lastIndexOf('.');
        String pathWithoutExt = lastDot > 0 ? normalized.substring(0, lastDot) : normalized;
        
        // Split by '/' to sanitize each folder/file segment
        String[] segments = pathWithoutExt.split("/");
        StringBuilder sb = new StringBuilder();
        
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            
            // Lowercase, replace spaces with underscores, and keep only valid characters
            String sanitized = segment.toLowerCase()
                    .replace(' ', '_')
                    .replaceAll("[^a-z0-9_\\-]", "_")
                    .replaceAll("_{2,}", "_")
                    .replaceAll("-{2,}", "-");
            
            // Remove leading/trailing separators in segment
            while (sanitized.startsWith("_") || sanitized.startsWith("-")) {
                sanitized = sanitized.substring(1);
            }
            while (sanitized.endsWith("_") || sanitized.endsWith("-")) {
                sanitized = sanitized.substring(0, sanitized.length() - 1);
            }
            
            if (!sanitized.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('/');
                }
                sb.append(sanitized);
            }
        }
        
        return sb.toString();
    }
}

