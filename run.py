#!/usr/bin/env python3
"""
PokeMMO PvP Stats Hook Agent Manager
Provides an OS-agnostic, zero-dependency CLI interface to build, launch,
sanitize, and clean the Java stats-gathering hook.
"""

import sys
import os
import json
import platform
import subprocess
import urllib.request
from pathlib import Path

# Constants
BYTE_BUDDY_VERSION = "1.18.8"
ECJ_VERSION = "3.45.0"

BYTE_BUDDY_URL = f"https://repo1.maven.org/maven2/net/bytebuddy/byte-buddy/{BYTE_BUDDY_VERSION}/byte-buddy-{BYTE_BUDDY_VERSION}.jar"
ECJ_URL = f"https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/{ECJ_VERSION}/ecj-{ECJ_VERSION}.jar"

def print_banner():
    print("==========================================================")
    print("      🔱 PokeMMO PvP Stats Hook Manager (CLI) 🔱")
    print("==========================================================")

def get_paths():
    root = Path(__file__).parent.resolve()
    return {
        "root": root,
        "hook_src": root / "hook" / "src" / "io" / "pokemmo" / "hook" / "PokemonStatsAgent.java",
        "hook_lib": root / "hook" / "lib",
        "hook_build": root / "hook" / "build",
        "hook_dist": root / "hook" / "dist",
        "hook_logs": root / "hook" / "logs",
        "agent_jar": root / "hook" / "dist" / "pokemmo-pvp-hook-agent.jar",
        "log_jsonl": root / "hook" / "logs" / "pvp-stats.jsonl",
        "log_json": root / "hook" / "logs" / "pvp-stats-sanitized.json",
        "byte_buddy_jar": root / "hook" / "lib" / f"byte-buddy-{BYTE_BUDDY_VERSION}.jar",
        "ecj_jar": root / "hook" / "lib" / f"ecj-{ECJ_VERSION}.jar"
    }

def download_file(url, dest_path):
    print(f"Downloading {url.split('/')[-1]}...")
    dest_path.parent.mkdir(parents=True, exist_ok=True)
    
    # Custom opener to prevent any user-agent blocking
    opener = urllib.request.build_opener()
    opener.addheaders = [('User-Agent', 'Mozilla/5.0')]
    urllib.request.install_opener(opener)
    
    try:
        urllib.request.urlretrieve(url, dest_path)
        print(f" Saved to {dest_path.name}")
    except Exception as e:
        print(f"❌ Failed to download {url}: {e}")
        sys.exit(1)

def ensure_dependencies(paths):
    if not paths["byte_buddy_jar"].exists():
        download_file(BYTE_BUDDY_URL, paths["byte_buddy_jar"])
    if not paths["ecj_jar"].exists():
        download_file(ECJ_URL, paths["ecj_jar"])

