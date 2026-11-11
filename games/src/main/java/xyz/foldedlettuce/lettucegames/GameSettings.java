package xyz.foldedlettuce.lettucegames;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** One mode's chosen settings, blacklists and OP loot twists included. */
public class GameSettings {
    public final Mode mode;
    private final Map<Setting, Integer> idx = new EnumMap<>(Setting.class);
    public final Set<String> itemBlacklist = new LinkedHashSet<>();
    public final Set<String> potionBlacklist = new LinkedHashSet<>();
    /** Death Swap: which OP Loot twists are on */
    public final Set<OpSource> opLoot = EnumSet.noneOf(OpSource.class);

    public GameSettings(Mode mode) {
        this.mode = mode;
        for (Setting s : Setting.values()) idx.put(s, s.def(mode));
    }

    public int index(Setting s) {
        Integer i = idx.get(s);
        return i == null ? s.def(mode) : i;
    }

    public int get(Setting s) {
        return s.values[index(s)];
    }

    public boolean on(Setting s) {
        return get(s) != 0;
    }

    public String label(Setting s) {
        return s.labels[index(s)];
    }

    public void set(Setting s, int index) {
        if (index < 0 || index >= s.labels.length) return;
        idx.put(s, index);
    }

    public void cycle(Setting s, int dir) {
        int n = s.labels.length;
        idx.put(s, ((index(s) + dir) % n + n) % n);
    }

    public boolean isDefault(Setting s) {
        return index(s) == s.def(mode);
    }

    /** Settings that "randomize" and Surprise Settings get to roll (only the ones in this mode are used). */
    private static final Setting[] RANDOM = {
            Setting.MAP_SIZE, Setting.DROP_FIRST, Setting.DROP_INTERVAL, Setting.DROP_SIZE, Setting.GRACE,
            Setting.CRAFTING, Setting.WEAPON_CRAFTING, Setting.SHARE_ITEMS, Setting.NETHER, Setting.MOB_LOOT,
            Setting.SPAWN_EGGS, Setting.SHRINKING_BORDER, Setting.HEIGHT_LIMIT, Setting.DIG_LIMIT, Setting.GROUND_SWEEP,
            Setting.EAT_EFFECT, Setting.DAMAGE_EFFECT, Setting.RANDOM_ENCHANTS,
            Setting.SWAP_DELAY, Setting.PVP, Setting.LIVES, Setting.KEEP_INVENTORY, Setting.FIRE_RES, Setting.FALL_RES,
            Setting.NETHER_ENTRY_RES, Setting.VEIN_MINER, Setting.AUTO_SMELT, Setting.AUTO_COOK, Setting.RANDOM_OP,
            Setting.ROUND_TIME, Setting.ELIMINATION_MODE, Setting.SAME_BLOCK, Setting.BS_FORMAT, Setting.RANDOM_DROPS,
            Setting.HEAD_START, Setting.HITMEN_FREEZE, Setting.GLOWING, Setting.HUNTER_RESPAWN, Setting.NETHER_FIRST,
            Setting.INCREASED_LUCK, Setting.HUNTER_KEEP_INV, Setting.OP_ENCHANTING, Setting.OP_SHEARING, Setting.JUGGERNAUT,
            Setting.UHC, Setting.AGGRO_MOBS, Setting.SNEAK_INVIS, Setting.INFECTION, Setting.ELYTRA_RACE, Setting.TINY,
            Setting.STRUCTURE_MANIA, Setting.LUCKY_BLOCK, Setting.RANDOM_CRAFTS, Setting.MORE_DEATHS, Setting.SPAWN_MODE,
            Setting.STARTER_KIT, Setting.BINGO_ELYTRA, Setting.CARD_SIZE};

