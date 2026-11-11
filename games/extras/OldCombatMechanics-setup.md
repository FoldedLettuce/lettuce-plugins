# 1.8 Combat setup (Games server only)

LettuceGames has a **1.8 Combat** setting in the game settings menu (default On). It flips each player between
two OldCombatMechanics modesets. Players in a match with it on get `old`, everyone else (lobby, waiting area,
matches with it off) gets `new`.

1. Drop the newest OldCombatMechanics jar into the Games server's plugins folder and start it once.
   Use 2.5.1 or newer. The latest it lists support for is 26.1.2, so test it on Paper 26.3 first.
2. In `plugins/OldCombatMechanics/config.yml` make sure two modesets exist and both are allowed everywhere:

       worlds:
         __default__: ["new", "old"]

   - `new` should have no modules, so it stays vanilla.
   - `old` should have every 1.8 module on: attack cooldown off, tool damage, sword blocking, attack range,
     player knockback, crits, regen, armour strength and the rest. The match worlds are created on the fly,
     so `__default__` is what covers them.
3. `/ocm reload`, then start a match with 1.8 Combat on and off and check both feel right.

LettuceGames sends `ocm mode <old|new> <player>` from the console (and tries `ocm modeset ...` if that
command doesn't exist). The names and commands are in LettuceGames' `config.yml` under `combat:`. If the
live config doesn't have that section, the defaults above are used. A warning shows in the console if
OCM didn't accept the command.
