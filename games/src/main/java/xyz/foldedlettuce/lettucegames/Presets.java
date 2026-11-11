package xyz.foldedlettuce.lettucegames;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** 3 saved setting presets per player, stored in presets.yml. */
public class Presets {
    private final LettuceGames plugin;
    private final File file;
    private final YamlConfiguration yaml;

    public Presets(LettuceGames plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "presets.yml");
        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    private String path(UUID id, int slot) {
        return id.toString() + ".slot" + slot;
    }

    public GameSettings get(UUID id, int slot) {
        ConfigurationSection sec = yaml.getConfigurationSection(path(id, slot));
        return sec == null ? null : GameSettings.load(sec);
    }

    public void save(UUID id, int slot, GameSettings s) {
        yaml.set(path(id, slot), null);
        ConfigurationSection sec = yaml.createSection(path(id, slot));
        s.save(sec);
        write();
    }

    public void delete(UUID id, int slot) {
        yaml.set(path(id, slot), null);
        write();
    }

    private void write() {
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save presets.yml: " + e.getMessage());
        }
    }
}
