package xyz.foldedlettuce.lettucegames;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** One running game, any mode. */
public class Match {
    public static final String COMPASS_NAME = "§c§lTracker §7(Right-click)";

    private final LettuceGames plugin;
    public final Party party;
    public final GameSettings s;
    public final Mode mode;
    public final String id;
    public final long seed;
    public final int radius;

    public World world;
    public World nether;

    public final Set<UUID> participants = new LinkedHashSet<>();
    public final Set<UUID> alive = new LinkedHashSet<>();
    public final Set<UUID> spectators = new LinkedHashSet<>();
    /** null map entry = free for all */
    public final Map<UUID, TeamColor> teams = new HashMap<>();
    public final Map<UUID, Integer> kills = new HashMap<>();

    // ---------- death swap state ----------
    public final Map<UUID, Integer> lives = new HashMap<>();
    /** who's spot each player got on the last swap, for trap kill credit */
    private final Map<UUID, UUID> swappedInto = new HashMap<>();
    private int lastSwapAt = -1000;
    public int nextSwap;
    public int swapCount = 0;
    public final Set<OpSource> opActive = java.util.EnumSet.noneOf(OpSource.class);
    /** Random Drops twist: each block/mob type always drops the same random item this game */
    private final Map<String, Material> randomDrops = new HashMap<>();

    public Material randomDrop(String key, List<Material> pool) {
        return randomDrops.computeIfAbsent(key, k -> pool.get(ThreadLocalRandom.current().nextInt(pool.size())));
    }
    private int nextRandomOp = -1;
    /** overflow item entities from drops, so pickup rules know them apart from thrown items */
    public final Set<UUID> dropItems = new HashSet<>();
    public final Set<UUID> deathItems = new HashSet<>();
    /** finishing place for eliminated players, and the second they went out */
    private final Map<UUID, Integer> placement = new HashMap<>();
    private final Map<UUID, Integer> outAt = new HashMap<>();
    /** stats only count real games (2+ sides) */
    private boolean counted = false;
    public final Map<UUID, Set<String>> dropHistory = new HashMap<>();
    public final Map<UUID, UUID> trackLock = new HashMap<>();
    public final Map<UUID, UUID> lastDamager = new HashMap<>();
    public final Map<UUID, Long> lastDamageAt = new HashMap<>();
    public final Map<UUID, Long> effectCooldown = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, Location> lastPointed = new HashMap<>();

    private int initialSides;
    public int elapsed = 0;
    public int nextDrop;
    public int dropCount = 0;
    public boolean ended = false;
    private double borderSize;
    private int borderPhase = 0;
    private int borderNextAt = -1;
    private BukkitTask secondTask, trackerTask;
    private Scoreboard board;
    private Objective obj;

    /** Who's playing: everyone in the party, or with parties off, whoever was ready when the countdown hit 0. */
    public final Set<UUID> roster = new LinkedHashSet<>();

    public Match(LettuceGames plugin, Party party) {
        this.plugin = plugin;
        this.party = party;
        if (party.everyone) roster.addAll(party.ready);
        else roster.addAll(party.members);
        this.s = party.settings.copy();
        if (s.on(Setting.SURPRISE)) s.randomize();
        this.mode = s.mode;
        opActive.addAll(s.opLoot);
        this.id = Long.toString(System.currentTimeMillis() % 1_000_000_000L, 36);
        this.seed = ThreadLocalRandom.current().nextLong();
        this.radius = s.get(Setting.MAP_SIZE);
    }

    public String worldName() {
        return world != null ? world.getName() : plugin.worldPrefix() + id;
    }

    public boolean owns(World w) {
        return w != null && (w.equals(world) || w.equals(nether) || w.equals(end));
    }

    public boolean isGrace() {
        return elapsed < s.get(Setting.GRACE);
    }

    public TeamColor teamOf(UUID id) {
        return teams.get(id);
    }

    public boolean sameTeam(UUID a, UUID b) {
        TeamColor ta = teams.get(a), tb = teams.get(b);
        return ta != null && ta == tb;
    }

    public String name(UUID id) {
        String n = names.get(id);
        if (n != null) return n;
        Player p = Bukkit.getPlayer(id);
        return p == null ? "?" : p.getName();
    }

    public String colored(UUID id) {
        TeamColor t = teams.get(id);
        return (t == null ? "§f" : t.code) + name(id);
    }

    public void broadcast(String text) {
        for (Player p : viewers()) p.sendMessage(text);
    }

