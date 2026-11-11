package xyz.foldedlettuce.lettucegames;

import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The hand-picked item pool from config.yml (item-pool.groups).
 * Every drop picks a group by weight, then an item from it. Potions are rolled separately and are always splash.
 */
public class ItemPool {

    public static class Group {
        public final String name;
        public final int weight;
        public final String requires;
        /** 0 = full stacks, otherwise cap each stack at this many (spawn eggs) */
        public final int stackSize;
        public final List<Material> items = new ArrayList<>();
        /** per-item amount per stack, from entries like ENDER_PEARL:4 (otherwise a full stack) */
        public final Map<Material, Integer> amounts = new java.util.HashMap<>();

        Group(String name, int weight, String requires, int stackSize) {
            this.name = name;
            this.weight = weight;
            this.requires = requires;
            this.stackSize = stackSize;
        }
    }

    private final LettuceGames plugin;
    public final Map<String, Group> groups = new LinkedHashMap<>();
    public final List<Material> eggs = new ArrayList<>();
    /** everything that can ever drop, for the blacklist menu */
    public final List<Material> all = new ArrayList<>();
    public final List<PotionType> potions = new ArrayList<>();
    public final List<PotionEffectType> effects = new ArrayList<>();
    private final List<Enchantment> enchants = new ArrayList<>();
    private final Set<String> edibleExceptions = new HashSet<>();
    /** drops in a row without any weapon/armor/better item, per player (gear pity timer) */
    private final Map<UUID, Integer> sinceGear = new java.util.HashMap<>();

    private static final String[] TOOL_SUFFIX = {
            "_SWORD", "_AXE", "_SPEAR", "_PICKAXE", "_SHOVEL", "_HOE",
            "_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS"};
    private static final Set<String> TOOL_EXTRA = Set.of(
            "BOW", "CROSSBOW", "TRIDENT", "SHIELD", "MACE", "FISHING_ROD", "SHEARS", "FLINT_AND_STEEL",
            "ELYTRA", "TURTLE_HELMET", "CARROT_ON_A_STICK", "WARPED_FUNGUS_ON_A_STICK", "BRUSH");

    public ItemPool(LettuceGames plugin) {
        this.plugin = plugin;
    }

