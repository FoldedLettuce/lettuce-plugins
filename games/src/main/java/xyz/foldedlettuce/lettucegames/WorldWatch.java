package xyz.foldedlettuce.lettucegames;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Leak detector. Remembers every unloaded world through a weak reference, which doesn't keep it alive.
 * If the reference is still set after a full garbage collection, something else is holding that world.
 */
public final class WorldWatch implements Listener {
    private static final long SETTLE_MS = 60_000L;

    private static final class Gone {
        final String name;
        final WeakReference<World> ref;
        final long at = System.currentTimeMillis();

        Gone(World w) {
            name = w.getName();
            ref = new WeakReference<>(w);
        }
    }

    private final LettuceGames plugin;
    private final List<Gone> gone = new ArrayList<>();
    private int lastWarned = 0;

    public WorldWatch(LettuceGames plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // every 10 minutes, mention it in the log if the number of lingering worlds went up
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int n = lingering(30 * 60_000L).size();
            if (n > lastWarned) {
                plugin.getLogger().warning(n + " world(s) unloaded 30+ minutes ago are still in memory."
                        + " Run /lg memory gc to check if they're really stuck.");
            }
            lastWarned = n;
        }, 12000L, 12000L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUnload(WorldUnloadEvent e) {
        gone.add(new Gone(e.getWorld()));
    }

    /** Worlds unloaded at least minAgeMs ago that Java hasn't freed. */
    private List<Gone> lingering(long minAgeMs) {
        gone.removeIf(g -> g.ref.get() == null);
        long now = System.currentTimeMillis();
        List<Gone> out = new ArrayList<>();
        for (Gone g : gone) {
            World w = g.ref.get();
            // the unload can still fail after the event, so skip anything that's actually still loaded
            if (w != null && now - g.at >= minAgeMs && !Bukkit.getWorlds().contains(w)) out.add(g);
        }
        return out;
    }

    public void report(CommandSender s, boolean gc) {
        if (gc) System.gc();
        List<Gone> stuck = lingering(SETTLE_MS);
        Runtime rt = Runtime.getRuntime();
        long mb = 1024L * 1024L;
        s.sendMessage("§7Heap used: §f" + (rt.totalMemory() - rt.freeMemory()) / mb + " MB §7(reserved §f"
                + rt.totalMemory() / mb + " MB§7, max §f" + rt.maxMemory() / mb + " MB§7)");
        s.sendMessage("§7Loaded worlds: §f" + Bukkit.getWorlds().size());
        s.sendMessage("§7Unloaded 1+ min ago and still in memory: §f" + stuck.size()
                + (gc ? "" : " §8(may just be waiting for cleanup, use /lg memory gc)"));
        long now = System.currentTimeMillis();
        for (Gone g : stuck) s.sendMessage("§8 - §f" + g.name + " §7unloaded " + (now - g.at) / 60_000L + " min ago");
    }
}
