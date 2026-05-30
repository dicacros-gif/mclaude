#!/usr/bin/env python3
"""
삭제 로직 (공용 모듈)
- data/metadata.json 에서 항목 제거
- media/ 썸네일 파일 삭제
- data/blocklist.json 에 ID 추가 (재크롤 방지)

두 군데서 재사용:
  1) run_local.py (자가 호스팅 러너) — 갤러리 localStorage 큐를 읽어 delete_ids() 호출
  2) .github/workflows/delete_items.yml — DELETE_IDS 환경변수로 main() 실행 (수동 폴백)
"""

import json, os, sys
from pathlib import Path

DATA_DIR   = Path("data")
META_FILE  = DATA_DIR / "metadata.json"
BLOCK_FILE = DATA_DIR / "blocklist.json"


def delete_ids(ids) -> dict:
    """주어진 ID 집합을 metadata/media/blocklist 에서 삭제. 결과 요약 dict 반환."""
    ids_to_delete = {str(s).strip() for s in ids if str(s).strip()}
    if not ids_to_delete:
        return {"removed": 0, "files": 0, "remaining": None, "blocklist": None}

    print(f"IDs to delete: {len(ids_to_delete)}")
    for i in sorted(ids_to_delete):
        print(f"  - {i}")

    # ── metadata 로드 ──────────────────────────────────────────────────────────
    meta = []
    if META_FILE.exists():
        try:
            meta = json.loads(META_FILE.read_text("utf-8"))
        except Exception:
            meta = []

    to_delete = [it for it in meta if it.get("id") in ids_to_delete]
    remaining = [it for it in meta if it.get("id") not in ids_to_delete]

    # ── 썸네일 파일 삭제 ────────────────────────────────────────────────────────
    deleted_files = 0
    for item in to_delete:
        fp = item.get("file_path")
        if fp:
            p = Path(fp)
            if p.exists():
                try:
                    p.unlink()
                    print(f"  Deleted file: {fp}")
                    deleted_files += 1
                except Exception as exc:
                    print(f"  File delete error ({fp}): {exc}")
            else:
                print(f"  File not found (skip): {fp}")

    # ── metadata.json 갱신 ─────────────────────────────────────────────────────
    DATA_DIR.mkdir(exist_ok=True)
    META_FILE.write_text(json.dumps(remaining, indent=2, ensure_ascii=False), "utf-8")

    # ── blocklist.json 갱신 (재크롤 방지) ───────────────────────────────────────
    blocklist: set = set()
    if BLOCK_FILE.exists():
        try:
            blocklist = set(json.loads(BLOCK_FILE.read_text("utf-8")))
        except Exception:
            blocklist = set()
    blocklist.update(ids_to_delete)
    BLOCK_FILE.write_text(json.dumps(sorted(blocklist), indent=2, ensure_ascii=False), "utf-8")

    print(f"\nDone: removed {len(to_delete)} items, {deleted_files} files deleted.")
    print(f"Remaining items: {len(remaining)}")
    print(f"Blocklist size: {len(blocklist)}")
    return {
        "removed": len(to_delete),
        "files": deleted_files,
        "remaining": len(remaining),
        "blocklist": len(blocklist),
    }


def main() -> int:
    raw = os.environ.get("DELETE_IDS", "").strip()
    if not raw:
        print("DELETE_IDS is empty — nothing to do.")
        return 0
    delete_ids({s for s in raw.split(",")})
    return 0


if __name__ == "__main__":
    sys.exit(main())
