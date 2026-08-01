#!/usr/bin/env python3
"""
publish-repo.py — Publish built extension APKs to the MHRepo repository.

Usage:
  python publish-repo.py                    # Publish mode (default)
  python publish-repo.py --cleanup          # Cleanup mode (remove orphaned extensions)
  python publish-repo.py --cleanup --dry    # Cleanup dry-run (list what would be deleted)

Publish mode:
  Reads built APKs from the current repo, extracts metadata, and pushes
  them to the MHRepo repository (checked out at /tmp/MHRepo). Updates
  index.json with the new/updated entries.

Cleanup mode:
  Removes APKs and icons from MHRepo that no longer have a corresponding
  source in MHExtensions. The list of valid package names is extracted
  from settings.gradle.kts (loadIndividualExtension calls).

Environment variables:
  REPO_PAT    — GitHub PAT for the MHRepo repository (required)
  REPO_OWNER  — Owner of the MHRepo repo (default: marbou92)
  REPO_NAME   — Name of the MHRepo repo (default: MHRepo)
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

# =============================================================================
# Configuration
# =============================================================================

REPO_OWNER = os.environ.get("REPO_OWNER", "marbou92")
REPO_NAME = os.environ.get("REPO_NAME", "MHRepo")
REPO_URL = f"https://x-access-token:{os.environ['REPO_PAT']}@github.com/{REPO_OWNER}/{REPO_NAME}.git"
REPO_DIR = Path("/tmp/MHRepo")
INDEX_FILE = REPO_DIR / "index.json"

# jsDelivr CDN base URL for APK/icon/JAR URLs in the index
JSDELIVR_BASE = f"https://cdn.jsdelivr.net/gh/{REPO_OWNER}/{REPO_NAME}@main"

# Source repo root (where settings.gradle.kts lives)
SOURCE_ROOT = Path(os.environ.get("GITHUB_WORKSPACE", "."))

# Icon density priority (highest first)
ICON_DENSITIES = ["xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "mdpi"]


# =============================================================================
# Helpers
# =============================================================================

def run(cmd, **kwargs):
    """Run a shell command, exit on failure."""
    result = subprocess.run(cmd, shell=True, **kwargs)
    if result.returncode != 0:
        print(f"ERROR: Command failed: {cmd}", file=sys.stderr)
        sys.exit(1)
    return result


def git(*args, cwd=REPO_DIR):
    """Run a git command in the repo directory."""
    cmd = ["git"] + list(args)
    result = subprocess.run(cmd, cwd=str(cwd), capture_output=True, text=True)
    if result.returncode != 0:
        print(f"git {' '.join(args)} failed:", file=sys.stderr)
        print(result.stderr, file=sys.stderr)
        sys.exit(1)
    return result.stdout.strip()


def get_valid_packages():
    """
    Extract all valid extension package names from settings.gradle.kts.
    Returns a set of full package names like:
      eu.kanade.tachiyomi.extension.all.comixto
      eu.kanade.tachiyomi.extension.en.manhuarmtl
    """
    settings_file = SOURCE_ROOT / "settings.gradle.kts"
    if not settings_file.exists():
        print(f"ERROR: {settings_file} not found", file=sys.stderr)
        sys.exit(1)

    content = settings_file.read_text()
    # Match: loadIndividualExtension("lang", "name")
    pattern = re.compile(r'loadIndividualExtension\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)')
    matches = pattern.findall(content)

    packages = set()
    for lang, name in matches:
        packages.add(f"eu.kanade.tachiyomi.extension.{lang}.{name}")

    print(f"Found {len(packages)} valid package(s) in settings.gradle.kts:")
    for pkg in sorted(packages):
        print(f"  - {pkg}")
    return packages


def clone_repo():
    """Clone or pull the MHRepo repository."""
    if REPO_DIR.exists():
        shutil.rmtree(REPO_DIR)
    print(f"Cloning {REPO_OWNER}/{REPO_NAME}...")
    run(f"git clone --depth 1 {REPO_URL} {REPO_DIR}")


def load_index():
    """Load the existing index.json, or return empty structure if it doesn't exist."""
    if not INDEX_FILE.exists():
        return {"info": [], "latest": 0, "blocked": []}
    with open(INDEX_FILE) as f:
        return json.load(f)


def save_index(index):
    """Save index.json with pretty formatting."""
    with open(INDEX_FILE, "w") as f:
        json.dump(index, f, indent=2, ensure_ascii=False)
        f.write("\n")


# =============================================================================
# Cleanup mode
# =============================================================================

