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
import com.slack.api.methods.response.users.UsersInfoResponse;
import com.slack.api.model.event.MessageBotEvent;
import com.slack.api.model.event.MessageEvent;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import com.slack.api.model.block.element.ImageElement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import com.gamer_waves.slackbridge.commands.UnlinkCommand;

public class SlackBridge implements ModInitializer {
    public static final String MOD_ID = "slackbridge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile MinecraftServer currentServer = null;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static SocketModeApp socket;
    private static App slackApp;
    private static final AtomicBoolean slackInitialized = new AtomicBoolean(false);
    
    private static com.gamer_waves.slackbridge.emoji.ResourcePackServer resourcePackServer;
    private static com.gamer_waves.slackbridge.emoji.EmoggEmojiDownloader emojiDownloader;

    private static Config currentConfig = null;
    private static final String CONFIG_DIR = "config";
    private static final String CONFIG_FILE = "slackbridge.json";
    private static final String LINKS_FILE = "slackbridge_links.json";

    private static final Queue<String> pendingMessages = new ConcurrentLinkedQueue<>();
    private static AccountLinks accountLinks = null;
    private static final Map<String, LinkCodeData> pendingLinkCodes = new HashMap<>();
    private static final Random random = new Random();
    private static final int CODE_EXPIRY_SECONDS = 300; // 5 minutes

    private static class LinkCodeData {
        String uuid;
        String username;
        long expiryTime;

        LinkCodeData(String uuid, String username, long expiryTime) {
            this.uuid = uuid;
            this.username = username;
            this.expiryTime = expiryTime;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
    }

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
        accountLinks = AccountLinks.loadLinks();

        if (slackInitialized.compareAndSet(false, true)) {
            initSlackSocketMode();
        }

