#!/usr/bin/env python3
"""
index.html은 data/metadata.json을 런타임에 fetch()로 읽습니다.
이 스크립트는 GitHub Actions 호환성을 위해 유지되지만
실질적으로는 index.html이 이미 저장소에 포함되어 있으므로 아무 작업도 하지 않습니다.
"""
from pathlib import Path

def main():
    p = Path("index.html")
    if p.exists():
        print(f"index.html 존재 확인 ({p.stat().st_size} bytes) — 갱신 불필요")
    else:
        print("index.html 없음 — scraper.py 실행 후 push 필요")

if __name__ == "__main__":
    main()
