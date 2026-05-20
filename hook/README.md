# Hook

This directory contains the Java agent and launcher scripts for collecting decoded PvP statistics packets from the running PokeMMO client.

## Files

- `src/io/pokemmo/hook/PokemonStatsAgent.java`
- `build-hook.ps1`
- `Launch-PokeMMO-With-Hook.ps1`

## Build

```powershell
powershell -ExecutionPolicy Bypass -File .\hook\build-hook.ps1
```

## Launch

```powershell
powershell -ExecutionPolicy Bypass -File .\hook\Launch-PokeMMO-With-Hook.ps1
```

## Output

The hook writes JSONL logs in:

- `hook/logs/pvp-stats.jsonl`
