package xyz.foldedlettuce.lettucegames;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** One Bingo card and everyone's progress on it. */
public class Bingo {
    public enum Type { ITEM, KILL, EAT, DIE, ADV, BIOME, OPP, LEVEL }

    public static class Task {
        public final Type type;
        public final String key;
        public final String display;
        public final Material icon;
        public final String cat;
        public final String family;
        public final boolean nether, biome;

        Task(Type type, String key, String display, Material icon, String cat, String family, boolean nether, boolean biome) {
            this.type = type;
            this.key = key;
            this.display = display;
            this.icon = icon;
            this.cat = cat;
            this.family = family;
            this.nether = nether;
            this.biome = biome;
        }
    }

    public static final String CARD_NAME = "§e§lBingo Card §7(Right-click)";

    private final LettuceGames plugin;
    private final Match m;
    public final int size;
    public final Task[] card;
    public final int mode; // 0 standard, 1 lockout, 2 single
    /** standard/single: tiles each side has done */
    public final Map<String, Set<Integer>> done = new LinkedHashMap<>();
    /** lockout: who claimed each tile */
    public final String[] owner;
    public final List<String> finishers = new ArrayList<>();

    public Bingo(LettuceGames plugin, Match m, int sides) {
        this.plugin = plugin;
        this.m = m;
        this.size = m.s.get(Setting.CARD_SIZE);
        this.mode = m.s.get(Setting.BINGO_MODE);
        this.card = generate(sides);
        this.owner = new String[card.length];
    }

    // ------------------------------------------------------------------ card generation

