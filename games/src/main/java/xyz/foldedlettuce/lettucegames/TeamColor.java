package xyz.foldedlettuce.lettucegames;

import org.bukkit.ChatColor;
import org.bukkit.Material;

public enum TeamColor {
    RED("Red", "§c", ChatColor.RED, Material.RED_WOOL),
    BLUE("Blue", "§9", ChatColor.BLUE, Material.BLUE_WOOL),
    GREEN("Green", "§a", ChatColor.GREEN, Material.LIME_WOOL),
    YELLOW("Yellow", "§e", ChatColor.YELLOW, Material.YELLOW_WOOL);

    public final String display;
    public final String code;
    public final ChatColor chat;
    public final Material wool;

    TeamColor(String display, String code, ChatColor chat, Material wool) {
        this.display = display;
        this.code = code;
        this.chat = chat;
        this.wool = wool;
    }

    public String tag() {
        return code + "§l" + display.toUpperCase();
    }
}
