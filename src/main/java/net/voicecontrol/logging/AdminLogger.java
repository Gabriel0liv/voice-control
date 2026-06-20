package net.voicecontrol.logging;

import net.voicecontrol.VoiceControlMod;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class AdminLogger {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static synchronized void log(String level, String operator, String message) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String logEntry = String.format("[%s] [%s] [%s]: %s", timestamp, level, operator, message);
        
        // Log to MC console
        if ("ERROR".equals(level)) {
            VoiceControlMod.LOGGER.error(logEntry);
        } else if ("WARN".equals(level)) {
            VoiceControlMod.LOGGER.warn(logEntry);
        } else {
            VoiceControlMod.LOGGER.info(logEntry);
        }

        // Log to admin.log file
        Path logFile = VoiceControlMod.getBaseFolder().resolve("logs/admin.log");
        try (FileWriter fw = new FileWriter(logFile.toFile(), true);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.println(logEntry);
        } catch (IOException e) {
            VoiceControlMod.LOGGER.error("Failed to write to admin log file", e);
        }
    }

    public static void info(String operator, String message) {
        log("INFO", operator, message);
    }

    public static void warn(String operator, String message) {
        log("WARN", operator, message);
    }

    public static void error(String operator, String message) {
        log("ERROR", operator, message);
    }
}
