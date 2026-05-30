#!/usr/bin/env python3
"""
Midjourney Explore Crawler
- 3개 탭 크롤링: images (?tab=top) / styles (?tab=styles_top) / videos (?tab=video_top)
- 비디오 탭: 영상 파일 저장 안 함, 썸네일 이미지만 저장
- data/metadata.json 앞에 신규 항목 추가 (기존 보존)
"""

import json, os, re, sys, time, random, urllib.parse
from datetime import datetime, timezone
from pathlib import Path

import requests
from playwright.sync_api import sync_playwright, BrowserContext

# ── 설정 ──────────────────────────────────────────────────────────────────────

TABS = [
    {"name": "images", "url": "https://www.midjourney.com/explore?tab=top",        "type": "image"},
    {"name": "styles", "url": "https://www.midjourney.com/explore?tab=styles_top", "type": "style"},
    {"name": "videos", "url": "https://www.midjourney.com/explore?tab=video_top",  "type": "video"},
]

DATA_DIR   = Path("data")
MEDIA_DIR  = Path("media")
META_FILE  = DATA_DIR / "metadata.json"
BLOCK_FILE = DATA_DIR / "blocklist.json"

MAX_PER_TAB   = 50
SCROLL_COUNT  = 22
SCROLL_DELAY  = 2.0
MAX_IMG_BYTES = 10 * 1024 * 1024   # 10 MB
CF_WAIT_SECS  = 40                 # Cloudflare 챌린지 최대 대기
CF_RETRY      = 2                  # 탭당 최대 재시도

# Stealth JavaScript: webdriver 감지 우회
STEALTH_JS = """
Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
Object.defineProperty(navigator, 'plugins',   {get: () => [1,2,3,4,5]});
Object.defineProperty(navigator, 'languages', {get: () => ['en-US','en']});
window.chrome = {runtime: {}};
const origQuery = window.navigator.permissions.query;
window.navigator.permissions.query = (parameters) =>
  parameters.name === 'notifications'
    ? Promise.resolve({state: Notification.permission})
    : origQuery(parameters);
"""

# ── 헬퍼 ──────────────────────────────────────────────────────────────────────

def load_meta():
    if META_FILE.exists():
        try: return json.loads(META_FILE.read_text("utf-8"))
        except: return []
    return []

def load_blocklist():
    if BLOCK_FILE.exists():
        try: return set(json.loads(BLOCK_FILE.read_text("utf-8")))
        except: return set()
    return set()

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
    e = os.path.splitext(urllib.parse.urlparse(url).path)[1].lstrip(".")
    return e if e in ("jpg","jpeg","png","webp","gif") else default

def log(msg):
    try: print(msg)
    except UnicodeEncodeError: print(msg.encode("ascii","replace").decode())

# ── 파일 저장 ─────────────────────────────────────────────────────────────────

def save_bytes(data: bytes, uid: str, item_type: str, src_url: str, today: str) -> str | None:
    ext    = get_ext(src_url.split("?")[0])
    folder = "videos" if item_type == "video" else (item_type + "s")
    subdir = MEDIA_DIR / folder / today
    subdir.mkdir(parents=True, exist_ok=True)
    dest   = subdir / f"{uid}.{ext}"
    if dest.exists() and dest.stat().st_size > 0:
        return dest.as_posix()
    dest.write_bytes(data)
    log(f"    Saved {dest.name} ({len(data)//1024} KB)")
    return dest.as_posix()

def try_requests_download(url: str, uid: str, item_type: str, today: str) -> str | None:
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36",
        "Referer": "https://www.midjourney.com/",
        "Accept": "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
        "Sec-Fetch-Dest": "image",
        "Sec-Fetch-Mode": "no-cors",
        "Sec-Fetch-Site": "cross-site",
    }
    try:
        r = requests.get(url.split("?")[0], headers=headers, timeout=30, stream=True)
        if r.status_code != 200: return None
        buf, total = b"", 0
        for chunk in r.iter_content(65536):
            buf += chunk; total += len(chunk)
            if total > MAX_IMG_BYTES: return None
        if len(buf) < 5000: return None
        return save_bytes(buf, uid, item_type, url, today)
    except Exception:
        return None

# ── HTML 파싱 ─────────────────────────────────────────────────────────────────

