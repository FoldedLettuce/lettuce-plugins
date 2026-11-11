package xyz.foldedlettuce.lettucegames;

import org.bukkit.Material;

import java.util.Set;

/**
 * Ways to die. Death Shuffle hands these out each round, Bingo uses them for "die to X" tiles.
 * A death matches on the damage cause, plus the mob (or the mob that shot it) or the block that did it.
 */
public enum Death {
    // ---------- base pool
    FALL("Fall to your death", Material.FEATHER, false, "FALL", null),
    LAVA("Swim in lava", Material.LAVA_BUCKET, false, "LAVA", null),
    BURN("Burn to death", Material.FLINT_AND_STEEL, false, "FIRE,FIRE_TICK", null),
    DROWN("Drown", Material.WATER_BUCKET, false, "DROWNING", null),
    SUFFOCATE("Suffocate in a wall", Material.GRAVEL, false, "SUFFOCATION", null),
    CACTUS("Get pricked by a cactus", Material.CACTUS, false, "CONTACT", "CACTUS"),
    BERRY("Die in a sweet berry bush", Material.SWEET_BERRIES, false, "CONTACT", "SWEET_BERRY_BUSH"),
    MAGMA("Die on a magma block", Material.MAGMA_BLOCK, false, "HOT_FLOOR", null),
    CAMPFIRE("Burn on a campfire", Material.CAMPFIRE, false, "CAMPFIRE", null),
    ZOMBIE("Get killed by a zombie", Material.ROTTEN_FLESH, false, "ENTITY_ATTACK", "ZOMBIE,HUSK,ZOMBIE_VILLAGER"),
    SKELETON("Get shot by a skeleton", Material.BONE, false, "PROJECTILE", "SKELETON,STRAY,BOGGED"),
    CREEPER("Get blown up by a creeper", Material.GUNPOWDER, false, "ENTITY_EXPLOSION", "CREEPER"),
    SPIDER("Get killed by a spider", Material.SPIDER_EYE, false, "ENTITY_ATTACK", "SPIDER,CAVE_SPIDER"),
    TNT("Blow up with TNT", Material.TNT, false, "ENTITY_EXPLOSION,BLOCK_EXPLOSION", "TNT,TNT_MINECART"),
    ANVIL("Get squished by an anvil", Material.ANVIL, false, "FALLING_BLOCK", "ANVIL,CHIPPED_ANVIL,DAMAGED_ANVIL"),
    FREEZE("Freeze in powder snow", Material.POWDER_SNOW_BUCKET, false, "FREEZE", null),
    WITHER_ROSE("Wither away (wither rose works)", Material.WITHER_ROSE, false, "WITHER", null),
    LIGHTNING("Get struck by lightning", Material.LIGHTNING_ROD, false, "LIGHTNING", null),
    STARVE("Starve to death", Material.BOWL, false, "STARVATION", null),
    WOLF("Get killed by a wolf", Material.BONE, false, "ENTITY_ATTACK", "WOLF"),
    IRON_GOLEM("Get killed by an iron golem", Material.IRON_BLOCK, false, "ENTITY_ATTACK", "IRON_GOLEM"),
    DROWNED("Get killed by a drowned", Material.TRIDENT, false, "ENTITY_ATTACK,PROJECTILE", "DROWNED"),
    SLIME("Get killed by a slime", Material.SLIME_BALL, false, "ENTITY_ATTACK", "SLIME"),
    PLAYER("Get killed by another player", Material.IRON_SWORD, false, "ENTITY_ATTACK,ENTITY_SWEEP_ATTACK,PROJECTILE", "PLAYER"),

