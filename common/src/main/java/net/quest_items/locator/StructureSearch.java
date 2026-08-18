package net.quest_items.locator;

import com.mojang.datafixers.util.Pair;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.StructurePresence;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.chunk.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.chunk.placement.StructurePlacement;
import net.minecraft.world.gen.structure.Structure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Non-blocking nearest-structure search, adapted from Explorer's Compass.
 *
 * Structures are searched by iterating their placement grid (not by loading chunks): for each
 * candidate grid position only structure-start metadata is computed (ChunkStatus.STRUCTURE_STARTS),
 * which is cheap compared to actual generation. Workers run inside the {@link TickWorkers} budget.
 *
 * One worker is created per distinct StructurePlacement; workers run sequentially — if the first
 * placement yields nothing within the radius, the next one is started.
 */
public final class StructureSearch {

    private static final int MAX_SAMPLES = 100_000;

    private final List<SearchWorker<?>> pending = new ArrayList<>();
    private final BiConsumer<BlockPos, Structure> onFound;
    private final Runnable onExhausted;

    private StructureSearch(BiConsumer<BlockPos, Structure> onFound, Runnable onExhausted) {
        this.onFound = onFound;
        this.onExhausted = onExhausted;
    }

    /**
     * Starts a search. Callbacks fire on the server thread, possibly synchronously
     * (e.g. when the structure cannot generate in this dimension at all).
     */
    public static void start(ServerWorld world, BlockPos origin, List<RegistryEntry<Structure>> structures,
                             int maxRadiusBlocks, BiConsumer<BlockPos, Structure> onFound, Runnable onExhausted) {
        var search = new StructureSearch(onFound, onExhausted);
        var calculator = world.getChunkManager().getStructurePlacementCalculator();

        Map<StructurePlacement, List<Structure>> byPlacement = new LinkedHashMap<>();
        for (RegistryEntry<Structure> entry : structures) {
            for (StructurePlacement placement : calculator.getPlacements(entry)) {
                byPlacement.computeIfAbsent(placement, p -> new ArrayList<>()).add(entry.value());
            }
        }

        for (Map.Entry<StructurePlacement, List<Structure>> entry : byPlacement.entrySet()) {
            StructurePlacement placement = entry.getKey();
            List<Structure> placementStructures = entry.getValue();
            if (placement instanceof ConcentricRingsStructurePlacement concentric) {
                search.pending.add(new ConcentricRingsWorker(search, world, origin, maxRadiusBlocks, concentric, placementStructures));
            } else if (placement instanceof RandomSpreadStructurePlacement randomSpread) {
                search.pending.add(new RandomSpreadWorker(search, world, origin, maxRadiusBlocks, randomSpread, placementStructures));
            } else {
                search.pending.add(new GenericWorker(search, world, origin, maxRadiusBlocks, placement, placementStructures));
            }
        }

        search.startNext();
    }

    private void startNext() {
        if (pending.isEmpty()) {
            onExhausted.run();
        } else {
            TickWorkers.add(pending.get(0));
        }
    }

    private void workerSucceeded(BlockPos pos, Structure structure) {
        onFound.accept(pos, structure);
    }

    private void workerExhausted(SearchWorker<?> worker) {
        pending.remove(worker);
        startNext();
    }

    private static abstract class SearchWorker<T extends StructurePlacement> implements TickWorkers.Worker {
        protected final StructureSearch search;
        protected final ServerWorld world;
        protected final BlockPos startPos;
        protected final int maxRadius;
        protected final T placement;
        protected final List<Structure> structures;
        protected BlockPos currentPos;
        protected int samples;
        protected boolean finished;

        protected SearchWorker(StructureSearch search, ServerWorld world, BlockPos startPos, int maxRadius,
                               T placement, List<Structure> structures) {
            this.search = search;
            this.world = world;
            this.startPos = startPos;
            this.maxRadius = maxRadius;
            this.placement = placement;
            this.structures = structures;
            this.currentPos = startPos;
            this.finished = !world.getServer().getSaveProperties().getGeneratorOptions().shouldGenerateStructures();
        }