    /** Rolls the fun settings. Height and dig limits only show up sometimes so most games are normal. */
    public void randomize() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Setting st : RANDOM) {
            if (!st.in(mode)) continue;
            if ((st == Setting.HEIGHT_LIMIT || st == Setting.DIG_LIMIT) && r.nextDouble() < 0.75) {
                set(st, 0);
                continue;
            }
            set(st, r.nextInt(st.labels.length));
        }
        if (mode.hasOpLoot()) {
            // a few random OP loot twists
            opLoot.clear();
            List<OpSource> all = new ArrayList<>();
            for (OpSource o : OpSource.values()) if (o.in(mode)) all.add(o);
            int n = r.nextInt(4);
            for (int i = 0; i < n; i++) opLoot.add(all.get(r.nextInt(all.size())));
        }
    }

    public GameSettings copy() {
        GameSettings c = new GameSettings(mode);
        c.copyFrom(this);
        return c;
    }

    public void copyFrom(GameSettings o) {
        idx.clear();
        idx.putAll(o.idx);
        itemBlacklist.clear();
        itemBlacklist.addAll(o.itemBlacklist);
        potionBlacklist.clear();
        potionBlacklist.addAll(o.potionBlacklist);
        opLoot.clear();
        opLoot.addAll(o.opLoot);
    }

    public void save(ConfigurationSection sec) {
        sec.set("mode", mode.name());
        for (Setting s : Setting.values()) sec.set("settings." + s.name(), index(s));
        sec.set("blacklist", new ArrayList<>(itemBlacklist));
        sec.set("potion-blacklist", new ArrayList<>(potionBlacklist));
        List<String> ops = new ArrayList<>();
        for (OpSource o : opLoot) ops.add(o.name());
        sec.set("op-loot", ops);
    }

    public static GameSettings load(ConfigurationSection sec) {
        Mode mode = Mode.RIC;
        if (sec != null) {
            try {
                mode = Mode.valueOf(sec.getString("mode", "RIC"));
            } catch (IllegalArgumentException ignored) {
            }
        }
        GameSettings g = new GameSettings(mode);
        if (sec == null) return g;
        for (Setting s : Setting.values()) {
            if (sec.contains("settings." + s.name())) g.set(s, sec.getInt("settings." + s.name()));
        }
        g.itemBlacklist.addAll(sec.getStringList("blacklist"));
        g.potionBlacklist.addAll(sec.getStringList("potion-blacklist"));
        for (String n : sec.getStringList("op-loot")) {
            try {
                g.opLoot.add(OpSource.valueOf(n));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return g;
    }

    /** Lines for the "Current Game Settings" message. Only shows things that aren't default. */
    public List<String> summary() {
        return summary(true);
    }

    /** @param twists false leaves out the Twists tab (for hunters when Announce Twists is off) */
    public List<String> summary(boolean twists) {
        List<String> out = new ArrayList<>();
        out.add("§6Mode: §a" + mode.display);
        for (Setting s : Setting.values()) {
            if (!s.in(mode)) continue;
            if (!twists && s.cat == Setting.Cat.TWISTS) continue;
            if (s == Setting.MAP_SIZE || !isDefault(s)) {
                String col = label(s).equals("Off") || label(s).equals("None") ? "§c" : "§a";
                out.add("§6" + s.title(mode) + ": " + col + label(s));
            }
        }
        boolean lists = mode == Mode.RIC || mode.isHunt();
        if (!twists) return out;
        if (!itemBlacklist.isEmpty() && lists) out.add("§6Blacklisted Items: §a" + itemBlacklist.size());
        if (!potionBlacklist.isEmpty() && lists) out.add("§6Blacklisted Potions: §a" + potionBlacklist.size());
        if (!opLoot.isEmpty() && mode.hasOpLoot()) {
            StringBuilder sb = new StringBuilder();
            for (OpSource o : opLoot) {
                if (sb.length() > 0) sb.append("§7, §a");
                sb.append(o.display);
            }
            out.add("§6OP Loot: §a" + sb);
        }
        return out;
    }
}