def parse_bg_image(html: str, item_type: str, crawled_at: str) -> list:
    """background-image: image-set() 패턴 – images/styles/videos 탭 공통"""
    items = []
    pattern = (
        r'href="/jobs/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})'
        r'(?:\?index=(\d+))?[^"]*"[^>]*?style="[^"]*image-set\('
        r'url\(&quot;([^&]+)&quot;\)\s*1x'
        r'(?:\s*,\s*url\(&quot;([^&]+)&quot;\)\s*2x)?'
    )
    for m in re.finditer(pattern, html, re.DOTALL):
        job_id = m.group(1)
        index  = m.group(2) or "0"
        url_1x = m.group(3)
        url_2x = m.group(4) or url_1x
        uid    = f"{job_id}_{index}"
        items.append({
            "id":         uid,
            "job_id":     job_id,
            "type":       item_type,
            "url":        url_2x,      # 640px 해상도 (2x)
            "url_thumb":  url_1x,      # 384px 해상도 (1x)
            "link":       f"https://www.midjourney.com/jobs/{job_id}",
            "prompt":     "",
            "username":   "",
            "likes":      0,
            "crawled_at": crawled_at,
        })
    return items

def parse_video_posters(html: str, crawled_at: str) -> list:
    """<video poster="..."> 패턴 – video 탭 폴백"""
    items = []
    pattern = r'href="/jobs/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})[^"]*"[^>]*poster="([^"]+)"'
    for m in re.finditer(pattern, html, re.DOTALL):
        job_id = m.group(1)
        poster = m.group(2)
        items.append({
            "id":         job_id,
            "job_id":     job_id,
            "type":       "video",
            "url":        poster,
            "link":       f"https://www.midjourney.com/jobs/{job_id}",
            "prompt":     "",
            "username":   "",
            "likes":      0,
            "crawled_at": crawled_at,
        })
    return items

# ── 탭 크롤링 ─────────────────────────────────────────────────────────────────

def wait_for_cf(page, timeout_s=CF_WAIT_SECS) -> bool:
    """Cloudflare 챌린지 대기. True=통과, False=실패"""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if "Just a moment" not in page.title():
            return True
        time.sleep(4)
    return False

def scrape_tab(ctx: BrowserContext, tab: dict, existing_ids: set, today: str) -> list:
    crawled_at = datetime.now(timezone.utc).isoformat()
    captured: dict[str, bytes] = {}

    def on_resp(resp):
        u = resp.url
        if "cdn.midjourney.com" not in u: return
        if ".webp" not in u and ".jpg" not in u and ".png" not in u: return
        try:
            body = resp.body()
            if len(body) > 3000:
                captured[u] = body
        except Exception:
            pass

    html = ""
    for attempt in range(CF_RETRY + 1):
        page = ctx.new_page()
        page.on("response", on_resp)
        try:
            log(f"  Loading ({attempt+1}): {tab['url']}")
            page.goto(tab["url"], wait_until="domcontentloaded", timeout=90_000)

            # CF 대기
            if not wait_for_cf(page, CF_WAIT_SECS):
                log(f"  CF challenge not resolved - retrying...")
                page.close(); continue

            # 스크롤로 이미지 로딩 유도
            time.sleep(4 + random.uniform(0, 2))
            for i in range(SCROLL_COUNT):
                page.evaluate("window.scrollBy(0, window.innerHeight * 1.5)")
                time.sleep(SCROLL_DELAY + random.uniform(0, 0.5))
                if (i + 1) % 7 == 0:
                    log(f"    scroll {i+1}/{SCROLL_COUNT}...")

            time.sleep(3)
            html = page.content()
            break

        except Exception as exc:
            log(f"  ERROR: {exc}")
        finally:
            page.close()

    # ── HTML 파싱 ──────────────────────────────────────────────────────────────
    items = parse_bg_image(html, tab["type"], crawled_at)

    # 비디오 탭: bg-image 없으면 poster 폴백
    if tab["type"] == "video" and not items:
        items = parse_video_posters(html, crawled_at)

    log(f"  HTML items: {len(items)},  browser-captured: {len(captured)}")

    new_items = [it for it in dedup(items) if it["id"] not in existing_ids]
    new_items  = new_items[:MAX_PER_TAB]

    # ── 썸네일 저장 (이미지/스타일/비디오 모두 이미지 파일만) ─────────────────────
    for it in new_items:
        uid       = it["id"]
        thumb_url = it["url"]   # 항상 이미지 URL (비디오도 썸네일 URL)

        # 1순위: 브라우저 캡처
        matched = None
        job_id  = it.get("job_id", uid.split("_")[0])
        for cap_url, data in captured.items():
            if job_id in cap_url and ".webp" in cap_url:
                matched = (cap_url, data); break

        if matched:
            fp = save_bytes(matched[1], uid, it["type"], matched[0], today)
        else:
            # 2순위: requests 직접 다운로드
            fp = try_requests_download(thumb_url, uid, it["type"], today)

        if fp:
            it["file_path"] = fp

    return new_items

