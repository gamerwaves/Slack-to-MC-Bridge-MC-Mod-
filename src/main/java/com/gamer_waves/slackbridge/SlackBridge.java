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
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

public class SlackBridge implements ModInitializer {
	public static final String MOD_ID = "slackbridge";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		System.out.println("[Slack Bridge] Loading Fabric webhook listener...");
        ServerLifecycleEvents.SERVER_STARTED.register(server -> currentServer = server);
        startWebhookServer();
	}
    private static volatile MinecraftServer currentServer = null;
    private static final Gson GSON = new Gson();
	private void startWebhookServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            server.createContext("/webhook", exchange -> {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

                    System.out.println("Webhook received: " + body);

                    
                    String displayUser = null;
                    String textMsg = body;
                    try {
                        JsonObject json = GSON.fromJson(body, JsonObject.class);
                        if (json != null) {
                            if (json.has("user") && !json.get("user").isJsonNull()) {
                                displayUser = json.get("user").getAsString();
                            }
                            if (json.has("text") && !json.get("text").isJsonNull()) {
                                textMsg = json.get("text").getAsString();
                            }
                        }
                    } catch (JsonSyntaxException e) {
						System.err.println("Failed to parse JSON payload: " + e.getMessage());
                    }

                    MinecraftServer serverMC = currentServer;

                    if (serverMC != null) {
                        final String broadcast = (displayUser != null)
                                ? ("[Slack] <" + displayUser + "> " + textMsg)
                                : ("[Slack] " + textMsg);

                        serverMC.execute(() -> {
                            serverMC.getPlayerManager().broadcast(
                                    net.minecraft.text.Text.literal(broadcast),
                                    false
                            );
                        });
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
            System.out.println("[Slack Bridge] Webhook server started on port 8080");

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}