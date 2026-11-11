package xyz.foldedlettuce.lettucegames;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Player stats, kept per game mode so new modes just add their own section.
 * "all" combines every mode. Stored in stats.yml (easy to move to MariaDB later if we want stats across servers).
 */
public class Stats implements CommandExecutor {

    /** Raw counters saved per player per mode. */
    public enum Stat {
        GAMES, WINS, KILLS, DEATHS, TOP3, BEST_KILLS, WIN_STREAK, BEST_STREAK, TIME, DROPS, PLACE_SUM, BEST_TIME;

        /** these take the max across modes instead of adding up */
        boolean isMax() {
            return this == BEST_KILLS || this == BEST_STREAK || this == WIN_STREAK;
        }
    }

    /** Modes that exist so far. Add new ones here. */
    public static final Map<String, String> MODES = new LinkedHashMap<>();

    static {
        MODES.put("all", "All Games");
        MODES.put("ric", "Random Items Challenge");
        MODES.put("deathswap", "Death Swap");
        MODES.put("blockshuffle", "Block Shuffle");
        MODES.put("manhunt", "Manhunt");
        MODES.put("hitman", "Hitman");
        MODES.put("speedrun", "Speedrun");
        MODES.put("bingo", "Bingo");
        MODES.put("deathshuffle", "Death Shuffle");
    }

    public static class Entry {
        public String name;
        public final Map<String, Map<Stat, Long>> modes = new HashMap<>();

        public long get(String mode, Stat s) {
            if (mode.equals("all")) {
                long v = 0;
                for (Map<Stat, Long> m : modes.values()) {
                    long x = m.getOrDefault(s, 0L);
                    v = s.isMax() ? Math.max(v, x) : v + x;
                }
                return v;
            }
            Map<Stat, Long> m = modes.get(mode);
            return m == null ? 0 : m.getOrDefault(s, 0L);
        }

        void add(String mode, Stat s, long amt) {
            modes.computeIfAbsent(mode, k -> new HashMap<>()).merge(s, amt, Long::sum);
        }

        void set(String mode, Stat s, long v) {
            modes.computeIfAbsent(mode, k -> new HashMap<>()).put(s, v);
        }
    }

    /** What a leaderboard can be sorted by. */
    public enum Board {
        WINS("Wins", Material.GOLD_INGOT, 0, (e, m) -> e.get(m, Stat.WINS), false),
        KILLS("Kills", Material.IRON_SWORD, 0, (e, m) -> e.get(m, Stat.KILLS), false),
        WINRATE("Win %", Material.EMERALD, 3, (e, m) -> 100.0 * e.get(m, Stat.WINS) / Math.max(1, e.get(m, Stat.GAMES)), true),
        GAMES("Games Played", Material.MAP, 0, (e, m) -> e.get(m, Stat.GAMES), false),
        TOP3("Top 3 Finishes", Material.IRON_INGOT, 0, (e, m) -> e.get(m, Stat.TOP3), false),
        BEST_STREAK("Best Win Streak", Material.BLAZE_POWDER, 0, (e, m) -> e.get(m, Stat.BEST_STREAK), false),
        BEST_KILLS("Most Kills in a Game", Material.DIAMOND_SWORD, 0, (e, m) -> e.get(m, Stat.BEST_KILLS), false),
        TIME("Time Played", Material.CLOCK, 0, (e, m) -> e.get(m, Stat.TIME), false);

        public final String display;
        public final Material icon;
        public final int minGames;
        final java.util.function.ToDoubleBiFunction<Entry, String> value;
        public final boolean decimal;

        Board(String display, Material icon, int minGames, java.util.function.ToDoubleBiFunction<Entry, String> value, boolean decimal) {
            this.display = display;
            this.icon = icon;
            this.minGames = minGames;
            this.value = value;
            this.decimal = decimal;
        }

        public double of(Entry e, String mode) {
            return value.applyAsDouble(e, mode);
        }

        public String format(double v) {
            if (this == TIME) return Msg.time((int) v);
            if (this == WINRATE) return String.format(Locale.ROOT, "%.1f%%", v);
            if (decimal) return String.format(Locale.ROOT, "%.2f", v);
            return String.valueOf((long) v);
        }
    }

    private final LettuceGames plugin;
    private final File file;
    private final Map<UUID, Entry> data = new HashMap<>();
    private boolean dirty = false;

