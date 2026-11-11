package xyz.foldedlettuce.lettucerestart;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Platform-independent restart logic shared by the Paper and Velocity entry points.
 *
 * A restart becomes "due" once a scheduled slot (for example Monday 06:00 America/Chicago)
 * has passed since this server started. While due, the server restarts as soon as it has
 * been empty for {@code empty-seconds}. If people are online it simply keeps waiting.
 */
public final class RestartScheduler {

    /** What the scheduler needs from the platform. */
    public interface Platform {
        int playerCount();
        void saveAndShutdown();
        void info(String msg);
        void warn(String msg);
    }

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("EEE MMM d h:mm a z", Locale.US);

    private final Platform platform;
    private final Clock clock;
    private final Instant startedAt;

    // config
    private boolean enabled = true;
    private Set<DayOfWeek> days = EnumSet.of(DayOfWeek.MONDAY);
    private LocalTime time = LocalTime.of(6, 0);
    private ZoneId zone = ZoneId.of("America/Chicago");
    private long emptySeconds = 30;
    private long minUptimeMinutes = 30;

    // state
    private Instant emptySince;
    private boolean announcedWaiting;
    private boolean forced;
    private boolean restarting;

    public RestartScheduler(Platform platform, Clock clock) {
        this.platform = platform;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    // ------------------------------------------------------------------ config

    public void loadConfig(Path dataDir) {
        Path file = dataDir.resolve("config.yml");
        try {
            Files.createDirectories(dataDir);
            if (!Files.exists(file)) {
                try (InputStream in = RestartScheduler.class.getResourceAsStream("/lettucerestart-config.yml")) {
                    if (in != null) Files.copy(in, file);
                }
            }
            applyConfig(parse(Files.readAllLines(file, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            platform.warn("Could not read " + file + " (" + e.getMessage() + "), using defaults (Monday 6:00 AM America/Chicago)");
        }
    }

    static Map<String, String> parse(List<String> lines) {
        Map<String, String> out = new HashMap<>();
        for (String raw : lines) {
            String line = stripComment(raw).trim();
            int colon = line.indexOf(':');
            if (line.isEmpty() || colon <= 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            out.put(key, value);
        }
        return out;
    }

    private static String stripComment(String line) {
        boolean single = false, dbl = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !dbl) single = !single;
            else if (c == '"' && !single) dbl = !dbl;
            else if (c == '#' && !single && !dbl && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    void applyConfig(Map<String, String> cfg) {
        if (cfg.containsKey("enabled")) enabled = Boolean.parseBoolean(cfg.get("enabled"));
        if (cfg.containsKey("days")) days = parseDays(cfg.get("days"));
        if (cfg.containsKey("time")) time = LocalTime.parse(normalizeTime(cfg.get("time")));
        if (cfg.containsKey("timezone")) zone = ZoneId.of(cfg.get("timezone"));
        if (cfg.containsKey("empty-seconds")) emptySeconds = Math.max(0, Long.parseLong(cfg.get("empty-seconds")));
        if (cfg.containsKey("min-uptime-minutes")) minUptimeMinutes = Math.max(0, Long.parseLong(cfg.get("min-uptime-minutes")));
    }

    private static String normalizeTime(String t) {
        String s = t.trim();
        return s.length() == 4 && s.charAt(1) == ':' ? "0" + s : s; // "6:00" -> "06:00"
    }

    static Set<DayOfWeek> parseDays(String value) {
        String v = value.replace("[", "").replace("]", "").trim().toUpperCase(Locale.ROOT);
        if (v.equals("DAILY") || v.equals("EVERYDAY") || v.equals("ALL")) return EnumSet.allOf(DayOfWeek.class);
        EnumSet<DayOfWeek> set = EnumSet.noneOf(DayOfWeek.class);
        for (String part : v.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            for (DayOfWeek d : DayOfWeek.values()) {
                if (d.name().equals(p) || d.name().startsWith(p) && p.length() >= 3) { set.add(d); break; }
            }
        }
        if (set.isEmpty()) throw new IllegalArgumentException("no valid days in '" + value + "'");
        return set;
    }

    // ------------------------------------------------------------------ logic

    /** Most recent scheduled slot at or before {@code now}, or null if none in the last week. */
    Instant lastSlot(Instant now) {
        LocalDate today = now.atZone(zone).toLocalDate();
        for (int back = 0; back <= 7; back++) {
            LocalDate d = today.minusDays(back);
            if (!days.contains(d.getDayOfWeek())) continue;
            Instant slot = ZonedDateTime.of(d, time, zone).toInstant();
            if (!slot.isAfter(now)) return slot;
        }
        return null;
    }

    /** Next scheduled slot strictly after {@code now}. */
    Instant nextSlot(Instant now) {
        LocalDate today = now.atZone(zone).toLocalDate();
        for (int fwd = 0; fwd <= 7; fwd++) {
            LocalDate d = today.plusDays(fwd);
            if (!days.contains(d.getDayOfWeek())) continue;
            Instant slot = ZonedDateTime.of(d, time, zone).toInstant();
            if (slot.isAfter(now)) return slot;
        }
        return null;
    }

    public boolean isDue() {
        if (forced) return true;
        if (!enabled) return false;
        Instant slot = lastSlot(clock.instant());
        return slot != null && startedAt.isBefore(slot);
    }

    /** Called every few seconds by the platform scheduler. */
    public void tick() {
        if (restarting || !isDue()) return;
        Instant now = clock.instant();

        if (!forced && Duration.between(startedAt, now).toMinutes() < minUptimeMinutes) return;

        int players = platform.playerCount();
        if (players > 0) {
            emptySince = null;
            if (!announcedWaiting) {
                platform.info("Weekly restart is due, waiting for the server to empty (" + players + " online)");
                announcedWaiting = true;
            }
            return;
        }
        if (emptySince == null) {
            emptySince = now;
            if (emptySeconds > 0) return;
        }
        if (Duration.between(emptySince, now).getSeconds() >= emptySeconds) {
            restarting = true;
            platform.info("Server is empty and a restart is due. Saving and restarting now.");
            platform.saveAndShutdown();
        }
    }

    public void force() { forced = true; }
    public boolean cancelForce() { boolean was = forced; forced = false; return was; }

    public String status() {
        Instant now = clock.instant();
        StringBuilder sb = new StringBuilder();
        if (!enabled && !forced) {
            sb.append("Scheduled restarts are disabled in config.");
            return sb.toString();
        }
        if (isDue()) {
            sb.append(forced ? "Test restart queued. " : "Restart is DUE. ")
              .append("Restarts after the server has been empty for ").append(emptySeconds).append("s.");
        } else {
            Instant next = nextSlot(now);
            sb.append("Next restart window: ").append(next == null ? "none" : FMT.format(next.atZone(zone)))
              .append(" (then restarts as soon as it's empty).");
        }
        sb.append(" Up since ").append(FMT.format(startedAt.atZone(zone))).append('.');
        return sb.toString();
    }

    public String describeSchedule() {
        return "Restart window: " + (days.size() == 7 ? "daily" : days.toString().toLowerCase(Locale.ROOT))
                + " at " + time + " " + zone + ", after " + emptySeconds + "s empty"
                + (enabled ? "" : " (DISABLED)");
    }
}
