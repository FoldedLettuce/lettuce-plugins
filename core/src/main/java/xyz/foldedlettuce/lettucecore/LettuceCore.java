package xyz.foldedlettuce.lettucecore;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Lettuce MC backend helper: /lobby + /hub + /l, and blocks chat from players muted network-wide. */
public final class LettuceCore extends JavaPlugin implements Listener {
    private static final String TARGET = "lobby";
    private static final String MUTE_KEY = "lettucechat-muted";

    @Override
    public void onEnable() {
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        if (isMuted(p)) {
            event.setCancelled(true);
            p.sendMessage("§cYou're muted right now.");
        }
    }

    private static boolean isMuted(Player p) {
        try {
            var user = net.luckperms.api.LuckPermsProvider.get().getUserManager().getUser(p.getUniqueId());
            if (user == null) return false;
            return "true".equalsIgnoreCase(user.getCachedData().getMetaData().getMetaValue(MUTE_KEY));
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this.");
            return true;
        }
        Player p = (Player) sender;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(TARGET);
            p.sendMessage("§aSending you to the lobby...");
            p.sendPluginMessage(this, "BungeeCord", bytes.toByteArray());
        } catch (IOException e) {
            p.sendMessage("§cCouldn't connect you to the lobby.");
        }
        return true;
    }
}