    /** Everyone currently in this match's worlds (alive + spectators). */
    public List<Player> viewers() {
        List<Player> out = new ArrayList<>();
        Set<UUID> ids = new LinkedHashSet<>(alive);
        ids.addAll(spectators);
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ start

    /** true while the world is still pre-generating and nobody's been teleported yet */
    public boolean loading = true;
    private Location center;
    private BukkitTask loadTask;

    /** Creates the world, finds dry land, then pre-generates the area around spawn before anyone goes in. */
    public boolean start() {
        if (roster.isEmpty()) return false;

        MapPool.Ready readyMap = plugin.maps().take();
        if (readyMap != null) {
            world = readyMap.world;
            party.broadcast("§aMap's ready, sending you in!");
            afterLand(readyMap.center, true);
            return true;
        }

        party.broadcast("§eNo map ready yet, making one, hang tight...");
        WorldCreator wc = new WorldCreator(worldName());
        wc.seed(seed);
        wc.environment(World.Environment.NORMAL);
        world = Bukkit.createWorld(wc);
        if (world == null) {
            party.broadcast("§cCouldn't create the game world. Tell FoldedLettuce to check the console.");
            return false;
        }
        loadTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : party.online()) p.sendActionBar("§eFinding a good spawn...");
        }, 0L, 20L);
        ChunkWork.findLand(plugin, world, loc -> {
            if (loadTask != null) loadTask.cancel();
            if (ended) return;
            if (loc == null) {
                party.broadcast("§cThat map was all ocean, rolling another one...");
                World bad = world;
                File f = bad.getWorldFolder();
                Bukkit.unloadWorld(bad, false);
                plugin.deleteFolder(f);
                WorldCreator wc2 = new WorldCreator(plugin.worldPrefix() + id + "b");
                wc2.seed(ThreadLocalRandom.current().nextLong());
                wc2.environment(World.Environment.NORMAL);
                world = Bukkit.createWorld(wc2);
                if (world == null) { end(null, "Couldn't make a map."); return; }
                ChunkWork.findLand(plugin, world, loc2 -> {
                    if (ended) return;
                    afterLand(loc2 != null ? loc2 : world.getSpawnLocation(), false);
                });
                return;
            }
            afterLand(loc, false);
        });
        return true;
    }

    /** Spawn is picked: set up the border and pre-load the area, then teleport everyone in. */
    private void afterLand(Location spot, boolean instant) {
        center = spot;
        world.setAutoSave(false);
        borderSize = mode == Mode.DEATHSHUFFLE ? 59_999_968 : radius * 2.0;
        setupBorder(world, borderSize);
        world.setSpawnLocation(center);
        // fresh day every game
        world.setTime(1000);
        world.setStorm(false);
        world.setThundering(false);
        try {
            world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, !s.on(Setting.DAYLIGHT));
        } catch (Throwable ignored) {
        }

        int r = Math.max(2, plugin.getConfig().getInt("preload-radius-blocks", 128) / 16);
        int cx = center.getBlockX() >> 4, cz = center.getBlockZ() >> 4;
        // keep the spawn area loaded until everyone's in
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) world.addPluginChunkTicket(cx + dx, cz + dz, plugin);
        if (instant) {
            // ready maps keep their spawn loaded the whole time they wait, so go straight in
            // and load the rest of the area in the background
            ChunkWork.load(plugin, world, ChunkWork.square(center, r), 4, () -> {
            });
            spreadThenBegin();
            return;
        }
        long started = System.currentTimeMillis();
        int timeout = plugin.getConfig().getInt("preload-timeout-seconds", 60) * 1000;
        ChunkWork.Job job = ChunkWork.load(plugin, world, ChunkWork.square(center, r), 16, () -> {
        });
        loadTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (ended) { loadTask.cancel(); job.cancel(); return; }
            for (Player p : party.online()) p.sendActionBar("§eLoading the map... §f" + job.percent() + "%");
            if (job.finished || System.currentTimeMillis() - started > timeout) {
                loadTask.cancel();
                spreadThenBegin();
            }
        }, 2L, 5L);
    }

    // ------------------------------------------------------------------ death swap spread spawns

    /** One spawn spot per player (teammates share), spread on a ring around the center. */
    private final List<Location> spreadSpots = new ArrayList<>();

    private void spreadThenBegin() {
        if (mode != Mode.DEATHSWAP || roster.size() < 2) {
            begin();
            return;
        }
        // one spot per side: teams share, everyone else gets their own
        int sides = 0;
        Set<TeamColor> seenTeams = new HashSet<>();
        for (UUID id : roster) {
            TeamColor t = party.teams.get(id);
            if (t == null || seenTeams.add(t)) sides++;
        }
        int dist = Math.max(16, plugin.getConfig().getInt("deathswap.spread-distance", 100));
        dist = Math.min(dist, Math.max(16, radius - 48));
        double offset = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        List<int[]> targets = new ArrayList<>();
        for (int i = 0; i < sides; i++) {
            double a = offset + (Math.PI * 2 / sides) * i;
            targets.add(new int[]{(int) Math.round(center.getX() + Math.cos(a) * dist), (int) Math.round(center.getZ() + Math.sin(a) * dist)});
        }
        loadTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : party.online()) p.sendActionBar("§eSpreading players out...");
        }, 0L, 20L);
        findSpread(targets, 0);
    }

    private void findSpread(List<int[]> targets, int i) {
        if (ended) {
            if (loadTask != null) loadTask.cancel();
            return;
        }
        if (i >= targets.size()) {
            if (loadTask != null) loadTask.cancel();
            begin();
            return;
        }
        int[] t = targets.get(i);
        ChunkWork.findLandNear(plugin, world, t[0], t[1], 96, 24, 60, spot -> {
            Location use = spot != null ? spot : center.clone();
            int cx = use.getBlockX() >> 4, cz = use.getBlockZ() >> 4;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) world.addPluginChunkTicket(cx + dx, cz + dz, plugin);
            spreadSpots.add(use);
            findSpread(targets, i + 1);
        });
    }

    private void begin() {
        List<Player> players = new ArrayList<>();
        for (UUID id : roster) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && plugin.matchFor(p.getWorld()) == null) players.add(p);
        }
        if (players.isEmpty()) {
            loading = false;
            end(null, "Everyone left.");
            return;
        }
        loading = false;

        // teams: if anyone picked a team, everyone is on a team. Manhunt/Hitman use roles instead.
        boolean useTeams = false;
        for (Player p : players) if (party.teams.get(p.getUniqueId()) != null) useTeams = true;
        if (mode.isHunt()) {
            useTeams = false;
            assignRoles(players);
        }
        if (useTeams) {
            for (Player p : players) {
                TeamColor t = party.teams.get(p.getUniqueId());
                if (t == null) {
                    t = smallestTeam();
                    p.sendMessage(Msg.GAME + "You were put on the " + t.code + t.display + " §7team.");
                }
                teams.put(p.getUniqueId(), t);
            }
        }

        // sides = teams, or one per player in free for all
        Map<String, List<Player>> sides = new LinkedHashMap<>();
        for (Player p : players) {
            TeamColor t = teams.get(p.getUniqueId());
            String key = t == null ? p.getUniqueId().toString() : t.name();
            sides.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        initialSides = sides.size();
        counted = initialSides >= 2;
        if (counted) {
            for (Player p : players) plugin.stats().add(p.getUniqueId(), p.getName(), mode.key, Stats.Stat.GAMES, 1);
        }

        setupScoreboard();

        // everyone spawns together on dry land, in a little circle
        List<Player> order = new ArrayList<>();
        for (List<Player> side : sides.values()) order.addAll(side);
        int n = order.size();
        boolean hunger = s.on(Setting.HUNGER);
        Map<String, Integer> sideIndex = new HashMap<>();
        Map<String, Integer> sideCount = new HashMap<>();
        for (String k : sides.keySet()) sideIndex.put(k, sideIndex.size());
        Map<TeamColor, Integer> roleIdx = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Player p = order.get(i);
            Location spot;
            if (mode == Mode.HITMAN && n > 1) {
                TeamColor tc = teams.get(p.getUniqueId());
                int k = roleIdx.merge(tc, 1, Integer::sum) - 1;
                spot = hitmanSpot(p, k, sides.get(tc.name()).size());
            } else if (!spreadSpots.isEmpty()) {
                // death swap: each player (or team) gets their own spot out on the ring
                TeamColor tc = teams.get(p.getUniqueId());
                String key = tc == null ? p.getUniqueId().toString() : tc.name();
                Location base = spreadSpots.get(sideIndex.getOrDefault(key, 0) % spreadSpots.size());
                int k = sideCount.merge(key, 1, Integer::sum) - 1;
                spot = k == 0 ? base.clone() : nearSpot(world, base, Math.cos(k * 1.7) * 2, Math.sin(k * 1.7) * 2);
                // face back toward the middle of the map
                spot.setYaw((float) Math.toDegrees(Math.atan2(spot.getX() - center.getX(), center.getZ() - spot.getZ())) + 180f);
            } else {
                double a = (Math.PI * 2 / n) * i;
                spot = n == 1 ? center.clone() : nearSpot(world, center, Math.cos(a) * 2.5, Math.sin(a) * 2.5);
                // face the middle of the circle
                spot.setYaw((float) Math.toDegrees(Math.atan2(spot.getX() - center.getX(), center.getZ() - spot.getZ())));
            }
            UUID id = p.getUniqueId();
            participants.add(id);
            alive.add(id);
            names.put(id, p.getName());
            kills.put(id, 0);
            plugin.resetPlayer(p, GameMode.SURVIVAL);
            if (!hunger) {
                p.setFoodLevel(lockedFood());
                p.setSaturation(0f);
            }
            p.teleport(spot);
            p.setScoreboard(board);
            if (mode == Mode.RIC) giveTracker(p);
            lives.put(id, s.get(Setting.LIVES));
            LettuceGames.setLocatorRange(p, 0.0);
            giveKit(p, true);
            if (mode.isHunt()) {
                try {
                    p.setRespawnLocation(null, true);
                } catch (Throwable ignored) {
                }
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (world != null) world.removePluginChunkTickets(plugin);
        }, 200L);

        nextDrop = s.get(Setting.DROP_FIRST);
        nextSwap = swapDelay();
        if (mode == Mode.BLOCKSHUFFLE) bsSetup();
        if (Setting.RANDOM_OP.in(mode) && s.on(Setting.RANDOM_OP)) nextRandomOp = ThreadLocalRandom.current().nextInt(120, 361);
        if (mode.isHunt()) huntBegin();
        if (mode == Mode.BINGO) bingoSetup();
        if (mode == Mode.DEATHSHUFFLE) dshSetup();
        twistsBegin();

        int grace = s.get(Setting.GRACE);
        String sub;
        if (mode.isHunt()) {
            sub = null;
        } else if (mode == Mode.SPEEDRUN) {
            sub = "§bKill the Ender Dragon, fast!";
        } else if (mode == Mode.BINGO) {
            sub = s.get(Setting.PREVIEW) > 0 ? "§eStudy the card!" : "§eRight-click the card to see your tasks";
        } else if (mode == Mode.DEATHSHUFFLE) {
            sub = "§eFirst death in 3 seconds...";
        } else if (mode == Mode.BLOCKSHUFFLE) {
            sub = "§eFirst block in 3 seconds...";
        } else if (mode == Mode.DEATHSWAP) {
            sub = s.get(Setting.SWAP_DELAY) < 0 ? "§eFirst swap in 2-6 minutes" : "§eFirst swap in " + Msg.time(nextSwap);
        } else {
            sub = grace > 0 ? "§eGrace period: " + grace + "s" : "§cPvP is on!";
        }
        for (Player p : viewers()) {
            String psub = sub;
            if (psub == null) {
                Role r = roleOf(p.getUniqueId());
                psub = r == Role.RUNNER
                        ? (mode == Mode.MANHUNT ? "§aBeat the Ender Dragon!" : "§aSurvive for " + Msg.time(s.get(Setting.TIME_LIMIT)) + "!")
                        : "§cHunt down the " + mode.runners().toLowerCase() + "!";
                p.sendTitle(r.code() + "§lYou're a " + r.display(mode) + "!", psub, 5, 60, 10);
            } else {
                p.sendTitle("§a§lGame Started!", psub, 5, 50, 10);
            }
            p.playSound(p.getLocation(), "minecraft:entity.ender_dragon.growl", 0.6f, 1.4f);
        }
        broadcast(" ");
        broadcast(s.on(Setting.SURPRISE) ? "§d§l      Surprise! This game's settings:" : "§6§l      Current Game Settings:");
        if (mode.isHunt() && !s.on(Setting.ANNOUNCE_TWISTS)) {
            List<String> full = s.summary(true), noTwists = s.summary(false);
            for (Player p : viewers()) {
                for (String line : isRunner(p.getUniqueId()) ? full : noTwists) p.sendMessage("§7   " + line);
                if (!isRunner(p.getUniqueId())) p.sendMessage("§7   §8(Twists are hidden from " + mode.hunters().toLowerCase() + ")");
            }
        } else {
            for (String line : s.summary()) broadcast("§7   " + line);
        }
        if (mode.isHunt()) {
            for (Role r : Role.values()) {
                StringBuilder sb = new StringBuilder();
                for (UUID id : withRole(r, true)) { if (sb.length() > 0) sb.append("§7, "); sb.append(colored(id)); }
                broadcast("§7   " + r.code() + "§l" + r.plural(mode) + ": " + sb);
            }
        } else if (!teams.isEmpty()) broadcast("§7   §6Teams: §a" + initialSides);
        broadcast(switch (mode) {
            case RIC -> "§7 Need to leave? §c/leave §7(you'll be eliminated). §7Left-click the tracker to switch targets.";
            case DEATHSWAP -> "§7 Everyone swaps places on the timer. Leave a trap behind! §c/leave §7to quit.";
            case BLOCKSHUFFLE -> "§7 Stand on your block before time runs out. Your block shows on your action bar. §c/leave §7to quit.";
            case MANHUNT -> "§7 Runners win by killing the Ender Dragon. Hunters respawn and win by killing every runner. §c/leave §7to quit.";
            case HITMAN -> "§7 Survivors win if anyone's alive when the timer ends. Hitmen respawn and hunt them. §c/leave §7to quit.";
            case SPEEDRUN -> "§7 Work together and kill the Ender Dragon. Dying just sends you back. §c/leave §7to quit.";
            case BINGO -> "§7 Right-click the card (or §e/bingocard§7) to see your tasks. Dying just sends you back. §c/leave §7to quit.";
            case DEATHSHUFFLE -> "§7 Every round you get a way to die. Pull it off before time runs out! §c/leave §7to quit.";
        });
        broadcast(" ");

        // driven by the real clock so a laggy server doesn't stretch game seconds
        clockStart = System.currentTimeMillis();
        secondTask = Bukkit.getScheduler().runTaskTimer(plugin, this::clockTick, 5L, 5L);
        trackerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickTracker, 10L, 10L);
    }

    private TeamColor smallestTeam() {
        // only teams someone actually picked (at least two), so 2 teams stay 2 teams
        Map<TeamColor, Integer> counts = new LinkedHashMap<>();
        for (UUID id : party.members) {
            TeamColor t = party.teams.get(id);
            if (t != null) counts.merge(t, 1, Integer::sum);
        }
        for (TeamColor t : TeamColor.values()) if (counts.size() < 2) counts.putIfAbsent(t, 0);
        for (Map.Entry<UUID, TeamColor> e : teams.entrySet()) {
            if (party.teams.get(e.getKey()) == null) counts.merge(e.getValue(), 1, Integer::sum);
        }
        TeamColor best = null;
        int bestN = Integer.MAX_VALUE;
        for (Map.Entry<TeamColor, Integer> e : counts.entrySet()) {
            if (e.getValue() < bestN) { bestN = e.getValue(); best = e.getKey(); }
        }
        return best;
    }

    /** Vanilla's smallest possible world border (1x1). Every shrink ends here, which is as good as 0x0. */
    private static final double MIN_BORDER = 1.0;

    private void setupBorder(World w, double size) {
        WorldBorder b = w.getWorldBorder();
        // border is centered on spawn, scaled down for the nether
        double scale = w.equals(nether) ? 8.0 : 1.0;
        b.setCenter(center.getX() / scale, center.getZ() / scale);
        b.setSize(size);
        b.setDamageAmount(1.0);
        b.setDamageBuffer(0);
        b.setWarningDistance(16);
    }

    /** Food level when hunger is off. 19 when the eating twist is on so normal food can still be eaten. */
    public int lockedFood() {
        return s.on(Setting.EAT_EFFECT) ? 19 : 20;
    }

    private static boolean badSurface(Material t) {
        String n = t.name();
        return n.contains("LEAVES") || n.equals("WATER") || n.equals("LAVA") || n.equals("MAGMA_BLOCK")
                || n.equals("CACTUS") || n.equals("POWDER_SNOW") || n.equals("ICE") || n.contains("KELP") || n.equals("SEAGRASS");
    }

    public static boolean okBiome(World w, int x, int z) {
        try {
            String biome = w.getBiome(x, 64, z).getKey().getKey();
            return !(biome.contains("ocean") || biome.contains("river") || biome.contains("beach") || biome.contains("swamp"));
        } catch (Throwable ignored) {
            return true; // the block checks still catch water
        }
    }

    /** Only call with the chunks around x,z already loaded (ChunkWork.findLand does that). */
    public static boolean dryLand(World w, int x, int z) {
        if (!okBiome(w, x, z)) return false;
        // the spot and two rings around it (10 and 24 blocks out) all have to be solid ground
        int[] dists = {0, 10, 24};
        for (int d : dists) {
            int steps = d == 0 ? 1 : 8;
            for (int k = 0; k < steps; k++) {
                double a = (Math.PI * 2 / steps) * k;
                Block b = w.getHighestBlockAt(x + (int) Math.round(Math.cos(a) * d), z + (int) Math.round(Math.sin(a) * d));
                if (b.isLiquid() || badSurface(b.getType())) return false;
            }
        }
        return true;
    }

    private static Location nearSpot(World w, Location center, double dx, double dz) {
        int x = (int) Math.floor(center.getX() + dx);
        int z = (int) Math.floor(center.getZ() + dz);
        Block b = w.getHighestBlockAt(x, z);
        if (b.isLiquid() || badSurface(b.getType()) || Math.abs(b.getY() + 1 - center.getY()) > 3) {
            return center.clone();
        }
        return new Location(w, x + 0.5, b.getY() + 1, z + 0.5);
    }

    public static Location safeSpot(World w, int x, int z) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 25; attempt++) {
            int tx = x + (attempt == 0 ? 0 : r.nextInt(-24, 25));
            int tz = z + (attempt == 0 ? 0 : r.nextInt(-24, 25));
            Block b = w.getHighestBlockAt(tx, tz);
            Material t = b.getType();
            if (!b.isLiquid() && t != Material.MAGMA_BLOCK && t != Material.CACTUS && !t.name().contains("LEAVES")
                    && t != Material.POWDER_SNOW) {
                return new Location(w, tx + 0.5, b.getY() + 1, tz + 0.5);
            }
        }
        Block b = w.getHighestBlockAt(x, z);
        return new Location(w, x + 0.5, b.getY() + 1, z + 0.5);
    }

    private void giveTracker(Player p) {
        ItemStack c = new ItemStack(Material.COMPASS);
        ItemMeta m = c.getItemMeta();
        m.setDisplayName(COMPASS_NAME);
        m.setLore(List.of("§7Points at the nearest enemy.", "§7Right-click to pick who to track."));
        c.setItemMeta(m);
        p.getInventory().setItem(8, c);
    }

    public static boolean isTracker(ItemStack it) {
        if (it == null || it.getType() != Material.COMPASS || !it.hasItemMeta()) return false;
        ItemMeta m = it.getItemMeta();
        return m != null && m.hasDisplayName() && COMPASS_NAME.equals(m.getDisplayName());
    }

    // ------------------------------------------------------------------ ticking

    private long clockStart;

    /** Runs a game second for every real second that has passed (catches up after lag spikes). */
    private void clockTick() {
        if (ended) return;
        long due = (System.currentTimeMillis() - clockStart) / 1000L;
        int n = 0;
        while (!ended && elapsed < due && n++ < 5) tickSecond();
        if (elapsed < due - 5) clockStart += (due - 5 - elapsed) * 1000L; // way behind: let it go instead of fast-forwarding
    }

    private void tickSecond() {
        if (ended) return;
        elapsed++;
        if (s.on(Setting.DAYLIGHT) && elapsed % 10 == 0 && world.getTime() > 11000) world.setTime(1000);
        if (plugin.getConfig().getBoolean("spectator-tablist", true)) {
            for (UUID g : ghosts) {
                Player gp = Bukkit.getPlayer(g);
                if (gp != null) hideGhostGear(gp);
            }
        }

        // spectator help on the action bar
        for (UUID id : spectators) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (ghosts.contains(id)) {
                p.sendActionBar("§eDouble-tap jump §7to fly/walk §8• §eCompass §7teleporter §8• §eEye §7for noclip");
            } else if (p.getGameMode() != GameMode.SPECTATOR) {
                continue;
            } else if (p.getSpectatorTarget() != null) {
                p.sendActionBar("§7Watching §f" + p.getSpectatorTarget().getName() + " §8• §eSneak §7to get out");
            } else {
                p.sendActionBar("§eLeft-click §7a player to watch §8• §e/spec §7teleporter §8• §e/specmode §7walk/fly");
            }
        }

        int grace = s.get(Setting.GRACE);
        if (grace > 0 && elapsed == grace) {
            broadcast(Msg.GAME + "§c§lGrace period is over! §7PvP is on.");
            for (Player p : viewers()) p.playSound(p.getLocation(), "minecraft:block.note_block.pling", 1f, 0.6f);
        }

        if (mode == Mode.RIC) {
            // item drops
            nextDrop--;
            if (nextDrop > 0 && nextDrop <= 3) {
                for (Player p : viewers()) p.playSound(p.getLocation(), "minecraft:block.note_block.hat", 0.8f, 1.6f);
            }
            if (nextDrop <= 0) {
                doDrop();
                nextDrop = s.get(Setting.DROP_INTERVAL);
            }
        } else if (mode == Mode.DEATHSWAP) {
            tickSwap();
        } else if (mode == Mode.BLOCKSHUFFLE) {
            tickShuffle();
        } else if (mode.isHunt()) {
            tickHunt();
            if (ended) return;
        } else if (mode == Mode.DEATHSHUFFLE) {
            tickDeathShuffle();
            if (ended) return;
        } else if (mode == Mode.BINGO) {
            tickBingo();
            if (ended) return;
        }
        tickRandomOp();
        tickTwists();

        tickBorder();

        if (Setting.TIME_LIMIT.in(mode)) {
            int limit = s.get(Setting.TIME_LIMIT);
            if (mode == Mode.HITMAN && limit - elapsed > 0 && limit - elapsed <= 60 && (limit - elapsed) % 10 == 0) {
                broadcast(tag() + "§e" + (limit - elapsed) + "s §7left for the hitmen!");
            }
            if (elapsed >= limit) {
                if (mode == Mode.HITMAN && !withRole(Role.RUNNER, true).isEmpty()) end(Role.RUNNER.team.name(), "The survivors made it!");
                else end(null, "Time's up!");
                return;
            }
        }
        updateSidebar();
    }

    // ------------------------------------------------------------------ death swap

    /** Seconds until the next swap. Random picks 2-6 minutes. */
    private int swapDelay() {
        int d = s.get(Setting.SWAP_DELAY);
        return d > 0 ? d : ThreadLocalRandom.current().nextInt(120, 361);
    }

    private void bsLines(List<String> lines) {
        lines.add("§fRound: §a" + Math.max(1, round));
        if (choosing) lines.add("§fPicking: §b" + chooseLeft + "s");
        else if (roundGap > 0 || round == 0) lines.add("§fNext round: §e" + roundGap + "s");
        else lines.add("§fBlock Time: " + (roundLeft <= 10 ? "§c" : "§e") + Msg.time(roundLeft));
        if (s.get(Setting.BS_FORMAT) != 2 && !choosing && round > 0) lines.add("§fFound: §a" + found.size() + "§7/" + alive.size());
    }

    private String swapTimerText() {
        boolean hidden = s.get(Setting.SWAP_TIMER) == 1 || s.get(Setting.SWAP_DELAY) < 0;
        if (hidden && nextSwap > 10) return "§7???";
        return (nextSwap <= 10 ? "§c" : "§e") + Msg.time(nextSwap);
    }

    private void tickSwap() {
        nextSwap--;
        if (nextSwap > 0 && nextSwap <= 10) {
            for (Player p : viewers()) {
                p.sendTitle("", (nextSwap <= 3 ? "§c§l" : "§e") + "Swapping in " + nextSwap + "...", 0, 25, 5);
                p.playSound(p.getLocation(), "minecraft:block.note_block.pling", 0.8f, nextSwap <= 3 ? 2f : 1f);
            }
        }
        if (nextSwap <= 0) {
            doSwap();
            nextSwap = swapDelay();
        }
        // lives on the action bar
        if (s.get(Setting.LIVES) > 1) {
            for (UUID id : alive) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && (nextSwap > 10)) p.sendActionBar("§c❤ Lives: " + lives.getOrDefault(id, 1));
            }
        }
    }

    /** Random OP Loot twist: a new OP loot source turns on every few minutes. */
    private void tickRandomOp() {
        if (nextRandomOp > 0 && elapsed >= nextRandomOp) {
            List<OpSource> off = new ArrayList<>();
            for (OpSource o : OpSource.values()) if (!opActive.contains(o) && o.in(mode)) off.add(o);
            if (off.isEmpty()) {
                nextRandomOp = -1;
            } else {
                OpSource o = off.get(ThreadLocalRandom.current().nextInt(off.size()));
                opActive.add(o);
                broadcast(" ");
                broadcast("§6§l✦ OP LOOT TWIST! §e" + mode.words(o.desc) + "!");
                broadcast(" ");
                for (Player p : viewers()) {
                    p.sendTitle("§6§lOP LOOT TWIST", "§e" + mode.words(o.display) + " drop OP loot!", 5, 50, 10);
                    p.playSound(p.getLocation(), "minecraft:ui.toast.challenge_complete", 0.6f, 1.4f);
                }
                nextRandomOp = elapsed + ThreadLocalRandom.current().nextInt(180, 481);
            }
        }
    }

    /** Everyone alive trades places. Positions, momentum and fall distance all carry over. */
    public void doSwap() {
        List<Player> ps = new ArrayList<>();
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) ps.add(p);
        }
        if (ps.size() < 2) return;
        // shuffle until nobody lands on a teammate's spot (if we can)
        for (int tries = 0; tries < 30; tries++) {
            java.util.Collections.shuffle(ps);
            boolean ok = true;
            for (int i = 0; i < ps.size() && !teams.isEmpty(); i++) {
                if (sameTeam(ps.get(i).getUniqueId(), ps.get((i + 1) % ps.size()).getUniqueId())) ok = false;
            }
            if (ok) break;
        }
        int n = ps.size();
        Location[] locs = new Location[n];
        org.bukkit.util.Vector[] vel = new org.bukkit.util.Vector[n];
        float[] fall = new float[n];
        for (int i = 0; i < n; i++) {
            Player p = ps.get(i);
            if (p.isInsideVehicle()) p.leaveVehicle();
            p.closeInventory();
            locs[i] = p.getLocation().clone();
            vel[i] = p.getVelocity().clone();
            fall[i] = p.getFallDistance();
        }
        swapCount++;
        lastSwapAt = elapsed;
        swappedInto.clear();
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            Player p = ps.get(i);
            p.teleport(locs[j]);
            p.setVelocity(vel[j]);
            p.setFallDistance(fall[j]);
            swappedInto.put(p.getUniqueId(), ps.get(j).getUniqueId());
            p.sendTitle("§d§lSWAP!", "§7You're at " + colored(ps.get(j).getUniqueId()) + "§7's spot", 0, 30, 10);
            p.playSound(locs[j], "minecraft:entity.enderman.teleport", 1f, 1f);
        }
        for (UUID id : spectators) {
            Player sp = Bukkit.getPlayer(id);
            if (sp != null) sp.playSound(sp.getLocation(), "minecraft:entity.enderman.teleport", 0.6f, 1f);
        }
        broadcast(tag() + "§d§lSWAP #" + swapCount + "! §7Good luck.");
    }

    public String tag() {
        return switch (mode) {
            case RIC -> "§c§lRIC §8» §7";
            case DEATHSWAP -> "§5§lSWAP §8» §7";
            case BLOCKSHUFFLE -> "§a§lSHUFFLE §8» §7";
            case MANHUNT -> "§c§lMANHUNT §8» §7";
            case HITMAN -> "§4§lHITMAN §8» §7";
            case SPEEDRUN -> "§b§lSPEEDRUN §8» §7";
            case BINGO -> "§e§lBINGO §8» §7";
            case DEATHSHUFFLE -> "§4§lDEATH §8» §7";
        };
    }

    /**
     * Death Swap lives: if the player has lives left, respawn them instead of eliminating.
     * @return true if they used a life
     */
    public boolean useLife(Player p, String deathMessage) {
        UUID id = p.getUniqueId();
        int left = lives.getOrDefault(id, 1);
        if (left <= 1) return false;
        lives.put(id, left - 1);
        String l = (left - 1) + " " + (left - 1 == 1 ? "life" : "lives") + " left";
        respawn(p, deathMessage + " §7(" + l + ")", "§7" + l);
        return true;
    }

    /** Puts a dead player back at spawn (drops their stuff unless Keep Inventory is on). */
    public void respawn(Player p, String deathMessage, String subtitle) {
        UUID id = p.getUniqueId();
        if (counted) plugin.stats().add(id, name(id), mode.key, Stats.Stat.DEATHS, 1);
        Location loc = p.getLocation();
        if (!s.on(Setting.KEEP_INVENTORY)) {
            if (s.get(Setting.DEATH_LOOT) > 0) {
                for (ItemStack it : p.getInventory().getContents()) {
                    if (it == null || it.getType() == Material.AIR || isTracker(it) || Bingo.isCard(it) || isReusableRocket(it)) continue;
                    plugin.allowItemSpawn = true;
                    try {
                        Item dropped = loc.getWorld().dropItemNaturally(loc, it);
                        dropped.setThrower(id);
                        deathItems.add(dropped.getUniqueId());
                    } finally {
                        plugin.allowItemSpawn = false;
                    }
                }
            }
            p.getInventory().clear();
            p.setLevel(0);
            p.setExp(0f);
        }
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.setSaturation(5f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        p.teleport(center.clone().add(0, 0.5, 0));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 60, 4));
        swappedInto.remove(id);
        lastDamager.remove(id);
        broadcast("§c☠ " + deathMessage);
        p.sendTitle("§c§lYOU DIED", subtitle, 5, 40, 10);
        p.playSound(p.getLocation(), "minecraft:entity.player.death", 1f, 1f);
    }

    // ------------------------------------------------------------------ manhunt / hitman

    public World end;
    /** hunters waiting out their respawn timer (still count as alive, they're just in spectator) */
    public final Set<UUID> respawning = new HashSet<>();
    /** assassins mode: hunters a runner is looking at right now */
    public final Set<UUID> seen = new HashSet<>();
    /** freeze tag: frozen survivor -> seconds left on the kill timer (-1 = no timer) */
    public final Map<UUID, Integer> iced = new HashMap<>();
    private final Map<UUID, Integer> thawProgress = new HashMap<>();
    private final Map<UUID, org.bukkit.entity.TextDisplay> iceLabels = new HashMap<>();
    /** sneak invisibility we handed out (so we only take away our own) */
    public final Set<UUID> sneakInvis = new HashSet<>();
    /** where each player last LEFT a world (for tracking runners through portals) */
    private final Map<UUID, Map<String, Location>> exitPortal = new HashMap<>();
    /** where each player last ARRIVED in a world (advanced compass "your portal") */
    private final Map<UUID, Map<String, Location>> entryPortal = new HashMap<>();
    /** advanced compass: hunters tracking their own last portal */
    public final Set<UUID> trackPortal = new HashSet<>();
    public boolean runnerInNether = false;
    private int runnersFreeAt = 0, huntersFreeAt = 0;
    private int borderShrinkEnd = -1;
    private BukkitTask huntTask;
    private final Map<String, org.bukkit.loot.LootTables> shearTables = new HashMap<>();

    public boolean isRunner(UUID id) {
        return mode.isHunt() && teams.get(id) == Role.RUNNER.team;
    }

    public boolean isHunter(UUID id) {
        return mode.isHunt() && teams.get(id) == Role.HUNTER.team;
    }

    public Role roleOf(UUID id) {
        return isRunner(id) ? Role.RUNNER : Role.HUNTER;
    }

    public List<UUID> withRole(Role r, boolean onlyAlive) {
        List<UUID> out = new ArrayList<>();
        for (UUID id : onlyAlive ? alive : participants) if (teams.get(id) == r.team) out.add(id);
        return out;
    }

    /** Seconds this player still can't move for (start freeze, head start, assassins, freeze tag). */
    public boolean isFrozen(UUID id) {
        if (!alive.contains(id)) return false;
        if (mode == Mode.BINGO) return bingo != null && elapsed < previewEnd;
        if (!mode.isHunt()) return false;
        if (iced.containsKey(id) || seen.contains(id)) return true;
        return elapsed < (isRunner(id) ? runnersFreeAt : huntersFreeAt);
    }

    public boolean freezeTag() {
        return mode == Mode.HITMAN && s.get(Setting.FREEZE_TAG) != 0;
    }

    /** Splits the players into runners and hunters. Picks stick, everyone else fills in. */
    private void assignRoles(List<Player> players) {
        List<Player> runners = new ArrayList<>(), hunters = new ArrayList<>(), rest = new ArrayList<>();
        for (Player p : players) {
            Role r = party.roles.get(p.getUniqueId());
            if (r == Role.RUNNER) runners.add(p);
            else if (r == Role.HUNTER) hunters.add(p);
            else rest.add(p);
        }
        java.util.Collections.shuffle(rest);
        if (runners.isEmpty()) {
            if (!rest.isEmpty()) runners.add(rest.remove(0));
            else if (!hunters.isEmpty()) runners.add(hunters.remove(ThreadLocalRandom.current().nextInt(hunters.size())));
        }
        hunters.addAll(rest);
        if (hunters.isEmpty() && runners.size() > 1) hunters.add(runners.remove(ThreadLocalRandom.current().nextInt(runners.size())));
        for (Player p : runners) teams.put(p.getUniqueId(), Role.RUNNER.team);
        for (Player p : hunters) teams.put(p.getUniqueId(), Role.HUNTER.team);
        for (Player p : players) {
            Role r = roleOf(p.getUniqueId());
            if (party.roles.get(p.getUniqueId()) != r) {
                p.sendMessage(Msg.GAME + "You're a " + r.code() + "§l" + r.display(mode) + "§7 this game.");
            }
        }
    }

    /** Hitman layout: survivors back to back in the middle, hitmen in a ring around them. */
    private Location hitmanSpot(Player p, int idxInRole, int roleSize) {
        double a = (Math.PI * 2 / Math.max(1, roleSize)) * idxInRole;
        if (isRunner(p.getUniqueId())) {
            Location l = roleSize == 1 ? center.clone() : nearSpot(world, center, Math.cos(a) * 0.9, Math.sin(a) * 0.9);
            // facing out
            l.setYaw((float) Math.toDegrees(Math.atan2(l.getX() - center.getX(), center.getZ() - l.getZ())) + 180f);
            return l;
        }
        int dist = Math.max(10, Math.min(plugin.getConfig().getInt("hitman.ring-distance", 40), radius - 20));
        Location l = safeSpot(world, (int) Math.round(center.getX() + Math.cos(a) * dist), (int) Math.round(center.getZ() + Math.sin(a) * dist));
        l.setYaw((float) Math.toDegrees(Math.atan2(l.getX() - center.getX(), center.getZ() - l.getZ())));
        return l;
    }

    /** Called once everyone is in. */
    private void huntBegin() {
        int startFreeze = Math.max(0, plugin.getConfig().getInt("hunt.start-freeze-seconds", 5));
        runnersFreeAt = mode == Mode.MANHUNT ? startFreeze : 0;
        huntersFreeAt = runnersFreeAt + s.get(mode == Mode.MANHUNT ? Setting.HEAD_START : Setting.HITMEN_FREEZE);
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            applyRoleEffects(p);
        }
        if (mode == Mode.HITMAN && (s.get(Setting.SHRINKING_BORDER) == 1 || s.get(Setting.SHRINKING_BORDER) == 2)) {
            int limit = s.get(Setting.TIME_LIMIT);
            // closes fully in 10m (Normal) or 6m (Rush), or sooner if the time limit is shorter than that
            int dur = Math.min((int) (limit * 0.9), s.get(Setting.SHRINKING_BORDER) == 2 ? 360 : 600);
            borderShrinkEnd = dur;
            world.getWorldBorder().setSize(MIN_BORDER, dur);
        }
        if (s.on(Setting.ASSASSINS) || freezeTag()) {
            huntTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAssassins, 5L, 2L);
        }
    }

    /** Tracker for hunters, plus whatever gear the twists hand out. */
    private void giveKit(Player p, boolean fresh) {
        UUID id = p.getUniqueId();
        if (isHunter(id)) {
            boolean has = false;
            for (ItemStack it : p.getInventory().getContents()) if (isTracker(it)) has = true;
            if (!has) giveTracker(p);
            if (s.on(Setting.JUGGERNAUT)) {
                // every spawn, but never doubles up on gear they kept
                PlayerInventory inv = p.getInventory();
                for (Material t : new Material[]{Material.DIAMOND_SWORD, Material.DIAMOND_PICKAXE, Material.DIAMOND_AXE, Material.DIAMOND_SHOVEL}) {
                    boolean got = false;
                    for (ItemStack it : inv.getContents()) if (it != null && it.getType() == t) got = true;
                    if (!got) inv.addItem(new ItemStack(t));
                }
                if (empty(inv.getHelmet())) inv.setHelmet(enchanted(Material.DIAMOND_HELMET));
                if (empty(inv.getChestplate())) inv.setChestplate(enchanted(Material.DIAMOND_CHESTPLATE));
                if (empty(inv.getLeggings())) inv.setLeggings(enchanted(Material.DIAMOND_LEGGINGS));
                if (empty(inv.getBoots())) inv.setBoots(enchanted(Material.DIAMOND_BOOTS));
            }
        }
        if (s.on(Setting.ELYTRA_RACE) && fresh) {
            // "Icarus": mending elytra at half durability plus a stack of rockets
            ItemStack el = new ItemStack(Material.ELYTRA);
            el.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.MENDING, 1);
            if (el.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dm) {
                dm.setDamage(216);
                el.setItemMeta(dm);
            }
            PlayerInventory inv = p.getInventory();
            if (empty(inv.getChestplate())) inv.setChestplate(el);
            else inv.addItem(el);
            inv.addItem(new ItemStack(Material.FIREWORK_ROCKET, 64));
        }
        if (mode == Mode.BINGO) {
            PlayerInventory inv = p.getInventory();
            boolean card = false, compass = false, elytra = false;
            for (ItemStack it : inv.getContents()) {
                if (Bingo.isCard(it)) card = true;
                if (isTracker(it)) compass = true;
                if (it != null && it.getType() == Material.ELYTRA) elytra = true;
            }
            if (!card) inv.setItem(8, Bingo.cardItem());
            if (s.on(Setting.BINGO_COMPASS) && !compass) {
                // giveTracker uses slot 8 (the card's spot), so put the compass in 7 instead
                ItemStack old8 = inv.getItem(8);
                giveTracker(p);
                ItemStack tr = inv.getItem(8);
                inv.setItem(8, old8);
                if (empty(inv.getItem(7))) inv.setItem(7, tr);
                else inv.addItem(tr);
            }
            if (s.on(Setting.BINGO_ELYTRA) && !elytra) {
                ItemStack el = new ItemStack(Material.ELYTRA);
                ItemMeta em = el.getItemMeta();
                em.setUnbreakable(true);
                el.setItemMeta(em);
                if (empty(inv.getChestplate())) inv.setChestplate(el);
                else inv.addItem(el);
                boolean rocket = false;
                for (ItemStack it : inv.getContents()) if (isReusableRocket(it)) rocket = true;
                if (!rocket) inv.addItem(reusableRocket());
            }
        }
    }

    public static final String REUSABLE_ROCKET = "§b§lReusable Firework §7(5s cooldown)";

    public static ItemStack reusableRocket() {
        ItemStack it = new ItemStack(Material.FIREWORK_ROCKET);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(REUSABLE_ROCKET);
        m.setLore(List.of("§7Right-click while gliding for a boost.", "§7Never runs out."));
        it.setItemMeta(m);
        return it;
    }

    public static boolean isReusableRocket(ItemStack it) {
        return it != null && it.getType() == Material.FIREWORK_ROCKET && it.hasItemMeta() && REUSABLE_ROCKET.equals(it.getItemMeta().getDisplayName());
    }

    private static boolean empty(ItemStack it) {
        return it == null || it.getType() == Material.AIR;
    }

    private static ItemStack enchanted(Material m) {
        ItemStack it = new ItemStack(m);
        try {
            it.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.PROTECTION, 2);
            it.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING, 3);
        } catch (Throwable ignored) {
        }
        return it;
    }

    /** Glowing, tiny and so on. Clears the old ones first so role swaps are clean. */
    public void applyRoleEffects(Player p) {
        UUID id = p.getUniqueId();
        boolean runner = isRunner(id);
        p.removePotionEffect(PotionEffectType.GLOWING);
        if (sneakInvis.remove(id)) p.removePotionEffect(PotionEffectType.INVISIBILITY);
        if (runner && s.on(Setting.GLOWING)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, PotionEffect.INFINITE_DURATION, 0, false, false));
        }
        LettuceGames.setScale(p, runner && s.on(Setting.TINY) ? 0.5 : 1.0);
        if (!s.on(Setting.HUNGER)) {
            p.setFoodLevel(lockedFood());
            p.setSaturation(0f);
        }
    }

    /** Moves a player to the other side, mid game. */
    public void setRole(Player p, Role r) {
        UUID id = p.getUniqueId();
        TeamColor old = teams.put(id, r.team);
        for (Scoreboard sb : new Scoreboard[]{board, specBoard}) {
            if (sb == null) continue;
            if (old != null) {
                Team t = sb.getTeam("t_" + old.name().toLowerCase());
                if (t != null) t.removeEntry(p.getName());
            }
            Team t = sb.getTeam("t_" + r.team.name().toLowerCase());
            if (t != null) t.addEntry(p.getName());
        }
        trackLock.remove(id);
        trackLock.values().removeIf(v -> v.equals(id));
        trackPortal.remove(id);
        if (r == Role.RUNNER) {
            PlayerInventory inv = p.getInventory();
            for (int i = 0; i < inv.getSize(); i++) if (isTracker(inv.getItem(i))) inv.setItem(i, null);
        }
        // shared inventory: you take on your new side's stuff
        ItemStack[] snap = shared.get(r.team);
        if (sharedInv() && snap != null) pushShared(p, snap);
        applyRoleEffects(p);
    }

    public UUID recentKiller(UUID id) {
        UUID killer = lastDamager.get(id);
        Long at = lastDamageAt.get(id);
        return killer != null && at != null && System.currentTimeMillis() - at < 10_000 ? killer : null;
    }

    private void dropAt(Location loc, ItemStack it, UUID owner) {
        plugin.allowItemSpawn = true;
        try {
            Item dropped = loc.getWorld().dropItemNaturally(loc, it);
            if (owner != null) {
                dropped.setThrower(owner);
                deathItems.add(dropped.getUniqueId());
            }
        } finally {
            plugin.allowItemSpawn = false;
        }
    }

    public static final String GOLDEN_HEAD = "§6§lGolden Head";

    /** A lethal hit in Manhunt or Hitman. msg already has the kill credited. */
    public void huntDeath(Player p, String msg) {
        UUID id = p.getUniqueId();
        UUID killer = recentKiller(id);
        boolean runner = isRunner(id);
        Location loc = p.getLocation();
        if (loc.getY() < loc.getWorld().getMinHeight()) loc = null;
        int ops = plugin.getConfig().getInt("hunt.op-loot-on-death", 3);
        if (loc != null && opActive.contains(runner ? OpSource.RUNNER_DEATHS : OpSource.HUNTER_DEATHS)) {
            for (int i = 0; i < ops; i++) dropAt(loc, plugin.pool().opLoot(), null);
        }
        if (loc != null && !runner && s.on(Setting.UHC)) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta sm) {
                sm.setOwningPlayer(p);
                sm.setDisplayName("§e" + p.getName() + "'s Head");
                sm.setLore(List.of("§7Surround with 8 gold ingots", "§7to craft a §6Golden Head§7."));
                head.setItemMeta(sm);
            }
            dropAt(loc, head, null);
        }
        if (runner) {
            if (s.on(Setting.SWAPHUNT) && !freezeTag() && swapHunt(p, msg, killer)) return;
            if (s.on(Setting.INFECTION) && withRole(Role.RUNNER, true).size() > 1) {
                setRole(p, Role.HUNTER);
                down(p, "§2☣ " + msg + " §7and got §2infected§7! Now a " + mode.hunter().toLowerCase() + ".", sharedInv());
                checkWin();
                return;
            }
            eliminate(p, msg, !sharedInv());
            if (mode == Mode.HITMAN) speedUpBorder();
            return;
        }
        if (mode == Mode.HITMAN && s.on(Setting.REVERSE_INFECTION) && killer != null && isRunner(killer)) {
            setRole(p, Role.RUNNER);
            down(p, "§e✚ " + msg + " §7and switched sides! Now a survivor.", sharedInv());
            return;
        }
        down(p, "§c☠ " + msg, sharedInv() || (isHunter(id) && s.on(Setting.HUNTER_KEEP_INV)));
    }

    /** SwapHunt: the dead runner and a hunter trade roles, inventories and spawn points. */
    private boolean swapHunt(Player dead, String msg, UUID killer) {
        Player h = null;
        if (killer != null && isHunter(killer) && !respawning.contains(killer)) h = Bukkit.getPlayer(killer);
        if (h == null) {
            double best = Double.MAX_VALUE;
            for (UUID o : withRole(Role.HUNTER, true)) {
                Player c = Bukkit.getPlayer(o);
                if (c == null || respawning.contains(o)) continue;
                double d = c.getWorld().equals(dead.getWorld()) ? c.getLocation().distanceSquared(dead.getLocation()) : 1e12;
                if (d < best) { best = d; h = c; }
            }
        }
        if (h == null) return false;
        ItemStack[] runnerInv = dead.getInventory().getContents().clone();
        ItemStack[] hunterInv = h.getInventory().getContents().clone();
        Location runnerSpawn = dead.getRespawnLocation(), hunterSpawn = h.getRespawnLocation();
        setRole(dead, Role.HUNTER);
        setRole(h, Role.RUNNER);
        for (int i = 0; i < runnerInv.length; i++) if (isTracker(runnerInv[i])) runnerInv[i] = null;
        for (int i = 0; i < hunterInv.length; i++) if (isTracker(hunterInv[i])) hunterInv[i] = null;
        h.getInventory().setContents(runnerInv);
        dead.getInventory().setContents(hunterInv);
        h.setRespawnLocation(runnerSpawn, true);
        dead.setRespawnLocation(hunterSpawn, true);
        h.sendTitle("§a§lYOU'RE A RUNNER!", "§7You swapped with " + name(dead.getUniqueId()), 5, 50, 10);
        h.playSound(h.getLocation(), "minecraft:entity.enderman.teleport", 1f, 1.2f);
        down(dead, "§d⇄ " + msg + " §7- §d" + name(dead.getUniqueId()) + " §7and §d" + h.getName() + " §7swapped roles!", true);
        return true;
    }

    /** Hunter goes down: drop stuff (unless kept), sit in spectator for the respawn timer, come back. */
    public void down(Player p, String broadcastMsg, boolean keepItems) {
        UUID id = p.getUniqueId();
        if (counted) plugin.stats().add(id, name(id), mode.key, Stats.Stat.DEATHS, 1);
        broadcast(broadcastMsg);
        Location loc = p.getLocation();
        boolean voidDeath = loc.getY() < loc.getWorld().getMinHeight();
        if (!keepItems) {
            for (ItemStack it : p.getInventory().getContents()) {
                if (it == null || it.getType() == Material.AIR || isTracker(it) || Bingo.isCard(it) || isReusableRocket(it)) continue;
                if (!voidDeath) dropAt(loc, it, id);
            }
            p.getInventory().clear();
            p.setLevel(0);
            p.setExp(0f);
        }
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        sneakInvis.remove(id);
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.setSaturation(5f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        lastDamager.remove(id);
        p.playSound(p.getLocation(), "minecraft:entity.player.death", 1f, 1f);
        int wait = mode == Mode.BINGO ? s.get(Setting.RESPAWN_TIMER) : mode.isHunt() ? s.get(Setting.HUNTER_RESPAWN) : 0;
        if (wait <= 0) {
            finishRespawn(p);
            return;
        }
        respawning.add(id);
        p.setGameMode(GameMode.SPECTATOR);
        if (voidDeath) p.teleport(center.clone().add(0, 20, 0));
        final int[] left = {wait};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (ended || !p.isOnline() || !alive.contains(id)) {
                respawning.remove(id);
                task[0].cancel();
                return;
            }
            if (left[0] <= 0) {
                task[0].cancel();
                respawning.remove(id);
                finishRespawn(p);
                return;
            }
            p.sendTitle("§c§lYOU DIED", "§7Respawning in §e" + left[0] + "s", 0, 25, 5);
            left[0]--;
        }, 0L, 20L);
    }

    private void finishRespawn(Player p) {
        UUID id = p.getUniqueId();
        if (ended || !alive.contains(id) || !owns(p.getWorld())) return;
        Location spot = null;
        try {
            Location bed = p.getRespawnLocation();
            if (bed != null && owns(bed.getWorld())) spot = bed;
        } catch (Throwable ignored) {
        }
        if (spot == null) spot = center.clone().add(0, 0.5, 0);
        p.setGameMode(GameMode.SURVIVAL);
        p.teleport(spot);
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 60, 4));
        giveKit(p, mode.isHunt() ? !s.on(Setting.HUNTER_KEEP_INV) || isRunner(id) : !s.on(Setting.KEEP_INVENTORY));
        applyRoleEffects(p);
        if (mode.isHunt()) p.sendTitle(roleOf(id).code() + "§l" + roleOf(id).display(mode).toUpperCase(), "§7Back in!", 0, 30, 10);
        else p.sendTitle("§a§lRESPAWNED", "§7Back at it!", 0, 30, 10);
    }

    /** Hitman shrinking border: every survivor that goes out makes the rest of the shrink faster. */
    private void speedUpBorder() {
        if (borderShrinkEnd < 0 || elapsed >= borderShrinkEnd) return;
        int remaining = borderShrinkEnd - elapsed;
        int faster = Math.max(30, (int) (remaining * 0.75));
        borderShrinkEnd = elapsed + faster;
        world.getWorldBorder().setSize(MIN_BORDER, faster);
        broadcast(tag() + "§cThe border speeds up! §7Fully closed in " + Msg.time(faster) + ".");
    }

    /** Every second while a hunt game runs. */
    private void tickHunt() {
        // freeze countdowns
        if (elapsed < huntersFreeAt || elapsed < runnersFreeAt) {
            for (UUID id : alive) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                int left = (isRunner(id) ? runnersFreeAt : huntersFreeAt) - elapsed;
                if (left > 0) p.sendActionBar("§b❄ Frozen §7- you can move in §e" + left + "s");
                else if (left == 0) p.sendActionBar("§aGo go go!");
            }
        }
        if (elapsed == runnersFreeAt && runnersFreeAt > 0) {
            for (UUID id : withRole(Role.RUNNER, true)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.sendTitle("§a§lRUN!", huntersFreeAt > runnersFreeAt ? "§7" + mode.hunters() + " move in " + (huntersFreeAt - runnersFreeAt) + "s" : "", 0, 30, 10);
            }
        }
        if (elapsed == huntersFreeAt && huntersFreeAt > 0) {
            broadcast(tag() + "§c§l" + mode.hunters() + " are free! §7The hunt is on.");
            for (Player p : viewers()) p.playSound(p.getLocation(), "minecraft:entity.wither.spawn", 0.5f, 1.4f);
        }
        if (s.on(Setting.AGGRO_MOBS) && elapsed % 2 == 0) {
            for (UUID id : alive) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || respawning.contains(id) || p.getGameMode() != GameMode.SURVIVAL) continue;
                for (org.bukkit.entity.Entity e : p.getNearbyEntities(16, 8, 16)) {
                    if (e instanceof org.bukkit.entity.Mob mob && !(e instanceof org.bukkit.entity.EnderDragon) && mob.getTarget() == null) {
                        mob.setTarget(p);
                    }
                }
            }
        }
        if (freezeTag()) tickFreezeTag();
        // milk clears glowing, so keep putting it back
        if (s.on(Setting.GLOWING)) {
            for (UUID id : withRole(Role.RUNNER, true)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && !p.hasPotionEffect(PotionEffectType.GLOWING)) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, PotionEffect.INFINITE_DURATION, 0, false, false));
                }
            }
        }
    }

    /** Assassins (looked-at hunters freeze) and freeze tag particles, every 2 ticks. */
    private void tickAssassins() {
        if (ended) return;
        if (!s.on(Setting.ASSASSINS)) return;
        Set<UUID> now = new HashSet<>();
        List<Player> runners = new ArrayList<>();
        for (UUID id : withRole(Role.RUNNER, true)) {
            Player r = Bukkit.getPlayer(id);
            if (r != null && r.getGameMode() == GameMode.SURVIVAL) runners.add(r);
        }
        for (UUID id : withRole(Role.HUNTER, true)) {
            if (respawning.contains(id)) continue;
            Player h = Bukkit.getPlayer(id);
            if (h == null) continue;
            for (Player r : runners) {
                if (!r.getWorld().equals(h.getWorld())) continue;
                org.bukkit.util.Vector to = h.getEyeLocation().toVector().subtract(r.getEyeLocation().toVector());
                double dist = to.length();
                if (dist > 64 || dist < 0.5) continue;
                double allowed = Math.max(0.12, Math.atan(1.2 / dist));
                if (r.getEyeLocation().getDirection().angle(to) <= allowed && r.hasLineOfSight(h)) {
                    now.add(id);
                    break;
                }
            }
        }
        for (UUID id : now) {
            if (!seen.contains(id)) {
                Player h = Bukkit.getPlayer(id);
                if (h != null) h.sendActionBar("§c§lYOU'VE BEEN SPOTTED! §7Frozen while they look at you.");
            }
        }
        seen.clear();
        seen.addAll(now);
    }

    /** Hitman punched a survivor with Freeze Tag on. */
    public void ice(Player p, Player by) {
        UUID id = p.getUniqueId();
        if (iced.containsKey(id)) return;
        iced.put(id, s.get(Setting.FREEZE_TAG));
        thawProgress.remove(id);
        p.setFreezeTicks(200);
        p.playSound(p.getLocation(), "minecraft:block.glass.break", 1f, 0.6f);
        p.sendTitle("§b§lFROZEN!", "§7A teammate can sneak next to you to thaw you", 5, 50, 10);
        broadcast(tag() + "§b❄ " + colored(id) + " §7was frozen by " + colored(by.getUniqueId()) + "§7!");
        try {
            org.bukkit.entity.TextDisplay td = p.getWorld().spawn(p.getLocation().add(0, 2.4, 0), org.bukkit.entity.TextDisplay.class);
            td.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            td.setPersistent(false);
            td.setText("§b§l❄ FROZEN");
            iceLabels.put(id, td);
        } catch (Throwable ignored) {
        }
        // everyone frozen at once = hitmen win
        boolean anyFree = false;
        for (UUID r : withRole(Role.RUNNER, true)) if (!iced.containsKey(r)) anyFree = true;
        if (!anyFree) end(Role.HUNTER.team.name(), "Every survivor is frozen!");
    }

    public void thaw(Player p, String byName) {
        UUID id = p.getUniqueId();
        if (iced.remove(id) == null) return;
        thawProgress.remove(id);
        org.bukkit.entity.TextDisplay td = iceLabels.remove(id);
        if (td != null) td.remove();
        p.setFreezeTicks(0);
        p.playSound(p.getLocation(), "minecraft:block.amethyst_block.chime", 1f, 1.4f);
        p.sendTitle("§a§lTHAWED!", byName == null ? "" : "§7Thanks, " + byName, 0, 30, 10);
        broadcast(tag() + "§a✔ " + colored(id) + " §7was thawed" + (byName == null ? "" : " by §a" + byName) + "§7!");
    }

    private void tickFreezeTag() {
        for (UUID id : new ArrayList<>(iced.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !alive.contains(id)) {
                iced.remove(id);
                org.bukkit.entity.TextDisplay td = iceLabels.remove(id);
                if (td != null) td.remove();
                continue;
            }
            p.setFreezeTicks(200);
            // a free teammate sneaking right next to them thaws them in 3s
            Player helper = null;
            for (UUID o : withRole(Role.RUNNER, true)) {
                if (o.equals(id) || iced.containsKey(o)) continue;
                Player op = Bukkit.getPlayer(o);
                if (op != null && op.isSneaking() && op.getWorld().equals(p.getWorld()) && op.getLocation().distanceSquared(p.getLocation()) <= 6.25) {
                    helper = op;
                    break;
                }
            }
            if (helper != null) {
                int prog = thawProgress.merge(id, 1, Integer::sum);
                helper.sendActionBar("§bThawing " + p.getName() + "... §f" + prog + "/3");
                p.sendActionBar("§bBeing thawed... §f" + prog + "/3");
                if (prog >= 3) {
                    thaw(p, helper.getName());
                    continue;
                }
            } else {
                thawProgress.remove(id);
            }
            // kill timer pauses while a hitman is close
            int left = iced.get(id);
            boolean hitmanNear = false;
            for (UUID h : withRole(Role.HUNTER, true)) {
                Player hp = Bukkit.getPlayer(h);
                if (hp != null && hp.getWorld().equals(p.getWorld()) && hp.getLocation().distanceSquared(p.getLocation()) <= 64) hitmanNear = true;
            }
            if (left > 0 && !hitmanNear) {
                left--;
                iced.put(id, left);
            }
            org.bukkit.entity.TextDisplay td = iceLabels.get(id);
            if (td != null) {
                td.teleport(p.getLocation().add(0, 2.4, 0));
                td.setText(left < 0 ? "§b§l❄ FROZEN" : "§b§l❄ FROZEN §c" + Msg.time(left) + (hitmanNear ? " §8(paused)" : ""));
            }
            if (left == 0) {
                iced.remove(id);
                org.bukkit.entity.TextDisplay t2 = iceLabels.remove(id);
                if (t2 != null) t2.remove();
                p.setFreezeTicks(0);
                eliminate(p, colored(id) + " §7froze solid", true);
                speedUpBorder();
            }
        }
    }

    /** Remembers portal trips so the compass can follow runners into other dimensions. */
    public void notePortal(Player p, Location from, Location to) {
        UUID id = p.getUniqueId();
        exitPortal.computeIfAbsent(id, k -> new HashMap<>()).put(from.getWorld().getName(), from.clone());
        if (to != null) entryPortal.computeIfAbsent(id, k -> new HashMap<>()).put(to.getWorld().getName(), to.clone());
    }

    /** Where a hunter's compass should point right now, plus the action bar text. */
    private Location huntPoint(Player p, StringBuilder text) {
        UUID id = p.getUniqueId();
        if (trackPortal.contains(id)) {
            Map<String, Location> mine = entryPortal.get(id);
            Location l = mine == null ? null : mine.get(p.getWorld().getName());
            if (l == null) {
                text.append("§7You haven't come through a portal in this dimension");
                return null;
            }
            text.append("§dTracking your portal §7- §a").append((int) l.distance(p.getLocation())).append("m");
            return l;
        }
        Player target = trackTarget(p);
        if (target == null) {
            text.append("§7No " + mode.runners().toLowerCase() + " left to track");
            return null;
        }
        if (target.getWorld().equals(p.getWorld())) {
            text.append("§eTracking ").append(colored(target.getUniqueId())).append(" §7- §a")
                    .append((int) target.getLocation().distance(p.getLocation())).append("m");
            if (trackLock.containsKey(id)) text.append(" §8[locked]");
            return target.getLocation();
        }
        Map<String, Location> theirs = exitPortal.get(target.getUniqueId());
        Location l = theirs == null ? null : theirs.get(p.getWorld().getName());
        if (l == null) {
            text.append("§eTracking ").append(colored(target.getUniqueId())).append(" §7- in another dimension");
            return null;
        }
        text.append("§eTracking ").append(colored(target.getUniqueId())).append("§7's portal §8(").append(dimName(target.getWorld()))
                .append("§8) §7- §a").append((int) l.distance(p.getLocation())).append("m");
        return l;
    }

    private static String dimName(World w) {
        return switch (w.getEnvironment()) {
            case NETHER -> "§cNether";
            case THE_END -> "§5End";
            default -> "§aOverworld";
        };
    }

    public World endWorld() {
        if (end == null) {
            WorldCreator wc = new WorldCreator(world.getName() + "_the_end");
            wc.seed(world.getSeed());
            wc.environment(World.Environment.THE_END);
            end = Bukkit.createWorld(wc);
            if (end != null) end.setAutoSave(false);
        }
        return end;
    }

    /** The little obsidian platform you land on in the End. */
    public Location endPlatform() {
        World e = endWorld();
        if (e == null) return null;
        int px = 100, py = 48, pz = 0;
        for (int x = px - 2; x <= px + 2; x++) {
            for (int z = pz - 2; z <= pz + 2; z++) {
                e.getBlockAt(x, py, z).setType(Material.OBSIDIAN);
                for (int y = py + 1; y <= py + 3; y++) e.getBlockAt(x, y, z).setType(Material.AIR);
            }
        }
        return new Location(e, px + 0.5, py + 1, pz + 0.5, 90f, 0f);
    }

    /** Shearing is OP: each mob variant gets its own chest loot table for the game. */
    public org.bukkit.loot.LootTables shearTable(String key) {
        return shearTables.computeIfAbsent(key, k -> {
            List<org.bukkit.loot.LootTables> chests = new ArrayList<>();
            for (org.bukkit.loot.LootTables t : org.bukkit.loot.LootTables.values()) {
                try {
                    if (t.getKey().getKey().startsWith("chests/")) chests.add(t);
                } catch (Throwable ignored) {
                }
            }
            return chests.isEmpty() ? null : chests.get(ThreadLocalRandom.current().nextInt(chests.size()));
        });
    }


    // ------------------------------------------------------------------ structure mania, lucky blocks, random crafts, shared inventory

    private int nextStructure = -1, nextLucky = -1;
    /** lucky blocks sitting in the world */
    public final Set<String> luckyPlaced = new HashSet<>();
    private final Map<Material, Material> craftMap = new HashMap<>();
    private final Map<TeamColor, ItemStack[]> shared = new HashMap<>();
    private BukkitTask shareTask;
    public static final String LUCKY_NAME = "§e§l? Lucky Block ?";

    public boolean sharedInv() {
        return mode.isHunt() && s.on(Setting.SHARED_INV);
    }

    private void twistsBegin() {
        if (s.get(Setting.STRUCTURE_MANIA) > 0) nextStructure = s.get(Setting.STRUCTURE_MANIA);
        if (s.get(Setting.LUCKY_BLOCK) > 0) nextLucky = s.get(Setting.LUCKY_BLOCK);
        if (sharedInv()) shareTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickShared, 10L, 2L);
    }

    private void tickTwists() {
        if (nextStructure > 0 && elapsed >= nextStructure) {
            nextStructure = elapsed + s.get(Setting.STRUCTURE_MANIA);
            placeStructures();
        } else if (nextStructure > 0 && nextStructure - elapsed == 10) {
            broadcast(tag() + "§dStructure Mania in §e10s§d! §7Look up.");
        }
        if (nextLucky > 0 && elapsed >= nextLucky) {
            nextLucky = elapsed + s.get(Setting.LUCKY_BLOCK);
            for (UUID id : alive) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || respawning.contains(id) || iced.containsKey(id)) continue;
                p.getInventory().addItem(luckyBlock()).values().forEach(left -> dropAt(p.getLocation(), left, id));
                p.playSound(p.getLocation(), "minecraft:entity.item.pickup", 0.8f, 0.7f);
                p.sendActionBar("§e§l+1 Lucky Block! §7Place it and break it.");
            }
        }
    }

    public static ItemStack luckyBlock() {
        ItemStack it = new ItemStack(Material.SPONGE);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(LUCKY_NAME);
        m.setLore(List.of("§7Place it, then break it.", "§7Could be amazing. Could be awful."));
        it.setItemMeta(m);
        return it;
    }

    public static boolean isLucky(ItemStack it) {
        return it != null && it.getType() == Material.SPONGE && it.hasItemMeta() && LUCKY_NAME.equals(it.getItemMeta().getDisplayName());
    }

    /** Structure Mania: everyone gets their own random structure dropped right on them. */
    private void placeStructures() {
        List<String> list = plugin.getConfig().getStringList("structure-mania.structures");
        if (list.isEmpty()) return;
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || respawning.contains(id) || p.getGameMode() != GameMode.SURVIVAL) continue;
            String st = list.get(ThreadLocalRandom.current().nextInt(list.size()));
            String key = st.contains(":") ? st : "minecraft:" + st;
            Location l = p.getLocation();
            String cmd = "execute in " + l.getWorld().getKey().getNamespace() + ":" + l.getWorld().getKey().getKey()
                    + " run place structure " + key + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
            boolean ok;
            try {
                ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (Throwable t) {
                ok = false;
            }
            if (!ok) plugin.getLogger().warning("Structure Mania couldn't place " + key);
            p.sendTitle("§d§lSTRUCTURE MANIA!", "§7You got: §f" + Msg.pretty(key.substring(key.indexOf(':') + 1).toUpperCase()), 5, 50, 10);
            p.playSound(l, "minecraft:block.anvil.land", 0.6f, 0.6f);
        }
    }

    /** Random Crafts: every result is swapped for a random item, the same swap all game. */
    public Material randomCraft(Material result) {
        return craftMap.computeIfAbsent(result, k -> {
            List<Material> pool = plugin.pool().everyItem();
            return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        });
    }

    /** Lucky block broken: roll something good, bad or weird. */
    public void luckyRoll(Player p, Location l) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        World w = l.getWorld();
        Location c = l.clone().add(0.5, 0.2, 0.5);
        String what;
        int roll = r.nextInt(22);
        switch (roll) {
            case 0, 1, 2 -> {
                int n = r.nextInt(1, 4);
                for (int i = 0; i < n; i++) dropAt(c, plugin.pool().opLoot(), null);
                what = "§6OP Loot x" + n;
            }
            case 3 -> { dropAt(c, new ItemStack(Material.DIAMOND, r.nextInt(3, 9)), null); what = "§bDiamonds!"; }
            case 4 -> { dropAt(c, new ItemStack(Material.IRON_INGOT, 16), null); what = "§fIron"; }
            case 5 -> { dropAt(c, new ItemStack(Material.GOLDEN_APPLE, 3), null); what = "§6Golden apples"; }
            case 6 -> { dropAt(c, new ItemStack(Material.ENDER_PEARL, 8), null); what = "§5Ender pearls"; }
            case 7 -> {
                for (Material m : new Material[]{Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS}) dropAt(c, new ItemStack(m), null);
                what = "§fIron armor";
            }
            case 8 -> { dropAt(c, new ItemStack(Material.BLAZE_ROD, 6), null); dropAt(c, new ItemStack(Material.ENDER_EYE, 3), null); what = "§eSpeedrun kit"; }
            case 9 -> { p.giveExp(500); what = "§aXP"; }
            case 10 -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 1200, 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 1200, 0));
                what = "§aSpeed + Strength";
            }
            case 11 -> { dropAt(c, new ItemStack(Material.TOTEM_OF_UNDYING), null); what = "§6A totem!"; }
            case 12, 13 -> {
                for (int i = 0; i < 3; i++) w.spawnEntity(c, org.bukkit.entity.EntityType.ZOMBIE);
                what = "§cZombies!";
            }
            case 14 -> { w.spawnEntity(c, org.bukkit.entity.EntityType.CREEPER); what = "§cCreeper!"; }
            case 15 -> {
                org.bukkit.entity.TNTPrimed tnt = w.spawn(c, org.bukkit.entity.TNTPrimed.class);
                tnt.setFuseTicks(40);
                what = "§cTNT! Run!";
            }
            case 16 -> { w.strikeLightning(p.getLocation()); what = "§eLightning"; }
            case 17 -> {
                Location up = p.getLocation().add(0, 12, 0);
                if (up.getBlock().getType().isAir()) up.getBlock().setType(Material.ANVIL);
                what = "§7Heads up...";
            }
            case 18 -> {
                for (int i = 0; i < 5; i++) w.spawnEntity(c, org.bukkit.entity.EntityType.SILVERFISH);
                what = "§7Silverfish";
            }
            case 19 -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 100, 1));
                what = "§fWheee";
            }
            case 20 -> { w.spawnEntity(c, org.bukkit.entity.EntityType.WITCH); what = "§5A witch"; }
            default -> { dropAt(c, new ItemStack(Material.CAKE), null); what = "§dCake :)"; }
        }
        p.sendTitle("", "§e§lLucky Block: " + what, 0, 40, 10);
        p.playSound(l, "minecraft:entity.player.levelup", 0.6f, roll <= 11 ? 1.6f : 0.6f);
    }

    /** Shared inventory: whoever's inventory changed becomes everyone on that side's inventory. Trackers stay personal. */
    private void tickShared() {
        if (ended) return;
        for (Role r : Role.values()) {
            List<Player> ps = new ArrayList<>();
            for (UUID id : withRole(r, true)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) ps.add(p);
            }
            if (ps.isEmpty()) continue;
            ItemStack[] master = shared.get(r.team);
            Player changed = null;
            ItemStack[] snap = null;
            for (Player p : ps) {
                ItemStack[] cur = masked(p.getInventory().getContents());
                if (master == null || !java.util.Arrays.equals(cur, master)) {
                    changed = p;
                    snap = cur;
                    break;
                }
            }
            if (changed == null) continue;
            shared.put(r.team, snap);
            for (Player p : ps) if (p != changed) pushShared(p, snap);
        }
    }

    private static ItemStack[] masked(ItemStack[] in) {
        ItemStack[] out = new ItemStack[in.length];
        for (int i = 0; i < in.length; i++) {
            ItemStack it = in[i];
            out[i] = it == null || it.getType() == Material.AIR || isTracker(it) ? null : it.clone();
        }
        return out;
    }

    private void pushShared(Player p, ItemStack[] snap) {
        PlayerInventory inv = p.getInventory();
        ItemStack tracker = null;
        int trackerSlot = -1;
        ItemStack[] cur = inv.getContents();
        for (int i = 0; i < cur.length; i++) if (isTracker(cur[i])) { tracker = cur[i]; trackerSlot = i; }
        ItemStack[] copy = new ItemStack[snap.length];
        for (int i = 0; i < snap.length; i++) copy[i] = snap[i] == null ? null : snap[i].clone();
        if (tracker != null) {
            if (trackerSlot < copy.length && copy[trackerSlot] == null) copy[trackerSlot] = tracker;
            else for (int i = 0; i < 36 && i < copy.length; i++) if (copy[i] == null) { copy[i] = tracker; break; }
        }
        inv.setContents(copy);
    }


    // ------------------------------------------------------------------ sides (teams or solo players)

    /** Team name, or the player's uuid in free for all. Same keys end() uses. */
    public String sideKey(UUID id) {
        TeamColor t = teams.get(id);
        return t == null ? id.toString() : t.name();
    }

    public String sideName(String side) {
        try {
            TeamColor t = TeamColor.valueOf(side);
            return t.code + "§l" + t.display;
        } catch (IllegalArgumentException e) {
            try {
                return colored(UUID.fromString(side));
            } catch (IllegalArgumentException e2) {
                return side;
            }
        }
    }

    public Material sideGlass(String side) {
        try {
            return switch (TeamColor.valueOf(side)) {
                case RED -> Material.RED_STAINED_GLASS_PANE;
                case BLUE -> Material.BLUE_STAINED_GLASS_PANE;
                case GREEN -> Material.LIME_STAINED_GLASS_PANE;
                case YELLOW -> Material.YELLOW_STAINED_GLASS_PANE;
            };
        } catch (IllegalArgumentException e) {
            return Material.GRAY_STAINED_GLASS_PANE;
        }
    }

    /** Sides with someone still playing. */
    public List<String> liveSides() {
        List<String> out = new ArrayList<>();
        for (UUID id : alive) {
            String k = sideKey(id);
            if (!out.contains(k)) out.add(k);
        }
        return out;
    }

    /** Ends with several winners sharing it (ties). */
    public void endTie(List<String> sides, String reason) {
        if (sides.size() == 1) {
            end(sides.get(0), reason);
            return;
        }
        tiedSides.clear();
        tiedSides.addAll(sides);
        end(sides.isEmpty() ? null : "TIE", reason);
    }

    private final List<String> tiedSides = new ArrayList<>();

    // ------------------------------------------------------------------ bingo

    public Bingo bingo;
    public int previewEnd = 0;
    private boolean bingoStarted = false;
    public final Map<UUID, Integer> fallSafeUntil = new HashMap<>();
    private final Set<UUID> dropInElytra = new HashSet<>();
    public static final String DROP_IN_ELYTRA = "§7Drop-in Elytra";

    /** True once the preview is over and tasks count. */
    public boolean bingoLive() {
        return bingo != null && bingoStarted && !ended;
    }

    private void bingoSetup() {
        bingo = new Bingo(plugin, this, initialSides);
        previewEnd = s.get(Setting.PREVIEW);
        if (previewEnd > 0) {
            broadcast(tag() + "§eCard preview! §7You've got §f" + previewEnd + "s §7to study it. Everyone's frozen till then.");
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (UUID id : alive) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) plugin.menus().openBingo(p);
                }
            }, 5L);
        } else {
            bingoGo();
        }
    }

    /** Preview's over: starter kit, drop-ins, go. */
    private void bingoGo() {
        bingoStarted = true;
        int spawn = s.get(Setting.SPAWN_MODE);
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menus.Menu) p.closeInventory();
            if (s.on(Setting.STARTER_KIT)) {
                p.getInventory().addItem(new ItemStack(Material.STONE_PICKAXE), new ItemStack(Material.STONE_AXE),
                        new ItemStack(Material.STONE_SHOVEL), new ItemStack(Material.STONE_SWORD), new ItemStack(Material.COOKED_BEEF, 16));
            }
            if (spawn > 0) {
                Location l = p.getLocation().clone();
                l.setY(300);
                PlayerInventory inv = p.getInventory();
                if (empty(inv.getChestplate())) {
                    ItemStack el = new ItemStack(Material.ELYTRA);
                    ItemMeta em = el.getItemMeta();
                    em.setDisplayName(DROP_IN_ELYTRA);
                    em.setUnbreakable(true);
                    el.setItemMeta(em);
                    inv.setChestplate(el);
                    dropInElytra.add(id);
                }
                if (spawn == 2) inv.addItem(new ItemStack(Material.FIREWORK_ROCKET));
                p.teleport(l);
                p.setGliding(true);
                fallSafeUntil.put(id, elapsed + 60);
            }
            p.sendTitle("§a§lGO!", "§7Right-click the card to see your tasks", 0, 40, 10);
            p.playSound(p.getLocation(), "minecraft:entity.ender_dragon.growl", 0.6f, 1.4f);
        }
        bingoEndAt = elapsed + s.get(Setting.BINGO_TIME);
        broadcast(tag() + "§a§lGO! §7You have §f" + Msg.time(s.get(Setting.BINGO_TIME)) + "§7.");
    }

    private int bingoEndAt = Integer.MAX_VALUE;

    private void tickBingo() {
        if (bingo == null) return;
        if (!bingoStarted) {
            int left = previewEnd - elapsed;
            for (UUID id : alive) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.sendActionBar("§eCard preview §7- starting in §f" + left + "s");
            }
            if (left <= 0) bingoGo();
            return;
        }
        // drop-in elytras come off once you land
        for (UUID id : new ArrayList<>(dropInElytra)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) { dropInElytra.remove(id); continue; }
            if (p.isOnGround() || !p.isGliding() && p.getLocation().getY() < 200 && elapsed > previewEnd + 5) {
                ItemStack ch = p.getInventory().getChestplate();
                if (ch != null && ch.hasItemMeta() && DROP_IN_ELYTRA.equals(ch.getItemMeta().getDisplayName())) p.getInventory().setChestplate(null);
                dropInElytra.remove(id);
            }
        }
        bingo.tick();
        if (ended) return;
        int left = bingoEndAt - elapsed;
        if (left == 300 || left == 60 || (left <= 10 && left > 0)) broadcast(tag() + "§e" + Msg.time(left) + " §7left!");
        if (left <= 0) bingo.timeUp();
    }

    /** Standard bingo with "3 finish" etc: a side that finished watches the rest. */
    public void finishSide(String side) {
        for (UUID id : new ArrayList<>(alive)) {
            if (!sideKey(id).equals(side)) continue;
            Player p = Bukkit.getPlayer(id);
            alive.remove(id);
            spectators.add(id);
            if (p != null) {
                toSpectator(p, plugin.getConfig().getString("spectator-default", "ghost").equalsIgnoreCase("ghost"));
                p.sendTitle("§6§lBINGO!", "§7You finished " + bingo.finishers.size() + (bingo.finishers.size() == 1 ? "st" : "th"), 5, 60, 10);
            }
        }
    }

    // ------------------------------------------------------------------ death shuffle

    public final Map<UUID, Death> deathTarget = new HashMap<>();
    private List<Death> deathPool;
    private static final Set<Death> NETHER_DEATHS = java.util.EnumSet.of(Death.PIGLIN, Death.ZOMBIFIED_PIGLIN, Death.BLAZE,
            Death.GHAST, Death.MAGMA_CUBE, Death.WITHER_SKELETON, Death.BED);

    private void dshSetup() {
        deathPool = new ArrayList<>();
        for (Death d : Death.values()) {
            if (d.extra && !s.on(Setting.MORE_DEATHS)) continue;
            if (NETHER_DEATHS.contains(d) && !s.on(Setting.NETHER)) continue;
            if (d == Death.PLAYER && (!s.on(Setting.PVP) || alive.size() < 2)) continue;
            if (d == Death.KINETIC && !s.on(Setting.ELYTRA_RACE)) continue;
            if (d == Death.STARVE || d == Death.LIGHTNING) continue; // can't really pull these off in a round
            deathPool.add(d);
        }
        for (UUID id : alive) score.put(id, 0);
        roundGap = 3;
    }

    private Death randomDeath() {
        return deathPool.get(ThreadLocalRandom.current().nextInt(deathPool.size()));
    }

    private void tickDeathShuffle() {
        if (roundGap > 0) {
            roundGap--;
            if (roundGap == 0) dshStartRound();
            return;
        }
        if (round == 0) return;
        roundLeft--;
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            Death t = deathTarget.get(id);
            String time = " §8• §f" + Msg.time(roundLeft);
            if (found.contains(id)) p.sendActionBar("§a✔ Done this round!" + time);
            else p.sendActionBar("§c☠ " + (t == null ? "?" : t.display) + time);
            if (roundLeft > 0 && roundLeft <= 10 && !found.contains(id)) {
                p.sendTitle("", (roundLeft <= 3 ? "§c§l" : "§e") + roundLeft + "s left!", 0, 25, 5);
                p.playSound(p.getLocation(), "minecraft:block.note_block.pling", 0.8f, roundLeft <= 3 ? 2f : 1f);
            }
        }
        if (roundLeft <= 0) dshEndRound();
    }

    private void dshStartRound() {
        round++;
        found.clear();
        deathTarget.clear();
        skipVotes.clear();
        List<Player> online = new ArrayList<>();
        for (UUID id : alive) { Player ap = Bukkit.getPlayer(id); if (ap != null) online.add(ap); }
        Death same = s.on(Setting.SAME_BLOCK) ? doableDeath(online) : null;
        for (Player ap : online) deathTarget.put(ap.getUniqueId(), same != null ? same : doableDeath(List.of(ap)));
        roundLeft = s.get(Setting.ROUND_TIME);
        broadcast(" ");
        broadcast(tag() + "§c§lRound " + round + "! §7You have §f" + Msg.time(roundLeft) + " §7to die the right way."
                + (s.on(Setting.VOTE_SKIP) ? " §8(/skip to vote for new ones)" : ""));
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            Death t = deathTarget.get(id);
            if (p == null || t == null) continue;
            p.sendMessage("§c§l☠ Your death: §f§l" + t.display);
            p.sendTitle("§c" + t.display, "§7Die this way before time's up!", 5, 60, 10);
            p.playSound(p.getLocation(), "minecraft:entity.wither.ambient", 0.6f, 1.2f);
        }
    }

    /** Lethal hit in Death Shuffle. Right way = done for the round, either way you respawn. */
    public void dshDeath(Player p, String msg, String cause, String source, String entity) {
        UUID id = p.getUniqueId();
        Death t = deathTarget.get(id);
        boolean hit = t != null && round > 0 && roundGap == 0 && roundLeft > 0 && !found.contains(id) && t.matches(cause, source, entity);
        respawn(p, msg + (hit ? " §a✔" : ""), hit ? "§a✔ That's the one!" : found.contains(id) ? "§7Already done this round" : "§7Wrong way...");
        if (!hit) return;
        found.add(id);
        score.merge(id, 1, Integer::sum);
        broadcast("§a✔ " + colored(id) + " §7pulled off §f" + t.display + "§7!");
        ding(p);
        int left = alive.size() - found.size();
        if (left == 0 || (s.on(Setting.ELIMINATION_MODE) && alive.size() > 1 && left == 1)) dshEndRound();
    }

    private void dshEndRound() {
        if (ended || roundGap > 0) return;
        List<UUID> losers = new ArrayList<>();
        for (UUID id : alive) if (!found.contains(id)) losers.add(id);
        if (found.isEmpty() && !losers.isEmpty()) {
            broadcast(tag() + "§eNobody managed it, so nobody's out!");
            losers.clear();
        }
        roundLeft = 0;
        for (UUID id : losers) {
            Player p = Bukkit.getPlayer(id);
            Death t = deathTarget.get(id);
            String why = "didn't " + (t == null ? "die the right way" : "manage: " + t.display.toLowerCase(java.util.Locale.ROOT));
            int left = lives.getOrDefault(id, 1);
            if (left > 1) {
                lives.put(id, left - 1);
                broadcast("§c✖ " + colored(id) + " §7" + why + " §8(" + (left - 1) + " " + (left - 1 == 1 ? "life" : "lives") + " left)");
                if (p != null) p.sendTitle("§c§l-1 LIFE", "§7" + (left - 1) + " left", 5, 40, 10);
            } else if (p != null) {
                eliminate(p, colored(id) + " §7" + why, true);
            } else {
                alive.remove(id);
            }
            if (ended) return;
        }
        if (!ended) {
            roundGap = 5;
            broadcast(tag() + "§7Next round in 5 seconds...");
        }
    }

    private void dshLines(List<String> lines) {
        lines.add("§fRound: §a" + Math.max(1, round));
        if (roundGap > 0 || round == 0) lines.add("§fNext round: §e" + roundGap + "s");
        else lines.add("§fDeath Time: " + (roundLeft <= 10 ? "§c" : "§e") + Msg.time(roundLeft));
        if (round > 0) lines.add("§fDone: §a" + found.size() + "§7/" + alive.size());
    }

    private void bingoLines(List<String> lines) {
        if (bingo == null) return;
        if (!bingoStarted) {
            lines.add("§fPreview: §e" + Math.max(0, previewEnd - elapsed) + "s");
        } else {
            lines.add("§fTime Left: §a" + Msg.time(Math.max(0, bingoEndAt - elapsed)));
        }
        lines.add("§fMode: §e" + (bingo.mode == 1 ? "Lockout" : bingo.mode == 2 ? "Single" : "Standard") + " §7" + bingo.size + "x" + bingo.size);
        lines.add("     ");
        List<String> sides = bingo.sides();
        sides.sort((a, b) -> bingo.score(b) - bingo.score(a));
        int shown = 0;
        for (String side : sides) {
            if (shown++ >= 6) break;
            String val = bingo.mode == 1 ? "§6" + bingo.score(side) + " pts" : "§a" + bingo.count(side) + "§7/" + bingo.card.length;
            lines.add(sideName(side) + " " + val + (bingo.finishers.contains(side) ? " §6✔" : ""));
        }
    }

    private void runLines(List<String> lines) {
        lines.add("§b§lSPEEDRUNNERS §7(" + alive.size() + ")");
        int shown = 0;
        for (UUID id : alive) {
            if (shown++ >= 6) break;
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            String dim = p.getWorld().getEnvironment() == World.Environment.NETHER ? " §4☀"
                    : p.getWorld().getEnvironment() == World.Environment.THE_END ? " §5✦" : "";
            lines.add("§f" + name(id) + " §c" + (int) Math.ceil(p.getHealth() / 2.0) + "❤" + dim);
        }
    }


    // ------------------------------------------------------------------ is this death doable from here?

    private static final Map<Death, String[]> DEATH_BIOMES = new java.util.EnumMap<>(Death.class);
    private static final String[] OCEANS = {"ocean", "deep_ocean", "cold_ocean", "deep_cold_ocean", "lukewarm_ocean",
            "deep_lukewarm_ocean", "warm_ocean", "frozen_ocean", "deep_frozen_ocean"};

    static {
        DEATH_BIOMES.put(Death.BERRY, new String[]{"taiga", "snowy_taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga"});
        DEATH_BIOMES.put(Death.CACTUS, new String[]{"desert", "badlands", "eroded_badlands", "wooded_badlands"});
        DEATH_BIOMES.put(Death.FREEZE, new String[]{"snowy_slopes", "grove"});
        DEATH_BIOMES.put(Death.MAGMA, OCEANS);
        DEATH_BIOMES.put(Death.SLIME, new String[]{"swamp", "mangrove_swamp"});
        DEATH_BIOMES.put(Death.WITCH, new String[]{"swamp", "mangrove_swamp"});
        DEATH_BIOMES.put(Death.POLAR_BEAR, new String[]{"snowy_plains", "ice_spikes", "frozen_ocean", "deep_frozen_ocean"});
        DEATH_BIOMES.put(Death.LLAMA, new String[]{"windswept_hills", "windswept_gravelly_hills", "windswept_forest", "savanna",
                "savanna_plateau", "windswept_savanna", "meadow"});
        DEATH_BIOMES.put(Death.GOAT, new String[]{"jagged_peaks", "frozen_peaks", "snowy_slopes"});
        DEATH_BIOMES.put(Death.WOLF, new String[]{"forest", "taiga", "snowy_taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga",
                "grove", "savanna", "wooded_badlands", "sparse_jungle"});
        DEATH_BIOMES.put(Death.BEE, new String[]{"plains", "sunflower_plains", "flower_forest", "meadow", "cherry_grove", "forest", "birch_forest"});
        DEATH_BIOMES.put(Death.DROWNED, new String[]{"river", "frozen_river", "ocean", "deep_ocean", "cold_ocean", "deep_cold_ocean",
                "lukewarm_ocean", "deep_lukewarm_ocean", "warm_ocean"});
        DEATH_BIOMES.put(Death.STALACTITE, new String[]{"dripstone_caves"});
        DEATH_BIOMES.put(Death.SILVERFISH, new String[]{"windswept_hills", "windswept_gravelly_hills", "windswept_forest"});
    }

    private final Map<String, Boolean> feasibleCache = new HashMap<>();

    /** Is there somewhere close enough to pull this death off? Cached per 256-block area. */
    private boolean feasible(Death d, Location l) {
        if (d == Death.PHANTOM) return false; // needs 3 sleepless nights
        boolean needsBiome = DEATH_BIOMES.containsKey(d);
        boolean needsStructure = d == Death.IRON_GOLEM || d == Death.GUARDIAN || d == Death.PILLAGER || d == Death.VINDICATOR;
        if (!needsBiome && !needsStructure) return true;
        if (l == null || l.getWorld() == null || l.getWorld().getEnvironment() != World.Environment.NORMAL) return !needsStructure && !needsBiome;
        int range = plugin.getConfig().getInt("death-shuffle.max-distance", 600);
        String key = d.name() + ":" + l.getWorld().getName() + ":" + (l.getBlockX() >> 8) + ":" + (l.getBlockZ() >> 8);
        Boolean cached = feasibleCache.get(key);
        if (cached != null) return cached;
        boolean ok = false;
        try {
            if (needsBiome) {
                List<org.bukkit.block.Biome> list = new ArrayList<>();
                for (String b : DEATH_BIOMES.get(d)) {
                    try {
                        list.add(org.bukkit.block.Biome.valueOf(b.toUpperCase(java.util.Locale.ROOT)));
                    } catch (Throwable ignored) {
                    }
                }
                ok = !list.isEmpty() && l.getWorld().locateNearestBiome(l, range, list.toArray(new org.bukkit.block.Biome[0])) != null;
            } else {
                List<org.bukkit.generator.structure.Structure> list = switch (d) {
                    case IRON_GOLEM -> List.of(org.bukkit.generator.structure.Structure.VILLAGE_PLAINS, org.bukkit.generator.structure.Structure.VILLAGE_DESERT,
                            org.bukkit.generator.structure.Structure.VILLAGE_SAVANNA, org.bukkit.generator.structure.Structure.VILLAGE_SNOWY,
                            org.bukkit.generator.structure.Structure.VILLAGE_TAIGA);
                    case GUARDIAN -> List.of(org.bukkit.generator.structure.Structure.MONUMENT);
                    case PILLAGER -> List.of(org.bukkit.generator.structure.Structure.PILLAGER_OUTPOST);
                    default -> List.of(org.bukkit.generator.structure.Structure.MANSION);
                };
                for (org.bukkit.generator.structure.Structure st : list) {
                    if (l.getWorld().locateNearestStructure(l, st, Math.max(1, range / 16), false) != null) {
                        ok = true;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            ok = false;
        }
        feasibleCache.put(key, ok);
        return ok;
    }

    /** A random death this player can actually reach in time. */
    private Death doableDeath(List<Player> forWho) {
        for (int tries = 0; tries < 15; tries++) {
            Death d = randomDeath();
            boolean all = true;
            for (Player p : forWho) if (!feasible(d, p.getLocation())) { all = false; break; }
            if (all) return d;
        }
        // nothing special worked out, fall back to something that works anywhere
        List<Death> anywhere = new ArrayList<>();
        for (Death d : deathPool) if (!DEATH_BIOMES.containsKey(d) && d != Death.IRON_GOLEM && d != Death.GUARDIAN
                && d != Death.PILLAGER && d != Death.VINDICATOR && d != Death.PHANTOM) anywhere.add(d);
        return anywhere.isEmpty() ? randomDeath() : anywhere.get(ThreadLocalRandom.current().nextInt(anywhere.size()));
    }

    // ------------------------------------------------------------------ vote skip

    public final Set<UUID> skipVotes = new HashSet<>();

    private boolean roundRunning() {
        if (mode == Mode.BLOCKSHUFFLE) return round > 0 && roundGap == 0 && !choosing && roundLeft > 0 && s.get(Setting.BS_FORMAT) != 1;
        if (mode == Mode.DEATHSHUFFLE) return round > 0 && roundGap == 0 && roundLeft > 0;
        return false;
    }

    public void voteSkip(Player p) {
        UUID id = p.getUniqueId();
        if (!Setting.VOTE_SKIP.in(mode)) { p.sendMessage(Msg.GAME + "§cThere's nothing to skip in this mode."); return; }
        if (!s.on(Setting.VOTE_SKIP)) { p.sendMessage(Msg.GAME + "§cVote skip is off this game."); return; }
        if (!alive.contains(id)) { p.sendMessage(Msg.GAME + "§cOnly players still in can vote."); return; }
        if (!roundRunning()) { p.sendMessage(Msg.GAME + "§cWait for the round to start."); return; }
        boolean on = skipVotes.add(id);
        if (!on) skipVotes.remove(id);
        int need = alive.size() / 2 + 1;
        String what = mode == Mode.BLOCKSHUFFLE ? "blocks" : "deaths";
        broadcast(tag() + "§e" + p.getName() + (on ? " §7voted to skip this round's " : " §7took back their skip vote ") + "§8(§f" + skipVotes.size() + "§7/" + need + "§8) §7Type §e/skip §7to vote.");
        if (skipVotes.size() < need) return;
        skipVotes.clear();
        roundLeft = s.get(Setting.ROUND_TIME);
        List<Player> left = new ArrayList<>();
        for (UUID a : alive) {
            Player ap = Bukkit.getPlayer(a);
            if (ap != null && !found.contains(a)) left.add(ap);
        }
        if (mode == Mode.BLOCKSHUFFLE) {
            Material same = s.on(Setting.SAME_BLOCK) ? randomBlock(null) : null;
            for (Player ap : left) {
                Material m = same != null ? same : randomBlock(target.get(ap.getUniqueId()));
                target.put(ap.getUniqueId(), m);
                ap.sendMessage("§e§l➜ New block: §f§l" + Msg.pretty(m));
                ap.sendTitle("§e" + Msg.pretty(m), "§7Skipped! Timer reset.", 5, 50, 10);
            }
        } else {
            Death same = s.on(Setting.SAME_BLOCK) ? doableDeath(left) : null;
            for (Player ap : left) {
                Death d = same != null ? same : doableDeath(List.of(ap));
                deathTarget.put(ap.getUniqueId(), d);
                ap.sendMessage("§c§l☠ New death: §f§l" + d.display);
                ap.sendTitle("§c" + d.display, "§7Skipped! Timer reset.", 5, 50, 10);
            }
        }
        broadcast(tag() + "§a§lVote passed! §7New " + what + " for everyone still going, and the timer reset to §f" + Msg.time(roundLeft) + "§7.");
        for (Player v : viewers()) v.playSound(v.getLocation(), "minecraft:block.note_block.pling", 1f, 1.2f);
    }

    /** Someone accomplished something: big sound for them, a little ding for everyone else. */
    /** first time each player reaches the nether / end (speedrun, manhunt, hitman) */
    public final Set<String> milestones = new HashSet<>();

    public void ding(Player who) {
        for (Player v : viewers()) {
            if (v.equals(who)) v.playSound(v.getLocation(), "minecraft:ui.toast.challenge_complete", 0.7f, 1.2f);
            else v.playSound(v.getLocation(), "minecraft:block.note_block.chime", 0.6f, 1.4f);
        }
    }

    // ------------------------------------------------------------------ block shuffle

    public int round = 0;
    public int roundLeft = 0;
    private int roundGap = 0;
    public boolean choosing = false;
    private int chooseLeft = 0;
    public final Map<UUID, Material> target = new HashMap<>();
    public final Set<UUID> found = new HashSet<>();
    public final Map<UUID, Integer> score = new HashMap<>();
    public final Map<UUID, Material> chosen = new HashMap<>();
    public final Map<UUID, List<Material>> pickOptions = new HashMap<>();
    private BukkitTask standTask;
    private List<Material> bsStarter, bsFull;

    private List<Material> loadBlocks(String path) {
        List<Material> out = new ArrayList<>();
        for (String n : plugin.getConfig().getStringList(path)) {
            Material m = Material.matchMaterial(n);
            if (m != null && m.isBlock()) out.add(m);
        }
        return out;
    }

    private void bsSetup() {
        bsStarter = loadBlocks("blockshuffle.starter-blocks");
        bsFull = loadBlocks("blockshuffle.blocks");
        if (s.on(Setting.NETHER)) bsFull.addAll(loadBlocks("blockshuffle.nether-blocks"));
        if (bsStarter.isEmpty()) bsStarter.addAll(List.of(Material.DIRT, Material.STONE, Material.OAK_LOG, Material.SAND));
        if (bsFull.isEmpty()) bsFull.addAll(bsStarter);
        for (UUID id : alive) score.put(id, 0);
        roundGap = 3;
        standTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkStanding, 5L, 4L);
    }

    private Material randomBlock(Material avoid) {
        List<Material> pool = round <= 2 ? bsStarter : bsFull;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 10; i++) {
            Material m = pool.get(r.nextInt(pool.size()));
            if (m != avoid) return m;
        }
        return pool.get(r.nextInt(pool.size()));
    }

    private void tickShuffle() {
        if (choosing) {
            chooseLeft--;
            for (UUID id : alive) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                Material c = chosen.get(id);
                p.sendActionBar(c == null ? "§ePick your block! §f" + chooseLeft + "s §8• §7/pick to reopen the menu"
                        : "§aYou picked §f" + Msg.pretty(c) + " §8• §7round starts in " + chooseLeft + "s");
            }
            if (chooseLeft <= 0) finishChoosing();
            return;
        }
        if (roundGap > 0) {
            roundGap--;
            if (roundGap == 0) startRound();
            return;
        }
        if (round == 0) return;
        roundLeft--;
        if (roundLeft > 0 && roundLeft <= 10) {
            for (UUID id : alive) {
                if (found.contains(id) && s.get(Setting.BS_FORMAT) != 2) continue;
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                p.sendTitle("", (roundLeft <= 3 ? "§c§l" : "§e") + roundLeft + "s left!", 0, 25, 5);
                p.playSound(p.getLocation(), "minecraft:block.note_block.pling", 0.8f, roundLeft <= 3 ? 2f : 1f);
            }
        }
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            Material t = target.get(id);
            String time = " §8• §f" + Msg.time(roundLeft);
            if (s.get(Setting.BS_FORMAT) == 2) {
                p.sendActionBar("§eFind: §f" + (t == null ? "?" : Msg.pretty(t)) + " §8• §6Score: " + score.getOrDefault(id, 0) + time);
            } else if (found.contains(id)) {
                p.sendActionBar("§a✔ Found your block!" + time);
            } else {
                p.sendActionBar("§eFind: §f" + (t == null ? "?" : Msg.pretty(t)) + time);
            }
        }
        if (roundLeft <= 0) endRound();
    }

    private void startRound() {
        round++;
        found.clear();
        target.clear();
        skipVotes.clear();
        int format = s.get(Setting.BS_FORMAT);
        if (format == 1 && round % 2 == 1) {
            // blockswap: pick your own block first
            choosing = true;
            chooseLeft = 30;
            chosen.clear();
            pickOptions.clear();
            for (UUID id : alive) {
                List<Material> opts = new ArrayList<>();
                for (int i = 0; i < 27 && opts.size() < 9; i++) {
                    Material m = randomBlock(null);
                    if (!opts.contains(m)) opts.add(m);
                }
                pickOptions.put(id, opts);
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    plugin.menus().openBlockPick(p, party);
                    p.sendTitle("§b§lPICK A BLOCK", "§7Next round someone else has to find it", 5, 40, 10);
                }
            }
            broadcast(tag() + "§b§lRound " + round + " §7- pick your own block! §8(30s)");
            return;
        }
        if (format == 1) {
            // blockswap: hand the picked blocks to other players
            List<UUID> ids = new ArrayList<>(alive);
            java.util.Collections.shuffle(ids);
            for (int i = 0; i < ids.size(); i++) {
                UUID from = ids.get((i + 1) % ids.size());
                Material m = chosen.getOrDefault(from, randomBlock(null));
                target.put(ids.get(i), m);
                Player p = Bukkit.getPlayer(ids.get(i));
                if (p != null && ids.size() > 1) p.sendMessage(tag() + "You got " + colored(from) + "§7's pick.");
            }
        } else {
            Material same = s.on(Setting.SAME_BLOCK) ? randomBlock(null) : null;
            for (UUID id : alive) target.put(id, same != null ? same : randomBlock(null));
        }
        beginFinding();
    }

    private void finishChoosing() {
        choosing = false;
        for (UUID id : alive) {
            if (!chosen.containsKey(id)) {
                List<Material> opts = pickOptions.get(id);
                Material m = opts == null || opts.isEmpty() ? randomBlock(null) : opts.get(ThreadLocalRandom.current().nextInt(opts.size()));
                chosen.put(id, m);
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.sendMessage(tag() + "Time's up, you got §f" + Msg.pretty(m) + "§7.");
            }
            target.put(id, chosen.get(id));
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getOpenInventory().getTopInventory().getHolder() instanceof Menus.Menu) p.closeInventory();
        }
        beginFinding();
    }

    private void beginFinding() {
        roundLeft = s.get(Setting.ROUND_TIME);
        broadcast(" ");
        broadcast(tag() + "§a§lRound " + round + "! §7You have §f" + Msg.time(roundLeft) + "§7."
                + (s.on(Setting.VOTE_SKIP) && s.get(Setting.BS_FORMAT) != 1 ? " §8(/skip to vote for new blocks)" : ""));
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            Material t = target.get(id);
            if (p == null || t == null) continue;
            p.sendMessage("§e§l➜ Your block: §f§l" + Msg.pretty(t));
            p.sendTitle("§e" + Msg.pretty(t), "§7Find it and stand on it!", 5, 50, 10);
            p.playSound(p.getLocation(), "minecraft:block.note_block.bell", 1f, 1.2f);
        }
    }

    private void checkStanding() {
        if (ended || choosing || round == 0 || roundGap > 0 || roundLeft <= 0) return;
        for (UUID id : new ArrayList<>(alive)) {
            Player p = Bukkit.getPlayer(id);
            Material t = target.get(id);
            if (p == null || t == null || (found.contains(id) && s.get(Setting.BS_FORMAT) != 2)) continue;
            Location l = p.getLocation();
            Material feet = l.getBlock().getType();
            Material below = l.clone().subtract(0, 0.2, 0).getBlock().getType();
            if (feet == t || below == t) onFound(p, t);
            if (ended || roundGap > 0) return;
        }
    }

    private void onFound(Player p, Material t) {
        UUID id = p.getUniqueId();
        ding(p);
        if (s.get(Setting.BS_FORMAT) == 2) {
            int sc = score.merge(id, 1, Integer::sum);
            Material next = randomBlock(t);
            target.put(id, next);
            broadcast("§a✔ " + colored(id) + " §7found §f" + Msg.pretty(t) + " §8(§6" + sc + "§8)");
            p.sendMessage("§e§l➜ Next block: §f§l" + Msg.pretty(next));
            p.sendTitle("§a+1", "§7Next: §e" + Msg.pretty(next), 0, 30, 5);
            return;
        }
        found.add(id);
        broadcast("§a✔ " + colored(id) + " §7found their block! §8(" + Msg.pretty(t) + ")");
        p.sendTitle("§a§lFOUND IT!", "§7Safe this round", 0, 30, 10);
        int left = alive.size() - found.size();
        if (left == 0) {
            endRound();
        } else if (s.on(Setting.ELIMINATION_MODE) && alive.size() > 1 && left == 1) {
            endRound();
        }
    }

    private void endRound() {
        if (ended || roundGap > 0 || choosing) return;
        int format = s.get(Setting.BS_FORMAT);
        List<UUID> losers = new ArrayList<>();
        if (format == 2) {
            if (alive.size() > 1) {
                int low = Integer.MAX_VALUE;
                for (UUID id : alive) low = Math.min(low, score.getOrDefault(id, 0));
                List<UUID> lowest = new ArrayList<>();
                for (UUID id : alive) if (score.getOrDefault(id, 0) == low) lowest.add(id);
                losers.add(lowest.get(ThreadLocalRandom.current().nextInt(lowest.size())));
            }
        } else {
            for (UUID id : alive) if (!found.contains(id)) losers.add(id);
            if (found.isEmpty() && !losers.isEmpty()) {
                broadcast(tag() + "§eNobody found their block, so nobody's out!");
                losers.clear();
            }
        }
        roundLeft = 0;
        for (UUID id : losers) {
            Player p = Bukkit.getPlayer(id);
            Material t = target.get(id);
            String why = format == 2 ? "had the lowest score" : "didn't find " + (t == null ? "their block" : Msg.pretty(t));
            int left = lives.getOrDefault(id, 1);
            if (left > 1) {
                lives.put(id, left - 1);
                broadcast("§c✖ " + colored(id) + " §7" + why + " §8(" + (left - 1) + " " + (left - 1 == 1 ? "life" : "lives") + " left)");
                if (p != null) p.sendTitle("§c§l-1 LIFE", "§7" + (left - 1) + " left", 5, 40, 10);
            } else if (p != null) {
                eliminate(p, colored(id) + " §7" + why, true);
            } else {
                alive.remove(id);
            }
            if (ended) return;
        }
        if (!ended) {
            roundGap = 5;
            broadcast(tag() + "§7Next round in 5 seconds...");
        }
    }

    private void doDrop() {
        dropCount++;
        // sweep the ground first, anything left lying around is gone
        if (s.on(Setting.GROUND_SWEEP)) {
            sweep(world);
            sweep(nether);
        }

        boolean first = dropCount == 1;
        int share = s.get(Setting.SHARE_ITEMS);
        Map<UUID, String> dropSummary = new LinkedHashMap<>();
        for (UUID id : new ArrayList<>(alive)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            ItemPool.Roll roll = plugin.pool().roll(id, first, s, dropHistory);
            PlayerInventory inv = p.getInventory();
            for (ItemStack it : roll.stacks) {
                Map<Integer, ItemStack> left = inv.addItem(it);
                for (ItemStack over : left.values()) {
                    plugin.allowItemSpawn = true;
                    Item dropped;
                    try {
                        dropped = p.getWorld().dropItem(p.getLocation(), over);
                    } finally {
                        plugin.allowItemSpawn = false;
                    }
                    dropped.setPickupDelay(0);
                    dropped.setThrower(id);
                    dropItems.add(dropped.getUniqueId());
                    if (share == 0) dropped.setOwner(id);
                }
            }
            p.sendMessage("§6§l➜ ITEM DROP: §e" + roll.name);
            dropSummary.put(id, roll.name);
            if (counted) plugin.stats().add(id, p.getName(), mode.key, Stats.Stat.DROPS, 1);
            p.sendTitle("", "§6" + roll.name, 2, 30, 8);
            p.playSound(p.getLocation(), "minecraft:block.anvil.land", 0.5f, 1.2f);
        }
        // spectators get a rundown of what everyone just received
        if (!dropSummary.isEmpty() && !spectators.isEmpty()) {
            for (UUID sid : spectators) {
                if (alive.contains(sid)) continue;
                Player sp = Bukkit.getPlayer(sid);
                if (sp == null) continue;
                sp.sendMessage("§6§l➜ ITEM DROP §7(everyone)");
                for (Map.Entry<UUID, String> en : dropSummary.entrySet()) {
                    sp.sendMessage(" " + colored(en.getKey()) + "§8: §e" + en.getValue());
                }
            }
        }
    }

    private void sweep(World w) {
        if (w == null) return;
        for (Item it : w.getEntitiesByClass(Item.class)) it.remove();
    }

    private void tickBorder() {
        if (this.mode == Mode.MANHUNT || this.mode == Mode.DEATHSHUFFLE || borderShrinkEnd >= 0) return;
        int mode = s.get(Setting.SHRINKING_BORDER);
        if (mode == 3 && Setting.SHRINKING_BORDER.in(this.mode)) {
            tickZones();
            return;
        }
        double full = radius * 2.0;
        // each phase: {seconds to wait (first one counts from the start, the rest from the last shrink ending),
        //              target size, shortest allowed shrink time}
        double[][] phases;
        double speed;
        if (mode == 1) {
            phases = new double[][]{{120, full * 0.6, 30}, {60, full * 0.3, 30}, {60, MIN_BORDER, 30}};
            speed = plugin.getConfig().getDouble("border.normal-speed", 1.5);
        } else if (mode == 2) {
            phases = new double[][]{{60, full * 0.5, 20}, {45, full * 0.2, 20}, {45, MIN_BORDER, 20}};
            speed = plugin.getConfig().getDouble("border.rush-speed", 2.5);
        } else {
            int close = s.get(Setting.BORDER_CLOSE);
            phases = close < 0 ? new double[0][] : new double[][]{{close, MIN_BORDER, 30}};
            speed = plugin.getConfig().getDouble("border.normal-speed", 1.5);
        }
        speed = Math.max(0.2, speed);
        if (borderPhase >= phases.length) return;
        double[] ph = phases[borderPhase];
        if (borderNextAt < 0) borderNextAt = (int) ph[0];
        if (elapsed == borderNextAt - 30) {
            broadcast(Msg.GAME + "§eThe border starts shrinking in §c30 seconds§e!");
        }
        if (elapsed >= borderNextAt) {
            double target = Math.max(MIN_BORDER, ph[1]);
            double cur = world.getWorldBorder().getSize();
            // each side only moves at "speed" blocks per second, so it's always walkable
            int dur = (int) Math.max(ph[2], Math.ceil(Math.max(0, cur - target) / 2.0 / speed));
            borderPhase++;
            borderSize = target;
            world.getWorldBorder().setSize(borderSize, (long) dur);
            if (nether != null) nether.getWorldBorder().setSize(Math.max(MIN_BORDER, borderSize / 8), (long) dur);
            borderNextAt = elapsed + dur + (borderPhase < phases.length ? (int) phases[borderPhase][0] : 0);
            broadcast(Msg.GAME + "§c§lThe border is shrinking! §7Down to §e±" + (int) (borderSize / 2) + " §7over " + Msg.time(dur) + ".");
            for (Player p : viewers()) p.playSound(p.getLocation(), "minecraft:entity.wither.spawn", 0.5f, 1.2f);
        }
    }

    // ------------------------------------------------------------------ zones (fortnite style border)

    private static final double[] ZONE_SIZES = {0.6, 0.35, 0.18, 0.08, 0.0};
    private int zoneStage = -1, zoneWaitEnd, zoneShrinkEnd;
    private double zoneFromX, zoneFromZ, zoneFromSize, zoneToX, zoneToZ, zoneToSize;
    private boolean zoneShrinking;

    /** Picks the next circle somewhere inside the current one. */
    private void planZone() {
        WorldBorder b = world.getWorldBorder();
        zoneFromX = b.getCenter().getX();
        zoneFromZ = b.getCenter().getZ();
        zoneFromSize = b.getSize();
        double full = radius * 2.0;
        zoneToSize = Math.max(MIN_BORDER, full * ZONE_SIZES[zoneStage]);
        double slack = Math.max(0, (zoneFromSize - zoneToSize) / 2.0);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        zoneToX = zoneFromX + (slack > 0 ? r.nextDouble(-slack, slack) : 0);
        zoneToZ = zoneFromZ + (slack > 0 ? r.nextDouble(-slack, slack) : 0);
        // wait before it closes, then it closes at a walkable speed (blocks per second, per side)
        int wait = Math.max(10, plugin.getConfig().getInt("zones.wait-seconds", 45));
        double speed = Math.max(0.2, plugin.getConfig().getDouble("zones.shrink-speed", 1.5));
        double sideTravel = (zoneFromSize - zoneToSize) / 2.0 + Math.max(Math.abs(zoneToX - zoneFromX), Math.abs(zoneToZ - zoneFromZ));
        int shrinkLen = (int) Math.max(30, Math.min(400, Math.ceil(sideTravel / speed)));
        zoneWaitEnd = elapsed + wait;
        zoneShrinkEnd = zoneWaitEnd + shrinkLen;
        zoneShrinking = false;
        broadcast(tag() + "§d§lNext zone: §fcentered at §e" + (int) zoneToX + ", " + (int) zoneToZ + " §7(±" + (int) (zoneToSize / 2)
                + ")§f. It starts closing in §e" + Msg.time(zoneWaitEnd - elapsed) + "§f.");
    }

    private void tickZones() {
        if (zoneStage < 0) {
            if (elapsed < Math.max(30, s.get(Setting.GRACE))) return;
            zoneStage = 0;
            planZone();
            return;
        }
        if (zoneStage >= ZONE_SIZES.length) return;
        if (!zoneShrinking) {
            int left = zoneWaitEnd - elapsed;
            if (left == 30) {
                broadcast(tag() + "§d⚠ The zone starts closing in §e30s§d! §7Head to §e" + (int) zoneToX + ", " + (int) zoneToZ);
                for (Player p : viewers()) p.playSound(p.getLocation(), "minecraft:block.note_block.bell", 0.8f, 0.8f);
            }
            if (left <= 0) {
                zoneShrinking = true;
                broadcast(tag() + "§d§lThe zone is closing! §7Get inside §e" + (int) zoneToX + ", " + (int) zoneToZ + " §7(±" + (int) (zoneToSize / 2) + ")");
                for (Player p : viewers()) p.playSound(p.getLocation(), "minecraft:entity.wither.spawn", 0.5f, 1.2f);
            }
            return;
        }
        double f = Math.min(1.0, (elapsed - zoneWaitEnd) / (double) Math.max(1, zoneShrinkEnd - zoneWaitEnd));
        WorldBorder b = world.getWorldBorder();
        b.setCenter(zoneFromX + (zoneToX - zoneFromX) * f, zoneFromZ + (zoneToZ - zoneFromZ) * f);
        b.setSize(zoneFromSize + (zoneToSize - zoneFromSize) * f, 1L);
        borderSize = b.getSize();
        if (f >= 1.0) {
            zoneStage++;
            if (zoneStage < ZONE_SIZES.length) planZone();
            else broadcast(tag() + "§d§lThe zone has closed completely!");
        }
    }

    private String zoneLine() {
        if (zoneStage < 0) return "§fZone: §7waiting";
        if (zoneStage >= ZONE_SIZES.length) return "§fZone: §dfinal";
        if (zoneShrinking) return "§fZone: §d§lclosing §7" + Msg.time(Math.max(0, zoneShrinkEnd - elapsed));
        return "§fZone: §ecloses in " + Msg.time(Math.max(0, zoneWaitEnd - elapsed));
    }

    private boolean zonesOn() {
        return Setting.SHRINKING_BORDER.in(mode) && s.get(Setting.SHRINKING_BORDER) == 3;
    }

    private void tickTracker() {
        if (ended) return;
        updateSpecSidebar();
        tickNav();
        if (mode.isHunt()) {
            tickHuntTracker();
            return;
        }
        if (mode != Mode.RIC && !(mode == Mode.BINGO && s.on(Setting.BINGO_COMPASS))) return;
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            Player target = trackTarget(p);
            PlayerInventory inv = p.getInventory();
            boolean holding = isTracker(inv.getItemInMainHand()) || isTracker(inv.getItemInOffHand());
            // only touch the compass when the target actually moved, so it doesn't bob in your hand
            boolean needsUpdate = false;
            if (target != null && target.getWorld().equals(p.getWorld())) {
                Location last = lastPointed.get(id);
                Location now = target.getLocation();
                if (last == null || !last.getWorld().equals(now.getWorld()) || last.distanceSquared(now) > 9) {
                    lastPointed.put(id, now);
                    needsUpdate = true;
                }
            }
            for (int slot = 0; needsUpdate && slot < inv.getSize(); slot++) {
                ItemStack it = inv.getItem(slot);
                if (!isTracker(it)) continue;
                if (target != null && target.getWorld().equals(p.getWorld())) {
                    CompassMeta cm = (CompassMeta) it.getItemMeta();
                    cm.setLodestone(target.getLocation());
                    cm.setLodestoneTracked(false);
                    it.setItemMeta(cm);
                    inv.setItem(slot, it);
                }
            }
            if (holding) {
                if (target == null) {
                    p.sendActionBar("§7No one left to track");
                } else if (!target.getWorld().equals(p.getWorld())) {
                    p.sendActionBar("§eTracking " + colored(target.getUniqueId()) + " §7- they're in a different dimension");
                } else {
                    int dist = (int) target.getLocation().distance(p.getLocation());
                    String lock = trackLock.containsKey(id) ? " §8[locked]" : "";
                    p.sendActionBar("§eTracking " + colored(target.getUniqueId()) + " §7- §a" + dist + "m" + lock);
                }
            }
        }
    }

    private void tickHuntTracker() {
        for (UUID id : alive) {
            if (!isHunter(id) || respawning.contains(id)) continue;
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            PlayerInventory inv = p.getInventory();
            boolean holding = isTracker(inv.getItemInMainHand()) || isTracker(inv.getItemInOffHand());
            StringBuilder text = new StringBuilder();
            Location point = huntPoint(p, text);
            if (point != null && point.getWorld().equals(p.getWorld())) {
                Location last = lastPointed.get(id);
                if (last == null || !last.getWorld().equals(point.getWorld()) || last.distanceSquared(point) > 9) {
                    lastPointed.put(id, point.clone());
                    for (int slot = 0; slot < inv.getSize(); slot++) {
                        ItemStack it = inv.getItem(slot);
                        if (!isTracker(it)) continue;
                        CompassMeta cm = (CompassMeta) it.getItemMeta();
                        cm.setLodestone(point);
                        cm.setLodestoneTracked(false);
                        it.setItemMeta(cm);
                        inv.setItem(slot, it);
                    }
                }
            }
            if (holding && !isFrozen(id)) p.sendActionBar(text.toString());
        }
    }

    /** Alive enemies of this player, sorted by name. */
    public List<UUID> enemiesOf(UUID id) {
        List<UUID> out = new ArrayList<>();
        for (UUID o : alive) if (!o.equals(id) && !sameTeam(id, o) && !respawning.contains(o)) out.add(o);
        out.sort((a, b) -> name(a).compareToIgnoreCase(name(b)));
        return out;
    }

    /** Left-click on the tracker: nearest -> each enemy -> back to nearest. */
    public void cycleTrack(Player p) {
        UUID id = p.getUniqueId();
        trackPortal.remove(id);
        List<UUID> enemies = enemiesOf(id);
        if (enemies.isEmpty()) {
            p.sendActionBar("§7No one left to track");
            return;
        }
        UUID cur = trackLock.get(id);
        int idx = cur == null ? -1 : enemies.indexOf(cur);
        if (idx + 1 >= enemies.size()) {
            trackLock.remove(id);
            p.sendActionBar("§eTracking: §fNearest enemy");
        } else {
            UUID next = enemies.get(idx + 1);
            trackLock.put(id, next);
            p.sendActionBar("§eTracking: " + colored(next));
        }
        lastPointed.remove(id);
        p.playSound(p.getLocation(), "minecraft:ui.button.click", 0.5f, 1.6f);
    }

    /** Blocks players placed this game. Breaking these still drops them when World Loot is off. */
    public final Set<String> placed = new HashSet<>();

    public static String blockKey(org.bukkit.block.Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    public Player trackTarget(Player p) {
        UUID id = p.getUniqueId();
        UUID locked = trackLock.get(id);
        if (locked != null) {
            Player t = Bukkit.getPlayer(locked);
            if (t != null && alive.contains(locked)) return t;
            trackLock.remove(id);
        }
        Player best = null;
        double bestD = Double.MAX_VALUE;
        Player anyOther = null;
        for (UUID o : alive) {
            if (o.equals(id) || sameTeam(id, o) || respawning.contains(o)) continue;
            Player t = Bukkit.getPlayer(o);
            if (t == null) continue;
            if (!t.getWorld().equals(p.getWorld())) { anyOther = t; continue; }
            double d = t.getLocation().distanceSquared(p.getLocation());
            if (d < bestD) { bestD = d; best = t; }
        }
        return best != null ? best : anyOther;
    }

    // ------------------------------------------------------------------ sidebar

    private static final int LINES = 15;

    private static final int SPEC_LINES = 15;
    private Scoreboard specBoard;
    private Objective specObj;

    private Objective makeBoard(Scoreboard sb, String title, int lines) {
        Objective o = sb.registerNewObjective("lgric", "dummy", title);
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        try {
            o.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank());
        } catch (Throwable ignored) {
            // older API, numbers just show
        }
        ChatColor[] cc = ChatColor.values();
        for (int i = 0; i < lines; i++) {
            Team t = sb.registerNewTeam("line" + i);
            t.addEntry(cc[i].toString() + "§r");
        }
        for (TeamColor tc : TeamColor.values()) {
            Team t = sb.registerNewTeam("t_" + tc.name().toLowerCase());
            t.setColor(tc.chat);
            if (mode.isHunt() && (tc == Role.RUNNER.team || tc == Role.HUNTER.team)) {
                Role r = tc == Role.RUNNER.team ? Role.RUNNER : Role.HUNTER;
                t.setPrefix(tc.code + "[" + r.display(mode) + "] ");
            } else {
                t.setPrefix(tc.code + "[" + tc.display.charAt(0) + "] ");
            }
            t.setAllowFriendlyFire(true);
        }
        for (Map.Entry<UUID, TeamColor> e : teams.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) sb.getTeam("t_" + e.getValue().name().toLowerCase()).addEntry(p.getName());
        }
        return o;
    }

    private void setupScoreboard() {
        board = Bukkit.getScoreboardManager().getNewScoreboard();
        obj = makeBoard(board, mode.sidebarTitle, LINES);

        // spectators get their own board: everyone's health on the sidebar and under their names
        specBoard = Bukkit.getScoreboardManager().getNewScoreboard();
        specObj = makeBoard(specBoard, "§b§lSPECTATING", SPEC_LINES);
        try {
            Objective hp = specBoard.registerNewObjective("lghp", "health", "§c❤", RenderType.HEARTS);
            hp.setDisplaySlot(DisplaySlot.BELOW_NAME);
        } catch (Throwable ignored) {
        }
        updateSidebar();
    }

    public Scoreboard spectatorBoard() {
        return specBoard != null ? specBoard : board;
    }

    private void setLines(Scoreboard sb, Objective o, List<String> lines, int max) {
        ChatColor[] cc = ChatColor.values();
        for (int i = 0; i < max; i++) {
            String entry = cc[i].toString() + "§r";
            if (i < lines.size()) {
                sb.getTeam("line" + i).setPrefix(lines.get(i));
                o.getScore(entry).setScore(max - i);
            } else {
                sb.resetScores(entry);
            }
        }
    }

    private void updateSpecSidebar() {
        if (specBoard == null) return;
        List<String> lines = new ArrayList<>();
        lines.add(timeLine());
        if (mode == Mode.RIC && s.on(Setting.DROP_COUNTDOWN)) lines.add("§fNext Drop: §e" + Msg.time(nextDrop));
        if (mode == Mode.DEATHSWAP) lines.add("§fNext Swap: " + swapTimerText());
        if (mode == Mode.BLOCKSHUFFLE) bsLines(lines);
        if (mode == Mode.DEATHSHUFFLE) dshLines(lines);
        if (mode == Mode.BINGO) {
            lines.clear();
            bingoLines(lines);
            setLines(specBoard, specObj, lines, SPEC_LINES);
            return;
        }
        if (mode != Mode.MANHUNT && mode != Mode.DEATHSHUFFLE) lines.add("§fBorder: §e±" + (int) (world.getWorldBorder().getSize() / 2));
        if (zonesOn()) lines.add(zoneLine());
        lines.add(" ");
        lines.add("§c§lALIVE §7(" + alive.size() + ")");
        List<Player> ps = new ArrayList<>();
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) ps.add(p);
        }
        ps.sort((x, y) -> Double.compare(y.getHealth(), x.getHealth()));
        int room = SPEC_LINES - lines.size();
        for (int i = 0; i < ps.size() && i < room; i++) {
            Player p = ps.get(i);
            double hp = Math.ceil(p.getHealth()) / 2.0;
            String hpText = hp == Math.floor(hp) ? String.valueOf((int) hp) : String.valueOf(hp);
            String col = p.getHealth() > 13 ? "§a" : p.getHealth() > 6 ? "§e" : "§c";
            lines.add(colored(p.getUniqueId()) + " " + col + hpText + "❤");
        }
        setLines(specBoard, specObj, lines, SPEC_LINES);
    }

    private String timeLine() {
        if (mode == Mode.MANHUNT || mode == Mode.BINGO) return "§fTime: §a" + Msg.time(elapsed);
        if (mode == Mode.SPEEDRUN) return "§fTime: §a" + Msg.time(elapsed) + " §8(" + Msg.time(Math.max(0, s.get(Setting.TIME_LIMIT) - elapsed)) + " left)";
        return "§fTime Left: §a" + Msg.time(s.get(Setting.TIME_LIMIT) - elapsed);
    }

    private void huntLines(List<String> lines) {
        List<UUID> runners = withRole(Role.RUNNER, false);
        lines.add(Role.RUNNER.code() + "§l" + mode.runners().toUpperCase() + " §7(" + withRole(Role.RUNNER, true).size() + " left)");
        int shown = 0;
        for (UUID id : runners) {
            if (shown++ >= 4) break;
            if (!alive.contains(id)) {
                lines.add("§8✖ §7§m" + name(id));
                continue;
            }
            Player p = Bukkit.getPlayer(id);
            String hp = p == null ? "" : " §c" + (int) Math.ceil(p.getHealth() / 2.0) + "❤";
            String state = iced.containsKey(id) ? " §b❄" : p != null && p.getWorld().getEnvironment() == World.Environment.NETHER ? " §4☀"
                    : p != null && p.getWorld().getEnvironment() == World.Environment.THE_END ? " §5✦" : "";
            lines.add("§a✔ §f" + name(id) + hp + state);
        }
        if (runners.size() > 4) lines.add("§7...and " + (runners.size() - 4) + " more");
        lines.add("    ");
        lines.add(Role.HUNTER.code() + "§l" + mode.hunters().toUpperCase() + "§7: §f" + withRole(Role.HUNTER, true).size());
    }

    public Scoreboard board() {
        return board;
    }

    private void updateSidebar() {
        if (board == null) return;
        List<String> lines = new ArrayList<>();
        lines.add("§8" + id);
        lines.add(" ");
        lines.add(timeLine());
        if (mode == Mode.RIC && s.on(Setting.DROP_COUNTDOWN)) lines.add("§fNext Drop: §e" + Msg.time(nextDrop));
        if (mode == Mode.DEATHSWAP) lines.add("§fNext Swap: " + swapTimerText());
        if (mode == Mode.BLOCKSHUFFLE) bsLines(lines);
        if (mode.isHunt() && elapsed < huntersFreeAt) lines.add("§f" + mode.hunters() + " free in: §b" + (huntersFreeAt - elapsed) + "s");
        if (mode == Mode.DEATHSHUFFLE) dshLines(lines);
        if (mode == Mode.BINGO) {
            lines.clear();
            lines.add("§8" + id);
            lines.add(" ");
            bingoLines(lines);
            lines.add("   ");
            lines.add("§aplay.foldedlettuce.xyz");
            setLines(board, obj, lines, LINES);
            updateSpecSidebar();
            return;
        }
        if (mode == Mode.SPEEDRUN) {
            lines.add("  ");
            runLines(lines);
            lines.add("   ");
            lines.add("§aplay.foldedlettuce.xyz");
            setLines(board, obj, lines, LINES);
            updateSpecSidebar();
            return;
        }
        if (isGrace()) lines.add("§fGrace: §e" + Msg.time(s.get(Setting.GRACE) - elapsed));
        if (mode != Mode.MANHUNT && mode != Mode.DEATHSHUFFLE) lines.add("§fBorder: §e±" + (int) (world.getWorldBorder().getSize() / 2));
        if (zonesOn()) lines.add(zoneLine());
        lines.add("  ");
        if (mode.isHunt()) {
            huntLines(lines);
            lines.add("   ");
            lines.add("§aplay.foldedlettuce.xyz");
            setLines(board, obj, lines, LINES);
            updateSpecSidebar();
            return;
        }
        if (mode == Mode.BLOCKSHUFFLE && s.get(Setting.BS_FORMAT) == 2 && alive.size() <= 6) {
            List<UUID> byScore = new ArrayList<>(alive);
            byScore.sort((x, y) -> score.getOrDefault(y, 0) - score.getOrDefault(x, 0));
            for (UUID id : byScore) lines.add(colored(id) + " §6" + score.getOrDefault(id, 0));
            lines.add("     ");
        }
        if (mode != Mode.RIC && s.get(Setting.LIVES) > 1 && alive.size() <= 6) {
            for (UUID id : alive) lines.add(colored(id) + " §c" + "❤".repeat(Math.max(0, lives.getOrDefault(id, 1))));
            lines.add("    ");
        }
        if (teams.isEmpty()) {
            lines.add("§fPlayers Left: §a" + alive.size());
        } else {
            Map<TeamColor, Integer> left = new LinkedHashMap<>();
            for (UUID id : alive) left.merge(teams.get(id), 1, Integer::sum);
            for (TeamColor tc : TeamColor.values()) {
                if (!teams.containsValue(tc)) continue;
                Integer c = left.get(tc);
                lines.add(tc.code + tc.display + ": " + (c == null ? "§c✖" : "§a" + c + " alive"));
            }
        }
        lines.add("   ");
        lines.add("§aplay.foldedlettuce.xyz");

        setLines(board, obj, lines, LINES);
        updateSpecSidebar();
    }

    // ------------------------------------------------------------------ deaths

    public void eliminate(Player p, String deathMessage, boolean dropItems) {
        UUID id = p.getUniqueId();
        if (!alive.remove(id)) return;
        placement.put(id, alive.size() + 1);
        outAt.put(id, elapsed);
        if (counted) plugin.stats().add(id, name(id), mode.key, Stats.Stat.DEATHS, 1);
        Location loc = p.getLocation();
        if (dropItems && s.get(Setting.DEATH_LOOT) > 0) {
            for (ItemStack it : p.getInventory().getContents()) {
                if (it == null || it.getType() == Material.AIR || isTracker(it) || Bingo.isCard(it) || isReusableRocket(it)) continue;
                plugin.allowItemSpawn = true;
                try {
                    Item dropped = loc.getWorld().dropItemNaturally(loc, it);
                    dropped.setThrower(id);
                    deathItems.add(dropped.getUniqueId());
                } finally {
                    plugin.allowItemSpawn = false;
                }
            }
        }
        p.getInventory().clear();
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        p.setHealth(20.0);
        p.setFireTicks(0);
        p.setFoodLevel(20);
        spectators.add(id);
        toSpectator(p, plugin.getConfig().getString("spectator-default", "ghost").equalsIgnoreCase("ghost"));
        trackLock.values().removeIf(v -> v.equals(id));

        broadcast("§c✖ " + deathMessage);
        for (Player v : viewers()) v.playSound(v.getLocation(), "minecraft:entity.lightning_bolt.thunder", 0.4f, 1.5f);
        if (p.isOnline()) {
            p.sendTitle("§c§lELIMINATED", "§7/spec teleporter • /specmode walk/fly • /leave", 5, 60, 15);
            Msg.tellraw(p, "[" + Msg.plain("You're spectating. ", "gray") + ","
                    + Msg.button("[Spectate]", "aqua", "/spec", "Teleport to a player") + "," + Msg.plain(" ", "gray") + ","
                    + Msg.button("[Leave Game]", "red", "/leave", "Back to the waiting area") + "]");
        }
        checkWin();
    }

    private void creditKill(UUID killer) {
        kills.merge(killer, 1, Integer::sum);
        if (counted) plugin.stats().add(killer, name(killer), mode.key, Stats.Stat.KILLS, 1);
    }

    public String deathMessage(Player p, String causeName) {
        return deathMessage(p, causeName, null, null);
    }

    private static String a(String thing) {
        return ("AEIOU".indexOf(Character.toUpperCase(thing.charAt(0))) >= 0 ? "an " : "a ") + thing;
    }

    /** Vanilla-style death message, as specific as we can make it. source = mob / shooter / block, entity = projectile etc. */
    public String deathMessage(Player p, String causeName, String source, String entity) {
        UUID id = p.getUniqueId();
        UUID killer = lastDamager.get(id);
        Long at = lastDamageAt.get(id);
        boolean recent = killer != null && at != null && System.currentTimeMillis() - at < 10_000;
        String who = colored(id) + "§7";
        if (recent && causeName != null && (causeName.equals("ENTITY_ATTACK") || causeName.equals("PROJECTILE")
                || causeName.equals("ENTITY_SWEEP_ATTACK") || causeName.equals("MAGIC") || causeName.equals("THORNS"))
                && (source == null || source.equals("PLAYER"))) {
            creditKill(killer);
            Player kp = Bukkit.getPlayer(killer);
            String with = "";
            if (kp != null) {
                ItemStack hand = kp.getInventory().getItemInMainHand();
                if (hand != null && hand.getType() != Material.AIR) {
                    String n = hand.hasItemMeta() && hand.getItemMeta().hasDisplayName() ? hand.getItemMeta().getDisplayName() : Msg.pretty(hand.getType());
                    with = " §7using §f" + n;
                }
            }
            String how = causeName.equals("PROJECTILE") ? "was shot by " : causeName.equals("MAGIC") ? "was killed by magic from " : "was slain by ";
            return who + " " + how + colored(killer) + (causeName.equals("PROJECTILE") ? "" : with);
        }
        String mob = source == null || source.equals("PLAYER") ? null : Msg.pretty(source);
        String ent = entity == null ? "" : entity;
        String src = source == null ? "" : source;
        String base = switch (causeName == null ? "" : causeName) {
            case "FALL" -> src.equals("GOAT") ? "was rammed off a cliff by a Goat" : "hit the ground too hard";
            case "LAVA" -> "tried to swim in lava";
            case "FIRE" -> "went up in flames";
            case "FIRE_TICK" -> mob != null && !src.equals(ent) ? "was burnt to a crisp by " + a(mob) : "burned to death";
            case "CAMPFIRE" -> "burned to death on a campfire";
            case "HOT_FLOOR" -> "discovered the floor was lava (magma block)";
            case "DROWNING" -> "drowned";
            case "VOID" -> "fell out of the world";
            case "WORLD_BORDER" -> "got squished by the border";
            case "SUFFOCATION" -> "suffocated in a wall";
            case "CRAMMING" -> "was squished too much";
            case "STARVATION" -> "starved to death";
            case "WITHER" -> mob != null ? "withered away fighting " + a(mob) : "withered away";
            case "POISON" -> mob != null ? "was poisoned by " + a(mob) : "was poisoned";
            case "FREEZE" -> "froze to death in powder snow";
            case "FLY_INTO_WALL" -> "flew into a wall (kinetic energy)";
            case "LIGHTNING" -> "was struck by lightning";
            case "SONIC_BOOM" -> "was obliterated by a Warden's sonic boom";
            case "DRAGON_BREATH" -> "was roasted in dragon's breath";
            case "THORNS" -> mob != null ? "was killed trying to hurt " + a(mob) : "was killed by thorns";
            case "STALAGMITE" -> "was impaled on a stalagmite";
            case "CONTACT" -> switch (src) {
                case "CACTUS" -> "was pricked to death by a cactus";
                case "SWEET_BERRY_BUSH" -> "was poked to death by a sweet berry bush";
                case "CAMPFIRE", "SOUL_CAMPFIRE" -> "burned to death on a campfire";
                case "POINTED_DRIPSTONE" -> "was impaled on a stalagmite";
                default -> mob != null ? "was hurt to death by " + Msg.pretty(src).toLowerCase(java.util.Locale.ROOT) : "was pricked to death";
            };
            case "FALLING_BLOCK" -> src.contains("ANVIL") ? "was squished by a falling anvil"
                    : src.equals("POINTED_DRIPSTONE") ? "was skewered by a falling stalactite"
                    : mob != null ? "was squashed by falling " + Msg.pretty(src).toLowerCase(java.util.Locale.ROOT) : "was squashed by a falling block";
            case "BLOCK_EXPLOSION" -> src.equals("BED") ? "was killed by [Intentional Game Design] (exploding bed)"
                    : src.equals("RESPAWN_ANCHOR") ? "blew up with a respawn anchor"
                    : ent.contains("TNT") || src.contains("TNT") ? "blew up with TNT" : "blew up";
            case "ENTITY_EXPLOSION" -> ent.equals("FIREWORK_ROCKET") ? "went off with a bang (firework)"
                    : ent.contains("TNT") ? "blew up with TNT"
                    : ent.equals("END_CRYSTAL") ? "was blown up by an end crystal"
                    : ent.equals("WITHER_SKULL") ? "was blown up by a Wither"
                    : ent.equals("FIREBALL") ? "was fireballed by " + a(mob == null ? "Ghast" : mob)
                    : mob != null ? "was blown up by " + a(mob) : "blew up";
            case "PROJECTILE" -> ent.equals("LLAMA_SPIT") ? "was spat on to death by " + a(mob == null ? "Llama" : mob)
                    : ent.equals("TRIDENT") ? "was impaled by " + a(mob == null ? "Drowned" : mob)
                    : ent.equals("SMALL_FIREBALL") || ent.equals("FIREBALL") ? "was fireballed by " + a(mob == null ? "Blaze" : mob)
                    : ent.equals("SHULKER_BULLET") ? "was sniped by a Shulker"
                    : ent.equals("WIND_CHARGE") || ent.equals("BREEZE_WIND_CHARGE") ? "was blown away by " + a(mob == null ? "Breeze" : mob)
                    : mob != null ? "was shot by " + a(mob) : "was shot";
            case "MAGIC" -> src.equals("WITCH") ? "was killed by a Witch's potion"
                    : src.contains("GUARDIAN") ? "was zapped to death by " + a(mob)
                    : src.equals("EVOKER") || ent.equals("EVOKER_FANGS") ? "was chomped by evoker fangs"
                    : "was killed by magic";
            case "ENTITY_ATTACK", "ENTITY_SWEEP_ATTACK" -> src.equals("BEE") ? "was stung to death by a Bee"
                    : mob != null ? "was slain by " + a(mob) : "was killed by a mob";
            case "DISCONNECT" -> "disconnected";
            case "FORFEIT" -> "forfeited";
            default -> "died";
        };
        if (recent && !causeName.equals("DISCONNECT") && !causeName.equals("FORFEIT")) {
            creditKill(killer);
            return who + " " + base + " while fighting " + colored(killer);
        }
        // death swap: dying soon after a swap credits whoever's spot you landed in
        UUID trapper = swappedInto.get(id);
        int trapWindow = plugin.getConfig().getInt("deathswap.trap-credit-seconds", 20);
        boolean quit = "DISCONNECT".equals(causeName) || "FORFEIT".equals(causeName);
        if (mode == Mode.DEATHSWAP && trapper != null && !trapper.equals(id) && !quit
                && elapsed - lastSwapAt <= trapWindow && participants.contains(trapper)) {
            creditKill(trapper);
            return who + " " + base + " §8(§d" + name(trapper) + "'s trap§8)";
        }
        return who + " " + base;
    }

    private void checkWin() {
        if (ended) return;
        if (mode.respawns()) {
            // nobody gets knocked out in these, the game only ends on its own terms
            if (alive.isEmpty()) end(null, "Everyone left.");
            return;
        }
        Set<String> sides = new HashSet<>();
        for (UUID id : alive) {
            TeamColor t = teams.get(id);
            sides.add(t == null ? id.toString() : t.name());
        }
        int left = alive.size();
        if (left > 0 && left <= 3) broadcast(Msg.GAME + "§e" + left + " §7player" + (left == 1 ? "" : "s") + " left.");
        if (initialSides >= 2 && sides.size() <= 1) {
            end(sides.isEmpty() ? null : sides.iterator().next(), null);
        } else if (initialSides < 2 && alive.isEmpty()) {
            end(null, null);
        }
    }

    // ------------------------------------------------------------------ joining / leaving

    public void addSpectator(Player p) {
        UUID id = p.getUniqueId();
        spectators.add(id);
        names.put(id, p.getName());
        Player t = null;
        for (UUID a : alive) { t = Bukkit.getPlayer(a); if (t != null) break; }
        p.teleport(t != null ? t.getLocation() : world.getSpawnLocation());
        toSpectator(p, plugin.getConfig().getString("spectator-default", "ghost").equalsIgnoreCase("ghost"));
        p.sendMessage(Msg.GAME + "You're spectating. §e/spec §7to teleport, §e/specmode §7to switch walk/fly or noclip, §c/leave §7to go back.");
    }

    // ------------------------------------------------------------------ spectator modes

    /** Spectators in "ghost" mode: creative-style flying (double-tap jump), hidden from players, can't touch anything. */
    public final Set<UUID> ghosts = new HashSet<>();

    public static final String GHOST_TP = "§b§lTeleporter §7(Right-click)";
    public static final String GHOST_NOCLIP = "§d§lNoclip Mode §7(Right-click)";
    public static final String GHOST_LEAVE = "§c§lLeave Game §7(Right-click)";

    public void toSpectator(Player p, boolean ghost) {
        UUID id = p.getUniqueId();
        p.setScoreboard(spectatorBoard());
        p.getInventory().clear();
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        p.setFireTicks(0);
        p.setHealth(20.0);
        p.setFoodLevel(20);
        if (ghost) {
            ghosts.add(id);
            ghostTeam(true, p);
            p.setGameMode(GameMode.ADVENTURE);
            p.setAllowFlight(true);
            p.setFlying(true);
            p.setInvisible(true);
            p.setCollidable(false);
            noPush(p, true);
            p.setCanPickupItems(false);
            if (plugin.getConfig().getBoolean("spectator-tablist", true)) {
                // stay in the tab list: invisible, silent, no nametag, held items hidden (see hideGhostGear)
                p.setSilent(true);
                hideGhostGear(p);
            } else {
                for (UUID a : alive) {
                    Player ap = Bukkit.getPlayer(a);
                    if (ap != null) ap.hidePlayer(plugin, p);
                }
            }
            PlayerInventory inv = p.getInventory();
            inv.setItem(0, Menus.item(Material.COMPASS, GHOST_TP, "§7Teleport to a player."));
            inv.setItem(4, Menus.item(Material.ENDER_EYE, GHOST_NOCLIP, "§7Switch to real spectator mode:", "§7fly through walls and see", "§7through players' eyes."));
            inv.setItem(8, Menus.item(Material.RED_BED, GHOST_LEAVE, "§7Back to the waiting area."));
        } else {
            boolean wasGhost = ghosts.remove(id);
            ghostTeam(false, p);
            p.setInvisible(false);
            p.setSilent(false);
            p.setCollidable(true);
            noPush(p, false);
            p.setCanPickupItems(true);
            p.setGameMode(GameMode.SPECTATOR);
            if (wasGhost) {
                for (Player o : Bukkit.getOnlinePlayers()) o.showPlayer(plugin, p);
            }
        }
    }

    public static final String NO_PUSH_TEAM = "lg_nopush";

    /**
     * Walk/fly spectators must never shove players, mobs, boats or minecarts. Player#setCollidable alone isn't enough:
     * the server decides pushing from the MAIN scoreboard's team collision rules (our per-match boards don't count),
     * and a team with collision NEVER stops the push in both directions.
     */
    public static void noPush(Player p, boolean on) {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        Team t = main.getTeam(NO_PUSH_TEAM);
        if (on) {
            if (t == null) {
                t = main.registerNewTeam(NO_PUSH_TEAM);
                t.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
                t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            }
            // a player can only be on one team of a board; the main board isn't used for anything else here
            t.addEntry(p.getName());
        } else if (t != null) {
            t.removeEntry(p.getName());
        }
    }

    /** Clears leftovers (the main scoreboard is saved to disk, so a crash mid-game could leave people on the team). */
    public static void clearNoPushTeam() {
        Team t = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(NO_PUSH_TEAM);
        if (t != null) t.unregister();
    }

    /** Ghost spectators share a team on the spectator board that can see friendly invisibles, so they show as shadows to each other. */
    private void ghostTeam(boolean join, Player p) {
        Scoreboard sb = spectatorBoard();
        if (sb == null) return;
        Team t = sb.getTeam("zz_ghosts");
        if (t == null) {
            t = sb.registerNewTeam("zz_ghosts");
            t.setCanSeeFriendlyInvisibles(true);
            t.setPrefix("§7[Spec] ");
            t.setColor(ChatColor.GRAY);
        }
        if (join) {
            for (Team other : sb.getTeams()) if (other != t && other.hasEntry(p.getName())) other.removeEntry(p.getName());
            t.addEntry(p.getName());
        } else {
            t.removeEntry(p.getName());
        }
        // and on the players' board: gray [Spec] in tab, never a nametag
        if (board != null && board != sb) {
            Team bt = board.getTeam("zz_ghosts");
            if (bt == null) {
                bt = board.registerNewTeam("zz_ghosts");
                bt.setPrefix("§7[Spec] ");
                bt.setColor(ChatColor.GRAY);
                bt.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            }
            if (join) {
                for (Team other : board.getTeams()) if (other != bt && other.hasEntry(p.getName())) other.removeEntry(p.getName());
                bt.addEntry(p.getName());
            } else {
                bt.removeEntry(p.getName());
            }
        }
    }

    private static final org.bukkit.inventory.EquipmentSlot[] GEAR = {org.bukkit.inventory.EquipmentSlot.HAND,
            org.bukkit.inventory.EquipmentSlot.OFF_HAND, org.bukkit.inventory.EquipmentSlot.HEAD, org.bukkit.inventory.EquipmentSlot.CHEST,
            org.bukkit.inventory.EquipmentSlot.LEGS, org.bukkit.inventory.EquipmentSlot.FEET};

    /** Players still in the game see ghosts as holding/wearing nothing (invisibility alone still shows items). */
    public void hideGhostGear(Player ghost) {
        ItemStack air = new ItemStack(Material.AIR);
        for (UUID a : alive) {
            Player ap = Bukkit.getPlayer(a);
            if (ap == null || ap.equals(ghost) || !ap.getWorld().equals(ghost.getWorld())) continue;
            for (org.bukkit.inventory.EquipmentSlot slot : GEAR) {
                try {
                    ap.sendEquipmentChange(ghost, slot, air);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ teammate locator (boss bar)

    private final Map<UUID, org.bukkit.boss.BossBar> navBars = new HashMap<>();

    private boolean navMate(UUID a, UUID b) {
        if (a.equals(b)) return false;
        if (mode == Mode.SPEEDRUN) return true; // everyone's on the same side
        TeamColor ta = teams.get(a);
        return ta != null && ta == teams.get(b);
    }

    private static final String[] ARROWS = {"⬆", "⬈", "➡", "⬊", "⬇", "⬋", "⬅", "⬉"};

    /** Vanilla's locator bar can't filter by team, so it's off in games and this shows only your teammates. */
    private void tickNav() {
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            List<String> parts = new ArrayList<>();
            for (UUID o : alive) {
                if (!navMate(id, o) || respawning.contains(o)) continue;
                Player op = Bukkit.getPlayer(o);
                if (op == null) continue;
                if (!op.getWorld().equals(p.getWorld())) {
                    String dim = op.getWorld().getEnvironment() == World.Environment.NETHER ? "Nether" : op.getWorld().getEnvironment() == World.Environment.THE_END ? "End" : "Overworld";
                    parts.add(colored(o) + " §8(" + dim + ")");
                    continue;
                }
                double dx = op.getLocation().getX() - p.getLocation().getX(), dz = op.getLocation().getZ() - p.getLocation().getZ();
                double bearing = Math.toDegrees(Math.atan2(-dx, dz));
                double rel = ((bearing - p.getLocation().getYaw()) % 360 + 540) % 360 - 180;
                int idx = ((int) Math.round(rel / 45.0) % 8 + 8) % 8;
                parts.add("§e" + ARROWS[idx] + " " + colored(o) + " §7" + (int) Math.sqrt(dx * dx + dz * dz) + "m");
                if (parts.size() >= 4) break;
            }
            org.bukkit.boss.BossBar bar = navBars.get(id);
            if (parts.isEmpty() || respawning.contains(id)) {
                if (bar != null) {
                    bar.removeAll();
                    navBars.remove(id);
                }
                continue;
            }
            if (bar == null) {
                bar = Bukkit.createBossBar("", org.bukkit.boss.BarColor.GREEN, org.bukkit.boss.BarStyle.SOLID);
                bar.setProgress(1.0);
                bar.addPlayer(p);
                navBars.put(id, bar);
            }
            bar.setTitle(String.join("   ", parts));
        }
        for (UUID id : new ArrayList<>(navBars.keySet())) {
            if (!alive.contains(id) || ended) {
                navBars.remove(id).removeAll();
            }
        }
    }

    public void toggleSpecMode(Player p) {
        boolean toGhost = !ghosts.contains(p.getUniqueId());
        toSpectator(p, toGhost);
        p.sendMessage(Msg.GAME + (toGhost
                ? "§bWalk/fly mode. §7Double-tap jump to switch between flying and walking."
                : "§dNoclip mode. §7Fly through walls, left-click a player to see through their eyes."));
    }

    /** Undo ghost hiding, used when a player leaves the game. */
    public void clearGhost(Player p) {
        if (!ghosts.remove(p.getUniqueId())) return;
        p.setInvisible(false);
        p.setCollidable(true);
        noPush(p, false);
        p.setCanPickupItems(true);
        for (Player o : Bukkit.getOnlinePlayers()) o.showPlayer(plugin, p);
    }

    /** Player left this match (via /leave or disconnect). */
    public void removePlayer(Player p, boolean disconnect) {
        UUID id = p.getUniqueId();
        clearGhost(p);
        if (alive.contains(id)) {
            eliminate(p, deathMessage(p, disconnect ? "DISCONNECT" : "FORFEIT"), true);
        }
        spectators.remove(id);
        if (!ended && !loading && alive.isEmpty() && spectators.isEmpty()) end(null, "Everyone left.");
    }

    // ------------------------------------------------------------------ ending

    public void end(String winningSide, String reason) {
        if (ended) return;
        ended = true;
        if (loadTask != null) loadTask.cancel();
        if (secondTask != null) secondTask.cancel();
        if (trackerTask != null) trackerTask.cancel();
        if (standTask != null) standTask.cancel();
        if (huntTask != null) huntTask.cancel();
        if (shareTask != null) shareTask.cancel();
        for (org.bukkit.boss.BossBar b : navBars.values()) b.removeAll();
        navBars.clear();
        for (org.bukkit.entity.TextDisplay td : iceLabels.values()) td.remove();
        iceLabels.clear();
        iced.clear();
        seen.clear();
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) LettuceGames.setScale(p, 1.0);
        }

        List<UUID> winners = new ArrayList<>();
        if ("ALL".equals(winningSide)) {
            // speedrun: everyone still in the game wins together
            winners.addAll(alive);
        } else if ("TIE".equals(winningSide)) {
            for (UUID id : participants) if (tiedSides.contains(sideKey(id))) winners.add(id);
        } else if (winningSide != null) {
            for (UUID id : participants) {
                TeamColor t = teams.get(id);
                String side = t == null ? id.toString() : t.name();
                if (side.equals(winningSide)) winners.add(id);
            }
        }

        if (counted) {
            for (UUID id : participants) {
                boolean won = winners.contains(id);
                int place = won ? 1 : placement.getOrDefault(id, 0);
                int secs = outAt.getOrDefault(id, elapsed);
                plugin.stats().finishGame(id, name(id), mode.key, won, place, kills.getOrDefault(id, 0), secs);
            }
            plugin.stats().gameDone();
        }

        if (counted && mode == Mode.SPEEDRUN && "ALL".equals(winningSide)) {
            for (UUID id : winners) plugin.stats().bestTime(id, name(id), mode.key, elapsed);
            plugin.stats().gameDone();
        }
        String winnerText;
        if ("ALL".equals(winningSide)) {
            winnerText = "§b§lSpeedrun complete in §f§l" + Msg.time(elapsed) + "§b§l!";
        } else if ("TIE".equals(winningSide)) {
            StringBuilder sb = new StringBuilder();
            for (String side : tiedSides) { if (sb.length() > 0) sb.append("§7, "); sb.append(sideName(side)); }
            winnerText = "§6Tie: " + sb + (reason != null ? " §7- " + reason : "");
        } else if (winners.isEmpty()) {
            winnerText = "§7No winner" + (reason != null ? " - " + reason : "") + " §e(Draw)";
        } else if (mode.isHunt()) {
            Role r = teams.get(winners.get(0)) == Role.RUNNER.team ? Role.RUNNER : Role.HUNTER;
            winnerText = "§6Winners: " + r.code() + "§l" + r.plural(mode) + (reason != null ? " §7- " + reason : "");
        } else if (teams.isEmpty()) {
            winnerText = "§6Winner: " + colored(winners.get(0));
        } else {
            TeamColor t = teams.get(winners.get(0));
            StringBuilder sb = new StringBuilder();
            for (UUID w : winners) { if (sb.length() > 0) sb.append("§7, "); sb.append(colored(w)); }
            winnerText = "§6Winner: " + t.code + "§l" + t.display + " Team §7(" + sb + "§7)";
        }

        broadcast(" ");
        broadcast("§c§l              GAME OVER!");
        broadcast("   " + winnerText);
        List<Map.Entry<UUID, Integer>> top = new ArrayList<>(kills.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        if (!top.isEmpty() && top.get(0).getValue() > 0) {
            broadcast("   §6Most Kills: " + colored(top.get(0).getKey()) + " §7(" + top.get(0).getValue() + ")");
        }
        broadcast("   §7Game length: §f" + Msg.time(elapsed) + (mode == Mode.RIC ? " §8• §7Drops: §f" + dropCount : ""));
        Msg.to(spectatorsAndAlive(), "   §aSeed: §f" + world.getSeed());
        broadcast(" ");

        for (Player p : viewers()) {
            boolean won = winners.contains(p.getUniqueId());
            p.sendTitle(won ? "§6§lVICTORY!" : "§c§lGAME OVER", winnerText, 10, 80, 20);
            p.playSound(p.getLocation(), won ? "minecraft:ui.toast.challenge_complete" : "minecraft:entity.wither.death", 0.6f, 1f);
            clearGhost(p);
            if (p.getGameMode() != GameMode.SPECTATOR) {
                p.setGameMode(GameMode.ADVENTURE);
                p.setAllowFlight(true);
            }
        }

        Bukkit.getScheduler().runTaskLater(plugin, this::cleanup, 20L * plugin.getConfig().getInt("end-delay-seconds", 10));
    }

    private Collection<UUID> spectatorsAndAlive() {
        Set<UUID> ids = new LinkedHashSet<>(alive);
        ids.addAll(spectators);
        return ids;
    }

    private void cleanup() {
        for (World w : new World[]{world, nether, end}) {
            if (w == null) continue;
            for (Player p : new ArrayList<>(w.getPlayers())) plugin.sendToLobby(p);
        }
        party.match = null;
        party.ready.clear();
        for (Player p : party.online()) plugin.lobbyItems(p);
        party.broadcast("§aBack in the waiting area. Ready up to play again!");
        plugin.unregisterMatch(this);

        List<File> folders = new ArrayList<>();
        for (World w : new World[]{end, nether, world}) {
            if (w == null) continue;
            File f = w.getWorldFolder();
            if (Bukkit.unloadWorld(w, false)) folders.add(f);
        }
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            for (File f : folders) plugin.deleteFolder(f);
        }, 40L);
        plugin.maps().fillLater(100L);
    }

    // ------------------------------------------------------------------ nether

    public World netherWorld() {
        if (nether == null) {
            WorldCreator wc = new WorldCreator(world.getName() + "_nether");
            wc.seed(world.getSeed());
            wc.environment(World.Environment.NETHER);
            nether = Bukkit.createWorld(wc);
            if (nether != null) {
                nether.setAutoSave(false);
                setupBorder(nether, mode == Mode.DEATHSHUFFLE ? 59_999_968 : Math.max(16, world.getWorldBorder().getSize() / 8));
            }
        }
        return nether;
    }
}
