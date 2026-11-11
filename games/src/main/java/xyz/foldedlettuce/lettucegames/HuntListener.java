package xyz.foldedlettuce.lettucegames;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PiglinBarterEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTables;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Manhunt and Hitman rules and twists. Everything here only kicks in for those two modes. */
public class HuntListener implements Listener {
    private final LettuceGames plugin;

    public HuntListener(LettuceGames plugin) {
        this.plugin = plugin;
        registerGoldenHead();
    }

    /** Any running game in this world (settings that don't apply to a mode are off there anyway). */
    private Match any(World w) {
        Match m = plugin.matchFor(w);
        return m != null && !m.ended ? m : null;
    }

    /** Running Manhunt/Hitman game in this world, or null. */
    private Match hunt(World w) {
        Match m = plugin.matchFor(w);
        return m != null && !m.ended && m.mode.isHunt() ? m : null;
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    // ------------------------------------------------------------------ freezing

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        if (to == null || (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ())) return;
        Match m = any(e.getPlayer().getWorld());
        if (m == null || !m.isFrozen(e.getPlayer().getUniqueId())) return;
        // let them fall onto the ground, just not walk or jump
        if (to.getY() < from.getY() && !e.getPlayer().isOnGround() && to.getX() == from.getX() && to.getZ() == from.getZ()) return;
        Location back = from.clone();
        back.setYaw(to.getYaw());
        back.setPitch(to.getPitch());
        e.setTo(back);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFrozenBreak(BlockBreakEvent e) {
        Match m = any(e.getPlayer().getWorld());
        if (m != null && m.isFrozen(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFrozenPlace(BlockPlaceEvent e) {
        Match m = any(e.getPlayer().getWorld());
        if (m != null && m.isFrozen(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    /** Frozen survivors (freeze tag) can't be hurt. Runs before the death check. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIcedDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = any(p.getWorld());
        if (m == null) return;
        UUID id = p.getUniqueId();
        if (m.iced.containsKey(id) || (m.mode == Mode.BINGO && m.isFrozen(id))) {
            e.setCancelled(true);
            return;
        }
        // bingo drop-ins: no fall damage for the first minute
        Integer safe = m.fallSafeUntil.get(id);
        if (safe != null && e.getCause().name().equals("FALL") && m.elapsed < safe) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Player att = attacker(e.getDamager());
        if (att == null) return;
        Match m = any(att.getWorld());
        if (m == null) return;
        if (m.isFrozen(att.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (!(e.getEntity() instanceof Player victim) || victim.equals(att)) return;
        UUID a = att.getUniqueId(), v = victim.getUniqueId();
        if (!m.alive.contains(v)) return;
        // freeze tag: a hitman's punch freezes instead of hurting
        if (m.freezeTag() && m.isHunter(a) && m.isRunner(v) && e.getDamager() instanceof Player) {
            e.setCancelled(true);
            m.ice(victim, att);
            return;
        }
        // assassins: runners die in one hit
        if (m.s.on(Setting.ASSASSINS) && m.isHunter(a) && m.isRunner(v)) {
            e.setDamage(1000.0);
        }
    }

    // ------------------------------------------------------------------ twists

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        Player p = e.getPlayer();
        Match m = hunt(p.getWorld());
        if (m == null || !m.s.on(Setting.SNEAK_INVIS) || !m.isRunner(p.getUniqueId()) || !m.alive.contains(p.getUniqueId())) return;
        if (e.isSneaking()) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false));
            m.sneakInvis.add(p.getUniqueId());
        } else if (m.sneakInvis.remove(p.getUniqueId())) {
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = hunt(p.getWorld());
        if (m == null || !m.s.on(Setting.UHC)) return;
        String r = e.getRegainReason().name();
        if (r.equals("SATIATED") || r.equals("REGEN")) e.setCancelled(true);
    }

    private ItemStack goldenHead() {
        ItemStack it = new ItemStack(Material.GOLDEN_APPLE);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(Match.GOLDEN_HEAD);
        meta.setLore(List.of("§7Like a golden apple, but heals more."));
        it.setItemMeta(meta);
        return it;
    }

    private void registerGoldenHead() {
        try {
            NamespacedKey key = new NamespacedKey(plugin, "golden_head");
            Bukkit.removeRecipe(key);
            ShapedRecipe r = new ShapedRecipe(key, goldenHead());
            r.shape("GGG", "GHG", "GGG");
            r.setIngredient('G', Material.GOLD_INGOT);
            r.setIngredient('H', Material.PLAYER_HEAD);
            Bukkit.addRecipe(r);
        } catch (Throwable t) {
            plugin.getLogger().warning("Couldn't add the Golden Head recipe: " + t);
        }
    }

    private static boolean isGoldenHead(ItemStack it) {
        return it != null && it.getType() == Material.GOLDEN_APPLE && it.hasItemMeta()
                && Match.GOLDEN_HEAD.equals(it.getItemMeta().getDisplayName());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        Match m = hunt(p.getWorld());
        if (m == null) return;
        ItemStack it = e.getItem();
        if (blacklisted(m, it)) {
            e.setCancelled(true);
            p.sendActionBar("§cThat item is blacklisted this game.");
            return;
        }
        if (isGoldenHead(it)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 0));
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCraft(PrepareItemCraftEvent e) {
        if (e.getViewers().isEmpty()) return;
        ItemStack result = e.getInventory().getResult();
        if (result == null) return;
        Match m = plugin.matchFor(e.getViewers().get(0).getWorld());
        boolean uhc = m != null && m.mode.isHunt() && m.s.on(Setting.UHC);
        if (isGoldenHead(result) && !uhc) {
            e.getInventory().setResult(null);
            return;
        }
        if (m != null && m.mode.isHunt() && blacklisted(m, result)) e.getInventory().setResult(null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        Match m = any(e.getEnchanter().getWorld());
        if (m == null || !m.s.on(Setting.OP_ENCHANTING)) return;
        ItemStack item = e.getItem();
        Map<Enchantment, Integer> add = e.getEnchantsToAdd();
        List<Enchantment> fits = new ArrayList<>();
        for (Enchantment en : org.bukkit.Registry.ENCHANTMENT) {
            if (item.getType() == Material.BOOK || en.canEnchantItem(item)) {
                if (en.isCursed()) continue;
                fits.add(en);
            }
        }
        if (fits.isEmpty()) return;
        java.util.Collections.shuffle(fits);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        // bump what the table rolled, then add a couple more
        for (Map.Entry<Enchantment, Integer> en : add.entrySet()) {
            en.setValue(Math.min(10, en.getKey().getMaxLevel() + r.nextInt(1, 4)));
        }
        int extra = r.nextInt(1, 3);
        for (Enchantment en : fits) {
            if (extra <= 0) break;
            if (add.containsKey(en)) continue;
            boolean clash = false;
            for (Enchantment have : add.keySet()) if (have.conflictsWith(en)) clash = true;
            if (clash) continue;
            add.put(en, Math.min(10, en.getMaxLevel() + r.nextInt(0, 3)));
            extra--;
        }
        e.getEnchanter().sendActionBar("§d✦ OP Enchant!");
    }

    @EventHandler(ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent e) {
        Player p = e.getPlayer();
        Match m = any(p.getWorld());
        if (m == null || !m.s.on(Setting.OP_SHEARING)) return;
        Entity ent = e.getEntity();
        String key = ent.getType().name();
        if (ent instanceof org.bukkit.entity.Sheep sh && sh.getColor() != null) key += ":" + sh.getColor().name();
        LootTables t = m.shearTable(key);
        if (t == null) return;
        try {
            LootContext ctx = new LootContext.Builder(ent.getLocation()).lootedEntity(ent).killer(p).build();
            Collection<ItemStack> loot = t.getLootTable().populateLoot(ThreadLocalRandom.current(), ctx);
            e.setDrops(new ArrayList<>(loot));
            p.sendActionBar("§6✦ Shearing loot: §e" + Msg.pretty(t.name()));
        } catch (Throwable ex) {
            plugin.getLogger().warning("Shearing loot failed: " + ex);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBarter(PiglinBarterEvent e) {
        Match m = any(e.getEntity().getWorld());
        if (m == null || !m.s.on(Setting.INCREASED_LUCK)) return;
        List<ItemStack> out = e.getOutcome();
        for (ItemStack it : out) if (it.getType() == Material.ENDER_PEARL) return;
        // 2.5% -> 6.6%
        if (ThreadLocalRandom.current().nextDouble() < 0.042) {
            out.clear();
            out.add(new ItemStack(Material.ENDER_PEARL, ThreadLocalRandom.current().nextInt(4, 9)));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMobDeath(EntityDeathEvent e) {
        LivingEntity ent = e.getEntity();
        Match m = any(ent.getWorld());
        if (m == null) return;
        String type = ent.getType().name();
        if (type.equals("ENDER_DRAGON") && m.mode.hasEnd()) {
            Player k = ent.getKiller();
            String who = k != null ? " §7(" + m.colored(k.getUniqueId()) + " §7got the kill)" : "";
            m.broadcast(m.tag() + "§5§lThe Ender Dragon is dead!" + who);
            String side = m.mode == Mode.SPEEDRUN ? "ALL" : Role.RUNNER.team.name();
            Bukkit.getScheduler().runTask(plugin, () -> m.end(side, "The Ender Dragon is dead!"));
            return;
        }
        if (!m.s.on(Setting.INCREASED_LUCK) || ent.getKiller() == null || m.s.on(Setting.RANDOM_DROPS)) return;
        Material want = type.equals("BLAZE") ? Material.BLAZE_ROD : type.equals("ENDERMAN") ? Material.ENDER_PEARL : null;
        if (want == null) return;
        for (ItemStack it : e.getDrops()) if (it.getType() == want) return;
        // 50% -> 66.6%: a third of the misses become a drop
        if (ThreadLocalRandom.current().nextDouble() < 0.333) e.getDrops().add(new ItemStack(want));
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!e.getEntityType().name().equals("PIGLIN_BRUTE")) return;
        Match m = any(e.getLocation().getWorld());
        if (m != null && m.s.on(Setting.NO_BRUTES)) e.setCancelled(true);
    }

    private static boolean blazeSpawner(Block b) {
        if (b.getType() != Material.SPAWNER) return false;
        try {
            return b.getState() instanceof org.bukkit.block.CreatureSpawner cs && cs.getSpawnedType() != null
                    && cs.getSpawnedType().name().equals("BLAZE");
        } catch (Throwable t) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnerBreak(BlockBreakEvent e) {
        Match m = any(e.getBlock().getWorld());
        if (m == null || !m.s.on(Setting.PROTECT_SPAWNERS) || !blazeSpawner(e.getBlock())) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar("§cBlaze spawners are protected this game.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        Match m = any(e.getEntity().getWorld());
        if (m != null && m.s.on(Setting.PROTECT_SPAWNERS)) e.blockList().removeIf(HuntListener::blazeSpawner);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        Match m = any(e.getBlock().getWorld());
        if (m != null && m.s.on(Setting.PROTECT_SPAWNERS)) e.blockList().removeIf(HuntListener::blazeSpawner);
    }

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        Player p = e.getPlayer();
        Match m = hunt(p.getWorld());
        if (m != null && m.s.on(Setting.ASSASSINS) && m.isHunter(p.getUniqueId())) e.message(null);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        Match m = any(p.getWorld());
        if (m == null || !m.alive.contains(p.getUniqueId())) return;
        World.Environment env = p.getWorld().getEnvironment();
        // milestones: first time into the nether or the end
        if ((m.mode.isHunt() || m.mode == Mode.SPEEDRUN) && env != World.Environment.NORMAL
                && m.milestones.add(p.getUniqueId() + ":" + env.name())) {
            String where = env == World.Environment.NETHER ? "§cthe Nether" : "§5the End";
            m.broadcast(m.tag() + m.colored(p.getUniqueId()) + " §7entered " + where + "§7!");
            m.ding(p);
        }
        if (!m.isRunner(p.getUniqueId())) return;
        if (env == World.Environment.NETHER && !m.runnerInNether) {
            m.runnerInNether = true;
            if (m.s.on(Setting.NETHER_FIRST)) {
                m.broadcast(m.tag() + "§c" + m.mode.words("A runner made it to the nether! Hunters can follow now."));
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        Match m = hunt(p.getWorld());
        if (m == null) return;
        if (m.s.on(Setting.SWAPHUNT) && m.isRunner(p.getUniqueId())) {
            e.setCancelled(true);
            p.sendActionBar(m.mode.words("§cRunners can't drop items in SwapHunt."));
        }
    }

    // ------------------------------------------------------------------ blacklist

    private static boolean blacklisted(Match m, ItemStack it) {
        if (it == null || m.s.itemBlacklist.isEmpty()) {
            return it != null && potionBlacklisted(m, it);
        }
        return m.s.itemBlacklist.contains(it.getType().name()) || potionBlacklisted(m, it);
    }

    private static boolean potionBlacklisted(Match m, ItemStack it) {
        if (m.s.potionBlacklist.isEmpty() || !it.hasItemMeta() || !(it.getItemMeta() instanceof PotionMeta pm)) return false;
        PotionType t = pm.getBasePotionType();
        return t != null && m.s.potionBlacklist.contains(t.name());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = hunt(p.getWorld());
        if (m != null && blacklisted(m, e.getItem().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Match m = hunt(p.getWorld());
        if (m == null || e.getInventory().getHolder() instanceof Menus.Menu) return;
        if (blacklisted(m, e.getCurrentItem())) {
            e.setCancelled(true);
            p.sendActionBar("§cThat item is blacklisted this game.");
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent e) {
        Match m = hunt(e.getPlayer().getWorld());
        if (m == null || e.getItem() == null) return;
        if (e.getAction() == Action.PHYSICAL) return;
        if (blacklisted(m, e.getItem())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar("§cThat item is blacklisted this game.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEffect(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = hunt(p.getWorld());
        if (m == null || m.s.potionBlacklist.isEmpty() || e.getNewEffect() == null) return;
        PotionEffectType type = e.getNewEffect().getType();
        for (String name : m.s.potionBlacklist) {
            try {
                for (PotionEffect pe : PotionType.valueOf(name).getPotionEffects()) {
                    if (pe.getType().equals(type)) {
                        e.setCancelled(true);
                        return;
                    }
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }
}
