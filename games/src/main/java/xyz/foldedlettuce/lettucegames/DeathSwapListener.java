package xyz.foldedlettuce.lettucegames;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

/** Extra rules shared by the newer modes: resistances, bed bombing, QoL, OP loot, Random Drops. */
public class DeathSwapListener implements Listener {
    private final LettuceGames plugin;

    public DeathSwapListener(LettuceGames plugin) {
        this.plugin = plugin;
    }

    /** Any running game. Every rule here checks its own setting, which is off in modes that don't have it. */
    private Match ds(World w) {
        Match m = plugin.matchFor(w);
        return m != null && !m.ended ? m : null;
    }

    private void dropOp(Location l, Player notify) {
        ItemStack it = plugin.pool().opLoot();
        plugin.allowItemSpawn = true;
        try {
            l.getWorld().dropItemNaturally(l, it);
        } finally {
            plugin.allowItemSpawn = false;
        }
        if (notify != null) {
            notify.playSound(notify.getLocation(), "minecraft:entity.player.levelup", 0.6f, 1.6f);
            notify.sendActionBar("§6✦ OP Loot: §e" + Msg.pretty(it.getType()) + (it.getAmount() > 1 ? " x" + it.getAmount() : ""));
        }
    }

    // ------------------------------------------------------------------ damage

    private static boolean isFire(String cause) {
        return cause.equals("FIRE") || cause.equals("FIRE_TICK") || cause.equals("LAVA") || cause.equals("HOT_FLOOR")
                || cause.equals("CAMPFIRE");
    }

