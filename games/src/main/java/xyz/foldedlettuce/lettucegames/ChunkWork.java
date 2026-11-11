package xyz.foldedlettuce.lettucegames;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Chunk loading that never blocks the server thread.
 * Chunks are requested a few per tick and generated off the main thread.
 */
public final class ChunkWork {
    private ChunkWork() {}

    public static class Job {
        public int done;
        public int total;
        public boolean finished;
        BukkitTask task;

        public int percent() {
            return total == 0 ? 100 : (int) (done * 100L / total);
        }

        public void cancel() {
            if (task != null) task.cancel();
        }
    }

    /** Loads (generating if needed) every chunk in the list, perTick requests per tick, then runs done on the main thread. */
    public static Job load(LettuceGames plugin, World w, List<int[]> chunks, int perTick, Runnable done) {
        return load(plugin, w, chunks, perTick, 1, done);
    }

    /** Same, but only sends requests every periodTicks ticks (slower and lighter). */
    public static Job load(LettuceGames plugin, World w, List<int[]> chunks, int perTick, int periodTicks, Runnable done) {
        Job job = new Job();
        job.total = chunks.size();
        if (chunks.isEmpty()) {
            job.finished = true;
            Bukkit.getScheduler().runTask(plugin, done);
            return job;
        }
        int[] next = {0};
        job.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (int k = 0; k < perTick && next[0] < chunks.size(); k++) {
                int[] c = chunks.get(next[0]++);
                w.getChunkAtAsync(c[0], c[1]).whenComplete((chunk, err) -> {
                    job.done++;
                    if (job.done >= job.total && !job.finished) {
                        job.finished = true;
                        Bukkit.getScheduler().runTask(plugin, done);
                    }
                });
            }
            if (next[0] >= chunks.size()) job.task.cancel();
        }, 0L, Math.max(1, periodTicks));
        return job;
    }

    /** Square of chunks around a block position, closest first. */
    public static List<int[]> square(Location center, int radiusChunks) {
        int cx = center.getBlockX() >> 4, cz = center.getBlockZ() >> 4;
        List<int[]> chunks = new ArrayList<>();
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) chunks.add(new int[]{cx + dx, cz + dz});
        }
        chunks.sort((a, b) -> Integer.compare(Math.abs(a[0] - cx) + Math.abs(a[1] - cz), Math.abs(b[0] - cx) + Math.abs(b[1] - cz)));
        return chunks;
    }

    // ------------------------------------------------------------------ land search

    private static final int[] RING_DISTS = {0, 10, 24};

    private static List<int[]> sampleChunks(int x, int z) {
        Set<Long> seen = new LinkedHashSet<>();
        List<int[]> out = new ArrayList<>();
        for (int d : RING_DISTS) {
            int steps = d == 0 ? 1 : 8;
            for (int k = 0; k < steps; k++) {
                double a = (Math.PI * 2 / steps) * k;
                int bx = x + (int) Math.round(Math.cos(a) * d);
                int bz = z + (int) Math.round(Math.sin(a) * d);
                int cx = bx >> 4, cz = bz >> 4;
                if (seen.add(((long) cx << 32) ^ (cz & 0xffffffffL))) out.add(new int[]{cx, cz});
            }
        }
        return out;
    }

    /**
     * Spirals out from 0,0 looking for solid dry land (see Match.dryLand), loading chunks in the background.
     * Calls result with the spawn spot, or null if nothing turned up.
     */
    public static void findLand(LettuceGames plugin, World w, Consumer<Location> result) {
        findLandNear(plugin, w, 0, 0, 2500, 64, 300, result);
    }

    /** Same search, spiralling out from ox,oz instead of 0,0. */
    public static void findLandNear(LettuceGames plugin, World w, int ox, int oz, int maxRadius, int step, int maxCandidates,
                                    Consumer<Location> result) {
        List<int[]> candidates = new ArrayList<>();
        for (int r = 0; r <= maxRadius && candidates.size() < maxCandidates; r += step) {
            int steps = r == 0 ? 1 : Math.max(8, r / Math.max(8, step * 3 / 4));
            double offset = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            for (int k = 0; k < steps; k++) {
                double a = offset + (Math.PI * 2 / steps) * k;
                candidates.add(new int[]{ox + (int) Math.round(Math.cos(a) * r), oz + (int) Math.round(Math.sin(a) * r)});
            }
        }
        tryCandidate(plugin, w, candidates, 0, result);
    }

    private static void tryCandidate(LettuceGames plugin, World w, List<int[]> cands, int i, Consumer<Location> result) {
        if (i >= cands.size()) {
            result.accept(null);
            return;
        }
        int x = cands.get(i)[0], z = cands.get(i)[1];
        // quick check on just the middle chunk first, most ocean spots fail right here
        List<int[]> first = new ArrayList<>();
        first.add(new int[]{x >> 4, z >> 4});
        load(plugin, w, first, 1, () -> {
            Block b = w.getHighestBlockAt(x, z);
            if (b.isLiquid() || !Match.okBiome(w, x, z)) {
                tryCandidate(plugin, w, cands, i + 1, result);
                return;
            }
            load(plugin, w, sampleChunks(x, z), 4, () -> {
                if (Match.dryLand(w, x, z)) {
                    Block top = w.getHighestBlockAt(x, z);
                    result.accept(new Location(w, x + 0.5, top.getY() + 1, z + 0.5));
                } else {
                    tryCandidate(plugin, w, cands, i + 1, result);
                }
            });
        });
    }
}
