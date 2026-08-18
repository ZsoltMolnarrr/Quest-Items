package net.quest_items.locator;

import net.blay09.mods.waystones.block.WaystoneBlock;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.PoolStructurePiece;
import net.minecraft.structure.SimpleStructurePiece;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureStart;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.structure.pool.SinglePoolElement;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.quest_items.mixin.SimpleStructurePieceAccessor;
import net.quest_items.mixin.SinglePoolElementAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers "does this structure piece contain a waystone?" from data alone — no terrain.
 *
 * A template-based piece names its structure template, and the template's NBT palette says
 * whether it contains a waystone block. Any structure that hosts a waystone — Waystones' own
 * village piece, or a third-party structure with a waystone embedded in its template — is
 * detected automatically, with no curated list of structure types to maintain.
 *
 * Covers jigsaw pieces ({@link PoolStructurePiece} with single-template elements) and plain
 * template pieces ({@link SimpleStructurePiece}). Pieces built procedurally in code can't be
 * inspected — but those don't place waystones either. Template verdicts are cached statically
 * per server run.
 */
class WaystoneTemplates {

    private static final StructurePlacementData PLACEMENT_DATA = new StructurePlacementData();

    // Concurrent: the scan runs on a worldgen worker thread while the main thread may also
    // inspect pieces during candidate resolution.
    private static final Map<Identifier, Boolean> TEMPLATE_CACHE = new ConcurrentHashMap<>();
    /** Waystone-piece boxes of assembled structure starts, keyed "structureId@chunkLong". */
    private static final Map<String, List<BlockBox>> START_CACHE = new ConcurrentHashMap<>();
    private static Object cacheOwner;

    private final StructureTemplateManager templateManager;
    private final List<Block> waystoneBlocks;

    WaystoneTemplates(ServerWorld world) {
        this.templateManager = world.getStructureTemplateManager();
        if (cacheOwner != world.getServer()) {
            TEMPLATE_CACHE.clear();
            START_CACHE.clear();
            cacheOwner = world.getServer();
        }
        List<Block> blocks = new ArrayList<>();
        for (Block block : Registries.BLOCK) {
            if (block instanceof WaystoneBlock) {
                blocks.add(block);
            }
        }
        this.waystoneBlocks = blocks;
    }

    static int cachedTemplateCount() {
        return TEMPLATE_CACHE.size();
    }

    /** Cached piece boxes for an assembled start, or null if this start was never assembled. */
    static List<BlockBox> cachedStartBoxes(String startKey) {
        return START_CACHE.get(startKey);
    }

    /** Remember an assembled start's waystone pieces so later searches skip its assembly. */
    static void storeStartBoxes(String startKey, List<BlockBox> boxes) {
        START_CACHE.put(startKey, boxes);
    }

    /** Bounding boxes of this structure start's pieces whose template contains a waystone block. */
    List<BlockBox> waystonePieceBoxes(StructureStart start) {
        List<BlockBox> result = new ArrayList<>();
        for (StructurePiece piece : start.getChildren()) {
            if (pieceContainsWaystone(piece)) {
                result.add(piece.getBoundingBox());
            }
        }
        return result;
    }

    private boolean pieceContainsWaystone(StructurePiece piece) {
        try {
            if (piece instanceof PoolStructurePiece poolPiece
                    && poolPiece.getPoolElement() instanceof SinglePoolElement singleElement) {
                return ((SinglePoolElementAccessor) singleElement).questItems$getLocation()
                        .map(this::templateContainsWaystone, this::containsWaystone);
            }
            if (piece instanceof SimpleStructurePiece simplePiece) {
                StructureTemplate template = ((SimpleStructurePieceAccessor) simplePiece).questItems$getTemplate();
                return template != null && containsWaystone(template);
            }
        } catch (Throwable ignored) {
            // A piece we cannot inspect is treated as waystone-free.
        }
        return false;
    }

    private boolean templateContainsWaystone(Identifier templateId) {
        Boolean cached = TEMPLATE_CACHE.get(templateId);
        if (cached != null) {
            return cached;
        }
        boolean verdict;
        try {
            verdict = templateManager.getTemplate(templateId).map(this::containsWaystone).orElse(false);
        } catch (Throwable ignored) {
            verdict = false;
        }
        TEMPLATE_CACHE.put(templateId, verdict);
        return verdict;
    }

    private boolean containsWaystone(StructureTemplate template) {
        for (Block block : waystoneBlocks) {
            if (!template.getInfosForBlock(BlockPos.ORIGIN, PLACEMENT_DATA, block).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
