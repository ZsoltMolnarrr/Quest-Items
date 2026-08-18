package net.quest_items.locator;

import net.blay09.mods.waystones.api.Waystone;
import net.blay09.mods.waystones.api.WaystoneTypes;
import net.blay09.mods.waystones.api.WaystonesAPI;
import net.blay09.mods.waystones.block.WaystoneBlock;
import net.blay09.mods.waystones.config.WaystonesConfig;
import net.blay09.mods.waystones.worldgen.namegen.NameGeneratorManager;
import net.minecraft.server.world.ChunkLevelType;
import net.minecraft.server.world.ChunkLevels;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.OptionalChunk;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.chunk.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.chunk.placement.StructurePlacement;
import net.minecraft.world.gen.structure.Structure;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Finds the nearest Waystone to a center position, within a block radius.
 *
 * Waystones spawn two ways — in the wilderness on a deterministic seed grid, and inside
 * structures (e.g. a village waystone house, or a waystone embedded in a modded tower template).
 * Both are discoverable from data alone, so the search works like structure location rather than
 * a terrain sweep:
 *
 * 1. Registry query — waystones already materialized anywhere in radius. Done, no chunks touched.
 * 2. Structure-hosted candidates (checked first): every structure type's placement grid within
 *    the radius is enumerated (pure math), matching structure starts are read at
 *    STRUCTURE_STARTS metadata level (piece layout, no terrain), and each start's pieces are
 *    checked via {@link WaystoneTemplates} — a piece hosts a waystone iff its template's NBT
 *    contains a waystone block. No curated structure list needed; any structure that embeds a
 *    waystone qualifies automatically. Matching pieces contribute their 1-2 chunks as candidates.
 * 3. Wilderness candidates: Waystones' deterministic placement formula
 *    (WaystonePlacement.isWaystoneChunk — seed + chunk coords + config spacing) is replicated
 *    over the radius; the matching chunks (a couple dozen at default spacing) are appended after
 *    the structure candidates.
 * 4. Candidate chunks are generated nearest-first (structures before wilderness), a few at a
 *    time, up to the cheap FEATURES stage via async chunk futures on the worldgen pool, polling
 *    once per tick from the {@link TickWorkers} budget. Wilderness waystones self-register
 *    during generation (registry re-query detects them); structure waystones are caught by the
 *    palette scan of completed chunks. Only the single winning chunk is promoted to FULL so its
 *    waystone registers and can be activated.
 */
public class WaystoneSearch implements TickWorkers.Worker {

    private static final Logger LOGGER = LoggerFactory.getLogger("quest_items/locate_waystone");

    /** Chunk generations requested but not yet completed. Candidates are few, so a small window. */
    private static final int MAX_IN_FLIGHT = 8;

    /**
     * addTicket computes level = FULL(33) - radius, so this (negative) radius yields the level
     * that holds chunks at FEATURES and no further. NOTE: a ticket at a proto level only PINS the
     * chunk holder — ChunkHolder.updateFutures schedules generation solely for FULL and above.
     * Generation is actually driven by getChunkFutureSyncOnMainThread below; this ticket exists
     * to keep the holder alive while the (1-tick-expiry) internal UNKNOWN ticket comes and goes.
     */
    private static final int TICKET_RADIUS =
            ChunkLevels.getLevelFromType(ChunkLevelType.FULL) - ChunkLevels.getLevelFromStatus(ChunkStatus.FEATURES);

    /** Abort a search whose chunk futures stop completing entirely (safety net, ~60s of silence). */
    private static final int STALL_TICKS = 1200;

    /** Expiry is a backstop only — tickets are removed manually as chunks complete or the search ends. */
    private static final ChunkTicketType<ChunkPos> TICKET =
            ChunkTicketType.create("quest_items_locator", Comparator.comparingLong(ChunkPos::toLong), 20 * 60 * 5);

    private final ServerWorld world;
    private final BlockPos center;
    private final int radiusBlocks;
    private final int maxRing;
    private final ChunkPos centerChunk;
    private final Consumer<Waystone> onFound;
    private final Runnable onExhausted;
    private final Consumer<Integer> onProgressPercent;

    private record Pending(ChunkPos pos, CompletableFuture<OptionalChunk<Chunk>> future) {}

