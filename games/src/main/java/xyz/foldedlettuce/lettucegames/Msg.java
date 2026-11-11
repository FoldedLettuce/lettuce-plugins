package xyz.foldedlettuce.lettucegames;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Locale;
import java.util.UUID;

/** Text helpers. Everything uses legacy section-sign colors so it works on every client. */
public final class Msg {
    private Msg() {}

    public static String PARTY = "§d§lParty §8» §7";
    public static final String GAME = "§c§lRIC §8» §7";
    public static final String SYS = "§a§lGames §8» §7";

    /** With parties off, party messages just say Games. */
    public static void setup(boolean partiesEnabled) {
        PARTY = partiesEnabled ? "§d§lParty §8» §7" : SYS;
    }

    public static void send(Player p, String prefix, String text) {
        if (p != null) p.sendMessage(prefix + text);
    }

    public static void to(Collection<UUID> ids, String text) {
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(text);
        }
    }

    /** Sends a raw text component (JSON/SNBT) through tellraw. Used for clickable buttons. */
    public static void tellraw(Player p, String json) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tellraw " + p.getName() + " " + json);
    }

    public static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** A clickable chat button piece. */
    public static String button(String text, String color, String command, String hover) {
        return "{\"text\":\"" + esc(text) + "\",\"color\":\"" + color + "\",\"bold\":true,"
                + "\"click_event\":{\"action\":\"run_command\",\"command\":\"" + esc(command) + "\"},"
                + "\"hover_event\":{\"action\":\"show_text\",\"value\":\"" + esc(hover) + "\"}}";
    }

    public static String suggest(String text, String color, String command, String hover) {
        return "{\"text\":\"" + esc(text) + "\",\"color\":\"" + color + "\","
                + "\"click_event\":{\"action\":\"suggest_command\",\"command\":\"" + esc(command) + "\"},"
                + "\"hover_event\":{\"action\":\"show_text\",\"value\":\"" + esc(hover) + "\"}}";
    }

    public static String plain(String text, String color) {
        return "{\"text\":\"" + esc(text) + "\",\"color\":\"" + color + "\"}";
    }

    /** 9000 -> "2h 30m 0s", 95 -> "1m 35s", 7 -> "7s" */
    public static String time(int seconds) {
        if (seconds < 0) seconds = 0;
        int h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (h > 0) return h + "h " + m + "m " + s + "s";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    /** NETHER_GOLD_ORE -> "Nether Gold Ore" */
    public static String pretty(String enumName) {
        StringBuilder sb = new StringBuilder();
        for (String w : enumName.toLowerCase(Locale.ROOT).split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    public static String pretty(Material m) {
        return pretty(m.name());
    }
}
