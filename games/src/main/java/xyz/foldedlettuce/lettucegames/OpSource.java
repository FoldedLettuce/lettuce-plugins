package xyz.foldedlettuce.lettucegames;

import org.bukkit.Material;

import java.util.Set;

/** "OP Loot" twists (Death Swap, Manhunt, Hitman): things that drop OP loot instead of their normal drops. */
public enum OpSource {
    // blocks
    COAL_ORE("Coal Ore", Material.COAL_ORE, Kind.BLOCK, "Coal Ore drops OP Loot when broken", "COAL_ORE", "DEEPSLATE_COAL_ORE"),
    REDSTONE_ORE("Redstone Ore", Material.REDSTONE_ORE, Kind.BLOCK, "Redstone Ore drops OP Loot when broken", "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE"),
    DIAMOND_ORE("Diamond Ore", Material.DIAMOND_ORE, Kind.BLOCK, "Diamond Ore drops OP Loot when broken", "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE"),
    GLASS("Glass", Material.GLASS, Kind.BLOCK, "Any glass or glass pane drops OP Loot when broken"),
    BRICKS("Bricks", Material.BRICKS, Kind.BLOCK, "Bricks drop OP Loot when broken", "BRICKS", "BRICK_STAIRS", "BRICK_SLAB", "BRICK_WALL"),
    FLOWERS("Flowers", Material.POPPY, Kind.BLOCK, "Any flower drops OP Loot when broken"),
    GLOW_LICHEN("Glow Lichen", Material.GLOW_LICHEN, Kind.BLOCK, "Glow Lichen drops OP Loot when broken", "GLOW_LICHEN"),
    PUMPKINS("Pumpkins", Material.PUMPKIN, Kind.BLOCK, "Pumpkins drop OP Loot when broken", "PUMPKIN", "CARVED_PUMPKIN", "JACK_O_LANTERN"),
    WOOL("Wool", Material.WHITE_WOOL, Kind.BLOCK, "Any color wool drops OP Loot when broken"),
    // mobs
    PIGS("Pigs", Material.PORKCHOP, Kind.MOB, "Pigs drop OP Loot when killed", "PIG"),
    COWS("Cows", Material.BEEF, Kind.MOB, "Cows drop OP Loot when killed", "COW", "MOOSHROOM"),
    CHICKENS("Chickens", Material.FEATHER, Kind.MOB, "Chickens drop OP Loot when killed", "CHICKEN"),
    SHEEP("Sheep", Material.MUTTON, Kind.MOB, "Sheep drop OP Loot when killed", "SHEEP"),
    RABBITS("Rabbits", Material.RABBIT_HIDE, Kind.MOB, "Rabbits drop OP Loot when killed", "RABBIT"),
    HORSES("Horses", Material.SADDLE, Kind.MOB, "Horses drop OP Loot when killed", "HORSE", "DONKEY", "MULE"),
    LLAMAS("Llamas", Material.LEAD, Kind.MOB, "Llamas drop OP Loot when killed", "LLAMA", "TRADER_LLAMA"),
    ARMADILLOS("Armadillos", Material.ARMADILLO_SCUTE, Kind.MOB, "Armadillos drop OP Loot when killed", "ARMADILLO"),
    BATS("Bats", Material.BLACK_DYE, Kind.MOB, "Bats drop OP Loot when killed", "BAT"),
    VILLAGERS("Villagers", Material.EMERALD, Kind.MOB, "Villagers drop OP Loot when killed", "VILLAGER"),
    FISH("Fish", Material.COD, Kind.MOB, "Cod, Salmon, Pufferfish, Tropical Fish and Axolotl drop OP Loot when killed",
            "COD", "SALMON", "PUFFERFISH", "TROPICAL_FISH", "AXOLOTL"),
    ZOMBIES("Zombies", Material.ROTTEN_FLESH, Kind.MOB, "Zombies drop OP Loot when killed", "ZOMBIE", "HUSK", "DROWNED", "ZOMBIE_VILLAGER"),
    SKELETONS("Skeletons", Material.BONE, Kind.MOB, "Skeletons drop OP Loot when killed", "SKELETON", "STRAY", "BOGGED"),
    CREEPERS("Creepers", Material.GUNPOWDER, Kind.MOB, "Creepers drop OP Loot when killed", "CREEPER"),
    SPIDERS("Spiders", Material.SPIDER_EYE, Kind.MOB, "Spiders drop OP Loot when killed", "SPIDER", "CAVE_SPIDER"),
    ENDERMEN("Endermen", Material.ENDER_PEARL, Kind.MOB, "Endermen drop OP Loot when killed", "ENDERMAN"),
    MAGMA_CUBES("Magma Cubes", Material.MAGMA_CREAM, Kind.MOB, "Magma Cubes drop OP Loot when killed", "MAGMA_CUBE"),
    ZOMBIFIED_PIGLINS("Zombified Piglins", Material.GOLD_NUGGET, Kind.MOB, "Zombified Piglins drop OP Loot when killed", "ZOMBIFIED_PIGLIN"),
    WITHER_SKELETONS("Wither Skeletons", Material.WITHER_SKELETON_SKULL, Kind.MOB, "Wither Skeletons drop OP Loot when killed", "WITHER_SKELETON"),
    // other
    ADVANCEMENTS("Advancements", Material.KNOWLEDGE_BOOK, Kind.OTHER, "Getting an advancement drops OP Loot"),
    EATING("Eating", Material.COOKED_BEEF, Kind.OTHER, "Finishing eating any item drops OP Loot at your location"),
    FISHING("Fishing", Material.FISHING_ROD, Kind.OTHER, "Catching a fish or item with a fishing rod drops OP Loot instead"),
    // manhunt / hitman
    HUNTER_DEATHS("Hunters", Material.CROSSBOW, Kind.OTHER, "Hunters drop OP Loot when they die"),
    RUNNER_DEATHS("Runners", Material.LEATHER_BOOTS, Kind.OTHER, "Runners drop OP Loot when they die");

    public enum Kind { BLOCK, MOB, OTHER }

    public final String display;
    public final Material icon;
    public final Kind kind;
    public final String desc;
    private final Set<String> names;

    OpSource(String display, Material icon, Kind kind, String desc, String... names) {
        this.display = display;
        this.icon = icon;
        this.kind = kind;
        this.desc = desc;
        this.names = Set.of(names);
    }

    /** Which modes show this source. Hunter/Runner deaths only make sense in Manhunt and Hitman. */
    public boolean in(Mode m) {
        if (this == HUNTER_DEATHS || this == RUNNER_DEATHS) return m.isHunt();
        return m.hasOpLoot();
    }

    /** Does this block type count for this source? */
    public boolean matchesBlock(Material m) {
        if (kind != Kind.BLOCK) return false;
        String n = m.name();
        return switch (this) {
            case GLASS -> n.equals("GLASS") || n.equals("GLASS_PANE") || n.endsWith("_GLASS") || n.endsWith("_GLASS_PANE");
            case WOOL -> n.endsWith("_WOOL");
            case FLOWERS -> isFlower(m);
            default -> names.contains(n);
        };
    }

    public boolean matchesEntity(String entityType) {
        return kind == Kind.MOB && names.contains(entityType);
    }

    private static boolean isFlower(Material m) {
        try {
            if (org.bukkit.Tag.SMALL_FLOWERS.isTagged(m)) return true;
        } catch (Throwable ignored) {
        }
        String n = m.name();
        return n.equals("SUNFLOWER") || n.equals("LILAC") || n.equals("ROSE_BUSH") || n.equals("PEONY") || n.equals("PITCHER_PLANT");
    }
}
