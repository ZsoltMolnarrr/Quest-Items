package net.quest_items.locator;

import net.blay09.mods.waystones.api.Waystone;
import net.blay09.mods.waystones.api.WaystoneTypes;
import net.blay09.mods.waystones.api.WaystonesAPI;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Finds the nearest Waystone to a center position, within a block radius.
 *
 * Waystones live in a global saved-data registry (WaystoneManager), but only get registered
 * once the chunk containing them has been generated (wilderness waystones register during
 * feature placement, village waystones when their chunk first loads). So the search is:
 *
 * 1. Query the registry — if a known waystone is already within radius, done, no chunks touched.
 * 2. Otherwise generate chunks in expanding square rings around the center (one chunk per
 *    {@link TickWorkers} work unit, so generation is spread across ticks), re-querying the
 *    registry after each completed ring. First hit wins (ring order ≈ nearest first).
 */
public class WaystoneSearch implements TickWorkers.Worker {

    private final ServerWorld world;
    private final BlockPos center;
    private final int radiusBlocks;
    private final int maxRing;
    private final ChunkPos centerChunk;
    private final Consumer<Waystone> onFound;
    private final Runnable onExhausted;
    private final Consumer<Integer> onProgressPercent;

    private int ring = 0;
    private int ringIndex = 0;
    private List<ChunkPos> ringPositions;
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
        Optional<Waystone> existing = findRegistered();
        if (existing.isPresent()) {
            finished = true;
            onFound.accept(existing.get());
            return;
        }
        ringPositions = positionsOfRing(0);
        TickWorkers.add(this);
    }

    @Override
    public boolean hasWork() {
        return !finished;
    }

    @Override
    public boolean doWork() {
        if (!hasWork()) {
            return false;
        }

        if (ringIndex < ringPositions.size()) {
            ChunkPos pos = ringPositions.get(ringIndex++);
            // Generating to FULL status registers wilderness waystones (feature placement)
            // and village waystones (block entity load) into the global registry.
            world.getChunk(pos.x, pos.z, ChunkStatus.FULL, true);
            return hasWork();
        }

        // Ring completed: check whether anything new got registered.
        Optional<Waystone> found = findRegistered();
        if (found.isPresent()) {
            finished = true;
            onFound.accept(found.get());
            return false;
        }

        ring++;
        if (ring > maxRing) {
            finished = true;
            onExhausted.run();
            return false;
        }

        onProgressPercent.accept(Math.min(99, ring * 100 / maxRing));
        ringIndex = 0;
        ringPositions = positionsOfRing(ring);
        return hasWork();
    }

    private Optional<Waystone> findRegistered() {
        long radiusSq = (long) radiusBlocks * radiusBlocks;
        return WaystonesAPI.getWaystonesByType(world.getServer(), WaystoneTypes.WAYSTONE)
                .filter(waystone -> waystone.getDimension() == world.getRegistryKey())
                .filter(waystone -> horizontalDistanceSq(waystone.getPos()) <= radiusSq)
                .min(Comparator.comparingLong(waystone -> horizontalDistanceSq(waystone.getPos())));
    }

    private long horizontalDistanceSq(BlockPos pos) {
        long dx = pos.getX() - center.getX();
        long dz = pos.getZ() - center.getZ();
        return dx * dx + dz * dz;
    }

    /** Chunks at Chebyshev distance {@code ring} from the center chunk, clipped to the block radius. */
    private List<ChunkPos> positionsOfRing(int ring) {
        List<ChunkPos> positions = new ArrayList<>();
        if (ring == 0) {
            positions.add(centerChunk);
            return positions;
        }
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                    continue;
                }
                ChunkPos pos = new ChunkPos(centerChunk.x + dx, centerChunk.z + dz);
                // Skip ring corners that lie entirely outside the block radius
                // (a chunk counts if any of its blocks could be within reach: center + ~12 blocks).
                long cx = pos.getStartX() + 8 - center.getX();
                long cz = pos.getStartZ() + 8 - center.getZ();
                long reach = (long) radiusBlocks + 12;
                if (cx * cx + cz * cz <= reach * reach) {
                    positions.add(pos);
                }
            }
        }
        return positions;
    }
}
