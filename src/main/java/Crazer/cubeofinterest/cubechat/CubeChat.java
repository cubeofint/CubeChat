package Crazer.cubeofinterest.cubechat;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Mod(CubeChat.MODID)
public class CubeChat {
    public static final String MODID = "cubechat";

    private static final String NETWORK_PROTOCOL_VERSION = "1";
    private static final SimpleChannel NETWORK_CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MODID, "main"),
            () -> NETWORK_PROTOCOL_VERSION,
            NETWORK_PROTOCOL_VERSION::equals,
            NETWORK_PROTOCOL_VERSION::equals
    );
    private static boolean NETWORK_REGISTERED = false;

    private static final ForgeConfigSpec CONFIG_SPEC;

    private static final ForgeConfigSpec.DoubleValue LOCAL_RADIUS;
    private static final ForgeConfigSpec.ConfigValue<String> LOCAL_PREFIX;
    private static final ForgeConfigSpec.ConfigValue<String> GLOBAL_PREFIX;
    private static final ForgeConfigSpec.ConfigValue<String> PRIVATE_PREFIX;
    private static final ForgeConfigSpec.ConfigValue<String> DISCORD_PREFIX;
    private static final ForgeConfigSpec.BooleanValue USE_EXCLAMATION_FOR_GLOBAL;
    private static final ForgeConfigSpec.BooleanValue SHOW_CHAT_PANEL_ON_JOIN;

    private static final ForgeConfigSpec.BooleanValue DISCORD_ENABLED;
    private static final ForgeConfigSpec.ConfigValue<String> DISCORD_BOT_TOKEN;
    private static final ForgeConfigSpec.ConfigValue<String> DISCORD_WEBHOOK_URL;
    private static final ForgeConfigSpec.ConfigValue<String> DISCORD_AVATAR_URL_TEMPLATE;
    private static final ForgeConfigSpec.ConfigValue<String> DISCORD_CHANNEL_ID;
    private static final ForgeConfigSpec.ConfigValue<String> DISCORD_LOG_CHANNEL_ID;
    private static final ForgeConfigSpec.BooleanValue DISCORD_SEND_SERVER_STATUS;
    private static final ForgeConfigSpec.BooleanValue DISCORD_ONLINE_STATUS_ENABLED;
    private static final ForgeConfigSpec.ConfigValue<String> DISCORD_ONLINE_STATUS_CHANNEL_ID;
    private static final ForgeConfigSpec.IntValue DISCORD_ONLINE_STATUS_UPDATE_SECONDS;
    private static final ForgeConfigSpec.BooleanValue DISCORD_SEND_GLOBAL_CHAT;
    private static final ForgeConfigSpec.BooleanValue DISCORD_SEND_LOCAL_CHAT;
    private static final ForgeConfigSpec.BooleanValue DISCORD_SEND_PRIVATE_CHAT;
    public static ForgeConfigSpec.IntValue RESERVED_PUBLIC_SLOTS;
    public static ForgeConfigSpec.IntValue RESERVED_TOTAL_SLOTS;
    public static ForgeConfigSpec.ConfigValue<String> RESERVED_PERMISSION;
    public static ForgeConfigSpec.ConfigValue<String> RESERVED_FULL_MESSAGE;
    public static ForgeConfigSpec.ConfigValue<String> RESERVED_NO_PERMISSION_MESSAGE;

    private static final ForgeConfigSpec.BooleanValue RESTART_ENABLED;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> RESTART_TIMES;
    private static final ForgeConfigSpec.ConfigValue<List<? extends Integer>> RESTART_WARNING_MINUTES;
    private static final ForgeConfigSpec.IntValue RESTART_COUNTDOWN_SECONDS;
    private static final ForgeConfigSpec.BooleanValue RESTART_SHOW_TITLE;
    private static final ForgeConfigSpec.BooleanValue RESTART_SHOW_ACTIONBAR;
    private static final ForgeConfigSpec.BooleanValue RESTART_SHOW_CHAT;
    private static final ForgeConfigSpec.BooleanValue RESTART_KICK_PLAYERS;
    private static final ForgeConfigSpec.IntValue RESTART_KICK_SECONDS_BEFORE_STOP;
    private static final ForgeConfigSpec.ConfigValue<String> RESTART_KICK_MESSAGE;

    private static final Map<UUID, ChatView> CHAT_VIEWS = new HashMap<>();
    private static final Map<UUID, UUID> LAST_PRIVATE = new HashMap<>();
    private static final Map<UUID, Boolean> SHOW_TIME = new HashMap<>();
    private static final Map<UUID, Deque<ChatHistoryMessage>> CHAT_HISTORY = new ConcurrentHashMap<>();
    private static final AtomicLong CHAT_HISTORY_COUNTER = new AtomicLong();
    private static final int MAX_CHAT_HISTORY_PER_PLAYER = 100;
    private static final Map<UUID, MuteData> MUTED_PLAYERS = new ConcurrentHashMap<>();
    private static final Map<UUID, TempBanData> TEMP_BANNED_PLAYERS = new ConcurrentHashMap<>();
    private static final Map<UUID, LastLocationData> LAST_LOCATIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, ArrayList<WarnData>> WARNED_PLAYERS = new ConcurrentHashMap<>();

    private static long NEXT_RESTART_MILLIS = -1L;
    private static long LAST_RESTART_CHECK_SECOND = -1L;
    private static boolean RESTARTING_NOW = false;
    private static boolean RESTART_PLAYERS_KICKED = false;
    private static final Set<Integer> SENT_RESTART_WARNINGS = ConcurrentHashMap.newKeySet();

    private static MinecraftServer CURRENT_SERVER;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");

    enum ChatView {
        ALL,
        LOCAL,
        GLOBAL,
        PRIVATE
    }

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("chat");

        LOCAL_RADIUS = builder
                .comment("Radius of local chat in blocks.")
                .defineInRange("local_radius", 300.0D, 1.0D, 100000.0D);

        LOCAL_PREFIX = builder
                .comment("Prefix for local chat. Supports & color codes and §.")
                .define("local_prefix", "&a[L] ");

        GLOBAL_PREFIX = builder
                .comment("Prefix for global chat. Supports & color codes and §.")
                .define("global_prefix", "&6[G] ");

        PRIVATE_PREFIX = builder
                .comment("Prefix for private messages. Supports & color codes and §.")
                .define("private_prefix", "&d[PM] ");

        DISCORD_PREFIX = builder
                .comment("Prefix for messages from Discord in Minecraft chat.")
                .define("discord_prefix", "&9[D] ");

        USE_EXCLAMATION_FOR_GLOBAL = builder
                .comment("If true, messages starting with ! will be sent to global chat.")
                .define("use_exclamation_for_global", true);

        SHOW_CHAT_PANEL_ON_JOIN = builder
                .comment("If true, short chat hint will be shown when player joins.")
                .define("show_chat_panel_on_join", true);

        builder.pop();

        builder.push("reserved_slots");

        RESERVED_PUBLIC_SLOTS = builder
                .comment("How many slots are available for regular players.")
                .defineInRange("public_slots", 100, 1, 10000);

        RESERVED_TOTAL_SLOTS = builder
                .comment("Maximum players including reserved slots. This should be equal to or lower than max-players in server.properties.")
                .defineInRange("total_slots", 110, 1, 10000);

        RESERVED_PERMISSION = builder
                .comment("LuckPerms permission for joining reserved slots.")
                .define("permission", "cubechat.joinfull");

        RESERVED_FULL_MESSAGE = builder
                .comment("Kick message when even reserved slots are full.")
                .define("full_message", "Сервер заполнен.");

        RESERVED_NO_PERMISSION_MESSAGE = builder
                .comment("Kick message when only reserved slots are left.")
                .define("no_permission_message", "Сервер заполнен. Резервные слоты доступны только администрации и донатерам.");

        builder.pop();

        builder.push("discord");

        DISCORD_ENABLED = builder
                .comment("Enable Discord bridge.")
                .define("enabled", false);

        DISCORD_BOT_TOKEN = builder
                .comment("Discord bot token. Do not share it.")
                .define("bot_token", "TOKEN_HERE");

        DISCORD_WEBHOOK_URL = builder
                .comment("Discord webhook URL for Minecraft -> Discord player messages. If empty, the bot sends messages normally.")
                .define("webhook_url", "");

        DISCORD_AVATAR_URL_TEMPLATE = builder
                .comment("Avatar URL template for Minecraft -> Discord webhook messages. Use %username% for player name and %uuid% for UUID. Example: https://mawlee.org/api/skin-api/skins/%username%.png")
                .define("avatar_url_template", "https://mawlee.org/api/skin-api/skins/%username%.png");

        DISCORD_CHANNEL_ID = builder
                .comment("Discord channel ID.")
                .define("channel_id", "CHANNEL_ID_HERE");

        DISCORD_LOG_CHANNEL_ID = builder
                .comment("Discord channel ID for local chat and private messages log.")
                .define("log_channel_id", "LOG_CHANNEL_ID_HERE");

        DISCORD_SEND_SERVER_STATUS = builder
                .comment("Send server start/stop messages to Discord.")
                .define("send_server_status", true);

        DISCORD_ONLINE_STATUS_ENABLED = builder
                .comment("Keep one Discord message updated with current server online list.")
                .define("online_status_enabled", true);

        DISCORD_ONLINE_STATUS_CHANNEL_ID = builder
                .comment("Discord channel ID for online status message. If empty, main channel_id is used.")
                .define("online_status_channel_id", "");

        DISCORD_ONLINE_STATUS_UPDATE_SECONDS = builder
                .comment("How often to edit the online status message.")
                .defineInRange("online_status_update_seconds", 60, 15, 3600);

        DISCORD_SEND_GLOBAL_CHAT = builder
                .comment("Send global Minecraft chat to Discord.")
                .define("send_global_chat", true);

        DISCORD_SEND_LOCAL_CHAT = builder
                .comment("Send local Minecraft chat to Discord.")
                .define("send_local_chat", false);

        DISCORD_SEND_PRIVATE_CHAT = builder
                .comment("Send private Minecraft messages to Discord.")
                .define("send_private_chat", false);

        builder.pop();

        builder.push("restart");

        RESTART_ENABLED = builder
                .comment("Enable automatic scheduled restarts. CubeChat stops the server; your host/start script must start it again.")
                .define("enabled", false);

        RESTART_TIMES = builder
                .comment("Restart times in Europe/Moscow timezone, HH:mm format. Example: 06:00, 18:00")
                .defineList("times", List.of("06:00", "18:00"), value -> value instanceof String);

        RESTART_WARNING_MINUTES = builder
                .comment("Warnings before restart, in minutes.")
                .defineList("warning_minutes", List.of(30, 15, 10, 5, 3, 2, 1), value -> value instanceof Integer integer && integer >= 1);

        RESTART_COUNTDOWN_SECONDS = builder
                .comment("Big title countdown in the last N seconds before restart.")
                .defineInRange("countdown_seconds", 10, 0, 60);

        RESTART_SHOW_TITLE = builder
                .comment("Show big title on players screens for restart warnings.")
                .define("show_title", true);

        RESTART_SHOW_ACTIONBAR = builder
                .comment("Show actionbar restart warnings.")
                .define("show_actionbar", true);

        RESTART_SHOW_CHAT = builder
                .comment("Send restart warnings to chat.")
                .define("show_chat", true);

        RESTART_KICK_PLAYERS = builder
                .comment("Disconnect players before /stop with restart message.")
                .define("kick_players", true);

        RESTART_KICK_SECONDS_BEFORE_STOP = builder
                .comment("How many seconds before /stop players should be kicked. This helps avoid item/inventory loss when players are interacting right before restart.")
                .defineInRange("kick_seconds_before_stop", 10, 0, 60);

        RESTART_KICK_MESSAGE = builder
                .comment("Kick message when automatic restart begins.")
                .define("kick_message", "Сервер перезапускается. Зайдите через пару минут.");

        builder.pop();

        CONFIG_SPEC = builder.build();
    }

    public CubeChat() {
        registerNetwork();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CONFIG_SPEC);
        MinecraftForge.EVENT_BUS.register(this);
    }


    private static void registerNetwork() {
        if (NETWORK_REGISTERED) {
            return;
        }

        NETWORK_CHANNEL.messageBuilder(ClearChatPacket.class, 0)
                .encoder(ClearChatPacket::encode)
                .decoder(ClearChatPacket::decode)
                .consumerMainThread(ClearChatPacket::handle)
                .add();

        NETWORK_REGISTERED = true;
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        CURRENT_SERVER = event.getServer();
        loadTempBans();
        loadLastLocations();
        loadWarns();

        CubeDiscordBridge.start(
                CURRENT_SERVER,
                DISCORD_ENABLED.get(),
                DISCORD_BOT_TOKEN.get(),
                DISCORD_WEBHOOK_URL.get(),
                DISCORD_AVATAR_URL_TEMPLATE.get(),
                DISCORD_CHANNEL_ID.get(),
                DISCORD_LOG_CHANNEL_ID.get(),
                DISCORD_SEND_SERVER_STATUS.get(),
                DISCORD_ONLINE_STATUS_ENABLED.get(),
                DISCORD_ONLINE_STATUS_CHANNEL_ID.get(),
                DISCORD_ONLINE_STATUS_UPDATE_SECONDS.get()
        );

        resetRestartSchedule();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        saveTempBans();
        saveLastLocations();
        saveWarns();
        CubeDiscordBridge.stop();
        NEXT_RESTART_MILLIS = -1L;
        LAST_RESTART_CHECK_SECOND = -1L;
        RESTARTING_NOW = false;
        RESTART_PLAYERS_KICKED = false;
        SENT_RESTART_WARNINGS.clear();
        CURRENT_SERVER = null;
    }

    @SubscribeEvent
    public void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }


        if (isTempBanned(player)) {
            disconnectTempBannedPlayer(player);
            return;
        }

        saveLastLocation(player);

        CHAT_VIEWS.putIfAbsent(player.getUUID(), ChatView.ALL);

        if (SHOW_CHAT_PANEL_ON_JOIN.get()) {
            player.displayClientMessage(Component.literal("§7Откройте чат, чтобы выбрать канал: §f[ALL] §a[L] §6[G] §d[PM]"), true);
        }
    }

    @SubscribeEvent
    public void onLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        saveLastLocation(player);
        saveLastLocations();
        CHAT_HISTORY.remove(player.getUUID());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("chat")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            player.displayClientMessage(Component.literal("§7Откройте чат и выберите канал сверху: §f[ALL] §a[L] §6[G] §d[PM]"), true);
                            return 1;
                        })

                        .then(Commands.literal("all")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    setChatView(player, ChatView.ALL);
                                    return 1;
                                }))

                        .then(Commands.literal("local")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    setChatView(player, ChatView.LOCAL);
                                    return 1;
                                }))

                        .then(Commands.literal("l")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    setChatView(player, ChatView.LOCAL);
                                    return 1;
                                }))

                        .then(Commands.literal("global")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    setChatView(player, ChatView.GLOBAL);
                                    return 1;
                                }))

                        .then(Commands.literal("g")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    setChatView(player, ChatView.GLOBAL);
                                    return 1;
                                }))

                        .then(Commands.literal("pm")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    setChatView(player, ChatView.PRIVATE);
                                    return 1;
                                }))

                        .then(Commands.literal("time")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    toggleTime(player);
                                    return 1;
                                }))
        );

        event.getDispatcher().register(
                Commands.literal("mute")
                        .requires(source -> hasCommandPermission(source, "cubechat.mute"))
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("time", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                            String time = StringArgumentType.getString(ctx, "time");
                                            long duration = parsePunishmentTime(time);

                                            if (duration <= 0L) {
                                                ctx.getSource().sendFailure(Component.literal("Использование: /mute <ник> <10s/5m/2h/1d> [причина]"));
                                                return 0;
                                            }

                                            mutePlayer(target, duration, "");

                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("Игрок " + target.getGameProfile().getName() + " замучен на " + time),
                                                    true
                                            );
                                            target.sendSystemMessage(Component.literal("§cТы получил мут на §e" + time));
                                            return 1;
                                        })
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                                    String time = StringArgumentType.getString(ctx, "time");
                                                    String reason = StringArgumentType.getString(ctx, "reason");
                                                    long duration = parsePunishmentTime(time);

                                                    if (duration <= 0L) {
                                                        ctx.getSource().sendFailure(Component.literal("Использование: /mute <ник> <10s/5m/2h/1d> [причина]"));
                                                        return 0;
                                                    }

                                                    mutePlayer(target, duration, reason);

                                                    ctx.getSource().sendSuccess(
                                                            () -> Component.literal("Игрок " + target.getGameProfile().getName() + " замучен на " + time + ". Причина: " + reason),
                                                            true
                                                    );
                                                    target.sendSystemMessage(Component.literal("§cТы получил мут на §e" + time));
                                                    target.sendSystemMessage(Component.literal("§cПричина: §f" + reason));
                                                    return 1;
                                                })
                                        )
                                )
                        )
        );

        event.getDispatcher().register(
                Commands.literal("unmute")
                        .requires(source -> hasCommandPermission(source, "cubechat.mute"))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");

                                    if (!MUTED_PLAYERS.containsKey(target.getUUID())) {
                                        ctx.getSource().sendFailure(Component.literal("Игрок не замучен."));
                                        return 0;
                                    }

                                    unmutePlayer(target);

                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("Мут снят с игрока " + target.getGameProfile().getName()),
                                            true
                                    );
                                    target.sendSystemMessage(Component.literal("§aС тебя сняли мут"));
                                    return 1;
                                })
                        )
        );


        event.getDispatcher().register(
                Commands.literal("unban")
                        .requires(source -> hasCommandPermission(source, "cubechat.unban"))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestKnownPlayerNames(builder))
                                .executes(ctx -> {
                                    String targetName = StringArgumentType.getString(ctx, "target");

                                    ctx.getSource().getServer().getCommands().performPrefixedCommand(
                                            ctx.getSource(),
                                            "pardon " + targetName
                                    );
                                    return 1;
                                })
                        )
        );


        event.getDispatcher().register(
                Commands.literal("tempban")
                        .requires(source -> hasCommandPermission(source, "cubechat.tempban"))
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("time", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                            String time = StringArgumentType.getString(ctx, "time");
                                            long duration = parsePunishmentTime(time);

                                            if (duration <= 0L) {
                                                ctx.getSource().sendFailure(Component.literal("Использование: /tempban <ник> <10s/5m/2h/1d> [причина]"));
                                                return 0;
                                            }

                                            tempBanPlayer(target, duration, "");

                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("Игрок " + target.getGameProfile().getName() + " забанен на " + time),
                                                    true
                                            );
                                            disconnectTempBannedPlayer(target);
                                            return 1;
                                        })
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                                    String time = StringArgumentType.getString(ctx, "time");
                                                    String reason = StringArgumentType.getString(ctx, "reason");
                                                    long duration = parsePunishmentTime(time);

                                                    if (duration <= 0L) {
                                                        ctx.getSource().sendFailure(Component.literal("Использование: /tempban <ник> <10s/5m/2h/1d> [причина]"));
                                                        return 0;
                                                    }

                                                    tempBanPlayer(target, duration, reason);

                                                    ctx.getSource().sendSuccess(
                                                            () -> Component.literal("Игрок " + target.getGameProfile().getName() + " забанен на " + time + ". Причина: " + reason),
                                                            true
                                                    );
                                                    disconnectTempBannedPlayer(target);
                                                    return 1;
                                                })
                                        )
                                )
                        )
        );

        event.getDispatcher().register(
                Commands.literal("untempban")
                        .requires(source -> hasCommandPermission(source, "cubechat.tempban"))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestKnownPlayerNames(builder))
                                .executes(ctx -> {
                                    String targetName = StringArgumentType.getString(ctx, "target");

                                    if (!untempBanPlayerByName(targetName)) {
                                        ctx.getSource().sendFailure(Component.literal("Игрок не находится во временном бане."));
                                        return 0;
                                    }

                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("Временный бан снят с игрока " + targetName),
                                            true
                                    );
                                    return 1;
                                })
                        )
        );


        event.getDispatcher().register(
                Commands.literal("warn")
                        .requires(source -> hasCommandPermission(source, "cubechat.warn"))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");

                                    warnPlayer(target, "");

                                    int count = getWarnCount(target.getUUID());
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("Игрок " + target.getGameProfile().getName() + " получил предупреждение. Всего варнов: " + count),
                                            true
                                    );
                                    target.sendSystemMessage(Component.literal("§cТы получил предупреждение. Всего варнов: §e" + count));
                                    return 1;
                                })
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                            String reason = StringArgumentType.getString(ctx, "reason");

                                            warnPlayer(target, reason);

                                            int count = getWarnCount(target.getUUID());
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("Игрок " + target.getGameProfile().getName() + " получил предупреждение. Всего варнов: " + count + ". Причина: " + reason),
                                                    true
                                            );
                                            target.sendSystemMessage(Component.literal("§cТы получил предупреждение. Всего варнов: §e" + count));
                                            target.sendSystemMessage(Component.literal("§cПричина: §f" + reason));
                                            return 1;
                                        })
                                )
                        )
        );

        event.getDispatcher().register(
                Commands.literal("warns")
                        .requires(source -> hasCommandPermission(source, "cubechat.warn"))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestKnownPlayerNames(builder))
                                .executes(ctx -> {
                                    String targetName = StringArgumentType.getString(ctx, "target");
                                    ArrayList<WarnData> warns = getWarnsByName(targetName);

                                    if (warns == null || warns.isEmpty()) {
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("У игрока " + targetName + " нет варнов."),
                                                false
                                        );
                                        return 1;
                                    }

                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("У игрока " + targetName + " варнов: " + warns.size()),
                                            false
                                    );

                                    for (int i = 0; i < warns.size(); i++) {
                                        WarnData warn = warns.get(i);
                                        String reason = warn.reason() == null || warn.reason().isBlank() ? "не указана" : warn.reason();
                                        int number = i + 1;

                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("#" + number + " | " + formatDateTime(warn.createdMillis()) + " | Причина: " + reason),
                                                false
                                        );
                                    }

                                    return 1;
                                })
                        )
        );

        event.getDispatcher().register(
                Commands.literal("unwarn")
                        .requires(source -> hasCommandPermission(source, "cubechat.warn"))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestKnownPlayerNames(builder))
                                .executes(ctx -> {
                                    String targetName = StringArgumentType.getString(ctx, "target");

                                    if (!removeLastWarnByName(targetName)) {
                                        ctx.getSource().sendFailure(Component.literal("У игрока нет варнов."));
                                        return 0;
                                    }

                                    int count = getWarnCountByName(targetName);
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("Последний варн снят с игрока " + targetName + ". Осталось варнов: " + count),
                                            true
                                    );
                                    return 1;
                                })
                                .then(Commands.argument("number", IntegerArgumentType.integer(1))
                                        .suggests((ctx, builder) -> suggestWarnNumbers(StringArgumentType.getString(ctx, "target"), builder))
                                        .executes(ctx -> {
                                            String targetName = StringArgumentType.getString(ctx, "target");
                                            int number = IntegerArgumentType.getInteger(ctx, "number");

                                            if (!removeWarnByNameAndNumber(targetName, number)) {
                                                ctx.getSource().sendFailure(Component.literal("У игрока нет варна с номером " + number + "."));
                                                return 0;
                                            }

                                            int count = getWarnCountByName(targetName);
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("Варн #" + number + " снят с игрока " + targetName + ". Осталось варнов: " + count),
                                                    true
                                            );
                                            return 1;
                                        })
                                )
                        )
        );

        event.getDispatcher().register(
                Commands.literal("tpl")
                        .requires(source -> hasCommandPermission(source, "cubechat.tpl"))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestKnownPlayerNames(builder))
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String targetName = StringArgumentType.getString(ctx, "target");

                                    if (!teleportToPlayerOrLastLocation(player, targetName)) {
                                        ctx.getSource().sendFailure(Component.literal("Игрок не найден и его последняя позиция не сохранена."));
                                        return 0;
                                    }

                                    return 1;
                                })
                        )
        );

        event.getDispatcher().register(
                Commands.literal("homeother")
                        .requires(source -> hasCommandPermission(source, "cubechat.home.others"))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestKnownPlayerNames(builder))
                                .then(Commands.argument("home", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestFtbHomeNamesForPlayer(StringArgumentType.getString(ctx, "target"), builder))
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            String targetName = StringArgumentType.getString(ctx, "target");
                                            String homeName = StringArgumentType.getString(ctx, "home");

                                            if (!teleportToFtbHome(player, homeName, targetName)) {
                                                ctx.getSource().sendFailure(Component.literal("Использование: /homeother <ник> <название_хома>"));
                                                return 0;
                                            }

                                            return 1;
                                        })
                                )
                        )
        );

        event.getDispatcher().register(
                Commands.literal("cuberestart")
                        .then(Commands.literal("status")
                                .executes(ctx -> {
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal(getRestartStatusText()),
                                            false
                                    );
                                    return 1;
                                }))
                        .then(Commands.literal("reload")
                                .requires(source -> hasCommandPermission(source, "cubechat.restart"))
                                .executes(ctx -> {
                                    resetRestartSchedule();
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("§aРасписание рестартов CubeChat перезагружено. " + getRestartStatusText()),
                                            true
                                    );
                                    return 1;
                                }))
                        .then(Commands.literal("cancel")
                                .requires(source -> hasCommandPermission(source, "cubechat.restart"))
                                .executes(ctx -> {
                                    NEXT_RESTART_MILLIS = -1L;
                                    RESTARTING_NOW = false;
                                    RESTART_PLAYERS_KICKED = false;
                                    SENT_RESTART_WARNINGS.clear();
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("§cБлижайший рестарт CubeChat отменён до /cuberestart reload или перезапуска сервера."),
                                            true
                                    );
                                    return 1;
                                }))
                        .then(Commands.literal("now")
                                .requires(source -> hasCommandPermission(source, "cubechat.restart"))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 3600))
                                        .executes(ctx -> {
                                            int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
                                            scheduleManualRestart(seconds);
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("§eРучной рестарт запланирован через " + seconds + " сек."),
                                                    true
                                            );
                                            return 1;
                                        })))
        );

        event.getDispatcher().register(
                Commands.literal("g")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String message = StringArgumentType.getString(ctx, "message");

                                    if (isMuted(player)) {
                                        sendMutedMessage(player);
                                        return 0;
                                    }

                                    sendGlobalChat(player, message);
                                    return 1;
                                }))
        );

        event.getDispatcher().register(
                Commands.literal("global")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String message = StringArgumentType.getString(ctx, "message");

                                    if (isMuted(player)) {
                                        sendMutedMessage(player);
                                        return 0;
                                    }

                                    sendGlobalChat(player, message);
                                    return 1;
                                }))
        );

        event.getDispatcher().register(
                Commands.literal("l")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String message = StringArgumentType.getString(ctx, "message");

                                    if (isMuted(player)) {
                                        sendMutedMessage(player);
                                        return 0;
                                    }

                                    sendLocalChat(player, message);
                                    return 1;
                                }))
        );

        event.getDispatcher().register(
                Commands.literal("local")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String message = StringArgumentType.getString(ctx, "message");

                                    if (isMuted(player)) {
                                        sendMutedMessage(player);
                                        return 0;
                                    }

                                    sendLocalChat(player, message);
                                    return 1;
                                }))
        );

        event.getDispatcher().register(
                Commands.literal("pm")
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                            String message = StringArgumentType.getString(ctx, "message");

                                            if (isMuted(player)) {
                                                sendMutedMessage(player);
                                                return 0;
                                            }

                                            sendPrivateMessage(player, target, message);
                                            return 1;
                                        })))
        );

        event.getDispatcher().register(
                Commands.literal("r")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String message = StringArgumentType.getString(ctx, "message");

                                    if (isMuted(player)) {
                                        sendMutedMessage(player);
                                        return 0;
                                    }

                                    UUID lastUuid = LAST_PRIVATE.get(player.getUUID());

                                    if (lastUuid == null) {
                                        player.displayClientMessage(Component.literal("§cНекому ответить."), true);
                                        return 0;
                                    }

                                    ServerPlayer target = player.server.getPlayerList().getPlayer(lastUuid);

                                    if (target == null) {
                                        player.displayClientMessage(Component.literal("§cИгрок уже не в сети."), true);
                                        return 0;
                                    }

                                    sendPrivateMessage(player, target, message);
                                    return 1;
                                }))
        );
    }


    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        MinecraftServer server = CURRENT_SERVER;
        if (server == null) {
            return;
        }

        long currentSecond = System.currentTimeMillis() / 1000L;
        if (currentSecond == LAST_RESTART_CHECK_SECOND) {
            return;
        }
        LAST_RESTART_CHECK_SECOND = currentSecond;

        handleRestartTick(server);
    }

    private static void resetRestartSchedule() {
        SENT_RESTART_WARNINGS.clear();
        RESTARTING_NOW = false;
        RESTART_PLAYERS_KICKED = false;
        LAST_RESTART_CHECK_SECOND = -1L;
        NEXT_RESTART_MILLIS = calculateNextRestartMillis();

        if (NEXT_RESTART_MILLIS > 0L) {
            System.out.println("[CubeChat] Next automatic restart: " + formatDateTime(NEXT_RESTART_MILLIS));
        } else {
            System.out.println("[CubeChat] Automatic restarts are disabled or no valid restart times configured.");
        }
    }

    private static void scheduleManualRestart(int seconds) {
        SENT_RESTART_WARNINGS.clear();
        RESTARTING_NOW = false;
        RESTART_PLAYERS_KICKED = false;
        LAST_RESTART_CHECK_SECOND = -1L;
        NEXT_RESTART_MILLIS = System.currentTimeMillis() + Math.max(5, seconds) * 1000L;
        broadcastRestartWarning(Math.max(5, seconds), true);
    }

    private static void handleRestartTick(MinecraftServer server) {
        if (NEXT_RESTART_MILLIS <= 0L) {
            if (RESTART_ENABLED.get()) {
                NEXT_RESTART_MILLIS = calculateNextRestartMillis();
            }
            return;
        }

        long millisLeft = NEXT_RESTART_MILLIS - System.currentTimeMillis();
        long secondsLeftLong = Math.max(0L, (millisLeft + 999L) / 1000L);

        if (secondsLeftLong <= 0L) {
            performRestart(server);
            return;
        }

        if (secondsLeftLong > Integer.MAX_VALUE) {
            return;
        }

        int secondsLeft = (int) secondsLeftLong;

        int kickSecondsBeforeStop = RESTART_KICK_SECONDS_BEFORE_STOP.get();
        if (RESTART_KICK_PLAYERS.get()
                && kickSecondsBeforeStop > 0
                && secondsLeft <= kickSecondsBeforeStop
                && !RESTART_PLAYERS_KICKED) {
            kickPlayersBeforeRestart(server);
        }

        int countdownSeconds = RESTART_COUNTDOWN_SECONDS.get();

        if (countdownSeconds > 0 && secondsLeft <= countdownSeconds) {
            int key = -secondsLeft;
            if (SENT_RESTART_WARNINGS.add(key)) {
                broadcastRestartWarning(secondsLeft, true);
            }
            return;
        }

        for (Integer minutes : RESTART_WARNING_MINUTES.get()) {
            if (minutes == null || minutes <= 0) {
                continue;
            }

            int warningSeconds = minutes * 60;
            if (secondsLeft <= warningSeconds && secondsLeft > warningSeconds - 3 && SENT_RESTART_WARNINGS.add(warningSeconds)) {
                broadcastRestartWarning(secondsLeft, true);
                return;
            }
        }
    }

    private static long calculateNextRestartMillis() {
        if (!RESTART_ENABLED.get()) {
            return -1L;
        }

        ZonedDateTime now = ZonedDateTime.now(MOSCOW_ZONE);
        ZonedDateTime best = null;

        for (String rawTime : RESTART_TIMES.get()) {
            if (rawTime == null || rawTime.isBlank()) {
                continue;
            }

            try {
                LocalTime time = LocalTime.parse(rawTime.trim());
                ZonedDateTime candidate = now.withHour(time.getHour()).withMinute(time.getMinute()).withSecond(0).withNano(0);

                if (!candidate.isAfter(now)) {
                    candidate = candidate.plusDays(1);
                }

                if (best == null || candidate.isBefore(best)) {
                    best = candidate;
                }
            } catch (Throwable ignored) {
                System.out.println("[CubeChat] Invalid restart time in config: " + rawTime + ". Use HH:mm, for example 06:00");
            }
        }

        return best == null ? -1L : best.toInstant().toEpochMilli();
    }

    private static void broadcastRestartWarning(int secondsLeft, boolean important) {
        MinecraftServer server = CURRENT_SERVER;
        if (server == null) {
            return;
        }

        String timeText = formatRestartTime(secondsLeft);
        String chatMessage = "§c⚠ Рестарт сервера через §e" + timeText + "§c!";
        String title = "§c⚠ РЕСТАРТ СЕРВЕРА ⚠";
        String subtitle = "§eДо рестарта " + timeText;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (RESTART_SHOW_CHAT.get()) {
                player.sendSystemMessage(Component.literal(chatMessage));
            }

            if (RESTART_SHOW_ACTIONBAR.get()) {
                player.displayClientMessage(Component.literal(chatMessage), true);
            }

            if (RESTART_SHOW_TITLE.get() && important) {
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(10, 60, 10));
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(title)));
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
            }
        }

        System.out.println("[CubeChat] Restart warning: " + stripColor(chatMessage));
        CubeDiscordBridge.sendToDiscord("⚠ Рестарт сервера через **" + stripColor(timeText) + "**!");
    }


    private static void kickPlayersBeforeRestart(MinecraftServer server) {
        if (RESTART_PLAYERS_KICKED) {
            return;
        }

        RESTART_PLAYERS_KICKED = true;

        System.out.println("[CubeChat] Kicking players before restart and saving the world.");

        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "save-all flush");
        } catch (Throwable e) {
            System.out.println("[CubeChat] Failed to execute save-all flush before kicking players: " + e.getMessage());
        }

        Component kickMessage = Component.literal(RESTART_KICK_MESSAGE.get());
        for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
            try {
                player.connection.disconnect(kickMessage);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void performRestart(MinecraftServer server) {
        if (RESTARTING_NOW) {
            return;
        }

        RESTARTING_NOW = true;
        NEXT_RESTART_MILLIS = -1L;

        if (RESTART_KICK_PLAYERS.get() && !RESTART_PLAYERS_KICKED) {
            kickPlayersBeforeRestart(server);
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(5, 80, 10));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal("§cСЕРВЕР ПЕРЕЗАПУСКАЕТСЯ")));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal("§7Зайдите через пару минут")));
            player.sendSystemMessage(Component.literal("§cСервер перезапускается. Зайдите через пару минут."));
        }

        CubeDiscordBridge.sendToDiscord("🔄 **Сервер уходит на плановый рестарт.**");
        System.out.println("[CubeChat] Automatic restart started.");

        server.execute(() -> {
            try {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "save-all flush");
            } catch (Throwable e) {
                System.out.println("[CubeChat] Failed to execute save-all flush: " + e.getMessage());
            }

            try {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "stop");
            } catch (Throwable e) {
                System.out.println("[CubeChat] Failed to execute stop command: " + e.getMessage());
                try {
                    server.halt(false);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static String getRestartStatusText() {
        if (NEXT_RESTART_MILLIS <= 0L) {
            return RESTART_ENABLED.get()
                    ? "§eАвто-рестарт включён, но ближайшее время не рассчитано. Используйте /cuberestart reload."
                    : "§cАвто-рестарт выключен в конфиге.";
        }

        long secondsLeft = Math.max(0L, (NEXT_RESTART_MILLIS - System.currentTimeMillis() + 999L) / 1000L);
        return "§aБлижайший рестарт: §e" + formatDateTime(NEXT_RESTART_MILLIS) + " МСК§7, осталось §e" + formatRestartTime((int) Math.min(Integer.MAX_VALUE, secondsLeft));
    }

    private static String formatRestartTime(int seconds) {
        seconds = Math.max(0, seconds);

        if (seconds < 60) {
            return seconds + " сек.";
        }

        int minutes = seconds / 60;
        int restSeconds = seconds % 60;

        if (minutes < 60) {
            return restSeconds > 0 ? minutes + " мин. " + restSeconds + " сек." : minutes + " мин.";
        }

        int hours = minutes / 60;
        int restMinutes = minutes % 60;
        return restMinutes > 0 ? hours + " ч. " + restMinutes + " мин." : hours + " ч.";
    }

    @SubscribeEvent
    public void onCommand(net.minecraftforge.event.CommandEvent event) {
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }

        String input = event.getParseResults().getReader().getString();

        if (input.startsWith("/")) {
            input = input.substring(1);
        }

        String lower = input.toLowerCase(java.util.Locale.ROOT);


        if (!lower.equals("tell")
                && !lower.startsWith("tell ")
                && !lower.equals("message")
                && !lower.startsWith("message ")
                && !lower.equals("w")
                && !lower.startsWith("w ")
                && !lower.equals("msg")
                && !lower.startsWith("msg ")) {
            return;
        }

        player.displayClientMessage(Component.literal(
                "§cВанильные личные сообщения отключены. Используйте §d/pm <ник> <сообщение> §cили §d/r <сообщение>§c."
        ), true);

        event.setCanceled(true);
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        String message = event.getRawText();

        event.setCanceled(true);

        if (isMuted(player)) {
            sendMutedMessage(player);
            return;
        }

        if (USE_EXCLAMATION_FOR_GLOBAL.get() && message.startsWith("!")) {
            String globalMessage = message.substring(1).trim();

            if (globalMessage.isEmpty()) {
                player.displayClientMessage(Component.literal("§cВведите сообщение после !"), true);
                return;
            }

            sendGlobalChat(player, globalMessage);
            return;
        }

        ChatView view = getChatView(player);

        if (view == ChatView.GLOBAL) {
            sendGlobalChat(player, message);
            return;
        }

        if (view == ChatView.PRIVATE) {
            player.displayClientMessage(Component.literal("§dЛичные сообщения отправляются так: §f/msg Ник сообщение §7или §f/r сообщение"), true);
            return;
        }

        sendLocalChat(player, message);
    }

    private static void setChatView(ServerPlayer player, ChatView view) {
        CHAT_VIEWS.put(player.getUUID(), view);
        replayChatHistory(player, view);

        if (view == ChatView.ALL) {
            player.displayClientMessage(Component.literal("§aТеперь вы видите все чаты."), true);
        }

        if (view == ChatView.LOCAL) {
            player.displayClientMessage(Component.literal("§aТеперь вы видите только локальный чат."), true);
        }

        if (view == ChatView.GLOBAL) {
            player.displayClientMessage(Component.literal("§6Теперь вы видите только глобальный чат."), true);
        }

        if (view == ChatView.PRIVATE) {
            player.displayClientMessage(Component.literal("§dТеперь вы видите только личные сообщения."), true);
        }
    }

    private static void rememberChatMessage(ServerPlayer target, ChatView view, String formattedMessage) {
        if (target == null || formattedMessage == null || formattedMessage.isBlank()) {
            return;
        }

        Deque<ChatHistoryMessage> history = CHAT_HISTORY.computeIfAbsent(target.getUUID(), uuid -> new ArrayDeque<>());

        synchronized (history) {
            history.addLast(new ChatHistoryMessage(CHAT_HISTORY_COUNTER.incrementAndGet(), view, formattedMessage));

            while (history.size() > MAX_CHAT_HISTORY_PER_PLAYER) {
                history.removeFirst();
            }
        }
    }

    private static void sendFilteredChatMessage(ServerPlayer target, ChatView view, String formattedMessage) {
        rememberChatMessage(target, view, formattedMessage);

        if (canReceive(target, view)) {
            target.sendSystemMessage(Component.literal(formattedMessage));
        }
    }

    private static void replayChatHistory(ServerPlayer player, ChatView view) {
        clearClientChat(player);

        Deque<ChatHistoryMessage> history = CHAT_HISTORY.get(player.getUUID());
        if (history == null || history.isEmpty()) {
            return;
        }

        ArrayList<ChatHistoryMessage> snapshot;
        synchronized (history) {
            snapshot = new ArrayList<>(history);
        }

        for (ChatHistoryMessage message : snapshot) {
            if (view != ChatView.ALL && message.view() != view) {
                continue;
            }

            player.sendSystemMessage(Component.literal(message.message()));
        }
    }

    private static void clearClientChat(ServerPlayer player) {
        try {
            NETWORK_CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ClearChatPacket());
        } catch (Throwable ignored) {
        }
    }

    private static ChatView getChatView(ServerPlayer player) {
        return CHAT_VIEWS.getOrDefault(player.getUUID(), ChatView.ALL);
    }

    private static boolean canReceive(ServerPlayer target, ChatView messageType) {
        ChatView view = getChatView(target);

        if (view == ChatView.ALL) {
            return true;
        }

        return view == messageType;
    }

    private static void sendLocalChat(ServerPlayer player, String message) {
        String withoutTime = color(LOCAL_PREFIX.get())
                + getLuckPermsPrefix(player)
                + getStaffTag(player)
                + "§f" + player.getGameProfile().getName()
                + "§7: §f"
                + message;

        String discordFormatted = stripColor(withoutTime);

        int receivers = 0;
        double radius = LOCAL_RADIUS.get();
        double radiusSquared = radius * radius;

        for (ServerPlayer target : player.server.getPlayerList().getPlayers()) {
            if (target.level().dimension() != player.level().dimension()) {
                continue;
            }

            if (target.distanceToSqr(player) > radiusSquared) {
                continue;
            }

            sendFilteredChatMessage(target, ChatView.LOCAL, timePrefix(target) + withoutTime);
            receivers++;
        }

        if (receivers <= 1) {
            player.displayClientMessage(Component.literal("§7Рядом никого нет. Для глобального чата используйте §e!сообщение §7или §e/g сообщение§7."), true);
        }

        if (DISCORD_SEND_LOCAL_CHAT.get()) {
            CubeDiscordBridge.sendToDiscordLog(discordFormatted);
        }

        System.out.println("[LocalChat] " + stripColor(timePrefix(player) + withoutTime));
    }

    private static void sendGlobalChat(ServerPlayer player, String message) {
        String withoutTime = color(GLOBAL_PREFIX.get())
                + getLuckPermsPrefix(player)
                + getStaffTag(player)
                + "§f" + player.getGameProfile().getName()
                + "§7: §f"
                + message;

        String discordFormatted = stripColor(withoutTime);

        for (ServerPlayer target : player.server.getPlayerList().getPlayers()) {
            sendFilteredChatMessage(target, ChatView.GLOBAL, timePrefix(target) + withoutTime);
        }

        if (DISCORD_SEND_GLOBAL_CHAT.get()) {
            CubeDiscordBridge.sendPlayerMessageToDiscord(
                    getDiscordDisplayName(player, GLOBAL_PREFIX.get()),
                    message,
                    player.getUUID().toString(),
                    player.getGameProfile().getName()
            );
        }

        System.out.println("[GlobalChat] " + stripColor(timePrefix(player) + withoutTime));
    }

    private static void sendPrivateMessage(ServerPlayer sender, ServerPlayer target, String message) {
        if (sender.getUUID().equals(target.getUUID())) {
            sender.displayClientMessage(Component.literal("§cНельзя написать самому себе."), true);
            return;
        }

        String senderName = sender.getGameProfile().getName();
        String targetName = target.getGameProfile().getName();

        String toSender = timePrefix(sender)
                + color(PRIVATE_PREFIX.get())
                + "§7Вы -> §d"
                + targetName
                + "§7: §f"
                + message;

        String toTarget = timePrefix(target)
                + color(PRIVATE_PREFIX.get())
                + "§d"
                + senderName
                + " §7-> Вы: §f"
                + message;

        rememberChatMessage(sender, ChatView.PRIVATE, toSender);
        rememberChatMessage(target, ChatView.PRIVATE, toTarget);

        sender.sendSystemMessage(Component.literal(toSender));
        target.sendSystemMessage(Component.literal(toTarget));

        LAST_PRIVATE.put(sender.getUUID(), target.getUUID());
        LAST_PRIVATE.put(target.getUUID(), sender.getUUID());

        if (DISCORD_SEND_PRIVATE_CHAT.get()) {
            CubeDiscordBridge.sendToDiscordLog("[PM] " + senderName + " -> " + targetName + ": " + message);
        }

        System.out.println("[PrivateChat] " + senderName + " -> " + targetName + ": " + message);
    }

    public static void broadcastDiscordMessage(String author, String message, String replyToMinecraftPlayer) {
        if (CURRENT_SERVER == null) {
            return;
        }

        String safeAuthor = sanitizeDiscord(author);
        String safeMessage = sanitizeDiscord(message);

        String formatted;

        if (replyToMinecraftPlayer != null && !replyToMinecraftPlayer.isBlank()) {
            formatted = timePrefix()
                    + color(DISCORD_PREFIX.get())
                    + "§f"
                    + safeAuthor
                    + " §7-> §e"
                    + sanitizeDiscord(replyToMinecraftPlayer)
                    + "§7: §f"
                    + safeMessage;
        } else {
            formatted = timePrefix()
                    + color(DISCORD_PREFIX.get())
                    + "§f"
                    + safeAuthor
                    + "§7: §f"
                    + safeMessage;
        }

        for (ServerPlayer target : CURRENT_SERVER.getPlayerList().getPlayers()) {
            sendFilteredChatMessage(target, ChatView.GLOBAL, formatted);
        }

        System.out.println("[DiscordChat] " + safeAuthor + ": " + safeMessage);
    }

    private static void mutePlayer(ServerPlayer player, long durationMillis, String reason) {
        long untilMillis = System.currentTimeMillis() + durationMillis;
        MUTED_PLAYERS.put(
                player.getUUID(),
                new MuteData(player.getGameProfile().getName(), untilMillis, reason == null ? "" : reason)
        );
    }

    private static void unmutePlayer(ServerPlayer player) {
        MUTED_PLAYERS.remove(player.getUUID());
    }

    private static void tempBanPlayer(ServerPlayer player, long durationMillis, String reason) {
        long untilMillis = System.currentTimeMillis() + durationMillis;
        TEMP_BANNED_PLAYERS.put(
                player.getUUID(),
                new TempBanData(player.getGameProfile().getName(), untilMillis, reason == null ? "" : reason)
        );
        saveTempBans();
    }

    private static boolean untempBanPlayerByName(String name) {
        UUID foundUuid = null;

        for (Map.Entry<UUID, TempBanData> entry : TEMP_BANNED_PLAYERS.entrySet()) {
            if (entry.getValue().name().equalsIgnoreCase(name)) {
                foundUuid = entry.getKey();
                break;
            }
        }

        if (foundUuid == null) {
            return false;
        }

        TEMP_BANNED_PLAYERS.remove(foundUuid);
        saveTempBans();
        return true;
    }

    private static boolean isTempBanned(ServerPlayer player) {
        TempBanData data = TEMP_BANNED_PLAYERS.get(player.getUUID());

        if (data == null) {
            return false;
        }

        if (System.currentTimeMillis() >= data.untilMillis()) {
            TEMP_BANNED_PLAYERS.remove(player.getUUID());
            saveTempBans();
            return false;
        }

        return true;
    }

    private static void disconnectTempBannedPlayer(ServerPlayer player) {
        TempBanData data = TEMP_BANNED_PLAYERS.get(player.getUUID());

        if (data == null) {
            return;
        }

        long leftMillis = Math.max(1000L, data.untilMillis() - System.currentTimeMillis());
        String reason = data.reason() == null || data.reason().isBlank() ? "не указана" : data.reason();

        player.connection.disconnect(Component.literal(
                "§cТы временно забанен.\n"
                        + "§cОсталось: §e" + formatMuteTime(leftMillis) + "\n"
                        + "§cПричина: §f" + reason
        ));
    }


    private static Path tempBansPath() {
        return FMLPaths.CONFIGDIR.get().resolve("cubechat-tempbans.txt");
    }

    private static void loadTempBans() {
        TEMP_BANNED_PLAYERS.clear();

        Path path = tempBansPath();
        if (!Files.exists(path)) {
            return;
        }

        try {
            long now = System.currentTimeMillis();

            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }

                String[] parts = line.split("\\t", 4);
                if (parts.length < 4) {
                    continue;
                }

                UUID uuid = UUID.fromString(parts[0]);
                long untilMillis = Long.parseLong(parts[1]);

                if (now >= untilMillis) {
                    continue;
                }

                String name = decodeBase64(parts[2]);
                String reason = decodeBase64(parts[3]);

                TEMP_BANNED_PLAYERS.put(uuid, new TempBanData(name, untilMillis, reason));
            }
        } catch (Throwable e) {
            System.out.println("[CubeChat] Failed to load tempbans: " + e.getMessage());
        }
    }

    private static void saveTempBans() {
        Path path = tempBansPath();

        try {
            Files.createDirectories(path.getParent());

            long now = System.currentTimeMillis();
            ArrayList<String> lines = new ArrayList<>();

            for (Map.Entry<UUID, TempBanData> entry : TEMP_BANNED_PLAYERS.entrySet()) {
                TempBanData data = entry.getValue();

                if (now >= data.untilMillis()) {
                    continue;
                }

                lines.add(entry.getKey()
                        + "\t" + data.untilMillis()
                        + "\t" + encodeBase64(data.name())
                        + "\t" + encodeBase64(data.reason()));
            }

            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            System.out.println("[CubeChat] Failed to save tempbans: " + e.getMessage());
        }
    }

    private static String encodeBase64(String text) {
        String safe = text == null ? "" : text;
        return Base64.getEncoder().encodeToString(safe.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeBase64(String text) {
        return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }


    private static Path lastLocationsPath() {
        return FMLPaths.CONFIGDIR.get().resolve("cubechat-lastlocations.txt");
    }

    private static void saveLastLocation(ServerPlayer player) {
        LAST_LOCATIONS.put(
                player.getUUID(),
                new LastLocationData(
                        player.getGameProfile().getName(),
                        player.serverLevel().dimension().location().toString(),
                        player.getX(),
                        player.getY(),
                        player.getZ(),
                        player.getYRot(),
                        player.getXRot(),
                        System.currentTimeMillis()
                )
        );
    }

    private static LastLocationData getLastLocationByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        for (LastLocationData data : LAST_LOCATIONS.values()) {
            if (data.name().equalsIgnoreCase(name)) {
                return data;
            }
        }

        return null;
    }

    private static boolean teleportToPlayerOrLastLocation(ServerPlayer player, String targetName) {
        MinecraftServer server = player.getServer();
        if (server == null || targetName == null || targetName.isBlank()) {
            return false;
        }

        ServerPlayer onlineTarget = server.getPlayerList().getPlayerByName(targetName);
        if (onlineTarget != null) {
            player.teleportTo(
                    onlineTarget.serverLevel(),
                    onlineTarget.getX(),
                    onlineTarget.getY(),
                    onlineTarget.getZ(),
                    onlineTarget.getYRot(),
                    onlineTarget.getXRot()
            );
            player.sendSystemMessage(Component.literal("§aТелепорт к игроку §e" + onlineTarget.getGameProfile().getName()));
            return true;
        }

        LastLocationData location = getLastLocationByName(targetName);
        if (location == null) {
            return false;
        }

        ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(location.dimension()));
        ServerLevel level = server.getLevel(dimensionKey);

        if (level == null) {
            player.sendSystemMessage(Component.literal("§cМир последней позиции не найден: §f" + location.dimension()));
            return true;
        }

        player.teleportTo(
                level,
                location.x(),
                location.y(),
                location.z(),
                location.yRot(),
                location.xRot()
        );

        player.sendSystemMessage(Component.literal(
                "§aТелепорт к последней позиции игрока §e"
                        + location.name()
                        + "§7 ("
                        + formatDateTime(location.savedMillis())
                        + ")"
        ));
        return true;
    }

    private static void loadLastLocations() {
        LAST_LOCATIONS.clear();

        Path path = lastLocationsPath();
        if (!Files.exists(path)) {
            return;
        }

        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }

                String[] parts = line.split("\\t", 9);
                if (parts.length < 9) {
                    continue;
                }

                UUID uuid = UUID.fromString(parts[0]);
                String name = decodeBase64(parts[1]);
                String dimension = decodeBase64(parts[2]);
                double x = Double.parseDouble(parts[3]);
                double y = Double.parseDouble(parts[4]);
                double z = Double.parseDouble(parts[5]);
                float yRot = Float.parseFloat(parts[6]);
                float xRot = Float.parseFloat(parts[7]);
                long savedMillis = Long.parseLong(parts[8]);

                LAST_LOCATIONS.put(uuid, new LastLocationData(name, dimension, x, y, z, yRot, xRot, savedMillis));
            }
        } catch (Throwable e) {
            System.out.println("[CubeChat] Failed to load last locations: " + e.getMessage());
        }
    }

    private static void saveLastLocations() {
        Path path = lastLocationsPath();

        try {
            Files.createDirectories(path.getParent());

            ArrayList<String> lines = new ArrayList<>();

            for (Map.Entry<UUID, LastLocationData> entry : LAST_LOCATIONS.entrySet()) {
                LastLocationData data = entry.getValue();
                lines.add(entry.getKey()
                        + "\t" + encodeBase64(data.name())
                        + "\t" + encodeBase64(data.dimension())
                        + "\t" + data.x()
                        + "\t" + data.y()
                        + "\t" + data.z()
                        + "\t" + data.yRot()
                        + "\t" + data.xRot()
                        + "\t" + data.savedMillis());
            }

            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            System.out.println("[CubeChat] Failed to save last locations: " + e.getMessage());
        }
    }

    private static Path warnsPath() {
        return FMLPaths.CONFIGDIR.get().resolve("cubechat-warns.txt");
    }

    private static void warnPlayer(ServerPlayer player, String reason) {
        ArrayList<WarnData> warns = WARNED_PLAYERS.computeIfAbsent(player.getUUID(), uuid -> new ArrayList<>());
        warns.add(new WarnData(player.getGameProfile().getName(), System.currentTimeMillis(), reason == null ? "" : reason));
        saveWarns();
    }

    private static int getWarnCount(UUID uuid) {
        ArrayList<WarnData> warns = WARNED_PLAYERS.get(uuid);
        return warns == null ? 0 : warns.size();
    }

    private static int getWarnCountByName(String name) {
        ArrayList<WarnData> warns = getWarnsByName(name);
        return warns == null ? 0 : warns.size();
    }

    private static ArrayList<WarnData> getWarnsByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        for (ArrayList<WarnData> warns : WARNED_PLAYERS.values()) {
            if (warns.isEmpty()) {
                continue;
            }

            if (warns.get(0).name().equalsIgnoreCase(name)) {
                return warns;
            }
        }

        return null;
    }

    private static boolean canUseOthersHomeCommand(ServerPlayer player) {
        return player.hasPermissions(2) || hasPermissionNode(player, "cubechat.home.others");
    }

    private static UUID findKnownUuidByName(MinecraftServer server, String name) {
        if (server == null || name == null || name.isBlank()) {
            return null;
        }

        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return online.getUUID();
        }

        for (Map.Entry<UUID, LastLocationData> entry : LAST_LOCATIONS.entrySet()) {
            LastLocationData data = entry.getValue();
            if (data.name() != null && data.name().equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }

        for (Map.Entry<UUID, TempBanData> entry : TEMP_BANNED_PLAYERS.entrySet()) {
            TempBanData data = entry.getValue();
            if (data.name() != null && data.name().equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }

        for (Map.Entry<UUID, ArrayList<WarnData>> entry : WARNED_PLAYERS.entrySet()) {
            ArrayList<WarnData> warns = entry.getValue();
            if (warns != null && !warns.isEmpty() && warns.get(0).name().equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }

        try {
            return server.getProfileCache()
                    .get(name)
                    .map(profile -> profile.getId())
                    .orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean teleportToFtbHome(ServerPlayer admin, String homeName, String targetName) {
        MinecraftServer server = admin.getServer();
        if (server == null || homeName == null || homeName.isBlank() || targetName == null || targetName.isBlank()) {
            return false;
        }

        UUID targetUuid = findKnownUuidByName(server, targetName);
        if (targetUuid == null) {
            admin.sendSystemMessage(Component.literal("§cИгрок не найден в известных данных сервера: §f" + targetName));
            return true;
        }

        try {
            Class<?> dataClass = Class.forName("dev.ftb.mods.ftbessentials.util.FTBEPlayerData");
            GameProfile profile = new GameProfile(targetUuid, targetName);

            Object optional = dataClass
                    .getMethod("getOrCreate", GameProfile.class)
                    .invoke(null, profile);

            if (!(optional instanceof java.util.Optional<?> opt) || opt.isEmpty()) {
                admin.sendSystemMessage(Component.literal("§cFTB Essentials не нашёл данные игрока: §f" + targetName));
                return true;
            }

            Object data = opt.get();

            // Для оффлайн-игроков FTB хранит хомы в playerdata/<uuid>.snbt.
            // getOrCreate(GameProfile) создаёт объект в памяти, а load() подгружает сохранённые хомы из файла.
            try {
                Object exists = dataClass.getMethod("playerExists", UUID.class).invoke(null, targetUuid);
                if (!(exists instanceof Boolean value) || !value) {
                    data.getClass().getMethod("load").invoke(data);
                }
            } catch (Throwable ignored) {
                try {
                    data.getClass().getMethod("load").invoke(data);
                } catch (Throwable ignored2) {
                }
            }

            Object homeManager = data.getClass().getMethod("homeManager").invoke(data);
            Object streamObj = homeManager.getClass().getMethod("destinations").invoke(homeManager);

            if (!(streamObj instanceof java.util.stream.Stream<?> stream)) {
                admin.sendSystemMessage(Component.literal("§cНе удалось прочитать список хомов FTB Essentials."));
                return true;
            }

            final Object[] foundDestination = new Object[1];
            final String[] foundName = new String[1];

            stream.forEach(entry -> {
                try {
                    String entryName = String.valueOf(entry.getClass().getMethod("name").invoke(entry));
                    if (entryName.equalsIgnoreCase(homeName)) {
                        foundName[0] = entryName;
                        foundDestination[0] = entry.getClass().getMethod("destination").invoke(entry);
                    }
                } catch (Throwable ignored) {
                }
            });

            if (foundDestination[0] == null) {
                admin.sendSystemMessage(Component.literal("§cУ игрока §f" + targetName + " §cнет хома §f" + homeName));
                return true;
            }

            Object result = foundDestination[0].getClass()
                    .getMethod("teleport", ServerPlayer.class)
                    .invoke(foundDestination[0], admin);

            int commandResult = 1;
            try {
                Object runResult = result.getClass().getMethod("runCommand", ServerPlayer.class).invoke(result, admin);
                if (runResult instanceof Integer value) {
                    commandResult = value;
                }
            } catch (Throwable ignored) {
            }

            boolean success = commandResult > 0;
            try {
                Object successObj = result.getClass().getMethod("isSuccess").invoke(result);
                if (successObj instanceof Boolean value) {
                    success = value;
                }
            } catch (Throwable ignored) {
            }

            if (success) {
                admin.sendSystemMessage(Component.literal("§aТелепорт к хому §e" + foundName[0] + " §aигрока §e" + targetName));
            }

            return true;
        } catch (ClassNotFoundException e) {
            admin.sendSystemMessage(Component.literal("§cFTB Essentials не найден на сервере."));
            return true;
        } catch (Throwable e) {
            admin.sendSystemMessage(Component.literal("§cОшибка чтения хомов FTB Essentials: §f" + e.getMessage()));
            return true;
        }
    }


    private static CompletableFuture<Suggestions> suggestFtbHomeNamesForPlayer(String targetName, SuggestionsBuilder builder) {
        if (CURRENT_SERVER == null || targetName == null || targetName.isBlank()) {
            return builder.buildFuture();
        }

        UUID targetUuid = findKnownUuidByName(CURRENT_SERVER, targetName);
        if (targetUuid == null) {
            return builder.buildFuture();
        }

        try {
            Class<?> dataClass = Class.forName("dev.ftb.mods.ftbessentials.util.FTBEPlayerData");
            GameProfile profile = new GameProfile(targetUuid, targetName);

            Object optional = dataClass
                    .getMethod("getOrCreate", GameProfile.class)
                    .invoke(null, profile);

            if (!(optional instanceof java.util.Optional<?> opt) || opt.isEmpty()) {
                return builder.buildFuture();
            }

            Object data = opt.get();

            try {
                Object exists = dataClass.getMethod("playerExists", UUID.class).invoke(null, targetUuid);
                if (!(exists instanceof Boolean value) || !value) {
                    data.getClass().getMethod("load").invoke(data);
                }
            } catch (Throwable ignored) {
                try {
                    data.getClass().getMethod("load").invoke(data);
                } catch (Throwable ignored2) {
                }
            }

            Object homeManager = data.getClass().getMethod("homeManager").invoke(data);

            try {
                Object namesObj = homeManager.getClass().getMethod("getNames").invoke(homeManager);
                if (namesObj instanceof Iterable<?> names) {
                    for (Object name : names) {
                        if (name != null) {
                            builder.suggest(String.valueOf(name));
                        }
                    }
                    return builder.buildFuture();
                }
            } catch (Throwable ignored) {
            }

            Object streamObj = homeManager.getClass().getMethod("destinations").invoke(homeManager);
            if (streamObj instanceof java.util.stream.Stream<?> stream) {
                stream.forEach(entry -> {
                    try {
                        Object name = entry.getClass().getMethod("name").invoke(entry);
                        if (name != null) {
                            builder.suggest(String.valueOf(name));
                        }
                    } catch (Throwable ignored) {
                    }
                });
            }
        } catch (Throwable ignored) {
        }

        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestFtbHomeNames(SuggestionsBuilder builder) {
        builder.suggest("home");

        if (CURRENT_SERVER == null) {
            return builder.buildFuture();
        }

        try {
            Class<?> dataClass = Class.forName("dev.ftb.mods.ftbessentials.util.FTBEPlayerData");
            for (ServerPlayer player : CURRENT_SERVER.getPlayerList().getPlayers()) {
                Object optional = dataClass
                        .getMethod("getOrCreate", net.minecraft.world.entity.player.Player.class)
                        .invoke(null, player);

                if (!(optional instanceof java.util.Optional<?> opt) || opt.isEmpty()) {
                    continue;
                }

                Object data = opt.get();
                Object homeManager = data.getClass().getMethod("homeManager").invoke(data);
                Object namesObj = homeManager.getClass().getMethod("getNames").invoke(homeManager);

                if (namesObj instanceof Iterable<?> names) {
                    for (Object name : names) {
                        if (name != null) {
                            builder.suggest(String.valueOf(name));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestKnownPlayerNames(SuggestionsBuilder builder) {
        if (CURRENT_SERVER != null) {
            for (ServerPlayer player : CURRENT_SERVER.getPlayerList().getPlayers()) {
                builder.suggest(player.getGameProfile().getName());
            }
        }

        for (TempBanData data : TEMP_BANNED_PLAYERS.values()) {
            if (data.name() != null && !data.name().isBlank()) {
                builder.suggest(data.name());
            }
        }

        for (LastLocationData data : LAST_LOCATIONS.values()) {
            if (data.name() != null && !data.name().isBlank()) {
                builder.suggest(data.name());
            }
        }

        for (ArrayList<WarnData> warns : WARNED_PLAYERS.values()) {
            if (!warns.isEmpty() && warns.get(0).name() != null && !warns.get(0).name().isBlank()) {
                builder.suggest(warns.get(0).name());
            }
        }

        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestWarnNumbers(String name, SuggestionsBuilder builder) {
        ArrayList<WarnData> warns = getWarnsByName(name);

        if (warns != null) {
            for (int i = 1; i <= warns.size(); i++) {
                builder.suggest(i);
            }
        }

        return builder.buildFuture();
    }

    private static boolean removeLastWarnByName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }

        UUID foundUuid = null;
        ArrayList<WarnData> foundWarns = null;

        for (Map.Entry<UUID, ArrayList<WarnData>> entry : WARNED_PLAYERS.entrySet()) {
            ArrayList<WarnData> warns = entry.getValue();

            if (warns.isEmpty()) {
                continue;
            }

            if (warns.get(0).name().equalsIgnoreCase(name)) {
                foundUuid = entry.getKey();
                foundWarns = warns;
                break;
            }
        }

        if (foundUuid == null || foundWarns == null || foundWarns.isEmpty()) {
            return false;
        }

        foundWarns.remove(foundWarns.size() - 1);

        if (foundWarns.isEmpty()) {
            WARNED_PLAYERS.remove(foundUuid);
        }

        saveWarns();
        return true;
    }

    private static boolean removeWarnByNameAndNumber(String name, int number) {
        if (name == null || name.isBlank() || number <= 0) {
            return false;
        }

        UUID foundUuid = null;
        ArrayList<WarnData> foundWarns = null;

        for (Map.Entry<UUID, ArrayList<WarnData>> entry : WARNED_PLAYERS.entrySet()) {
            ArrayList<WarnData> warns = entry.getValue();

            if (warns.isEmpty()) {
                continue;
            }

            if (warns.get(0).name().equalsIgnoreCase(name)) {
                foundUuid = entry.getKey();
                foundWarns = warns;
                break;
            }
        }

        if (foundUuid == null || foundWarns == null || number > foundWarns.size()) {
            return false;
        }

        foundWarns.remove(number - 1);

        if (foundWarns.isEmpty()) {
            WARNED_PLAYERS.remove(foundUuid);
        }

        saveWarns();
        return true;
    }

    private static void loadWarns() {
        WARNED_PLAYERS.clear();

        Path path = warnsPath();
        if (!Files.exists(path)) {
            return;
        }

        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }

                String[] parts = line.split("\\t", 4);
                if (parts.length < 4) {
                    continue;
                }

                UUID uuid = UUID.fromString(parts[0]);
                String name = decodeBase64(parts[1]);
                long createdMillis = Long.parseLong(parts[2]);
                String reason = decodeBase64(parts[3]);

                WARNED_PLAYERS
                        .computeIfAbsent(uuid, key -> new ArrayList<>())
                        .add(new WarnData(name, createdMillis, reason));
            }
        } catch (Throwable e) {
            System.out.println("[CubeChat] Failed to load warns: " + e.getMessage());
        }
    }

    private static void saveWarns() {
        Path path = warnsPath();

        try {
            Files.createDirectories(path.getParent());

            ArrayList<String> lines = new ArrayList<>();

            for (Map.Entry<UUID, ArrayList<WarnData>> entry : WARNED_PLAYERS.entrySet()) {
                for (WarnData warn : entry.getValue()) {
                    lines.add(entry.getKey()
                            + "\t" + encodeBase64(warn.name())
                            + "\t" + warn.createdMillis()
                            + "\t" + encodeBase64(warn.reason()));
                }
            }

            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            System.out.println("[CubeChat] Failed to save warns: " + e.getMessage());
        }
    }

    private static boolean isMuted(ServerPlayer player) {
        MuteData data = MUTED_PLAYERS.get(player.getUUID());

        if (data == null) {
            return false;
        }

        if (System.currentTimeMillis() >= data.untilMillis()) {
            MUTED_PLAYERS.remove(player.getUUID());
            return false;
        }

        return true;
    }

    private static void sendMutedMessage(ServerPlayer player) {
        MuteData data = MUTED_PLAYERS.get(player.getUUID());

        if (data == null) {
            return;
        }

        long leftMillis = Math.max(1000L, data.untilMillis() - System.currentTimeMillis());
        String reason = data.reason() == null || data.reason().isBlank() ? "не указана" : data.reason();

        String fullMessage = "§cТы замучен. Осталось: §e"
                + formatMuteTime(leftMillis)
                + "§c. Причина: §f"
                + reason;

        player.displayClientMessage(Component.literal(fullMessage), true);
        player.sendSystemMessage(Component.literal(fullMessage));
    }

    private static long parsePunishmentTime(String input) {
        if (input == null || input.length() < 2) {
            return -1L;
        }

        input = input.toLowerCase(java.util.Locale.ROOT);

        long value;
        try {
            value = Long.parseLong(input.substring(0, input.length() - 1));
        } catch (NumberFormatException e) {
            return -1L;
        }

        if (value <= 0L) {
            return -1L;
        }

        char unit = input.charAt(input.length() - 1);

        return switch (unit) {
            case 's' -> value * 1000L;
            case 'm' -> value * 60_000L;
            case 'h' -> value * 3_600_000L;
            case 'd' -> value * 86_400_000L;
            default -> -1L;
        };
    }

    private static String formatMuteTime(long millis) {
        long seconds = Math.max(1L, millis / 1000L);

        if (seconds < 60L) {
            return seconds + " сек.";
        }

        long minutes = seconds / 60L;
        if (minutes < 60L) {
            return minutes + " мин.";
        }

        long hours = minutes / 60L;
        if (hours < 24L) {
            return hours + " ч.";
        }

        long days = hours / 24L;
        return days + " дн.";
    }


    private static String formatDateTime(long millis) {
        return ZonedDateTime
                .ofInstant(java.time.Instant.ofEpochMilli(millis), MOSCOW_ZONE)
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
    }

    public static String getDiscordDisplayName(ServerPlayer player) {
        return getDiscordDisplayName(player, "");
    }

    public static String getDiscordDisplayName(ServerPlayer player, String channelPrefix) {
        String displayName = color(channelPrefix == null ? "" : channelPrefix)
                + getLuckPermsPrefix(player)
                + getStaffTag(player)
                + player.getGameProfile().getName();

        displayName = stripColor(displayName).trim().replaceAll("\\s+", " ");

        if (displayName.length() > 80) {
            displayName = displayName.substring(0, 80);
        }

        return displayName;
    }

    private static String getLuckPermsPrefix(ServerPlayer player) {
        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            User user = luckPerms.getPlayerAdapter(ServerPlayer.class).getUser(player);

            String prefix = user.getCachedData().getMetaData().getPrefix();

            if (prefix == null) {
                return "";
            }

            return color(prefix);
        } catch (Throwable e) {
            return "";
        }
    }

    private static boolean hasCommandPermission(CommandSourceStack source, String permission) {
        if (source.hasPermission(2)) {
            return true;
        }

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return false;
        }

        return hasPermissionNode(player, permission);
    }

    public static boolean shouldShowInDiscordOnlineStatus(ServerPlayer player) {
        if (player == null) {
            return false;
        }

        if (player.isSpectator() || player.isInvisible()) {
            return false;
        }

        if (player.getTags().contains("cubechat_hide_online")
                || player.getTags().contains("cubechat_hidden")
                || player.getTags().contains("vanished")
                || player.getTags().contains("vanish")
                || player.getTags().contains("hidden")) {
            return false;
        }

        if (hasPermissionNode(player, "cubechat.discord.hideonline")) {
            return false;
        }

        return !isFtbEssentialsVanished(player);
    }

    private static boolean isFtbEssentialsVanished(ServerPlayer player) {
        try {
            Class<?> dataClass = Class.forName("dev.ftb.mods.ftbessentials.util.FTBEPlayerData");
            Object optional = dataClass
                    .getMethod("getOrCreate", net.minecraft.world.entity.player.Player.class)
                    .invoke(null, player);

            if (!(optional instanceof java.util.Optional<?> opt) || opt.isEmpty()) {
                return false;
            }

            Object data = opt.get();
            String[] methodNames = {"isVanished", "getVanished", "vanished", "isVanish", "getVanish", "vanish"};

            for (String methodName : methodNames) {
                try {
                    Object result = data.getClass().getMethod(methodName).invoke(data);
                    if (result instanceof Boolean value) {
                        return value;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static boolean hasPermissionNode(ServerPlayer player, String permission) {
        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            User user = luckPerms.getPlayerAdapter(ServerPlayer.class).getUser(player);
            return user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
        } catch (Throwable ignored) {
            return player.hasPermissions(2);
        }
    }

    private static String getPrimaryGroup(ServerPlayer player) {
        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            User user = luckPerms.getPlayerAdapter(ServerPlayer.class).getUser(player);

            return user.getPrimaryGroup().toLowerCase();
        } catch (Throwable e) {
            return "";
        }
    }

    private static String getStaffTag(ServerPlayer player) {
        String group = getPrimaryGroup(player);

        return switch (group) {
            case "admin" -> "§4[Админ] ";
            case "curator" -> "§6[Куратор] ";
            case "headmoderator" -> "§c[Гл.Модератор] ";
            case "moderator" -> "§2[Модератор] ";
            case "helper" -> "§b[Хелпер] ";
            case "trainee" -> "§a[Стажёр] ";
            default -> "";
        };
    }

    private static String color(String text) {
        return text.replace("&", "§");
    }

    private static String stripColor(String text) {
        return text.replaceAll("§.", "");
    }

    private static String sanitizeDiscord(String text) {
        return text
                .replace("@everyone", "@\u200Beveryone")
                .replace("@here", "@\u200Bhere")
                .replace("§", "");
    }

    private static String timePrefix() {
        return "§8[" + ZonedDateTime.now(MOSCOW_ZONE).format(TIME_FORMAT) + " МСК] ";
    }

    private static boolean shouldShowTime(ServerPlayer player) {
        return SHOW_TIME.getOrDefault(player.getUUID(), true);
    }

    private static void toggleTime(ServerPlayer player) {
        boolean current = shouldShowTime(player);
        boolean next = !current;

        SHOW_TIME.put(player.getUUID(), next);

        if (next) {
            player.displayClientMessage(Component.literal("§aВремя в чате включено."), true);
        } else {
            player.displayClientMessage(Component.literal("§cВремя в чате выключено."), true);
        }
    }

    private static String timePrefix(ServerPlayer player) {
        if (!shouldShowTime(player)) {
            return "";
        }

        return "§8[" + ZonedDateTime.now(MOSCOW_ZONE).format(TIME_FORMAT) + " МСК] ";
    }

    private static final class ClearChatPacket {
        private ClearChatPacket() {
        }

        private static void encode(ClearChatPacket packet, FriendlyByteBuf buffer) {
        }

        private static ClearChatPacket decode(FriendlyByteBuf buffer) {
            return new ClearChatPacket();
        }

        private static void handle(ClearChatPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> CubeChatClient::clearChatMessages
            ));
            context.setPacketHandled(true);
        }
    }

    private record ChatHistoryMessage(long order, ChatView view, String message) {
    }

    private record MuteData(String name, long untilMillis, String reason) {
    }

    private record TempBanData(String name, long untilMillis, String reason) {
    }


    private record LastLocationData(String name, String dimension, double x, double y, double z, float yRot, float xRot, long savedMillis) {
    }

    private record WarnData(String name, long createdMillis, String reason) {
    }
}
