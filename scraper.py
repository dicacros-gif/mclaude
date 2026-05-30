#!/usr/bin/env python3
"""
Midjourney Explore Crawler
- 3개 탭(images / styles / videos) 크롤링
  * images:  공개 탭 - 인증 불필요
  * styles / videos: MJ_COOKIES 환경변수로 인증 시 활성화
- HTML background-image 패턴으로 URL 추출
- 브라우저 response 캡처로 미디어 파일 다운로드 (best-effort)
- data/metadata.json 앞에 신규 항목 추가 (기존 보존)
"""

import base64, json, os, re, time, random, urllib.parse
from datetime import datetime, timezone
from pathlib import Path

import requests
from playwright.sync_api import sync_playwright, BrowserContext

# ── 설정 ──────────────────────────────────────────────────────────────────────

TABS = [
    {"name": "images",  "url": "https://www.midjourney.com/explore?tab=top",         "type": "image",  "auth": False},
    {"name": "styles",  "url": "https://www.midjourney.com/explore?tab=top_kits",    "type": "style",  "auth": True},
    {"name": "videos",  "url": "https://www.midjourney.com/explore?tab=top_videos",  "type": "video",  "auth": True},
]

DATA_DIR  = Path("data")
MEDIA_DIR = Path("media")
META_FILE = DATA_DIR / "metadata.json"

MAX_PER_TAB   = 50
SCROLL_COUNT  = 20
SCROLL_DELAY  = 1.8
MAX_IMG_BYTES = 10 * 1024 * 1024   # 10 MB
MAX_VID_BYTES = 80 * 1024 * 1024   # 80 MB

DL_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/125.0.0.0 Safari/537.36"
    ),
    "Referer": "https://www.midjourney.com/",
}

# ── 데이터 헬퍼 ──────────────────────────────────────────────────────────────

def load_meta():
    if META_FILE.exists():
        try: return json.loads(META_FILE.read_text("utf-8"))
        except: return []
    return []

def save_meta(items):
    DATA_DIR.mkdir(exist_ok=True)
    META_FILE.write_text(json.dumps(items, indent=2, ensure_ascii=False), "utf-8")

def dedup(items):
    seen, out = set(), []
    for it in items:
        if it["id"] not in seen:
            seen.add(it["id"]); out.append(it)
    return out

def get_ext(url, default="webp"):
    p = urllib.parse.urlparse(url).path
    e = os.path.splitext(p)[1].lstrip(".")
    return e if e in ("jpg","jpeg","png","webp","gif","mp4","webm","mov") else default

# ── 파일 저장 ────────────────────────────────────────────────────────────────

def save_file(data: bytes, item_id: str, item_type: str, url: str, today: str) -> str | None:
    ext    = get_ext(url, "mp4" if item_type == "video" else "webp")
    subdir = MEDIA_DIR / (item_type + "s") / today
    subdir.mkdir(parents=True, exist_ok=True)
    dest   = subdir / f"{item_id}.{ext}"
    if dest.exists() and dest.stat().st_size > 0:
        return dest.as_posix()
    dest.write_bytes(data)
    print(f"    Saved {dest.name} ({len(data)//1024} KB)")
    return dest.as_posix()

def try_download(url: str, item_id: str, item_type: str, today: str) -> str | None:
    """Python requests 로 직접 다운로드 시도 (CDN이 허용하는 경우)."""
    if not url: return None
    max_bytes = MAX_VID_BYTES if item_type == "video" else MAX_IMG_BYTES
    try:
        r = requests.get(url.split("?")[0], headers=DL_HEADERS, timeout=30, stream=True)
        if r.status_code != 200: return None
        buf, total = b"", 0
        for chunk in r.iter_content(65536):
            buf += chunk; total += len(chunk)
            if total > max_bytes: return None
        if len(buf) < 5000: return None  # too small = probably an error page
        return save_file(buf, item_id, item_type, url, today)
    except Exception:
        return None

# ── HTML 파싱 (background-image: image-set 패턴) ─────────────────────────────

def parse_html(html: str, item_type: str, crawled_at: str) -> list:
    items = []
    # pattern: href="/jobs/{uuid}?index=N" ... style="...image-set(url(&quot;URL1&quot;) 1x, url(&quot;URL2&quot;) 2x)"
    pattern = (
        r'href="/jobs/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})'
        r'(?:\?index=(\d+))?[^"]*"[^>]*?style="[^"]*image-set\('
        r'url\(&quot;([^&]+)&quot;\)\s*1x'
        r'(?:\s*,\s*url\(&quot;([^&]+)&quot;\)\s*2x)?'
    )
    for m in re.finditer(pattern, html, re.DOTALL):
        job_id  = m.group(1)
        index   = m.group(2) or "0"
        url_1x  = m.group(3)
        url_2x  = m.group(4) or url_1x
        best    = url_2x or url_1x
        uid     = f"{job_id}_{index}"
        items.append({
            "id":         uid,
            "job_id":     job_id,
            "type":       item_type,
            "url":        best,
            "url_thumb":  url_1x,
            "link":       f"https://www.midjourney.com/jobs/{job_id}",
            "prompt":     "",
            "username":   "",
            "likes":      0,
            "crawled_at": crawled_at,
        })

    # Video: look for <video> or mp4 CDN URLs
    if item_type == "video":
        for url in re.findall(r'https://cdn\.midjourney\.com/[A-Za-z0-9_./-]+\.mp4', html):
            m2 = re.search(r'([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})', url)
            uid = m2.group(1) if m2 else str(abs(hash(url)))
            items.append({
                "id": uid, "job_id": uid, "type": "video",
                "url": url, "link": f"https://www.midjourney.com/jobs/{uid}",
                "prompt": "", "username": "", "likes": 0, "crawled_at": crawled_at,
            })

    return dedup(items)

