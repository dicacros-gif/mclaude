#!/usr/bin/env python3
"""
Delete workflow script.
Called by .github/workflows/delete_items.yml via workflow_dispatch.
Reads DELETE_IDS env variable (comma-separated IDs).
- Removes items from data/metadata.json
- Deletes thumbnail files from media/
- Adds IDs to data/blocklist.json (prevents re-crawl)
"""

import json, os, sys
from pathlib import Path

DATA_DIR  = Path("data")
META_FILE = DATA_DIR / "metadata.json"
BLOCK_FILE = DATA_DIR / "blocklist.json"

def main():
    raw = os.environ.get("DELETE_IDS", "").strip()
    if not raw:
        print("DELETE_IDS is empty — nothing to do.")
        return 0

    ids_to_delete = {s.strip() for s in raw.split(",") if s.strip()}
    print(f"IDs to delete: {len(ids_to_delete)}")
    for i in sorted(ids_to_delete):
        print(f"  - {i}")

    # ── Load metadata ──────────────────────────────────────────────────────────
    if not META_FILE.exists():
        print("metadata.json not found.")
        return 1
    meta = json.loads(META_FILE.read_text("utf-8"))

    to_delete = [it for it in meta if it["id"] in ids_to_delete]
    remaining  = [it for it in meta if it["id"] not in ids_to_delete]

    # ── Delete thumbnail files ─────────────────────────────────────────────────
    deleted_files = 0
    for item in to_delete:
        fp = item.get("file_path")
        if fp:
            p = Path(fp)
            if p.exists():
                p.unlink()
                print(f"  Deleted file: {fp}")
                deleted_files += 1
            else:
                print(f"  File not found (skip): {fp}")

    # ── Update metadata.json ───────────────────────────────────────────────────
    META_FILE.write_text(json.dumps(remaining, indent=2, ensure_ascii=False), "utf-8")

    # ── Update blocklist.json ──────────────────────────────────────────────────
    blocklist: set = set()
    if BLOCK_FILE.exists():
        try:
            blocklist = set(json.loads(BLOCK_FILE.read_text("utf-8")))
        except Exception:
            blocklist = set()
    blocklist.update(ids_to_delete)
    BLOCK_FILE.write_text(
        json.dumps(sorted(blocklist), indent=2, ensure_ascii=False), "utf-8"
    )

    print(f"\nDone: removed {len(to_delete)} items, {deleted_files} files deleted.")
    print(f"Remaining items: {len(remaining)}")
    print(f"Blocklist size: {len(blocklist)}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