    private List<Task> pool(int sides) {
        boolean nether = m.s.on(Setting.NETHER);
        boolean biomes = m.s.on(Setting.BIOME_TASKS);
        boolean opps = m.s.on(Setting.OPPONENT_TASKS) && sides >= 2;
        boolean pvp = m.s.on(Setting.PVP);
        List<Task> out = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("bingo.tasks")) {
            Task t = parse(line);
            if (t == null) continue;
            if (t.nether && !nether) continue;
            if (t.biome && !biomes) continue;
            if (t.type == Type.OPP && (!opps || (t.key.equals("KILL") && !pvp))) continue;
            out.add(t);
        }
        return out;
    }

    /** "TYPE KEY [flags...] | Display". Flags: nether, biome, fam=x, cat=x, icon=MATERIAL */
    private static Task parse(String line) {
        try {
            String[] halves = line.split("\\|", 2);
            String[] tok = halves[0].trim().split("\\s+");
            Type type = Type.valueOf(tok[0].toUpperCase(Locale.ROOT));
            String key = tok[1];
            boolean nether = false, biome = type == Type.BIOME;
            String fam = null, cat = switch (type) {
                case EAT -> "eat";
                case DIE -> "death";
                case OPP -> "opp";
                default -> "other";
            };
            Material icon = null;
            for (int i = 2; i < tok.length; i++) {
                String f = tok[i];
                if (f.equalsIgnoreCase("nether")) nether = true;
                else if (f.equalsIgnoreCase("biome")) biome = true;
                else if (f.startsWith("fam=")) fam = f.substring(4);
                else if (f.startsWith("cat=")) cat = f.substring(4);
                else if (f.startsWith("icon=")) icon = Material.matchMaterial(f.substring(5));
            }
            String display = halves.length > 1 ? halves[1].trim() : null;
            switch (type) {
                case ITEM, EAT -> {
                    Material mat = Material.matchMaterial(key);
                    if (mat == null || !mat.isItem()) return null;
                    key = mat.name();
                    if (icon == null) icon = mat;
                    if (display == null) display = (type == Type.EAT ? "Eat " : "Get ") + aOrAn(Msg.pretty(mat));
                }
                case KILL -> {
                    key = key.toUpperCase(Locale.ROOT);
                    org.bukkit.entity.EntityType.valueOf(key); // throws if this version doesn't have it
                    if (icon == null) icon = Material.matchMaterial(key + "_SPAWN_EGG");
                    if (display == null) display = "Kill " + aOrAn(Msg.pretty(key));
                }
                case DIE -> {
                    Death d = Death.valueOf(key.toUpperCase(Locale.ROOT));
                    key = d.name();
                    if (icon == null) icon = d.icon;
                    if (display == null) display = "Die: " + d.display;
                }
                case ADV -> {
                    if (Bukkit.getAdvancement(NamespacedKey.minecraft(key)) == null) return null;
                    if (display == null) display = Msg.pretty(key.substring(key.indexOf('/') + 1).toUpperCase(Locale.ROOT));
                    if (icon == null) icon = Material.KNOWLEDGE_BOOK;
                }
                case BIOME -> {
                    key = key.toLowerCase(Locale.ROOT);
                    if (display == null) display = "Visit " + aOrAn(Msg.pretty(key.toUpperCase(Locale.ROOT))) + " biome";
                    if (icon == null) icon = Material.FILLED_MAP;
                }
                case LEVEL -> {
                    Integer.parseInt(key);
                    if (display == null) display = "Reach level " + key;
                    if (icon == null) icon = Material.EXPERIENCE_BOTTLE;
                }
                case OPP -> {
                    key = key.toUpperCase(Locale.ROOT);
                    if (icon == null) icon = Material.IRON_SWORD;
                    if (display == null) display = Msg.pretty(key);
                }
            }
            if (icon == null) icon = Material.PAPER;
            return new Task(type, key, display, icon, cat, fam, nether, biome);
        } catch (Throwable ex) {
            return null;
        }
    }

    private static String aOrAn(String word) {
        if (word.endsWith("s") && !word.endsWith("ss")) return word;
        return ("AEIOU".indexOf(Character.toUpperCase(word.charAt(0))) >= 0 ? "an " : "a ") + word;
    }

    /** Category limits: 4 eating, 4 death, 3 opponent, 1 enchanting, one per family. At least one death. */
    private Task[] generate(int sides) {
        List<Task> pool = pool(sides);
        Collections.shuffle(pool);
        int n = size * size;
        Map<String, Integer> limits = Map.of("eat", 4, "death", 4, "opp", 3, "enchant", 1);
        Map<String, Integer> used = new HashMap<>();
        Set<String> fams = new HashSet<>();
        Set<String> keys = new HashSet<>();
        List<Task> picked = new ArrayList<>();
        // one death first
        for (Task t : pool) {
            if (t.cat.equals("death")) {
                picked.add(t);
                used.merge("death", 1, Integer::sum);
                keys.add(t.type + ":" + t.key);
                break;
            }
        }
        for (Task t : pool) {
            if (picked.size() >= n) break;
            if (!keys.add(t.type + ":" + t.key)) continue;
            Integer lim = limits.get(t.cat);
            if (lim != null && used.getOrDefault(t.cat, 0) >= lim) continue;
            if (t.family != null && !fams.add(t.family)) continue;
            used.merge(t.cat, 1, Integer::sum);
            picked.add(t);
        }
        // tiny pool fallback so a card always fills
        while (picked.size() < n && !pool.isEmpty()) picked.add(pool.get(ThreadLocalRandom.current().nextInt(pool.size())));
        Collections.shuffle(picked);
        return picked.toArray(new Task[0]);
    }

    // ------------------------------------------------------------------ progress

    public boolean has(String side, int i) {
        if (mode == 1) return side.equals(owner[i]);
        Set<Integer> d = done.get(side);
        return d != null && d.contains(i);
    }

    /** Something happened that might tick a tile. */
    public void progress(Player p, Type type, String key) {
        if (m.ended || m.loading || !m.alive.contains(p.getUniqueId()) || !m.bingoLive()) return;
        String side = m.sideKey(p.getUniqueId());
        if (finishers.contains(side)) return;
        for (int i = 0; i < card.length; i++) {
            Task t = card[i];
            if (t.type != type || !t.key.equals(key)) continue;
            if (mode == 1 ? owner[i] != null : has(side, i)) continue;
            complete(side, i, p);
            if (m.ended) return;
        }
    }

    private void complete(String side, int i, Player who) {
        if (mode == 1) owner[i] = side;
        else done.computeIfAbsent(side, k -> new HashSet<>()).add(i);
        Task t = card[i];
        m.broadcast(m.tag() + "§a✔ " + m.sideName(side) + " §7completed §f" + t.display
                + (mode == 1 ? " §8(§6" + score(side) + " pts§8)" : " §8(" + count(side) + "/" + card.length + ")"));
        for (Player v : m.viewers()) {
            boolean mine = m.alive.contains(v.getUniqueId()) && m.sideKey(v.getUniqueId()).equals(side);
            if (mine) v.playSound(v.getLocation(), "minecraft:ui.toast.challenge_complete", 0.7f, 1.2f);
            else v.playSound(v.getLocation(), "minecraft:block.note_block.chime", 0.6f, 1.4f);
        }
        if (who != null) who.sendTitle("", "§a✔ " + t.display, 0, 30, 10);
        plugin.menus().refreshBingo();
        checkWin(side);
    }

    public int count(String side) {
        if (mode == 1) {
            int c = 0;
            for (String o : owner) if (side.equals(o)) c++;
            return c;
        }
        Set<Integer> d = done.get(side);
        return d == null ? 0 : d.size();
    }

    /** Rows, columns and both diagonals this side owns completely. */
    public int lines(String side) {
        int c = 0;
        for (int r = 0; r < size; r++) {
            boolean row = true, col = true;
            for (int k = 0; k < size; k++) {
                if (!has(side, r * size + k)) row = false;
                if (!has(side, k * size + r)) col = false;
            }
            if (row) c++;
            if (col) c++;
        }
        boolean d1 = true, d2 = true;
        for (int k = 0; k < size; k++) {
            if (!has(side, k * size + k)) d1 = false;
            if (!has(side, k * size + (size - 1 - k))) d2 = false;
        }
        if (d1) c++;
        if (d2) c++;
        return c;
    }

    public int score(String side) {
        if (mode != 1) return count(side);
        return count(side) + lines(side) * m.s.get(Setting.LINE_BONUS);
    }

    private void checkWin(String side) {
        if (mode == 2) {
            m.end(side, "First task done!");
            return;
        }
        if (mode == 1) {
            int claimed = 0;
            for (String o : owner) if (o != null) claimed++;
            boolean majorityOn = m.s.get(Setting.LOCKOUT_END) == 0;
            if (majorityOn && count(side) * 2 > card.length) {
                m.end(side, "Claimed the majority of the board!");
                return;
            }
            if (claimed == card.length) {
                lockoutResult("The board is full!");
                return;
            }
            // line bonus: stop once the leader can't be caught
            if (majorityOn && m.s.get(Setting.LINE_BONUS) > 0) {
                String lead = leader();
                int left = card.length - claimed;
                boolean safe = lead != null;
                for (String o : sides()) {
                    if (o.equals(lead)) continue;
                    int best = score(o) + left + maxNewLines(o) * m.s.get(Setting.LINE_BONUS);
                    if (best >= score(lead)) safe = false;
                }
                if (safe) m.end(lead, "Nobody can catch them!");
            }
            return;
        }
        boolean full = m.s.get(Setting.WIN_CONDITION) == 1;
        boolean finished = full ? count(side) == card.length : lines(side) > 0;
        if (!finished || finishers.contains(side)) return;
        finishers.add(side);
        int target = m.s.get(Setting.BINGO_END);
        m.broadcast(m.tag() + "§6§lBINGO! " + m.sideName(side) + " §7finished " + ordinal(finishers.size()) + "!");
        int remaining = m.liveSides().size() - 1; // minus this one
        if (target == 1 || (target > 0 && finishers.size() >= target) || (target < 0 && remaining <= 1) || remaining <= 0) {
            m.end(finishers.get(0), "BINGO!");
            return;
        }
        m.finishSide(side);
    }

    private static String ordinal(int n) {
        return n + (n == 1 ? "st" : n == 2 ? "nd" : n == 3 ? "rd" : "th");
    }

    /** Lines this side could still finish with tiles nobody else has. */
    private int maxNewLines(String side) {
        int c = 0;
        List<int[]> all = new ArrayList<>();
        for (int r = 0; r < size; r++) {
            int[] row = new int[size], col = new int[size];
            for (int k = 0; k < size; k++) { row[k] = r * size + k; col[k] = k * size + r; }
            all.add(row);
            all.add(col);
        }
        int[] d1 = new int[size], d2 = new int[size];
        for (int k = 0; k < size; k++) { d1[k] = k * size + k; d2[k] = k * size + (size - 1 - k); }
        all.add(d1);
        all.add(d2);
        for (int[] line : all) {
            boolean open = false, blocked = false;
            for (int i : line) {
                if (owner[i] == null) open = true;
                else if (!owner[i].equals(side)) blocked = true;
            }
            if (open && !blocked) c++;
        }
        return c;
    }

    public List<String> sides() {
        List<String> out = new ArrayList<>(m.liveSides());
        for (String s : done.keySet()) if (!out.contains(s)) out.add(s);
        for (String o : owner) if (o != null && !out.contains(o)) out.add(o);
        return out;
    }

    private String leader() {
        String best = null;
        int bs = -1;
        for (String s : sides()) {
            int sc = score(s);
            if (sc > bs) { bs = sc; best = s; }
        }
        return best;
    }

    private void lockoutResult(String reason) {
        List<String> top = new ArrayList<>();
        int bs = -1;
        for (String s : sides()) {
            int sc = score(s);
            if (sc > bs) { bs = sc; top.clear(); top.add(s); } else if (sc == bs) top.add(s);
        }
        if (bs <= 0) m.end(null, reason + " Nobody scored.");
        else if (top.size() == 1) m.end(top.get(0), reason);
        else m.endTie(top, reason + " It's a tie!");
    }

    /** Time ran out: most tiles (or points) wins. */
    public void timeUp() {
        if (!finishers.isEmpty()) {
            m.end(finishers.get(0), "Time's up!");
            return;
        }
        lockoutResult("Time's up!");
    }

    // ------------------------------------------------------------------ once a second: inventory, biome and level tasks

    public void tick() {
        if (!m.bingoLive()) return;
        for (UUID id : new ArrayList<>(m.alive)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || m.respawning.contains(id)) continue;
            Set<String> have = new HashSet<>();
            for (ItemStack it : p.getInventory().getContents()) if (it != null) have.add(it.getType().name());
            for (int i = 0; i < card.length && !m.ended; i++) {
                Task t = card[i];
                switch (t.type) {
                    case ITEM -> { if (have.contains(t.key)) progress(p, Type.ITEM, t.key); }
                    case LEVEL -> { if (p.getLevel() >= Integer.parseInt(t.key)) progress(p, Type.LEVEL, t.key); }
                    case BIOME -> {
                        try {
                            String b = p.getWorld().getBiome(p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ()).getKey().getKey();
                            if (b.equals(t.key)) progress(p, Type.BIOME, t.key);
                        } catch (Throwable ignored) {
                        }
                    }
                    case OPP -> {
                        if (t.key.equals("OUTLEVEL") && p.getLevel() >= 5) {
                            boolean beats = true, any = false;
                            for (UUID o : m.alive) {
                                if (m.sideKey(o).equals(m.sideKey(id))) continue;
                                Player op = Bukkit.getPlayer(o);
                                if (op == null) continue;
                                any = true;
                                if (op.getLevel() >= p.getLevel()) beats = false;
                            }
                            if (any && beats) progress(p, Type.OPP, "OUTLEVEL");
                        }
                    }
                    default -> {
                    }
                }
            }
            if (m.ended) return;
        }
    }

    // ------------------------------------------------------------------ the card menu

    public static ItemStack cardItem() {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(CARD_NAME);
        meta.setLore(List.of("§7Right-click or §e/bingocard §7to see", "§7the card and who has what."));
        it.setItemMeta(meta);
        return it;
    }

    public static boolean isCard(ItemStack it) {
        return it != null && it.getType() == Material.PAPER && it.hasItemMeta() && CARD_NAME.equals(it.getItemMeta().getDisplayName());
    }

    /** Draws the card for this viewer into a size*9 inventory. */
    public void render(Menus.Menu menu, Player v) {
        String mine = m.alive.contains(v.getUniqueId()) || m.participants.contains(v.getUniqueId()) ? m.sideKey(v.getUniqueId()) : null;
        int offset = (9 - size) / 2;
        for (int i = 0; i < card.length; i++) {
            Task t = card[i];
            int slot = (i / size) * 9 + offset + (i % size);
            List<String> lore = new ArrayList<>();
            String name;
            Material icon = t.icon;
            boolean glow = false;
            if (mode == 1) {
                String o = owner[i];
                if (o == null) {
                    name = "§f" + t.display;
                    lore.add("§7Not claimed yet");
                } else if (o.equals(mine)) {
                    name = "§a✔ " + t.display;
                    lore.add("§aYou claimed this");
                    glow = true;
                } else {
                    name = "§c✖ " + t.display;
                    lore.add("§7Claimed by " + m.sideName(o));
                    icon = m.sideGlass(o);
                }
            } else {
                boolean me = mine != null && has(mine, i);
                name = (me ? "§a✔ " : "§f") + t.display;
                glow = me;
                List<String> others = new ArrayList<>();
                for (String s : done.keySet()) if (!s.equals(mine) && done.get(s).contains(i)) others.add(m.sideName(s));
                lore.add(me ? "§aDone!" : "§7Not done yet");
                if (!others.isEmpty()) lore.add("§7Also done by: " + String.join("§7, ", others));
            }
            lore.add("");
            lore.add("§8" + t.type.name().toLowerCase(Locale.ROOT) + (t.nether ? " • nether" : "") + (t.biome ? " • biome" : ""));
            ItemStack it = Menus.item(icon, name, lore);
            menu.inv.setItem(slot, glow ? Menus.glowItem(it) : it);
        }
    }

    public String title(Player v) {
        String side = m.participants.contains(v.getUniqueId()) ? m.sideKey(v.getUniqueId()) : null;
        String what = mode == 1 ? "Lockout" : mode == 2 ? "Single" : "Bingo";
        if (side == null) return "§8" + what + " Card";
        return "§8" + what + " Card §7(" + (mode == 1 ? score(side) + " pts" : count(side) + "/" + card.length) + ")";
    }
}
