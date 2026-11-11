package xyz.foldedlettuce.lettucegames;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;

/** Feeds Bingo tiles: kills, eating, advancements, pickups and opponent tasks. */
public class BingoListener implements Listener {
    private final LettuceGames plugin;

    public BingoListener(LettuceGames plugin) {
        this.plugin = plugin;
    }

    private Bingo bingo(org.bukkit.World w) {
        Match m = plugin.matchFor(w);
        return m != null && m.bingoLive() ? m.bingo : null;
    }

    private boolean opponents(Match m, Player a, Player b) {
        return m.alive.contains(a.getUniqueId()) && m.alive.contains(b.getUniqueId())
                && !m.sideKey(a.getUniqueId()).equals(m.sideKey(b.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        LivingEntity ent = e.getEntity();
        if (ent instanceof Player) return;
        Player k = ent.getKiller();
        if (k == null) return;
        Bingo b = bingo(k.getWorld());
        if (b != null) b.progress(k, Bingo.Type.KILL, ent.getType().name());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        Bingo b = bingo(e.getPlayer().getWorld());
        if (b != null) b.progress(e.getPlayer(), Bingo.Type.EAT, e.getItem().getType().name());
    }

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        Bingo b = bingo(e.getPlayer().getWorld());
        if (b != null) b.progress(e.getPlayer(), Bingo.Type.ADV, e.getAdvancement().getKey().getKey());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Bingo b = bingo(p.getWorld());
        if (b != null) b.progress(p, Bingo.Type.ITEM, e.getItem().getItemStack().getType().name());
    }

    /** Snowballs and eggs only need to land, so they count with PvP off too. */
    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        Projectile pr = e.getEntity();
        if (!(pr.getShooter() instanceof Player shooter) || !(e.getHitEntity() instanceof Player hit)) return;
        Match m = plugin.matchFor(shooter.getWorld());
        if (m == null || !m.bingoLive() || !opponents(m, shooter, hit)) return;
        String type = pr.getType().name();
        if (type.equals("SNOWBALL")) m.bingo.progress(shooter, Bingo.Type.OPP, "SNOWBALL");
        else if (type.equals("EGG")) m.bingo.progress(shooter, Bingo.Type.OPP, "EGG");
    }

    @EventHandler
    public void onFish(PlayerFishEvent e) {
        if (!e.getState().name().equals("CAUGHT_ENTITY")) return;
        Entity caught = e.getCaught();
        Player p = e.getPlayer();
        if (!(caught instanceof Player other)) return;
        Match m = plugin.matchFor(p.getWorld());
        if (m != null && m.bingoLive() && opponents(m, p, other)) m.bingo.progress(p, Bingo.Type.OPP, "HOOK");
    }
}
