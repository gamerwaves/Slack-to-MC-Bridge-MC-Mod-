package com.gamer_waves.slackbridge.emoji;

import com.gamer_waves.slackbridge.SlackBridge;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

public class ResourcePackServer {
    private HttpServer server;
    private final int port;
    private Path resourcePackPath;

    public ResourcePackServer(int port) {
        this.port = port;
    }

    public void start(Path resourcePackPath) {
        this.resourcePackPath = resourcePackPath;
        
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/resourcepack.zip", this::handleRequest);
            server.setExecutor(null);
            server.start();
            
            SlackBridge.LOGGER.info("Resource pack server started on port {}", port);
            SlackBridge.LOGGER.info("Resource pack URL: http://localhost:{}/resourcepack.zip", port);
        } catch (IOException e) {
            SlackBridge.LOGGER.error("Failed to start resource pack server", e);
        }
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        if (resourcePackPath == null || !Files.exists(resourcePackPath)) {
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
            return;
        }

        byte[] data = Files.readAllBytes(resourcePackPath);
        
        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        exchange.sendResponseHeaders(200, data.length);
        
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
        
        SlackBridge.LOGGER.debug("Served resource pack to {}", exchange.getRemoteAddress());
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            SlackBridge.LOGGER.info("Resource pack server stopped");
        }
    }

    public String getUrl(String serverIp) {
        if (serverIp == null || serverIp.isEmpty()) {
            serverIp = "localhost";
        }
        return "http://" + serverIp + ":" + port + "/resourcepack.zip";
    }
}
