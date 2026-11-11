package xyz.foldedlettuce.lettucegames;

import org.bukkit.Material;

/** The game modes. Each has its own settings, sidebar, stats section and rules. */
public enum Mode {
    RIC("Random Items Challenge", "Random Items", "ric", Material.CHEST, "§c§lRANDOM ITEM CHALLENGE",
            "Everyone gets a hotbar of random items on a timer.", "Last player or team alive wins."),
    DEATHSWAP("Death Swap", "Death Swap", "deathswap", Material.ENDER_PEARL, "§5§lDEATH SWAP",
            "Every few minutes everyone swaps places.", "Leave a trap behind. Last one alive wins."),
    BLOCKSHUFFLE("Block Shuffle", "Block Shuffle", "blockshuffle", Material.MOSS_BLOCK, "§a§lBLOCK SHUFFLE",
            "Every round you get a random block.", "Stand on it before time runs out or you're out."),
    MANHUNT("Manhunt", "Manhunt", "manhunt", Material.COMPASS, "§c§lMANHUNT",
            "Runners try to beat the Ender Dragon.", "Hunters respawn forever and try to stop them."),
    HITMAN("Hitman", "Hitman", "hitman", Material.IRON_SWORD, "§4§lHITMAN",
            "Survivors have to stay alive until the timer ends.", "Hitmen respawn forever and hunt them down."),
    SPEEDRUN("Speedrun", "Speedrun", "speedrun", Material.ENDER_EYE, "§b§lSPEEDRUN",
            "Everyone works together to kill the", "Ender Dragon as fast as possible."),
    BINGO("Bingo", "Bingo", "bingo", Material.PAPER, "§e§lBINGO",
            "Race to complete a card of random tasks.", "First to a line (or most tiles) wins."),
    DEATHSHUFFLE("Death Shuffle", "Death Shuffle", "deathshuffle", Material.SKELETON_SKULL, "§4§lDEATH SHUFFLE",
            "Every round you get a way to die.", "Pull it off before time runs out or you're out.");

    public final String display;
    public final String shortName;
    /** used as the stats section name */
    public final String key;
    public final Material icon;
    public final String sidebarTitle;
    public final String[] desc;

    Mode(String display, String shortName, String key, Material icon, String sidebarTitle, String... desc) {
        this.display = display;
        this.shortName = shortName;
        this.key = key;
        this.icon = icon;
        this.sidebarTitle = sidebarTitle;
        this.desc = desc;
    }

    /** Manhunt and Hitman: runners/survivors vs hunters/hitmen. */
    public boolean isHunt() {
        return this == MANHUNT || this == HITMAN;
    }

    /** Modes where the End is open and killing the dragon matters. */
    public boolean hasEnd() {
        return this == MANHUNT || this == SPEEDRUN;
    }

    /** Modes with the OP Loot twist menu. */
    public boolean hasOpLoot() {
        return this == DEATHSWAP || this == SPEEDRUN || isHunt();
    }

    /** Modes where dying just sends you back to spawn. */
    public boolean respawns() {
        return this == SPEEDRUN || this == BINGO;
    }

    public String runner() { return this == HITMAN ? "Survivor" : "Runner"; }
    public String runners() { return this == HITMAN ? "Survivors" : "Runners"; }
    public String hunter() { return this == HITMAN ? "Hitman" : "Hunter"; }
    public String hunters() { return this == HITMAN ? "Hitmen" : "Hunters"; }

    /** Swaps Runner/Hunter wording for Survivor/Hitman in Hitman. */
    public String words(String text) {
        if (this != HITMAN || text == null) return text;
        return text.replace("Runners", "Survivors").replace("runners", "survivors")
                .replace("Runner", "Survivor").replace("runner", "survivor")
                .replace("Hunters", "Hitmen").replace("hunters", "hitmen")
                .replace("Hunter", "Hitman").replace("hunter", "hitman");
    }

    public Mode next() {
        Mode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
