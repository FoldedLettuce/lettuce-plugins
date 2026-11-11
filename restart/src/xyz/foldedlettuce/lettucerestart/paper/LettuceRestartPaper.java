package xyz.foldedlettuce.lettucerestart.paper;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import xyz.foldedlettuce.lettucerestart.RestartScheduler;

import java.time.Clock;

public final class LettuceRestartPaper extends JavaPlugin implements RestartScheduler.Platform {

    private RestartScheduler scheduler;
    private BukkitTask task;

    @Override
    public void onEnable() {
        scheduler = new RestartScheduler(this, Clock.systemUTC());
        scheduler.loadConfig(getDataFolder().toPath());
        info(scheduler.describeSchedule());
        // every 5 seconds (100 ticks), on the main thread so saving/shutdown is safe
        task = Bukkit.getScheduler().runTaskTimer(this, () -> scheduler.tick(), 200L, 100L);
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("lettucerestart.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "test":
                scheduler.force();
                sender.sendMessage("§aTest restart queued. The server will save and restart once it's been empty for the configured time.");
                break;
            case "cancel":
                sender.sendMessage(scheduler.cancelForce() ? "§aTest restart cancelled." : "§7No test restart was queued.");
                break;
            default:
                sender.sendMessage("§e[LettuceRestart] §f" + scheduler.status());
                sender.sendMessage("§7/" + label + " test §8- §7queue a restart for when the server is empty");
                sender.sendMessage("§7/" + label + " cancel §8- §7cancel a test restart");
        }
        return true;
    }

    // ---- Platform

    @Override
    public int playerCount() {
        return Bukkit.getOnlinePlayers().size();
    }

    @Override
    public void saveAndShutdown() {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush");
        Bukkit.shutdown(); // Pterodactyl/Wings starts it back up
    }

    @Override
    public void info(String msg) { getLogger().info(msg); }

    @Override
    public void warn(String msg) { getLogger().warning(msg); }
}
