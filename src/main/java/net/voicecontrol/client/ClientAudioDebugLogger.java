package net.voicecontrol.client;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientAudioDebugLogger {
    private static File logFile;
    private static final Map<String, Long> lastLoggedTimes = new ConcurrentHashMap<>();
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    public static void init(File gameDir) {
        try {
            File logDir = new File(gameDir, "voice-control/logs");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            logFile = new File(logDir, "client-audio.log");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static synchronized void log(String level, String message) {
        if (logFile == null) return;
        try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, true))) {
            String timestamp = DATE_FORMAT.format(new Date());
            writer.println(String.format("[%s] [%s] %s", timestamp, level, message));
        } catch (Exception e) {
            System.err.println("Failed to write client audio log: " + e.getMessage());
        }
    }

    public static void info(String message) {
        log("INFO", message);
    }

    public static void warn(String message) {
        log("WARN", message);
    }

    public static void error(String message) {
        log("ERROR", message);
    }

    public static void warnOnceEvery(String key, String message, long intervalMillis) {
        long now = System.currentTimeMillis();
        Long lastLogged = lastLoggedTimes.get(key);
        if (lastLogged == null || (now - lastLogged) >= intervalMillis) {
            lastLoggedTimes.put(key, now);
            warn(message);
        }
    }

    public static void errorOnceEvery(String key, String message, long intervalMillis) {
        long now = System.currentTimeMillis();
        Long lastLogged = lastLoggedTimes.get(key);
        if (lastLogged == null || (now - lastLogged) >= intervalMillis) {
            lastLoggedTimes.put(key, now);
            error(message);
        }
    }
}
