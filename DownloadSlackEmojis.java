import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Downloads all Slack emojis and saves them to assets/emogg/emoji/
 * Run this once to populate emojis for the Emogg mod.
 * 
 * Compile and run:
 *   javac -cp "build/libs/*:$(find ~/.gradle/caches -name 'gson-*.jar' | head -1)" DownloadSlackEmojis.java
 *   java -cp ".:build/libs/*:$(find ~/.gradle/caches -name 'gson-*.jar' | head -1)" DownloadSlackEmojis xoxb-your-token
 * 
 * Or simpler (if you have Gson in classpath):
 *   javac DownloadSlackEmojis.java
 *   java DownloadSlackEmojis xoxb-your-token
 */
public class DownloadSlackEmojis {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== Slack Emoji Downloader ===\n");
        
        if (args.length == 0) {
            System.out.println("Usage: java DownloadSlackEmojis <slack-bot-token>");
            System.out.println("\nExample:");
            System.out.println("  java DownloadSlackEmojis xoxb-1234567890-1234567890-abcdefghijklmnop");
            System.out.println("\nGet your token from: https://api.slack.com/apps");
            System.out.println("Required scope: emoji:read");
            System.exit(1);
        }
        
        String token = args[0];
        Path outputDir = Paths.get("src/main/resources/assets/emogg/emoji");
        
        System.out.println("Fetching emoji list from Slack...");
        Map<String, String> emojis = fetchEmojiList(token);
        
        System.out.println("Found " + emojis.size() + " emojis");
        System.out.println("Downloading to: " + outputDir.toAbsolutePath());
        
        Files.createDirectories(outputDir);
        
        int count = 0;
        int failed = 0;
        
        for (Map.Entry<String, String> entry : emojis.entrySet()) {
            String name = entry.getKey();
            String url = entry.getValue();
            
            try {
                downloadEmoji(name, url, outputDir);
                count++;
                
                if (count % 100 == 0) {
                    System.out.println("Progress: " + count + "/" + emojis.size());
                }
            } catch (Exception e) {
                System.err.println("Failed to download " + name + ": " + e.getMessage());
                failed++;
            }
            
            // Rate limiting
            Thread.sleep(100);
        }
        
        System.out.println("\nComplete!");
        System.out.println("   Downloaded: " + count);
        System.out.println("   Failed: " + failed);
        System.out.println("   Location: " + outputDir.toAbsolutePath());
    }
    
    private static Map<String, String> fetchEmojiList(String token) throws Exception {
        URL url = new URI("https://slack.com/api/emoji.list").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestMethod("GET");
        
        InputStreamReader reader = new InputStreamReader(conn.getInputStream());
        JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
        reader.close();
        
        if (!json.get("ok").getAsBoolean()) {
            throw new Exception("Slack API error: " + json.get("error").getAsString());
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
    
    private static void downloadEmoji(String name, String urlString, Path outputDir) throws Exception {
        // Sanitize filename (remove special characters)
        String safeName = name.replaceAll("[^a-zA-Z0-9._-]", "");
        Path outputFile = outputDir.resolve(safeName + ".png");
        
        // Skip if already exists
        if (Files.exists(outputFile)) {
            return;
        }
        
        // Download
        URL url = new URI(urlString).toURL();
        try (InputStream in = url.openStream()) {
            Files.copy(in, outputFile);
        }
    }
}
