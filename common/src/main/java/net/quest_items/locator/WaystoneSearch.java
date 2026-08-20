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
import net.minecraft.world.biome.source.BiomeCoords;
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

import java.util.ArrayDeque;
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

    /**
     * Chunk generations requested but not yet completed. Exactly one: every in-flight chunk
     * drags its own ~17x17 dependency apron through generation, and in 1.21.1 the chunk
     * load/save bookkeeping for all of that runs on the main thread outside our control. The
     * nearest candidate is usually a verified waystone piece anyway, so parallelism buys
     * little and costs TPS.
     */
    private static final int MAX_IN_FLIGHT = 1;

    /** A tick whose own work stayed under this had leftover headroom; only then do we add load. */
    private static final long HEALTHY_TICK_MS = 45;

    /** On a chronically busy server, still issue the next chunk request after this many ticks. */
    private static final int ISSUE_FORCE_TICKS = 40;

    /**
     * Completed chunks keep their tickets and are released by DECAY (one level-step per healthy
     * tick): dropping a ticket outright orphans its whole apron at once, and vanilla's
     * unloadChunks force-saves up to 200 chunks per tick IGNORING the time budget — the burst
     * that used to freeze the server. Stepping the ticket level outward sheds only an outer
     * ring of holders at a time, so unload-saves trickle. Above the soft cap, decay proceeds
     * even on busy ticks (memory guard).
     */
    private static final int TICKET_DECAY_STEP = 3;
    private static final int RETAINED_SOFT_CAP = 4;

    /** Give up on a hung background scan (broken third-party structure code) after 2 minutes. */
    private static final int SCAN_TIMEOUT_TICKS = 20 * 120;

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

    private enum Stage { SCAN, GENERATE, RESOLVE }

    /** Ticket radius 0 = level 33 = FULL, used by the RESOLVE stage's async full-loads. */
    private static final int RESOLVE_TICKET_RADIUS = 0;

    private Registry<Structure> structureRegistry;
    private WaystoneTemplates waystoneTemplates;
    private Stage stage = Stage.SCAN;
    private long searchStartMs;
    private long stageStartMs;

    private List<ScanUnit> scanUnits = List.of();
    /**
     * The scan runs on a worldgen worker thread (createStructureStart is what vanilla executes
     * on those threads during STRUCTURE_STARTS anyway) — the main thread only polls the future.
     * structureCandidates is written exclusively by the scan thread and read on the main thread
     * only after the future completes (the completion is the happens-before edge).
     */
    private CompletableFuture<Void> scanFuture;
    private volatile boolean scanAbandoned = false;
    private volatile int scannedUnits = 0;
    private volatile int totalScanUnits = 0;
    private volatile int assembledStarts = 0;
    private int scanWaitTicks = 0;
    private final Set<String> inspectedStarts = new HashSet<>();
    private final List<Candidate> structureCandidates = new ArrayList<>();

    /**
     * RESOLVE: binds the result to the block by fully loading the winning chunk ASYNCHRONOUSLY
     * (a blocking FULL load here previously froze the main thread for many seconds — full
     * promotion drags lighting plus its whole dependency apron). Whatever waystone the block
     * holds after the load is the identity activated (see the double-unlock notes).
     */
    private Waystone chosenWaystone;
    private final ArrayDeque<BlockPos> resolveQueue = new ArrayDeque<>();
    private Pending resolvePending;
    private BlockPos resolveTargetPos;
    private boolean sweepExhausted = false;

    private static final class RetainedTicket {
        final ChunkPos pos;
        int radius;

        RetainedTicket(ChunkPos pos, int radius) {
            this.pos = pos;
            this.radius = radius;
        }
    }

    private final ArrayDeque<RetainedTicket> retainedTickets = new ArrayDeque<>();
    private int issueWaitTicks = 0;

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
        searchStartMs = System.currentTimeMillis();
        stageStartMs = searchStartMs;
        warmUpSavedData();
        structureRegistry = world.getRegistryManager().get(RegistryKeys.STRUCTURE);
        waystoneTemplates = new WaystoneTemplates(world);

        Optional<Waystone> existing = findRegistered();
        if (existing.isPresent()) {
            // Bind to the block through the async RESOLVE stage — no blocking chunk load here.
            chosenWaystone = existing.get();
            resolveQueue.add(chosenWaystone.getPos());
            stage = Stage.RESOLVE;
            LOGGER.info("Waystone search around {} (radius {}): registered waystone already in range at {}; binding to block",
                    center.toShortString(), radiusBlocks, chosenWaystone.getPos().toShortString());
            TickWorkers.add(this);
            return;
        }
        LOGGER.info("Waystone search around {} (radius {}): no registered waystone in range; scanning structures off-thread",
                center.toShortString(), radiusBlocks);
        scanFuture = CompletableFuture.runAsync(this::runScan, net.minecraft.util.Util.getMainWorkerExecutor());
        TickWorkers.add(this);
    }

    /**
     * Runs on a worldgen worker thread; touches no chunks and no Waystones saved data.
     * Also builds the scan-unit list here — enumerating and sorting tens of thousands of
     * placement positions (including modded per-chunk placement checks) is itself too heavy
     * for the main thread.
     */
    private void runScan() {
        try {
            List<ScanUnit> units = buildScanUnits();
            scanUnits = units;
            totalScanUnits = units.size();
            LOGGER.info("Structure scan: {} placement positions to check nearest-first ({} templates cached)",
                    units.size(), WaystoneTemplates.cachedTemplateCount());
            for (ScanUnit unit : units) {
                if (scanAbandoned || finished) {
                    return;
                }
                processScanUnit(unit);
                scannedUnits++;
                if (!structureCandidates.isEmpty()) {
                    return; // units are distance-ordered: first hit is the nearest structure waystone
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Structure scan failed; continuing with wilderness candidates only", t);
        }
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
        // Stays alive after the search completes until ticket decay has cooled everything down.
        return !finished || !retainedTickets.isEmpty();
    }

    @Override
    public boolean doWork() {
        tickDecay();
        if (finished) {
            return false;
        }
        // Stage 1: the scan runs on a worker thread (see runScan); here we only poll its future
        // and keep the progress bar moving — near-zero main-thread cost.
        if (stage == Stage.SCAN) {
            if (!scanFuture.isDone()) {
                if (++scanWaitTicks % 20 == 0) {
                    onProgressPercent.accept(Math.min(59, scannedUnits * 60 / Math.max(1, totalScanUnits)));
                }
                if (scanWaitTicks > SCAN_TIMEOUT_TICKS) {
                    LOGGER.warn("Structure scan timed out after {}/{} units — continuing with wilderness candidates only",
                            scannedUnits, scanUnits.size());
                    scanAbandoned = true;
                    // structureCandidates may still be mid-write on the scan thread; don't read it.
                    candidates = assembleCandidates(List.of());
                    stage = Stage.GENERATE;
                    stageStartMs = System.currentTimeMillis();
                }
                return false;
            }
            boolean hit = !structureCandidates.isEmpty();
            candidates = assembleCandidates(structureCandidates);
            LOGGER.info("Structure scan {} in {} ms: {}/{} positions checked, {} starts assembled, "
                            + "{} waystone-piece chunks; {} total candidate chunks incl. wilderness grid",
                    hit ? "hit" : "exhausted", System.currentTimeMillis() - stageStartMs,
                    scannedUnits, scanUnits.size(), assembledStarts,
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

        if (stage == Stage.RESOLVE) {
            return doResolveWork();
        }

        // Stage 2: generate candidate chunks and detect the waystone.
        // Collect completed chunks: release their tickets and palette-scan them for waystone blocks.
        int completed = 0;
        for (int i = inFlight.size() - 1; i >= 0; i--) {
            Pending pending = inFlight.get(i);
            if (pending.future().isDone()) {
                // The ticket is NOT released here — it enters the decay queue so its apron
                // unloads gradually instead of as one forced-save burst.
                retainedTickets.add(new RetainedTicket(pending.pos(), TICKET_RADIUS));
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

        if (!draining && inFlight.size() < MAX_IN_FLIGHT && canIssueNow()) {
            // Issue the next candidate — one at a time, and only when the last tick had
            // headroom, because the chunk system schedules loads/saves for the whole apron
            // onto the main thread regardless of how we request the chunk. The ticket is
            // added before requesting the future so the holder never sits without level
            // support once the internal UNKNOWN ticket (1-tick expiry) lapses.
            ChunkPos next = nextPosition();
            if (next != null) {
                world.getChunkManager().addTicket(TICKET, next, TICKET_RADIUS, next);
                var future = world.getChunkManager()
                        .getChunkFutureSyncOnMainThread(next.x, next.z, ChunkStatus.FEATURES, true);
                inFlight.add(new Pending(next, future));
            }
        }

        if (inFlight.isEmpty()) {
            if (draining) {
                draining = false;
                // A hit or scan candidates exist — bind to the block asynchronously.
                // If enterResolve finds nothing after all, sweeping resumes next tick.
                enterResolve();
                return false;
            }
            if (candidateIndex >= candidates.size()) {
                // Nothing in flight and no candidates left: the whole radius has been swept.
                sweepExhausted = true;
                if (!enterResolve()) {
                    finished = true;
                    LOGGER.info("All {} candidates exhausted, no waystone found ({} ms total)",
                            candidates.size(), System.currentTimeMillis() - searchStartMs);
                    onExhausted.run();
                }
            }
            // else: waiting for tick headroom before issuing the next candidate.
            return false;
        }

        // One poll per tick is enough; the real work happens on the chunk system's threads.
        return false;
    }

    /**
     * Issue-pacing: true when the last tick had leftover headroom. On a server that never gets
     * under the threshold, force an issue every {@link #ISSUE_FORCE_TICKS} ticks so the search
     * still progresses.
     */
    private boolean canIssueNow() {
        if (TickWorkers.lastTickWorkMs() <= HEALTHY_TICK_MS || ++issueWaitTicks >= ISSUE_FORCE_TICKS) {
            issueWaitTicks = 0;
            return true;
        }
        return false;
    }

    /**
     * Gradually releases retained chunk tickets, one level-step on one ticket per call —
     * and only on ticks with headroom (unless too many tickets have piled up). Each step
     * raises the ticket's level (radius more negative), shedding an outer ring of apron
     * holders whose unload-saves land on the main thread; small rings keep vanilla's
     * forced-unload path from producing multi-second ticks.
     */
    private void tickDecay() {
        if (retainedTickets.isEmpty()) {
            return;
        }
        boolean healthy = TickWorkers.lastTickWorkMs() <= HEALTHY_TICK_MS;
        if (!healthy && retainedTickets.size() <= RETAINED_SOFT_CAP) {
            return;
        }
        RetainedTicket ticket = retainedTickets.poll();
        world.getChunkManager().removeTicket(TICKET, ticket.pos, ticket.radius, ticket.pos);
        ticket.radius -= TICKET_DECAY_STEP;
        int newLevel = ChunkLevels.getLevelFromType(ChunkLevelType.FULL) - ticket.radius;
        if (newLevel < ChunkLevels.INACCESSIBLE) {
            world.getChunkManager().addTicket(TICKET, ticket.pos, ticket.radius, ticket.pos);
            retainedTickets.add(ticket); // rotate: next call steps the next ticket
        }
        // else: fully released — the remaining holders drain within one small ring.
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

    /**
     * Prepares the RESOLVE stage: if the registry has a hit, its chunk gets fully loaded so the
     * result can be bound to the waystone the BLOCK actually holds (activating the registry
     * entry directly is unsafe for wilderness waystones — if their FEATURES-stage chunk never
     * reached disk, it regenerates on the player's arrival, the feature re-runs with a NEW
     * random UUID, and the player "unlocks" the block's waystone a second time). Otherwise
     * palette-scanned candidate positions are full-loaded so their block entities register.
     *
     * @return false when there is nothing to resolve (caller resumes sweeping or exhausts).
     */
    private boolean enterResolve() {
        resolveQueue.clear();
        Optional<Waystone> hit = findRegistered();
        if (hit.isPresent()) {
            chosenWaystone = hit.get();
            resolveQueue.add(chosenWaystone.getPos());
        } else if (!scanCandidates.isEmpty()) {
            scanCandidates.sort(Comparator.comparingLong(this::horizontalDistanceSq));
            resolveQueue.addAll(scanCandidates);
            scanCandidates.clear();
        } else {
            return false;
        }
        stage = Stage.RESOLVE;
        onProgressPercent.accept(99);
        return true;
    }

    /** Full-loads the queued chunks asynchronously (one at a time), then delivers the result. */
    private boolean doResolveWork() {
        if (resolvePending == null) {
            if (!resolveQueue.isEmpty() && !canIssueNow()) {
                return false; // wait for tick headroom before the next full-load
            }
            BlockPos next = resolveQueue.poll();
            if (next == null) {
                // Candidates failed to produce a registered waystone.
                if (sweepExhausted) {
                    finished = true;
                    LOGGER.info("All candidates exhausted, no waystone found ({} ms total)",
                            System.currentTimeMillis() - searchStartMs);
                    onExhausted.run();
                } else {
                    stage = Stage.GENERATE; // resume sweeping
                }
                return false;
            }
            ChunkPos chunkPos = new ChunkPos(next);
            world.getChunkManager().addTicket(TICKET, chunkPos, RESOLVE_TICKET_RADIUS, chunkPos);
            var future = world.getChunkManager()
                    .getChunkFutureSyncOnMainThread(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true);
            resolvePending = new Pending(chunkPos, future);
            resolveTargetPos = next;
            return false;
        }
        if (!resolvePending.future().isDone()) {
            if (++ticksWithoutProgress > STALL_TICKS) {
                LOGGER.warn("Resolve full-load of {} stalled; delivering best-known result", resolvePending.pos());
                finishStalled();
            }
            return false;
        }
        ticksWithoutProgress = 0;
        Pending done = resolvePending;
        resolvePending = null;
        resolveTargetPos = null;

        if (chosenWaystone != null) {
            // The chunk holding the chosen waystone is now fully loaded: bind to the block.
            Waystone bound = WaystonesAPI.getWaystoneAt(world, chosenWaystone.getPos())
                    .filter(Waystone::isValid)
                    .orElse(chosenWaystone);
            if (!bound.getWaystoneUid().equals(chosenWaystone.getWaystoneUid())) {
                LOGGER.warn("Registry waystone {} at {} does not match the block's waystone {} — "
                                + "activating the block's (its chunk was likely regenerated; the registry entry is orphaned)",
                        chosenWaystone.getWaystoneUid(), chosenWaystone.getPos().toShortString(), bound.getWaystoneUid());
            }
            finished = true;
            logFound(bound);
            onFound.accept(bound);
        } else {
            // A palette-scanned candidate chunk finished loading — its block entity should have
            // registered the waystone by now. If so, bind to it; its chunk is already loaded.
            Optional<Waystone> hit = findRegistered();
            if (hit.isPresent()) {
                chosenWaystone = hit.get();
                resolveQueue.clear();
                resolveQueue.add(chosenWaystone.getPos());
            }
            // else: try the next queued candidate (or resume/exhaust when the queue runs dry).
        }
        // Decays instead of instant release — a FULL-level ticket holds the largest apron of all.
        retainedTickets.add(new RetainedTicket(done.pos(), RESOLVE_TICKET_RADIUS));
        return false;
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
            retainedTickets.add(new RetainedTicket(pending.pos(), TICKET_RADIUS));
        }
        inFlight.clear();
        if (resolvePending != null) {
            retainedTickets.add(new RetainedTicket(resolvePending.pos(), RESOLVE_TICKET_RADIUS));
            resolvePending = null;
        }
        Optional<Waystone> found = findRegistered();
        if (found.isPresent()) {
            onFound.accept(found.get());
        } else {
            onExhausted.run();
        }
    }

    /** Next candidate chunk in nearest-first order, or null when all candidates are issued. */
    private ChunkPos nextPosition() {
        return candidateIndex < candidates.size() ? candidates.get(candidateIndex++) : null;
    }

    /**
     * Heuristic pre-filter: samples the biome at the position (surface-ish and underground Y)
     * against the structure's valid-biome list — pure noise math, far cheaper than the anchor
     * computation inside createStructureStart. A rare false negative here just means the
     * wilderness grid answers instead.
     */
    private boolean biomePlausible(Structure structure, ChunkPos pos) {
        var validBiomes = structure.getValidBiomes();
        var biomeSource = world.getChunkManager().getChunkGenerator().getBiomeSource();
        var sampler = world.getChunkManager().getNoiseConfig().getMultiNoiseSampler();
        int bx = BiomeCoords.fromBlock(pos.getStartX() + 8);
        int bz = BiomeCoords.fromBlock(pos.getStartZ() + 8);
        return validBiomes.contains(biomeSource.getBiome(bx, BiomeCoords.fromBlock(96), bz, sampler))
                || validBiomes.contains(biomeSource.getBiome(bx, BiomeCoords.fromBlock(-32), bz, sampler));
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
            List<BlockBox> pieceBoxes = WaystoneTemplates.cachedStartBoxes(startKey);
            if (pieceBoxes == null) {
                // Cheap biome plausibility check first: createStructureStart computes its anchor
                // position (a noise column sample, ~1ms) BEFORE validating the biome, which is
                // the wrong order for a mass scan. Two quick biome samples reject most units.
                if (!biomePlausible(structure, unit.pos())) {
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
                pieceBoxes = waystoneTemplates.waystonePieceBoxes(start);
                WaystoneTemplates.storeStartBoxes(startKey, pieceBoxes);
            }
            long reach = (long) radiusBlocks + 12;
            for (BlockBox box : pieceBoxes) {
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
    private List<ChunkPos> assembleCandidates(List<Candidate> structureHits) {
        List<Candidate> sorted = new ArrayList<>(structureHits);
        sorted.sort(Comparator.comparingLong(Candidate::distSq));
        LinkedHashSet<ChunkPos> ordered = new LinkedHashSet<>();
        for (Candidate candidate : sorted) {
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
