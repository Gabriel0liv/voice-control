package net.voicecontrol.recording;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.mp3.Mp3Encoder;
import net.voicecontrol.VoiceControlVoicePlugin;
import net.voicecontrol.logging.AdminLogger;

import javax.sound.sampled.AudioFormat;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AudioEncoderWrapper {
    private final File outputFile;
    private final boolean useMp3;
    private Mp3Encoder mp3Encoder;
    private OutputStream outputStream;
    private int wavDataSize = 0;
    private boolean closed = false;

    public AudioEncoderWrapper(File baseFile, boolean requestMp3) throws IOException {
        boolean mp3Success = false;
        File mp3File = new File(baseFile.getPath() + ".mp3");

        if (requestMp3) {
            try {
                VoicechatServerApi api = VoiceControlVoicePlugin.getServerApi();
                if (api != null) {
                    this.outputStream = new BufferedOutputStream(new FileOutputStream(mp3File));
                    // 48000 Hz, 16 bits, Mono, signed=true, bigEndian=false
                    AudioFormat format = new AudioFormat(48000.0f, 16, 1, true, false);
                    this.mp3Encoder = api.createMp3Encoder(format, 128000, 2, this.outputStream);
                    if (this.mp3Encoder != null) {
                        mp3Success = true;
                    } else {
                        this.outputStream.close();
                        if (mp3File.exists()) {
                            mp3File.delete();
                        }
                    }
                }
            } catch (Throwable t) {
                AdminLogger.warn("SYSTEM", "Failed to initialize MP3 encoder, falling back to WAV: " + t.getMessage());
                if (this.outputStream != null) {
                    try { this.outputStream.close(); } catch (IOException ignored) {}
                }
                if (mp3File.exists()) {
                    mp3File.delete();
                }
            }
        }

        if (mp3Success) {
            this.useMp3 = true;
            this.outputFile = mp3File;
        } else {
            this.useMp3 = false;
            this.mp3Encoder = null;
            // Set up WAV writing
            File wavFile = new File(baseFile.getPath() + ".wav");
            this.outputFile = wavFile;
            this.outputStream = new BufferedOutputStream(new FileOutputStream(wavFile));
            RecordingStorage.writeWavHeader(this.outputStream, 0);
        }
    }

    public File getOutputFile() {
        return outputFile;
    }

    public String getFormatExtension() {
        return useMp3 ? "mp3" : "wav";
    }

    public synchronized void write(short[] pcmData) throws IOException {
        if (closed) return;
        if (useMp3) {
            mp3Encoder.encode(pcmData);
        } else {
            // Write as WAV (little-endian shorts to bytes)
            ByteBuffer byteBuffer = ByteBuffer.allocate(pcmData.length * 2);
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
            for (short sample : pcmData) {
                byteBuffer.putShort(sample);
            }
            byte[] bytes = byteBuffer.array();
            outputStream.write(bytes);
            wavDataSize += bytes.length;
        }
    }

    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        
        if (useMp3) {
            if (mp3Encoder != null) {
                try {
                    mp3Encoder.close();
                } catch (Throwable t) {
                    AdminLogger.error("SYSTEM", "Error closing MP3 encoder: " + t.getMessage());
                }
            }
            if (outputStream != null) {
                outputStream.close();
            }
        } else {
            if (outputStream != null) {
                outputStream.close();
            }
            // Rewrite WAV header with actual data size
            RecordingStorage.fixWavHeader(outputFile, wavDataSize);
        }
    }

    public boolean isMp3() {
        return useMp3;
    }
}
