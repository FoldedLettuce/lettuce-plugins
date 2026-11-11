package xyz.foldedlettuce.lettucegames;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Keeps a couple of maps generated and ready so games start fast.
 * Each map is used once and deleted after the game. A replacement starts generating the moment one is taken.
 * Ready maps survive restarts (listed in maps.yml).
 */
public class MapPool {
    public static class Ready {
        public final World world;
        public final Location center;

        Ready(World world, Location center) {
            this.world = world;
            this.center = center;
        }
    }

    private final LettuceGames plugin;
    private final File file;
    private final List<Ready> ready = new ArrayList<>();
    private boolean generating = false;

    public MapPool(LettuceGames plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "maps.yml");
    }

    public int target() {
        return Math.max(0, plugin.getConfig().getInt("ready-maps", 2));
    }

    public int readyCount() {
        return ready.size();
    }

    public boolean isGenerating() {
        return generating;
    }

    /** World folder names listed in maps.yml, so startup cleanup leaves them alone. */
    public Set<String> savedNames() {
        Set<String> out = new HashSet<>();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("ready");
        if (sec != null) for (String n : sec.getKeys(false)) if (sec.getInt(n + ".v", 1) >= 2) out.add(n);
        ConfigurationSection pen = y.getConfigurationSection("pending");
        if (pen != null) out.addAll(pen.getKeys(false));
        return out;
    }

    /**
     * Loads the ready maps from before the last restart, one every couple seconds so startup isn't one long freeze.
     * Same seed means same terrain, so even if a chunk didn't get saved it regenerates identical.
     */
    public void loadSaved() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("ready");
        List<String> names = sec == null ? new ArrayList<>() : new ArrayList<>(sec.getKeys(false));
        if (sec == null) sec = y.createSection("ready");
        loading = true;
        loadNext(sec, names, 0);
    }

    private boolean loading = false;

    private void loadNext(ConfigurationSection sec, List<String> names, int i) {
        if (i >= names.size()) {
            loading = false;
            plugin.getLogger().info("Ready maps loaded: " + ready.size() + "/" + target());
            // a map that was mid-generation when the server stopped picks up where it left off
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection pen = y.getConfigurationSection("pending");
            String resume = null;
            if (pen != null) {
                for (String n : pen.getKeys(false)) {
                    String path = pen.getString(n + ".path", "");
                    if (!path.isEmpty() && new File(path).isDirectory() && resume == null && ready.size() < target()) resume = n;
                    else if (!path.isEmpty()) plugin.deleteFolder(new File(path));
                }
            }
            save();
            if (resume != null) {
                World w = Bukkit.createWorld(new WorldCreator(resume));
                if (w != null) {
                    plugin.getLogger().info("Resuming map " + resume);
                    generating = true;
                    prepare(w);
                    return;
                }
            }
            fillLater(100L);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            String name = names.get(i);
            String path = sec.getString(name + ".path", "");
            boolean exists = !path.isEmpty() && new File(path).isDirectory();
            if (sec.getInt(name + ".v", 1) < 2 || !exists) {
                plugin.getLogger().info("Skipping old/missing ready map " + name);
                if (!path.isEmpty()) plugin.deleteFolder(new File(path));
            } else {
                World w = Bukkit.createWorld(new WorldCreator(name));
                if (w != null) {
                    w.setAutoSave(true);
                    Location c = new Location(w, sec.getDouble(name + ".x"), sec.getDouble(name + ".y"), sec.getDouble(name + ".z"));
                    ready.add(new Ready(w, c));
                    holdSpawn(w, c);
                    plugin.getLogger().info("Loaded ready map " + name);
                }
            }
            loadNext(sec, names, i + 1);
        }, 40L);
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Ready r : ready) {
            String n = r.world.getName();
            y.set("ready." + n + ".x", r.center.getX());
            y.set("ready." + n + ".y", r.center.getY());
            y.set("ready." + n + ".z", r.center.getZ());
            y.set("ready." + n + ".path", r.world.getWorldFolder().getAbsolutePath());
            y.set("ready." + n + ".v", 2);
        }
        // a map that's mid-generation is listed too, so a restart doesn't throw it away
        if (pending != null) {
            String n = pending.getName();
            y.set("pending." + n + ".path", pending.getWorldFolder().getAbsolutePath());
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save maps.yml: " + e.getMessage());
        }
    }

    private World pending;

    /** Hands out a ready map, or null if none are ready. It's never handed out again. */
    public Ready take() {
        if (ready.isEmpty()) return null;
        Ready r = ready.remove(0);
        save();
        // start making the next one right away
        fillLater(20L);
        return r;
    }

    public void fillLater(long ticks) {
        Bukkit.getScheduler().runTaskLater(plugin, this::fill, ticks);
    }

    /** Makes one new map if we're short. All the chunk work happens off the main thread. */
    public void fill() {
        if (loading || generating || ready.size() >= target()) return;
        generating = true;

        String name = plugin.worldPrefix() + "map_" + Long.toString(System.currentTimeMillis() % 1_000_000_000L, 36);
        WorldCreator wc = new WorldCreator(name);
        wc.seed(ThreadLocalRandom.current().nextLong());
        wc.environment(World.Environment.NORMAL);
        World w = Bukkit.createWorld(wc);
        if (w == null) {
            generating = false;
            plugin.getLogger().warning("Couldn't create a ready map.");
            return;
        }
        prepare(w);
    }

    /** Finds a spawn and pre-generates around it in the background, then marks the map ready. */
    private void prepare(World w) {
        String name = w.getName();
        w.setAutoSave(true);
        pending = w;
        save();
        ChunkWork.findLand(plugin, w, center -> {
            if (center == null) {
                // no land anywhere near, toss this seed and try another
                plugin.getLogger().info("Map " + name + " was all ocean, trying another seed");
                File f = w.getWorldFolder();
                Bukkit.unloadWorld(w, false);
                plugin.deleteFolder(f);
                pending = null;
                generating = false;
                fillLater(40L);
                return;
            }
            w.setSpawnLocation(center);
            int r = Math.max(2, plugin.getConfig().getInt("ready-map-preload-blocks", 96) / 16);
            // gentle: a game is usually running while this happens
            int perTick = Math.max(1, plugin.getConfig().getInt("ready-map-chunks-per-tick", 1));
            int period = plugin.activeMatches() > 0 ? 2 : 1;
            ChunkWork.load(plugin, w, ChunkWork.square(center, r), perTick, period, () -> finish(w, center));
        });
    }

    /** Keeps the spawn area of a waiting map fully loaded so games can teleport in instantly. */
    private void holdSpawn(World w, Location center) {
        int cx = center.getBlockX() >> 4, cz = center.getBlockZ() >> 4;
        int r = Math.max(1, plugin.getConfig().getInt("ready-map-hold-chunks", 3));
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) w.addPluginChunkTicket(cx + dx, cz + dz, plugin);
    }

    private void finish(World w, Location center) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            holdSpawn(w, center);
            pending = null;
            ready.add(new Ready(w, center));
            save();
            generating = false;
            plugin.getLogger().info("Map " + w.getName() + " is ready (" + ready.size() + "/" + target() + ")");
            fillLater(40L);
        });
    }
}
