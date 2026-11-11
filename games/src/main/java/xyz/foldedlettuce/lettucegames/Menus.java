package xyz.foldedlettuce.lettucegames;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Menus:
 *  PARTY    - who's in your party, invite, public/private, ready, leave (Nether Star)
 *  GAME     - the game setup with tabs: Settings / Twists / Teams, plus map size and presets (Comparator)
 *  PRESETS, BLACKLIST, POTIONS - opened from GAME
 *  TRACKER, SPECTATE           - in game
 */
public class Menus implements Listener {

    public enum Type { PARTY, GAME, PRESETS, BLACKLIST, POTIONS, TRACKER, SPECTATE, LEADERBOARD, OPLOOT, BSPICK, BINGO }
    public enum Tab { SETTINGS, TWISTS, TEAMS }

    public static class Menu implements InventoryHolder {
        public final Type type;
        public final Party party;
        public Tab tab = Tab.SETTINGS;
        public int page;
        public String filter;
        public Inventory inv;
        public final Object[] slots = new Object[54];
        public Stats.Board board = Stats.Board.WINS;
        public String mode = "all";

        Menu(Type type, Party party) {
            this.type = type;
            this.party = party;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final LettuceGames plugin;

    public Menus(LettuceGames plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ item helpers

    public static ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null) meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            it.setItemMeta(meta);
        }
        return it;
    }

    public static ItemStack item(Material m, String name, String... lore) {
        return item(m, name, Arrays.asList(lore));
    }

    public static ItemStack glowItem(ItemStack it) {
        return glow(it, true);
    }

    /** Opens the Bingo card for whoever's looking (players and spectators). */
    public void openBingo(Player p) {
        Match match = plugin.matchFor(p.getWorld());
        if (match == null || match.bingo == null) {
            Msg.send(p, Msg.GAME, "§cThere's no Bingo game here.");
            return;
        }
        Menu m = new Menu(Type.BINGO, match.party);
        m.inv = Bukkit.createInventory(m, match.bingo.size * 9, match.bingo.title(p));
        match.bingo.render(m, p);
        p.openInventory(m.inv);
    }