    private record ScanUnit(StructurePlacement placement, List<Structure> structures, ChunkPos pos) {}

    private record Candidate(ChunkPos pos, long distSq) {}

    private enum Stage { SCAN, GENERATE }

    private Registry<Structure> structureRegistry;
    private WaystoneTemplates waystoneTemplates;
    private Stage stage = Stage.SCAN;
    private long searchStartMs;
    private long stageStartMs;

    private List<ScanUnit> scanUnits = List.of();
    private int scanIndex = 0;
    private int assembledStarts = 0;
    private final Set<String> inspectedStarts = new HashSet<>();
    private final List<Candidate> structureCandidates = new ArrayList<>();

    private List<ChunkPos> candidates = List.of();
    private int candidateIndex = 0;
    private int completedCandidates = 0;
    private final List<Pending> inFlight = new ArrayList<>();
    private int ticksWithoutProgress = 0;
    /** Waystone block positions spotted by the palette scan, awaiting full-load resolution. */
    private final List<BlockPos> scanCandidates = new ArrayList<>();
    /**
     * Set once a hit is known: stop issuing new tickets and let in-flight generation finish
     * before resolving. Wilderness waystone features write Waystones' saved data from worldgen
     * threads through a non-thread-safe store (DimensionDataStorage); resolving/activating also
     * touches that store from the main thread, so the two must never overlap.
     */
    private boolean draining = false;
    private boolean finished = false;

    public WaystoneSearch(ServerWorld world, BlockPos center, int radiusBlocks,
                          Consumer<Waystone> onFound, Runnable onExhausted, Consumer<Integer> onProgressPercent) {
        this.world = world;
        this.center = center;
        this.radiusBlocks = radiusBlocks;
        this.maxRing = (radiusBlocks + 15) / 16;
        this.centerChunk = new ChunkPos(center);
        this.onFound = onFound;
        this.onExhausted = onExhausted;
        this.onProgressPercent = onProgressPercent;
    }

    /** Callbacks fire on the server thread; onFound may fire synchronously from here. */
    public void start() {
        // findRegistered() also loads the waystone registry into the saved-data cache on the
        // main thread, before any worldgen thread can race the (non-thread-safe) lazy load.
        Optional<Waystone> existing = findRegistered();
        if (existing.isPresent()) {
            finished = true;
            onFound.accept(existing.get());
            return;
        }
        warmUpSavedData();
        searchStartMs = System.currentTimeMillis();
        stageStartMs = searchStartMs;
        structureRegistry = world.getRegistryManager().get(RegistryKeys.STRUCTURE);
        waystoneTemplates = new WaystoneTemplates(world);
        scanUnits = buildScanUnits();
        LOGGER.info("Waystone search around {} (radius {}): no registered waystone in range; "
                        + "{} structure placement positions to scan nearest-first ({} templates cached)",
                center.toShortString(), radiusBlocks, scanUnits.size(), WaystoneTemplates.cachedTemplateCount());
        TickWorkers.add(this);
    }

    /**
     * Waystones loads its name-generator saved data lazily on first activation — which is exactly
     * when this command activates a waystone, potentially while waystone features on worldgen
     * threads are still reading the same non-thread-safe saved-data store. Loading it up front on
     * the main thread removes that write entirely.
     */
    private void warmUpSavedData() {
        try {
            NameGeneratorManager.get(world.getServer());
        } catch (Throwable ignored) {
            // Internal Waystones class; if a future version moves it, the warm-up is skipped
            // (the drain before resolution still guards the common case).
        }
    }

    @Override
    public boolean hasWork() {
        return !finished;
    }

