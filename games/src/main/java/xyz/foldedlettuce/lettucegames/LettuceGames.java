package xyz.foldedlettuce.lettucegames;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class LettuceGames extends JavaPlugin {
    private ItemPool pool;
    private PartyManager parties;
    private Menus menus;
    private Presets presets;
    private final List<Match> matches = new ArrayList<>();
    private MapPool maps;
    private Stats stats;
    /** set while the plugin itself drops items, so the World Loot filter lets them through */
    public boolean allowItemSpawn = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        maps = new MapPool(this);
        cleanupOldWorlds(maps.savedNames());
        // after the server's up: load saved maps one at a time, then top up
        Bukkit.getScheduler().runTaskLater(this, maps::loadSaved, 40L);
        Msg.setup(getConfig().getBoolean("parties-enabled", false));

        pool = new ItemPool(this);
        pool.build();
        parties = new PartyManager(this);
        menus = new Menus(this);
        presets = new Presets(this);
        stats = new Stats(this);

        Bukkit.getPluginManager().registerEvents(menus, this);
        Bukkit.getPluginManager().registerEvents(new GameListener(this), this);
        Bukkit.getPluginManager().registerEvents(new DeathSwapListener(this), this);
        Bukkit.getPluginManager().registerEvents(new HuntListener(this), this);
        Bukkit.getPluginManager().registerEvents(new TwistListener(this), this);
        Bukkit.getPluginManager().registerEvents(new BingoListener(this), this);

        bind("party", parties);
        for (String c : new String[]{"stats", "leaderboard"}) {
            PluginCommand pc = getCommand(c);
            if (pc != null) pc.setExecutor(stats);
        }
        bind("pc", parties);
        for (String c : new String[]{"track", "spec", "leave", "specmode", "blacklist", "pick", "bingocard", "skip", "lettucegames"}) bind(c, null);

        // keep the waiting area sunny and daytime
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            World w = lobby().getWorld();
            if (w != null && getConfig().getBoolean("lobby-always-day", true)) {
                w.setTime(6000);
                w.setStorm(false);
            }
        }, 20L, 20L * 30);

        for (Player p : Bukkit.getOnlinePlayers()) sendToLobby(p);
        getLogger().info("LettuceGames ready.");
    }

    @Override
    public void onDisable() {
        if (stats != null) stats.save();
        for (Match m : new ArrayList<>(matches)) {
            for (Player p : m.viewers()) p.teleport(lobby());
            for (World w : new World[]{m.nether, m.world}) {
                if (w == null) continue;
                File f = w.getWorldFolder();
                Bukkit.unloadWorld(w, false);
                deleteFolder(f);
            }
        }
        matches.clear();
    }

    private void bind(String name, Object handler) {
        PluginCommand c = getCommand(name);
        if (c == null) return;
        if (handler instanceof PartyManager pm) {
            c.setExecutor(pm);
            c.setTabCompleter(pm);
        } else {
            c.setExecutor(this);
        }
    }

    public ItemPool pool() { return pool; }
    public PartyManager parties() { return parties; }
    public Menus menus() { return menus; }
    public Presets presets() { return presets; }
    public MapPool maps() { return maps; }
    public Stats stats() { return stats; }

    public String worldPrefix() {
        return getConfig().getString("world-prefix", "lg_");
    }

    // ------------------------------------------------------------------ matches

    public Match matchFor(World w) {
        if (w == null) return null;
        for (Match m : matches) if (m.owns(w)) return m;
        return null;
    }

    public int activeMatches() {
        return matches.size();
    }

    public boolean canStartMatch() {
        return matches.size() < getConfig().getInt("max-games", 4);
    }

    public void startMatch(Party party) {
        if (party.inGame() || party.members.isEmpty()) return;
        Match m = new Match(this, party);
        party.match = m;
        matches.add(m);
        boolean ok;
        try {
            ok = m.start();
        } catch (Throwable t) {
            getLogger().severe("Game failed to start: " + t);
            t.printStackTrace();
            ok = false;
        }
        if (!ok) {
            party.match = null;
            matches.remove(m);
            if (m.world != null) {
                for (Player p : new ArrayList<>(m.world.getPlayers())) sendToLobby(p);
                File f = m.world.getWorldFolder();
                Bukkit.unloadWorld(m.world, false);
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> deleteFolder(f));
            }
            party.ready.clear();
            for (Player p : party.online()) sendToLobby(p);
        }
    }

    public void unregisterMatch(Match m) {
        matches.remove(m);
    }

    public void spectate(Player p) {
        Match here = matchFor(p.getWorld());
        if (here != null) {
            if (here.alive.contains(p.getUniqueId())) {
                Msg.send(p, Msg.GAME, "§cYou're still in the game!");
                return;
            }
            menus.open(p, Menus.Type.SPECTATE, here.party, null);
            return;
        }
        Party party = parties.get(p);
        if (party == null || party.match == null || party.match.ended) {
            Msg.send(p, Msg.GAME, "§cYour party doesn't have a game running.");
            return;
        }
        party.match.addSpectator(p);
    }

    public void leaveGame(Player p) {
        Match m = matchFor(p.getWorld());
        if (m == null) {
            Msg.send(p, Msg.GAME, "§cYou're not in a game. §7Use §e/lobby §7to go to the network lobby.");
            return;
        }
        m.removePlayer(p, false);
        sendToLobby(p);
        Msg.send(p, Msg.GAME, "You left the game.");
    }

    // ------------------------------------------------------------------ players

    public Location lobby() {
        String w = getConfig().getString("lobby.world", "");
        World world = w.isEmpty() ? null : Bukkit.getWorld(w);
        if (world == null) {
            World main = Bukkit.getWorlds().get(0);
            return main.getSpawnLocation().add(0.5, 0, 0.5);
        }
        return new Location(world, getConfig().getDouble("lobby.x"), getConfig().getDouble("lobby.y"),
                getConfig().getDouble("lobby.z"), (float) getConfig().getDouble("lobby.yaw"), (float) getConfig().getDouble("lobby.pitch"));
    }

    /** Player size (Tiny Survivors). Safe on servers without the scale attribute. */
    public static void setScale(Player p, double scale) {
        try {
            org.bukkit.attribute.AttributeInstance a = p.getAttribute(org.bukkit.attribute.Attribute.SCALE);
            if (a != null && a.getBaseValue() != scale) a.setBaseValue(scale);
        } catch (Throwable ignored) {
        }
    }

    /** Locator bar range (0 hides everyone from your XP bar). Default puts it back to vanilla. */
    public static void setLocatorRange(Player p, Double range) {
        try {
            org.bukkit.attribute.AttributeInstance a = p.getAttribute(org.bukkit.attribute.Attribute.WAYPOINT_RECEIVE_RANGE);
            if (a != null) a.setBaseValue(range == null ? a.getDefaultValue() : range);
        } catch (Throwable ignored) {
        }
    }

    public void resetPlayer(Player p, GameMode mode) {
        setScale(p, 1.0);
        setLocatorRange(p, null);
        p.setFreezeTicks(0);
        p.closeInventory();
        p.getInventory().clear();
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        p.setGameMode(mode);
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.setSaturation(5f);
        p.setExp(0f);
        p.setLevel(0);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setRemainingAir(300);
        if (mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR) {
            p.setFlying(false);
            p.setAllowFlight(false);
        }
    }

    public void sendToLobby(Player p) {
        resetPlayer(p, GameMode.ADVENTURE);
        p.setInvisible(false);
        p.setCollidable(true);
        p.setCanPickupItems(true);
        for (Player o : Bukkit.getOnlinePlayers()) o.showPlayer(this, p);
        p.teleport(lobby());
        stats.lobbyBoard(p);
        lobbyItems(p);
    }

    /** Lobby hotbar items are recognized by type, nothing else lives in a lobby inventory. */
    public static String lobbyTag(ItemStack it) {
        if (it == null) return null;
        return switch (it.getType()) {
            case NETHER_STAR -> "party";
            case LIME_DYE, GRAY_DYE -> "ready";
            case COMPARATOR -> "settings";
            case ENDER_EYE -> "spectate";
            case GOLD_INGOT -> "leaderboard";
            default -> null;
        };
    }

    public void lobbyItems(Player p) {
        if (matchFor(p.getWorld()) != null) return;
        PlayerInventory inv = p.getInventory();
        // only touch slots that need it so the hotbar doesn't flicker
        int lbSlot = parties.enabled() ? 1 : 0;
        for (int i = 0; i < inv.getSize(); i++) {
            if (i == 0 || i == 4 || i == 8 || i == lbSlot) continue;
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() != Material.AIR) inv.setItem(i, null);
        }
        Party party = parties.get(p);
        if (parties.enabled()) {
            inv.setItem(0, Menus.item(Material.NETHER_STAR, "§d§lParty §7(Right-click)",
                    party == null ? "§7Create a party and invite friends." : "§7Members, invites, ready up.",
                    "", "§e/p invite <name> §7works too"));
        } else {
            inv.setItem(0, null);
        }
        boolean ready = party != null && party.ready.contains(p.getUniqueId());
        if (party != null && party.inGame()) {
            inv.setItem(4, Menus.item(Material.ENDER_EYE, "§b§lSpectate §7(Right-click)", "§7Your party's game is running."));
        } else {
            inv.setItem(4, Menus.item(ready ? Material.LIME_DYE : Material.GRAY_DYE,
                    ready ? "§a§lReady! §7(Right-click to unready)" : "§c§lNot Ready §7(Right-click to ready up)",
                    "§7The game starts when everyone", "§7in your party is ready.",
                    "§7Needs at least §f" + minPlayers() + " §7players."));
        }
        inv.setItem(lbSlot, Menus.item(Material.GOLD_INGOT, "§6§lLeaderboards §7(Right-click)",
                "§7Top players by wins, kills and more.", "§e/stats §7for your own."));
        inv.setItem(8, Menus.item(Material.COMPARATOR, "§6§lGame Settings §7(Right-click)",
                "§7Map, settings, twists, teams, presets.",
                parties.enabled() ? "§7The party leader makes the changes." : "§7Anyone can change them."));
    }

    public int minPlayers() {
        return Math.max(1, getConfig().getInt("min-players", 2));
    }

    public void deleteFolder(File f) {
        if (f == null || !f.exists()) return;
        // safety: only ever delete our own game worlds
        if (!f.getName().startsWith(worldPrefix())) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        if (!f.delete()) getLogger().warning("Couldn't fully delete " + f.getPath());
    }

    private void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    /** Deletes leftover game worlds. 26.x keeps extra worlds inside world/dimensions, so look there too. */
    private void cleanupOldWorlds(java.util.Set<String> keep) {
        List<File> roots = new ArrayList<>();
        roots.add(Bukkit.getWorldContainer());
        try {
            File level = Bukkit.getServer().getLevelDirectory().toFile();
            roots.add(level);
            File dims = new File(level, "dimensions");
            File[] namespaces = dims.listFiles();
            if (namespaces != null) for (File ns : namespaces) if (ns.isDirectory()) roots.add(ns);
        } catch (Throwable ignored) {
        }
        for (File root : roots) {
            File[] dirs = root.listFiles();
            if (dirs == null) continue;
            for (File d : dirs) {
                if (d.isDirectory() && d.getName().startsWith(worldPrefix()) && !keep.contains(d.getName())
                        && !keep.contains(d.getName().replace("_nether", ""))) {
                    getLogger().info("Deleting leftover game world " + d.getPath());
                    deleteFolder(d);
                }
            }
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("lettucegames")) return admin(sender, args);
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        switch (name) {
            case "spec" -> spectate(p);
            case "bingocard" -> menus.openBingo(p);
            case "skip" -> {
                Match sm = matchFor(p.getWorld());
                if (sm == null) Msg.send(p, Msg.GAME, "§cYou're not in a game.");
                else sm.voteSkip(p);
            }
            case "leave" -> leaveGame(p);
            case "pick" -> {
                Match m = matchFor(p.getWorld());
                if (m == null || !m.choosing || !m.alive.contains(p.getUniqueId())) {
                    Msg.send(p, Msg.GAME, "§cThere's nothing to pick right now.");
                    return true;
                }
                menus.openBlockPick(p, m.party);
            }
            case "specmode" -> {
                Match m = matchFor(p.getWorld());
                if (m == null || !m.spectators.contains(p.getUniqueId())) {
                    Msg.send(p, Msg.GAME, "§cYou're not spectating.");
                    return true;
                }
                m.toggleSpecMode(p);
            }
            case "track" -> {
                Match m = matchFor(p.getWorld());
                if (m == null || !m.alive.contains(p.getUniqueId())) {
                    Msg.send(p, Msg.GAME, "§cYou're not in a game.");
                    return true;
                }
                if (args.length == 0) {
                    menus.open(p, Menus.Type.TRACKER, m.party, null);
                    return true;
                }
                if (args[0].equalsIgnoreCase("nearest")) {
                    m.trackLock.remove(p.getUniqueId());
                    Msg.send(p, Msg.GAME, "Tracking the nearest enemy.");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[0]);
                if (t == null || !m.alive.contains(t.getUniqueId()) || m.sameTeam(t.getUniqueId(), p.getUniqueId()) || t.equals(p)) {
                    Msg.send(p, Msg.GAME, "§cYou can't track " + args[0] + ".");
                    return true;
                }
                m.trackLock.put(p.getUniqueId(), t.getUniqueId());
                Msg.send(p, Msg.GAME, "Now tracking §e" + t.getName() + "§7.");
            }
            case "blacklist" -> {
                Party party = parties.get(p);
                if (party == null) party = parties.create(p);
                menus.open(p, Menus.Type.BLACKLIST, party, args.length == 0 ? null : String.join(" ", args));
            }
            default -> {
            }
        }
        return true;
    }

    private boolean admin(CommandSender s, String[] args) {
        if (!s.hasPermission("lettucegames.admin")) {
            s.sendMessage("§cNo permission.");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "setlobby" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                Location l = p.getLocation();
                getConfig().set("lobby.world", l.getWorld().getName());
                getConfig().set("lobby.x", l.getX());
                getConfig().set("lobby.y", l.getY());
                getConfig().set("lobby.z", l.getZ());
                getConfig().set("lobby.yaw", (double) l.getYaw());
                getConfig().set("lobby.pitch", (double) l.getPitch());
                saveConfig();
                s.sendMessage("§aWaiting area spawn set.");
            }
            case "hologram", "holo" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                if (args.length < 2) {
                    StringBuilder b = new StringBuilder();
                    for (Stats.Board bd : Stats.Board.values()) b.append(bd.name().toLowerCase(Locale.ROOT)).append(' ');
                    s.sendMessage("§c/lg hologram <" + b.toString().trim().replace(' ', '|') + "|remove>");
                    return true;
                }
                if (args[1].equalsIgnoreCase("remove")) {
                    s.sendMessage("§aRemoved " + stats.removeHologramsNear(p.getLocation()) + " leaderboard(s) near you.");
                    return true;
                }
                try {
                    stats.addHologram(p.getLocation(), Stats.Board.valueOf(args[1].toUpperCase(Locale.ROOT)));
                    s.sendMessage("§aLeaderboard placed. It updates every minute.");
                } catch (IllegalArgumentException ex) {
                    s.sendMessage("§cUnknown leaderboard " + args[1]);
                }
            }
            case "maps" -> s.sendMessage("§7Ready maps: §f" + maps.readyCount() + "/" + maps.target()
                    + (maps.isGenerating() ? " §e(making one now)" : ""));
            case "list" -> {
                if (matches.isEmpty()) s.sendMessage("§7No games running.");
                for (Match m : matches) {
                    s.sendMessage("§e" + m.id + " §7leader §f" + m.party.leaderName() + " §7alive §f" + m.alive.size()
                            + " §7time §f" + Msg.time(m.elapsed) + " §7world §f" + m.worldName());
                }
            }
            case "end" -> {
                if (args.length < 2) { s.sendMessage("§c/lg end <game id or leader name>"); return true; }
                for (Match m : new ArrayList<>(matches)) {
                    if (m.id.equalsIgnoreCase(args[1]) || m.party.leaderName().equalsIgnoreCase(args[1])) {
                        m.end(null, "Ended by an admin.");
                        s.sendMessage("§aEnded game " + m.id + ".");
                        return true;
                    }
                }
                s.sendMessage("§cNo game found.");
            }
            case "reload" -> {
                reloadConfig();
                pool.build();
                s.sendMessage("§aConfig reloaded and item pool rebuilt.");
            }
            case "pool" -> {
                StringBuilder sb = new StringBuilder("§7Pool:");
                for (ItemPool.Group g : pool.groups.values()) {
                    sb.append(" §f").append(g.name).append(" §7").append(g.items.size()).append(" (w").append(g.weight).append(")");
                }
                sb.append(" §feggs §7").append(pool.eggs.size()).append(" §fpotions §7").append(pool.potions.size());
                s.sendMessage(sb.toString());
            }
            default -> {
                s.sendMessage("§a§lLettuceGames admin");
                s.sendMessage("§f/lg setlobby §7- set the waiting area spawn to where you stand");
                s.sendMessage("§f/lg list §7- running games");
                s.sendMessage("§f/lg end <id|leader> §7- force end a game");
                s.sendMessage("§f/lg reload §7- reload config.yml");
                s.sendMessage("§f/lg pool §7- item pool sizes");
                s.sendMessage("§f/lg maps §7- how many maps are ready");
                s.sendMessage("§f/lg hologram <wins|kills|kd|...> §7- floating leaderboard where you stand");
                s.sendMessage("§f/lg hologram remove §7- remove ones near you");
            }
        }
        return true;
    }

    public UUID id(Player p) {
        return p.getUniqueId();
    }
}
