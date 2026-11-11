package xyz.foldedlettuce.lettucechat;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;

/**
 * Network chat for Lettuce MC. Copies each chat message to players on the other
 * servers (never cancels or edits chat), and adds network-wide /mute and /unmute
 * backed by a LuckPerms meta key so every server sees the same mute.
 */
public final class LettuceChat {
    static final String MUTE_KEY = "lettucechat-muted";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.]{1,32}");
    private static final Pattern DURATION = Pattern.compile("\\d+[smhdwy]");
    private static final Map<String, String> NAMES = Map.of(
            "lobby", "&aLobby",
            "survival1", "&2Survival 1",
            "survival2", "&6Survival 2",
            "skyblock", "&bSkyblock",
            "games", "&cGames");

    private static final Map<String, String> PLAIN = Map.of(
            "lobby", "the Lobby",
            "survival1", "Survival 1",
            "survival2", "Survival 2",
            "skyblock", "Skyblock",
            "games", "Games");
    private static final Path WEBHOOK_FILE = Path.of("plugins", "lettucechat", "discord-webhook.txt");

    private final ProxyServer proxy;
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private final HttpClient http = HttpClient.newHttpClient();
    private String webhook = "";
    private final Logger log = Logger.getLogger("LettuceChat");
    private Object legacy;
    private Method deserialize;
    private Object plainSerializer;
    private Method plainSerialize;

    @Inject
    public LettuceChat(ProxyServer proxy) {
        this.proxy = proxy;
        try {
            Class<?> c = Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
            legacy = c.getMethod("legacyAmpersand").invoke(null);
            deserialize = c.getMethod("deserialize", String.class);
        } catch (Throwable t) {
            log.severe("LettuceChat could not load the text serializer: " + t);
        }
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        var cm = proxy.getCommandManager();
        cm.register(cm.metaBuilder("mute").plugin(this).build(), new MuteCommand(true));
        cm.register(cm.metaBuilder("unmute").plugin(this).build(), new MuteCommand(false));
        loadWebhook();
        cm.register(cm.metaBuilder("duty").plugin(this).build(), new DutyCommand());
        log.info("LettuceChat 1.6.1 ready: network chat + /mute /unmute /duty + restart-to-lobby");
    }

    @Subscribe
    public void onChat(PlayerChatEvent event) {
        try {
            if (event.getResult() != null && !event.getResult().isAllowed()) return;
            Player sender = event.getPlayer();
            if (isMuted(sender.getUniqueId())) return; // backend blocks it locally, we just don't relay
            Optional<ServerConnection> conn = sender.getCurrentServer();
            if (!conn.isPresent() || deserialize == null) return;
            String from = conn.get().getServerInfo().getName();

            String tag = NAMES.getOrDefault(from.toLowerCase(), "&7" + from);
            String msg = event.getMessage().replace("&", "&​"); // no player color codes
            String line = "&8[" + tag + "&8] " + prefix(sender) + sender.getUsername() + "&7: &f" + msg;
            Component out = text(line);

            for (RegisteredServer server : proxy.getAllServers()) {
                if (server.getServerInfo().getName().equalsIgnoreCase(from)) continue;
                for (Player p : server.getPlayersConnected()) p.sendMessage(out);
            }
        } catch (Throwable t) {
            log.warning("LettuceChat failed to relay a message: " + t);
        }
    }

    // ---------- Discord join / switch / leave ----------

    private void loadWebhook() {
        try {
            if (!Files.exists(WEBHOOK_FILE)) {
                Files.createDirectories(WEBHOOK_FILE.getParent());
                Files.writeString(WEBHOOK_FILE, "");
            }
            webhook = Files.readString(WEBHOOK_FILE).trim();
            if (webhook.startsWith("https://discord.com/api/webhooks/") || webhook.startsWith("https://discordapp.com/api/webhooks/")) {
                log.info("LettuceChat: Discord join/leave messages on");
            } else {
                webhook = "";
                log.info("LettuceChat: put a Discord webhook URL in " + WEBHOOK_FILE + " to get join/leave messages");
            }
        } catch (Exception e) {
            log.warning("LettuceChat could not read " + WEBHOOK_FILE + ": " + e);
        }
    }

    private static String plain(String server) {
        return PLAIN.getOrDefault(server.toLowerCase(), server);
    }

    @Subscribe
    public void onConnected(ServerConnectedEvent event) {
        try {
            Player p = event.getPlayer();
            String to = event.getServer().getServerInfo().getName();
            boolean first = online.add(p.getUniqueId());
            String tag = NAMES.getOrDefault(to.toLowerCase(), "&7" + to);
            if (first) {
                post(p, p.getUsername() + " joined the server", 0x57F287);
                broadcast("&8[&a+&8] &f" + p.getUsername() + " &7joined the server");
            } else {
                boolean lobby = to.equalsIgnoreCase("lobby");
                String where = lobby ? "went back to the Lobby" : "went to " + plain(to);
                post(p, p.getUsername() + " " + where, 0x5865F2);
                broadcast("&8[&b\u2192&8] &f" + p.getUsername() + " &7"
                        + (lobby ? "went back to the " : "went to ") + tag);
            }
        } catch (Throwable t) {
            log.warning("LettuceChat join message failed: " + t);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        try {
            Player p = event.getPlayer();
            if (online.remove(p.getUniqueId())) {
                post(p, p.getUsername() + " left the server", 0xED4245);
                broadcast("&8[&c-&8] &f" + p.getUsername() + " &7left the server");
            }
        } catch (Throwable t) {
            log.warning("LettuceChat leave message failed: " + t);
        }
    }

    // ---------- restart redirect ----------

    /** Where players go when their server restarts, in order. First one that's up wins. */
    private static final java.util.List<String> FALLBACKS = java.util.List.of("lobby", "games");

    /** When a backend restarts, send its players to the Lobby (or Games if the Lobby is down) instead of kicking them off the network. */
    @Subscribe
    public void onKicked(KickedFromServerEvent event) {
        try {
            if (event.kickedDuringServerConnect()) return;
            String from = event.getServer().getServerInfo().getName();
            String reason = event.getServerKickReason().map(this::plainText).orElse("").toLowerCase();
            if (!reason.contains("lettuce-restart") && !reason.contains("server closed") && !reason.contains("restart")) return;
            RegisteredServer target = null;
            for (String name : FALLBACKS) {
                if (name.equalsIgnoreCase(from)) continue;
                Optional<RegisteredServer> s = proxy.getServer(name);
                if (s.isPresent() && isUp(s.get())) { target = s.get(); break; }
            }
            if (target == null) return;
            String to = target.getServerInfo().getName();
            String who = from.equalsIgnoreCase("lobby") ? "The Lobby" : plain(from);
            event.setResult(KickedFromServerEvent.RedirectPlayer.create(target,
                    text("&e" + who + " is restarting, sent you to " + plain(to) + ".")));
            log.info("Sent " + event.getPlayer().getUsername() + " to " + to + " (" + from + " restarting)");
        } catch (Throwable t) {
            log.warning("LettuceChat restart redirect failed: " + t);
        }
    }

    /** Quick ping so we don't redirect people into a server that's also down. */
    private static boolean isUp(RegisteredServer s) {
        try {
            s.ping().get(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private String plainText(Component c) {
        try {
            if (plainSerializer == null) {
                Class<?> k = Class.forName("net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer");
                plainSerializer = k.getMethod("plainText").invoke(null);
                plainSerialize = k.getMethod("serialize", Component.class);
            }
            return (String) plainSerialize.invoke(plainSerializer, c);
        } catch (Throwable t) {
            return String.valueOf(c);
        }
    }

    private void broadcast(String legacyText) {
        try {
            Component c = text(legacyText);
            for (RegisteredServer server : proxy.getAllServers())
                for (Player pl : server.getPlayersConnected()) pl.sendMessage(c);
        } catch (Throwable t) {
            log.warning("LettuceChat broadcast failed: " + t);
        }
    }

    private void post(Player p, String text, int color) {
        if (webhook.isEmpty()) return;
        String icon = "https://mc-heads.net/avatar/" + p.getUniqueId() + "/64";
        String json = "{\"username\":\"Lettuce MC\",\"allowed_mentions\":{\"parse\":[]},"
                + "\"embeds\":[{\"color\":" + color + ",\"author\":{\"name\":" + quote(text)
                + ",\"icon_url\":" + quote(icon) + "}}]}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(webhook))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((res, err) -> {
            if (err != null) log.warning("LettuceChat webhook failed: " + err);
            else if (res.statusCode() >= 300) log.warning("LettuceChat webhook got HTTP " + res.statusCode());
        });
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    private Component text(String legacyText) throws Exception {
        return (Component) deserialize.invoke(legacy, legacyText);
    }

    private void tell(CommandSource to, String legacyText) {
        try { to.sendMessage(text(legacyText)); } catch (Throwable ignored) { }
    }

    private static Object meta(UUID id) {
        var user = net.luckperms.api.LuckPermsProvider.get().getUserManager().getUser(id);
        return user == null ? null : user.getCachedData().getMetaData();
    }

    static boolean isMuted(UUID id) {
        try {
            Object m = meta(id);
            if (m == null) return false;
            return "true".equalsIgnoreCase(((net.luckperms.api.cacheddata.CachedMetaData) m).getMetaValue(MUTE_KEY));
        } catch (Throwable t) {
            return false;
        }
    }

    private String prefix(Player p) {
        try {
            Object m = meta(p.getUniqueId());
            if (m == null) return "";
            String pre = ((net.luckperms.api.cacheddata.CachedMetaData) m).getPrefix();
            return pre == null ? "" : pre;
        } catch (Throwable t) {
            return "";
        }
    }

    private final class MuteCommand implements SimpleCommand {
        private final boolean mute;
        MuteCommand(boolean mute) { this.mute = mute; }

        @Override
        public boolean hasPermission(Invocation inv) {
            return inv.source().hasPermission("lettucechat.mute");
        }

        @Override
        public void execute(Invocation inv) {
            CommandSource src = inv.source();
            String[] args = inv.arguments();
            if (args.length < 1 || !NAME.matcher(args[0]).matches()) {
                tell(src, mute ? "&cUsage: /mute <player> [time like 30m, 2h, 1d]" : "&cUsage: /unmute <player>");
                return;
            }
            String target = args[0];
            var cm = proxy.getCommandManager();
            var console = proxy.getConsoleCommandSource();
            if (mute) {
                if (args.length >= 2 && !DURATION.matcher(args[1]).matches()) {
                    tell(src, "&cTime must look like 30m, 2h, 1d or 1w.");
                    return;
                }
                // clear any old mute first, then set the new one, in order
                String cmd = args.length >= 2
                        ? "lpv user " + target + " meta settemp " + MUTE_KEY + " true " + args[1]
                        : "lpv user " + target + " meta set " + MUTE_KEY + " true";
                cm.executeAsync(console, "lpv user " + target + " meta unset " + MUTE_KEY)
                        .thenCompose(x -> cm.executeAsync(console, "lpv user " + target + " meta unsettemp " + MUTE_KEY))
                        .thenCompose(x -> cm.executeAsync(console, cmd));
                tell(src, "&aMuted &f" + target + (args.length >= 2 ? " &afor &f" + args[1] : " &auntil unmuted") + "&a on every server.");
            } else {
                cm.executeAsync(console, "lpv user " + target + " meta unset " + MUTE_KEY)
                        .thenCompose(x -> cm.executeAsync(console, "lpv user " + target + " meta unsettemp " + MUTE_KEY));
                tell(src, "&aUnmuted &f" + target + "&a.");
            }
            log.info((src instanceof Player pl ? pl.getUsername() : "Console") + " ran " + (mute ? "mute " : "unmute ") + String.join(" ", args));
        }
    }

    /** /duty toggles the LuckPerms "admin" group on yourself, network-wide. */
    private final class DutyCommand implements SimpleCommand {
        @Override
        public boolean hasPermission(Invocation inv) {
            return inv.source().hasPermission("lettucechat.duty");
        }

        @Override
        public void execute(Invocation inv) {
            if (!(inv.source() instanceof Player p)) {
                tell(inv.source(), "&cOnly players can go on duty.");
                return;
            }
            boolean onDuty = p.hasPermission("group.admin");
            String cmd = "lpv user " + p.getUsername() + " parent " + (onDuty ? "remove" : "add") + " admin";
            proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), cmd);
            tell(p, onDuty ? "&7Duty &cOFF&7. Back to regular perms."
                           : "&7Duty &aON&7. Admin perms active on every server.");
            log.info(p.getUsername() + " went " + (onDuty ? "off" : "on") + " duty");
        }
    }
}