    public Stats(LettuceGames plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
        load();
        // save every 2 minutes if anything changed, refresh holograms every minute
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { if (dirty) save(); }, 2400L, 2400L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateHologramTexts, 100L, 1200L);
        // leaderboards flip through the modes that have been played
        long cycle = Math.max(0, plugin.getConfig().getInt("leaderboard-cycle-seconds", 8));
        if (cycle > 0) Bukkit.getScheduler().runTaskTimer(plugin, () -> { holoFrame++; updateHologramTexts(); }, 100L + cycle * 20L, cycle * 20L);
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection ps = y.getConfigurationSection("players");
        if (ps == null) return;
        for (String key : ps.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            ConfigurationSection p = ps.getConfigurationSection(key);
            if (p == null) continue;
            Entry e = new Entry();
            e.name = p.getString("name", "?");
            for (String mode : p.getKeys(false)) {
                if (mode.equals("name")) continue;
                ConfigurationSection m = p.getConfigurationSection(mode);
                if (m == null) continue;
                for (Stat s : Stat.values()) {
                    String k = s.name().toLowerCase(Locale.ROOT);
                    if (m.contains(k)) e.set(mode, s, m.getInt(k));
                }
            }
            data.put(id, e);
        }
    }

    /** After a game: save, refresh floating boards right away. */
    public void gameDone() {
        save();
        updateHologramTexts();
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Entry> en : data.entrySet()) {
            String base = "players." + en.getKey();
            y.set(base + ".name", en.getValue().name);
            for (Map.Entry<String, Map<Stat, Long>> m : en.getValue().modes.entrySet()) {
                for (Map.Entry<Stat, Long> s : m.getValue().entrySet()) {
                    y.set(base + "." + m.getKey() + "." + s.getKey().name().toLowerCase(Locale.ROOT), s.getValue());
                }
            }
        }
        dirty = false;
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Couldn't save stats.yml: " + ex.getMessage());
        }
    }

    public Entry entry(UUID id, String name) {
        Entry e = data.computeIfAbsent(id, k -> new Entry());
        if (name != null) e.name = name;
        return e;
    }

    public Entry find(String name) {
        for (Entry e : data.values()) if (e.name != null && e.name.equalsIgnoreCase(name)) return e;
        return null;
    }

    // ------------------------------------------------------------------ recording

    public void add(UUID id, String name, String mode, Stat s, long amt) {
        entry(id, name).add(mode, s, amt);
        dirty = true;
    }

    public void max(UUID id, String name, String mode, Stat s, long v) {
        Entry e = entry(id, name);
        if (v > e.get(mode, s)) e.set(mode, s, v);
        dirty = true;
    }

    /** Called once per player at the end of a counted game. */
    /** Speedrun personal best (lower is better). */
    public void bestTime(UUID id, String name, String mode, int seconds) {
        Entry e = entry(id, name);
        long cur = e.get(mode, Stat.BEST_TIME);
        if (cur <= 0 || seconds < cur) {
            e.set(mode, Stat.BEST_TIME, seconds);
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage("§b§l✦ New personal best: §f" + Msg.time(seconds) + (cur > 0 ? " §7(was " + Msg.time((int) cur) + ")" : ""));
        }
        dirty = true;
    }

    public void finishGame(UUID id, String name, String mode, boolean won, int placement, int kills, int seconds) {
        Entry e = entry(id, name);
        e.add(mode, Stat.TIME, seconds);
        if (placement > 0) {
            e.add(mode, Stat.PLACE_SUM, placement);
            if (placement <= 3) e.add(mode, Stat.TOP3, 1);
        }
        if (kills > e.get(mode, Stat.BEST_KILLS)) e.set(mode, Stat.BEST_KILLS, kills);
        if (won) {
            e.add(mode, Stat.WINS, 1);
            long streak = e.get(mode, Stat.WIN_STREAK) + 1;
            e.set(mode, Stat.WIN_STREAK, streak);
            if (streak > e.get(mode, Stat.BEST_STREAK)) e.set(mode, Stat.BEST_STREAK, streak);
        } else {
            e.set(mode, Stat.WIN_STREAK, 0);
        }
        dirty = true;
    }

    // ------------------------------------------------------------------ leaderboards

    public static class Row {
        public final UUID id;
        public final String name;
        public final double value;

        Row(UUID id, String name, double value) {
            this.id = id;
            this.name = name;
            this.value = value;
        }
    }

    public List<Row> top(Board b, String mode, int limit) {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<UUID, Entry> en : data.entrySet()) {
            Entry e = en.getValue();
            if (e.get(mode, Stat.GAMES) < Math.max(1, b.minGames)) continue;
            rows.add(new Row(en.getKey(), e.name, b.of(e, mode)));
        }
        rows.sort((a, c) -> Double.compare(c.value, a.value));
        return rows.size() > limit ? rows.subList(0, limit) : rows;
    }

    /** 1-based rank, or 0 if not on the board. */
    public int rank(UUID id, Board b, String mode) {
        List<Row> all = top(b, mode, Integer.MAX_VALUE);
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(id)) return i + 1;
        return 0;
    }

    // ------------------------------------------------------------------ /stats

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("leaderboard")) {
            if (sender instanceof Player p) plugin.menus().openLeaderboard(p, Board.WINS, "all");
            return true;
        }
        UUID id;
        Entry e;
        if (args.length > 0) {
            e = find(args[0]);
            if (e == null) {
                sender.sendMessage(Msg.SYS + "§cNo stats for " + args[0] + " yet.");
                return true;
            }
            id = null;
            for (Map.Entry<UUID, Entry> en : data.entrySet()) if (en.getValue() == e) id = en.getKey();
        } else if (sender instanceof Player p) {
            id = p.getUniqueId();
            e = entry(id, p.getName());
        } else {
            sender.sendMessage("Usage: /stats <player>");
            return true;
        }
        sender.sendMessage(" ");
        sender.sendMessage("§a§l" + e.name + "'s Stats");
        for (Map.Entry<String, String> mode : MODES.entrySet()) {
            String m = mode.getKey();
            long games = e.get(m, Stat.GAMES);
            if (games == 0 && !m.equals("all")) continue;
            sender.sendMessage("§6" + mode.getValue() + "§7:");
            sender.sendMessage("  §fWins §a" + e.get(m, Stat.WINS) + " §8• §fGames §a" + games
                    + " §8• §fWin % §a" + Board.WINRATE.format(Board.WINRATE.of(e, m)));
            sender.sendMessage("  §fKills §a" + e.get(m, Stat.KILLS) + " §8• §fDeaths §a" + e.get(m, Stat.DEATHS)
                    + "");
            long placeSum = e.get(m, Stat.PLACE_SUM);
            String avg = games == 0 ? "-" : String.format(Locale.ROOT, "%.1f", (double) placeSum / games);
            sender.sendMessage("  §fTop 3s §a" + e.get(m, Stat.TOP3) + " §8• §fAvg Place §a" + avg
                    + " §8• §fBest Kills §a" + e.get(m, Stat.BEST_KILLS));
            sender.sendMessage("  §fWin Streak §a" + e.get(m, Stat.WIN_STREAK) + " §7(best " + e.get(m, Stat.BEST_STREAK) + ")"
                    + " §8• §fTime §a" + Msg.time((int) e.get(m, Stat.TIME)));
            if (e.get(m, Stat.BEST_TIME) > 0) sender.sendMessage("  §fBest Speedrun §b" + Msg.time((int) e.get(m, Stat.BEST_TIME)));
            if (id != null && m.equals("all")) {
                int r = rank(id, Board.WINS, "all");
                if (r > 0) sender.sendMessage("  §7Rank §e#" + r + " §7in wins");
            }
        }
        sender.sendMessage(" ");
        return true;
    }

    // ------------------------------------------------------------------ waiting area sidebar

    public void lobbyBoard(Player p) {
        Entry e = entry(p.getUniqueId(), p.getName());
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective o = sb.registerNewObjective("lgstats", "dummy", "§a§lLETTUCE GAMES");
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        try {
            o.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank());
        } catch (Throwable ignored) {
        }
        List<String> lines = new ArrayList<>();
        lines.add(" ");
        lines.add("§6§lYour Stats");
        lines.add("§fWins: §a" + e.get("all", Stat.WINS));
        lines.add("§fKills: §a" + e.get("all", Stat.KILLS));
        lines.add("§fTop 3s: §a" + e.get("all", Stat.TOP3));
        lines.add("§fGames: §a" + e.get("all", Stat.GAMES));
        lines.add("§fWin Streak: §a" + e.get("all", Stat.WIN_STREAK));
        lines.add("  ");
        int r = rank(p.getUniqueId(), Board.WINS, "all");
        lines.add("§fRank: " + (r > 0 ? "§e#" + r : "§7unranked"));
        lines.add("   ");
        lines.add("§e/lb §7leaderboards");
        lines.add("§aplay.foldedlettuce.xyz");
        ChatColor[] cc = ChatColor.values();
        for (int i = 0; i < lines.size(); i++) {
            String entry = cc[i].toString() + "§r";
            Team t = sb.registerNewTeam("l" + i);
            t.addEntry(entry);
            t.setPrefix(lines.get(i));
            o.getScore(entry).setScore(lines.size() - i);
        }
        p.setScoreboard(sb);
    }

    // ------------------------------------------------------------------ holograms

    private static final String HOLO_TAG = "lg_leaderboard";

    public void addHologram(Location l, Board b) {
        List<String> list = new ArrayList<>(plugin.getConfig().getStringList("leaderboard-holograms"));
        list.add(l.getWorld().getName() + "," + l.getX() + "," + (l.getY() + 2.2) + "," + l.getZ() + "," + b.name());
        plugin.getConfig().set("leaderboard-holograms", list);
        plugin.saveConfig();
        refreshHolograms();
    }

    /** Removes holograms within 4 blocks. Returns how many. */
    public int removeHologramsNear(Location l) {
        List<String> keep = new ArrayList<>();
        int removed = 0;
        for (String s : plugin.getConfig().getStringList("leaderboard-holograms")) {
            Location h = parse(s);
            if (h != null && h.getWorld().equals(l.getWorld()) && h.distanceSquared(l) < 36) removed++;
            else keep.add(s);
        }
        plugin.getConfig().set("leaderboard-holograms", keep);
        plugin.saveConfig();
        refreshHolograms();
        return removed;
    }

    private Location parse(String s) {
        String[] p = s.split(",");
        if (p.length < 5) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int holoFrame = 0;

    /** "All Games" first, then every mode somebody has actually played. */
    private List<String> playedModes() {
        List<String> out = new ArrayList<>();
        out.add("all");
        for (String m : MODES.keySet()) {
            if (m.equals("all")) continue;
            for (Entry e : data.values()) {
                if (e.get(m, Stat.GAMES) > 0) {
                    out.add(m);
                    break;
                }
            }
        }
        return out;
    }

    private String holoText(Board b, List<String> modes) {
        String mode = modes.get(Math.floorMod(holoFrame, modes.size()));
        StringBuilder text = new StringBuilder("§6§lTOP " + b.display.toUpperCase(Locale.ROOT) + "\n§7" + MODES.get(mode));
        if (modes.size() > 1) text.append(" §8(").append(modes.indexOf(mode) + 1).append("/").append(modes.size()).append(")");
        text.append("\n");
        List<Row> rows = top(b, mode, 10);
        if (rows.isEmpty()) text.append("\n§7No data yet");
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            String c = i == 0 ? "§6" : i == 1 ? "§f" : i == 2 ? "§c" : "§7";
            text.append("\n").append(c).append(i + 1).append(". §f").append(r.name).append(" §8- §e").append(b.format(r.value));
        }
        return text.toString();
    }

    private static Board holoBoard(Iterable<String> tags) {
        for (String tg : tags) {
            if (tg.startsWith("lgb_")) {
                try {
                    return Board.valueOf(tg.substring(4));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return null;
    }

    /** Rewrites the text on the floating boards in place (no flicker). Respawns them if any went missing. */
    public void updateHologramTexts() {
        int want = 0;
        for (String s : plugin.getConfig().getStringList("leaderboard-holograms")) if (parse(s) != null) want++;
        List<TextDisplay> have = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            for (TextDisplay td : w.getEntitiesByClass(TextDisplay.class)) {
                if (td.getScoreboardTags().contains(HOLO_TAG)) have.add(td);
            }
        }
        if (have.size() != want) {
            refreshHolograms();
            return;
        }
        List<String> modes = playedModes();
        for (TextDisplay td : have) {
            Board b = holoBoard(td.getScoreboardTags());
            if (b != null) td.setText(holoText(b, modes));
        }
    }

    public void refreshHolograms() {
        // clear old ones, then spawn fresh (they're not saved with the world)
        for (World w : Bukkit.getWorlds()) {
            for (TextDisplay td : w.getEntitiesByClass(TextDisplay.class)) {
                if (td.getScoreboardTags().contains(HOLO_TAG)) td.remove();
            }
        }
        List<String> modes = playedModes();
        for (String s : plugin.getConfig().getStringList("leaderboard-holograms")) {
            Location l = parse(s);
            if (l == null) continue;
            Board b;
            try {
                b = Board.valueOf(s.substring(s.lastIndexOf(',') + 1));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            l.getWorld().addPluginChunkTicket(l.getBlockX() >> 4, l.getBlockZ() >> 4, plugin);
            TextDisplay td = l.getWorld().spawn(l, TextDisplay.class);
            td.setText(holoText(b, modes));
            td.setBillboard(Display.Billboard.CENTER);
            td.setPersistent(false);
            td.addScoreboardTag(HOLO_TAG);
            td.addScoreboardTag("lgb_" + b.name());
        }
    }
}