    @Override
    public boolean doWork() {
        // Stage 1: scan structure placement positions nearest-first. Each unit computes the
        // structure's start locally (biome-mismatches reject cheaply; only real instances pay
        // jigsaw assembly) and inspects its pieces for waystone templates — no chunks touched.
        // The scan stops at the first waystone piece found: units are distance-ordered, so the
        // first hit is the nearest structure waystone.
        if (stage == Stage.SCAN) {
            boolean hit = !structureCandidates.isEmpty();
            if (!hit && scanIndex < scanUnits.size()) {
                processScanUnit(scanUnits.get(scanIndex++));
                if (scanIndex % 64 == 0) {
                    onProgressPercent.accept(scanIndex * 60 / Math.max(1, scanUnits.size()));
                }
                return true;
            }
            candidates = assembleCandidates();
            LOGGER.info("Structure scan {} in {} ms: {}/{} positions checked, {} starts assembled, "
                            + "{} waystone-piece chunks; {} total candidate chunks incl. wilderness grid",
                    hit ? "hit" : "exhausted", System.currentTimeMillis() - stageStartMs,
                    scanIndex, scanUnits.size(), assembledStarts,
                    structureCandidates.size(), candidates.size());
            if (candidates.isEmpty()) {
                LOGGER.info("No candidate chunks at all — reporting no waystone ({} ms total)",
                        System.currentTimeMillis() - searchStartMs);
                finished = true;
                onExhausted.run();
                return false;
            }
            stage = Stage.GENERATE;
            stageStartMs = System.currentTimeMillis();
            return true;
        }

        // Stage 2: generate candidate chunks and detect the waystone.
        // Collect completed chunks: release their tickets and palette-scan them for waystone blocks.
        int completed = 0;
        for (int i = inFlight.size() - 1; i >= 0; i--) {
            Pending pending = inFlight.get(i);
            if (pending.future().isDone()) {
                world.getChunkManager().removeTicket(TICKET, pending.pos(), TICKET_RADIUS, pending.pos());
                inFlight.remove(i);
                completed++;
                Chunk chunk = getResult(pending.future());
                if (chunk != null) {
                    scanForWaystone(chunk, pending.pos()).ifPresent(scanCandidates::add);
                }
            }
        }

        // Safety net: if generation stops making progress entirely, end the search instead of
        // holding the player's busy-guard forever.
        if (completed == 0 && !inFlight.isEmpty()) {
            if (++ticksWithoutProgress > STALL_TICKS) {
                finishStalled();
                return false;
            }
        } else {
            ticksWithoutProgress = 0;
        }

        if (completed > 0) {
            completedCandidates += completed;
            onProgressPercent.accept(Math.min(99, 60 + completedCandidates * 39 / candidates.size()));
            LOGGER.info("Generated {}/{} candidate chunks ({} ms in generation stage)",
                    completedCandidates, candidates.size(), System.currentTimeMillis() - stageStartMs);
        }

        // A hit (registry or scanned block) exists: stop expanding, let in-flight generation
        // finish, and only then resolve — activation must not overlap running waystone features.
        if (!draining && completed > 0 && (findRegistered().isPresent() || !scanCandidates.isEmpty())) {
            draining = true;
        }

        if (!draining) {
            // Top the in-flight window back up; the chunk system generates these in parallel.
            // The ticket must be added before requesting the future so the holder never sits
            // without level support once the internal UNKNOWN ticket (1-tick expiry) lapses.
            while (inFlight.size() < MAX_IN_FLIGHT) {
                ChunkPos next = nextPosition();
                if (next == null) {
                    break;
                }
                world.getChunkManager().addTicket(TICKET, next, TICKET_RADIUS, next);
                var future = world.getChunkManager()
                        .getChunkFutureSyncOnMainThread(next.x, next.z, ChunkStatus.FEATURES, true);
                inFlight.add(new Pending(next, future));
            }
        }

        if (inFlight.isEmpty()) {
            if (draining) {
                draining = false;
                Optional<Waystone> found = findRegistered().or(this::resolveScanCandidates);
                if (found.isPresent()) {
                    finished = true;
                    logFound(found.get());
                    onFound.accept(found.get());
                }
                // Otherwise the candidates didn't pan out — resume sweeping next tick.
                return false;
            }
            // Window empty right after topping up: the whole radius has been swept.
            finished = true;
            Optional<Waystone> found = findRegistered().or(this::resolveScanCandidates);
            if (found.isPresent()) {
                logFound(found.get());
                onFound.accept(found.get());
            } else {
                LOGGER.info("All {} candidates exhausted, no waystone found ({} ms total)",
                        candidates.size(), System.currentTimeMillis() - searchStartMs);
                onExhausted.run();
            }
            return false;
        }

        // One poll per tick is enough; the real work happens on the chunk system's threads.
        return false;
    }