# ── 메인 ──────────────────────────────────────────────────────────────────────

def parse_cookies(raw: str) -> list:
    """'name=value; name2=value2' 쿠키 문자열 → Playwright 쿠키 목록"""
    cookies = []
    for part in raw.split(";"):
        part = part.strip()
        if not part or "=" not in part:
            continue
        name, _, value = part.partition("=")
        name, value = name.strip(), value.strip()
        if name:
            cookies.append({
                "name": name, "value": value,
                "domain": ".midjourney.com", "path": "/",
            })
    return cookies

def make_context(pw):
    browser = pw.chromium.launch(
        headless=True,
        args=[
            "--no-sandbox", "--disable-setuid-sandbox",
            "--disable-dev-shm-usage", "--disable-gpu", "--no-zygote",
            "--disable-blink-features=AutomationControlled",
        ],
    )
    # cf_clearance 쿠키는 User-Agent에 묶이므로, 쿠키를 뽑은 브라우저의 UA를
    # MJ_UA 시크릿으로 함께 넣어주면 통과 확률이 올라간다.
    ua = os.environ.get("MJ_UA", "").strip() or (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/125.0.0.0 Safari/537.36"
    )
    ctx = browser.new_context(
        user_agent=ua,
        viewport={"width": 1920, "height": 1080},
        locale="en-US",
        timezone_id="America/New_York",
        extra_http_headers={
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.9",
            "Sec-Ch-Ua": '"Google Chrome";v="125", "Chromium";v="125", "Not.A/Brand";v="24"',
            "Sec-Ch-Ua-Mobile": "?0",
            "Sec-Ch-Ua-Platform": '"Windows"',
            "Upgrade-Insecure-Requests": "1",
        },
    )
    ctx.add_init_script(STEALTH_JS)

    # MJ_COOKIES (로그인 세션) 적용 — 인증된 본인 세션으로 접근
    raw_cookies = os.environ.get("MJ_COOKIES", "").strip()
    if raw_cookies:
        cookies = parse_cookies(raw_cookies)
        if cookies:
            ctx.add_cookies(cookies)
            names = ", ".join(sorted({c["name"] for c in cookies}))
            log(f"Loaded {len(cookies)} cookies from MJ_COOKIES ({names})")
            log(f"  UA: {'custom (MJ_UA)' if os.environ.get('MJ_UA') else 'default'}")
    else:
        log("MJ_COOKIES not set - anonymous crawl (Cloudflare may block)")

    return browser, ctx

def main() -> int:
    log("=" * 52)
    log("  Midjourney Explore Crawler")
    log(f"  {datetime.now(timezone.utc).isoformat()}")
    log("=" * 52)

    today    = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    existing = load_meta()
    blocked  = load_blocklist()
    ex_ids   = {it["id"] for it in existing} | blocked
    log(f"Existing: {len(existing)}, Blocked: {len(blocked)}\n")

    all_new = []

    with sync_playwright() as pw:
        browser, ctx = make_context(pw)

        # CF warm-up: 메인 페이지 먼저 방문
        warm = ctx.new_page()
        try:
            warm.goto("https://www.midjourney.com/", wait_until="domcontentloaded", timeout=30_000)
            wait_for_cf(warm, 20)
            time.sleep(3)
        except Exception:
            pass
        finally:
            warm.close()

        for tab in TABS:
            log(f"\n[{tab['name'].upper()}]")
            new_items = scrape_tab(ctx, tab, ex_ids, today)
            log(f"  -> {len(new_items)} new items")
            all_new.extend(new_items)
            ex_ids.update(it["id"] for it in new_items)
            time.sleep(6 + random.uniform(0, 4))

        browser.close()

    if all_new:
        merged = dedup(all_new + existing)   # id 기준 최종 중복 제거
        save_meta(merged)
        log(f"\nSaved +{len(all_new)} new  (total {len(merged)})")
    else:
        log("\nNo new items found")

    log(f"Done: {datetime.now(timezone.utc).isoformat()}")
    return len(all_new)

if __name__ == "__main__":
    raise SystemExit(0 if main() >= 0 else 1)