    public void build() {
        everyItem = null;
        groups.clear(); eggs.clear(); all.clear(); potions.clear(); effects.clear(); enchants.clear(); edibleExceptions.clear();

        for (String s : plugin.getConfig().getStringList("item-pool.edible-exceptions")) edibleExceptions.add(s.toUpperCase(Locale.ROOT));

        ConfigurationSection gs = plugin.getConfig().getConfigurationSection("item-pool.groups");
        Set<Material> seen = new LinkedHashSet<>();
        if (gs != null) {
            for (String key : gs.getKeys(false)) {
                ConfigurationSection g = gs.getConfigurationSection(key);
                if (g == null) continue;
                Group group = new Group(key, Math.max(0, g.getInt("weight", 0)), g.getString("requires", ""), Math.max(0, g.getInt("stack-size", 0)));
                for (String raw : g.getStringList("items")) {
                    String name = raw.trim();
                    int amount = 0;
                    int colon = name.lastIndexOf(':');
                    if (colon > 0 && name.substring(colon + 1).matches("\\d+")) {
                        amount = Integer.parseInt(name.substring(colon + 1));
                        name = name.substring(0, colon);
                    }
                    Material m = Material.matchMaterial(name);
                    if (m == null || !m.isItem()) {
                        plugin.getLogger().warning("item-pool." + key + ": unknown item " + name + ", skipping");
                        continue;
                    }
                    group.items.add(m);
                    if (amount > 0) group.amounts.put(m, amount);
                    seen.add(m);
                }
                if (!group.items.isEmpty()) groups.put(key, group);
            }
        }
        if (groups.isEmpty()) plugin.getLogger().severe("item-pool.groups is empty! Delete config.yml to get the default one back.");

        Group eggGroup = groups.get("eggs");
        if (eggGroup != null) eggs.addAll(eggGroup.items);
        all.addAll(seen);

        List<String> potionNames = plugin.getConfig().getStringList("item-pool.potions");
        if (!potionNames.isEmpty()) {
            for (String n : potionNames) {
                try {
                    potions.add(PotionType.valueOf(n.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("item-pool.potions: unknown potion " + n + ", skipping");
                }
            }
        } else for (PotionType t : PotionType.values()) {
            String n = t.name();
            if (n.equals("WATER") || n.equals("MUNDANE") || n.equals("THICK") || n.equals("AWKWARD")) continue;
            potions.add(t);
        }
        for (PotionEffectType t : Registry.EFFECT) {
            String k = t.getKey().getKey();
            if (k.equals("instant_damage") || k.contains("omen") || k.equals("hero_of_the_village")) continue;
            effects.add(t);
        }
        for (Enchantment e : Registry.ENCHANTMENT) enchants.add(e);

        StringBuilder sb = new StringBuilder("Item pool:");
        for (Group g : groups.values()) sb.append(' ').append(g.name).append('=').append(g.items.size());
        sb.append(" eggs=").append(eggs.size()).append(" potions=").append(potions.size());
        plugin.getLogger().info(sb.toString());
        buildOpLoot();
    }

    public static boolean isToolOrGear(Material m) {
        String n = m.name();
        if (TOOL_EXTRA.contains(n)) return true;
        for (String s : TOOL_SUFFIX) if (n.endsWith(s)) return true;
        return false;
    }

    private boolean groupActive(Group g, GameSettings s) {
        if (g.weight <= 0) return false;
        return switch (g.requires.toLowerCase(Locale.ROOT)) {
            case "crafting" -> s.on(Setting.CRAFTING);
            case "eat-effect", "eating" -> s.on(Setting.EAT_EFFECT);
            case "spawn-eggs", "eggs" -> s.on(Setting.SPAWN_EGGS);
            default -> true;
        };
    }

    /** No food unless the eating twist is on (or it's listed in edible-exceptions). */
    private boolean allowed(Material m, GameSettings s) {
        if (s.itemBlacklist.contains(m.name())) return false;
        if (m.isEdible() && !s.on(Setting.EAT_EFFECT) && !edibleExceptions.contains(m.name())) return false;
        return true;
    }

    private List<Material> candidates(Group g, GameSettings s) {
        List<Material> out = new ArrayList<>();
        for (Material m : g.items) if (allowed(m, s)) out.add(m);
        return out;
    }

    /** What one player gets for one drop. */
    public static class Roll {
        public final List<ItemStack> stacks = new ArrayList<>();
        public String name;
    }

    public Roll roll(UUID player, boolean firstDrop, GameSettings s, Map<UUID, Set<String>> history) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Set<String> hist = history.computeIfAbsent(player, k -> new HashSet<>());
        int count = s.get(Setting.DROP_SIZE);
        Roll out = new Roll();

        double potionChance = plugin.getConfig().getDouble("item-pool.potion-chance", 0.15);
        if (!firstDrop && r.nextDouble() < potionChance && !availablePotions(s).isEmpty()) {
            List<PotionType> avail = availablePotions(s);
            PotionType t = avail.get(r.nextInt(avail.size()));
            for (int i = 0; i < count; i++) {
                ItemStack it = new ItemStack(Material.SPLASH_POTION, 1);
                ItemMeta meta = it.getItemMeta();
                if (meta instanceof PotionMeta pm) {
                    pm.setBasePotionType(t);
                    it.setItemMeta(pm);
                }
                out.stacks.add(it);
            }
            out.name = "Splash Potion of " + Msg.pretty(t.name());
            return out;
        }

        // pick a group by weight from the ones that have something to give
        List<Group> active = new ArrayList<>();
        List<List<Material>> lists = new ArrayList<>();
        int totalWeight = 0;
        for (Group g : groups.values()) {
            if (firstDrop && !(g.name.equals("blocks") || g.name.equals("utility"))) continue;
            if (!groupActive(g, s)) continue;
            List<Material> c = candidates(g, s);
            if (c.isEmpty()) continue;
            active.add(g);
            lists.add(c);
            totalWeight += g.weight;
        }
        if (active.isEmpty()) {
            // everything is blacklisted, fall back to blocks so drops never stop
            Group g = groups.get("blocks");
            if (g == null) g = groups.values().iterator().next();
            active.add(g);
            lists.add(g.items);
            totalWeight = Math.max(1, g.weight);
        }
        int pick = r.nextInt(Math.max(1, totalWeight));
        int idx = 0;
        for (int i = 0; i < active.size(); i++) {
            pick -= active.get(i).weight;
            if (pick < 0) { idx = i; break; }
        }
        // pity: never go more than a couple of drops in a row without a weapon or armor
        if (firstDrop) sinceGear.put(player, 0);
        int pity = plugin.getConfig().getInt("item-pool.gear-pity", 2);
        if (!firstDrop && pity > 0 && sinceGear.getOrDefault(player, 0) >= pity) {
            int w = -1, b = -1;
            for (int i = 0; i < active.size(); i++) {
                if (active.get(i).name.equals("weapons")) w = i;
                if (active.get(i).name.equals("better")) b = i;
            }
            if (b >= 0 && r.nextDouble() < 0.15) idx = b;
            else if (w >= 0) idx = w;
        }
        List<Material> list = lists.get(idx);
        Group chosen = active.get(idx);
        boolean gearGroup = chosen.name.equals("weapons") || chosen.name.equals("better");
        sinceGear.put(player, gearGroup ? 0 : sinceGear.getOrDefault(player, 0) + 1);

        List<Material> fresh = new ArrayList<>();
        for (Material m : list) if (!hist.contains(m.name())) fresh.add(m);
        if (fresh.isEmpty()) {
            // been through this whole group, start it over
            for (Material m : list) hist.remove(m.name());
            fresh = list;
        }
        Material m = fresh.get(r.nextInt(fresh.size()));
        hist.add(m.name());

        int mode = s.get(Setting.RANDOM_ENCHANTS);

        int max = Math.max(1, m.getMaxStackSize());
        if (chosen.stackSize > 0) max = Math.min(max, chosen.stackSize);
        Integer perItem = chosen.amounts.get(m);
        if (perItem != null) max = Math.min(max, perItem);
        boolean enchantable = mode > 0 && isToolOrGear(m);
        // gear is one item, and anything with an explicit amount (or a group stack-size cap) is one stack of that amount
        boolean single = m.getMaxStackSize() == 1 || perItem != null || chosen.stackSize > 0;
        int n = single ? 1 : count;
        for (int i = 0; i < n; i++) {
            ItemStack it = new ItemStack(m, max);
            if (enchantable) enchant(it, mode, s.on(Setting.NO_BLAST_PROT));
            out.stacks.add(it);
        }
        out.name = Msg.pretty(m);
        return out;
    }

    public List<PotionType> availablePotions(GameSettings s) {
        List<PotionType> out = new ArrayList<>();
        for (PotionType t : potions) if (!s.potionBlacklist.contains(t.name())) out.add(t);
        return out;
    }

    private void enchant(ItemStack it, int mode, boolean noBlast) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<Enchantment> pool = new ArrayList<>();
        for (Enchantment e : enchants) {
            String key = e.getKey().getKey();
            if (noBlast && key.equals("blast_protection")) continue;
            if (mode != 3 && key.contains("curse")) continue;
            if (mode == 3 || e.canEnchantItem(it)) pool.add(e);
        }
        if (pool.isEmpty()) return;
        Collections.shuffle(pool);

        int want = switch (mode) {
            case 1 -> 1 + r.nextInt(2);
            case 2 -> 2 + r.nextInt(3);
            default -> 1 + r.nextInt(4);
        };
        List<Enchantment> chosen = new ArrayList<>();
        for (Enchantment e : pool) {
            if (chosen.size() >= want) break;
            if (mode != 3) {
                boolean clash = false;
                for (Enchantment c : chosen) if (c.conflictsWith(e) || e.conflictsWith(c)) clash = true;
                if (clash) continue;
            }
            chosen.add(e);
        }
        for (Enchantment e : chosen) {
            int lvl = switch (mode) {
                case 1 -> 1 + r.nextInt(Math.max(1, e.getMaxLevel()));
                case 2 -> e.getMaxLevel();
                default -> 1 + r.nextInt(10);
            };
            it.addUnsafeEnchantment(e, lvl);
        }
    }

