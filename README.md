# 🔱 PokeMMO PvP Stats Scraper & Enhancer

A powerful, cross-platform, zero-dependency suite for collecting and sanitizing PvP usage statistics directly from the PokeMMO in-game client. 

This toolkit reverse-engineers the client UI and network packets, using a lightweight Java agent to programmatically and hands-free query the stats database for **all 720+ Pokémon** in under 4 minutes. It then sanitizes and enhances the raw logs into a premium, ranked, autocomplete-ready JSON database tailored for modern web dashboards.

---

## 📦 Features

- **🎮 Cross-Platform CLI (`run.py`)**: A single, dependency-free Python manager that handles compiling, launching, sanitizing, and cleaning across Linux (Flatpak/Native), Windows, and macOS.
- **⚡ Programmatic Auto-Dump**: Attaches a Java agent to the client via ByteBuddy. Clicking exactly **one** Pokémon in the PvP stats window starts a robust, client-side replayer that requests the statistics of all 720+ species sequentially (safely throttled at 300ms).
- **📈 Advanced Sanitization**: Compiles the raw packet streams into a sorted, rank-assigned JSON database. Calculates global `winRate`, `usageRate`, and provides O(1) dictionary key-maps for instant autocomplete lookups on the frontend.
- **🛡️ Clean & Obfuscation-Agnostic**: Dynamically intercepts the obfuscated GUI click-handler and resolves translations (species names, items, natures, abilities, and allies) directly through the client's internal registries.

---

## 🚀 Quick Start Guide

Everything is managed via the unified `./run.py` script at the root of the project.

### 1. Build the Java Agent
This downloads the required libraries (`byte-buddy` and the Eclipse compiler if `javac` is missing) and packages the Java hook agent JAR:
```bash
python run.py build
```

### 2. Launch PokeMMO with the Hook
This attaches the agent to the PokeMMO JVM. It automatically detects if you are running PokeMMO via **Flatpak** (common on Steam Deck/Linux) or natively (Windows/Linux) and injects the hook:
```bash
python run.py launch
```

### 3. Trigger the Dump
1. Log into your PokeMMO account and open the in-game **PvP Matchmaking menu**.
2. Click on the **Statistics (Estadísticas)** tab.
3. Click on **exactly one Pokémon** (e.g. Garchomp).
4. **Relax!** The background daemon thread will immediately wake up and quietly cycle through all 720+ Pokémon in the background. You can watch the console logs populate. The game remains fully playable.

### 4. Sanitize and Enhance the Dataset
The raw telemetry is dynamically saved to a date-specific log file depending on the month you requested (e.g. `hook/logs/pvp-stats-may-2026.jsonl`).

To compile and sanitize the dataset into a premium, dashboard-ready JSON database, run:
```bash
python run.py sanitize
```
*Note: By default, this will automatically discover and sanitize the newest raw `.jsonl` dump file in your logs directory. You can also explicitly specify a file path:*
```bash
python run.py sanitize hook/logs/pvp-stats-may-2026.jsonl
```

Your sanitized, ranked, and autocomplete-ready JSON database is saved alongside the raw telemetry (e.g. `📂 hook/logs/pvp-stats-may-2026-sanitized.json`).

---

## 📁 Repository Structure

```
Pokemmo-Stats-Widget/
├── run.py                 # Unified cross-platform CLI manager
├── README.md              # Documentation
├── hook/
│   ├── src/               # Java hook source code
│   │   └── io/pokemmo/hook/PokemonStatsAgent.java
│   ├── lib/               # Dynamic dependency folder (ByteBuddy, ECJ compiler)
│   ├── dist/              # Target folder for built JARs
│   └── logs/              # Date-named telemetry (e.g. pvp-stats-may-2026.jsonl)
└── parsers/
    └── pokemmo_pvp_stats_parser.py  # Utility to manually decode a raw hex body
```

---

## 🧹 Maintenance Commands

To prune target folders, intermediate build artifacts, and class directories:
```bash
python run.py clean
```
*(To preserve your dumped stats and only clean class/build files, run `python run.py clean --keep-logs`.)*

---

## ⚖️ Legal & Disclaimer

**This project is created solely for educational, research, and hobbyist purposes.** 

- **No Intent to Harm or Exploit:** The purpose of this project is to study Java Virtual Machine (JVM) runtime instrumentation, bytecode manipulation, and local telemetry extraction. It is not designed, intended, or recommended to facilitate cheating, automation (botting), exploitation, or any form of unfair play in the game.
- **Research Purpose Only:** This project exists solely as an academic exploration of JVM runtime instrumentation, bytecode manipulation, and local telemetry extraction techniques. It is intended for private study and personal research only.
- **Use at Your Own Risk:** The author(s) and contributor(s) of this project are not responsible for any actions taken against your accounts, hardware, or systems as a result of using, compiling, or reading this code.
- **No Affiliation:** This project is completely independent and has no affiliation, endorsement, or association with PokeMMO or its parent companies. All trademarks and assets belong to their respective owners.
- **Provided "As-Is":** This software is provided "as-is" without any express or implied warranties. Use of this material is entirely at the user's own discretion and liability.