# ── 탭 크롤링 ────────────────────────────────────────────────────────────────

def scrape_tab(ctx: BrowserContext, tab: dict, existing_ids: set, today: str) -> list:
    crawled_at = datetime.now(timezone.utc).isoformat()

    # response 캡처 딕셔너리: url -> bytes
    captured: dict[str, bytes] = {}

    def on_resp(resp):
        u = resp.url
        if "cdn.midjourney.com" not in u: return
        if not (".webp" in u or ".mp4" in u): return
        try:
            body = resp.body()
            if len(body) > 2000:
                captured[u] = body
        except Exception:
            pass

    page = ctx.new_page()
    page.on("response", on_resp)

    try:
        print(f"  Loading: {tab['url']}")
        page.goto(tab["url"], wait_until="domcontentloaded", timeout=90_000)
        time.sleep(5 + random.uniform(0, 2))

        # 스크롤하며 이미지 로딩 유도
        for i in range(SCROLL_COUNT):
            page.evaluate("window.scrollBy(0, window.innerHeight * 1.5)")
            time.sleep(SCROLL_DELAY + random.uniform(0, 0.5))
            if (i + 1) % 7 == 0:
                print(f"    scroll {i+1}/{SCROLL_COUNT} ...")

        time.sleep(3)
        html = page.content()

    except Exception as exc:
        print(f"  ERROR: {exc}")
        html = ""
    finally:
        page.close()

    # HTML 파싱으로 아이템 추출
    items = parse_html(html, tab["type"], crawled_at)
    print(f"  HTML items: {len(items)},  browser-captured: {len(captured)}")

    new_items = [it for it in items if it["id"] not in existing_ids]
    new_items  = new_items[:MAX_PER_TAB]

    # ── 파일 저장 ─────────────────────────────────────────────────────────────
    for it in new_items:
        item_url  = it["url"]
        item_type = it["type"]
        uid       = it["id"]

        # 1순위: 브라우저 캡처된 데이터
        matched_data = None
        for cap_url, data in captured.items():
            if uid.split("_")[0] in cap_url:  # job_id 매칭
                if (item_type == "video" and ".mp4" in cap_url) or \
                   (item_type != "video" and ".webp" in cap_url):
                    matched_data = (cap_url, data)
                    break

        if matched_data:
            fp = save_file(matched_data[1], uid, item_type, matched_data[0], today)
            if fp: it["file_path"] = fp
        else:
            # 2순위: requests 직접 다운로드 시도
            fp = try_download(item_url, uid, item_type, today)
            if fp: it["file_path"] = fp

    return new_items

# ── 메인 ─────────────────────────────────────────────────────────────────────

def main() -> int:
    print("=" * 52)
    print("  Midjourney Explore Crawler")
    print(f"  {datetime.now(timezone.utc).isoformat()}")
    print("=" * 52)

    today    = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    existing = load_meta()
    ex_ids   = {it["id"] for it in existing}
    print(f"Existing: {len(existing)}\n")

    has_cookies = bool(os.environ.get("MJ_COOKIES", "").strip())
    all_new: list = []

    with sync_playwright() as pw:
        browser = pw.chromium.launch(
            headless=True,
            args=["--no-sandbox", "--disable-setuid-sandbox",
                  "--disable-dev-shm-usage", "--disable-gpu", "--no-zygote"],
        )
        ctx = browser.new_context(
            user_agent=(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/125.0.0.0 Safari/537.36"
            ),
            viewport={"width": 1920, "height": 1080},
            locale="en-US",
            timezone_id="America/New_York",
            extra_http_headers={"Accept-Language": "en-US,en;q=0.9"},
        )

        # MJ_COOKIES 로드
        if has_cookies:
            try:
                ctx.add_cookies(json.loads(os.environ["MJ_COOKIES"]))
                print("Auth cookies loaded\n")
            except Exception as e:
                print(f"Cookie load error: {e}\n")

        # 메인 페이지 먼저 방문 (CF session warm-up)
        warm_page = ctx.new_page()
        try:
            warm_page.goto("https://www.midjourney.com/", wait_until="domcontentloaded", timeout=30_000)
            time.sleep(3)
        except Exception:
            pass
        finally:
            warm_page.close()

        for tab in TABS:
            # 인증 필요 탭은 쿠키 없으면 건너뜀
            if tab["auth"] and not has_cookies:
                print(f"\n[{tab['name'].upper()}] skipped (no MJ_COOKIES)")
                print("  -> GitHub Secrets > MJ_COOKIES 에 쿠키를 설정하면 활성화됩니다")
                continue

            print(f"\n[{tab['name'].upper()}]")
            new_items = scrape_tab(ctx, tab, ex_ids, today)
            print(f"  -> {len(new_items)} new")
            all_new.extend(new_items)
            ex_ids.update(it["id"] for it in new_items)
            time.sleep(5 + random.uniform(0, 3))

        browser.close()

    if all_new:
        merged = all_new + existing
        save_meta(merged)
        print(f"\nSaved: +{len(all_new)} new  (total {len(merged)})")
    else:
        print("\nNo new items")

    print(f"Done: {datetime.now(timezone.utc).isoformat()}")
    return len(all_new)

if __name__ == "__main__":
    raise SystemExit(0 if main() >= 0 else 1)
