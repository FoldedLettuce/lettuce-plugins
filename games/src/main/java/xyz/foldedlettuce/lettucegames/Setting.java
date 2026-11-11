package xyz.foldedlettuce.lettucegames;

import org.bukkit.Material;

/**
 * Every party setting. Each one is a list of options, the party stores the index.
 * Add a new setting here and it shows up in the menus, the start summary and presets automatically.
 */
public enum Setting {
    // ---------- map ----------
    MAP_SIZE(Cat.MAP, "Map Size", Material.GRASS_BLOCK,
            new String[]{"How big the world border is."},
            new String[]{"1k map (±500)", "3k map (±1500)", "5k map (±2500)", "7k map (±3500)", "20k map (±10000)"},
            new int[]{500, 1500, 2500, 3500, 10000}, 0),

    // ---------- settings ----------
    DROP_FIRST(Cat.SETTINGS, "First Drop", Material.CLOCK,
            new String[]{"How long after the game starts", "the first item drop arrives."},
            new String[]{"10s", "20s", "30s", "45s", "1m", "1m 30s", "2m"},
            new int[]{10, 20, 30, 45, 60, 90, 120}, 0),
    DROP_INTERVAL(Cat.SETTINGS, "Drop Frequency", Material.HOPPER,
            new String[]{"Time between item drops", "after the first one."},
            new String[]{"30s", "45s", "1m", "1m 30s", "2m", "2m 30s", "3m", "4m", "5m"},
            new int[]{30, 45, 60, 90, 120, 150, 180, 240, 300}, 1),
    DROP_SIZE(Cat.SETTINGS, "Drop Size", Material.CHEST,
            new String[]{"How many stacks of the random", "item you get each drop."},
            new String[]{"1 stack", "3 stacks", "5 stacks", "9 stacks (full hotbar)"},
            new int[]{1, 3, 5, 9}, 1),
    GROUND_SWEEP(Cat.SETTINGS, "Clear Ground Before Drops", Material.BRUSH,
            new String[]{"On: every item lying on the ground", "gets deleted right before each drop.",
                    "Off: items stay until they despawn."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    DROP_COUNTDOWN(Cat.SETTINGS, "Item Drop Countdown", Material.RECOVERY_COMPASS,
            new String[]{"Shows a countdown to the next", "item drop on the sidebar."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 1),
    GRACE(Cat.SETTINGS, "Grace Period", Material.GOLDEN_APPLE,
            new String[]{"No PvP damage for this long", "after the game starts."},
            new String[]{"Off", "15s", "30s", "1m", "2m", "5m"},
            new int[]{0, 15, 30, 60, 120, 300}, 1),
    TIME_LIMIT(Cat.SETTINGS, "Game Time Limit", Material.SUNFLOWER,
            new String[]{"If time runs out with more than", "one player or team alive, it's a draw."},
            new String[]{"30m", "45m", "1h", "1h 30m", "2h", "3h"},
            new int[]{1800, 2700, 3600, 5400, 7200, 10800}, 0),
    FRIENDLY_FIRE(Cat.SETTINGS, "Friendly Fire", Material.IRON_SWORD,
            new String[]{"Can teammates hurt each other?", "§cOff§7: never  §aOn§7: always",
                    "§6Boosting§7: punching a teammate knocks", "them back but does no damage.",
                    "No weapons (they just don't hit)."},
            new String[]{"Off", "On", "Boosting"}, new int[]{0, 1, 2}, 0),
    CRAFTING(Cat.SETTINGS, "Allow Crafting", Material.CRAFTING_TABLE,
            new String[]{"Lets players use crafting recipes.", "Off means drops only."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 1),
    WEAPON_CRAFTING(Cat.SETTINGS, "Allow Weapons Crafting", Material.IRON_PICKAXE,
            new String[]{"Lets players craft weapons, tools,", "armor and shields.",
                    "§8(Needs Allow Crafting on)"},
            new String[]{"Off", "On"}, new int[]{0, 1}, 1),
    SHARE_ITEMS(Cat.SETTINGS, "Drop Sharing", Material.ENDER_CHEST,
            new String[]{"Who can pick up the overflow from", "your item drops that lands on the ground."},
            new String[]{"Only you", "Teammates", "Anyone"}, new int[]{0, 1, 2}, 0),
    THROWN_ITEMS(Cat.SETTINGS, "Thrown Items", Material.HOPPER_MINECART,
            new String[]{"Who can pick up items you throw on", "the ground with Q.",
                    "§8(No teams: Teammates works like Only you)"},
            new String[]{"Only you", "Teammates", "Anyone"}, new int[]{0, 1, 2}, 1),
    DEATH_LOOT(Cat.SETTINGS, "Death Loot", Material.SKELETON_SKULL,
            new String[]{"What happens to your stuff when you die.", "§fNo Drop§7: it's gone.",
                    "§fTeammates§7: only your team can grab it.", "§fAnyone§7: free for all."},
            new String[]{"No Drop", "Teammates", "Anyone"}, new int[]{0, 1, 2}, 2),
    NETHER(Cat.SETTINGS, "Enable Nether", Material.NETHERRACK,
            new String[]{"Lets nether portals work.", "The End is always off."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    MOB_LOOT(Cat.SETTINGS, "Enable Mob Loot", Material.ROTTEN_FLESH,
            new String[]{"Mobs drop items and XP when killed."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    SPAWN_EGGS(Cat.SETTINGS, "Mob Spawn Eggs", Material.EGG,
            new String[]{"Adds mob spawn eggs to the item pool.", "§8(No Ender Dragon or Wither eggs)"},
            new String[]{"Off", "On"}, new int[]{0, 1}, 1),
    SHRINKING_BORDER(Cat.SETTINGS, "Shrinking Border", Material.BARRIER,
            new String[]{"§fOff§7: border stays put, then closes", "to 100 blocks at Final Border Close.",
                    "§fNormal§7: shrinks in stages from 10m.", "§fRush§7: shorter wait, faster shrink.",
                    "§fZones§7: Fortnite style. 5 circles, each", "one somewhere random inside the last.",
                    "The next zone is announced before it moves."},
            new String[]{"Off", "Normal", "Rush", "Zones"}, new int[]{0, 1, 2, 3}, 0),
    BORDER_CLOSE(Cat.SETTINGS, "Final Border Close", Material.IRON_BARS,
            new String[]{"When Shrinking Border is Off, the", "border closes to 100x100 at this time."},
            new String[]{"3m", "5m", "8m", "10m", "15m", "Never"},
            new int[]{180, 300, 480, 600, 900, -1}, 1),
    HEIGHT_LIMIT(Cat.SETTINGS, "Max Build Height", Material.LADDER,
            new String[]{"The highest you can build. Blocks can't", "be placed above it. You can still walk,",
                    "climb and jump higher, it never hurts you."},
            new String[]{"None", "Y 64", "Y 80", "Y 100", "Y 128", "Y 160", "Y 200"},
            new int[]{Integer.MAX_VALUE, 64, 80, 100, 128, 160, 200}, 0),
    DIG_LIMIT(Cat.SETTINGS, "Dig Limit", Material.IRON_SHOVEL,
            new String[]{"The lowest you can dig.", "Breaking blocks below it is denied."},
            new String[]{"None", "Y 48", "Y 32", "Y 16", "Y 0", "Y -32"},
            new int[]{Integer.MIN_VALUE, 48, 32, 16, 0, -32}, 0),
    HUNGER(Cat.SETTINGS, "Hunger", Material.COOKED_BEEF,
            new String[]{"Off: your hunger bar never drops and", "health regens at the normal slow speed.",
                    "§8(With the Eating twist on, the bar sits", "§8one notch below full so you can still eat.)"},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    WORLD_LOOT(Cat.SETTINGS, "World Loot", Material.OAK_LEAVES,
            new String[]{"Off: breaking blocks gives nothing (except", "blocks you placed). Structure chests still",
                    "have loot. No fishing, shearing, bartering,", "berries, leaf or explosion drops.", "On: normal Minecraft."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),

    // ---------- death swap ----------
    SWAP_DELAY(Cat.SETTINGS, "Swap Delay", Material.ENDER_PEARL,
            new String[]{"Time between swaps.", "Random picks a new 2-6 minute gap", "every swap."},
            new String[]{"1m", "2m", "3m", "4m", "5m", "6m", "8m", "10m", "Random (2-6m)"},
            new int[]{60, 120, 180, 240, 300, 360, 480, 600, -1}, 4),
    SWAP_TIMER(Cat.SETTINGS, "Swap Timer", Material.CLOCK,
            new String[]{"When the countdown to the next swap", "shows on the sidebar. The 10 second",
                    "warning always shows."},
            new String[]{"Always", "Last 10s only"}, new int[]{0, 1}, 0),
    PVP(Cat.SETTINGS, "Allow PvP", Material.IRON_SWORD,
            new String[]{"Can players hit each other?", "Off is classic Death Swap: traps only."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    LIVES(Cat.SETTINGS, "Lives", Material.TOTEM_OF_UNDYING,
            new String[]{"How many times you can die before", "you're out. You respawn at spawn."},
            new String[]{"1", "2", "3", "5"}, new int[]{1, 2, 3, 5}, 0),
    KEEP_INVENTORY(Cat.SETTINGS, "Keep Inventory", Material.CHEST,
            new String[]{"Players keep their inventory when they", "die and respawn (needs 2+ lives)."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    FIRE_RES(Cat.SETTINGS, "Fire Resistance", Material.MAGMA_CREAM,
            new String[]{"Players are immune to fire and lava damage."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    FALL_RES(Cat.SETTINGS, "Fall Resistance", Material.FEATHER,
            new String[]{"Players are immune to fall damage."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    BED_BOMBING(Cat.SETTINGS, "Allow Bed Bombing", Material.RED_BED,
            new String[]{"Beds in the nether and respawn anchors", "in the overworld explode like vanilla.",
                    "Off: they just don't work there."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 1),
    BED_REDUCE(Cat.SETTINGS, "Reduce Bed Bombing Damage", Material.WHITE_BED,
            new String[]{"Bed and respawn anchor explosions", "do 70% less damage to players."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    NETHER_ENTRY_RES(Cat.SETTINGS, "Nether Entry Resistance", Material.BLAZE_POWDER,
            new String[]{"15s of fire resistance when you enter", "the nether (portal or swap)."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    VEIN_MINER(Cat.SETTINGS, "Vein Miner", Material.IRON_PICKAXE,
            new String[]{"Mining an ore with a pickaxe that can", "harvest it also mines every ore of the",
                    "same type touching it (up to 64)."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    AUTO_SMELT(Cat.SETTINGS, "Auto Smelt", Material.FURNACE,
            new String[]{"Iron, gold and copper ores drop ingots", "instead of raw ore when mined with a pickaxe."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    AUTO_COOK(Cat.SETTINGS, "Auto Cook", Material.CAMPFIRE,
            new String[]{"Mobs killed by players drop cooked", "meat and fish instead of raw."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),

    // ---------- block shuffle ----------
    ROUND_TIME(Cat.SETTINGS, "New Blocks Interval", Material.CLOCK,
            new String[]{"How long each round lasts. Get it done", "before time's up."},
            new String[]{"1m", "2m", "3m", "4m", "5m", "6m", "8m", "10m"},
            new int[]{60, 120, 180, 240, 300, 360, 480, 600}, 4),
    ELIMINATION_MODE(Cat.SETTINGS, "Elimination Mode", Material.SKELETON_SKULL,
            new String[]{"The last player to finish each round", "is eliminated (or loses a life), even",
                    "before the timer runs out."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    SAME_BLOCK(Cat.SETTINGS, "Same Block", Material.WHITE_WOOL,
            new String[]{"Everyone gets the same one each round."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),

    // ---------- manhunt / hitman ----------
    HEAD_START(Cat.SETTINGS, "Runner Head Start", Material.LEATHER_BOOTS,
            new String[]{"Everyone is frozen for a few seconds at the", "start. Then runners get this long before",
                    "hunters can move."},
            new String[]{"None", "10s", "15s", "30s", "45s", "1m", "1m 30s", "2m"},
            new int[]{0, 10, 15, 30, 45, 60, 90, 120}, 3),
    HITMEN_FREEZE(Cat.SETTINGS, "Hitmen Freeze Timer", Material.PACKED_ICE,
            new String[]{"How long hitmen are frozen at the start", "so survivors can scatter."},
            new String[]{"None", "5s", "10s", "15s", "20s", "30s", "45s", "1m"},
            new int[]{0, 5, 10, 15, 20, 30, 45, 60}, 2),
    GLOWING(Cat.SETTINGS, "Glowing Runners", Material.GLOWSTONE_DUST,
            new String[]{"Runners glow so everyone can see", "them through walls."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    HUNTER_RESPAWN(Cat.SETTINGS, "Hunter Respawn Time", Material.RESPAWN_ANCHOR,
            new String[]{"How long a hunter waits before", "respawning. Hunters always respawn."},
            new String[]{"Instant", "5s", "10s", "15s", "30s", "1m"},
            new int[]{0, 5, 10, 15, 30, 60}, 1),
    ADV_COMPASS(Cat.SETTINGS, "Advanced Compass", Material.RECOVERY_COMPASS,
            new String[]{"Off: the compass always points at the", "nearest runner.",
                    "On: right-click to pick which runner to", "track, or your last portal. Left-click cycles."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 1),
    NETHER_FIRST(Cat.SETTINGS, "Runners Enter Nether First", Material.CRYING_OBSIDIAN,
            new String[]{"Hunters can't go through a nether", "portal until a runner has been", "to the nether."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    INCREASED_LUCK(Cat.SETTINGS, "Increased Luck", Material.RABBIT_FOOT,
            new String[]{"Pearl bartering: 2.5% -> 6.6%", "Blaze rods: 50% -> 66.6%", "Enderman pearls: 50% -> 66.6%"},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    NO_BRUTES(Cat.SETTINGS, "Disable Brute Piglin Spawn", Material.GOLDEN_AXE,
            new String[]{"Piglin brutes never spawn."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    PROTECT_SPAWNERS(Cat.SETTINGS, "Protect Blaze Spawners", Material.SPAWNER,
            new String[]{"Blaze spawners can't be broken or", "blown up."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    HUNTER_KEEP_INV(Cat.SETTINGS, "Keep Inventory (Hunters)", Material.ENDER_CHEST,
            new String[]{"Hunters keep their stuff when they die.", "Runners never keep inventory."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    ANNOUNCE_TWISTS(Cat.SETTINGS, "Announce Twists to Hunters", Material.BELL,
            new String[]{"Hunters see the twist list in chat", "when the game starts. Off: only", "runners know what's on."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 1),

    // ---------- every mode
    DAYLIGHT(Cat.SETTINGS, "Daylight", Material.CLOCK,
            new String[]{"§fNormal§7: a fresh day/night cycle", "starting in the morning.", "§fAlways Day§7: the sun never sets."},
            new String[]{"Normal", "Always Day"}, new int[]{0, 1}, 0),
    VOTE_SKIP(Cat.SETTINGS, "Vote Skip", Material.ARROW,
            new String[]{"Players can type §e/skip §7to vote to", "reroll this round's blocks or deaths.",
                    "Passes with more than half the players."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 1),

    // ---------- death shuffle
    MORE_DEATHS(Cat.SETTINGS, "Add More Deaths", Material.WITHER_SKELETON_SKULL,
            new String[]{"Adds an extra set of deaths with more", "ways to die (piglins, ghasts, llamas, beds...)."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),

    // ---------- bingo
    BINGO_MODE(Cat.SETTINGS, "Mode", Material.PAPER,
            new String[]{"§fStandard§7: first to a line wins.", "§fLockout§7: each tile can only be claimed",
                    "once. Score for each task.", "§fSingle§7: first to any task wins."},
            new String[]{"Standard", "Lockout", "Single"}, new int[]{0, 1, 2}, 0),
    CARD_SIZE(Cat.SETTINGS, "Card Size", Material.ITEM_FRAME,
            new String[]{"Size of the bingo card.", "Bigger cards = more tasks."},
            new String[]{"3x3", "4x4", "5x5", "6x6"}, new int[]{3, 4, 5, 6}, 2),
    WIN_CONDITION(Cat.SETTINGS, "Win Condition", Material.TARGET,
            new String[]{"Standard mode only.", "§fLine§7: first to finish a row, column", "or diagonal wins.",
                    "§fFull Board§7: first to fill the whole card."},
            new String[]{"Line", "Full Board"}, new int[]{0, 1}, 0),
    BINGO_END(Cat.SETTINGS, "End Condition", Material.CLOCK,
            new String[]{"Standard mode only. When the game ends.", "Players who finish become spectators.",
                    "The first finisher is the winner."},
            new String[]{"First to finish", "3 finish", "5 finish", "All but one"}, new int[]{1, 3, 5, -1}, 0),
    LOCKOUT_END(Cat.SETTINGS, "Lockout End Condition", Material.IRON_DOOR,
            new String[]{"Lockout mode only.", "§fMajority§7: more than half the tiles wins", "right away.",
                    "§fAll Tasks Completed§7: no early wins, plays", "until the board is full or time's up."},
            new String[]{"Majority", "All Tasks Completed"}, new int[]{0, 1}, 0),
    LINE_BONUS(Cat.SETTINGS, "Lockout Line Bonus", Material.GOLD_NUGGET,
            new String[]{"Lockout mode only. Bonus points when you", "finish a full line of your own claims.",
                    "Ends early once the leader can't be caught."},
            new String[]{"Off", "+1", "+2", "+3", "+5"}, new int[]{0, 1, 2, 3, 5}, 0),
    BINGO_TIME(Cat.SETTINGS, "Game Duration", Material.SUNFLOWER,
            new String[]{"How long the game lasts. When time runs", "out, most tiles (or points) wins."},
            new String[]{"10m", "20m", "30m", "40m", "50m", "1h", "1h 30m", "2h"},
            new int[]{600, 1200, 1800, 2400, 3000, 3600, 5400, 7200}, 2),
    PREVIEW(Cat.SETTINGS, "Preview Card Timer", Material.SPYGLASS,
            new String[]{"How long everyone gets to look at the", "card before the game starts. Everyone is",
                    "frozen and safe while it's up."},
            new String[]{"Off", "10s", "15s", "20s", "30s", "45s", "60s"}, new int[]{0, 10, 15, 20, 30, 45, 60}, 4),
    RESPAWN_TIMER(Cat.SETTINGS, "Respawn Timer", Material.RESPAWN_ANCHOR,
            new String[]{"How long it takes to respawn after dying.", "Dying is a setback, not an elimination."},
            new String[]{"Instant", "3s", "5s", "10s", "15s"}, new int[]{0, 3, 5, 10, 15}, 2),
    SPAWN_MODE(Cat.SETTINGS, "Drop In", Material.FEATHER,
            new String[]{"How players spawn at the start.", "§fOn Ground§7: spawn on the ground.",
                    "§fGlide§7: drop from the sky with an elytra.", "§fGlide + Firework§7: same plus a rocket.",
                    "Gliders get 60s of no fall damage."},
            new String[]{"On Ground", "Glide", "Glide + Firework"}, new int[]{0, 1, 2}, 0),
    BIOME_TASKS(Cat.SETTINGS, "Biome Tasks", Material.FILLED_MAP,
            new String[]{"Include tasks that depend on finding", "certain biomes. §6(Experimental)"},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    BINGO_COMPASS(Cat.SETTINGS, "Tracker Compass", Material.COMPASS,
            new String[]{"Everyone gets a tracker compass that", "points at the nearest opponent."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    BINGO_ELYTRA(Cat.SETTINGS, "Elytra", Material.ELYTRA,
            new String[]{"Everyone gets an unbreakable elytra and", "a reusable firework (5s cooldown)."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    STARTER_KIT(Cat.SETTINGS, "Starter Kit", Material.STONE_PICKAXE,
            new String[]{"Everyone starts with stone tools and", "16 steak."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    OPPONENT_TASKS(Cat.SETTINGS, "Opponent Tasks", Material.TARGET,
            new String[]{"Tasks about other players: kill one, hit", "one with a snowball, out-level them...",
                    "Up to 3 per card. Needs PvP for the", "fighting ones."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 1),

    // ---------- twists ----------
    BS_FORMAT(Cat.TWISTS, "Format", Material.NAME_TAG,
            new String[]{"§fClassic§7: find your block or you're out.",
                    "§fBlockswap§7: every other round you pick your", "own block (30s), next round they get",
                    "handed to other players.",
                    "§fBlockRace§7: +1 per block found, new block", "instantly. Lowest score each round is out."},
            new String[]{"Classic", "Blockswap", "BlockRace"}, new int[]{0, 1, 2}, 0),
    RANDOM_DROPS(Cat.TWISTS, "Random Drops", Material.CHORUS_FRUIT,
            new String[]{"Every block and mob drops a random item", "instead (same type = same item all game).",
                    "§cExceptions: Endermen, Blazes, Shulker Boxes"},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    RANDOM_OP(Cat.TWISTS, "Random OP Loot Twist", Material.NETHER_STAR,
            new String[]{"After a random 2-6 minutes, a random", "OP Loot twist turns on. Then another",
                    "one every 3-8 minutes after that."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    EAT_EFFECT(Cat.TWISTS, "Eating Gives a Random Effect", Material.APPLE,
            new String[]{"Every time a player eats, they get", "a random potion effect for 15 seconds.",
                    "Also adds food to the drops and lets", "you eat even when you're not hungry."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    DAMAGE_EFFECT(Cat.TWISTS, "Damage Gives a Random Effect", Material.FERMENTED_SPIDER_EYE,
            new String[]{"Every time a player takes damage, they", "get a random potion effect for 5 seconds."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    RANDOM_ENCHANTS(Cat.TWISTS, "Random Enchants", Material.ENCHANTED_BOOK,
            new String[]{"Tool, weapon and armor drops come", "with random enchants.",
                    "§fClassic§7: 1-2 normal enchants", "§fBoosted§7: 2-4 enchants at max level",
                    "§fChaos§7: anything goes, up to level 10"},
            new String[]{"Off", "Classic", "Boosted", "Chaos"}, new int[]{0, 1, 2, 3}, 0),
    SURPRISE(Cat.TWISTS, "Surprise Settings", Material.ENDER_EYE,
            new String[]{"Every game rolls random settings and", "twists when it starts. Time limit, hunger",
                    "and world loot stay how you set them."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    NO_BLAST_PROT(Cat.TWISTS, "No Blast Protection", Material.TNT,
            new String[]{"Random Enchants never give", "Blast Protection."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),

    // ---------- manhunt / hitman twists ----------
    OP_ENCHANTING(Cat.TWISTS, "Enchanting is OP", Material.ENCHANTING_TABLE,
            new String[]{"The enchanting table gives random", "OP enchants (above max level)."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    OP_SHEARING(Cat.TWISTS, "Shearing is OP", Material.SHEARS,
            new String[]{"Shearing drops a random chest loot table", "instead. Each mob variant gets its own",
                    "table, re-rolled every game."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    JUGGERNAUT(Cat.TWISTS, "Diamond Juggernaut", Material.DIAMOND_CHESTPLATE,
            new String[]{"Hunters get a diamond sword, diamond", "tools and full enchanted diamond armor."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    UHC(Cat.TWISTS, "UHCHunt", Material.GOLDEN_APPLE,
            new String[]{"No natural regen. Hunters drop their heads,", "which craft into Golden Heads (8 gold",
                    "around the head). Ores auto smelt."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    AGGRO_MOBS(Cat.TWISTS, "All Mobs Aggressive", Material.ZOMBIE_HEAD,
            new String[]{"Every mob that can attack goes after", "players (wolves, golems, endermen, bees...)."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    SNEAK_INVIS(Cat.TWISTS, "Sneaking Gives Invisibility", Material.GLASS,
            new String[]{"Runners turn invisible while sneaking", "(no potion particles). Armor still shows."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    INFECTION(Cat.TWISTS, "Infection Mode", Material.ROTTEN_FLESH,
            new String[]{"When a runner dies they respawn", "as a hunter."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    REVERSE_INFECTION(Cat.TWISTS, "Reverse Infection", Material.GOLDEN_CARROT,
            new String[]{"When a survivor kills a hitman, that", "hitman respawns as a survivor."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    SWAPHUNT(Cat.TWISTS, "SwapHunt", Material.ENDER_EYE,
            new String[]{"When a runner dies they swap roles", "instead of going out. Killed by a hunter:",
                    "swap with that hunter. Any other death:", "swap with the closest hunter.",
                    "Inventories and spawn points swap too.", "Runners can't drop items."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    ELYTRA_RACE(Cat.TWISTS, "Elytra Race", Material.ELYTRA,
            new String[]{"Everyone starts with an unbreakable", "elytra (\"Icarus\" mode)."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    ASSASSINS(Cat.TWISTS, "Assassins Mode", Material.WITHER_ROSE,
            new String[]{"Runners freeze hunters by looking right", "at them. Hunters unfreeze when they look",
                    "away. Runners die in one hit.", "Hunter advancements are hidden."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    TINY(Cat.TWISTS, "Tiny Survivors", Material.BROWN_MUSHROOM,
            new String[]{"Survivors are half size."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    FREEZE_TAG(Cat.TWISTS, "Freeze Tag", Material.BLUE_ICE,
            new String[]{"Hitmen freeze survivors with a punch", "instead of killing them. A free teammate",
                    "sneaking next to them for 3s thaws them.", "Hitmen win if every survivor is frozen",
                    "at once. Kill Timer: frozen survivors die", "after that long (pauses near a hitman)."},
            new String[]{"Off", "On (no kill timer)", "Kill Timer 1m", "Kill Timer 2m", "Kill Timer 3m"},
            new int[]{0, -1, 60, 120, 180}, 0),

    // ---------- twists for every mode ----------
    STRUCTURE_MANIA(Cat.TWISTS, "Structure Mania", Material.MOSSY_STONE_BRICKS,
            new String[]{"Every few minutes a random structure", "spawns on top of every player. Everyone",
                    "gets their own random structure!"},
            new String[]{"Off", "Every 2m", "Every 3m", "Every 5m"}, new int[]{0, 120, 180, 300}, 0),
    LUCKY_BLOCK(Cat.TWISTS, "Lucky Block", Material.SPONGE,
            new String[]{"Everyone gets a lucky block on a timer.", "Place it and break it for something good,",
                    "bad or weird. You can also craft them:", "8 gold ingots around a dropper."},
            new String[]{"Off", "Every 30s", "Every 1m", "Every 2m"}, new int[]{0, 30, 60, 120}, 0),
    RANDOM_CRAFTS(Cat.TWISTS, "Random Crafts", Material.CRAFTING_TABLE,
            new String[]{"Every crafting recipe makes a random item", "instead (same recipe = same item all game)."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0),
    SHARED_INV(Cat.TWISTS, "Shared Inventory", Material.BUNDLE,
            new String[]{"§6[BETA] §7Players on the same side share", "one inventory. Keep Inventory is on.",
                    "Compasses stay your own."},
            new String[]{"Off", "On"}, new int[]{0, 1}, 0);

    public enum Cat { MAP, SETTINGS, TWISTS }

    public final Cat cat;
    public final String display;
    public final Material icon;
    public final String[] desc;
    public final String[] labels;
    public final int[] values;
    public final int def;

    Setting(Cat cat, String display, Material icon, String[] desc, String[] labels, int[] values, int def) {
        this.cat = cat;
        this.display = display;
        this.icon = icon;
        this.desc = desc;
        this.labels = labels;
        this.values = values;
        this.def = def;
    }

    // ---------- which modes each setting belongs to ----------

    private static final java.util.Map<Setting, java.util.Set<Mode>> ONLY = new java.util.EnumMap<>(Setting.class);
    private static final java.util.Map<Mode, java.util.Map<Setting, Integer>> DEFAULTS = new java.util.EnumMap<>(Mode.class);

    private static void only(java.util.Set<Mode> modes, Setting... settings) {
        for (Setting s : settings) ONLY.put(s, modes);
    }

    private static void defaults(Mode m, Object... pairs) {
        java.util.Map<Setting, Integer> map = DEFAULTS.computeIfAbsent(m, k -> new java.util.EnumMap<>(Setting.class));
        for (int i = 0; i < pairs.length; i += 2) map.put((Setting) pairs[i], (Integer) pairs[i + 1]);
    }

    static {
        java.util.Set<Mode> ric = java.util.EnumSet.of(Mode.RIC);
        java.util.Set<Mode> ds = java.util.EnumSet.of(Mode.DEATHSWAP);
        java.util.Set<Mode> bs = java.util.EnumSet.of(Mode.BLOCKSHUFFLE);
        java.util.Set<Mode> dsBs = java.util.EnumSet.of(Mode.DEATHSWAP, Mode.BLOCKSHUFFLE);
        java.util.Set<Mode> mh = java.util.EnumSet.of(Mode.MANHUNT);
        java.util.Set<Mode> hm = java.util.EnumSet.of(Mode.HITMAN);
        java.util.Set<Mode> hunt = java.util.EnumSet.of(Mode.MANHUNT, Mode.HITMAN);
        java.util.Set<Mode> notMh = java.util.EnumSet.complementOf(java.util.EnumSet.of(Mode.MANHUNT));
        java.util.Set<Mode> notHunt = java.util.EnumSet.of(Mode.RIC, Mode.DEATHSWAP, Mode.BLOCKSHUFFLE);
        Mode RIC = Mode.RIC, DS = Mode.DEATHSWAP, BS = Mode.BLOCKSHUFFLE, MH = Mode.MANHUNT, HM = Mode.HITMAN,
                SR = Mode.SPEEDRUN, BG = Mode.BINGO, DSH = Mode.DEATHSHUFFLE;
        only(ric, DROP_FIRST, DROP_INTERVAL, DROP_SIZE, GROUND_SWEEP, DROP_COUNTDOWN, CRAFTING, WEAPON_CRAFTING,
                SHARE_ITEMS, MOB_LOOT, SPAWN_EGGS, WORLD_LOOT, RANDOM_ENCHANTS, NO_BLAST_PROT);
        // hunger shows as "No Hunger Loss" style in manhunt/hitman too
        only(java.util.EnumSet.of(RIC, MH, HM), HUNGER);
        only(ds, SWAP_DELAY, SWAP_TIMER, FIRE_RES, FALL_RES, BED_BOMBING);
        only(java.util.EnumSet.of(DS, MH, HM, SR), BED_REDUCE, NETHER_ENTRY_RES, RANDOM_OP);
        only(java.util.EnumSet.of(DS, BS, DSH), LIVES);
        only(java.util.EnumSet.of(DS, BS, SR, BG, DSH), PVP, KEEP_INVENTORY);
        only(java.util.EnumSet.of(DS, BS, MH, HM, SR, BG, DSH), VEIN_MINER, AUTO_SMELT, AUTO_COOK);
        only(java.util.EnumSet.of(BS, DSH), ROUND_TIME, ELIMINATION_MODE, SAME_BLOCK);
        only(bs, BS_FORMAT);
        only(java.util.EnumSet.of(DSH), MORE_DEATHS);
        only(java.util.EnumSet.of(BS, DSH), VOTE_SKIP);
        // death shuffle has no world border
        only(java.util.EnumSet.complementOf(java.util.EnumSet.of(DSH)), MAP_SIZE);
        only(java.util.EnumSet.of(BS, MH, HM, SR), RANDOM_DROPS);
        only(java.util.EnumSet.of(MH, SR), INCREASED_LUCK, NO_BRUTES, PROTECT_SPAWNERS);
        only(mh, HEAD_START);
        only(hm, HITMEN_FREEZE, TINY, FREEZE_TAG, REVERSE_INFECTION);
        only(hunt, GLOWING, HUNTER_RESPAWN, ADV_COMPASS, NETHER_FIRST, HUNTER_KEEP_INV, ANNOUNCE_TWISTS,
                OP_ENCHANTING, OP_SHEARING, JUGGERNAUT, UHC, AGGRO_MOBS, SNEAK_INVIS, INFECTION, SWAPHUNT, ASSASSINS, SHARED_INV);
        only(java.util.EnumSet.of(MH, HM, SR), ELYTRA_RACE);
        only(java.util.EnumSet.of(BG), BINGO_MODE, CARD_SIZE, WIN_CONDITION, BINGO_END, LOCKOUT_END, LINE_BONUS, BINGO_TIME,
                PREVIEW, RESPAWN_TIMER, SPAWN_MODE, BIOME_TASKS, BINGO_COMPASS, BINGO_ELYTRA, STARTER_KIT, OPPONENT_TASKS);
        // the nether and end are always open in manhunt and speedrun, bingo has its own timer
        only(java.util.EnumSet.of(RIC, DS, BS, HM, BG, DSH), NETHER);
        only(java.util.EnumSet.of(RIC, DS, BS, HM, SR, DSH), TIME_LIMIT);
        only(java.util.EnumSet.of(RIC, DS, BS, HM), SHRINKING_BORDER);
        only(java.util.EnumSet.of(RIC, DS, BS, HM, DSH), HEIGHT_LIMIT);
        only(java.util.EnumSet.of(RIC, DS, BS), BORDER_CLOSE);
        only(java.util.EnumSet.of(RIC, DS, BS, DSH), DIG_LIMIT, THROWN_ITEMS, DEATH_LOOT);

        // modes that aren't RIC play vanilla for the RIC-only stuff
        for (Mode m : new Mode[]{Mode.DEATHSWAP, Mode.BLOCKSHUFFLE, Mode.MANHUNT, Mode.HITMAN, Mode.SPEEDRUN, Mode.BINGO, Mode.DEATHSHUFFLE}) {
            defaults(m, HUNGER, 1, WORLD_LOOT, 1, MOB_LOOT, 1, CRAFTING, 1, WEAPON_CRAFTING, 1, GROUND_SWEEP, 0,
                    MAP_SIZE, 1, GRACE, 0, TIME_LIMIT, 2, BORDER_CLOSE, 5, THROWN_ITEMS, 2);
        }
        defaults(Mode.MANHUNT, MAP_SIZE, 4, NETHER, 1);
        defaults(Mode.HITMAN, MAP_SIZE, 0, NETHER, 0);
        defaults(Mode.SPEEDRUN, MAP_SIZE, 3, NETHER, 1, TIME_LIMIT, 5);
        defaults(Mode.BINGO, NETHER, 1);
        defaults(Mode.RIC, DAYLIGHT, 1);
    }

    public boolean in(Mode m) {
        java.util.Set<Mode> o = ONLY.get(this);
        return o == null || o.contains(m);
    }

    public int def(Mode m) {
        java.util.Map<Setting, Integer> map = DEFAULTS.get(m);
        if (map != null) {
            Integer o = map.get(this);
            if (o != null) return o;
        }
        return def;
    }

    /** Name with Survivor/Hitman wording in Hitman. */
    public String title(Mode m) {
        if (m == Mode.DEATHSHUFFLE && this == SAME_BLOCK) return "Same Way To Die";
        if (m == Mode.DEATHSHUFFLE && this == ROUND_TIME) return "Death Timer";
        return m.words(display);
    }

    public boolean isToggle() {
        return labels.length == 2 && labels[0].equals("Off") && labels[1].equals("On");
    }
}
