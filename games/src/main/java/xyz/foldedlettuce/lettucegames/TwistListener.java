package xyz.foldedlettuce.lettucegames;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;

/** Twists that work in every mode: Lucky Block and Random Crafts (Structure Mania runs on the game timer). */
public class TwistListener implements Listener {
    private final LettuceGames plugin;

    public TwistListener(LettuceGames plugin) {
        this.plugin = plugin;
        try {
            NamespacedKey key = new NamespacedKey(plugin, "lucky_block");
            Bukkit.removeRecipe(key);
            ShapedRecipe r = new ShapedRecipe(key, Match.luckyBlock());
            r.shape("GGG", "GDG", "GGG");
            r.setIngredient('G', Material.GOLD_INGOT);
            r.setIngredient('D', Material.DROPPER);
            Bukkit.addRecipe(r);
        } catch (Throwable t) {
            plugin.getLogger().warning("Couldn't add the Lucky Block recipe: " + t);
        }
    }

    private Match running(org.bukkit.World w) {
        Match m = plugin.matchFor(w);
        return m != null && !m.ended ? m : null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!Match.isLucky(e.getItemInHand())) return;
        Match m = running(e.getPlayer().getWorld());
        if (m != null) m.luckyPlaced.add(Match.blockKey(e.getBlock()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Match m = running(b.getWorld());
        if (m == null || !m.luckyPlaced.remove(Match.blockKey(b))) return;
        e.setDropItems(false);
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> m.luckyRoll(p, b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(PrepareItemCraftEvent e) {
        if (e.getViewers().isEmpty()) return;
        ItemStack result = e.getInventory().getResult();
        if (result == null || result.getType() == Material.AIR) return;
        Match m = plugin.matchFor(e.getViewers().get(0).getWorld());
        if (Match.isLucky(result)) {
            // lucky blocks only craft when the twist is on
            if (m == null || m.s.get(Setting.LUCKY_BLOCK) == 0) e.getInventory().setResult(null);
            return;
        }
        if (m == null || !m.s.on(Setting.RANDOM_CRAFTS)) return;
        if (result.hasItemMeta() && result.getItemMeta().hasDisplayName()) return; // golden heads and other special stuff
        Material to = m.randomCraft(result.getType());
        e.getInventory().setResult(new ItemStack(to, Math.max(1, Math.min(result.getAmount(), to.getMaxStackSize()))));
    }
}
