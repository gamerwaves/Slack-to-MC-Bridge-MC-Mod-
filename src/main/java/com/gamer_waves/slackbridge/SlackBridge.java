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
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import com.slack.api.model.block.element.ImageElement;

import java.util.ArrayList;
import java.util.List;

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

                    String channelId = event.getChannel();
                    if (channelId == null || !channelId.equals(currentConfig.slack_channel)) return ctx.ack();

                    try {
                        StringBuilder messageBlock = new StringBuilder();
                        String userId = event.getUser();
                        String displayName = getDisplayName(userId);

                        if (event.getThreadTs() != null && !event.getThreadTs().equals(event.getTs())) {
                            // Fetch all messages in the thread
                            var repliesResp = slackApp.client().conversationsReplies(r -> r
                                    .channel(channelId)
                                    .ts(event.getThreadTs()));
                            if (repliesResp.isOk()) {
                                boolean first = true;
                                for (var msg : repliesResp.getMessages()) {
                                    String msgUserId = msg.getUser();
                                    String msgText = msg.getText();
                                    String msgName = getDisplayName(msgUserId);
                                    if (first) {
                                        messageBlock.append("Γ [Slack] <").append(msgName).append("> ").append(msgText).append("\n");
                                        first = false;
                                    } else {
                                        messageBlock.append("| [Reply] <").append(msgName).append("> ").append(msgText).append("\n");
                                    }
                                }
                            }
                        } else {
                            messageBlock.append("[Slack] <").append(displayName).append("> ").append(event.getText());
                        }

                        broadcastToMinecraft(messageBlock.toString().trim());
                    } catch (Exception e) {}

                    return ctx.ack();
                });


                slackApp.event(MessageBotEvent.class, (payload, ctx) -> ctx.ack());

                slackApp.command("/list", (req, ctx) -> {
                    MinecraftServer server = currentServer;
                    if (server == null) {
                        return ctx.ack("Server is not running");
                    }

                    int playerCount = server.getCurrentPlayerCount();
                    int maxPlayers = server.getMaxPlayerCount();

                    List<LayoutBlock> blocks = new ArrayList<>();
                    
                    blocks.add(SectionBlock.builder()
                            .text(MarkdownTextObject.builder()
                                    .text("*Players online: " + playerCount + "/" + maxPlayers + "*")
                                    .build())
                            .build());

                    if (playerCount > 0) {
                        server.getPlayerManager().getPlayerList().forEach(player -> {
                            String playerName = player.getName().getString();
                            String uuid = player.getUuidAsString();
                            String avatarUrl = "https://cravatar.eu/avatar/" + uuid + "/32";
                            
                            blocks.add(SectionBlock.builder()
                                    .text(MarkdownTextObject.builder()
                                            .text(playerName)
                                            .build())
                                    .accessory(ImageElement.builder()
                                            .imageUrl(avatarUrl)
                                            .altText(playerName)
                                            .build())
                                    .build());
                        });
                    } else {
                        blocks.add(SectionBlock.builder()
                                .text(MarkdownTextObject.builder()
                                        .text("_No players online_")
                                        .build())
                                .build());
                    }

                    return ctx.ack(r -> r.blocks(blocks));
                });

                slackApp.command("/whois", (req, ctx) -> {
                    MinecraftServer server = currentServer;
                    if (server == null) {
                        return ctx.ack("Server is not running");
                    }

                    String playerName = req.getPayload().getText().trim();
                    if (playerName.isEmpty()) {
                        return ctx.ack("Usage: `/whois <player_name>`");
                    }

                    var player = server.getPlayerManager().getPlayer(playerName);
                    if (player == null) {
                        return ctx.ack("Player `" + playerName + "` is not online");
                    }

                    String uuid = player.getUuidAsString();
                    String avatarUrl = "https://cravatar.eu/avatar/" + uuid + "/64";
                    
                    double health = player.getHealth();
                    double maxHealth = player.getMaxHealth();
                    int foodLevel = player.getHungerManager().getFoodLevel();
                    
                    var pos = player.getBlockPos();
                    String location = String.format("X: %d, Y: %d, Z: %d", pos.getX(), pos.getY(), pos.getZ());
                    String world = player.getEntityWorld().getRegistryKey().getValue().toString();
                    String gameMode = player.interactionManager.getGameMode().asString();
                    
                    long lastSeenTicks = server.getTicks() - player.getLastActionTime();
                    boolean isAFK = lastSeenTicks > 6000; // 5 minutes
                    String afkStatus = isAFK ? "AFK (" + (lastSeenTicks / 20 / 60) + "m)" : "Active";
                    
                    StringBuilder info = new StringBuilder();
                    info.append("*Player Info: ").append(player.getName().getString()).append("*\n\n");
                    info.append("• *Health:* ").append(String.format("%.1f/%.1f", health, maxHealth)).append("\n");
                    info.append("• *Hunger:* ").append(foodLevel).append("/20\n");
                    info.append("• *Game Mode:* ").append(gameMode).append("\n");
                    info.append("• *Status:* ").append(afkStatus).append("\n");
                    info.append("• *World:* `").append(world).append("`\n");
                    info.append("• *Location:* `").append(location).append("`\n");
                    info.append("• *UUID:* `").append(uuid).append("`");

                    List<LayoutBlock> blocks = new ArrayList<>();
                    blocks.add(SectionBlock.builder()
                            .text(MarkdownTextObject.builder()
                                    .text(info.toString())
                                    .build())
                            .accessory(ImageElement.builder()
                                    .imageUrl(avatarUrl)
                                    .altText(player.getName().getString())
                                    .build())
                            .build());

                    return ctx.ack(r -> r.blocks(blocks));
                });

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