    @Nullable
    private static Chunk getResult(CompletableFuture<OptionalChunk<Chunk>> future) {
        try {
            OptionalChunk<Chunk> result = future.getNow(null);
            return result != null ? result.orElse(null) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void logFound(Waystone waystone) {
        LOGGER.info("Waystone found at {} ({} of {} candidates generated, {} ms total)",
                waystone.getPos().toShortString(), completedCandidates, candidates.size(),
                System.currentTimeMillis() - searchStartMs);
    }

    /** Ends a search whose generation futures stopped completing; reports whatever is known. */
    private void finishStalled() {
        LOGGER.warn("Waystone search stalled: no chunk completions for {} ticks with {} in flight; aborting ({} ms total)",
                STALL_TICKS, inFlight.size(), System.currentTimeMillis() - searchStartMs);
        finished = true;
        for (Pending pending : inFlight) {
            world.getChunkManager().removeTicket(TICKET, pending.pos(), TICKET_RADIUS, pending.pos());
        }
        inFlight.clear();
        Optional<Waystone> found = findRegistered();
        if (found.isPresent()) {
            onFound.accept(found.get());
        } else {
            onExhausted.run();
        }
    }

    /**
     * A scanned waystone block is not yet in the registry (its block entity never loaded at
     * FEATURES). Promote its chunk — just that one — to FULL, which loads the block entity and
     * registers the waystone, then pick it up from the registry.
     */
    private Optional<Waystone> resolveScanCandidates() {
        if (scanCandidates.isEmpty()) {
            return Optional.empty();
        }
        scanCandidates.sort(Comparator.comparingLong(this::horizontalDistanceSq));
        for (BlockPos candidate : scanCandidates) {
            world.getChunk(ChunkSectionPos.getSectionCoord(candidate.getX()),
                    ChunkSectionPos.getSectionCoord(candidate.getZ()), ChunkStatus.FULL, true);
            Optional<Waystone> found = findRegistered();
            if (found.isPresent()) {
                scanCandidates.clear();
                return found;
            }
        }
        scanCandidates.clear();
        return Optional.empty();
    }

    /** Next candidate chunk in nearest-first order, or null when all candidates are issued. */
    private ChunkPos nextPosition() {
        return candidateIndex < candidates.size() ? candidates.get(candidateIndex++) : null;
    }

    /**
     * Enumerates the placement-grid positions of EVERY structure type within reach — pure seed
     * math per position, exactly how structure location works. Structure footprints sprawl, so
     * start chunks slightly beyond the waystone radius are included; actual waystone pieces are
     * distance-filtered later. The result is sorted nearest-first so the scan can stop at its
     * first hit.
     */
    private List<ScanUnit> buildScanUnits() {
        var calculator = world.getChunkManager().getStructurePlacementCalculator();
        Map<StructurePlacement, List<Structure>> byPlacement = new LinkedHashMap<>();
        structureRegistry.streamEntries().forEach(entry -> {
            for (StructurePlacement placement : calculator.getPlacements(entry)) {
                byPlacement.computeIfAbsent(placement, p -> new ArrayList<>()).add(entry.value());
            }
        });

        int scanRing = maxRing + 8;
        List<ScanUnit> units = new ArrayList<>();
        for (Map.Entry<StructurePlacement, List<Structure>> entry : byPlacement.entrySet()) {
            StructurePlacement placement = entry.getKey();
            Set<ChunkPos> positions = new LinkedHashSet<>();
            if (placement instanceof RandomSpreadStructurePlacement randomSpread) {
                int spacing = randomSpread.getSpacing();
                for (int cx = centerChunk.x - scanRing; cx <= centerChunk.x + scanRing + spacing; cx += spacing) {
                    for (int cz = centerChunk.z - scanRing; cz <= centerChunk.z + scanRing + spacing; cz += spacing) {
                        positions.add(randomSpread.getStartChunk(world.getSeed(), cx, cz));
                    }
                }
            } else if (placement instanceof ConcentricRingsStructurePlacement rings) {
                List<ChunkPos> ringPositions = calculator.getPlacementPositions(rings);
                if (ringPositions != null) {
                    positions.addAll(ringPositions);
                }
            } else {
                for (int cx = centerChunk.x - scanRing; cx <= centerChunk.x + scanRing; cx++) {
                    for (int cz = centerChunk.z - scanRing; cz <= centerChunk.z + scanRing; cz++) {
                        if (placement.shouldGenerate(calculator, cx, cz)) {
                            positions.add(new ChunkPos(cx, cz));
                        }
                    }
                }
            }
            for (ChunkPos pos : positions) {
                if (Math.max(Math.abs(pos.x - centerChunk.x), Math.abs(pos.z - centerChunk.z)) <= scanRing) {
                    units.add(new ScanUnit(placement, entry.getValue(), pos));
                }
            }
        }
        units.sort(Comparator.comparingLong(unit -> {
            long dx = unit.pos().x - centerChunk.x;
            long dz = unit.pos().z - centerChunk.z;
            return dx * dx + dz * dz;
        }));
        return units;
    }

    /**
     * Checks one placement-grid position by computing the structure start locally — the same
     * public path the /place command uses. Biome mismatches reject before assembly (cheap);
     * only a structure that would truly generate here pays its jigsaw assembly. This avoids
     * STRUCTURE_STARTS chunk computation entirely, which would evaluate ALL registered
     * structure types per chunk and thrash the chunk system.
     */
    private void processScanUnit(ScanUnit unit) {
        // Grid enumeration alone ignores placement frequency/exclusion-zone rules; this is the
        // authoritative (and cheap) check that the structure set really rolls this position.
        if (!unit.placement().shouldGenerate(
                world.getChunkManager().getStructurePlacementCalculator(), unit.pos().x, unit.pos().z)) {
            return;
        }
        var chunkGenerator = world.getChunkManager().getChunkGenerator();
        for (Structure structure : unit.structures()) {
            String startKey = structureRegistry.getId(structure) + "@" + unit.pos().toLong();
            if (!inspectedStarts.add(startKey)) {
                continue;
            }
            StructureStart start;
            try {
                start = structure.createStructureStart(
                        world.getRegistryManager(),
                        chunkGenerator,
                        chunkGenerator.getBiomeSource(),
                        world.getChunkManager().getNoiseConfig(),
                        world.getStructureTemplateManager(),
                        world.getSeed(),
                        unit.pos(),
                        0,
                        world,
                        structure.getValidBiomes()::contains);
            } catch (Throwable t) {
                // A broken third-party structure must not kill the search.
                continue;
            }
            if (!start.hasChildren()) {
                continue;
            }
            assembledStarts++;
            long reach = (long) radiusBlocks + 12;
            for (BlockBox box : waystoneTemplates.waystonePieceBoxes(start)) {
                BlockPos boxCenter = box.getCenter();
                long distSq = horizontalDistanceSq(boxCenter);
                if (distSq > reach * reach) {
                    continue;
                }
                for (int cx = box.getMinX() >> 4; cx <= box.getMaxX() >> 4; cx++) {
                    for (int cz = box.getMinZ() >> 4; cz <= box.getMaxZ() >> 4; cz++) {
                        structureCandidates.add(new Candidate(new ChunkPos(cx, cz), distSq));
                    }
                }
            }
        }
    }

    /** Structure-hosted candidates first (per preference), wilderness grid after; both nearest-first. */
    private List<ChunkPos> assembleCandidates() {
        structureCandidates.sort(Comparator.comparingLong(Candidate::distSq));
        LinkedHashSet<ChunkPos> ordered = new LinkedHashSet<>();
        for (Candidate candidate : structureCandidates) {
            ordered.add(candidate.pos());
        }
        ordered.addAll(computeWildernessCandidates());
        return List.copyOf(ordered);
    }

    /**
     * Replicates {@code WaystonePlacement.isWaystoneChunk} — Waystones' deterministic wilderness
     * placement formula (world seed + chunk coordinates + configured spacing) — over every chunk
     * within the radius, and returns the matches sorted nearest-first. Pure math, no chunk access.
     * The formula is copied verbatim (including its truncating divisions and seed quirks) so the
     * candidate grid matches what actually generates. Config values are read from the live
     * Waystones config; if a future Waystones version moves them, defaults matching Waystones'
     * shipped defaults are used instead.
     */
    private List<ChunkPos> computeWildernessCandidates() {
        int chunkDistance;
        Set<Identifier> allowList;
        Set<Identifier> denyList;
        try {
            var worldGen = WaystonesConfig.getActive().worldGen;
            chunkDistance = worldGen.chunksBetweenWildWaystones;
            allowList = worldGen.wildWaystonesDimensionAllowList;
            denyList = worldGen.wildWaystonesDimensionDenyList;
        } catch (Throwable ignored) {
            chunkDistance = 25;
            allowList = Set.of(Identifier.ofVanilla("overworld"),
                    Identifier.ofVanilla("the_nether"), Identifier.ofVanilla("the_end"));
            denyList = Set.of();
        }
        if (chunkDistance == 0) {
            return List.of();
        }
        Identifier dimension = world.getRegistryKey().getValue();
        if (!allowList.isEmpty() && !allowList.contains(dimension)) {
            return List.of();
        }
        if (!denyList.isEmpty() && denyList.contains(dimension)) {
            return List.of();
        }

        final int maxDeviation = (int) Math.ceil(chunkDistance / 2f);
        final long seed = world.getSeed();
        final long reach = (long) radiusBlocks + 12;
        List<ChunkPos> result = new ArrayList<>();
        for (int cx = centerChunk.x - maxRing; cx <= centerChunk.x + maxRing; cx++) {
            for (int cz = centerChunk.z - maxRing; cz <= centerChunk.z + maxRing; cz++) {
                // The feature origin is the chunk's start block pos; divisions mirror the original.
                int blockX = cx * 16;
                int blockZ = cz * 16;
                int chunkX = blockX / 16;
                int chunkZ = blockZ / 16;
                int devGridX = blockX / 16 * maxDeviation;
                int devGridZ = blockZ / 16 * maxDeviation;
                Random random = new Random(seed * devGridX * devGridZ);
                int chunkOffsetX = random.nextInt(maxDeviation);
                int chunkOffsetZ = random.nextInt(maxDeviation);
                if ((chunkX + chunkOffsetX) % chunkDistance != 0 || (chunkZ + chunkOffsetZ) % chunkDistance != 0) {
                    continue;
                }
                long dx = blockX + 8 - center.getX();
                long dz = blockZ + 8 - center.getZ();
                if (dx * dx + dz * dz <= reach * reach) {
                    result.add(new ChunkPos(cx, cz));
                }
            }
        }
        result.sort(Comparator.comparingLong(pos ->
                horizontalDistanceSq(new BlockPos(pos.getStartX() + 8, 0, pos.getStartZ() + 8))));
        return result;
    }

    private Optional<Waystone> findRegistered() {
        long radiusSq = (long) radiusBlocks * radiusBlocks;
        return WaystonesAPI.getWaystonesByType(world.getServer(), WaystoneTypes.WAYSTONE)
                .filter(waystone -> waystone.getDimension() == world.getRegistryKey())
                .filter(waystone -> horizontalDistanceSq(waystone.getPos()) <= radiusSq)
                .min(Comparator.comparingLong(waystone -> horizontalDistanceSq(waystone.getPos())));
    }

    /**
     * Looks for a waystone block in a generated chunk. The palette check inspects only each
     * section's palette (a handful of entries), not blocks; the per-block scan below it runs
     * only on the rare section that actually contains a waystone.
     */
    private Optional<BlockPos> scanForWaystone(Chunk chunk, ChunkPos pos) {
        ChunkSection[] sections = chunk.getSectionArray();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()
                    || !section.getBlockStateContainer().hasAny(state -> state.getBlock() instanceof WaystoneBlock)) {
                continue;
            }
            int baseY = ChunkSectionPos.getBlockCoord(chunk.sectionIndexToCoord(i));
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (section.getBlockState(x, y, z).getBlock() instanceof WaystoneBlock) {
                            return Optional.of(new BlockPos(pos.getStartX() + x, baseY + y, pos.getStartZ() + z));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private long horizontalDistanceSq(BlockPos pos) {
        long dx = pos.getX() - center.getX();
        long dz = pos.getZ() - center.getZ();
        return dx * dx + dz * dz;
    }

}
