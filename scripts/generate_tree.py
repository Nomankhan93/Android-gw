import os
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
OUTPUT_FILE = ROOT_DIR / "tree.txt"

EXCLUDE_DIRS = {
    ".git",
    ".gradle",
    ".kotlin",
    ".idea",
    "build",
    "app/build",
    "__pycache__",
    ".artifacts",
    ".patch-backups"
}

EXCLUDE_FILES = {
    "local.properties",
    "tree.txt",
    "source-validate-debug.txt",
    ".DS_Store"
}

def generate_tree(dir_path: Path, prefix: str = "") -> list:
    lines = []
    try:
        entries = sorted(list(dir_path.iterdir()), key=lambda x: (not x.is_dir(), x.name.lower()))
    except PermissionError:
        return lines

    filtered_entries = []
    for entry in entries:
        if entry.name in EXCLUDE_DIRS or entry.name in EXCLUDE_FILES:
            continue
        if entry.name.endswith(".pyc") or entry.name.endswith(".apk") or entry.name.endswith(".zip"):
            continue
        filtered_entries.append(entry)

    count = len(filtered_entries)
    for index, entry in enumerate(filtered_entries):
        is_last = (index == count - 1)
        connector = "└── " if is_last else "├── "
        lines.append(f"{prefix}{connector}{entry.name}")

        if entry.is_dir():
            extension = "    " if is_last else "│   "
            lines.extend(generate_tree(entry, prefix + extension))

    return lines

def main():
    tree_lines = [f"{ROOT_DIR.name}/"]
    tree_lines.extend(generate_tree(ROOT_DIR))
    content = "\n".join(tree_lines) + "\n"

    OUTPUT_FILE.write_text(content, encoding="utf-8")
    print(f"SUCCESS: Generated tree.txt ({len(tree_lines)} entries)")

if __name__ == "__main__":
    main()
