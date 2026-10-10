import os
import zipfile
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
OUTPUT_ZIP = ROOT_DIR / "bulktext-android-0.18.4-clean.zip"

EXCLUDE_DIRS = {
    ".gradle",
    ".kotlin",
    ".idea",
    "build",
    "app/build",
    ".artifacts",
    ".patch-backups",
    "__pycache__"
}

EXCLUDE_FILES = {
    "local.properties",
    "bulktext-android-0.6.1.zip",
    "bulktext-android-0.18.4-clean.zip",
    "source-validate-debug.txt",
    ".DS_Store"
}

def is_excluded(path: Path) -> bool:
    rel = path.relative_to(ROOT_DIR)
    parts = rel.parts
    for p in parts:
        if p in EXCLUDE_DIRS:
            return True
        if p.startswith(".artifacts"):
            return True
        if p.startswith(".patch-backups"):
            return True
    if path.name in EXCLUDE_FILES:
        return True
    if path.suffix == ".pyc":
        return True
    return False

def main():
    if OUTPUT_ZIP.exists():
        OUTPUT_ZIP.unlink()

    included_count = 0
    with zipfile.ZipFile(OUTPUT_ZIP, "w", zipfile.ZIP_DEFLATED) as zf:
        for file_path in ROOT_DIR.rglob("*"):
            if file_path.is_file():
                if is_excluded(file_path):
                    continue
                arcname = file_path.relative_to(ROOT_DIR)
                zf.write(file_path, arcname)
                included_count += 1

    size_kb = OUTPUT_ZIP.stat().st_size / 1024
    print(f"Created clean ZIP: {OUTPUT_ZIP.name}")
    print(f"Files included: {included_count}")
    print(f"ZIP file size: {size_kb:.1f} KB")

if __name__ == "__main__":
    main()