        @Override
        public boolean hasWork() {
            return !finished && getRadius() < maxRadius && samples < MAX_SAMPLES;
        }

        protected Pair<BlockPos, Structure> getStructureGeneratingAt(ChunkPos chunkPos) {
            for (Structure structure : structures) {
                StructurePresence result = world.getStructureAccessor().getStructurePresence(chunkPos, structure, placement, false);
                if (result != StructurePresence.START_NOT_PRESENT) {
                    if (result == StructurePresence.START_PRESENT) {
                        return Pair.of(placement.getLocatePos(chunkPos), structure);
                    }

                    Chunk chunk = world.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.STRUCTURE_STARTS);
                    StructureStart structureStart = world.getStructureAccessor().getStructureStart(ChunkSectionPos.from(chunk), structure, chunk);
                    if (structureStart != null && structureStart.hasChildren()) {
                        return Pair.of(placement.getLocatePos(structureStart.getPos()), structure);
                    }
                }
            }

            return null;
        }

        protected void succeed(BlockPos pos, Structure structure) {
            finished = true;
            search.workerSucceeded(pos, structure);
        }

        protected void exhaust() {
            finished = true;
            search.workerExhausted(this);
        }

        protected int getRadius() {
            int dx = startPos.getX() - currentPos.getX();
            int dz = startPos.getZ() - currentPos.getZ();
            return (int) Math.sqrt((double) dx * dx + (double) dz * dz);
        }
    }

    /** Spiral over the random-spread placement grid (the placement type of nearly all structures). */
    private static class RandomSpreadWorker extends SearchWorker<RandomSpreadStructurePlacement> {
        private final int spacing;
        private final int startSectionPosX;
        private final int startSectionPosZ;
        private int x;
        private int z;
        private int length;

        RandomSpreadWorker(StructureSearch search, ServerWorld world, BlockPos startPos, int maxRadius,
                           RandomSpreadStructurePlacement placement, List<Structure> structures) {
            super(search, world, startPos, maxRadius, placement, structures);
            spacing = placement.getSpacing();
            startSectionPosX = ChunkSectionPos.getSectionCoord(startPos.getX());
            startSectionPosZ = ChunkSectionPos.getSectionCoord(startPos.getZ());
            x = 0;
            z = 0;
            length = 0;
        }

        @Override
        public boolean doWork() {
            if (hasWork()) {
                boolean shouldSampleX = x == -length || x == length;
                boolean shouldSampleZ = z == -length || z == length;

                if (shouldSampleX || shouldSampleZ) {
                    int sampleX = startSectionPosX + (spacing * x);
                    int sampleZ = startSectionPosZ + (spacing * z);

                    ChunkPos chunkPos = placement.getStartChunk(world.getSeed(), sampleX, sampleZ);
                    currentPos = new BlockPos(ChunkSectionPos.getOffsetPos(chunkPos.x, 8), 0, ChunkSectionPos.getOffsetPos(chunkPos.z, 8));

                    Pair<BlockPos, Structure> pair = getStructureGeneratingAt(chunkPos);
                    samples++;
                    if (pair != null) {
                        succeed(pair.getFirst(), pair.getSecond());
                        return false;
                    }
                }

                z++;
                if (z > length) {
                    x++;
                    if (x > length) {
                        length++;
                        x = -length;
                        z = -length;
                    } else {
                        z = -length;
                    }
                }
            }

            if (hasWork()) {
                return true;
            }

            if (!finished) {
                exhaust();
            }

            return false;
        }
    }

    /** Checks the precomputed placement positions (strongholds). */
    private static class ConcentricRingsWorker extends SearchWorker<ConcentricRingsStructurePlacement> {
        private final List<ChunkPos> potentialChunks;
        private int chunkIndex;
        private double minDistance;
        private Pair<BlockPos, Structure> closest;

        ConcentricRingsWorker(StructureSearch search, ServerWorld world, BlockPos startPos, int maxRadius,
                              ConcentricRingsStructurePlacement placement, List<Structure> structures) {
            super(search, world, startPos, maxRadius, placement, structures);
            minDistance = Double.MAX_VALUE;
            chunkIndex = 0;
            potentialChunks = world.getChunkManager().getStructurePlacementCalculator().getPlacementPositions(placement);
            if (potentialChunks == null || potentialChunks.isEmpty()) {
                finished = true;
            }
        }

        @Override
        public boolean hasWork() {
            // Placement positions are not ordered nearest-first, so the radius is not a stop condition here.
            return !finished && samples < MAX_SAMPLES && chunkIndex < potentialChunks.size();
        }

        @Override
        public boolean doWork() {
            if (hasWork()) {
                ChunkPos chunkPos = potentialChunks.get(chunkIndex);
                currentPos = new BlockPos(ChunkSectionPos.getOffsetPos(chunkPos.x, 8), 0, ChunkSectionPos.getOffsetPos(chunkPos.z, 8));
                double distance = startPos.getSquaredDistance(currentPos);

                if (closest == null || distance < minDistance) {
                    Pair<BlockPos, Structure> pair = getStructureGeneratingAt(chunkPos);
                    if (pair != null) {
                        minDistance = distance;
                        closest = pair;
                    }
                }

                samples++;
                chunkIndex++;
            }

            if (hasWork()) {
                return true;
            }

            if (closest != null && getRadiusTo(closest.getFirst()) <= maxRadius) {
                finished = true;
                succeed(closest.getFirst(), closest.getSecond());
            } else if (!finished) {
                exhaust();
            }

            return false;
        }

        private int getRadiusTo(BlockPos pos) {
            int dx = startPos.getX() - pos.getX();
            int dz = startPos.getZ() - pos.getZ();
            return (int) Math.sqrt((double) dx * dx + (double) dz * dz);
        }
    }

    /** Chunk-by-chunk spiral for any other placement type. */
    private static class GenericWorker extends SearchWorker<StructurePlacement> {
        private int chunkX;
        private int chunkZ;
        private int length;
        private double nextLength;
        private Direction direction;

        GenericWorker(StructureSearch search, ServerWorld world, BlockPos startPos, int maxRadius,
                      StructurePlacement placement, List<Structure> structures) {
            super(search, world, startPos, maxRadius, placement, structures);
            chunkX = startPos.getX() >> 4;
            chunkZ = startPos.getZ() >> 4;
            nextLength = 1;
            length = 0;
            direction = Direction.UP;
        }

        @Override
        public boolean doWork() {
            if (hasWork()) {
                if (direction == Direction.NORTH) {
                    chunkZ--;
                } else if (direction == Direction.EAST) {
                    chunkX++;
                } else if (direction == Direction.SOUTH) {
                    chunkZ++;
                } else if (direction == Direction.WEST) {
                    chunkX--;
                }

                ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
                currentPos = new BlockPos(ChunkSectionPos.getOffsetPos(chunkPos.x, 8), 0, ChunkSectionPos.getOffsetPos(chunkPos.z, 8));

                Pair<BlockPos, Structure> pair = getStructureGeneratingAt(chunkPos);
                samples++;
                if (pair != null) {
                    succeed(pair.getFirst(), pair.getSecond());
                    return false;
                }

                length++;
                if (length >= (int) nextLength) {
                    if (direction != Direction.UP) {
                        nextLength += 0.5;
                        direction = direction.rotateYClockwise();
                    } else {
                        direction = Direction.NORTH;
                    }
                    length = 0;
                }
            }

            if (hasWork()) {
                return true;
            }

            if (!finished) {
                exhaust();
            }

            return false;
        }
    }
}
