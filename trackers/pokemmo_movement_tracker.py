import argparse
import csv
import email.utils
import html
import json
import re
import sys
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple
from urllib.parse import quote, urljoin

import requests


BASE_URL = "https://forums.pokemmo.com"
SEARCH_URL = (
    BASE_URL
    + "/index.php?/search/&q={query}&type=forums_topic&updated_after=any"
    + "&sortby=newest&search_and_or=or&search_in=titles&page={page}"
)
SEARCH_TERMS = [
    "usage movements",
    "movement",
]
TOPIC_TITLE_RE = re.compile(
    r"^(January|February|March|April|May|June|July|August|September|October|November|December)"
    r"[- ]\d{4}\s+"
    r"(Usage Movements|Movement Thread|Movements|Movement Discussion Thread)$",
    re.IGNORECASE,
)
TOPIC_LINK_RE = re.compile(
    r'<li class="ipsStreamItem[\s\S]*?<h2 data-ips-hook="commentTitle">\s*'
    r'<a href="([^"]+)"[^>]*data-searchable[^>]*>([\s\S]*?)</a>[\s\S]*?'
    r'<div class="ipsStreamItem__summary">([\s\S]*?)</div>',
    re.IGNORECASE,
)
ARTICLE_RE = re.compile(r'<article[^>]+id="elComment_(\d+)"[\s\S]*?</article>', re.IGNORECASE)
CONTENT_RE = re.compile(
    r'<div[^>]+data-role="commentContent"[^>]*>([\s\S]*?)</div>\s*(?:</div>\s*)?<div class="ipsEntry__footer">',
    re.IGNORECASE,
)
CONTENT_FALLBACK_RE = re.compile(
    r'<div[^>]+data-role="commentContent"[^>]*>([\s\S]*?)</div>\s*(?:</div>\s*)*</article>',
    re.IGNORECASE,
)
AUTHOR_RE = re.compile(r'class="ipsUsername[^"]*"[^>]*>(.*?)</a>', re.IGNORECASE)
TIME_RE = re.compile(r"<time datetime=['\"]([^'\"]+)['\"]", re.IGNORECASE)
TABLE_RE = re.compile(r"<table[\s\S]*?</table>", re.IGNORECASE)
ROW_RE = re.compile(r"<tr[\s\S]*?</tr>", re.IGNORECASE)
CELL_RE = re.compile(r"<t[dh][^>]*>([\s\S]*?)</t[dh]>", re.IGNORECASE)
TAG_RE = re.compile(r"<[^>]+>")
SPACE_RE = re.compile(r"\s+")
TOPIC_ID_RE = re.compile(r"/topic/(\d+)-")
MOVE_LINE_RE = re.compile(r"([A-Za-z0-9'’.\- ]+?)\s+(\d+(?:\.\d+)?)%")
SECTION_RE = re.compile(
    r"To\s+[A-Z0-9]+(?:/[A-Z0-9]+)?\s+from\s+[A-Z0-9]+(?:/[A-Z0-9]+)?\:?",
    re.IGNORECASE,
)