    // ------------------------------------------------------------------ OP loot (Death Swap)

    private static class OpEntry {
        final Material m;
        final int amount;
        final int weight;
        final boolean enchanted;

        OpEntry(Material m, int amount, int weight, boolean enchanted) {
            this.m = m;
            this.amount = amount;
            this.weight = weight;
            this.enchanted = enchanted;
        }
    }

    private final List<OpEntry> opTable = new ArrayList<>();
    private int opTotal = 0;

    /** Reads op-loot from config: "MATERIAL amount weight [enchanted]" per line. */
    public void buildOpLoot() {
        opTable.clear();
        opTotal = 0;
        for (String line : plugin.getConfig().getStringList("op-loot")) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 3) continue;
            Material m = Material.matchMaterial(p[0]);
            if (m == null || !m.isItem()) {
                plugin.getLogger().warning("op-loot: unknown item " + p[0]);
                continue;
            }
            try {
                OpEntry e = new OpEntry(m, Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                        p.length > 3 && p[3].equalsIgnoreCase("enchanted"));
                if (e.weight <= 0) continue;
                opTable.add(e);
                opTotal += e.weight;
            } catch (NumberFormatException ex) {
                plugin.getLogger().warning("op-loot: bad line " + line);
            }
        }
        if (opTable.isEmpty()) {
            opTable.add(new OpEntry(Material.DIAMOND, 4, 1, false));
            opTotal = 1;
        }
        plugin.getLogger().info("OP loot table: " + opTable.size() + " items");
    }

    public ItemStack opLoot() {
        if (opTable.isEmpty()) buildOpLoot();
        int pick = ThreadLocalRandom.current().nextInt(Math.max(1, opTotal));
        OpEntry chosen = opTable.get(0);
        for (OpEntry e : opTable) {
            pick -= e.weight;
            if (pick < 0) { chosen = e; break; }
        }
        ItemStack it = new ItemStack(chosen.m, Math.max(1, Math.min(chosen.amount, chosen.m.getMaxStackSize())));
        if (chosen.enchanted) enchant(it, 2, false);
        return it;
    }

    private List<Material> everyItem;

    /** Every normal obtainable item, for the Random Drops twist. */
    public List<Material> everyItem() {
        if (everyItem != null) return everyItem;
        Set<String> exclude = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("item-pool.always-excluded")) exclude.add(s.toUpperCase(Locale.ROOT));
        List<String> containsExclude = plugin.getConfig().getStringList("item-pool.excluded-if-name-contains");
        List<Material> out = new ArrayList<>();
        outer:
        for (Material m : Material.values()) {
            try {
                if (m.isLegacy() || !m.isItem() || m.isAir()) continue;
            } catch (Throwable t) {
                continue;
            }
            String n = m.name();
            if (exclude.contains(n) || n.endsWith("_SPAWN_EGG")) continue;
            for (String c : containsExclude) if (n.contains(c.toUpperCase(Locale.ROOT))) continue outer;
            out.add(m);
        }
        everyItem = out;
        return out;
    }

    public PotionEffectType randomEffect() {
        if (effects.isEmpty()) return null;
        return effects.get(ThreadLocalRandom.current().nextInt(effects.size()));
    }
}
