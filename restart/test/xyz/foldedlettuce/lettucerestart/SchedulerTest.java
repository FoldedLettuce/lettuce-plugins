package xyz.foldedlettuce.lettucerestart;

import java.time.*;
import java.util.*;

public class SchedulerTest {
    static class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant start) { now = start; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now; }
        void plus(Duration d) { now = now.plus(d); }
    }
    static class FakePlatform implements RestartScheduler.Platform {
        int players; int shutdowns; List<String> log = new ArrayList<>();
        public int playerCount() { return players; }
        public void saveAndShutdown() { shutdowns++; }
        public void info(String m) { log.add(m); }
        public void warn(String m) { log.add("WARN " + m); }
    }
    static int fails = 0;
    static void check(boolean ok, String name) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }
    static Instant chi(String ldt) { return LocalDateTime.parse(ldt).atZone(ZoneId.of("America/Chicago")).toInstant(); }

    static void run(MutableClock c, RestartScheduler s, Duration total) {
        for (long t = 0; t < total.getSeconds(); t += 5) { s.tick(); c.plus(Duration.ofSeconds(5)); }
    }

    public static void main(String[] a) {
        // 1. Started Sunday, Monday 6am passes, server empty -> restarts ~30s after 6:00
        {
            MutableClock c = new MutableClock(chi("2026-11-08T12:00:00")); // Sunday
            FakePlatform p = new FakePlatform();
            RestartScheduler s = new RestartScheduler(p, c);
            run(c, s, Duration.ofHours(17).plusMinutes(59)); // to Mon 5:59
            check(p.shutdowns == 0, "no restart before window");
            c.now = chi("2026-11-09T06:00:00");
            run(c, s, Duration.ofSeconds(25));
            check(p.shutdowns == 0, "waits empty-seconds");
            run(c, s, Duration.ofSeconds(15));
            check(p.shutdowns == 1, "restarts once empty 30s after window");
            run(c, s, Duration.ofMinutes(10));
            check(p.shutdowns == 1, "only calls shutdown once");
        }
        // 2. Players online at 6am until 9:13am, restarts right after they leave
        {
            MutableClock c = new MutableClock(chi("2026-11-08T12:00:00"));
            FakePlatform p = new FakePlatform();
            RestartScheduler s = new RestartScheduler(p, c);
            c.now = chi("2026-11-09T06:00:00");
            p.players = 2;
            run(c, s, Duration.ofHours(3).plusMinutes(13));
            check(p.shutdowns == 0, "no restart while players online");
            check(p.log.stream().filter(m -> m.contains("waiting")).count() == 1, "waiting message logged once");
            p.players = 0;
            run(c, s, Duration.ofSeconds(40));
            check(p.shutdowns == 1, "restarts right after server empties");
        }
        // 3. Brief empty blip (server hop) shorter than empty-seconds doesn't restart
        {
            MutableClock c = new MutableClock(chi("2026-11-08T12:00:00"));
            FakePlatform p = new FakePlatform();
            RestartScheduler s = new RestartScheduler(p, c);
            c.now = chi("2026-11-09T07:00:00");
            p.players = 0; run(c, s, Duration.ofSeconds(15));
            p.players = 1; run(c, s, Duration.ofSeconds(10));
            p.players = 0; run(c, s, Duration.ofSeconds(15));
            check(p.shutdowns == 0, "short empty blips don't trigger restart");
        }
        // 4. Server that booted AFTER Monday 6am (e.g. just restarted) is not due until next Monday
        {
            MutableClock c = new MutableClock(chi("2026-11-09T06:01:00"));
            FakePlatform p = new FakePlatform();
            RestartScheduler s = new RestartScheduler(p, c);
            check(!s.isDue(), "freshly restarted server not due");
            run(c, s, Duration.ofHours(2));
            check(p.shutdowns == 0, "no restart loop after boot");
            c.now = chi("2026-11-16T06:00:00");
            run(c, s, Duration.ofMinutes(1));
            check(p.shutdowns == 1, "due again next Monday");
        }
        // 5. Uptime guard: booted 5:50, window 6:00 -> waits until 30 min uptime
        {
            MutableClock c = new MutableClock(chi("2026-11-09T05:50:00"));
            FakePlatform p = new FakePlatform();
            RestartScheduler s = new RestartScheduler(p, c);
            c.now = chi("2026-11-09T06:00:00");
            run(c, s, Duration.ofMinutes(15));
            check(p.shutdowns == 0, "min uptime respected");
            run(c, s, Duration.ofMinutes(10));
            check(p.shutdowns == 1, "restarts after min uptime");
        }
        // 6. DST: Nov 1 2026 fallback, Mar 8 2027 spring forward. Slot still 6:00 local
        {
            MutableClock c = new MutableClock(chi("2027-03-07T12:00:00"));
            FakePlatform p = new FakePlatform();
            RestartScheduler s = new RestartScheduler(p, c);
            check(s.nextSlot(c.now).equals(chi("2027-03-08T06:00:00")), "DST next slot is 6:00 local");
        }
        // 7. Config parsing
        {
            MutableClock c = new MutableClock(chi("2026-11-08T12:00:00"));
            FakePlatform p = new FakePlatform();
            RestartScheduler s = new RestartScheduler(p, c);
            s.applyConfig(RestartScheduler.parse(Arrays.asList(
                    "enabled: true # yes", "days: tue, fri", "time: \"6:30\"", "timezone: America/Chicago", "empty-seconds: 0")));
            check(s.nextSlot(c.now).equals(chi("2026-11-10T06:30:00")), "custom days/time parse");
            check(RestartScheduler.parseDays("DAILY").size() == 7, "daily");
            c.now = chi("2026-11-10T07:00:00");
            s.tick();
            check(p.shutdowns == 1, "empty-seconds 0 restarts immediately");
        }
        // 8. Disabled + forced test
        {
            MutableClock c = new MutableClock(chi("2026-11-08T12:00:00"));
            FakePlatform p = new FakePlatform();
            RestartScheduler s = new RestartScheduler(p, c);
            s.applyConfig(Collections.singletonMap("enabled", "false"));
            c.now = chi("2026-11-09T07:00:00");
            run(c, s, Duration.ofMinutes(5));
            check(p.shutdowns == 0, "disabled does nothing");
            s.force();
            run(c, s, Duration.ofMinutes(1));
            check(p.shutdowns == 1, "forced test restart works");
            System.out.println("  status sample: " + new RestartScheduler(p, c).status());
        }
        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