def cleanup(dry_run=False):
    """Remove orphaned extensions from MHRepo that no longer exist in MHExtensions."""
    valid_packages = get_valid_packages()

    clone_repo()
    index = load_index()

    if "info" not in index or not isinstance(index["info"], list):
        print("WARNING: index.json has no 'info' array, nothing to clean up")
        return

    original_count = len(index["info"])
    kept_entries = []
    deleted_entries = []

    for entry in index["info"]:
        pkg = entry.get("pkg", "")
        if pkg in valid_packages:
            kept_entries.append(entry)
        else:
            deleted_entries.append(entry)

    if not deleted_entries:
        print("No orphaned extensions found. Nothing to clean up.")
        return

    print(f"\nFound {len(deleted_entries)} orphaned extension(s) to remove:")
    for entry in deleted_entries:
        pkg = entry.get("pkg", "?")
        name = entry.get("name", "?")
        print(f"  - {name} ({pkg})")

    if dry_run:
        print("\n[DRY RUN] No files were deleted. Re-run without --dry to actually delete.")
        return

    # Delete APK and icon files for orphaned entries
    for entry in deleted_entries:
        for field in ["apk", "icon"]:
            rel_path = entry.get(field, "")
            if not rel_path:
                continue
            # Strip CDN prefix if present
            if rel_path.startswith("http"):
                rel_path = rel_path.split("@main/")[-1] if "@main/" in rel_path else rel_path.split("/main/")[-1]
            file_path = REPO_DIR / rel_path
            if file_path.exists():
                file_path.unlink()
                print(f"  Deleted file: {rel_path}")
            else:
                print(f"  File not found (skipped): {rel_path}")

    # Update index
    index["info"] = kept_entries
    save_index(index)

    print(f"\nCleanup complete: {original_count} → {len(kept_entries)} entries")

    # Commit and push
    git("add", "-A", cwd=REPO_DIR)
    status = git("status", "--porcelain", cwd=REPO_DIR)
    if not status:
        print("No changes to commit.")
        return

    commit_msg = "chore: cleanup orphaned extensions\n\nRemoved:\n"
    for entry in deleted_entries:
        commit_msg += f"- {entry.get('name', '?')} ({entry.get('pkg', '?')})\n"

    git("commit", "-m", commit_msg, cwd=REPO_DIR)
    git("push", cwd=REPO_DIR)
    print("Pushed cleanup commit to MHRepo.")


# =============================================================================
# Publish mode
# =============================================================================

def find_built_apks():
    """Find all RELEASE APK files in the repo (skip debug builds)."""
    apks = []
    for root, dirs, files in os.walk(SOURCE_ROOT):
        # Skip .gradle, build intermediates, etc.
        if ".gradle" in root or "intermediates" in root:
            continue
        for f in files:
            if f.endswith(".apk") and "-release" in f:
                apks.append(Path(root) / f)
    return apks


def parse_apk_metadata_from_filename(apk_path):
    """
    Parse metadata from the APK filename.
    Keiyoushi build produces: tachiyomi-{lang}.{name}-v{version}-release.apk
    where version = {libVersion}.{finalVersionCode}

    Examples:
      tachiyomi-en.manhuarmtl-v1.4.55-release.apk
        → pkg=eu.kanade.tachiyomi.extension.en.manhuarmtl
        → lang=en, name=manhuarmtl, version=1.4.55, code=55

      tachiyomi-all.comixto-v1.4.21-release.apk
        → pkg=eu.kanade.tachiyomi.extension.all.comixto
        → lang=all, name=comixto, version=1.4.21, code=21
    """
    filename = apk_path.name
    # Match: tachiyomi-{lang}.{name}-v{version}-release.apk
    match = re.match(r"tachiyomi-(.+?)\.(.+?)-v(.+?)-release\.apk$", filename)
    if not match:
        print(f"  WARNING: Could not parse filename: {filename}", file=sys.stderr)
        return None

    lang = match.group(1)
    ext_name = match.group(2)
    version = match.group(3)

    # version is like "1.4.55" — code is the last numeric segment
    version_parts = version.split(".")
    code = int(version_parts[-1]) if version_parts[-1].isdigit() else 0

    pkg = f"eu.kanade.tachiyomi.extension.{lang}.{ext_name}"

    return {
        "pkg": pkg,
        "lang": lang,
        "name": ext_name,
        "code": code,
        "version": version,
        "nsfw": False,  # Refined below from build.gradle.kts
    }


def extract_icon(apk_path, output_dir):
    """
    Extract the highest-density icon from the APK.
    Tries xxxhdpi → xxhdpi → xhdpi → hdpi → mdpi.
    Returns the path to the extracted icon file.
    """
    import zipfile

    with zipfile.ZipFile(apk_path, "r") as z:
        for density in ICON_DENSITIES:
            icon_path = f"res/mipmap-{density}/ic_launcher.png"
            try:
                z.extract(icon_path, output_dir)
                extracted = Path(output_dir) / icon_path
                if extracted.exists():
                    return extracted
            except KeyError:
                continue

        # Fallback: any ic_launcher.png
        for name in z.namelist():
            if "ic_launcher.png" in name and "mipmap" in name:
                z.extract(name, output_dir)
                return Path(output_dir) / name

    return None


