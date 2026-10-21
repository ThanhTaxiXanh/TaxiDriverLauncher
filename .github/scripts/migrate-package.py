#!/usr/bin/env python3
"""One-shot migration of first-party Kotlin/Java packages and source paths."""
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]
OLD = "io.github.seijikohara.femto"
NEW = "com.taxidriver.launcher"
OLD_PATH = Path("io/github/seijikohara/femto")
NEW_PATH = Path("com/taxidriver/launcher")

# Process only project source/configuration text, not binary assets or vendored code.
for source_root in (ROOT / "app" / "src",):
    for path in source_root.rglob("*"):
        if not path.is_file() or path.suffix not in (".kt", ".java", ".xml", ".pro"):
            continue
        try:
            original = path.read_text(encoding="utf-8")
        except UnicodeError:
            continue
        changed = original.replace(OLD, NEW)
        if changed != original:
            path.write_text(changed, encoding="utf-8")

for source_set in (ROOT / "app" / "src").iterdir():
    if not source_set.is_dir():
        continue
    for lang in ("java", "kotlin"):
        base = source_set / lang
        old_dir = base / OLD_PATH
        new_dir = base / NEW_PATH
        if not old_dir.is_dir():
            continue
        new_dir.mkdir(parents=True, exist_ok=True)
        for item in old_dir.iterdir():
            target = new_dir / item.name
            if target.exists():
                raise RuntimeError(f"Package migration conflict: {target}")
            shutil.move(str(item), str(target))
        for parent in (old_dir, *list(old_dir.parents)):
            if parent == base or not parent.is_relative_to(base):
                break
            if any(parent.iterdir()):
                break
            parent.rmdir()

# Metadata references (e.g. release feed and generated package rules).
for path in (ROOT / "app" / "build.gradle.kts",):
    content = path.read_text()
    updated = content.replace(OLD, NEW)
    if content != updated:
        path.write_text(updated)
print("Package migration complete")
