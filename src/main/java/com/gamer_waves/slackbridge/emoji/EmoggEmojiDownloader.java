package com.gamer_waves.slackbridge.emoji;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gamer_waves.slackbridge.SlackBridge;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Downloads Slack emojis and creates a resource pack for the Emogg mod.
 * Emogg requires emojis in a resource pack at assets/emogg/emoji/
 */
public class EmoggEmojiDownloader {
    private final String slackToken;
    private final Path resourcePackDir;
    private final Path emojiDir;
    private final Path zipFile;
    private final ExecutorService downloadExecutor;
    private final AtomicInteger downloadedCount = new AtomicInteger(0);
    private boolean initialized = false;
    private int totalEmojis = 0;

    public EmoggEmojiDownloader(String slackToken) {
        this.slackToken = slackToken;
        this.resourcePackDir = Paths.get("config/emogg_resourcepack");
        this.emojiDir = resourcePackDir.resolve("assets/emogg/emoji");
        this.zipFile = Paths.get("config/emogg_resourcepack.zip");
        this.downloadExecutor = Executors.newFixedThreadPool(5);
    }
    
    public Path getZipFile() {
        return zipFile;
    }
    
    public String getZipSha1() {
        try {
            if (!Files.exists(zipFile)) return null;
            return calculateSha1(zipFile);
        } catch (Exception e) {
            return null;
        }
    }

    public void start() {
        if (initialized) return;
        initialized = true;

        SlackBridge.LOGGER.info("Starting Emogg emoji downloader...");

        // Run in background thread
        new Thread(() -> {
            try {
                // Create directories
                Files.createDirectories(emojiDir);
                
                // Create pack.mcmeta
                createPackMcmeta();

                // Fetch emoji list
                Map<String, String> emojis = fetchEmojiList();
                totalEmojis = emojis.size();
                SlackBridge.LOGGER.info("Found {} emojis to download for Emogg", totalEmojis);

                // Download all emojis
                int count = 0;
                for (Map.Entry<String, String> entry : emojis.entrySet()) {
                    final String name = entry.getKey();
                    final String url = entry.getValue();

                    downloadExecutor.submit(() -> {
                        try {
                            if (downloadEmoji(name, url)) {
                                int downloaded = downloadedCount.incrementAndGet();
                                if (downloaded % 1000 == 0) {
                                    SlackBridge.LOGGER.info("Downloaded {}/{} emojis", downloaded, totalEmojis);
                                }
                            }
                        } catch (Exception e) {
                            SlackBridge.LOGGER.debug("Failed to download emoji {}: {}", name, e.getMessage());
                        }
                    });

                    count++;
                }

                SlackBridge.LOGGER.info("All {} emojis queued for download", count);
                
                // Wait a bit for downloads to complete, then create ZIP
                new Thread(() -> {
                    try {
                        Thread.sleep(60000); // Wait 1 minute
                        createZipFile();
                    } catch (Exception e) {
                        SlackBridge.LOGGER.error("Failed to create resource pack ZIP", e);
                    }
                }, "EmoggZipCreator").start();

            } catch (Exception e) {
                SlackBridge.LOGGER.error("Failed to start emoji downloader", e);
            }
        }, "EmoggEmojiDownloader").start();
    }
    
    private void createPackMcmeta() {
        try {
            Path packMcmeta = resourcePackDir.resolve("pack.mcmeta");
            if (Files.exists(packMcmeta)) {
                return; // Already exists
            }
            
            String content = "{\n" +
                           "  \"pack\": {\n" +
                           "    \"pack_format\": 22,\n" +
                           "    \"description\": \"Slack Emojis for Emogg\"\n" +
                           "  }\n" +
                           "}\n";
            
            Files.writeString(packMcmeta, content);
            SlackBridge.LOGGER.info("Created pack.mcmeta for Emogg resource pack");
        } catch (Exception e) {
            SlackBridge.LOGGER.error("Failed to create pack.mcmeta", e);
        }
    }

    private Map<String, String> fetchEmojiList() throws Exception {
        if (slackToken == null || slackToken.isBlank()) {
            throw new Exception("No Slack token provided");
        }

        URL url = new java.net.URI("https://slack.com/api/emoji.list").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + slackToken);
        conn.setRequestMethod("GET");

        InputStreamReader reader = new InputStreamReader(conn.getInputStream());
        JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
        reader.close();

        if (!json.get("ok").getAsBoolean()) {
            String error = json.has("error") ? json.get("error").getAsString() : "unknown";
            throw new Exception("Slack API error: " + error);
        }

        JsonObject emojis = json.getAsJsonObject("emoji");
        Map<String, String> result = new HashMap<>();

        emojis.entrySet().forEach(e -> {
            String emojiUrl = e.getValue().getAsString();
            // Skip aliases (they reference other emojis)
            if (!emojiUrl.startsWith("alias:")) {
                result.put(e.getKey(), emojiUrl);
            }
        });

        return result;
    }

    private boolean downloadEmoji(String name, String urlString) throws Exception {
        // Sanitize filename
        String safeName = name.replaceAll("[^a-zA-Z0-9._-]", "");
        Path outputFile = emojiDir.resolve(safeName + ".png");

        // Skip if already exists
        if (Files.exists(outputFile)) {
            return false;
        }

        // Download
        URL url = new java.net.URI(urlString).toURL();
        try (InputStream in = url.openStream()) {
            Files.copy(in, outputFile);
        }
        return true;
    }
    
    private void createZipFile() throws Exception {
        SlackBridge.LOGGER.info("Creating resource pack ZIP...");
        
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile.toFile()))) {
            // Add pack.mcmeta
            addFileToZip(zos, resourcePackDir.resolve("pack.mcmeta"), "pack.mcmeta");
            
            // Add all emoji files
            Files.walk(emojiDir)
                .filter(Files::isRegularFile)
                .forEach(file -> {
                    try {
                        String relativePath = resourcePackDir.relativize(file).toString().replace("\\", "/");
                        addFileToZip(zos, file, relativePath);
                    } catch (Exception e) {
                        SlackBridge.LOGGER.debug("Failed to add {} to ZIP", file);
                    }
                });
        }
        
        String sha1 = calculateSha1(zipFile);
        SlackBridge.LOGGER.info("Resource pack ZIP created: {}", zipFile.toAbsolutePath());
        SlackBridge.LOGGER.info("SHA-1: {}", sha1);
        SlackBridge.LOGGER.info("Downloaded {} emojis", downloadedCount.get());
    }
    
    private void addFileToZip(ZipOutputStream zos, Path file, String zipPath) throws Exception {
        ZipEntry entry = new ZipEntry(zipPath);
        zos.putNextEntry(entry);
        Files.copy(file, zos);
        zos.closeEntry();
    }
    
    private String calculateSha1(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream fis = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = fis.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        
        byte[] hash = digest.digest();
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    public void shutdown() {
        downloadExecutor.shutdown();
    }
}