def publish():
    """Publish built APKs to MHRepo."""
    clone_repo()
    index = load_index()

    apks = find_built_apks()
    if not apks:
        print("No APKs found to publish.")
        return

    print(f"\nFound {len(apks)} APK(s) to publish:")
    for apk in apks:
        print(f"  - {apk}")

    # Index lookup by package name for easy update
    info_by_pkg = {e.get("pkg"): e for e in index.get("info", [])}

    for apk_path in apks:
        meta = parse_apk_metadata_from_filename(apk_path)
        if meta is None:
            print(f"  Skipping {apk_path} (could not extract metadata)")
            continue

        pkg = meta["pkg"]
        code = meta["code"]
        version = meta["version"]
        lang = meta["lang"]

        # Read app name and NSFW flag from the extension's build.gradle.kts
        gradle_file = SOURCE_ROOT / "src" / lang / meta["name"] / "build.gradle.kts"
        app_name = meta["name"].capitalize()
        is_nsfw = False
        if gradle_file.exists():
            content = gradle_file.read_text()
            name_match = re.search(r'name\s*=\s*"([^"]+)"', content)
            if name_match:
                app_name = name_match.group(1)
            if "ContentWarning.NSFW" in content:
                is_nsfw = True

        name = app_name

        print(f"\n  Publishing {name} ({pkg}) v{version} (code {code})...")

        # Copy APK to repo
        apk_filename = f"tachiyomi-{pkg}-v{code}.apk"
        apk_dest = REPO_DIR / apk_filename
        shutil.copy2(apk_path, apk_dest)

        # Extract and copy icon
        icon_temp = Path("/tmp/icon_extract")
        if icon_temp.exists():
            shutil.rmtree(icon_temp)
        icon_temp.mkdir(parents=True)

        icon_file = extract_icon(apk_path, icon_temp)
        icon_filename = f"icon-{pkg}.png"
        icon_dest = REPO_DIR / icon_filename

        if icon_file:
            shutil.copy2(icon_file, icon_dest)
        elif icon_dest.exists():
            print(f"    WARNING: Could not extract icon, keeping existing {icon_filename}")
        else:
            print(f"    WARNING: No icon found for {pkg}")

        # Build index entry
        entry = {
            "name": name,
            "pkg": pkg,
            "apk": f"{JSDELIVR_BASE}/{apk_filename}",
            "lang": lang,
            "code": code,
            "version": version,
            "nsfw": is_nsfw,
            "hasUpdate": True,
            "hasReadme": False,
            "hasChangelog": False,
            "sources": [],
        }

        if icon_file or icon_dest.exists():
            entry["icon"] = f"{JSDELIVR_BASE}/{icon_filename}"

        # Update or add entry
        info_by_pkg[pkg] = entry
        print(f"    Updated index entry for {pkg}")

    # Rebuild info array (preserve order, add new entries at end)
    existing_pkgs = {e.get("pkg") for e in index.get("info", [])}
    new_info = []
    for entry in index.get("info", []):
        pkg = entry.get("pkg")
        if pkg in info_by_pkg:
            new_info.append(info_by_pkg[pkg])
    # Add new entries
    for pkg, entry in info_by_pkg.items():
        if pkg not in existing_pkgs:
            new_info.append(entry)

    index["info"] = new_info
    save_index(index)

    # Commit and push
    git("add", "-A", cwd=REPO_DIR)
    status = git("status", "--porcelain", cwd=REPO_DIR)
    if not status:
        print("\nNo changes to commit.")
        return

    git("commit", "-m", f"chore: publish extensions\n\n{len(apks)} extension(s) updated", cwd=REPO_DIR)
    git("push", cwd=REPO_DIR)
    print(f"\nPublished {len(apks)} extension(s) to MHRepo.")


# =============================================================================
# Main
# =============================================================================

def main():
    parser = argparse.ArgumentParser(description="Publish extensions to MHRepo")
    parser.add_argument(
        "--cleanup",
        action="store_true",
        help="Remove orphaned extensions from MHRepo that no longer exist in MHExtensions",
    )
    parser.add_argument(
        "--dry",
        action="store_true",
        help="Dry run (only used with --cleanup): list what would be deleted without deleting",
    )
    args = parser.parse_args()

    if "REPO_PAT" not in os.environ:
        print("ERROR: REPO_PAT environment variable is required", file=sys.stderr)
        sys.exit(1)

    if args.cleanup:
        cleanup(dry_run=args.dry)
    else:
        publish()


if __name__ == "__main__":
    main()
