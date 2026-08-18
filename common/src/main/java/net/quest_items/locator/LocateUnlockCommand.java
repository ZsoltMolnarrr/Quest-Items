package net.quest_items.locator;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.blay09.mods.waystones.api.Waystone;
import net.blay09.mods.waystones.api.WaystonesAPI;
import net.minecraft.command.argument.DimensionArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.RegistryPredicateArgumentType;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.Texts;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.structure.Structure;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * /quest_items locate_waystone &lt;structure&gt; [dimension] [structure_search_radius] [waystone_search_radius] [player]
 *
 * Locates the nearest matching structure (searching from the invoking player), then finds the
 * nearest waystone to that structure (generating terrain around it if needed), and unlocks it
 * for the target player. Both phases run inside the {@link TickWorkers} tick budget, so the
 * command returns immediately and results arrive as chat messages.
 */
public class LocateUnlockCommand {

    private static final DynamicCommandExceptionType STRUCTURE_INVALID_EXCEPTION = new DynamicCommandExceptionType(
            id -> Text.stringifiedTranslatable("commands.locate.structure.invalid", id));

    public static final int DEFAULT_STRUCTURE_SEARCH_RADIUS = 10_000;
    public static final int DEFAULT_WAYSTONE_SEARCH_RADIUS = 1024;

    private static final String KEY_PREFIX = "commands.quest_items.locate_waystone.";

