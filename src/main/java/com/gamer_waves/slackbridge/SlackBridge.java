package com.gamer_waves.slackbridge;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.users.UsersInfoResponse;
import com.slack.api.model.event.MessageBotEvent;
import com.slack.api.model.event.MessageEvent;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;

public class SlackBridge implements ModInitializer {
    public static final String MOD_ID = "slackbridge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile MinecraftServer currentServer = null;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static SocketModeApp socket;
    private static App slackApp;
    private static final AtomicBoolean slackInitialized = new AtomicBoolean(false);

    private static Config currentConfig = null;
    private static final String CONFIG_DIR = "config";
    private static final String CONFIG_FILE = "slackbridge.json";

    private static final Queue<String> pendingMessages = new ConcurrentLinkedQueue<>();

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            currentServer = server;
            server.execute(() -> {
                while (!pendingMessages.isEmpty()) {
                    server.getPlayerManager().broadcast(Text.literal(pendingMessages.poll()), false);
                }
            });
        });

        currentConfig = Config.loadConfig();

        if (slackInitialized.compareAndSet(false, true)) {
            initSlackSocketMode();
        }

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            String name = handler.player.getName().getString();
            String uuid = handler.player.getUuidAsString();
            sendSlackMessageFromPlayer(name, uuid, "*" + name + "* joined the game");
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            String name = handler.player.getName().getString();
            String uuid = handler.player.getUuidAsString();
            sendSlackMessageFromPlayer(name, uuid, "*" + name + "* left the game");
        });

        // ServerMessageEvents.CHAT_MESSAGE.register((msg, sender, params) -> {
        //     String name = sender.getName().getString();
        //     String uuid = sender.getUuidAsString();
        //     String text = msg.getContent().getString();
        //     sendSlackMessageFromPlayer(name, uuid, text);
        // });
    }

    private static class Config {
        public String slack_channel = "";
        public String slack_bot_token = "";
        public String slack_app_token = "";

        static Config loadConfig() {
            Path path = Paths.get(CONFIG_DIR, CONFIG_FILE);
            try {
                if (!Files.exists(path)) {
                    Files.createDirectories(path.getParent());
                    Config def = new Config();
                    saveConfig(def);
                    return def;
                }
                try (Reader r = Files.newBufferedReader(path)) {
                    Config cfg = GSON.fromJson(r, Config.class);
                    if (cfg == null) cfg = new Config();
                    saveConfig(cfg);
                    return cfg;
                }
            } catch (Exception e) {
                return new Config();
            }
        }

        static void saveConfig(Config cfg) {
            Path path = Paths.get(CONFIG_DIR, CONFIG_FILE);
            try {
                Files.createDirectories(path.getParent());
                Gson pretty = new GsonBuilder().setPrettyPrinting().create();
                try (Writer w = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    pretty.toJson(cfg, w);
                }
            } catch (Exception e) {}
        }
    }

    private void initSlackSocketMode() {
        new Thread(() -> {
            try {
                if (currentConfig.slack_bot_token.isBlank() || currentConfig.slack_app_token.isBlank()) return;

                AppConfig config = AppConfig.builder()
                        .singleTeamBotToken(currentConfig.slack_bot_token)
                        .build();

                slackApp = new App(config);

                Pattern sdk = Pattern.compile(".*");
                slackApp.message(sdk, (payload, ctx) -> {
                    MessageEvent event = payload.getEvent();
                    if (event == null) return ctx.ack();

                    String text = event.getText();
                    String channelId = event.getChannel();
                    String mainChannel = currentConfig.slack_channel;
                    if (channelId == null || !channelId.equals(mainChannel)) return ctx.ack();

                    String userId = event.getUser();
                    String displayName = getDisplayName(userId);

                    broadcastToMinecraft("<" + displayName + "> " + text);
                    return ctx.ack();
                });

                slackApp.event(MessageBotEvent.class, (payload, ctx) -> ctx.ack());

                socket = new SocketModeApp(currentConfig.slack_app_token, slackApp);
                socket.startAsync();

            } catch (Exception e) {}
        }, "SlackBridge-SocketThread").start();
    }

    private static String getDisplayName(String userId) {
        if (slackApp == null || userId == null || userId.isBlank()) return userId;
        try {
            UsersInfoResponse response = slackApp.client().usersInfo(r -> r.user(userId));
            if (response.isOk() && response.getUser() != null) {
                String displayName = response.getUser().getProfile().getDisplayName();
                if (displayName != null && !displayName.isBlank()) return displayName;
                String realName = response.getUser().getProfile().getRealName();
                if (realName != null && !realName.isBlank()) return realName;
            }
        } catch (Exception e) {}
        return userId;
    }

    public static void sendSlackMessageFromPlayer(String playerName, String uuid, String message) {
        if (slackApp == null || currentConfig == null || currentConfig.slack_channel.isBlank()) return;

        String icon = "https://cravatar.eu/avatar/" + uuid + "/512?u=" + System.currentTimeMillis();
        System.out.println(icon);

        try {
            ChatPostMessageResponse resp = slackApp.client().chatPostMessage(r -> r
                    .channel(currentConfig.slack_channel)
                    .text(message)
                    .username(playerName)
                    .iconUrl(icon)
            );
        } catch (IOException | SlackApiException e) {}
    }

    private static void broadcastToMinecraft(String msg) {
        MinecraftServer server = currentServer;
        if (server == null) {
            pendingMessages.add(msg);
            return;
        }

        server.execute(() -> {
            while (!pendingMessages.isEmpty()) {
                server.getPlayerManager().broadcast(Text.literal(pendingMessages.poll()), false);
            }
            server.getPlayerManager().broadcast(Text.literal(msg), false);
        });
    }
}
