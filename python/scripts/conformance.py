#!/usr/bin/env python3
"""Mechanical 1:1 conformance check (06-acceptance.md): every one of the 83
scenario ids must appear in exactly one test name, and no id may be claimed
twice. Run via: uv run --python .venv/bin/python --no-project python
scripts/conformance.py  (exit 0 = 83/83).
"""

from __future__ import annotations

import pathlib
import re
import sys

# The authoritative 83-scenario catalog (06-acceptance.md conformance table).
EXPECTED: dict[str, int] = {
    "S-ID": 2,
    "S-US": 6,
    "S-PG": 5,
    "S-DM": 12,
    "S-CH": 24,
    "S-AT": 7,
    "S-NT": 5,
    "S-FD": 6,
    "S-LP": 5,
    "S-PR": 5,
    "S-SM": 6,
}

_TEST_DEF = re.compile(r"^def (test_s_([a-z]{2})_(\d{2})_)", re.MULTILINE)


def main() -> int:
    tests_dir = pathlib.Path(__file__).resolve().parent.parent / "tests"
    found: dict[str, set[str]] = {}
    duplicates: list[str] = []
    for path in sorted(tests_dir.rglob("*_test.py")):
        text = path.read_text()
        for match in _TEST_DEF.finditer(text):
            area = "S-" + match.group(2).upper()
            scenario = f"{area}-{match.group(3)}"
            bucket = found.setdefault(area, set())
            if scenario in bucket:
                duplicates.append(f"{scenario} (in {path.name})")
            bucket.add(scenario)

    ok = True
    total = 0
    for area, count in EXPECTED.items():
        have = found.get(area, set())
        total += len(have)
        marker = "ok" if len(have) == count else "MISMATCH"
        if len(have) != count:
            ok = False
        print(f"{area}: {len(have)}/{count} {marker}")
        if len(have) != count:
            expected_ids = {f"{area}-{i:02d}" for i in range(1, count + 1)}
            missing = sorted(expected_ids - have)
            extra = sorted(have - expected_ids)
            if missing:
                print(f"    missing: {missing}")
            if extra:
                print(f"    extra:   {extra}")

    if duplicates:
        ok = False
        print(f"DUPLICATE scenario ids: {duplicates}")

    expected_total = sum(EXPECTED.values())
    print(f"\nTOTAL: {total}/{expected_total}")
    if ok and total == expected_total:
        print("CONFORMANCE 83/83: PASS")
        return 0
    print("CONFORMANCE: FAIL")
    return 1


if __name__ == "__main__":
    sys.exit(main())
