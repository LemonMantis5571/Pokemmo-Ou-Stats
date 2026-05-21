# 🔱 PokeMMO PvP Stats Hook Agent

This directory contains the Java agent (`PokemonStatsAgent.java`) used to intercept, decode, and log the PokeMMO in-game PvP matchmaking statistics packets.

---

## 📁 Directory Layout

- `src/io/pokemmo/hook/PokemonStatsAgent.java` - The ByteBuddy Java agent hook logic.
- `lib/` - Dynamic compiler and ByteBuddy dependencies downloaded automatically by `run.py`.
- `dist/` - Houses the compiled Java Agent JAR (`pokemmo-pvp-hook-agent.jar`).
- `logs/` - Target folder where the raw date-specific `.jsonl` packets and sanitized JSONs are saved.

---

## ⚙️ Compilation & Execution

Compilation, dependency resolution, game launching, and dataset sanitization are all managed via the root manager script `./run.py`.

Refer to the main [README.md](../README.md) at the root of the workspace for complete quickstart instructions.

---

## 📈 Telemetry Logs

When you view the matchmaking statistics window inside PokeMMO and click on any Pokémon, the agent dynamically intercepts the requested month and year, creating a date-specific log file:

- **Raw telemetry packets**: `hook/logs/pvp-stats-<month>-<year>.jsonl`
- **Sanitized dashboard JSON database**: `hook/logs/pvp-stats-<month>-<year>-sanitized.json`