def clean_text(raw: str) -> str:
    text = raw
    text = re.sub(r"<br\s*/?>", "\n", text, flags=re.IGNORECASE)
    text = re.sub(r"</p>", "\n", text, flags=re.IGNORECASE)
    text = re.sub(r"</tr>", "\n", text, flags=re.IGNORECASE)
    text = re.sub(r"</td>", " | ", text, flags=re.IGNORECASE)
    text = re.sub(r"</th>", " | ", text, flags=re.IGNORECASE)
    text = TAG_RE.sub(" ", text)
    text = html.unescape(text)
    text = text.replace("\xa0", " ")
    text = re.sub(r"[ \t]+\n", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    text = SPACE_RE.sub(" ", text)
    text = re.sub(r" ?\n ?", "\n", text)
    return text.strip()


def canonical_topic_url(href: str) -> str:
    href = html.unescape(href).replace("&amp;", "&")
    if href.startswith("/"):
        href = urljoin(BASE_URL, href)
    if "&do=findComment" in href:
        href = href.split("&do=findComment", 1)[0]
    return href


def topic_id_from_url(url: str) -> str:
    match = TOPIC_ID_RE.search(url)
    if not match:
        raise ValueError(f"Could not parse topic id from URL: {url}")
    return match.group(1)


def topic_title_ok(title: str) -> bool:
    return bool(TOPIC_TITLE_RE.match(title.strip()))


def normalize_section(section: str) -> str:
    return section.strip().rstrip(":").strip()


def normalize_pokemon_name(name: str) -> str:
    normalized = SPACE_RE.sub(" ", name).strip(" :-|")
    normalized = re.sub(r"^(usage in (ou|uu|nu)\s+)", "", normalized, flags=re.IGNORECASE)
    return normalized


@dataclass
class SearchHit:
    topic_id: str
    title: str
    url: str
    source_summary: str


class MovementTracker:
    def __init__(self, session: requests.Session, max_pages: int) -> None:
        self.session = session
        self.max_pages = max_pages
        self.request_delay_seconds = 1.0
        self.search_delay_seconds = 10.5
        self._last_search_request_at = 0.0

    def get(self, url: str) -> str:
        if "/search/" in url:
            elapsed = time.time() - self._last_search_request_at
            if elapsed < self.search_delay_seconds:
                time.sleep(self.search_delay_seconds - elapsed)
        last_error: Optional[Exception] = None
        for attempt in range(5):
            response = self.session.get(url, timeout=30)
            if response.status_code != 429:
                response.raise_for_status()
                if "/search/" in url:
                    self._last_search_request_at = time.time()
                time.sleep(self.request_delay_seconds)
                return response.text
            retry_after = response.headers.get("Retry-After")
            sleep_seconds = self._retry_after_seconds(retry_after, attempt)
            time.sleep(sleep_seconds)
            last_error = requests.HTTPError(f"429 Too Many Requests for {url}", response=response)
        if last_error:
            raise last_error
        raise RuntimeError(f"Failed to fetch {url}")

    @staticmethod
    def _retry_after_seconds(retry_after: Optional[str], attempt: int) -> float:
        if not retry_after:
            return 4 + (attempt * 3)
        if retry_after.isdigit():
            return float(retry_after)
        retry_dt = email.utils.parsedate_to_datetime(retry_after)
        return max(1.0, retry_dt.timestamp() - time.time())

    def discover_topics(self) -> List[SearchHit]:
        found: Dict[str, SearchHit] = {}
        for term in SEARCH_TERMS:
            for page in range(1, self.max_pages + 1):
                url = SEARCH_URL.format(query=quote(term), page=page)
                page_html = self.get(url)
                hits = self._parse_search_page(page_html)
                if not hits:
                    break
                before = len(found)
                for hit in hits:
                    found.setdefault(hit.topic_id, hit)
                if len(found) == before and page > 2:
                    break
        return sorted(
            found.values(),
            key=lambda hit: self._sort_key(hit.title),
        )

    def _parse_search_page(self, page_html: str) -> List[SearchHit]:
        hits: List[SearchHit] = []
        for href, raw_title, raw_summary in TOPIC_LINK_RE.findall(page_html):
            title = clean_text(raw_title)
            summary = clean_text(raw_summary)
            url = canonical_topic_url(href)
            if not topic_title_ok(title):
                continue
            if "competition alley" not in summary.lower() and "competition archive" not in summary.lower():
                continue
            topic_id = topic_id_from_url(url)
            hits.append(SearchHit(topic_id=topic_id, title=title, url=url, source_summary=summary))
        return hits

    def fetch_topic(self, hit: SearchHit) -> Dict:
        page_html = self.get(hit.url)
        comments = []
        for article_html in ARTICLE_RE.findall(page_html):
            pass
        article_blocks = ARTICLE_RE.finditer(page_html)
        for article_match in article_blocks:
            comment_id = article_match.group(1)
            article_html = article_match.group(0)
            content_match = CONTENT_RE.search(article_html)
            if not content_match:
                content_match = CONTENT_FALLBACK_RE.search(article_html)
            if not content_match:
                continue
            content_html = content_match.group(1)
            author_match = AUTHOR_RE.search(article_html)
            time_match = TIME_RE.search(article_html)
            content_text = clean_text(content_html)
            tables = self._parse_tables(content_html)
            comments.append(
                {
                    "comment_id": comment_id,
                    "author": clean_text(author_match.group(1)) if author_match else None,
                    "posted_at": time_match.group(1) if time_match else None,
                    "content_text": content_text,
                    "tables": tables,
                    "moves": self._extract_moves(content_text, tables),
                }
            )
        return {
            "topic_id": hit.topic_id,
            "title": hit.title,
            "url": hit.url,
            "source_summary": hit.source_summary,
            "comments": comments,
        }

    def _parse_tables(self, content_html: str) -> List[Dict]:
        parsed_tables: List[Dict] = []
        for table_html in TABLE_RE.findall(content_html):
            current_section: Optional[str] = None
            current_entries: List[Dict] = []
            for row_html in ROW_RE.findall(table_html):
                cells = [clean_text(cell) for cell in CELL_RE.findall(row_html)]
                cells = [cell for cell in cells if cell]
                if not cells:
                    continue

                row_label = cells[0]
                if SECTION_RE.fullmatch(row_label):
                    if current_section:
                        parsed_tables.append({"section": current_section, "entries": current_entries})
                    current_section = normalize_section(row_label)
                    current_entries = []
                    continue

                if len(cells) >= 2 and SECTION_RE.fullmatch(cells[0]) and not any(cells[1:]):
                    if current_section:
                        parsed_tables.append({"section": current_section, "entries": current_entries})
                    current_section = normalize_section(cells[0])
                    current_entries = []
                    continue

                if current_section and len(cells) >= 2:
                    current_entries.append({"name": normalize_pokemon_name(cells[0]), "value": cells[1]})
                elif current_section and len(cells) == 1:
                    current_entries.append({"name": normalize_pokemon_name(cells[0]), "value": None})

            if current_section:
                parsed_tables.append({"section": current_section, "entries": current_entries})
        return parsed_tables

    def _extract_moves(self, content_text: str, tables: List[Dict]) -> List[Dict]:
        if tables:
            moves = []
            for table in tables:
                if not SECTION_RE.fullmatch(table["section"]):
                    continue
                for entry in table["entries"]:
                    percent = self._parse_percent(entry["value"])
                    moves.append(
                        {
                            "section": normalize_section(table["section"]),
                            "pokemon": normalize_pokemon_name(entry["name"]),
                            "usage_percent": percent,
                            "raw_value": entry["value"],
                        }
                    )
            return moves

        matches = list(SECTION_RE.finditer(content_text))
        if not matches:
            return []

        sections: List[Dict] = []
        for index, match in enumerate(matches):
            start = match.end()
            end = matches[index + 1].start() if index + 1 < len(matches) else len(content_text)
            section = normalize_section(match.group(0))
            body = content_text[start:end].replace("|", " ")
            for name, percent in MOVE_LINE_RE.findall(body):
                name = normalize_pokemon_name(name)
                if name.lower() == "usage":
                    continue
                sections.append(
                    {
                        "section": section,
                        "pokemon": name,
                        "usage_percent": float(percent),
                        "raw_value": f"{percent}%",
                    }
                )
        return sections

    @staticmethod
    def _parse_percent(value: Optional[str]) -> Optional[float]:
        if not value:
            return None
        match = re.search(r"(\d+(?:\.\d+)?)%", value)
        return float(match.group(1)) if match else None

    @staticmethod
    def _sort_key(title: str) -> Tuple[int, int]:
        month_map = {
            "january": 1,
            "february": 2,
            "march": 3,
            "april": 4,
            "may": 5,
            "june": 6,
            "july": 7,
            "august": 8,
            "september": 9,
            "october": 10,
            "november": 11,
            "december": 12,
        }
        match = re.match(r"^(?P<month>[A-Za-z]+)[- ](?P<year>\d{4})", title)
        if not match:
            return (0, 0)
        return (int(match.group("year")), month_map[match.group("month").lower()])


def flatten_moves(topics: Iterable[Dict]) -> List[Dict]:
    rows: List[Dict] = []
    for topic in topics:
        for comment in topic.get("comments", []):
            for move in comment.get("moves", []):
                rows.append(
                    {
                        "topic_id": topic["topic_id"],
                        "title": topic["title"],
                        "url": topic["url"],
                        "comment_id": comment["comment_id"],
                        "author": comment["author"],
                        "posted_at": comment["posted_at"],
                        "section": move["section"],
                        "pokemon": move["pokemon"],
                        "usage_percent": move["usage_percent"],
                        "raw_value": move["raw_value"],
                    }
                )
    return rows


def write_json(path: Path, payload: Dict) -> None:
    text = json.dumps(payload, indent=2, ensure_ascii=False)
    try:
        path.write_text(text, encoding="utf-8")
    except PermissionError:
        fallback_path = versioned_fallback_path(path)
        fallback_path.write_text(text, encoding="utf-8")
        print(f"Could not overwrite {path}; wrote {fallback_path} instead")


def write_csv(path: Path, rows: List[Dict]) -> None:
    fieldnames = [
        "topic_id",
        "title",
        "url",
        "comment_id",
        "author",
        "posted_at",
        "section",
        "pokemon",
        "usage_percent",
        "raw_value",
    ]
    try:
        with path.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(rows)
    except PermissionError:
        fallback_path = versioned_fallback_path(path)
        with fallback_path.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(rows)
        print(f"Could not overwrite {path}; wrote {fallback_path} instead")


def versioned_fallback_path(path: Path) -> Path:
    timestamp = datetime.utcnow().strftime("%Y%m%dT%H%M%SZ")
    return path.with_name(f"{path.stem}_{timestamp}{path.suffix}")


def load_previous_snapshot(path: Path) -> Optional[Dict]:
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def build_changes(previous: Optional[Dict], current: Dict) -> Dict:
    previous_topics = {topic["topic_id"]: topic for topic in (previous or {}).get("topics", [])}
    current_topics = {topic["topic_id"]: topic for topic in current.get("topics", [])}

    new_topics = [current_topics[tid] for tid in current_topics.keys() - previous_topics.keys()]
    removed_topics = [previous_topics[tid] for tid in previous_topics.keys() - current_topics.keys()]
    updated_topics = []

    for topic_id in current_topics.keys() & previous_topics.keys():
        old = previous_topics[topic_id]
        new = current_topics[topic_id]
        old_comment_ids = [comment["comment_id"] for comment in old.get("comments", [])]
        new_comment_ids = [comment["comment_id"] for comment in new.get("comments", [])]
        if old_comment_ids != new_comment_ids or old.get("comments") != new.get("comments"):
            updated_topics.append(
                {
                    "topic_id": topic_id,
                    "title": new["title"],
                    "url": new["url"],
                    "old_comment_count": len(old.get("comments", [])),
                    "new_comment_count": len(new.get("comments", [])),
                    "new_comment_ids": [cid for cid in new_comment_ids if cid not in old_comment_ids],
                }
            )

    return {
        "new_topics": new_topics,
        "removed_topics": removed_topics,
        "updated_topics": updated_topics,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Track PokeMMO movement forum topics.")
    parser.add_argument(
        "--outdir",
        type=Path,
        default=Path.cwd() / "pokemmo_movement_tracker_output",
        help="Directory for JSON/CSV outputs.",
    )
    parser.add_argument(
        "--max-pages",
        type=int,
        default=5,
        help="How many search result pages to scan per query.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    args.outdir.mkdir(parents=True, exist_ok=True)

    session = requests.Session()
    session.headers.update({"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"})

    tracker = MovementTracker(session=session, max_pages=args.max_pages)
    topics = tracker.discover_topics()
    topic_payloads = [tracker.fetch_topic(hit) for hit in topics]

    snapshot = {
        "source": BASE_URL,
        "search_terms": SEARCH_TERMS,
        "topic_count": len(topic_payloads),
        "topics": topic_payloads,
    }

    snapshot_path = args.outdir / "movements_snapshot.json"
    changes_path = args.outdir / "movements_changes.json"
    csv_path = args.outdir / "movements_flat.csv"

    previous = load_previous_snapshot(snapshot_path)
    changes = build_changes(previous, snapshot)
    flat_rows = flatten_moves(topic_payloads)

    write_json(snapshot_path, snapshot)
    write_json(changes_path, changes)
    write_csv(csv_path, flat_rows)

    print(f"Saved {len(topic_payloads)} topics to {snapshot_path}")
    print(f"Saved {len(flat_rows)} move rows to {csv_path}")
    print(
        "Changes:"
        f" new_topics={len(changes['new_topics'])}"
        f" updated_topics={len(changes['updated_topics'])}"
        f" removed_topics={len(changes['removed_topics'])}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
