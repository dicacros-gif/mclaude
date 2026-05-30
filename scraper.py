#!/usr/bin/env python3
"""
Midjourney Explore Crawler
Scrapes top images, styles, and videos from Midjourney's public explore page.
New items are prepended to data/metadata.json (newest-first order).
"""

import json
import os
import re
import time
import random
from datetime import datetime, timezone
from pathlib import Path

from playwright.sync_api import sync_playwright, Page, BrowserContext, Response

# ── Configuration ──────────────────────────────────────────────────────────────

TABS = [
    {
        "name": "images",
        "url": "https://www.midjourney.com/explore?tab=top",
        "type": "image",
    },
    {
        "name": "styles",
        "url": "https://www.midjourney.com/explore?tab=top_kits",
        "type": "style",
    },
    {
        "name": "videos",
        "url": "https://www.midjourney.com/explore?tab=top_videos",
        "type": "video",
    },
]

DATA_DIR = Path("data")
METADATA_FILE = DATA_DIR / "metadata.json"
MAX_ITEMS_PER_TAB = 100
SCROLL_COUNT = 20
SCROLL_DELAY = 2.0

# CDN domains used by Midjourney
CDN_PATTERNS = [
    "cdn.midjourney.com",
    "midjourney-video",
    "imagecache.midjourney.com",
]

# ── Data helpers ───────────────────────────────────────────────────────────────


def load_metadata() -> list:
    if METADATA_FILE.exists():
        try:
            return json.loads(METADATA_FILE.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, IOError):
            return []
    return []


def save_metadata(items: list) -> None:
    DATA_DIR.mkdir(exist_ok=True)
    METADATA_FILE.write_text(
        json.dumps(items, indent=2, ensure_ascii=False), encoding="utf-8"
    )


def make_id(url: str) -> str:
    """Derive a stable ID from a CDN URL (extracts job UUID when possible)."""
    m = re.search(r"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})", url)
    return m.group(1) if m else str(abs(hash(url)))


def deduplicate(items: list) -> list:
    seen, result = set(), []
    for item in items:
        if item["id"] not in seen:
            seen.add(item["id"])
            result.append(item)
    return result


def is_content_url(url: str) -> bool:
    return any(p in url for p in CDN_PATTERNS) and bool(
        re.search(r"\.(jpg|jpeg|png|webp|gif|mp4|webm)", url, re.I)
    )


# ── API response parser ────────────────────────────────────────────────────────


def parse_api_response(data, item_type: str, crawled_at: str) -> list:
    """Extract items from an unknown Midjourney API JSON response."""
    items = []

    def process(obj):
        if not isinstance(obj, dict):
            return

        item_id = (
            obj.get("id")
            or obj.get("jobId")
            or obj.get("job_id")
            or obj.get("uuid")
            or ""
        )
        if not item_id:
            return
        item_id = str(item_id)

        image_url = (
            obj.get("imageUrl")
            or obj.get("image_url")
            or obj.get("cdnUrl")
            or obj.get("cdn_url")
            or obj.get("url")
            or ""
        )
        video_url = obj.get("videoUrl") or obj.get("video_url") or ""
        poster_url = (
            obj.get("poster")
            or obj.get("thumbnailUrl")
            or obj.get("thumbnail_url")
            or image_url
        )

        if item_type == "video":
            actual_url = video_url or image_url
            actual_type = "video"
        else:
            actual_url = image_url
            actual_type = item_type

        if not actual_url:
            return

        prompt = obj.get("prompt") or obj.get("text") or obj.get("description") or ""
        if isinstance(prompt, list):
            prompt = " ".join(str(p) for p in prompt)

        user = obj.get("user", {})
        username = (
            obj.get("username")
            or (user.get("username") if isinstance(user, dict) else "")
            or ""
        )

        likes = (
            obj.get("likes")
            or obj.get("reactions")
            or obj.get("score")
            or obj.get("ranking_score")
            or 0
        )

        link = obj.get("link") or f"https://www.midjourney.com/jobs/{item_id}"

        entry = {
            "id": item_id,
            "type": actual_type,
            "url": actual_url,
            "link": link,
            "prompt": str(prompt)[:500],
            "username": str(username)[:100],
            "likes": likes,
            "crawled_at": crawled_at,
        }
        if item_type == "video" and poster_url:
            entry["poster"] = poster_url

        items.append(entry)

    # Unwrap common envelope structures
    if isinstance(data, list):
        for item in data:
            process(item)
    elif isinstance(data, dict):
        found = False
        for key in ("items", "jobs", "data", "results", "feed", "explore", "content", "images"):
            if key in data and isinstance(data[key], list):
                for item in data[key]:
                    process(item)
                found = True
                break
        if not found:
            # Brute-force: find any list of dicts inside the response
            for value in data.values():
                if isinstance(value, list) and value and isinstance(value[0], dict):
                    for item in value:
                        process(item)
                    break

    return items


# ── DOM fallback extraction ────────────────────────────────────────────────────


