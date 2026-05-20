# Pokemmo-Stats-Widget

Utilities for researching and collecting PokeMMO statistics from two sources:

- forum movement threads
- the in-game PvP Statistics window

## Contents

- `trackers/pokemmo_movement_tracker.py`
  - Crawls PokeMMO forum movement threads
  - Exports JSON snapshots and a flat CSV
- `parsers/pokemmo_pvp_stats_parser.py`
  - Decodes the reverse-engineered inbound PvP stats packet body (`nu`, opcode `95`)
- `hook/`
  - Java agent source and PowerShell scripts for launching PokeMMO with a stats hook

## Forum Movement Tracker

Install Python dependencies:

```powershell
python -m pip install requests
```

Run:

```powershell
python .\trackers\pokemmo_movement_tracker.py
```

Outputs are written to `pokemmo_movement_tracker_output/` by default.

## PvP Packet Parser

The parser expects the decoded `nu` packet body, not the encrypted network frame.

Example:

```powershell
python .\parsers\pokemmo_pvp_stats_parser.py --hex "BD01..."
```

## PokeMMO Hook

The bundled PokeMMO runtime does not expose `java.instrument`, so the hook launcher uses a portable external JDK 17.

Build the hook:

```powershell
powershell -ExecutionPolicy Bypass -File .\hook\build-hook.ps1
```

Launch PokeMMO with the hook:

```powershell
powershell -ExecutionPolicy Bypass -File .\hook\Launch-PokeMMO-With-Hook.ps1
```

The hook writes JSONL logs under `hook/logs/`.

## Notes

- This repo contains research tooling and reverse-engineering helpers.
- It does not include decompiled client dumps, build outputs, runtime downloads, or personal logs.
