package xyz.foldedlettuce.lettucegames;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Drives OldCombatMechanics from the "1.8 Combat" setting. Players in a match world whose party has it on get the
 * OCM modeset "old", everyone else (lobby, waiting area, matches with it off) gets "new".
 * Talks to OCM through its /ocm command so there's no compile-time dependency on it.
 */
public final class OldCombat implements Listener {
    private final LettuceGames plugin;
    private final Map<UUID, String> applied = new HashMap<>();
    private boolean warned = false;

    public OldCombat(LettuceGames plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // safety net: also catches settings changes, teleports between worlds, and OCM resetting someone's mode
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) apply(p, false);
        }, 40L, 20L);
    }

    public boolean available() {
        return plugin.getConfig().getBoolean("combat.enabled", true)
                && Bukkit.getPluginManager().isPluginEnabled("OldCombatMechanics");
    }

    private String want(Player p) {
        Match m = plugin.matchFor(p.getWorld());
        boolean old = m != null && m.s.on(Setting.OLD_COMBAT);
        return old ? plugin.getConfig().getString("combat.old-modeset", "old")
                : plugin.getConfig().getString("combat.new-modeset", "new");
    }

    /** Sets the player's OCM modeset if it isn't already what it should be. force = send even if we think it's set. */
    public void apply(Player p, boolean force) {
        if (!available()) return;
        String want = want(p);
        if (!force && want.equals(applied.get(p.getUniqueId()))) return;
        applied.put(p.getUniqueId(), want);
        boolean ok = false;
        // the live config.yml on the server won't have the combat section until it's added, so default here too
        java.util.List<String> cmds = plugin.getConfig().getStringList("combat.commands");
        if (cmds.isEmpty()) cmds = java.util.List.of("ocm mode {mode} {player}", "ocm modeset {mode} {player}");
        for (String cmd : cmds) {
            String line = cmd.replace("{mode}", want).replace("{player}", p.getName());
            try {
                if (Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line)) {
                    ok = true;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        if (!ok && !warned) {
            warned = true;
            plugin.getLogger().warning("1.8 Combat: couldn't set OldCombatMechanics modeset '" + want
                    + "'. Check combat.commands in config.yml and that OCM has modesets '"
                    + plugin.getConfig().getString("combat.old-modeset", "old") + "' and '"
                    + plugin.getConfig().getString("combat.new-modeset", "new") + "'.");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        applied.remove(e.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> apply(e.getPlayer(), true), 10L);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        // OCM may reset the mode when the world changes, so force it again shortly after
        applied.remove(e.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> apply(e.getPlayer(), true), 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        applied.remove(e.getPlayer().getUniqueId());
    }
}
