package com.gamer_waves.slackbridge;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.StandardOpenOption;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

public class SlackBridge implements ModInitializer {
    public static final String MOD_ID = "slackbridge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile MinecraftServer currentServer = null;
    private static final Gson GSON = new Gson();
    private static final String CONFIG_DIR = "config";
    private static final String CONFIG_FILE = "slackbridge.json";
    private static final Queue<String> pendingMessages = new ConcurrentLinkedQueue<>();

    @Override
    public void onInitialize() {
        System.out.println("[Slack Bridge] Loading Fabric webhook listener...");
        ServerLifecycleEvents.SERVER_STARTED.register(server -> currentServer = server);
        startWebhookServer();
    }

    private static class Config {
        public String host = "";
        public int port = 8080;
        public String path = "/webhook";

        static Config loadConfig() {
            Path cfgPath = Paths.get(CONFIG_DIR, CONFIG_FILE);
            try {
                if (!Files.exists(cfgPath)) {
                    Files.createDirectories(cfgPath.getParent());
                    Config defaults = new Config();
                    Gson pretty = new GsonBuilder().setPrettyPrinting().create();
                    try (Writer w = Files.newBufferedWriter(cfgPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        pretty.toJson(defaults, w);
                    }
                    System.out.println("[Slack Bridge] Generated default config at " + cfgPath.toString());
                    return defaults;
                } else {
                    try (Reader r = Files.newBufferedReader(cfgPath)) {
                        Config loaded = GSON.fromJson(r, Config.class);
                        if (loaded == null) return new Config();
                        return loaded;
                    }
                }
            } catch (Exception e) {
                System.err.println("[Slack Bridge] Failed to load config, using defaults: " + e.getMessage());
                try {
                    Files.createDirectories(cfgPath.getParent());
                    Config defaults = new Config();
                    Gson pretty = new GsonBuilder().setPrettyPrinting().create();
                    try (Writer w = Files.newBufferedWriter(cfgPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        pretty.toJson(defaults, w);
                    }
                    System.out.println("[Slack Bridge] Wrote default config to " + cfgPath.toString());
                } catch (Exception ignored) {} //ignore
                return new Config();
            }
        }
    }

    private void startWebhookServer() {
        try {
            Config cfg = Config.loadConfig();

            int port = cfg.port;
            String path = cfg.path;
            String host = cfg.host;

            String portEnv = System.getenv("SLACKBRIDGE_PORT");
            if (portEnv != null) {
                try { port = Integer.parseInt(portEnv); } catch (NumberFormatException ignored) {} //ignore
            }

            String pathEnv = System.getenv("SLACKBRIDGE_PATH");
            if (pathEnv != null && !pathEnv.isBlank()) path = pathEnv.startsWith("/") ? pathEnv : ("/" + pathEnv);

            String hostEnv = System.getenv("SLACKBRIDGE_HOST");
            if (hostEnv != null && !hostEnv.isBlank()) host = hostEnv;

            InetSocketAddress bindAddress = (host != null && !host.isBlank())
                    ? new InetSocketAddress(host, port)
                    : new InetSocketAddress(port);

            HttpServer server = HttpServer.create(bindAddress, 0);
            server.createContext(path, exchange -> {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

                    String displayUser = null;
                    String textMsg = null;
                    String parentUser = null;
                    String parentText = null;
                    String type = null;

                    try {
                        JsonObject json = GSON.fromJson(body, JsonObject.class);
                        if (json != null) {
                            if (json.has("user")) displayUser = json.get("user").getAsString();
                            if (json.has("text")) textMsg = json.get("text").getAsString();
                            if (json.has("parent_text")) parentText = json.get("parent_text").getAsString();
                            if (json.has("parent_user")) parentUser = json.get("parent_user").getAsString();
                            if (json.has("type")) type = json.get("type").getAsString();
                        }
                    } catch (JsonSyntaxException e) {
                        System.err.println("Failed to parse JSON payload: " + e.getMessage());
                    }

                    MinecraftServer serverMC = currentServer;

                    String finalBroadcast;
                    if ("thread_reply".equals(type) && parentText != null) {
                        finalBroadcast = "[Slack] Γ <" + parentUser + "> " + parentText + "\n" +
                                         "[Reply] |  <" + displayUser + "> " + textMsg;
                    } else {
                        finalBroadcast = "[Slack] <" + displayUser + "> " + textMsg;
                    }

                    if (serverMC != null) {
                        serverMC.execute(() -> {
                            while (!pendingMessages.isEmpty()) {
                                serverMC.getPlayerManager().broadcast(
                                    net.minecraft.text.Text.literal(pendingMessages.poll()),
                                    false
                                );
                            }
                            serverMC.getPlayerManager().broadcast(
                                net.minecraft.text.Text.literal(finalBroadcast),
                                false
                            );
                        });
                    } else {
                        pendingMessages.add(finalBroadcast);
                    }

                    String response = "Webhook received";
                    exchange.sendResponseHeaders(200, response.length());
                    OutputStream os = exchange.getResponseBody();
                    os.write(response.getBytes());
                    os.close();

                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
            });

            server.start();
            String bindInfo = (bindAddress.getAddress() != null && !bindAddress.getAddress().isAnyLocalAddress())
                    ? (bindAddress.getHostString() + ":" + port)
                    : ("0.0.0.0:" + port);

            System.out.println("[Slack Bridge] Webhook server started on " + bindInfo + " path " + path
                    + " (config: " + Paths.get(CONFIG_DIR, CONFIG_FILE).toString() + ")");

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