def cmd_build(args):
    print_banner()
    paths = get_paths()
    ensure_dependencies(paths)
    
    # Recreate build dirs
    classes_dir = paths["hook_build"] / "classes"
    if paths["hook_build"].exists():
        import shutil
        shutil.rmtree(paths["hook_build"])
    classes_dir.mkdir(parents=True, exist_ok=True)
    paths["hook_dist"].mkdir(parents=True, exist_ok=True)
    
    # Check if system javac is available
    javac_available = False
    try:
        res = subprocess.run(["javac", "-version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        javac_available = (res.returncode == 0)
    except FileNotFoundError:
        pass
        
    # Check if system java is available
    java_available = False
    try:
        res = subprocess.run(["java", "-version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        java_available = (res.returncode == 0)
    except FileNotFoundError:
        pass

    # Check if Flatpak is available and com.pokemmo.PokeMMO is installed
    is_flatpak = False
    try:
        res = subprocess.run(["flatpak", "info", "com.pokemmo.PokeMMO"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        is_flatpak = (res.returncode == 0)
    except FileNotFoundError:
        pass
    
    compile_cmd = []
    if javac_available:
        print("Compiling Java Agent using system javac...")
        compile_cmd = [
            "javac",
            "-source", "17",
            "-target", "17",
            "-classpath", str(paths["byte_buddy_jar"]),
            "-d", str(classes_dir),
            str(paths["hook_src"])
        ]
    elif java_available:
        print("javac not found on system. Compiling using packaged Eclipse Compiler (ECJ)...")
        compile_cmd = [
            "java",
            "-jar", str(paths["ecj_jar"]),
            "-source", "17",
            "-target", "17",
            "-classpath", str(paths["byte_buddy_jar"]),
            "-d", str(classes_dir),
            str(paths["hook_src"])
        ]
    elif is_flatpak:
        print("Java/javac not found on host. Compiling inside the PokeMMO Flatpak sandbox using ECJ...")
        compile_cmd = [
            "flatpak", "run",
            f"--filesystem={paths['root']}",
            "--command=java",
            "com.pokemmo.PokeMMO",
            "-jar", str(paths["ecj_jar"]),
            "-source", "17",
            "-target", "17",
            "-classpath", str(paths["byte_buddy_jar"]),
            "-d", str(classes_dir),
            str(paths["hook_src"])
        ]
    else:
        print("❌ Error: No Java runtime found! Please install Java (JDK or JRE) or Flatpak to compile the hook.")
        sys.exit(1)
        
    res = subprocess.run(compile_cmd)
    if res.returncode != 0:
        print("❌ Compilation failed!")
        sys.exit(1)
        
    # Write MANIFEST.MF
    manifest_path = paths["hook_build"] / "MANIFEST.MF"
    with open(manifest_path, "w", encoding="utf-8") as f:
        f.write("Manifest-Version: 1.0\n")
        f.write("Premain-Class: io.pokemmo.hook.PokemonStatsAgent\n")
        f.write("Agent-Class: io.pokemmo.hook.PokemonStatsAgent\n")
        f.write(f"Class-Path: ../lib/byte-buddy-{BYTE_BUDDY_VERSION}.jar\n\n")
        
    # Package clean agent JAR using standard python zipfile library
    import zipfile
    print("Packaging hook agent JAR...")
    with zipfile.ZipFile(paths["agent_jar"], 'w', zipfile.ZIP_DEFLATED) as z:
        z.write(manifest_path, 'META-INF/MANIFEST.MF')
        for root, dirs, files in os.walk(classes_dir):
            for file in files:
                file_path = Path(root) / file
                archive_name = file_path.relative_to(classes_dir).as_posix()
                z.write(file_path, archive_name)
                
    print(f"✨ Successfully built agent JAR:")
    print(f"   {paths['agent_jar']}")
    print("==========================================================")

def cmd_launch(args):
    print_banner()
    paths = get_paths()
    
    if not paths["agent_jar"].exists():
        print("⚠️ Agent JAR not found! Building first...")
        cmd_build(args)
        
    paths["hook_logs"].mkdir(parents=True, exist_ok=True)
    
    system = platform.system()
    print(f"Detected OS: {system}")
    
    if system == "Linux":
        # Check for Flatpak PokeMMO
        is_flatpak = False
        try:
            res = subprocess.run(["flatpak", "info", "com.pokemmo.PokeMMO"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            is_flatpak = (res.returncode == 0)
        except FileNotFoundError:
            pass
            
        if is_flatpak:
            print("🚀 Launching PokeMMO via Flatpak with PVP Stats Hook...")
            game_dir = "/home/lemonmantis/.var/app/com.pokemmo.PokeMMO/data/pokemmo-client-live"
            
            # Map paths cleanly inside Flatpak
            flatpak_cmd = [
                "flatpak", "run",
                f"--filesystem={paths['root']}",
                "--command=bash",
                "com.pokemmo.PokeMMO",
                "-c", f"cd {game_dir} && java -javaagent:{paths['agent_jar']} -Dpokemmo.hook.log={paths['log_jsonl']} -Xmx384M -Dfile.encoding=UTF-8 -cp PokeMMO.exe com.pokeemu.client.Client"
            ]
            print(f"Running command: {' '.join(flatpak_cmd)}")
            subprocess.run(flatpak_cmd)
        else:
            # Native Linux PokeMMO fallback
            print("Trying to launch native Linux PokeMMO...")
            native_dir = Path.home() / ".local" / "share" / "PokeMMO"
            if not native_dir.exists():
                native_dir = Path("/usr/share/pokemmo")
                
            if native_dir.exists():
                os.chdir(native_dir)
                native_cmd = [
                    "java",
                    f"-javaagent:{paths['agent_jar']}",
                    f"-Dpokemmo.hook.log={paths['log_jsonl']}",
                    "-Xmx384M", "-Dfile.encoding=UTF-8",
                    "-cp", "PokeMMO.exe",
                    "com.pokeemu.client.Client"
                ]
                print(f"Running native Linux command: {' '.join(native_cmd)}")
                subprocess.run(native_cmd)
            else:
                print("❌ Could not locate PokeMMO installation! Please install it via Flatpak or ensure it resides at ~/.local/share/PokeMMO")
                sys.exit(1)
                
    elif system == "Windows":
        print("🚀 Launching native Windows PokeMMO with PVP Stats Hook...")
        # Common Windows paths
        win_paths = [
            Path(os.environ.get("USERPROFILE", "")) / "AppData" / "Local" / "PokeMMO",
            Path(os.environ.get("PROGRAMFILES", "C:\\Program Files")) / "PokeMMO",
            Path(os.environ.get("PROGRAMFILES(X86)", "C:\\Program Files (x86)")) / "PokeMMO"
        ]
        
        target_dir = None
        for p in win_paths:
            if p.exists():
                target_dir = p
                break
                
        if not target_dir:
            # Check current working directory
            if Path("PokeMMO.exe").exists():
                target_dir = Path(".")
                
        if target_dir:
            print(f"Located installation folder: {target_dir}")
            os.chdir(target_dir)
            
            # Formulate javaw launch array
            # Note: Windows uses semicolons for CP separators if needed
            win_cmd = [
                "java",
                f"-javaagent:{paths['agent_jar']}",
                f"-Dpokemmo.hook.log={paths['log_jsonl']}",
                "-Xmx384M", "-Dfile.encoding=UTF-8",
                "-cp", "PokeMMO.exe",
                "com.pokeemu.client.Client"
            ]
            print(f"Executing: {' '.join(win_cmd)}")
            subprocess.run(win_cmd)
        else:
            print("❌ Could not locate PokeMMO installation directory! Please launch from your PokeMMO installation folder or ensure it is installed in standard AppData paths.")
            sys.exit(1)
            
    else:
        print(f"❌ Unsupported OS: {system}. Dynamic launching is not automated. Please run PokeMMO manually with these VM arguments:")
        print(f"   -javaagent:{paths['agent_jar']} -Dpokemmo.hook.log={paths['log_jsonl']}")

def cmd_sanitize(args):
    print_banner()
    paths = get_paths()
    
    if not paths["log_jsonl"].exists():
        print(f"❌ Log file not found at {paths['log_jsonl']}! Please launch the game first to dump stats.")
        sys.exit(1)
        
    print(f"Sanitizing log file: {paths['log_jsonl'].name}...")
    
    raw_packets = []
    seen_species = set()
    
    with open(paths["log_jsonl"], "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                data = json.loads(line)
                if data.get("event") == "nu_packet":
                    species_id = data.get("speciesId")
                    if species_id in seen_species:
                        raw_packets = [p for p in raw_packets if p.get("speciesId") != species_id]
                    seen_species.add(species_id)
                    raw_packets.append(data)
            except json.JSONDecodeError:
                continue

    print(f"Processing {len(raw_packets)} unique Pokémon records...")
    
    sanitized_list = []
    for p in raw_packets:
        usage = p.get("overallUsageCount", 0)
        wins = p.get("wins", 0)
        matches = p.get("overallMatches", 0)
        
        win_rate = round((wins / usage * 100.0), 2) if usage > 0 else 0.0
        usage_rate = round((usage / matches * 100.0), 2) if matches > 0 else 0.0
        
        def clean_entries(entries):
            cleaned = []
            for entry in entries:
                entry_id = entry.get("id")
                original_name = entry.get("name", "Unknown")
                
                cleaned.append({
                    "id": entry_id,
                    "name": original_name,
                    "count": entry.get("count", 0),
                    "percent": round(entry.get("percent", 0.0), 2)
                })
            return cleaned

        sanitized_list.append({
            "id": p.get("speciesId"),
            "name": p.get("speciesName"),
            "winRate": win_rate,
            "usageRate": usage_rate,
            "wins": wins,
            "overallUsageCount": usage,
            "overallMatches": matches,
            "matchmaking": {
                "matches": p.get("matchmakingMatches", 0),
                "usage": p.get("matchmakingUsageCount", 0)
            },
            "tournament": {
                "matches": p.get("tournamentMatches", 0),
                "usage": p.get("tournamentUsageCount", 0)
            },
            "topItems": clean_entries(p.get("topItems", [])),
            "topNatures": clean_entries(p.get("topNatures", [])),
            "topAbilities": clean_entries(p.get("topAbilities", [])),
            "commonAllies": clean_entries(p.get("commonAllies", []))
        })

    # Sort descending by usage and assign rank
    sanitized_list.sort(key=lambda x: x["overallUsageCount"], reverse=True)
    for rank, p in enumerate(sanitized_list, 1):
        p["rank"] = rank

    # Create O(1) keyed maps
    by_name = {p["name"].lower(): p for p in sanitized_list}
    by_id = {str(p["id"]): p for p in sanitized_list}

    final_payload = {
        "metadata": {
            "totalSpeciesWithStats": len(sanitized_list),
            "totalMatchesRecorded": max([p.get("overallMatches", 0) for p in sanitized_list]) if sanitized_list else 0,
            "generatedAt": "2026-05-21T05:00:00Z"
        },
        "statsList": sanitized_list,
        "statsByName": by_name,
        "statsById": by_id
    }

    with open(paths["log_json"], "w", encoding="utf-8") as out:
        json.dump(final_payload, out, indent=2, ensure_ascii=False)
        
    print(f"✨ Successfully saved sanitized dataset to:")
    print(f"   {paths['log_json']}")
    print("==========================================================")

def cmd_clean(args):
    print_banner()
    paths = get_paths()
    import shutil
    
    print("Cleaning workspace files...")
    
    if paths["hook_build"].exists():
        shutil.rmtree(paths["hook_build"])
        print(" Removed hook/build/")
        
    if paths["hook_dist"].exists():
        shutil.rmtree(paths["hook_dist"])
        print(" Removed hook/dist/")
        
    # Ask if they want to delete logs too
    delete_logs = True
    if "--keep-logs" in args:
        delete_logs = False
        
    if delete_logs and paths["hook_logs"].exists():
        shutil.rmtree(paths["hook_logs"])
        print(" Removed hook/logs/")
        
    print("✨ Clean complete.")
    print("==========================================================")

def show_help():
    print_banner()
    print("Usage: python run.py <command> [options]\n")
    print("Commands:")
    print("  build             Download missing dependencies, compile, and package the Java agent.")
    print("  launch            Compile agent (if needed) and run PokeMMO with agent attached.")
    print("  sanitize          Convert raw JSONL telemetry into dashboard-ready, sorted, enhanced JSON.")
    print("  clean             Prune target build directories, class files, and intermediate artifacts.")
    print("                    (Use '--keep-logs' to preserve dumped statistics files)")
    print("  help              Show this help menu.")
    print("==========================================================")

def main():
    if len(sys.argv) < 2:
        show_help()
        sys.exit(0)
        
    command = sys.argv[1].lower()
    args = sys.argv[2:]
    
    if command == "build":
        cmd_build(args)
    elif command == "launch" or command == "run":
        cmd_launch(args)
    elif command == "sanitize":
        cmd_sanitize(args)
    elif command == "clean":
        cmd_clean(args)
    elif command in ("help", "-h", "--help"):
        show_help()
    else:
        print(f"❌ Unknown command: {command}")
        show_help()
        sys.exit(1)

if __name__ == "__main__":
    main()