def extract_from_dom(page: Page, item_type: str, crawled_at: str) -> list:
    """Fallback: extract URLs directly from rendered DOM elements."""
    items = []

    if item_type in ("image", "style"):
        srcs = page.evaluate("""
            () => Array.from(document.querySelectorAll('img'))
                .map(img => ({
                    src: img.src || img.getAttribute('data-src') || '',
                    alt: img.alt || '',
                    link: img.closest('a') ? img.closest('a').href : ''
                }))
                .filter(o => o.src && o.src.includes('cdn.midjourney.com'))
        """)
        for obj in srcs:
            url = obj.get("src", "")
            if not url or not is_content_url(url):
                continue
            items.append({
                "id": make_id(url),
                "type": item_type,
                "url": url,
                "link": obj.get("link", ""),
                "prompt": obj.get("alt", ""),
                "username": "",
                "likes": 0,
                "crawled_at": crawled_at,
            })

    elif item_type == "video":
        srcs = page.evaluate("""
            () => {
                const results = [];
                document.querySelectorAll('video').forEach(v => {
                    const src = v.src || (v.querySelector('source') ? v.querySelector('source').src : '');
                    const poster = v.poster || '';
                    const link = v.closest('a') ? v.closest('a').href : '';
                    if (src) results.push({ src, poster, link });
                });
                return results;
            }
        """)
        for obj in srcs:
            url = obj.get("src", "")
            if not url:
                continue
            entry = {
                "id": make_id(url),
                "type": "video",
                "url": url,
                "link": obj.get("link", ""),
                "prompt": "",
                "username": "",
                "likes": 0,
                "crawled_at": crawled_at,
            }
            if obj.get("poster"):
                entry["poster"] = obj["poster"]
            items.append(entry)

    return deduplicate(items)


# ── Tab scraper ────────────────────────────────────────────────────────────────


def scrape_tab(context: BrowserContext, tab: dict, existing_ids: set) -> list:
    """Scrape one tab; return list of new items not in existing_ids."""
    crawled_at = datetime.now(timezone.utc).isoformat()
    api_items: list = []

    page = context.new_page()

    def on_response(resp: Response):
        try:
            if resp.status != 200:
                return
            ct = resp.headers.get("content-type", "")
            if "json" not in ct:
                return
            url = resp.url
            relevant_keywords = [
                "explore", "jobs/search", "feed", "/pg/", "v1/imagine",
                "api/app", "recent", "ranking",
            ]
            if not any(k in url for k in relevant_keywords):
                return
            data = resp.json()
            parsed = parse_api_response(data, tab["type"], crawled_at)
            if parsed:
                print(f"    API hit: {url[:80]} → {len(parsed)} items")
            api_items.extend(parsed)
        except Exception:
            pass

    page.on("response", on_response)

    try:
        print(f"  Loading: {tab['url']}")
        page.goto(tab["url"], wait_until="domcontentloaded", timeout=90000)
        time.sleep(4 + random.uniform(0, 2))

        # Incremental scroll to trigger lazy-load
        for i in range(SCROLL_COUNT):
            page.evaluate("window.scrollBy(0, window.innerHeight * 1.5)")
            time.sleep(SCROLL_DELAY + random.uniform(0, 0.8))
            if (i + 1) % 5 == 0:
                print(f"    Scrolled {i+1}/{SCROLL_COUNT} …")

        # Give a moment for final requests to complete
        time.sleep(2)

        # DOM fallback
        dom_items = extract_from_dom(page, tab["type"], crawled_at)
        print(f"  DOM fallback: {len(dom_items)} items")

    except Exception as exc:
        print(f"  ERROR scraping {tab['name']}: {exc}")
        dom_items = []
    finally:
        page.close()

    combined = deduplicate(api_items + dom_items)
    new_items = [it for it in combined if it["id"] not in existing_ids]
    return new_items[:MAX_ITEMS_PER_TAB]


# ── Main ───────────────────────────────────────────────────────────────────────


def main() -> int:
    print("=" * 50)
    print("Midjourney Explore Crawler")
    print(f"Started: {datetime.now(timezone.utc).isoformat()}")
    print("=" * 50)

    existing = load_metadata()
    existing_ids = {item["id"] for item in existing}
    print(f"Loaded {len(existing)} existing items\n")

    all_new: list = []

    with sync_playwright() as pw:
        browser = pw.chromium.launch(
            headless=True,
            args=[
                "--no-sandbox",
                "--disable-setuid-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--no-first-run",
                "--no-zygote",
                "--single-process",
            ],
        )

        context = browser.new_context(
            user_agent=(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/125.0.0.0 Safari/537.36"
            ),
            viewport={"width": 1920, "height": 1080},
            locale="en-US",
            timezone_id="America/New_York",
            extra_http_headers={
                "Accept-Language": "en-US,en;q=0.9",
            },
        )

        # Optional: load cookies if provided via environment
        cookie_json = os.environ.get("MJ_COOKIES", "")
        if cookie_json:
            try:
                cookies = json.loads(cookie_json)
                context.add_cookies(cookies)
                print("Loaded auth cookies from MJ_COOKIES env\n")
            except Exception as exc:
                print(f"Warning: could not parse MJ_COOKIES: {exc}\n")

        for tab in TABS:
            print(f"\n[{tab['name'].upper()}]")
            new_items = scrape_tab(context, tab, existing_ids)
            print(f"  → {len(new_items)} new items")
            all_new.extend(new_items)
            existing_ids.update(it["id"] for it in new_items)
            time.sleep(5 + random.uniform(0, 3))

        browser.close()

    if all_new:
        merged = all_new + existing  # new items on top
        save_metadata(merged)
        print(f"\nSaved {len(all_new)} new items  (total: {len(merged)})")
    else:
        print("\nNo new items found — metadata unchanged")

    print(f"\nCompleted: {datetime.now(timezone.utc).isoformat()}")
    return len(all_new)


if __name__ == "__main__":
    raise SystemExit(0 if main() >= 0 else 1)
