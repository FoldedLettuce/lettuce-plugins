package xyz.foldedlettuce.lettucegames;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Parties: /p and /pc. */
public class PartyManager implements CommandExecutor, TabCompleter {
    private final LettuceGames plugin;
    private final Map<UUID, Party> byPlayer = new HashMap<>();
    private final long inviteMillis;

    public PartyManager(LettuceGames plugin) {
        this.plugin = plugin;
        this.inviteMillis = plugin.getConfig().getInt("party.invite-expire-seconds", 60) * 1000L;
    }

    /** Parties are commented out for now: when off, everyone on the Games server is in one shared group. */
    public boolean enabled() {
        return plugin.getConfig().getBoolean("parties-enabled", false);
    }

    private Party global;

    public void onJoin(Player p) {
        if (enabled()) return;
        if (global == null) {
            global = new Party(p.getUniqueId());
            global.everyone = true;
        } else {
            if (global.members.isEmpty()) global.leader = p.getUniqueId();
            global.members.add(p.getUniqueId());
        }
        byPlayer.put(p.getUniqueId(), global);
        if (global.inGame()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) Msg.tellraw(p, "[" + Msg.plain("A game is running. ", "gray") + ","
                        + Msg.button("[Spectate]", "aqua", "/spec", "Watch the current game") + "]");
            }, 20L);
        }
    }

    public Party get(Player p) {
        return byPlayer.get(p.getUniqueId());
    }

    public Party get(UUID id) {
        return byPlayer.get(id);
    }

    public Collection<Party> all() {
        return new LinkedHashSet<>(byPlayer.values());
    }

    // ------------------------------------------------------------------ actions

    public Party create(Player p) {
        Party existing = get(p);
        if (existing != null) return existing;
        if (!enabled()) {
            onJoin(p);
            return get(p);
        }
        Party party = new Party(p.getUniqueId());
        byPlayer.put(p.getUniqueId(), party);
        Msg.send(p, Msg.PARTY, "Party created! Invite friends with §e/p invite <name>§7.");
        plugin.lobbyItems(p);
        return party;
    }

    public void invite(Player from, String targetName) {
        Party party = get(from);
        if (party == null) party = create(from);
        if (!party.isLeader(from.getUniqueId())) {
            Msg.send(from, Msg.PARTY, "§cOnly the party leader can invite.");
            return;
        }
        Player to = Bukkit.getPlayerExact(targetName);
        if (to == null) {
            Msg.send(from, Msg.PARTY, "§c" + targetName + " isn't on the Games server right now.");
            return;
        }
        if (to.equals(from)) {
            Msg.send(from, Msg.PARTY, "§cYou can't invite yourself.");
            return;
        }
        if (party.members.contains(to.getUniqueId())) {
            Msg.send(from, Msg.PARTY, "§c" + to.getName() + " is already in your party.");
            return;
        }
        party.invites.put(to.getUniqueId(), System.currentTimeMillis() + inviteMillis);
        party.broadcast("§e" + from.getName() + " §7invited §e" + to.getName() + "§7 to the party.");
        String leader = from.getName();
        Msg.tellraw(to, "[" + Msg.plain("", "gray") + ","
                + "{\"text\":\"Party \",\"color\":\"light_purple\",\"bold\":true},"
                + Msg.plain("» ", "dark_gray") + ","
                + Msg.plain(leader + " invited you to their party! ", "yellow") + ","
                + Msg.button("[Accept]", "green", "/p accept " + leader, "Join " + leader + "'s party") + ","
                + Msg.plain(" ", "gray") + ","
                + Msg.button("[Deny]", "red", "/p deny " + leader, "Ignore this invite") + "]");
        to.playSound(to.getLocation(), "minecraft:entity.experience_orb.pickup", 1f, 1.2f);
    }

    public void accept(Player p, String leaderName) {
        Party target = findInvite(p, leaderName);
        if (target == null) {
            Msg.send(p, Msg.PARTY, "§cYou don't have an invite" + (leaderName == null ? "" : " from " + leaderName) + " (or it expired).");
            return;
        }
        join(p, target);
    }

    public void deny(Player p, String leaderName) {
        Party target = findInvite(p, leaderName);
        if (target == null) {
            Msg.send(p, Msg.PARTY, "§cNo invite to deny.");
            return;
        }
        target.invites.remove(p.getUniqueId());
        Msg.send(p, Msg.PARTY, "Invite denied.");
        Player l = Bukkit.getPlayer(target.leader);
        if (l != null) Msg.send(l, Msg.PARTY, "§e" + p.getName() + " §7denied your invite.");
    }

    private Party findInvite(Player p, String leaderName) {
        long now = System.currentTimeMillis();
        for (Party party : all()) {
            Long exp = party.invites.get(p.getUniqueId());
            if (exp == null) continue;
            if (exp < now) { party.invites.remove(p.getUniqueId()); continue; }
            if (leaderName == null || party.leaderName().equalsIgnoreCase(leaderName)) return party;
        }
        return null;
    }

    public void joinOpen(Player p, String leaderName) {
        Player l = Bukkit.getPlayerExact(leaderName);
        Party party = l == null ? null : get(l);
        if (party == null) {
            Msg.send(p, Msg.PARTY, "§cCouldn't find a party led by " + leaderName + ".");
            return;
        }
        if (!party.open && findInvite(p, null) != party) {
            Msg.send(p, Msg.PARTY, "§cThat party is invite only.");
            return;
        }
        join(p, party);
    }

    private void join(Player p, Party party) {
        Party old = get(p);
        if (old == party) {
            Msg.send(p, Msg.PARTY, "You're already in that party.");
            return;
        }
        if (old != null) leave(p, false);
        party.invites.remove(p.getUniqueId());
        party.members.add(p.getUniqueId());
        byPlayer.put(p.getUniqueId(), party);
        party.broadcast("§a" + p.getName() + " joined the party! §7(" + party.members.size() + " members)");
        cancelCountdown(party, null);
        plugin.lobbyItems(p);
        if (party.inGame()) {
            Msg.tellraw(p, "[" + Msg.plain("A game is running. ", "gray") + ","
                    + Msg.button("[Spectate]", "aqua", "/spec", "Watch the current game") + "]");
        }
    }

    /** @param quit true when the player is logging off */
    public void leave(Player p, boolean quit) {
        if (!enabled() && !quit) {
            Msg.send(p, Msg.SYS, "§cParties are off right now, everyone here plays together.");
            return;
        }
        Party party = get(p);
        if (party == null) {
            if (!quit) Msg.send(p, Msg.PARTY, "§cYou're not in a party.");
            return;
        }
        UUID id = p.getUniqueId();
        if (party.match != null) party.match.removePlayer(p, quit);
        party.members.remove(id);
        party.ready.remove(id);
        party.teams.remove(id);
        party.roles.remove(id);
        byPlayer.remove(id);
        if (!quit) {
            Msg.send(p, Msg.PARTY, "You left the party.");
            if (plugin.matchFor(p.getWorld()) != null) plugin.sendToLobby(p);
            else plugin.lobbyItems(p);
        }
        if (party.members.isEmpty()) {
            cancelCountdown(party, null);
            return;
        }
        if (!party.everyone) party.broadcast("§e" + p.getName() + " §7" + (quit ? "disconnected and left" : "left") + " the party.");
        if (party.members.size() < plugin.minPlayers()) cancelCountdown(party, "§cStart cancelled, not enough players.");
        if (party.leader.equals(id)) {
            party.leader = party.members.iterator().next();
            if (!party.everyone) party.broadcast("§e" + party.leaderName() + " §7is the new party leader.");
            for (Player m : party.online()) plugin.lobbyItems(m);
        }
        checkReady(party);
    }

    public void kick(Player by, String name) {
        Party party = get(by);
        if (!leaderCheck(by, party)) return;
        UUID target = null;
        for (UUID id : party.members) {
            Player m = Bukkit.getPlayer(id);
            if (m != null && m.getName().equalsIgnoreCase(name)) target = id;
        }
        if (target == null || target.equals(by.getUniqueId())) {
            Msg.send(by, Msg.PARTY, "§c" + name + " isn't in your party.");
            return;
        }
        Player t = Bukkit.getPlayer(target);
        if (t != null) {
            leave(t, false);
            Msg.send(t, Msg.PARTY, "§cYou were kicked from the party.");
        }
    }

    public void promote(Player by, String name) {
        Party party = get(by);
        if (!leaderCheck(by, party)) return;
        for (UUID id : party.members) {
            Player m = Bukkit.getPlayer(id);
            if (m != null && m.getName().equalsIgnoreCase(name) && !id.equals(by.getUniqueId())) {
                party.leader = id;
                party.broadcast("§e" + m.getName() + " §7is the new party leader.");
                for (Player x : party.online()) plugin.lobbyItems(x);
                return;
            }
        }
        Msg.send(by, Msg.PARTY, "§c" + name + " isn't in your party.");
    }

    public void disband(Player by) {
        Party party = get(by);
        if (!leaderCheck(by, party)) return;
        if (party.match != null) party.match.end(null, "The party was disbanded.");
        party.broadcast("§cThe party was disbanded.");
        cancelCountdown(party, null);
        for (UUID id : new ArrayList<>(party.members)) {
            byPlayer.remove(id);
            Player m = Bukkit.getPlayer(id);
            if (m != null) plugin.lobbyItems(m);
        }
        party.members.clear();
    }

    public void setOpen(Player by, boolean open) {
        Party party = get(by);
        if (party == null) party = create(by);
        if (!leaderCheck(by, party)) return;
        party.open = open;
        party.broadcast(open ? "§aThe party is now public. §7Anyone can join with §e/p join " + party.leaderName()
                : "§eThe party is now invite only.");
    }

    public void list(Player p) {
        Party party = get(p);
        if (party != null) {
            p.sendMessage("§d§lYour Party §7(" + party.members.size() + ")" + (party.open ? " §a[Public]" : " §7[Invite only]"));
            for (UUID id : party.members) {
                Player m = Bukkit.getPlayer(id);
                String n = m == null ? "?" : m.getName();
                String star = party.isLeader(id) ? "§6★ " : "§7- ";
                String ready = party.ready.contains(id) ? " §a✔ Ready" : " §c✖ Not ready";
                TeamColor t = party.teams.get(id);
                p.sendMessage("  " + star + "§f" + n + ready + (t == null ? "" : " " + t.code + "[" + t.display + "]"));
            }
        }
        List<Party> openParties = new ArrayList<>();
        for (Party o : all()) if (o.open && o != party) openParties.add(o);
        if (!openParties.isEmpty()) {
            p.sendMessage("§d§lPublic Parties:");
            for (Party o : openParties) {
                Msg.tellraw(p, "[" + Msg.plain("  " + o.leaderName() + "'s party (" + o.members.size() + ") ", "white") + ","
                        + Msg.button("[Join]", "green", "/p join " + o.leaderName(), "Join this party") + "]");
            }
        } else if (party == null) {
            Msg.send(p, Msg.PARTY, "You're not in a party and there are no public parties. §e/p create");
        }
    }

    private boolean leaderCheck(Player p, Party party) {
        if (party == null) {
            Msg.send(p, Msg.PARTY, "§cYou're not in a party.");
            return false;
        }
        if (!party.isLeader(p.getUniqueId())) {
            Msg.send(p, Msg.PARTY, "§cOnly the party leader can do that.");
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ ready / start

    public void toggleReady(Player p) {
        Party party = get(p);
        if (party == null) party = create(p);
        if (party.inGame()) {
            Msg.send(p, Msg.PARTY, "§cA game is already running. §e/spec §cto watch.");
            return;
        }
        UUID id = p.getUniqueId();
        boolean nowReady = !party.ready.remove(id);
        if (nowReady) party.ready.add(id);
        p.playSound(p.getLocation(), "minecraft:ui.button.click", 0.6f, nowReady ? 1.6f : 0.8f);

        party.broadcast((nowReady ? "§a" + p.getName() + " is ready!" : "§c" + p.getName() + " is no longer ready.")
                + " §7(" + party.ready.size() + "/" + party.members.size() + ")");

        if (party.everyone) {
            // parties off: the first ready starts a countdown, whoever's ready when it hits 0 plays
            for (Player m : party.online()) plugin.lobbyItems(m);
            plugin.menus().refreshParty(party);
            if (!nowReady) {
                if (party.ready.isEmpty()) cancelCountdown(party, "§cCountdown stopped, nobody's ready.");
                return;
            }
            if (party.countdown == null) {
                int secs = plugin.getConfig().getInt("ready-countdown-seconds", 60);
                party.broadcast("§a§lGame starts in " + secs + "s! §7Right-click the §adye §7to join in.");
                startCountdown(party, secs);
            } else if (party.allReady() && party.countdownLeft > 5) {
                party.countdownLeft = 5;
                party.broadcast("§aEveryone's ready, starting in 5!");
            }
            return;
        }

        if (!nowReady) cancelCountdown(party, "§cStart cancelled, " + p.getName() + " unreadied.");
        for (Player m : party.online()) plugin.lobbyItems(m);
        plugin.menus().refreshParty(party);

        if (nowReady && party.allReady() && party.members.size() < plugin.minPlayers()) {
            Msg.tellraw(p, "[" + Msg.plain("You need at least " + plugin.minPlayers() + " players to start. ", "red") + ","
                    + Msg.suggest("[Invite someone]", "yellow", "/p invite ", "Fills in /p invite") + "]");
            return;
        }
        checkReady(party);
    }

    public void checkReady(Party party) {
        if (party.everyone) {
            if (party.countdown != null && party.ready.isEmpty()) cancelCountdown(party, "§cCountdown stopped, nobody's ready.");
            return;
        }
        if (party.inGame() || party.countdown != null) return;
        if (party.allReady() && party.members.size() >= plugin.minPlayers()) startCountdown(party);
    }

    public void forceStart(Player p) {
        Party party = get(p);
        if (party == null) party = create(p);
        if (!leaderCheck(p, party)) return;
        if (party.inGame()) {
            Msg.send(p, Msg.PARTY, "§cA game is already running.");
            return;
        }
        if (party.everyone) {
            party.ready.add(p.getUniqueId());
            if (party.ready.size() < plugin.minPlayers() && !p.hasPermission("lettucegames.admin")) {
                Msg.send(p, Msg.SYS, "§cAt least " + plugin.minPlayers() + " people need to be ready.");
                return;
            }
            party.forced = party.ready.size() < plugin.minPlayers();
            party.broadcast("§e" + p.getName() + " §7skipped the countdown.");
            if (party.countdown != null) party.countdownLeft = Math.min(party.countdownLeft, 3);
            else startCountdown(party, 3);
            return;
        }
        if (party.countdown != null) return;
        if (party.members.size() < plugin.minPlayers() && !p.hasPermission("lettucegames.admin")) {
            Msg.send(p, Msg.PARTY, "§cYou need at least " + plugin.minPlayers() + " players to start.");
            return;
        }
        party.broadcast("§e" + p.getName() + " §7force-started the game.");
        startCountdown(party);
    }

    private void startCountdown(Party party) {
        party.broadcast("§aGame starting...");
        startCountdown(party, plugin.getConfig().getInt("start-countdown-seconds", 5));
    }

    private void startCountdown(Party party, int seconds) {
        if (!plugin.canStartMatch()) {
            party.broadcast("§cThere are already " + plugin.activeMatches() + " games running, wait for one to finish.");
            return;
        }
        party.countdownLeft = seconds;
        party.countdown = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (party.members.isEmpty()) { cancelCountdown(party, null); return; }
            if (party.countdownLeft <= 0) {
                if (party.everyone && party.ready.size() < plugin.minPlayers() && !party.forced) {
                    cancelCountdown(party, "§cNot enough people ready (need " + plugin.minPlayers()
                            + "). Ready up again to restart the countdown.");
                    party.ready.clear();
                    for (Player m : party.online()) plugin.lobbyItems(m);
                    return;
                }
                party.countdown.cancel();
                party.countdown = null;
                party.forced = false;
                plugin.startMatch(party);
                return;
            }
            int left = party.countdownLeft;
            for (Player m : party.online()) {
                boolean in = !party.everyone || party.ready.contains(m.getUniqueId());
                if (left <= 10) {
                    m.sendTitle("§c§l" + left, in ? "§7" + party.mode.display + " starting..." : "§7Right-click the dye to join!", 0, 25, 5);
                    m.playSound(m.getLocation(), "minecraft:block.note_block.pling", 1f, 1f);
                }
                if (party.everyone) {
                    m.sendActionBar("§eGame starts in §f" + Msg.time(left) + " §8• §a" + party.ready.size() + " ready"
                            + (in ? " §8• §aYou're in!" : " §8• §7Right-click the dye to join"));
                }
            }
            if (party.everyone && (left == 30 || left == 15)) party.broadcast("§eGame starts in " + left + "s! §7" + party.ready.size() + " ready.");
            party.countdownLeft--;
        }, 0L, 20L);
    }

    public void cancelCountdown(Party party, String why) {
        party.forced = false;
        if (party.countdown != null) {
            party.countdown.cancel();
            party.countdown = null;
            if (why != null) party.broadcast(why);
        }
    }

    // ------------------------------------------------------------------ commands

    private void help(Player p) {
        p.sendMessage("§d§lPARTIES §7- Play with friends!");
        p.sendMessage("§f/p create §7: Create a party");
        p.sendMessage("§f/p invite <name> §7: Invite players");
        p.sendMessage("§f/p accept §7/ §f/p deny §7: Answer an invite");
        p.sendMessage("§f/p join <leader> §7: Join a public party");
        p.sendMessage("§f/p open §7/ §f/p close §7: Public or invite only");
        p.sendMessage("§f/p list §7: Members and public parties");
        p.sendMessage("§f/p leave §7: Leave your party");
        p.sendMessage("§f/p kick <name> §7: Kick someone §8(leader)");
        p.sendMessage("§f/p lead <name> §7: Hand off leader §8(leader)");
        p.sendMessage("§f/p disband §7: Kill the party §8(leader)");
        p.sendMessage("§f/p ready §7: Ready up, game starts when everyone's ready");
        p.sendMessage("§f/p start §7: Force start §8(leader)");
        p.sendMessage("§f/pc <msg> §7: Party chat");
        p.sendMessage("§7Nether Star = party menu, Comparator = game settings (leader changes them).");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!enabled()) {
            String sub0 = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            switch (sub0) {
                case "ready" -> toggleReady(p);
                case "start" -> forceStart(p);
                case "menu", "settings" -> plugin.menus().openGame(p, Menus.Tab.SETTINGS);
                default -> Msg.send(p, Msg.SYS, "Parties are off right now, everyone on the Games server plays together. "
                        + "Right-click the §edye §7to ready up, the §ecomparator §7for settings. §e/p start §7force starts.");
            }
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("pc")) {
            partyChat(p, String.join(" ", args));
            return true;
        }
        if (args.length == 0) {
            help(p);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String arg = args.length > 1 ? args[1] : null;
        switch (sub) {
            case "create" -> create(p);
            case "invite", "inv", "add" -> {
                if (arg == null) Msg.send(p, Msg.PARTY, "§cUsage: /p invite <name>");
                else for (int i = 1; i < args.length; i++) invite(p, args[i]);
            }
            case "accept" -> accept(p, arg);
            case "deny", "decline" -> deny(p, arg);
            case "join" -> {
                if (arg == null) accept(p, null);
                else joinOpen(p, arg);
            }
            case "open", "public" -> setOpen(p, true);
            case "close", "private" -> setOpen(p, false);
            case "list", "info" -> list(p);
            case "leave", "quit" -> leave(p, false);
            case "kick", "remove" -> {
                if (arg == null) Msg.send(p, Msg.PARTY, "§cUsage: /p kick <name>");
                else kick(p, arg);
            }
            case "lead", "leader", "promote", "transfer" -> {
                if (arg == null) Msg.send(p, Msg.PARTY, "§cUsage: /p lead <name>");
                else promote(p, arg);
            }
            case "disband" -> disband(p);
            case "ready" -> toggleReady(p);
            case "start" -> forceStart(p);
            case "menu" -> plugin.menus().openParty(p);
            case "settings" -> plugin.menus().openGame(p, Menus.Tab.SETTINGS);
            case "chat" -> partyChat(p, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
            default -> {
                // "/p Steve" invites like most servers
                if (args.length == 1) invite(p, args[0]);
                else help(p);
            }
        }
        return true;
    }

    private void partyChat(Player p, String msg) {
        Party party = get(p);
        if (party == null) {
            Msg.send(p, Msg.PARTY, "§cYou're not in a party.");
            return;
        }
        if (msg.isBlank()) {
            Msg.send(p, Msg.PARTY, "§cUsage: /pc <message>");
            return;
        }
        String star = party.isLeader(p.getUniqueId()) ? "§6★" : "";
        for (Player m : party.online()) m.sendMessage("§d§lParty §8» " + star + "§f" + p.getName() + "§7: §f" + msg);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("pc")) return out;
        if (args.length == 1) {
            for (String s : new String[]{"create", "invite", "accept", "deny", "join", "open", "close", "list", "leave",
                    "kick", "lead", "disband", "ready", "start", "menu", "chat"}) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2) {
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(o.getName());
            }
        }
        return out;
    }
}
