# LettuceGames

Minigames plugin for the Lettuce MC Games server (Paper 26.3). It recreates mcmanhunt.com modes with every setting unlocked.

**Modes:** Random Items Challenge, Death Swap, Block Shuffle, Manhunt, Hitman, Speedrun, Bingo, Death Shuffle

**Current version:** 2.17.0

## Building

Needs JDK 25 (Paper 26.x). The Gradle wrapper is included:

    ./gradlew build

The jar lands in `build/libs/LettuceGames-<version>.jar`. When you change something, bump `version` in both `build.gradle.kts` and `src/main/resources/plugin.yml`.

Releases before this repo were compiled against hand-written API stubs, because the build box couldn't reach Maven. This Gradle setup is new and hasn't been run yet. If the `paper-api` version in `build.gradle.kts` doesn't resolve, set it to whatever your server runs.

## Layout

- `src/main/java/xyz/foldedlettuce/lettucegames/`: all the code
  - `LettuceGames`: main class, commands, lobby items
  - `Match`: one running game (every mode's logic lives here)
  - `Mode`, `Setting`, `GameSettings`: modes, every setting and default, and per-mode values
  - `Bingo`, `Death`, `OpSource`: the Bingo card engine, Death Shuffle deaths, and OP loot sources
  - `GameListener`, `HuntListener`, `DeathSwapListener`, `TwistListener`, `BingoListener`: event handling
  - `Menus`, `Party`, `PartyManager`, `Presets`, `Stats`, `MapPool`, `ChunkWork`, `ItemPool`: everything else
- `src/main/resources/config.yml`: default config (item pool, OP loot, block lists, Bingo tasks, structures)
- `extras/`: the TAB proxy config and ServerSelectorX lobby menu that go with the Games server

## Commands

`/lg` (admin: setlobby, list, end, reload, pool, maps, hologram), `/track`, `/spec`, `/specmode`, `/leave`, `/blacklist`, `/pick`, `/skip`, `/bingocard`, `/stats`, `/leaderboard`