    /** Redraws every open Bingo card after a tile changes. */
    public void refreshBingo() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder();
            if (!(h instanceof Menu om) || om.type != Type.BINGO) continue;
            Match match = plugin.matchFor(p.getWorld());
            if (match == null || match.bingo == null) continue;
            match.bingo.render(om, p);
        }
    }

    private static ItemStack glow(ItemStack it, boolean on) {
        if (!on) return it;
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            try {
                meta.setEnchantmentGlintOverride(true);
            } catch (Throwable ignored) {
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack head(UUID id, String name, List<String> lore) {
        ItemStack it = item(Material.PLAYER_HEAD, name, lore);
        ItemMeta meta = it.getItemMeta();
        if (meta instanceof SkullMeta sm) {
            sm.setOwningPlayer(Bukkit.getOfflinePlayer(id));
            it.setItemMeta(sm);
        }
        return it;
    }

    private static final ItemStack PANE = item(Material.BLACK_STAINED_GLASS_PANE, " ");

    // ------------------------------------------------------------------ opening

    private String title(Menu m) {
        return switch (m.type) {
            case PARTY -> "§8Party";
            case GAME -> "§8" + m.party.mode.shortName + " » " + switch (m.tab) {
                case SETTINGS -> "Settings";
                case TWISTS -> "Twists";
                case TEAMS -> "Teams";
            };
            case PRESETS -> "§8Presets";
            case BLACKLIST -> "§8Blacklist Items";
            case POTIONS -> "§8Blacklist Potions";
            case TRACKER -> "§8Tracker";
            case SPECTATE -> "§8Teleporter";
            case LEADERBOARD -> "§8Leaderboard » " + m.board.display;
            case OPLOOT -> "§8OP Loot Twists";
            case BSPICK -> "§8Pick a block for someone else";
            case BINGO -> "§8Bingo Card";
        };
    }

    private int size(Type t) {
        return t == Type.PRESETS ? 27 : 54;
    }

    public void open(Player p, Type type, Party party, String filter) {
        open(p, type, party, filter, Tab.SETTINGS);
    }

    public void open(Player p, Type type, Party party, String filter, Tab tab) {
        Menu m = new Menu(type, party);
        m.filter = filter;
        m.tab = tab;
        m.inv = Bukkit.createInventory(m, size(type), title(m));
        render(m, p);
        p.openInventory(m.inv);
    }

    public void openLeaderboard(Player p, Stats.Board board, String mode) {
        Menu m = new Menu(Type.LEADERBOARD, plugin.parties().get(p));
        m.board = board;
        m.mode = mode;
        m.inv = Bukkit.createInventory(m, 54, title(m));
        render(m, p);
        p.openInventory(m.inv);
    }

    private void renderLeaderboard(Menu m, Player v) {
        Stats.Board[] boards = Stats.Board.values();
        for (int i = 0; i < boards.length && i < 9; i++) {
            Stats.Board b = boards[i];
            boolean sel = b == m.board;
            set(m, i, glow(item(b.icon, (sel ? "§a▶ " : "§f") + "§l" + b.display,
                    b.minGames > 0 ? "§8Needs " + b.minGames + "+ games to show up" : "", "", sel ? "§aViewing" : "§eClick to view"), sel), "lbboard:" + b.name());
        }
        List<Stats.Row> rows = plugin.stats().top(m.board, m.mode, 10);
        int[] slots = {22, 30, 32, 37, 38, 39, 40, 41, 42, 43};
        String[] medals = {"§6§l#1 ", "§f§l#2 ", "§c§l#3 "};
        for (int i = 0; i < slots.length; i++) {
            if (i >= rows.size()) {
                set(m, slots[i], item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "§7#" + (i + 1) + " §8- nobody yet"), null);
                continue;
            }
            Stats.Row r = rows.get(i);
            Stats.Entry e = plugin.stats().entry(r.id, null);
            List<String> lore = new ArrayList<>();
            lore.add("§7" + m.board.display + ": §e" + m.board.format(r.value));
            lore.add("");
            lore.add("§7Wins §f" + e.get(m.mode, Stats.Stat.WINS) + " §8• §7Kills §f" + e.get(m.mode, Stats.Stat.KILLS));
            lore.add("§7Games §f" + e.get(m.mode, Stats.Stat.GAMES) + " §8• §7Top 3s §f" + e.get(m.mode, Stats.Stat.TOP3));
            String prefix = i < 3 ? medals[i] : "§7#" + (i + 1) + " ";
            set(m, slots[i], head(r.id, prefix + "§f" + r.name, lore), null);
        }

        String modeName = Stats.MODES.getOrDefault(m.mode, m.mode);
        set(m, 45, item(Material.BOOK, "§b§lMode: §f" + modeName, "§7Which games count.", "", "§eClick to switch"), "lbmode");
        Stats.Entry me = plugin.stats().entry(v.getUniqueId(), v.getName());
        int rank = plugin.stats().rank(v.getUniqueId(), m.board, m.mode);
        set(m, 49, head(v.getUniqueId(), "§a§lYou", List.of(
                "§7" + m.board.display + ": §e" + m.board.format(m.board.of(me, m.mode)),
                "§7Rank: " + (rank > 0 ? "§e#" + rank : "§7not ranked yet"),
                "", "§e/stats §7for everything")), null);
        fillRow(m, 5);
    }

    public void openBlockPick(Player p, Party party) {
        Menu m = new Menu(Type.BSPICK, party);
        m.inv = Bukkit.createInventory(m, 27, title(m));
        render(m, p);
        p.openInventory(m.inv);
    }

    private void renderBlockPick(Menu m, Player v) {
        Match match = plugin.matchFor(v.getWorld());
        if (match == null) return;
        List<Material> opts = match.pickOptions.get(v.getUniqueId());
        if (opts == null) return;
        Material mine = match.chosen.get(v.getUniqueId());
        int[] slots = {9, 10, 11, 12, 13, 14, 15, 16, 17};
        for (int i = 0; i < opts.size() && i < slots.length; i++) {
            Material o = opts.get(i);
            boolean sel = o == mine;
            set(m, slots[i], glow(item(o, (sel ? "§a✔ " : "§f") + Msg.pretty(o),
                    "§7You stand on this one this round,", "§7then it goes to someone else.", "", sel ? "§aPicked" : "§eClick to pick"), sel), "bspick:" + o.name());
        }
        set(m, 4, item(Material.CLOCK, "§b§lPick a block", "§7Choose something you can find,", "§7but that'll be a pain for others."), null);
    }

    private void renderOpLoot(Menu m, Player v) {
        boolean leader = m.party.isLeader(v.getUniqueId());
        int i = 0;
        Mode md = m.party.mode;
        for (OpSource o : OpSource.values()) {
            if (i >= 45) break;
            if (!o.in(md)) continue;
            boolean on = m.party.settings.opLoot.contains(o);
            set(m, i++, glow(item(o.icon, (on ? "§a" : "§f") + md.words(o.display) + " Drop OP Loot §8[" + (on ? "§aON" : "§cOFF") + "§8]",
                    "§7" + md.words(o.desc), "", leader ? "§eClick to toggle" : "§7Only the leader can change this"), on), "op:" + o.name());
        }
        set(m, 45, item(Material.ARROW, "§7« Back"), "back");
        set(m, 49, item(Material.BOOK, "§e§lWhat's OP Loot?", "§7A random roll from the OP loot table:",
                "§7enchanted diamond/netherite gear, god apples,", "§7totems, pearls, TNT, lava, obsidian and more.",
                "§8(Edit op-loot in config.yml)"), null);
        if (leader) {
            set(m, 51, item(Material.LIME_DYE, "§a§lAll On", "§7Shift-click to turn every source on"), "opall");
            set(m, 52, item(Material.GRAY_DYE, "§c§lAll Off", "§7Shift-click to turn every source off"), "opnone");
        }
        fillRow(m, 5);
    }

    public void openParty(Player p) {
        open(p, Type.PARTY, plugin.parties().get(p), null);
    }

    public void openGame(Player p, Tab tab) {
        Party party = plugin.parties().get(p);
        if (party == null) party = plugin.parties().create(p);
        open(p, Type.GAME, party, null, tab);
    }

    /** Re-draws any menus party members have open so everyone sees changes live. */
    public void refreshParty(Party party) {
        for (Player p : party.online()) {
            InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder();
            if (h instanceof Menu m && m.party == party) render(m, p);
        }
    }

    // ------------------------------------------------------------------ rendering

    private void render(Menu m, Player viewer) {
        m.inv.clear();
        Arrays.fill(m.slots, null);
        switch (m.type) {
            case PARTY -> renderParty(m, viewer);
            case GAME -> renderGame(m, viewer);
            case PRESETS -> renderPresets(m, viewer);
            case BLACKLIST -> renderBlacklist(m, viewer);
            case POTIONS -> renderPotions(m, viewer);
            case TRACKER -> renderTracker(m, viewer);
            case SPECTATE -> renderSpectate(m, viewer);
            case LEADERBOARD -> renderLeaderboard(m, viewer);
            case OPLOOT -> renderOpLoot(m, viewer);
            case BSPICK -> renderBlockPick(m, viewer);
            case BINGO -> {
                Match match = plugin.matchFor(viewer.getWorld());
                if (match != null && match.bingo != null) match.bingo.render(m, viewer);
            }
        }
    }

    private void set(Menu m, int slot, ItemStack it, Object action) {
        m.inv.setItem(slot, it);
        m.slots[slot] = action;
    }

    private void fillRow(Menu m, int row) {
        for (int i = row * 9; i < row * 9 + 9; i++) if (m.inv.getItem(i) == null) m.inv.setItem(i, PANE);
    }

    // ---------- PARTY

    private void renderParty(Menu m, Player v) {
        Party party = m.party;
        if (party == null) {
            set(m, 13, item(Material.NETHER_STAR, "§a§lCreate a Party",
                    "§7Make a party, then invite friends", "§7with §e/p invite <name>§7.", "", "§eClick to create"), "create");
            List<Party> open = new ArrayList<>();
            for (Party o : plugin.parties().all()) if (o.open) open.add(o);
            set(m, 22, item(Material.OAK_SIGN, "§f§lPublic Parties",
                    open.isEmpty() ? "§7None right now" : "§7Click a head below to join"), null);
            int slot = 27;
            for (Party o : open) {
                if (slot > 44) break;
                set(m, slot++, head(o.leader, "§f" + o.leaderName() + "'s party",
                        List.of("§7Members: §f" + o.members.size(), o.inGame() ? "§eIn a game" : "§aIn the waiting area",
                                "", "§eClick to join")), "joinparty:" + o.leaderName());
            }
            fillRow(m, 5);
            return;
        }

        boolean leader = party.isLeader(v.getUniqueId());
        List<String> info = new ArrayList<>();
        info.add("§7Leader: §6" + party.leaderName());
        info.add("§7Members: §f" + party.members.size());
        info.add("§7Ready: §f" + party.ready.size() + "/" + party.members.size());
        info.add("§7Type: " + (party.open ? "§aPublic" : "§eInvite only"));
        if (party.inGame()) info.add("§eGame in progress");
        set(m, 4, item(Material.NAME_TAG, "§d§lYour Party", info), null);
        fillRow(m, 0);

        int slot = 18;
        for (UUID id : party.members) {
            if (slot > 35) break;
            Player p = Bukkit.getPlayer(id);
            String n = p == null ? "?" : p.getName();
            boolean ready = party.ready.contains(id);
            List<String> lore = new ArrayList<>();
            lore.add(ready ? "§a✔ Ready" : "§c✖ Not ready");
            TeamColor t = party.teams.get(id);
            lore.add("§7Team: " + (t == null ? "§fNone" : t.code + t.display));
            if (leader && !id.equals(v.getUniqueId())) {
                lore.add("");
                lore.add("§eLeft-click §7to make leader");
                lore.add("§cRight-click §7to kick");
            }
            set(m, slot++, head(id, (party.isLeader(id) ? "§6★ " : "") + (ready ? "§a" : "§c") + n, lore), "member:" + n);
        }

        set(m, 45, item(Material.WRITABLE_BOOK, "§a§lInvite Players", "§7/p invite <name>", "", "§eClick to type it"), "invite");
        set(m, 47, item(party.open ? Material.OAK_DOOR : Material.IRON_DOOR,
                party.open ? "§a§lPublic Party" : "§e§lInvite Only",
                party.open ? "§7Anyone can join from their party menu" : "§7Only invited players can join",
                "", leader ? "§eClick to toggle" : "§7Only the leader can change this"), "open");
        if (party.inGame()) {
            set(m, 49, item(Material.ENDER_EYE, "§b§lSpectate", "§7Your party's game is running.", "", "§eClick to watch"), "spectate");
        } else {
            boolean ready = party.ready.contains(v.getUniqueId());
            set(m, 49, item(ready ? Material.LIME_DYE : Material.GRAY_DYE, ready ? "§a§lReady!" : "§c§lNot Ready",
                    "§7Ready: §f" + party.ready.size() + "/" + party.members.size(),
                    "§7Needs at least §f" + plugin.minPlayers() + " §7players.", "",
                    ready ? "§eClick to unready" : "§aClick to ready up"), "ready");
        }
        set(m, 51, item(Material.COMPARATOR, "§6§lGame Settings", "§7Map, settings, twists, teams, presets.", "", "§eClick to open"), "game");
        set(m, 53, item(Material.BARRIER, leader ? "§c§lDisband Party" : "§c§lLeave Party",
                leader ? "§7Shift-click to disband" : "§7Click to leave"), "leave");
        fillRow(m, 5);
    }

    // ---------- GAME (tabs)

    private void renderGame(Menu m, Player v) {
        Party party = m.party;
        boolean leader = party.isLeader(v.getUniqueId());

        List<String> modeLore = new ArrayList<>();
        for (String d : party.mode.desc) modeLore.add("§7" + d);
        modeLore.add("");
        for (Mode md : Mode.values()) modeLore.add((md == party.mode ? "§a➜ §f" : "§8  §7") + md.display);
        modeLore.add("");
        modeLore.add(leader ? "§eClick to switch game mode" : "§7Only the leader can change this");
        set(m, 0, glow(item(party.mode.icon, "§b§lMode: §f" + party.mode.display, modeLore), true), "mode");
        String setLore = switch (party.mode) {
            case RIC -> "§7Drops, timers, crafting, border.";
            case DEATHSWAP -> "§7Swaps, lives, PvP, QoL, border.";
            case BLOCKSHUFFLE -> "§7Rounds, lives, PvP, QoL, border.";
            case MANHUNT, HITMAN -> party.mode.words("§7Head start, compass, respawns, nether, QoL.");
            case SPEEDRUN -> "§7Timer, keep inventory, nether luck, QoL.";
            case BINGO -> "§7Card size, mode, timers, drop-ins, kits.";
            case DEATHSHUFFLE -> "§7Death timer, lives, PvP, extra deaths, QoL.";
        };
        String twistLore = switch (party.mode) {
            case RIC -> "§7Random effects, enchants, blacklists.";
            case DEATHSWAP, BLOCKSHUFFLE -> "§7OP Loot, random effects.";
            case MANHUNT, HITMAN -> "§7OP Loot, infection, assassins and more.";
            case SPEEDRUN -> "§7Elytra race, OP Loot, lucky blocks and more.";
            case BINGO, DEATHSHUFFLE -> "§7Lucky blocks, structures, random effects.";
        };
        boolean hunt = party.mode.isHunt();
        set(m, 2, glow(item(Material.COMPARATOR, (m.tab == Tab.SETTINGS ? "§a▶ " : "§f") + "§lSETTINGS",
                setLore, "", m.tab == Tab.SETTINGS ? "§aViewing" : "§eClick to view"), m.tab == Tab.SETTINGS), "tab:SETTINGS");
        set(m, 4, glow(item(Material.FIREWORK_ROCKET, (m.tab == Tab.TWISTS ? "§a▶ " : "§f") + "§lTWISTS",
                twistLore, "", m.tab == Tab.TWISTS ? "§aViewing" : "§eClick to view"), m.tab == Tab.TWISTS), "tab:TWISTS");
        set(m, 6, glow(item(hunt ? Material.COMPASS : Material.WHITE_BANNER, (m.tab == Tab.TEAMS ? "§a▶ " : "§f") + (hunt ? "§lROLES" : "§lTEAMS"),
                hunt ? party.mode.words("§7Pick runner or hunter.") : "§7Pick a team or play solo.", "", m.tab == Tab.TEAMS ? "§aViewing" : "§eClick to view"), m.tab == Tab.TEAMS), "tab:TEAMS");
        fillRow(m, 0);

        switch (m.tab) {
            case SETTINGS -> renderSettingList(m, v, Setting.Cat.SETTINGS);
            case TWISTS -> renderSettingList(m, v, Setting.Cat.TWISTS);
            case TEAMS -> {
                if (hunt) renderRoles(m, v);
                else renderTeams(m, v);
            }
        }

        // bottom bar
        Setting ms = Setting.MAP_SIZE;
        List<String> mapLore = new ArrayList<>();
        for (int o = 0; o < ms.labels.length; o++) {
            boolean on = o == party.settings.index(ms);
            mapLore.add((on ? "§a➜ §f" : "§8  §7") + ms.labels[o] + (o == ms.def(party.mode) ? " §8(default)" : ""));
        }
        mapLore.add("");
        mapLore.add(leader ? "§eLeft-click: next §8• §eRight-click: back" : "§7Only the leader can change this");
        set(m, 45, item(Material.GRASS_BLOCK, "§a§lMap Size §8[§f" + party.settings.label(ms) + "§8]", mapLore), ms);
        set(m, 47, item(Material.BOOKSHELF, "§a§lPresets", "§7Save & load your settings.", "", "§eClick to open"), "presets");
        if (party.inGame()) {
            set(m, 49, item(Material.ENDER_EYE, "§b§lSpectate", "§7A game is running.", "", "§eClick to watch"), "spectate");
        } else {
            boolean ready = party.ready.contains(v.getUniqueId());
            set(m, 49, item(ready ? Material.LIME_DYE : Material.GRAY_DYE, ready ? "§a§lReady!" : "§c§lNot Ready",
                    "§7Ready: §f" + party.ready.size() + "/" + party.members.size(), "",
                    ready ? "§eClick to unready" : "§aClick to ready up"), "ready");
        }
        if (leader && m.tab != Tab.TEAMS) {
            set(m, 51, item(Material.TNT_MINECART, "§c§lReset " + (m.tab == Tab.TWISTS ? "Twists" : "Settings"),
                    "§7Shift-click to put this tab", "§7back to defaults."), "reset");
        }
        if (leader) set(m, 52, item(Material.EMERALD, "§a§lRandomize", "§7Roll random settings and twists", "§7right now. Time limit, hunger and", "§7world loot stay the same.", "", "§eClick to roll"), "randomize");
        if (plugin.parties().enabled()) set(m, 53, item(Material.NETHER_STAR, "§d§lParty", "§7Back to the party menu"), "party");
        fillRow(m, 5);
    }

    private void renderSettingList(Menu m, Player v, Setting.Cat cat) {
        boolean leader = m.party.isLeader(v.getUniqueId());
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        int i = 0;
        for (Setting s : Setting.values()) {
            if (s.cat != cat || !s.in(m.party.mode) || i >= slots.length) continue;
            String val = m.party.settings.label(s);
            String col = val.equals("Off") || val.equals("None") ? "§c" : "§a";
            List<String> lore = new ArrayList<>();
            for (String d : s.desc) lore.add("§7" + m.party.mode.words(d));
            lore.add("");
            if (!s.isToggle()) {
                for (int o = 0; o < s.labels.length; o++) {
                    boolean on = o == m.party.settings.index(s);
                    lore.add((on ? "§a➜ §f" : "§8  §7") + s.labels[o] + (o == s.def(m.party.mode) ? " §8(default)" : ""));
                }
                lore.add("");
            } else {
                lore.add("§8Default: " + s.labels[s.def(m.party.mode)]);
            }
            if (leader) lore.add(s.isToggle() ? "§eClick to toggle" : "§eLeft-click: next §8• §eRight-click: back");
            else lore.add("§7Only §e" + m.party.leaderName() + " §7can change this");
            ItemStack it = item(s.icon, "§6" + s.title(m.party.mode) + " §8[§f" + col + val + "§8]", lore);
            set(m, slots[i++], glow(it, !m.party.settings.isDefault(s)), s);
        }
        Mode md = m.party.mode;
        if (cat == Setting.Cat.TWISTS && md.hasOpLoot()) {
            set(m, slots[i++], glow(item(Material.ENCHANTED_GOLDEN_APPLE, "§6OP Loot Twists §8[§f" + m.party.settings.opLoot.size() + " on§8]",
                    "§7Pick things that drop OP loot", "§7instead of their normal drops.", "", "§eClick to open"), !m.party.settings.opLoot.isEmpty()), "oploot");
        }
        if (cat == Setting.Cat.TWISTS && md.isHunt()) {
            set(m, slots[i++], item(Material.STRUCTURE_VOID, "§6Blacklist Items §8[§f" + m.party.settings.itemBlacklist.size() + " banned§8]",
                    "§7Items nobody can pick up, craft", "§7or use this game.", "", "§eClick to open"), "blacklist");
            set(m, slots[i++], item(Material.POTION, "§6Blacklist Potions §8[§f" + m.party.settings.potionBlacklist.size() + " banned§8]",
                    "§7Potion effects nobody can get", "§7this game.", "", "§eClick to open"), "potions");
            set(m, slots[i], item(Material.ZOMBIE_HEAD, "§dMorph Twists §8[§7Coming later§8]",
                    "§7Turn players into mobs with their", "§7own perks and abilities.", "", "§8Not built yet!"), null);
        }
        if (cat == Setting.Cat.TWISTS && m.party.mode == Mode.RIC) {
            set(m, slots[i++], item(Material.STRUCTURE_VOID, "§6Blacklist Items §8[§f" + m.party.settings.itemBlacklist.size() + " banned§8]",
                    "§7Pick items that never show up", "§7in drops.", "", "§eClick to open"), "blacklist");
            set(m, slots[i++], item(Material.POTION, "§6Blacklist Potions §8[§f" + m.party.settings.potionBlacklist.size() + " banned§8]",
                    "§7Pick potion types that never", "§7show up in drops.", "", "§eClick to open"), "potions");
            set(m, slots[i], item(Material.ZOMBIE_HEAD, "§dMorph Twists §8[§7Coming later§8]",
                    "§7Turn players into mobs with their", "§7own perks and abilities.", "", "§8Not built yet!"), null);
        }
    }

    private void renderTeams(Menu m, Player v) {
        Party party = m.party;
        int[] slots = {19, 21, 23, 25};
        TeamColor mine = party.teams.get(v.getUniqueId());
        int i = 0;
        for (TeamColor t : TeamColor.values()) {
            List<String> lore = new ArrayList<>();
            lore.add("§7Join the " + t.code + t.display.toLowerCase(Locale.ROOT) + " §7team.");
            lore.add("");
            int count = 0;
            for (UUID id : party.members) {
                if (party.teams.get(id) == t) {
                    Player p = Bukkit.getPlayer(id);
                    lore.add(t.code + "- " + (p == null ? "?" : p.getName()));
                    count++;
                }
            }
            if (count == 0) lore.add("§8Nobody yet");
            lore.add("");
            lore.add(t == mine ? "§a✔ You're on this team" : party.teamsLocked ? "§cTeams are locked" : "§eClick to join");
            set(m, slots[i++], glow(item(t.wool, t.tag(), lore), t == mine), "team:" + t.name());
        }
        set(m, 31, glow(item(Material.WHITE_WOOL, "§f§lNo Team",
                "§7If nobody picks a team it's", "§7free for all. If others do, you get", "§7put on the smallest team.", "",
                mine == null ? "§a✔ Selected" : "§eClick to select"), mine == null), "team:NONE");
        if (party.isLeader(v.getUniqueId())) {
            set(m, 40, item(party.teamsLocked ? Material.IRON_BARS : Material.TRIPWIRE_HOOK,
                    party.teamsLocked ? "§c§lTeams Locked" : "§a§lTeams Unlocked",
                    "§7When locked, nobody can switch teams.", "", "§eClick to toggle"), "lock");
        }
    }

    private void renderRoles(Menu m, Player v) {
        Party party = m.party;
        Mode md = party.mode;
        Role mine = party.roles.get(v.getUniqueId());
        int[] slots = {20, 24};
        Material[] icons = {Material.LIME_WOOL, Material.RED_WOOL};
        String[] blurb = {md == Mode.MANHUNT ? "Beat the Ender Dragon. One life." : "Stay alive until the timer ends. One life.",
                md == Mode.MANHUNT ? "Stop the runners. Respawn forever." : "Hunt the survivors. Respawn forever."};
        for (Role r : Role.values()) {
            List<String> lore = new ArrayList<>();
            lore.add("§7" + blurb[r.ordinal()]);
            lore.add("");
            int count = 0;
            for (UUID id : party.members) {
                if (party.roles.get(id) == r) {
                    Player p = Bukkit.getPlayer(id);
                    lore.add(r.code() + "- " + (p == null ? "?" : p.getName()));
                    count++;
                }
            }
            if (count == 0) lore.add("§8Nobody picked this yet");
            lore.add("");
            lore.add(r == mine ? "§a✔ You picked this" : party.teamsLocked ? "§cRoles are locked" : "§eClick to pick");
            set(m, slots[r.ordinal()], glow(item(icons[r.ordinal()], r.code() + "§l" + r.plural(md).toUpperCase(), lore), r == mine), "role:" + r.name());
        }
        set(m, 31, glow(item(Material.WHITE_WOOL, "§f§lRandom / Fill",
                "§7No pick: you fill in wherever needed.", md.words("§7If no one picks runner, one random"), md.words("§7player becomes the runner."), "",
                mine == null ? "§a✔ Selected" : "§eClick to select"), mine == null), "role:NONE");
        if (party.isLeader(v.getUniqueId())) {
            set(m, 40, item(party.teamsLocked ? Material.IRON_BARS : Material.TRIPWIRE_HOOK,
                    party.teamsLocked ? "§c§lRoles Locked" : "§a§lRoles Unlocked",
                    "§7When locked, nobody can switch.", "", "§eClick to toggle"), "lock");
        }
    }

    // ---------- PRESETS / BLACKLISTS

    private void renderPresets(Menu m, Player v) {
        int[] slots = {11, 13, 15};
        for (int i = 0; i < 3; i++) {
            GameSettings saved = plugin.presets().get(v.getUniqueId(), i);
            List<String> lore = new ArrayList<>();
            if (saved == null) {
                lore.add("§7Empty");
            } else {
                for (String line : saved.summary()) lore.add("§7" + line);
            }
            lore.add("");
            if (saved != null) lore.add("§eLeft-click §7to load");
            lore.add("§eRight-click §7to save current settings here");
            if (saved != null) lore.add("§cShift-right-click §7to delete");
            set(m, slots[i], item(saved == null ? Material.BOOK : Material.ENCHANTED_BOOK, "§a§lPreset " + (i + 1), lore), "preset:" + i);
        }
        set(m, 22, item(Material.ARROW, "§7« Back"), "back");
    }

    private List<Material> blacklistItems(Menu m) {
        List<Material> list = new ArrayList<>(m.party.mode == Mode.RIC ? plugin.pool().all : plugin.pool().everyItem());
        list.sort((a, b) -> a.name().compareTo(b.name()));
        if (m.filter != null && !m.filter.isBlank()) {
            String f = m.filter.toUpperCase(Locale.ROOT).replace(' ', '_');
            list.removeIf(x -> !x.name().contains(f));
        }
        return list;
    }

    private void renderBlacklist(Menu m, Player v) {
        List<Material> list = blacklistItems(m);
        int pages = Math.max(1, (list.size() + 44) / 45);
        m.page = Math.max(0, Math.min(m.page, pages - 1));
        boolean leader = m.party.isLeader(v.getUniqueId());
        for (int i = 0; i < 45; i++) {
            int idx = m.page * 45 + i;
            if (idx >= list.size()) break;
            Material mat = list.get(idx);
            boolean bl = m.party.settings.itemBlacklist.contains(mat.name());
            ItemStack it = item(mat, (bl ? "§c§m" : "§f") + Msg.pretty(mat),
                    bl ? "§c✖ Banned" : "§a✔ In the pool", "", leader ? "§eClick to toggle" : "");
            set(m, i, glow(it, bl), "bl:" + mat.name());
        }
        set(m, 45, item(Material.ARROW, "§7« Back"), "back");
        if (m.page > 0) set(m, 46, item(Material.SPECTRAL_ARROW, "§e« Previous Page"), "prev");
        set(m, 48, item(Material.NAME_TAG, "§e§lSearch" + (m.filter == null ? "" : " §7(\"" + m.filter + "\")"),
                "§7Type §e/blacklist <word>", "§7to filter, like §e/blacklist sword", "", m.filter == null ? "" : "§eClick to clear search"), "clearfilter");
        set(m, 49, item(Material.PAPER, "§fPage " + (m.page + 1) + "/" + pages,
                "§7" + list.size() + " items", "§c" + m.party.settings.itemBlacklist.size() + " banned"), null);
        if (leader) set(m, 50, item(Material.LAVA_BUCKET, "§c§lClear Blacklist", "§7Shift-click to allow everything again"), "clearbl");
        if (m.page < pages - 1) set(m, 52, item(Material.SPECTRAL_ARROW, "§eNext Page »"), "next");
        fillRow(m, 5);
    }

    private void renderPotions(Menu m, Player v) {
        boolean leader = m.party.isLeader(v.getUniqueId());
        int i = 0;
        List<PotionType> pots = m.party.mode == Mode.RIC ? new ArrayList<>(plugin.pool().potions) : new ArrayList<>(java.util.Arrays.asList(PotionType.values()));
        for (PotionType t : pots) {
            if (i >= 45) break;
            boolean bl = m.party.settings.potionBlacklist.contains(t.name());
            ItemStack it = item(Material.POTION, (bl ? "§c§m" : "§f") + Msg.pretty(t.name()),
                    bl ? "§c✖ Banned" : "§a✔ In the pool", "", leader ? "§eClick to toggle" : "");
            ItemMeta meta = it.getItemMeta();
            if (meta instanceof PotionMeta pm) {
                pm.setBasePotionType(t);
                it.setItemMeta(pm);
            }
            set(m, i++, glow(it, bl), "pot:" + t.name());
        }
        set(m, 45, item(Material.ARROW, "§7« Back"), "back");
        if (leader) set(m, 50, item(Material.LAVA_BUCKET, "§c§lClear Potion Blacklist", "§7Shift-click to allow every potion"), "clearpot");
        fillRow(m, 5);
    }

    // ---------- IN GAME

    private void renderTracker(Menu m, Player v) {
        Match match = plugin.matchFor(v.getWorld());
        if (match == null) return;
        UUID locked = match.trackLock.get(v.getUniqueId());
        set(m, 4, glow(item(Material.COMPASS, "§e§lNearest Enemy", "§7Always point at whoever's closest.", "",
                locked == null && !match.trackPortal.contains(v.getUniqueId()) ? "§a✔ Selected" : "§eClick to select"),
                locked == null && !match.trackPortal.contains(v.getUniqueId())), "track:nearest");
        if (match.mode.isHunt()) {
            boolean portal = match.trackPortal.contains(v.getUniqueId());
            set(m, 6, glow(item(Material.OBSIDIAN, "§d§lYour Last Portal", "§7Point back at the portal you came", "§7through in this dimension.", "",
                    portal ? "§a✔ Selected" : "§eClick to select"), portal), "track:portal");
        }
        fillRow(m, 0);
        int slot = 18;
        for (UUID id : match.enemiesOf(v.getUniqueId())) {
            if (slot > 53) break;
            Player t = Bukkit.getPlayer(id);
            boolean sel = id.equals(locked);
            List<String> lore = new ArrayList<>();
            if (t != null && t.getWorld().equals(v.getWorld())) {
                lore.add("§7Distance: §a" + (int) t.getLocation().distance(v.getLocation()) + "m");
            } else {
                lore.add("§7In a different dimension");
            }
            lore.add("");
            lore.add(sel ? "§a✔ Tracking" : "§eClick to track");
            set(m, slot++, glow(head(id, match.colored(id), lore), sel), "track:" + id);
        }
    }

    private void renderSpectate(Menu m, Player v) {
        Match match = m.party.match;
        if (match == null) return;
        int slot = 0;
        for (UUID id : match.alive) {
            if (slot > 44) break;
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            List<String> lore = new ArrayList<>();
            lore.add("§7Health: §c" + (int) Math.ceil(p.getHealth()) + "❤");
            lore.add("§7Kills: §f" + match.kills.getOrDefault(id, 0));
            lore.add("");
            lore.add("§eClick to teleport");
            set(m, slot++, head(id, match.colored(id), lore), "tp:" + id);
        }
        set(m, 49, item(Material.RED_BED, "§c§lLeave Game", "§7Back to the waiting area"), "leavegame");
        fillRow(m, 5);
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int raw = e.getRawSlot();
        if (raw < 0 || raw >= m.inv.getSize()) return;
        Object action = m.slots[raw];
        if (action == null) return;
        boolean right = e.isRightClick();
        boolean shift = e.isShiftClick();
        Party party = m.party;
        boolean leader = party != null && party.isLeader(p.getUniqueId());

        if (action instanceof Setting s) {
            if (!leader) { notLeader(p, party); return; }
            party.settings.cycle(s, right ? -1 : 1);
            click(p);
            String val = party.settings.label(s);
            String col = val.equals("Off") || val.equals("None") ? "§c" : "§a";
            party.broadcast("§e" + p.getName() + " §7set §6" + s.title(party.mode) + " §7to " + col + val);
            refreshParty(party);
            return;
        }
        String a = (String) action;
        String arg = a.contains(":") ? a.substring(a.indexOf(':') + 1) : null;
        String key = a.contains(":") ? a.substring(0, a.indexOf(':')) : a;

        // leaderboard (works without a party)
        if (key.equals("lbboard")) {
            click(p);
            openLeaderboard(p, Stats.Board.valueOf(arg), m.mode);
            return;
        }
        if (key.equals("lbmode")) {
            click(p);
            List<String> modes = new ArrayList<>(Stats.MODES.keySet());
            String next = modes.get((modes.indexOf(m.mode) + 1) % modes.size());
            openLeaderboard(p, m.board, next);
            return;
        }

        if (key.equals("bspick")) {
            Match match = plugin.matchFor(p.getWorld());
            if (match == null || !match.choosing || !match.alive.contains(p.getUniqueId())) return;
            Material pick = Material.valueOf(arg);
            match.chosen.put(p.getUniqueId(), pick);
            click(p);
            p.closeInventory();
            p.sendMessage(match.tag() + "You picked §f" + Msg.pretty(pick) + "§7. Go stand on it!");
            return;
        }

        // party-less actions
        if (key.equals("create")) {
            plugin.parties().create(p);
            openParty(p);
            return;
        }
        if (key.equals("joinparty")) {
            p.closeInventory();
            plugin.parties().joinOpen(p, arg);
            return;
        }
        if (party == null) return;

        switch (key) {
            case "back" -> {
                Tab back = m.type == Type.PRESETS ? Tab.SETTINGS : Tab.TWISTS;
                open(p, Type.GAME, party, null, back);
            }
            case "tab" -> {
                click(p);
                open(p, Type.GAME, party, null, Tab.valueOf(arg));
            }
            case "game" -> open(p, Type.GAME, party, null, Tab.SETTINGS);
            case "party" -> open(p, Type.PARTY, party, null);
            case "presets" -> open(p, Type.PRESETS, party, null);
            case "blacklist" -> open(p, Type.BLACKLIST, party, null);
            case "potions" -> open(p, Type.POTIONS, party, null);
            case "spectate" -> { p.closeInventory(); plugin.spectate(p); }
            case "ready" -> plugin.parties().toggleReady(p);
            case "open" -> {
                if (!leader) { notLeader(p, party); return; }
                plugin.parties().setOpen(p, !party.open);
                refreshParty(party);
            }
            case "invite" -> {
                p.closeInventory();
                Msg.tellraw(p, "[" + Msg.suggest("» Click here, then type a name: /p invite <name>", "yellow", "/p invite ", "Fills in the command") + "]");
            }
            case "leave" -> {
                if (leader) {
                    if (!shift) { Msg.send(p, Msg.PARTY, "Shift-click to disband the party."); return; }
                    p.closeInventory();
                    plugin.parties().disband(p);
                } else {
                    p.closeInventory();
                    plugin.parties().leave(p, false);
                }
            }
            case "member" -> {
                if (!leader) return;
                if (right) plugin.parties().kick(p, arg);
                else plugin.parties().promote(p, arg);
                refreshParty(party);
            }
            case "reset" -> {
                if (!leader || !shift) return;
                Setting.Cat cat = m.tab == Tab.TWISTS ? Setting.Cat.TWISTS : Setting.Cat.SETTINGS;
                for (Setting s : Setting.values()) if (s.cat == cat && s.in(party.mode)) party.settings.set(s, s.def(party.mode));
                if (cat == Setting.Cat.TWISTS) party.settings.opLoot.clear();
                party.broadcast("§e" + p.getName() + " §7reset the " + (cat == Setting.Cat.TWISTS ? "twists" : "settings") + " to default.");
                refreshParty(party);
            }
            case "team" -> {
                if (party.teamsLocked && !leader) { Msg.send(p, Msg.PARTY, "§cTeams are locked."); return; }
                if (party.inGame()) { Msg.send(p, Msg.PARTY, "§cWait for the current game to end."); return; }
                TeamColor before = party.teams.get(p.getUniqueId());
                TeamColor after = arg.equals("NONE") ? null : TeamColor.valueOf(arg);
                if (before == after) return;
                if (after == null) {
                    party.teams.remove(p.getUniqueId());
                    party.broadcast("§e" + p.getName() + " §7left their team §8(no team)");
                } else {
                    party.teams.put(p.getUniqueId(), after);
                    party.broadcast("§e" + p.getName() + " §7joined the " + after.code + "§l" + after.display + " §7team.");
                }
                click(p);
                refreshParty(party);
            }
            case "role" -> {
                if (party.teamsLocked && !leader) { Msg.send(p, Msg.PARTY, "§cRoles are locked."); return; }
                if (party.inGame()) { Msg.send(p, Msg.PARTY, "§cWait for the current game to end."); return; }
                Role before = party.roles.get(p.getUniqueId());
                Role after = arg.equals("NONE") ? null : Role.valueOf(arg);
                if (before == after) return;
                if (after == null) {
                    party.roles.remove(p.getUniqueId());
                    party.broadcast("§e" + p.getName() + " §7will fill in §8(random role)");
                } else {
                    party.roles.put(p.getUniqueId(), after);
                    party.broadcast("§e" + p.getName() + " §7picked " + after.code() + "§l" + after.display(party.mode));
                }
                click(p);
                refreshParty(party);
            }
            case "randomize" -> {
                if (!leader) return;
                party.settings.randomize();
                p.playSound(p.getLocation(), "minecraft:entity.experience_orb.pickup", 0.7f, 1.2f);
                party.broadcast("§e" + p.getName() + " §7rolled random settings! §8(Comparator to see them)");
                refreshParty(party);
            }
            case "mode" -> {
                if (!leader) { notLeader(p, party); return; }
                if (party.inGame() || party.countdown != null) {
                    Msg.send(p, Msg.PARTY, "§cCan't switch modes while a game is starting or running.");
                    return;
                }
                party.setMode(party.mode.next());
                click(p);
                party.broadcast("§e" + p.getName() + " §7switched the game mode to §b§l" + party.mode.display);
                for (Player x : party.online()) plugin.lobbyItems(x);
                for (Player x : party.online()) {
                    InventoryHolder h = x.getOpenInventory().getTopInventory().getHolder();
                    if (h instanceof Menu om && om.party == party && om.type == Type.GAME) open(x, Type.GAME, party, null, Tab.SETTINGS);
                }
            }
            case "oploot" -> open(p, Type.OPLOOT, party, null);
            case "op" -> {
                if (!leader) { notLeader(p, party); return; }
                OpSource o = OpSource.valueOf(arg);
                boolean on = !party.settings.opLoot.remove(o);
                if (on) party.settings.opLoot.add(o);
                click(p);
                party.broadcast("§e" + p.getName() + " §7turned " + (on ? "§aon" : "§coff") + " §6" + party.mode.words(o.display) + " Drop OP Loot");
                refreshParty(party);
            }
            case "opall", "opnone" -> {
                if (!leader || !shift) return;
                party.settings.opLoot.clear();
                if (key.equals("opall")) for (OpSource o : OpSource.values()) if (o.in(party.mode)) party.settings.opLoot.add(o);
                party.broadcast("§e" + p.getName() + " §7turned " + (key.equals("opall") ? "§aon every" : "§coff every") + " §7OP Loot twist");
                refreshParty(party);
            }
            case "lock" -> {
                if (!leader) return;
                party.teamsLocked = !party.teamsLocked;
                party.broadcast(party.teamsLocked ? "§cTeams are now locked." : "§aTeams are unlocked.");
                refreshParty(party);
            }
            case "preset" -> {
                int slot = Integer.parseInt(arg);
                if (right && shift) {
                    plugin.presets().delete(p.getUniqueId(), slot);
                    Msg.send(p, Msg.PARTY, "Deleted preset " + (slot + 1) + ".");
                } else if (right) {
                    plugin.presets().save(p.getUniqueId(), slot, party.settings);
                    Msg.send(p, Msg.PARTY, "§aSaved current settings to preset " + (slot + 1) + ".");
                } else {
                    if (!leader) { notLeader(p, party); return; }
                    GameSettings g = plugin.presets().get(p.getUniqueId(), slot);
                    if (g == null) return;
                    party.setMode(g.mode);
                    party.settings.copyFrom(g);
                    party.broadcast("§e" + p.getName() + " §7loaded preset " + (slot + 1) + ".");
                }
                click(p);
                refreshParty(party);
            }
            case "bl" -> {
                if (!leader) { notLeader(p, party); return; }
                boolean banned = !party.settings.itemBlacklist.remove(arg);
                if (banned) party.settings.itemBlacklist.add(arg);
                party.broadcast("§e" + p.getName() + " §7" + (banned ? "§cbanned" : "§aunbanned") + " §f" + Msg.pretty(arg) + " §7from drops");
                click(p);
                render(m, p);
            }
            case "pot" -> {
                if (!leader) { notLeader(p, party); return; }
                boolean banned = !party.settings.potionBlacklist.remove(arg);
                if (banned) party.settings.potionBlacklist.add(arg);
                party.broadcast("§e" + p.getName() + " §7" + (banned ? "§cbanned" : "§aunbanned") + " §f" + Msg.pretty(arg) + " §7potions");
                click(p);
                render(m, p);
            }
            case "clearbl" -> {
                if (!leader || !shift) return;
                party.settings.itemBlacklist.clear();
                party.broadcast("§e" + p.getName() + " §7cleared the item blacklist");
                render(m, p);
            }
            case "clearpot" -> {
                if (!leader || !shift) return;
                party.settings.potionBlacklist.clear();
                party.broadcast("§e" + p.getName() + " §7cleared the potion blacklist");
                render(m, p);
            }
            case "clearfilter" -> {
                m.filter = null;
                m.page = 0;
                render(m, p);
            }
            case "prev" -> { m.page--; render(m, p); }
            case "next" -> { m.page++; render(m, p); }
            case "track" -> {
                Match match = plugin.matchFor(p.getWorld());
                if (match == null) return;
                match.trackPortal.remove(p.getUniqueId());
                if (arg.equals("nearest")) match.trackLock.remove(p.getUniqueId());
                else if (arg.equals("portal")) { match.trackLock.remove(p.getUniqueId()); match.trackPortal.add(p.getUniqueId()); }
                else match.trackLock.put(p.getUniqueId(), UUID.fromString(arg));
                click(p);
                p.closeInventory();
            }
            case "tp" -> {
                Player t = Bukkit.getPlayer(UUID.fromString(arg));
                Match match = party.match;
                if (t != null && match != null && match.owns(t.getWorld()) && match.spectators.contains(p.getUniqueId())) {
                    p.teleport(t.getLocation());
                }
                p.closeInventory();
            }
            case "leavegame" -> {
                p.closeInventory();
                plugin.leaveGame(p);
            }
            default -> {
            }
        }
    }

    private void notLeader(Player p, Party party) {
        Msg.send(p, Msg.PARTY, "§cOnly the party leader (§e" + (party == null ? "?" : party.leaderName()) + "§c) can change that.");
    }

    private void click(Player p) {
        p.playSound(p.getLocation(), "minecraft:ui.button.click", 0.5f, 1.4f);
    }
}
