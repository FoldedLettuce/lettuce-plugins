# lettuce-plugins

Everything custom on the Lettuce MC network.

| Folder | Plugin | Runs on | Build |
|---|---|---|---|
| `games/` | LettuceGames: minigames (RIC, Death Swap, Block Shuffle, Manhunt, Hitman, Speedrun, Bingo, Death Shuffle) | Games server (Paper) | Gradle, Java 25 |
| `core/` | LettuceCore: /lobby, /hub, network mutes | Paper backends | Gradle, Java 25 |
| `chat/` | LettuceChat: global chat and /mute | Velocity proxy | Gradle |
| `restart/` | LettuceRestart: weekly restart when the server is empty | Paper and Velocity | `build.sh` (plain JDK) |

## Building

CI builds all four on every push. Locally you need JDK 25:

    cd games && chmod +x gradlew && ./gradlew build --no-daemon     # same for core/ and chat/
    cd restart && bash build.sh

Jars come out as `<Name>-<version>.jar`. Bump `version` in the plugin's `build.gradle.kts` and its plugin descriptor
(`plugin.yml` or `velocity-plugin.json`) when you change something.

## Releasing

Every push to `main` builds and publishes a release named `vYYYY.MM.DD-b<run number>` with all four jars. Nothing to run.
Pushing a `v*` tag by hand also works and uses that tag name.

## Deploying

`deploy/update-plugins.sh <plugins-dir> <Plugin>...` pulls the latest release jars and replaces the old versions.
Put a read-only token in `deploy/.env` (`GITHUB_TOKEN=...`). Run it with the server stopped.

Server configs (`config.yml` on the servers, `stats.yml`, `presets.yml`) are not in this repo.
