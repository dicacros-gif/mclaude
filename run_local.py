#!/usr/bin/env python3
"""
자가 호스팅 러너 (self-host runner)
====================================
GitHub Actions 데이터센터 IP는 Cloudflare 챌린지에 막힙니다.
이 스크립트는 *본인 PC의 브라우저 프로필(로그인 세션)* 을 그대로 재사용해
Midjourney explore 페이지를 크롤링한 뒤 결과를 GitHub에 push 합니다.

  ── 우회 도구가 아닙니다 ──
  cloudscraper / FlareSolverr / TLS 위장 / CAPTCHA 풀이 같은 봇 탐지 우회는
  쓰지 않습니다. 본인이 정상적으로 로그인한 실제 브라우저 세션을 쓰는 것뿐입니다.

사용법
------
1) 최초 1회 — 로그인 세션 저장 (헤드풀 브라우저가 열립니다):
       python run_local.py --login
   열린 브라우저에서 Midjourney에 로그인한 뒤, 터미널에서 Enter.

2) 크롤링 + GitHub push:
       python run_local.py
   (스크롤이 보이게 띄우려면)  python run_local.py --headed

프로필 경로는 기본 ./.mj_profile (이미 .gitignore 처리, 절대 커밋되지 않음).
환경변수 MJ_PROFILE_DIR 로 변경 가능.
"""

import argparse
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT   = Path(__file__).resolve().parent
DEFAULT_DIR = REPO_ROOT / ".mj_profile"
HOME_URL    = "https://www.midjourney.com/explore?tab=top"


def profile_dir() -> str:
    return os.environ.get("MJ_PROFILE_DIR", "").strip() or str(DEFAULT_DIR)


# ── 로그인 모드 ────────────────────────────────────────────────────────────────

def do_login() -> int:
    """헤드풀 브라우저를 띄워 사용자가 직접 로그인 → 세션을 프로필에 저장."""
    from playwright.sync_api import sync_playwright

    pdir = profile_dir()
    Path(pdir).mkdir(parents=True, exist_ok=True)
    print(f"[login] 프로필 경로: {pdir}")
    print("[login] 브라우저가 열리면 Midjourney에 로그인하세요.")

    with sync_playwright() as pw:
        ctx = pw.chromium.launch_persistent_context(
            user_data_dir=pdir,
            headless=False,
            args=["--disable-blink-features=AutomationControlled"],
            viewport={"width": 1280, "height": 900},
        )
        page = ctx.pages[0] if ctx.pages else ctx.new_page()
        try:
            page.goto("https://www.midjourney.com/explore", timeout=60_000)
        except Exception as exc:
            print(f"[login] 페이지 이동 경고(무시 가능): {exc}")

        try:
            input("\n>>> 로그인을 끝냈으면 이 터미널에서 Enter 를 누르세요... ")
        except (EOFError, KeyboardInterrupt):
            pass

        ctx.close()
    print("[login] 세션이 저장되었습니다. 이제 'python run_local.py' 로 크롤링하세요.")
    return 0


# ── 크롤링 + push 모드 ─────────────────────────────────────────────────────────

def git(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git", *args],
        cwd=str(REPO_ROOT),
        capture_output=True,
        text=True,
    )


def commit_and_push(new_count: int) -> int:
    # 변경분 스테이징 (존재하는 것만)
    git("add", "data/metadata.json")
    if (REPO_ROOT / "data" / "blocklist.json").exists():
        git("add", "data/blocklist.json")
    git("add", "media")

    staged = git("diff", "--staged", "--quiet")
    if staged.returncode == 0:
        print("[git] 커밋할 변경분 없음.")
        return 0

    # 총 개수 계산
    try:
        import json
        total = len(json.loads((REPO_ROOT / "data" / "metadata.json").read_text("utf-8")))
    except Exception:
        total = "?"

    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    msg   = f"crawl(local): {stamp}  (+{new_count}, total {total})"
    c = git("commit", "-m", msg)
    if c.returncode != 0:
        print("[git] commit 실패:\n" + c.stdout + c.stderr)
        return 1
    print(f"[git] committed: {msg}")

    # 원격 변경분과 충돌 방지
    pull = git("pull", "--rebase")
    if pull.returncode != 0:
        print("[git] pull --rebase 경고:\n" + pull.stdout + pull.stderr)

    push = git("push")
    if push.returncode != 0:
        print("[git] push 실패:\n" + push.stdout + push.stderr)
        return 1
    print("[git] push 완료.")
    return 0


def do_crawl(headed: bool) -> int:
    pdir = profile_dir()
    if not Path(pdir).exists():
        print(f"[crawl] 프로필이 없습니다: {pdir}")
        print("[crawl] 먼저 'python run_local.py --login' 으로 로그인하세요.")
        return 2

    # scraper 가 자가 호스팅(프로필) 모드로 동작하도록 환경변수 설정
    os.environ["MJ_PROFILE_DIR"] = pdir
    if headed:
        os.environ["MJ_HEADED"] = "1"

    print(f"[crawl] 프로필 모드로 크롤링 시작: {pdir}  (headed={headed})")
    import scraper
    new_count = scraper.main()
    print(f"[crawl] 신규 {new_count}건.")

    return commit_and_push(new_count)


# ── 엔트리 ────────────────────────────────────────────────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser(description="Midjourney 자가 호스팅 크롤러")
    ap.add_argument("--login", action="store_true",
                    help="헤드풀 브라우저로 로그인 세션 저장 (최초 1회)")
    ap.add_argument("--headed", action="store_true",
                    help="크롤링 시 브라우저 창을 표시")
    args = ap.parse_args()

    if args.login:
        return do_login()
    return do_crawl(args.headed)


if __name__ == "__main__":
    raise SystemExit(main())
