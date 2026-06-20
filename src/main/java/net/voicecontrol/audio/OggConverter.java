package net.voicecontrol.audio;

import net.voicecontrol.logging.AdminLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class OggConverter {
    private static Boolean ffmpegAvailable = null;

    public static synchronized boolean isFFmpegAvailable() {
        if (ffmpegAvailable != null) {
            return ffmpegAvailable;
        }

        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").start();
            p.getInputStream().close();
            p.getOutputStream().close();
            p.getErrorStream().close();
            int exitCode = p.waitFor();
            ffmpegAvailable = (exitCode == 0);
        } catch (Exception e) {
            ffmpegAvailable = false;
        }

        if (!ffmpegAvailable) {
            AdminLogger.warn("SYSTEM", "FFmpeg was not found in the system PATH! Custom WAV/MP3 files cannot be automatically converted to OGG. Please install FFmpeg or place pre-converted .ogg files directly.");
        } else {
            AdminLogger.info("SYSTEM", "FFmpeg found and verified successfully.");
        }

        return ffmpegAvailable;
    }

    public static boolean convertToOgg(File input, File output) {
        if (!isFFmpegAvailable()) {
            AdminLogger.error("SYSTEM", "FFmpeg is unavailable. Transcoding skipped for: " + input.getName());
            return false;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg",
                    "-y",
                    "-i",
                    input.getAbsolutePath(),
                    "-acodec",
                    "libvorbis",
                    output.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();

            // Read output stream to prevent process blocks
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (reader.readLine() != null) {
                    // Consume output stream
                }
            }

            int exitCode = p.waitFor();
            if (exitCode == 0) {
                AdminLogger.info("SYSTEM", "Converted " + input.getName() + " -> " + output.getName());
                return true;
            } else {
                AdminLogger.error("SYSTEM", "FFmpeg exit code " + exitCode + " for file " + input.getName());
                return false;
            }
        } catch (Exception e) {
            AdminLogger.error("SYSTEM", "Failed to transcode " + input.getName() + " to OGG: " + e.getMessage());
            return false;
        }
    }
}
