package org.bukkit.plugin.java;
public abstract class JavaPlugin implements org.bukkit.plugin.Plugin {
  public void onEnable() {}
  public void onDisable() {}
  public java.util.logging.Logger getLogger() { return null; }
  public java.io.File getDataFolder() { return null; }
  public boolean onCommand(org.bukkit.command.CommandSender s, org.bukkit.command.Command c, String l, String[] a) { return false; }
}
