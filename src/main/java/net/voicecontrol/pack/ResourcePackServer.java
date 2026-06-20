package net.voicecontrol.pack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.voicecontrol.Config;
import net.voicecontrol.VoiceControlMod;
import net.voicecontrol.logging.AdminLogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

public class ResourcePackServer {
    private static HttpServer serverInstance = null;

    public static synchronized void startServer() {
        if (serverInstance != null) return;
        int port = Config.SERVER.resourcePackHttpPort.get();
        try {
            serverInstance = HttpServer.create(new InetSocketAddress(port), 0);
            serverInstance.createContext("/voicecontrol-pack.zip", new FileServerHandler());
            serverInstance.setExecutor(null);
            serverInstance.start();
            AdminLogger.info("SYSTEM", "Internal HTTP server started on port " + port);
        } catch (IOException e) {
            AdminLogger.error("SYSTEM", "Failed to start internal HTTP server on port " + port + ": " + e.getMessage());
        }
    }

    public static synchronized void stopServer() {
        if (serverInstance != null) {
            serverInstance.stop(0);
            serverInstance = null;
            AdminLogger.info("SYSTEM", "Internal HTTP server stopped.");
        }
    }

    public static synchronized boolean isRunning() {
        return serverInstance != null;
    }

    private static class FileServerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1); // Method Not Allowed
                return;
            }

            File packFile = ResourcePackBuilder.getLastBuiltPack();
            if (packFile == null || !packFile.exists()) {
                packFile = VoiceControlMod.getBaseFolder().resolve("resourcepack/build/voicecontrol-pack.zip").toFile();
            }

            if (!packFile.exists()) {
                String response = "Resource pack not found. Please run '/voicectl audio reload' first.";
                exchange.sendResponseHeaders(404, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, packFile.length());

            try (FileInputStream fis = new FileInputStream(packFile);
                 OutputStream os = exchange.getResponseBody()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = fis.read(buffer)) > 0) {
                    os.write(buffer, 0, read);
                }
            }
        }
    }
}