        // Start Emogg emoji downloader and resource pack server
        if (currentConfig.slack_bot_token != null && !currentConfig.slack_bot_token.isBlank()) {
            emojiDownloader = new com.gamer_waves.slackbridge.emoji.EmoggEmojiDownloader(currentConfig.slack_bot_token);
            emojiDownloader.start();
            
            // Start resource pack server after a delay (wait for ZIP to be created)
            new Thread(() -> {
                try {
                    Thread.sleep(90000); // Wait 90 seconds for downloads and ZIP creation
                    if (emojiDownloader.getZipFile() != null && 
                        java.nio.file.Files.exists(emojiDownloader.getZipFile())) {
                        
                        resourcePackServer = new com.gamer_waves.slackbridge.emoji.ResourcePackServer(8080);
                        resourcePackServer.start(emojiDownloader.getZipFile());
                        
                        LOGGER.info("Players will receive Emogg resource pack on join");
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to start resource pack server", e);
                }
            }, "ResourcePackServerInit").start();
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            UnlinkCommand.register(dispatcher);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            String name = handler.player.getName().getString();
            String uuid = handler.player.getUuidAsString();
            
            if (accountLinks.getSlackId(uuid) == null) {
                String linkCode = generateLinkCode();
                long expiryTime = System.currentTimeMillis() + (CODE_EXPIRY_SECONDS * 1000);
                pendingLinkCodes.put(linkCode, new LinkCodeData(uuid, name, expiryTime));
                
                Text disconnectMessage = Text.literal(
                    "§c§l§nYou must link your Slack account to join the server!\n\n" +
                    "§r§ePlease run §6/link " + linkCode + "§e in Slack to link your account.\n\n" +
                    "§7§oThis code expires in " + CODE_EXPIRY_SECONDS + " seconds."
                );
                
                handler.player.networkHandler.disconnect(disconnectMessage);
            } else {
                sendSlackMessageFromPlayer(name, uuid, "joined the game");
                
                // Send Emogg resource pack
                sendResourcePackToPlayer(handler.player);
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            String name = handler.player.getName().getString();
            String uuid = handler.player.getUuidAsString();
            
            if (accountLinks.getSlackId(uuid) != null) {
                sendSlackMessageFromPlayer(name, uuid, "left the game");
            }
        });

        // ServerMessageEvents.CHAT_MESSAGE.register((msg, sender, params) -> {
        //     String name = sender.getName().getString();
        //     String uuid = sender.getUuidAsString();
        //     String text = msg.getContent().getString();
        //     text = processMcMentions(text);
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

    private static class AccountLinks {
        public Map<String, String> mcUuidToSlackId = new HashMap<>();
        public Map<String, String> slackIdToMcUuid = new HashMap<>();

        static AccountLinks loadLinks() {
            Path path = Paths.get(CONFIG_DIR, LINKS_FILE);
            try {
                if (!Files.exists(path)) {
                    Files.createDirectories(path.getParent());
                    AccountLinks def = new AccountLinks();
                    saveLinks(def);
                    return def;
                }
                try (Reader r = Files.newBufferedReader(path)) {
                    AccountLinks links = GSON.fromJson(r, AccountLinks.class);
                    if (links == null) links = new AccountLinks();
                    return links;
                }
            } catch (Exception e) {
                return new AccountLinks();
            }
        }

        static void saveLinks(AccountLinks links) {
            Path path = Paths.get(CONFIG_DIR, LINKS_FILE);
            try {
                Files.createDirectories(path.getParent());
                Gson pretty = new GsonBuilder().setPrettyPrinting().create();
                try (Writer w = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    pretty.toJson(links, w);
                }
            } catch (Exception e) {}
        }

        void linkAccounts(String mcUuid, String slackId) {
            mcUuidToSlackId.put(mcUuid, slackId);
            slackIdToMcUuid.put(slackId, mcUuid);
            saveLinks(this);
        }

        void unlinkByUuid(String mcUuid) {
            String slackId = mcUuidToSlackId.remove(mcUuid);
            if (slackId != null) {
                slackIdToMcUuid.remove(slackId);
                saveLinks(this);
            }
        }

        void unlinkBySlackId(String slackId) {
            String mcUuid = slackIdToMcUuid.remove(slackId);
            if (mcUuid != null) {
                mcUuidToSlackId.remove(mcUuid);
                saveLinks(this);
            }
        }

        String getSlackId(String mcUuid) {
            return mcUuidToSlackId.get(mcUuid);
        }

        String getMcUuid(String slackId) {
            return slackIdToMcUuid.get(slackId);
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
                            String text = event.getText();
                            text = processSlackMentions(text);
                            messageBlock.append("[Slack] <").append(displayName).append("> ").append(text);
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

                slackApp.command("/link", (req, ctx) -> {
                    String slackUserId = req.getPayload().getUserId();
                    String linkCode = req.getPayload().getText().trim().toUpperCase();

                    if (linkCode.isEmpty()) {
                        return ctx.ack("Usage: `/link <code>`\nExample: `/link ABC123`\n\nGet your link code by joining the Minecraft server.");
                    }

                    LinkCodeData codeData = pendingLinkCodes.get(linkCode);
                    if (codeData == null || codeData.isExpired()) {
                        if (codeData != null && codeData.isExpired()) {
                            pendingLinkCodes.remove(linkCode);
                        }
                        return ctx.ack("Invalid or expired link code. Please join the Minecraft server to get a new code.");
                    }

                    String mcUuid = codeData.uuid;
                    String playerName = codeData.username;
                    
                    accountLinks.linkAccounts(mcUuid, slackUserId);
                    pendingLinkCodes.remove(linkCode);

                    return ctx.ack("Successfully linked your Slack account to Minecraft player: `" + playerName + "`\n\nYou can now join the server!");
                });

                slackApp.command("/unlink", (req, ctx) -> {
                    String slackUserId = req.getPayload().getUserId();
                    
                    String mcUuid = accountLinks.getMcUuid(slackUserId);
                    if (mcUuid == null) {
                        return ctx.ack("❌ You don't have a linked Minecraft account.");
                    }

                    accountLinks.unlinkBySlackId(slackUserId);

                    MinecraftServer server = currentServer;
                    if (server != null) {
                        var player = server.getPlayerManager().getPlayer(UUID.fromString(mcUuid));
                        if (player != null) {
                            server.execute(() -> {
                                player.networkHandler.disconnect(Text.literal("§c§lYour account has been unlinked from Slack.\n\n§eYou must relink to continue playing."));
                            });
                        }
                    }

                    return ctx.ack("✅ Successfully unlinked your Minecraft account.\n\nYou will need to link again to join the server.");
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
            slackApp.client().chatPostMessage(r -> r
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

    private static String processSlackMentions(String text) {
        if (accountLinks == null || currentServer == null) return text;

        Pattern mentionPattern = Pattern.compile("<@([A-Z0-9]+)>");
        Matcher matcher = mentionPattern.matcher(text);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String slackUserId = matcher.group(1);
            String mcUuid = accountLinks.getMcUuid(slackUserId);
            
            if (mcUuid != null) {
                var player = currentServer.getPlayerManager().getPlayer(UUID.fromString(mcUuid));
                if (player != null) {
                    String mcName = player.getName().getString();
                    matcher.appendReplacement(result, "§e@" + mcName + "§r");
                    
                    currentServer.execute(() -> {
                        player.sendMessage(Text.literal("§e§l[PING] §r§7You were mentioned in Slack!"));
                    });
                    continue;
                }
            }
            
            String displayName = getDisplayName(slackUserId);
            matcher.appendReplacement(result, "@" + displayName);
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static String processMcMentions(String text) {
        if (accountLinks == null || currentServer == null) return text;

        Pattern mentionPattern = Pattern.compile("@(\\w+)");
        Matcher matcher = mentionPattern.matcher(text);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String mcUsername = matcher.group(1);
            var player = currentServer.getPlayerManager().getPlayer(mcUsername);
            
            if (player != null) {
                String mcUuid = player.getUuidAsString();
                String slackUserId = accountLinks.getSlackId(mcUuid);
                
                if (slackUserId != null) {
                    matcher.appendReplacement(result, "<@" + slackUserId + ">");
                    continue;
                }
            }
            
            matcher.appendReplacement(result, "@" + mcUsername);
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String generateLinkCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            code.append(chars.charAt(random.nextInt(chars.length())));
        }
        return code.toString();
    }

    public static boolean isPlayerLinked(String uuid) {
        return accountLinks != null && accountLinks.getSlackId(uuid) != null;
    }

    public static void unlinkAccount(String uuid) {
        if (accountLinks != null) {
            accountLinks.unlinkByUuid(uuid);
        }
    }
    
    private static void sendResourcePackToPlayer(net.minecraft.server.network.ServerPlayerEntity player) {
        if (resourcePackServer == null || emojiDownloader == null) {
            return; // Not ready yet
        }
        
        try {
            String serverIp = currentServer.getServerIp();
            if (serverIp == null || serverIp.isEmpty()) {
                serverIp = "localhost";
            }
            
            String resourcePackUrl = resourcePackServer.getUrl(serverIp);
            String sha1 = emojiDownloader.getZipSha1();
            
            if (resourcePackUrl != null && sha1 != null) {
                // Send resource pack using the network handler (1.20.4 API)
                player.networkHandler.sendPacket(
                    new net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket(
                        UUID.randomUUID(),
                        resourcePackUrl,
                        sha1,
                        false,
                        Text.literal("Emogg Slack Emojis")
                    )
                );
                LOGGER.info("Sent Emogg resource pack to player: {}", player.getName().getString());
            }
        } catch (Exception e) {
            LOGGER.error("Failed to send resource pack to player", e);
        }
    }
}
