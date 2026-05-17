"""
Sanity-check a candidate drop_rates.json before it's committed.

Exits 0 if the new file is safe to ship, non-zero with a diagnostic message
on stderr otherwise. Used by the update-drop-rates workflow.

Checks:
  - new file is valid JSON
  - top-level is a non-empty object
  - item count delta vs old file is within MAX_DELTA (default 20%)

If the old file is missing or unreadable, only structural checks run.
"""

import json
import sys
from pathlib import Path

MAX_DELTA = 0.20


def load(path: Path):
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def fail(msg: str) -> None:
    print(f"sanity_check: {msg}", file=sys.stderr)
    sys.exit(1)


def main():
    if len(sys.argv) != 3:
        fail("usage: sanity_check.py <old> <new>")

    old_path, new_path = Path(sys.argv[1]), Path(sys.argv[2])

    try:
        new = load(new_path)
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"new file invalid: {exc}")

    if not isinstance(new, dict) or not new:
        fail(f"new file is empty or not an object (got {type(new).__name__})")

    new_count = len(new)

    if old_path.exists():
        try:
            old = load(old_path)
            old_count = len(old) if isinstance(old, dict) else 0
        except (OSError, json.JSONDecodeError):
            old_count = 0

        if old_count > 0:
            delta = abs(new_count - old_count) / old_count
            if delta > MAX_DELTA:
                fail(
                    f"item count changed by {delta:.1%} "
                    f"(old={old_count}, new={new_count}, max allowed={MAX_DELTA:.0%})"
                )
            print(f"OK: {old_count} -> {new_count} items ({delta:+.1%})")
            return

    print(f"OK: {new_count} items (no baseline to compare)")


if __name__ == "__main__":
    main()