    /** Players with a search currently underway (guards against stacking searches). */
    private static final Set<UUID> ACTIVE = new HashSet<>();

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("quest_items")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("locate_waystone")
                        .then(CommandManager.argument("structure", RegistryPredicateArgumentType.registryPredicate(RegistryKeys.STRUCTURE))
                                .executes(ctx -> run(ctx, false, false, false, false))
                                .then(CommandManager.argument("dimension", DimensionArgumentType.dimension())
                                        .executes(ctx -> run(ctx, true, false, false, false))
                                        .then(CommandManager.argument("structure_search_radius", IntegerArgumentType.integer(100, 100_000))
                                                .executes(ctx -> run(ctx, true, true, false, false))
                                                .then(CommandManager.argument("waystone_search_radius", IntegerArgumentType.integer(16, 4096))
                                                        .executes(ctx -> run(ctx, true, true, true, false))
                                                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                                                .executes(ctx -> run(ctx, true, true, true, true)))))))));
    }

    /** Clears transient state; call when the server stops. */
    public static void reset() {
        ACTIVE.clear();
    }

    private static int run(CommandContext<ServerCommandSource> ctx,
                           boolean hasDimension, boolean hasStructureRadius, boolean hasWaystoneRadius, boolean hasPlayer)
            throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        var predicate = RegistryPredicateArgumentType.getPredicate(ctx, "structure", RegistryKeys.STRUCTURE, STRUCTURE_INVALID_EXCEPTION);
        ServerPlayerEntity player = hasPlayer ? EntityArgumentType.getPlayer(ctx, "player") : source.getPlayerOrThrow();
        ServerWorld world = hasDimension ? DimensionArgumentType.getDimensionArgument(ctx, "dimension") : player.getServerWorld();
        int structureRadius = hasStructureRadius ? IntegerArgumentType.getInteger(ctx, "structure_search_radius") : DEFAULT_STRUCTURE_SEARCH_RADIUS;
        int waystoneRadius = hasWaystoneRadius ? IntegerArgumentType.getInteger(ctx, "waystone_search_radius") : DEFAULT_WAYSTONE_SEARCH_RADIUS;

        Registry<Structure> registry = world.getRegistryManager().get(RegistryKeys.STRUCTURE);
        RegistryEntryList<Structure> structures = getStructureListForPredicate(predicate, registry)
                .orElseThrow(() -> STRUCTURE_INVALID_EXCEPTION.create(predicate.asString()));
        List<RegistryEntry<Structure>> structureEntries = structures.stream().toList();
        Text queryName = prettyQueryName(predicate.asString());

        if (!ACTIVE.add(player.getUuid())) {
            source.sendError(Text.translatable(KEY_PREFIX + "busy", player.getDisplayName()));
            return 0;
        }

        // Search radius counts from the invoking player when there is one, else from the target player.
        BlockPos origin = source.getEntity() instanceof ServerPlayerEntity sourcePlayer
                ? sourcePlayer.getBlockPos()
                : player.getBlockPos();

        MinecraftServer server = world.getServer();
        UUID playerUuid = player.getUuid();

        source.sendFeedback(() -> Text.translatable(KEY_PREFIX + "searching", queryName, structureRadius)
                .formatted(Formatting.GRAY), false);

        StructureSearch.start(world, origin, structureEntries, structureRadius,
                (structurePos, structure) -> {
                    Text structureName = prettyId(registry.getId(structure));
                    send(server, source, playerUuid, Text.translatable(KEY_PREFIX + "structure_found",
                            structureName, coordinates(structurePos)).formatted(Formatting.GRAY));

                    new WaystoneSearch(world, structurePos, waystoneRadius,
                            waystone -> {
                                ACTIVE.remove(playerUuid);
                                unlock(server, source, playerUuid, waystone, structureName);
                            },
                            () -> {
                                ACTIVE.remove(playerUuid);
                                send(server, source, playerUuid, Text.translatable(KEY_PREFIX + "no_waystone",
                                        structureName, coordinates(structurePos), waystoneRadius).formatted(Formatting.YELLOW));
                            },
                            percent -> {
                                ServerPlayerEntity target = server.getPlayerManager().getPlayer(playerUuid);
                                if (target != null) {
                                    target.sendMessage(Text.translatable(KEY_PREFIX + "surveying", percent)
                                            .formatted(Formatting.GRAY), true);
                                }
                            }).start();
                },
                () -> {
                    ACTIVE.remove(playerUuid);
                    send(server, source, playerUuid, Text.translatable(KEY_PREFIX + "no_structure",
                            queryName, structureRadius).formatted(Formatting.YELLOW));
                });
        return 1;
    }

    private static void unlock(MinecraftServer server, ServerCommandSource source, UUID playerUuid,
                               Waystone waystone, Text structureName) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
        Text waystoneName = waystone.hasName() ? waystone.getName() : Text.translatable(KEY_PREFIX + "unnamed");
        if (player == null) {
            send(server, source, playerUuid, Text.translatable(KEY_PREFIX + "player_offline",
                    waystoneName, structureName).formatted(Formatting.YELLOW));
            return;
        }

        boolean alreadyUnlocked = WaystonesAPI.isWaystoneActivated(player, waystone);
        if (!alreadyUnlocked) {
            WaystonesAPI.activateWaystone(player, waystone);
            waystoneName = waystone.hasName() ? waystone.getName() : waystoneName;
        }

        String key = alreadyUnlocked ? "already_unlocked" : "unlocked";
        send(server, source, playerUuid, Text.translatable(KEY_PREFIX + key, waystoneName, structureName)
                .formatted(Formatting.GOLD));
    }

    /** Sends to the target player, and to the command source too when that is someone/something else. */
    private static void send(MinecraftServer server, ServerCommandSource source, UUID playerUuid, Text message) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
        if (player != null) {
            player.sendMessage(message);
        }
        if (source.getEntity() == null || source.getEntity() != player) {
            source.sendFeedback(() -> message, false);
        }
    }

    private static Optional<? extends RegistryEntryList.ListBacked<Structure>> getStructureListForPredicate(
            RegistryPredicateArgumentType.RegistryPredicate<Structure> predicate, Registry<Structure> registry) {
        return predicate.getKey().map(
                key -> registry.getEntry(key).map(entry -> RegistryEntryList.of(entry)),
                registry::getEntryList);
    }

    private static MutableText coordinates(BlockPos pos) {
        return Texts.bracketed(Text.translatable("chat.coordinates", pos.getX(), "~", pos.getZ()))
                .styled(style -> style
                        .withColor(Formatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                                "/tp @s " + pos.getX() + " ~ " + pos.getZ()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Text.translatable("chat.coordinates.tooltip"))));
    }

    private static Text prettyId(Identifier id) {
        return id != null ? Text.literal(prettyName(id.getPath())) : Text.translatable(KEY_PREFIX + "unnamed");
    }

    /** "minecraft:ancient_city" / "#minecraft:village" → "Ancient City" / "Village". */
    private static Text prettyQueryName(String predicateString) {
        String path = predicateString;
        int colon = path.indexOf(':');
        if (colon >= 0) {
            path = path.substring(colon + 1);
        }
        return Text.literal(prettyName(path));
    }

    private static String prettyName(String path) {
        String[] words = path.split("[_/]");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(word.charAt(0)));
            builder.append(word.substring(1));
        }
        return builder.toString();
    }
}
