package Crazer.cubeofinterest.cubechat;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CubeDiscordBridge {
    private static JDA jda;
    private static TextChannel textChannel;
    private static TextChannel logChannel;
    private static MinecraftServer server;
    private static boolean enabled = false;
    private static boolean sendServerStatus = true;

    private static String webhookUrl = "";
    private static boolean onlineStatusEnabled = false;
    private static String onlineStatusChannelId = "";
    private static int onlineStatusUpdateSeconds = 60;
    private static ScheduledExecutorService onlineStatusExecutor;
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    public static void start(
            MinecraftServer minecraftServer,
            boolean bridgeEnabled,
            String token,
            String configuredWebhookUrl,
            String channelId,
            String logChannelId,
            boolean statusMessages,
            boolean configuredOnlineStatusEnabled,
            String configuredOnlineStatusChannelId,
            int configuredOnlineStatusUpdateSeconds
    ) {
        server = minecraftServer;
        enabled = bridgeEnabled;
        sendServerStatus = statusMessages;
        webhookUrl = configuredWebhookUrl == null ? "" : configuredWebhookUrl.trim();
        onlineStatusEnabled = configuredOnlineStatusEnabled;
        onlineStatusChannelId = configuredOnlineStatusChannelId == null ? "" : configuredOnlineStatusChannelId.trim();
        onlineStatusUpdateSeconds = Math.max(15, configuredOnlineStatusUpdateSeconds);

        stopOnlineStatusUpdater();

        if (!enabled) {
            System.out.println("[CubeDiscord] Discord bridge is disabled.");
            return;
        }

        if (token == null || token.isBlank() || token.equalsIgnoreCase("TOKEN_HERE")) {
            System.out.println("[CubeDiscord] Bot token is empty. Discord bridge disabled.");
            return;
        }

        if (channelId == null || channelId.isBlank() || channelId.equalsIgnoreCase("CHANNEL_ID_HERE")) {
            System.out.println("[CubeDiscord] Channel ID is empty. Discord bridge disabled.");
            return;
        }

        try {
            jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(new ListenerAdapter() {
                        @Override
                        public void onMessageReceived(@NotNull MessageReceivedEvent event) {
                            handleDiscordMessage(event);
                        }
                    })
                    .build();

            new Thread(() -> {
                try {
                    jda.awaitReady();

                    textChannel = jda.getTextChannelById(channelId);

                    if (logChannelId != null
                            && !logChannelId.isBlank()
                            && !logChannelId.equalsIgnoreCase("LOG_CHANNEL_ID_HERE")) {
                        logChannel = jda.getTextChannelById(logChannelId);

                        if (logChannel == null) {
                            System.out.println("[CubeDiscord] Log channel not found: " + logChannelId);
                        }
                    }

                    if (textChannel == null) {
                        System.out.println("[CubeDiscord] Channel not found: " + channelId);
                        return;
                    }

                    System.out.println("[CubeDiscord] Discord bridge connected.");

                    if (sendServerStatus) {
                        sendToDiscord("**[A] сервер включился!**");
                    }

                    startOnlineStatusUpdater();
                } catch (Exception e) {
                    System.out.println("[CubeDiscord] Failed to start Discord bridge: " + e.getMessage());
                }
            }, "CubeDiscord-Init").start();

        } catch (Throwable e) {
            System.out.println("[CubeDiscord] Failed to create JDA: " + e.getMessage());
        }
    }

    public static void stop() {
        updateOnlineStatusMessageNow();
        stopOnlineStatusUpdater();

        if (sendServerStatus) {
            sendToDiscord("**[A] сервер выключился**");
        }

        if (jda != null) {
            try {
                jda.shutdown();
            } catch (Throwable ignored) {
            }
        }

        jda = null;
        textChannel = null;
        logChannel = null;
        server = null;
    }

    public static void sendToDiscord(String message) {
        if (!enabled) {
            return;
        }

        if (textChannel == null) {
            return;
        }

        if (message == null || message.isBlank()) {
            return;
        }

        String safe = sanitizeMessageForDiscord(message);

        try {
            textChannel
                    .sendMessage(safe)
                    .setAllowedMentions(java.util.Collections.emptyList())
                    .queue();
        } catch (Throwable e) {
            System.out.println("[CubeDiscord] Failed to send message: " + e.getMessage());
        }
    }

    public static void sendPlayerMessageToDiscord(String username, String message, String uuid) {
        if (!enabled || message == null || message.isBlank()) {
            return;
        }

        if (webhookUrl == null || webhookUrl.isBlank()) {
            String fallbackName = username == null || username.isBlank() ? "Minecraft" : username;
            sendToDiscord("**" + sanitizeMessageForDiscord(fallbackName) + "**: " + sanitizeMessageForDiscord(message));
            return;
        }

        String safeUsername = username == null || username.isBlank() ? "Minecraft" : username.trim();
        if (safeUsername.length() > 80) {
            safeUsername = safeUsername.substring(0, 80);
        }

        String avatarUrl = buildAvatarUrl(uuid);
        String payload = "{"
                + "\"username\":\"" + jsonEscape(safeUsername) + "\","
                + "\"content\":\"" + jsonEscape(sanitizeMessageForDiscord(message)) + "\","
                + "\"allowed_mentions\":{\"parse\":[]}"
                + (avatarUrl.isBlank() ? "" : ",\"avatar_url\":\"" + jsonEscape(avatarUrl) + "\"")
                + "}";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(error -> {
                        System.out.println("[CubeDiscord] Failed to send webhook message: " + error.getMessage());
                        return null;
                    });
        } catch (Throwable e) {
            System.out.println("[CubeDiscord] Failed to build webhook request: " + e.getMessage());
        }
    }

    public static void sendToDiscordLog(String message) {
        if (!enabled) {
            return;
        }

        if (logChannel == null) {
            return;
        }

        if (message == null || message.isBlank()) {
            return;
        }

        String safe = sanitizeMessageForDiscord(message);

        try {
            logChannel
                    .sendMessage(safe)
                    .setAllowedMentions(java.util.Collections.emptyList())
                    .queue();
        } catch (Throwable e) {
            System.out.println("[CubeDiscord] Failed to send log message: " + e.getMessage());
        }
    }

    private static void handleDiscordMessage(MessageReceivedEvent event) {
        if (!enabled) {
            return;
        }

        if (event.getAuthor().isBot()) {
            return;
        }

        if (textChannel == null) {
            return;
        }

        if (!event.getChannel().getId().equals(textChannel.getId())) {
            return;
        }

        String author = event.getAuthor().getName();

        String message = event.getMessage().getContentRaw();

        message = removeBotMention(event, message);
        message = removeDiscordEmojis(message);

        if (message == null || message.isBlank()) {
            return;
        }

        String replyToMinecraftPlayer = null;

        Message referenced = event.getMessage().getReferencedMessage();
        if (referenced != null && referenced.getAuthor().isBot()) {
            replyToMinecraftPlayer = extractMinecraftNameFromBotMessage(referenced.getContentDisplay());
        }

        if (server == null) {
            return;
        }

        String finalMessage = message;
        String finalReplyToMinecraftPlayer = replyToMinecraftPlayer;

        server.execute(() -> CubeChat.broadcastDiscordMessage(author, finalMessage, finalReplyToMinecraftPlayer));
    }

    private static void startOnlineStatusUpdater() {
        if (!enabled || !onlineStatusEnabled || jda == null || server == null) {
            return;
        }

        stopOnlineStatusUpdater();

        onlineStatusExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "CubeDiscord-OnlineStatus");
            thread.setDaemon(true);
            return thread;
        });

        onlineStatusExecutor.scheduleAtFixedRate(
                CubeDiscordBridge::updateOnlineStatusMessageNow,
                5L,
                onlineStatusUpdateSeconds,
                TimeUnit.SECONDS
        );
    }

    private static void stopOnlineStatusUpdater() {
        if (onlineStatusExecutor != null) {
            try {
                onlineStatusExecutor.shutdownNow();
            } catch (Throwable ignored) {
            }
        }

        onlineStatusExecutor = null;
    }

    private static void updateOnlineStatusMessageNow() {
        if (!enabled || !onlineStatusEnabled || jda == null || server == null) {
            return;
        }

        TextChannel statusChannel = getOnlineStatusChannel();
        if (statusChannel == null) {
            return;
        }

        String messageText = buildOnlineStatusMessage();
        String messageId = loadOnlineStatusMessageId();

        if (messageId == null || messageId.isBlank()) {
            sendNewOnlineStatusMessage(statusChannel, messageText);
            return;
        }

        statusChannel.retrieveMessageById(messageId).queue(
                message -> message.editMessage(messageText).queue(
                        success -> {},
                        error -> {
                            System.out.println("[CubeDiscord] Failed to edit online status message: " + error.getMessage());
                            sendNewOnlineStatusMessage(statusChannel, messageText);
                        }
                ),
                error -> {
                    System.out.println("[CubeDiscord] Online status message not found, creating a new one.");
                    sendNewOnlineStatusMessage(statusChannel, messageText);
                }
        );
    }

    private static TextChannel getOnlineStatusChannel() {
        if (jda == null) {
            return null;
        }

        if (onlineStatusChannelId != null && !onlineStatusChannelId.isBlank()) {
            TextChannel channel = jda.getTextChannelById(onlineStatusChannelId);
            if (channel != null) {
                return channel;
            }
        }

        return textChannel;
    }

    private static void sendNewOnlineStatusMessage(TextChannel channel, String messageText) {
        try {
            channel.sendMessage(messageText).queue(message -> {
                saveOnlineStatusMessageId(message.getId());
                System.out.println("[CubeDiscord] Created online status message. Pin this message in Discord. ID: " + message.getId());
            }, error -> System.out.println("[CubeDiscord] Failed to create online status message: " + error.getMessage()));
        } catch (Throwable e) {
            System.out.println("[CubeDiscord] Failed to send online status message: " + e.getMessage());
        }
    }

    private static String buildOnlineStatusMessage() {
        MinecraftServer minecraftServer = server;
        if (minecraftServer == null) {
            return "🔴 **Cube Of Interest** — сервер выключен";
        }

        List<ServerPlayer> players = minecraftServer.getPlayerList().getPlayers();
        int online = players.size();
        int max = minecraftServer.getPlayerList().getMaxPlayers();

        StringBuilder builder = new StringBuilder();
        builder.append("🟢 **Cube Of Interest** — онлайн: **")
                .append(online)
                .append("/")
                .append(max)
                .append("**\n");

        if (online <= 0) {
            builder.append("\nИгроков онлайн нет.");
            return builder.toString();
        }

        builder.append("\n**Игроки:**\n");

        int added = 0;
        for (ServerPlayer player : players) {
            String line = "• " + CubeChat.getDiscordDisplayName(player) + "\n";

            if (builder.length() + line.length() > 1900) {
                builder.append("• ...и ещё ").append(online - added).append("\n");
                break;
            }

            builder.append(line);
            added++;
        }

        return builder.toString();
    }

    private static Path onlineStatusMessageIdPath() {
        return FMLPaths.CONFIGDIR.get().resolve("cubechat-discord-online-message-id.txt");
    }

    private static String loadOnlineStatusMessageId() {
        try {
            Path path = onlineStatusMessageIdPath();
            if (!Files.exists(path)) {
                return "";
            }

            return Files.readString(path, StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void saveOnlineStatusMessageId(String messageId) {
        try {
            Path path = onlineStatusMessageIdPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, messageId == null ? "" : messageId.trim(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            System.out.println("[CubeDiscord] Failed to save online status message ID: " + e.getMessage());
        }
    }

    private static String removeBotMention(MessageReceivedEvent event, String message) {
        if (message == null || message.isBlank()) {
            return message;
        }

        String botId = event.getJDA().getSelfUser().getId();
        String botName = event.getJDA().getSelfUser().getName();

        message = message.replaceFirst("^<@!?" + java.util.regex.Pattern.quote(botId) + ">\\s*", "");

        message = message.replaceFirst("^@" + java.util.regex.Pattern.quote(botName) + "\\s*", "");

        return message.trim();
    }

    private static String removeDiscordEmojis(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        text = text.replaceAll("<a?:[a-zA-Z0-9_~]+:\\d+>", "");

        text = text.replaceAll("\\[[^\\]]*]\\(https://cdn\\.discordapp\\.com/emojis/[^)]*\\)", "");

        text = text.replaceAll("[\\x{1F300}-\\x{1FAFF}]", "");
        text = text.replaceAll("[\\x{2600}-\\x{27BF}]", "");

        text = text.replaceAll("[\\x{FE00}-\\x{FE0F}]", "");
        text = text.replaceAll("\\x{200D}", "");

        return text.replaceAll("\\s{2,}", " ").trim();
    }

    private static String extractMinecraftNameFromBotMessage(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        int colon = text.indexOf(":");
        if (colon <= 0) {
            return null;
        }

        String beforeColon = text.substring(0, colon).trim();

        beforeColon = beforeColon.replaceFirst("^\\[[^\\]]+]\\s*", "");

        beforeColon = beforeColon.replaceFirst("^\\[[^\\]]+]\\s*", "");

        String[] parts = beforeColon.trim().split("\\s+");

        if (parts.length == 0) {
            return null;
        }

        return parts[parts.length - 1];
    }

    private static String sanitizeMessageForDiscord(String message) {
        return message
                .replace("@everyone", "@\u200Beveryone")
                .replace("@here", "@\u200Bhere");
    }

    private static String buildAvatarUrl(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return "";
        }

        try {
            UUID parsed = UUID.fromString(uuid);
            return "https://crafatar.com/avatars/" + parsed + "?overlay";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String jsonEscape(String text) {
        if (text == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            switch (c) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }

        return builder.toString();
    }
}