    /** Runs before the elimination check so resistances can stop a "lethal" hit. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = ds(p.getWorld());
        if (m == null || !m.alive.contains(p.getUniqueId())) return;
        String cause = e.getCause().name();
        if (m.s.on(Setting.FIRE_RES) && isFire(cause)) {
            e.setCancelled(true);
            if (!cause.equals("LAVA")) p.setFireTicks(0);
            return;
        }
        if (m.s.on(Setting.FALL_RES) && cause.equals("FALL")) {
            e.setCancelled(true);
            return;
        }
        if (m.s.on(Setting.BED_REDUCE) && e instanceof EntityDamageByBlockEvent bd) {
            BlockState st = bd.getDamagerBlockState();
            if (st != null && (st.getType().name().endsWith("_BED") || st.getType() == Material.RESPAWN_ANCHOR)) {
                e.setDamage(e.getDamage() * 0.3);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onBedBomb(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Match m = ds(e.getPlayer().getWorld());
        if (m == null || m.s.on(Setting.BED_BOMBING)) return;
        Block b = e.getClickedBlock();
        World.Environment env = b.getWorld().getEnvironment();
        boolean bed = b.getType().name().endsWith("_BED") && env != World.Environment.NORMAL;
        ItemStack hand = e.getItem();
        boolean anchor = b.getType() == Material.RESPAWN_ANCHOR && env != World.Environment.NETHER
                && (hand == null || hand.getType() != Material.GLOWSTONE);
        if (bed || anchor) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar("§cBed bombing is off this game.");
        }
    }

    @EventHandler
    public void onNether(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        Match m = ds(p.getWorld());
        if (m == null || !m.alive.contains(p.getUniqueId()) || !m.s.on(Setting.NETHER_ENTRY_RES)) return;
        if (p.getWorld().getEnvironment() == World.Environment.NETHER) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 15 * 20, 0));
            p.sendActionBar("§615s of fire resistance!");
        }
    }

    // ------------------------------------------------------------------ blocks: OP loot, vein miner

    private static boolean isOre(Material m) {
        String n = m.name();
        return n.endsWith("_ORE") || n.equals("ANCIENT_DEBRIS");
    }

    private static String oreFamily(Material m) {
        return m.name().replace("DEEPSLATE_", "");
    }

    private static Material smelt(Material m) {
        return switch (m.name()) {
            case "RAW_IRON" -> Material.IRON_INGOT;
            case "RAW_GOLD" -> Material.GOLD_INGOT;
            case "RAW_COPPER" -> Material.COPPER_INGOT;
            default -> null;
        };
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Match m = ds(p.getWorld());
        if (m == null || !m.alive.contains(p.getUniqueId())) return;
        Block b = e.getBlock();
        Material type = b.getType();

        if (m.s.on(Setting.RANDOM_DROPS) && !type.name().endsWith("SHULKER_BOX") && type.isItem()) {
            e.setDropItems(false);
            Location l = b.getLocation().add(0.5, 0.5, 0.5);
            Material drop = m.randomDrop("block:" + type.name(), plugin.pool().everyItem());
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.allowItemSpawn = true;
                try {
                    l.getWorld().dropItemNaturally(l, new ItemStack(drop));
                } finally {
                    plugin.allowItemSpawn = false;
                }
            });
            return;
        }
        for (OpSource o : m.opActive) {
            if (o.matchesBlock(type)) {
                e.setDropItems(false);
                Location l = b.getLocation().add(0.5, 0.5, 0.5);
                Bukkit.getScheduler().runTask(plugin, () -> dropOp(l, p));
                return;
            }
        }

        if (!m.s.on(Setting.VEIN_MINER) || !isOre(type)) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (tool == null || !tool.getType().name().endsWith("_PICKAXE") || !b.isPreferredTool(tool)) return;

        // find touching ores of the same kind (diagonals count), up to 64 total
        String family = oreFamily(type);
        int max = Math.max(1, plugin.getConfig().getInt("deathswap.vein-miner-max", 64));
        Set<String> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        List<Block> vein = new ArrayList<>();
        queue.add(b);
        seen.add(key(b));
        while (!queue.isEmpty() && vein.size() < max) {
            Block cur = queue.poll();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                Block nb = cur.getRelative(dx, dy, dz);
                if (!seen.add(key(nb))) continue;
                if (!isOre(nb.getType()) || !oreFamily(nb.getType()).equals(family)) continue;
                vein.add(nb);
                queue.add(nb);
                if (vein.size() >= max - 1) break;
            }
        }
        if (vein.isEmpty()) return;
        boolean smelt = (m.s.on(Setting.AUTO_SMELT) || (m.mode.isHunt() && m.s.on(Setting.UHC)));
        Location dropAt = b.getLocation().add(0.5, 0.5, 0.5);
        int count = 0;
        for (Block ore : vein) {
            for (ItemStack drop : ore.getDrops(tool, p)) {
                Material sm = smelt ? smelt(drop.getType()) : null;
                if (sm != null) drop = new ItemStack(sm, drop.getAmount());
                plugin.allowItemSpawn = true;
                try {
                    ore.getWorld().dropItemNaturally(dropAt, drop);
                } finally {
                    plugin.allowItemSpawn = false;
                }
            }
            ore.setType(Material.AIR);
            count++;
        }
        p.giveExp(count * 2);
        try {
            p.damageItemStack(EquipmentSlot.HAND, count);
        } catch (Throwable ignored) {
        }
    }

    private static String key(Block b) {
        return b.getX() + "," + b.getY() + "," + b.getZ();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockDrops(BlockDropItemEvent e) {
        Player p = e.getPlayer();
        Match m = ds(p.getWorld());
        if (m == null || !(m.s.on(Setting.AUTO_SMELT) || (m.mode.isHunt() && m.s.on(Setting.UHC)))) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (tool == null || !tool.getType().name().endsWith("_PICKAXE")) return;
        for (Item i : e.getItems()) {
            ItemStack st = i.getItemStack();
            Material sm = smelt(st.getType());
            if (sm != null) i.setItemStack(new ItemStack(sm, st.getAmount()));
        }
    }

    // ------------------------------------------------------------------ mobs: OP loot, auto cook

    private static final Map<String, Material> COOKED = Map.of(
            "BEEF", Material.COOKED_BEEF, "PORKCHOP", Material.COOKED_PORKCHOP, "CHICKEN", Material.COOKED_CHICKEN,
            "MUTTON", Material.COOKED_MUTTON, "RABBIT", Material.COOKED_RABBIT, "COD", Material.COOKED_COD,
            "SALMON", Material.COOKED_SALMON);

    @EventHandler(priority = EventPriority.HIGH)
    public void onMobDeath(EntityDeathEvent e) {
        LivingEntity ent = e.getEntity();
        if (ent instanceof Player) return;
        Match m = ds(ent.getWorld());
        if (m == null) return;
        String type = ent.getType().name();
        if (m.s.on(Setting.RANDOM_DROPS) && !type.equals("ENDERMAN") && !type.equals("BLAZE")) {
            e.getDrops().clear();
            e.getDrops().add(new ItemStack(m.randomDrop("mob:" + type, plugin.pool().everyItem())));
            return;
        }
        Player killer = ent.getKiller();
        if (killer == null) return;
        for (OpSource o : m.opActive) {
            if (o.matchesEntity(type)) {
                e.getDrops().clear();
                e.getDrops().add(plugin.pool().opLoot());
                killer.playSound(killer.getLocation(), "minecraft:entity.player.levelup", 0.6f, 1.6f);
                return;
            }
        }
        if (m.s.on(Setting.AUTO_COOK)) {
            ListIterator<ItemStack> it = e.getDrops().listIterator();
            while (it.hasNext()) {
                ItemStack st = it.next();
                Material c = COOKED.get(st.getType().name());
                if (c != null) it.set(new ItemStack(c, st.getAmount()));
            }
        }
    }

    // ------------------------------------------------------------------ other OP loot sources

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        Player p = e.getPlayer();
        Match m = ds(p.getWorld());
        if (m == null || !m.alive.contains(p.getUniqueId()) || !m.opActive.contains(OpSource.ADVANCEMENTS)) return;
        String key = e.getAdvancement().getKey().getKey();
        if (key.startsWith("recipes/") || key.endsWith("/root")) return;
        dropOp(p.getLocation(), p);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        Match m = ds(p.getWorld());
        if (m == null || !m.alive.contains(p.getUniqueId()) || !m.opActive.contains(OpSource.EATING)) return;
        if (!e.getItem().getType().isEdible()) return;
        Bukkit.getScheduler().runTask(plugin, () -> dropOp(p.getLocation(), p));
    }

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        Match m = ds(p.getWorld());
        if (m == null || !m.opActive.contains(OpSource.FISHING)) return;
        if (!e.getState().name().equals("CAUGHT_FISH") || !(e.getCaught() instanceof Item caught)) return;
        ItemStack it = plugin.pool().opLoot();
        caught.setItemStack(it);
        p.sendActionBar("§6✦ OP Loot: §e" + Msg.pretty(it.getType()));
    }
}
