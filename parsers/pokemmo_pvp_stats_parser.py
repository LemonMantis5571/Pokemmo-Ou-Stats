#!/usr/bin/env python3
"""
Decode the PokeMMO inbound PvP Pokemon stats packet ("nu").

Reverse-engineered mappings:
- inbound opcode 95  -> f.nu
- outbound opcode 79 -> f.Te0 (month, tier, species id)
- outbound opcode 117 -> f.xU1

The `nu` payload is the already-decoded packet body after the opcode byte.
This tool is useful once you have any decrypted payload source, such as:
- an in-memory dump
- an instrumented client log
- a post-decode hook
"""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import List


@dataclass
class CountEntry:
    id: int
    count: int


@dataclass
class NuPacket:
    species_id: int
    overall_matches: int
    overall_usage_count: int
    wins: int
    tournament_matches: int
    tournament_usage_count: int
    matchmaking_matches: int
    matchmaking_usage_count: int
    top_items: List[CountEntry]
    top_natures: List[CountEntry]
    top_abilities: List[CountEntry]
    common_allies: List[CountEntry]


class Cursor:
    def __init__(self, data: bytes) -> None:
        self.data = data
        self.pos = 0

    def _read(self, n: int) -> bytes:
        end = self.pos + n
        if end > len(self.data):
            raise ValueError(
                f"Unexpected end of payload at offset {self.pos}, "
                f"needed {n} more bytes"
            )
        chunk = self.data[self.pos:end]
        self.pos = end
        return chunk

    def u8(self) -> int:
        return int.from_bytes(self._read(1), "little", signed=False)

    def i32(self) -> int:
        return int.from_bytes(self._read(4), "little", signed=True)

    def u16(self) -> int:
        return int.from_bytes(self._read(2), "little", signed=False)


def parse_short_count_array(cur: Cursor) -> List[CountEntry]:
    size = cur.u8()
    items: List[CountEntry] = []
    for _ in range(size):
        items.append(CountEntry(id=cur.u16(), count=cur.i32()))
    return items


def parse_byte_count_array(cur: Cursor) -> List[CountEntry]:
    size = cur.u8()
    items: List[CountEntry] = []
    for _ in range(size):
        items.append(CountEntry(id=cur.u8(), count=cur.i32()))
    return items


def parse_nu_payload(payload: bytes) -> NuPacket:
    cur = Cursor(payload)

    species_id = cur.u16()
    overall_matches = cur.i32()
    overall_usage_count = cur.i32()
    wins = cur.i32()
    tournament_matches = cur.i32()
    tournament_usage_count = cur.i32()

    matchmaking_matches = overall_matches - tournament_matches
    matchmaking_usage_count = overall_usage_count - tournament_usage_count

    top_items = parse_short_count_array(cur)
    top_natures = parse_byte_count_array(cur)
    top_abilities = parse_short_count_array(cur)
    common_allies = parse_short_count_array(cur)

    if cur.pos != len(cur.data):
        raise ValueError(
            f"Trailing bytes detected: parsed {cur.pos} of {len(cur.data)} bytes"
        )

    return NuPacket(
        species_id=species_id,
        overall_matches=overall_matches,
        overall_usage_count=overall_usage_count,
        wins=wins,
        tournament_matches=tournament_matches,
        tournament_usage_count=tournament_usage_count,
        matchmaking_matches=matchmaking_matches,
        matchmaking_usage_count=matchmaking_usage_count,
        top_items=top_items,
        top_natures=top_natures,
        top_abilities=top_abilities,
        common_allies=common_allies,
    )


def normalize_hex(text: str) -> bytes:
    hex_chars = "".join(ch for ch in text if ch not in " \t\r\n:-")
    if len(hex_chars) % 2:
        raise ValueError("Hex input must contain an even number of hex digits")
    return bytes.fromhex(hex_chars)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Decode a PokeMMO inbound PvP stats packet (nu / opcode 95)."
    )
    parser.add_argument(
        "--hex",
        help="Hex string for the payload body only, without the opcode byte",
    )
    parser.add_argument(
        "--hex-file",
        type=Path,
        help="Text file containing hex for the payload body only",
    )
    parser.add_argument(
        "--bin-file",
        type=Path,
        help="Binary file containing the payload body only",
    )
    parser.add_argument(
        "--pretty",
        action="store_true",
        help="Pretty-print JSON output",
    )
    args = parser.parse_args()

    provided = [args.hex is not None, args.hex_file is not None, args.bin_file is not None]
    if sum(provided) != 1:
        parser.error("Provide exactly one of --hex, --hex-file, or --bin-file")

    if args.hex is not None:
        payload = normalize_hex(args.hex)
    elif args.hex_file is not None:
        payload = normalize_hex(args.hex_file.read_text(encoding="utf-8"))
    else:
        payload = args.bin_file.read_bytes()

    decoded = parse_nu_payload(payload)
    indent = 2 if args.pretty else None
    print(json.dumps(asdict(decoded), indent=indent))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
