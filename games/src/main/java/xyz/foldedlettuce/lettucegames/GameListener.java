package xyz.foldedlettuce.lettucegames;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PiglinBarterEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.entity.Item;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GameListener implements Listener {
    private final LettuceGames plugin;
    /** players who actually died in a game (fallback path) and need to come back as spectators */
    private final Map<UUID, Match> pendingSpectate = new HashMap<>();

    public GameListener(LettuceGames plugin) {
        this.plugin = plugin;
    }

    private boolean isLobby(World w) {
        // ready maps sitting in the pool aren't the lobby, let them generate animals normally
        return plugin.matchFor(w) == null && !w.getName().startsWith(plugin.worldPrefix());
    }

    private boolean canBuild(Player p) {
        return p.getGameMode() == GameMode.CREATIVE && p.hasPermission("lettucegames.build");
    }

    // ------------------------------------------------------------------ join / quit

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.parties().onJoin(p);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) plugin.sendToLobby(p);
        }, 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Party party = plugin.parties().get(p);
        if (party != null) {
            plugin.parties().leave(p, true);
        } else {
            Match m = plugin.matchFor(p.getWorld());
            if (m != null) m.removePlayer(p, true);
        }
        pendingSpectate.remove(p.getUniqueId());
    }

    // ------------------------------------------------------------------ hotbar items

    private final Map<UUID, Long> clickCooldown = new HashMap<>();

    private boolean onCooldown(Player p, long ms) {
        long now = System.currentTimeMillis();
        Long last = clickCooldown.get(p.getUniqueId());
        if (last != null && now - last < ms) return true;
        clickCooldown.put(p.getUniqueId(), now);
        return false;
    }

    private final Map<UUID, Long> fireChargeCd = new HashMap<>();

    /** Right-clicking a fire charge during a game throws it as a small fireball instead of lighting the block. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFireChargeThrow(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || it.getType() != Material.FIRE_CHARGE) return;
        Player p = e.getPlayer();
        Match m = plugin.matchFor(p.getWorld());
        if (m == null || !m.alive.contains(p.getUniqueId()) || m.ghosts.contains(p.getUniqueId())) return;
        e.setCancelled(true);
        if (m.isFrozen(p.getUniqueId())) return;
        long now = System.currentTimeMillis();
        Long last = fireChargeCd.get(p.getUniqueId());
        if (last != null && now - last < 400) return;
        fireChargeCd.put(p.getUniqueId(), now);
        org.bukkit.util.Vector dir = p.getEyeLocation().getDirection();
        org.bukkit.entity.SmallFireball fb = p.launchProjectile(org.bukkit.entity.SmallFireball.class, dir.clone().multiply(1.5));
        fb.setDirection(dir);
        fb.setIsIncendiary(true);
        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ENTITY_BLAZE_SHOOT, 0.8f, 1f);
        if (p.getGameMode() != GameMode.CREATIVE) it.setAmount(it.getAmount() - 1);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Action a = e.getAction();
        ItemStack it = e.getItem();
        Match m = plugin.matchFor(p.getWorld());
        boolean right = a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK;
        boolean left = a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK;
        boolean mainHand = e.getHand() == EquipmentSlot.HAND;

        if (m != null && m.ghosts.contains(p.getUniqueId())) {
            // walk/fly spectators can't touch the world, only use their three items
            e.setCancelled(true);
            if (!right || !mainHand || it == null || !it.hasItemMeta() || onCooldown(p, 300)) return;
            String n = it.getItemMeta().getDisplayName();
            if (Match.GHOST_TP.equals(n)) plugin.spectate(p);
            else if (Match.GHOST_NOCLIP.equals(n)) m.toggleSpecMode(p);
            else if (Match.GHOST_LEAVE.equals(n)) plugin.leaveGame(p);
            return;
        }
        if (m != null && Bingo.isCard(it) && (right || left)) {
            e.setCancelled(true);
            if (mainHand && !onCooldown(p, 300)) plugin.menus().openBingo(p);
            return;
        }
        if (m != null && Match.isReusableRocket(it) && right) {
            e.setCancelled(true);
            if (!p.isGliding()) {
                p.sendActionBar("§7Only works while gliding.");
                return;
            }
            if (p.hasCooldown(Material.FIREWORK_ROCKET)) return;
            p.setVelocity(p.getLocation().getDirection().multiply(1.6).add(p.getVelocity().multiply(0.4)));
            p.setCooldown(Material.FIREWORK_ROCKET, 100);
            p.playSound(p.getLocation(), "minecraft:entity.firework_rocket.launch", 1f, 1f);
            return;
        }
        if (m != null) {
            if (!Match.isTracker(it)) return;
            e.setCancelled(true);
            if (!mainHand || !m.alive.contains(p.getUniqueId()) || onCooldown(p, 150)) return;
            if (m.mode.isHunt() && !m.s.on(Setting.ADV_COMPASS)) {
                p.sendActionBar(m.mode.words("§7Points at the nearest runner §8(Advanced Compass is off)"));
                return;
            }
            if (right) plugin.menus().open(p, Menus.Type.TRACKER, m.party, null);
            else if (left) m.cycleTrack(p);
            return;
        }

        // waiting area
        if (!canBuild(p) && a != Action.PHYSICAL) e.setCancelled(true);
        if (!right || !mainHand || it == null) return;
        String tag = LettuceGames.lobbyTag(it);
        if (tag == null) return;
        e.setCancelled(true);
        if (onCooldown(p, 400)) return;
        switch (tag) {
            case "party" -> {
                if (plugin.parties().enabled()) plugin.menus().openParty(p);
                else plugin.menus().openGame(p, Menus.Tab.SETTINGS);
            }
            case "ready" -> plugin.parties().toggleReady(p);
            case "settings" -> plugin.menus().openGame(p, Menus.Tab.SETTINGS);
            case "spectate" -> plugin.spectate(p);
            case "leaderboard" -> plugin.menus().openLeaderboard(p, Stats.Board.WINS, "all");
            default -> {
            }
        }
    }

    @EventHandler
    public void onLobbyInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getInventory().getHolder() instanceof Menus.Menu) return;
        if (isLobby(p.getWorld()) && !canBuild(p)) e.setCancelled(true);
        if (isGhost(p)) e.setCancelled(true);
    }

    private boolean isGhost(Entity ent) {
        if (!(ent instanceof Player p)) return false;
        Match m = plugin.matchFor(p.getWorld());
        return m != null && m.ghosts.contains(p.getUniqueId());
    }

    private boolean isOut(Player p) {
        Match m = plugin.matchFor(p.getWorld());
        return m != null && !m.alive.contains(p.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onGhostPickup(EntityPickupItemEvent e) {
        if (isGhost(e.getEntity())) {
            e.setCancelled(true);
            return;
        }
        if (!(e.getEntity() instanceof Player picker)) return;
        Match m = plugin.matchFor(picker.getWorld());
        if (m == null) return;
        Item item = e.getItem();
        UUID thrower = item.getThrower();
        if (thrower == null || thrower.equals(picker.getUniqueId())) return;
        // overflow from drops follows Drop Sharing, anything thrown with Q follows Thrown Items
        int rule = m.dropItems.contains(item.getUniqueId()) ? m.s.get(Setting.SHARE_ITEMS)
                : m.deathItems.contains(item.getUniqueId()) ? m.s.get(Setting.DEATH_LOOT)
                : m.s.get(Setting.THROWN_ITEMS);
        if (rule == 2) return;
        if (rule == 1 && m.sameTeam(thrower, picker.getUniqueId())) return;
        e.setCancelled(true);
    }

    /** Arrows and other projectiles fly straight through ghosts. */
    @EventHandler(ignoreCancelled = true)
    public void onGhostProjectile(org.bukkit.event.entity.ProjectileHitEvent e) {
        if (isGhost(e.getHitEntity())) e.setCancelled(true);
    }

    /** Ghosts don't set off sculk sensors, shriekers and the like. */
    @EventHandler(ignoreCancelled = true)
    public void onGhostVibration(org.bukkit.event.block.BlockReceiveGameEvent e) {
        if (isGhost(e.getEntity())) e.setCancelled(true);
    }

    /** Switching hotbar slots resends the held item, so hide it again right after. */
    @EventHandler
    public void onGhostSlot(org.bukkit.event.player.PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        Match m = plugin.matchFor(p.getWorld());
        if (m == null || !m.ghosts.contains(p.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> m.hideGhostGear(p));
    }

    @EventHandler(ignoreCancelled = true)
    public void onGhostTargeted(EntityTargetEvent e) {
        if (isGhost(e.getTarget())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onGhostInteractEntity(PlayerInteractEntityEvent e) {
        if (isGhost(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGhostHit(EntityDamageByEntityEvent e) {
        if (isGhost(e.getDamager())) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (isLobby(p.getWorld())) {
            if (!canBuild(p)) e.setCancelled(true);
            return;
        }
        ItemStack dropped = e.getItemDrop().getItemStack();
        if (Match.isTracker(dropped) || Bingo.isCard(dropped) || Match.isReusableRocket(dropped) || isGhost(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (isLobby(e.getPlayer().getWorld()) && !canBuild(e.getPlayer())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ lobby protection

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = plugin.matchFor(p.getWorld());
        if (m == null) {
            e.setCancelled(true);
            return;
        }
        if (!m.alive.contains(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (!m.s.on(Setting.HUNGER)) {
            // hunger off: bar stays put, no saturation so regen stays at the normal slow speed
            e.setCancelled(true);
            p.setFoodLevel(m.lockedFood());
            p.setSaturation(0f);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Match m = plugin.matchFor(p.getWorld());
        if (m == null) {
            if (!canBuild(p)) e.setCancelled(true);
            return;
        }
        if (isOut(p)) {
            e.setCancelled(true);
            return;
        }
        int dig = m.s.get(Setting.DIG_LIMIT);
        if (dig != Integer.MIN_VALUE && e.getBlock().getY() < dig) {
            e.setCancelled(true);
            p.sendActionBar("§cYou can't dig below Y " + dig + "!");
            return;
        }
        boolean ownBlock = m.placed.contains(Match.blockKey(e.getBlock()));
        if (!m.s.on(Setting.WORLD_LOOT)) {
            if (ownBlock) {
                allowAt(e.getBlock().getLocation().add(0.5, 0.5, 0.5));
            } else {
                e.setDropItems(false);
                // structure chests keep their loot: let whatever's inside spill out when broken
                if (isContainer(e.getBlock().getType())) allowAt(e.getBlock().getLocation().add(0.5, 0.5, 0.5));
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Match m = plugin.matchFor(p.getWorld());
        if (m == null) {
            if (!canBuild(p)) e.setCancelled(true);
            return;
        }
        if (isOut(p)) {
            e.setCancelled(true);
            return;
        }
        int above = m.s.get(Setting.HEIGHT_LIMIT);
        if (above != Integer.MAX_VALUE && e.getBlock().getY() > naturalTop(m, e.getBlock()) + above) {
            e.setCancelled(true);
            p.sendActionBar("§cYou can only build " + above + " blocks above the ground here!");
            return;
        }
        m.placed.add(Match.blockKey(e.getBlock()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(org.bukkit.event.player.PlayerBucketEmptyEvent e) {
        Player p = e.getPlayer();
        Match m = plugin.matchFor(p.getWorld());
        if (m == null) return;
        int above = m.s.get(Setting.HEIGHT_LIMIT);
        if (above != Integer.MAX_VALUE && e.getBlock().getY() > naturalTop(m, e.getBlock()) + above) {
            e.setCancelled(true);
            p.sendActionBar("§cYou can only build " + above + " blocks above the ground here!");
        }
    }

    /**
     * Highest natural block Y in this block's column (trees, hills and overhangs count). Blocks players placed
     * are skipped, and the result is cached per column so later building can't raise the ceiling.
     */
    private int naturalTop(Match m, org.bukkit.block.Block at) {
        World w = at.getWorld();
        int x = at.getX(), z = at.getZ();
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        Integer cached = m.naturalTop.get(key);
        if (cached != null) return cached;
        int min = w.getMinHeight();
        int y = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.WORLD_SURFACE);
        while (y > min) {
            org.bukkit.block.Block b = w.getBlockAt(x, y, z);
            boolean ours = b.equals(at) || m.placed.contains(Match.blockKey(b));
            if (!ours && !b.getType().isAir()) break;
            y--;
        }
        m.naturalTop.put(key, y);
        return y;
    }

    // ------------------------------------------------------------------ world loot off

    /** Short-lived spots where items are allowed to appear (own blocks broken, mob loot, vehicles). */
    private static class Zone {
        final World w;
        final double x, y, z;
        final long until;

        Zone(Location l, long ms) {
            w = l.getWorld();
            x = l.getX();
            y = l.getY();
            z = l.getZ();
            until = System.currentTimeMillis() + ms;
        }
    }

    private final java.util.List<Zone> zones = new java.util.ArrayList<>();

    private void allowAt(Location l) {
        zones.add(new Zone(l, 400));
    }

    private boolean inZone(Location l) {
        long now = System.currentTimeMillis();
        zones.removeIf(z -> z.until < now);
        for (Zone z : zones) {
            if (!z.w.equals(l.getWorld())) continue;
            double dx = z.x - l.getX(), dy = z.y - l.getY(), dz = z.z - l.getZ();
            if (dx * dx + dy * dy + dz * dz < 9) return true;
        }
        return false;
    }

    /** Catch-all: with World Loot off, items only appear from drops, death loot, players throwing them, or blocks they placed. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        Location l = e.getLocation();
        if (!noLoot(l.getWorld())) return;
        if (plugin.allowItemSpawn) return;
        Match m = plugin.matchFor(l.getWorld());
        Item it = e.getEntity();
        if (it.getThrower() != null) return;
        if (m.placed.contains(Match.blockKey(l.getBlock()))) return;
        if (inZone(l)) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleBreak(VehicleDestroyEvent e) {
        if (noLoot(e.getVehicle().getWorld())) allowAt(e.getVehicle().getLocation());
    }

    private static boolean isContainer(Material t) {
        String n = t.name();
        return n.contains("CHEST") || n.contains("BARREL") || n.contains("SHULKER_BOX") || n.contains("HOPPER")
                || n.equals("DISPENSER") || n.equals("DROPPER") || n.contains("FURNACE") || n.equals("SMOKER")
                || n.equals("BREWING_STAND") || n.equals("CRAFTER") || n.contains("DECORATED_POT") || n.equals("CHISELED_BOOKSHELF");
    }

    private boolean noLoot(World w) {
        Match m = plugin.matchFor(w);
        return m != null && !m.s.on(Setting.WORLD_LOOT);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) {
        if (noLoot(e.getBlock().getWorld())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (noLoot(e.getEntity().getWorld())) e.setYield(0f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (noLoot(e.getBlock().getWorld())) e.setYield(0f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (!noLoot(e.getPlayer().getWorld())) return;
        if (e.getState().name().equals("CAUGHT_FISH") && e.getCaught() instanceof Item caught) {
            caught.remove();
            e.setExpToDrop(0);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent e) {
        if (noLoot(e.getPlayer().getWorld())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBarter(PiglinBarterEvent e) {
        if (noLoot(e.getEntity().getWorld())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent e) {
        if (noLoot(e.getPlayer().getWorld())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDrop(EntityDropItemEvent e) {
        if (e.getEntity() instanceof Player) return;
        if (noLoot(e.getEntity().getWorld())) e.setCancelled(true);
    }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent e) {
        if (isLobby(e.getLocation().getWorld()) && !e.getSpawnReason().name().equals("CUSTOM")
                && !e.getSpawnReason().name().equals("COMMAND") && !e.getSpawnReason().name().equals("SPAWNER_EGG")) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ combat

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof org.bukkit.entity.AreaEffectCloud cloud && cloud.getSource() instanceof Player p) return p;
        if (damager instanceof org.bukkit.entity.TNTPrimed tnt && tnt.getSource() instanceof Player p) return p;
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) return p;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Match m = plugin.matchFor(victim.getWorld());
        if (m == null) {
            e.setCancelled(true);
            return;
        }
        Player att = attacker(e.getDamager());
        if (att == null || att.equals(victim)) return;
        if (!m.alive.contains(att.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (Setting.PVP.in(m.mode) && !m.s.on(Setting.PVP)) {
            e.setCancelled(true);
            att.sendActionBar(m.mode == Mode.DEATHSWAP ? "§cPvP is off this game. Use traps!" : "§cPvP is off this game.");
            return;
        }
        if (m.isGrace()) {
            e.setCancelled(true);
            att.sendActionBar("§eGrace period! §7PvP is on in " + Msg.time(m.s.get(Setting.GRACE) - m.elapsed));
            return;
        }
        if (m.sameTeam(att.getUniqueId(), victim.getUniqueId())) {
            int ff = m.s.get(Setting.FRIENDLY_FIRE);
            if (ff == 0) {
                e.setCancelled(true);
                return;
            }
            if (ff == 2) {
                // boosting: bare-handed punches knock back with no damage, weapons do nothing
                ItemStack hand = att.getInventory().getItemInMainHand();
                if (e.getDamager() instanceof Player && (hand == null || hand.getType() == Material.AIR)) {
                    e.setDamage(0.0);
                } else {
                    e.setCancelled(true);
                }
                return;
            }
        }
        m.lastDamager.put(victim.getUniqueId(), att.getUniqueId());
        m.lastDamageAt.put(victim.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = plugin.matchFor(p.getWorld());
        if (m == null) {
            e.setCancelled(true);
            if (e.getCause().name().equals("VOID")) plugin.sendToLobby(p);
            return;
        }
        if (m.ended || !m.alive.contains(p.getUniqueId())) {
            e.setCancelled(true);
            if (e.getCause().name().equals("VOID") && m.world != null) p.teleport(m.world.getSpawnLocation());
            return;
        }
        if (e.getFinalDamage() < p.getHealth()) return;
        PlayerInventory inv = p.getInventory();
        if (inv.getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || inv.getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) return;

        // this hit would kill them: skip the death screen and go straight to spectator
        e.setCancelled(true);
        String[] dsrc = sourceOf(e);
        String msg = m.deathMessage(p, e.getCause().name(), dsrc[0], dsrc[1]);
        boolean voidDeath = p.getLocation().getY() < p.getWorld().getMinHeight();
        String cause = e.getCause().name();
        String[] src = sourceOf(e);
        if (m.mode == Mode.DEATHSHUFFLE) {
            m.dshDeath(p, msg, cause, src[0], src[1]);
            return;
        }
        if (m.mode == Mode.BINGO) {
            if (m.bingo != null) {
                for (Death d : Death.all(cause, src[0], src[1])) m.bingo.progress(p, Bingo.Type.DIE, d.name());
                UUID killer = m.recentKiller(p.getUniqueId());
                Player kp = killer == null ? null : Bukkit.getPlayer(killer);
                if (kp != null && !m.sideKey(killer).equals(m.sideKey(p.getUniqueId()))) m.bingo.progress(kp, Bingo.Type.OPP, "KILL");
            }
            if (m.ended) return;
            m.down(p, "§c☠ " + msg, m.s.on(Setting.KEEP_INVENTORY));
            return;
        }
        if (m.mode == Mode.SPEEDRUN) {
            m.down(p, "§c☠ " + msg, m.s.on(Setting.KEEP_INVENTORY));
            return;
        }
        if (m.mode.isHunt()) {
            m.huntDeath(p, msg);
            if (voidDeath && !m.alive.contains(p.getUniqueId())) p.teleport(m.world.getSpawnLocation().add(0, 10, 0));
            return;
        }
        if (m.mode == Mode.DEATHSWAP && m.useLife(p, msg)) return;
        if (m.mode == Mode.BLOCKSHUFFLE) {
            // dying doesn't knock you out of block shuffle, only missing your block does
            m.respawn(p, msg, "§7Back to spawn");
            return;
        }
        m.eliminate(p, msg, true);
        if (voidDeath) p.teleport(m.world.getSpawnLocation().add(0, 10, 0));
    }

    /** {who did it (mob, shooter or block), the damaging entity itself} as upper-case type names. */
    static String[] sourceOf(EntityDamageEvent e) {
        String source = null, entity = null;
        if (e instanceof EntityDamageByEntityEvent be) {
            Entity d = be.getDamager();
            entity = d.getType().name();
            if (d instanceof Projectile pr && pr.getShooter() instanceof Entity sh) source = sh.getType().name();
            else if (d instanceof org.bukkit.entity.FallingBlock fb) source = fb.getBlockData().getMaterial().name();
            else source = entity;
        } else if (e instanceof org.bukkit.event.entity.EntityDamageByBlockEvent bb) {
            Material t = null;
            if (bb.getDamagerBlockState() != null) t = bb.getDamagerBlockState().getType();
            if ((t == null || t.isAir()) && bb.getDamager() != null) t = bb.getDamager().getType();
            if (t != null) source = t.name().endsWith("_BED") ? "BED" : t.name();
        }
        return new String[]{source, entity};
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageEffect(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = plugin.matchFor(p.getWorld());
        if (m == null || !m.alive.contains(p.getUniqueId()) || !m.s.on(Setting.DAMAGE_EFFECT)) return;
        long now = System.currentTimeMillis();
        Long last = m.effectCooldown.get(p.getUniqueId());
        if (last != null && now - last < 1000) return;
        m.effectCooldown.put(p.getUniqueId(), now);
        Bukkit.getScheduler().runTask(plugin, () -> giveRandomEffect(p, 5));
    }

    @EventHandler
    public void onEat(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        Match m = plugin.matchFor(p.getWorld());
        if (m == null || !m.alive.contains(p.getUniqueId()) || !m.s.on(Setting.EAT_EFFECT)) return;
        if (!e.getItem().getType().isEdible()) return;
        Bukkit.getScheduler().runTask(plugin, () -> giveRandomEffect(p, 15));
    }

    private void giveRandomEffect(Player p, int seconds) {
        if (!p.isOnline()) return;
        PotionEffectType t = plugin.pool().randomEffect();
        if (t == null) return;
        p.addPotionEffect(new PotionEffect(t, seconds * 20, 0));
        p.sendActionBar("§dRandom effect: §f" + Msg.pretty(t.getKey().getKey().toUpperCase()) + " §7(" + seconds + "s)");
    }

    // ------------------------------------------------------------------ fallback real deaths

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Match m = plugin.matchFor(p.getWorld());
        if (m == null) return;
        allowAt(p.getLocation());
        if (m.alive.contains(p.getUniqueId())) {
            // something bypassed the damage event (like /kill). Items drop normally.
            String msg = m.deathMessage(p, "CUSTOM");
            e.setDeathMessage(null);
            m.alive.remove(p.getUniqueId());
            m.spectators.add(p.getUniqueId());
            m.broadcast("§c✖ " + msg);
        } else {
            e.setDeathMessage(null);
        }
        pendingSpectate.put(p.getUniqueId(), m);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Match m = pendingSpectate.remove(p.getUniqueId());
        if (m != null && !m.ended && m.world != null) {
            e.setRespawnLocation(m.world.getSpawnLocation());
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                p.setGameMode(GameMode.SPECTATOR);
                p.setScoreboard(m.spectatorBoard());
            }, 1L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                // re-check the win now that they're out
                if (!m.ended && m.alive.isEmpty()) m.end(null, null);
            }, 2L);
        } else {
            e.setRespawnLocation(plugin.lobby());
            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.sendToLobby(p), 1L);
        }
    }

    // ------------------------------------------------------------------ game rules

    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        if (e.getViewers().isEmpty()) return;
        Match m = plugin.matchFor(e.getViewers().get(0).getWorld());
        if (m == null) return;
        ItemStack result = e.getInventory().getResult();
        if (result == null) return;
        if (!m.s.on(Setting.CRAFTING)) {
            e.getInventory().setResult(null);
        } else if (!m.s.on(Setting.WEAPON_CRAFTING) && ItemPool.isToolOrGear(result.getType())) {
            e.getInventory().setResult(null);
        }
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player) return;
        Match m = plugin.matchFor(e.getEntity().getWorld());
        if (m == null) return;
        if (m.s.on(Setting.MOB_LOOT)) {
            allowAt(e.getEntity().getLocation());
            return;
        }
        e.getDrops().clear();
        e.setDroppedExp(0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        Player p = e.getPlayer();
        Match m = plugin.matchFor(p.getWorld());
        if (m == null) {
            e.setCancelled(true);
            return;
        }
        Location from = e.getFrom();
        boolean endPortal = e.getCause().name().equals("END_PORTAL");
        if (endPortal && m.mode.hasEnd() && m.alive.contains(p.getUniqueId())) {
            Location to;
            if (from.getWorld().equals(m.end)) {
                // exit portal: home to your spawn point or the middle of the map
                Location bed = null;
                try {
                    bed = p.getRespawnLocation();
                } catch (Throwable ignored) {
                }
                to = bed != null && m.owns(bed.getWorld()) ? bed : m.world.getSpawnLocation().add(0.5, 0.5, 0.5);
            } else {
                to = m.endPlatform();
                if (to == null) {
                    e.setCancelled(true);
                    return;
                }
            }
            m.notePortal(p, from, to);
            e.setTo(to);
            return;
        }
        if (!e.getCause().name().equals("NETHER_PORTAL")) {
            e.setCancelled(true);
            p.sendActionBar("§cThe End is disabled in this game.");
            return;
        }
        if (!m.s.on(Setting.NETHER)) {
            e.setCancelled(true);
            p.sendActionBar("§cThe Nether is disabled in this game.");
            return;
        }
        if (m.mode.isHunt() && m.s.on(Setting.NETHER_FIRST) && m.isHunter(p.getUniqueId()) && !m.runnerInNether
                && from.getWorld().equals(m.world)) {
            e.setCancelled(true);
            p.sendActionBar(m.mode.words("§cA runner has to enter the nether first!"));
            return;
        }
        Location to;
        if (from.getWorld().equals(m.world)) {
            World n = m.netherWorld();
            if (n == null) {
                e.setCancelled(true);
                return;
            }
            double y = Math.max(32, Math.min(110, from.getY()));
            to = new Location(n, from.getX() / 8.0, y, from.getZ() / 8.0, from.getYaw(), from.getPitch());
        } else {
            double y = Math.max(m.world.getMinHeight() + 10, Math.min(200, from.getY()));
            to = new Location(m.world, from.getX() * 8.0, y, from.getZ() * 8.0, from.getYaw(), from.getPitch());
        }
        if (m.mode.isHunt()) m.notePortal(p, from, to);
        e.setTo(to);
    }

    @EventHandler
    public void onEntityPortal(EntityPortalEvent e) {
        if (plugin.matchFor(e.getFrom().getWorld()) != null || isLobby(e.getFrom().getWorld())) e.setCancelled(true);
    }

    @EventHandler
    public void onPortalCreate(PortalCreateEvent e) {
        Match m = plugin.matchFor(e.getWorld());
        if (m == null || !m.s.on(Setting.NETHER)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!e.getCause().name().equals("SPECTATE")) return;
        // spectators can only use the vanilla spectator menu inside their own game
        Match m = plugin.matchFor(e.getFrom().getWorld());
        if (m == null || e.getTo() == null || !m.owns(e.getTo().getWorld())) e.setCancelled(true);
    }
}