    // ---------- "Add More Deaths"
    BEE("Get stung to death by a bee", Material.HONEYCOMB, true, "ENTITY_ATTACK,POISON", "BEE"),
    PIGLIN("Get killed by a piglin", Material.GOLD_INGOT, true, "ENTITY_ATTACK,PROJECTILE", "PIGLIN,PIGLIN_BRUTE"),
    ZOMBIFIED_PIGLIN("Get killed by a zombified piglin", Material.GOLD_NUGGET, true, "ENTITY_ATTACK", "ZOMBIFIED_PIGLIN"),
    ENDERMAN("Get killed by an enderman", Material.ENDER_PEARL, true, "ENTITY_ATTACK", "ENDERMAN"),
    BLAZE("Get killed by a blaze", Material.BLAZE_ROD, true, "PROJECTILE,FIRE,FIRE_TICK,ENTITY_ATTACK", "BLAZE,SMALL_FIREBALL"),
    GHAST("Get killed by a ghast", Material.GHAST_TEAR, true, "PROJECTILE,ENTITY_EXPLOSION", "GHAST,FIREBALL"),
    WITCH("Get killed by a witch", Material.SPLASH_POTION, true, "MAGIC,POISON", "WITCH"),
    MAGMA_CUBE("Get killed by a magma cube", Material.MAGMA_CREAM, true, "ENTITY_ATTACK", "MAGMA_CUBE"),
    SILVERFISH("Get killed by a silverfish", Material.STONE_BRICKS, true, "ENTITY_ATTACK", "SILVERFISH"),
    LLAMA("Get spat to death by a llama", Material.LEAD, true, "PROJECTILE", "LLAMA,TRADER_LLAMA,LLAMA_SPIT"),
    GOAT("Get rammed to death by a goat", Material.GOAT_HORN, true, "ENTITY_ATTACK,FALL", "GOAT"),
    POLAR_BEAR("Get killed by a polar bear", Material.SNOW_BLOCK, true, "ENTITY_ATTACK", "POLAR_BEAR"),
    PHANTOM("Get killed by a phantom", Material.PHANTOM_MEMBRANE, true, "ENTITY_ATTACK", "PHANTOM"),
    WITHER_SKELETON("Get killed by a wither skeleton", Material.WITHER_SKELETON_SKULL, true, "ENTITY_ATTACK,WITHER", "WITHER_SKELETON"),
    GUARDIAN("Get killed by a guardian", Material.PRISMARINE_SHARD, true, "MAGIC,ENTITY_ATTACK,THORNS", "GUARDIAN,ELDER_GUARDIAN"),
    VINDICATOR("Get killed by a vindicator", Material.IRON_AXE, true, "ENTITY_ATTACK", "VINDICATOR"),
    PILLAGER("Get shot by a pillager", Material.CROSSBOW, true, "PROJECTILE", "PILLAGER"),
    FIREWORK("Blow up with a firework", Material.FIREWORK_ROCKET, true, "ENTITY_EXPLOSION", "FIREWORK_ROCKET"),
    BED("Blow up from a bed or respawn anchor", Material.RED_BED, true, "BLOCK_EXPLOSION", "BED,RESPAWN_ANCHOR"),
    KINETIC("Fly into a wall with an elytra", Material.ELYTRA, true, "FLY_INTO_WALL", null),
    STALACTITE("Get hit by a falling stalactite", Material.POINTED_DRIPSTONE, true, "FALLING_BLOCK,STALAGMITE", "POINTED_DRIPSTONE");

    public final String display;
    public final Material icon;
    /** only in the pool with "Add More Deaths" on */
    public final boolean extra;
    private final Set<String> causes;
    private final Set<String> by;

    Death(String display, Material icon, boolean extra, String causes, String by) {
        this.display = display;
        this.icon = icon;
        this.extra = extra;
        this.causes = Set.of(causes.split(","));
        this.by = by == null ? null : Set.of(by.split(","));
    }

    /**
     * @param cause  damage cause name
     * @param source mob type that did it (or shot it), the projectile/entity itself, or the block type, uppercase. Can be null.
     * @param entity the damaging entity type (projectile, tnt...), can be null
     */
    public boolean matches(String cause, String source, String entity) {
        // campfires sometimes come through as plain contact damage
        if (this == CAMPFIRE) return cause.equals("CAMPFIRE") || (cause.equals("CONTACT") && source != null && source.contains("CAMPFIRE"));
        if (this == CACTUS && source != null && source.contains("CAMPFIRE")) return false;
        if (!causes.contains(cause)) return false;
        if (by == null) return true;
        return (source != null && by.contains(source)) || (entity != null && by.contains(entity));
    }

    /** Every death that matches, so one death can tick several tiles. */
    public static java.util.List<Death> all(String cause, String source, String entity) {
        java.util.List<Death> out = new java.util.ArrayList<>();
        for (Death d : values()) if (d.matches(cause, source, entity)) out.add(d);
        return out;
    }
}
