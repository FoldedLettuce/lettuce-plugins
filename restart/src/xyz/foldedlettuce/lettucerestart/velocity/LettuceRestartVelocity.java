package xyz.foldedlettuce.lettucerestart.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import xyz.foldedlettuce.lettucerestart.RestartScheduler;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

public final class LettuceRestartVelocity implements RestartScheduler.Platform {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDir;
    private RestartScheduler scheduler;

    @Inject
    public LettuceRestartVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDir) {
        this.server = server;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        scheduler = new RestartScheduler(this, Clock.systemUTC());
        scheduler.loadConfig(dataDir);
        info(scheduler.describeSchedule());
        server.getScheduler().buildTask(this, () -> scheduler.tick())
                .delay(10, TimeUnit.SECONDS)
                .repeat(5, TimeUnit.SECONDS)
                .schedule();
    }

    @Override
    public int playerCount() {
        return server.getPlayerCount(); // whole network
    }

    @Override
    public void saveAndShutdown() {
        server.shutdown(); // Pterodactyl/Wings starts it back up
    }

    @Override
    public void info(String msg) { logger.info(msg); }

    @Override
    public void warn(String msg) { logger.warn(msg); }
}
