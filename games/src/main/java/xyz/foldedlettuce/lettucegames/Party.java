package xyz.foldedlettuce.lettucegames;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Party {
    public UUID leader;
    public final Set<UUID> members = new LinkedHashSet<>();
    public final Map<UUID, Long> invites = new HashMap<>();
    public final Set<UUID> ready = new LinkedHashSet<>();
    public final Map<UUID, TeamColor> teams = new HashMap<>();
    /** Manhunt / Hitman role picks (no pick = filled in at the start) */
    public final Map<UUID, Role> roles = new HashMap<>();
    /** each mode keeps its own settings, "settings" points at the current mode's */
    public final java.util.Map<Mode, GameSettings> allSettings = new java.util.EnumMap<>(Mode.class);
    public Mode mode = Mode.RIC;
    public GameSettings settings;

    {
        for (Mode m : Mode.values()) allSettings.put(m, new GameSettings(m));
        settings = allSettings.get(mode);
    }

    public void setMode(Mode m) {
        mode = m;
        settings = allSettings.get(m);
    }
    public boolean open = true;
    public boolean teamsLocked = false;
    public Match match;
    public BukkitTask countdown;
    public int countdownLeft;

    public Party(UUID leader) {
        this.leader = leader;
        members.add(leader);
    }

    /** Parties off: everyone on the server is in one group and anyone can change settings. */
    public boolean everyone = false;
    /** admin force-started with fewer than min players (testing) */
    public boolean forced = false;

    public boolean isLeader(UUID id) {
        return everyone || leader.equals(id);
    }

    public String leaderName() {
        Player p = Bukkit.getPlayer(leader);
        return p == null ? "?" : p.getName();
    }

    public List<Player> online() {
        List<Player> out = new ArrayList<>();
        for (UUID id : members) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) out.add(p);
        }
        return out;
    }

    public void broadcast(String text) {
        String prefix = everyone ? Msg.SYS : Msg.PARTY;
        for (Player p : online()) p.sendMessage(prefix + text);
    }

    public boolean allReady() {
        for (UUID id : members) if (!ready.contains(id)) return false;
        return !members.isEmpty();
    }

    public boolean inGame() {
        return match != null;
    }
}
