#!/usr/bin/env python3
"""Prepare public metadata and validate the local Play release. Never uploads."""
import argparse
import hashlib
import html
import json
from pathlib import Path
import re
import sys
from urllib.parse import urlparse
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = ("developerName", "supportEmail", "privacyPolicyUrl")

def config():
    path = ROOT / "publishing/release.properties"
    values = {}
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip() and not line.lstrip().startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    return values

def metadata_errors(values):
    errors = [f"Set {key} in publishing/release.properties" for key in REQUIRED if not values.get(key)]
    if values.get("supportEmail") and not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", values["supportEmail"]):
        errors.append("supportEmail must be a valid public contact address")
    if values.get("privacyPolicyUrl"):
        url = urlparse(values["privacyPolicyUrl"])
        if url.scheme != "https" or not url.hostname or url.username or url.password:
            errors.append("privacyPolicyUrl must be a public HTTPS page")
    if any("\n" in value or "\r" in value or len(value) > 300 for value in values.values()):
        errors.append("Public metadata values must be single lines of at most 300 characters")
    return errors

def prepare_site(values, draft=False):
    source = ROOT / "app/src/main/assets/privacy-policy.txt"
    contact = values.get("supportEmail", "Publisher contact pending")
    developer = values.get("developerName", "Publisher name pending")
    policy = html.escape(source.read_text(encoding="utf-8"))
    banner = "<p class='draft'>DRAFT BETA POLICY — fill publisher details before hosting.</p>" if draft else ""
    page = f"""<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Daybook Privacy Policy</title><style>body{{max-width:52rem;margin:3rem auto;padding:0 1.5rem;font:17px/1.65 system-ui;color:#26212f;background:#faf8ff}}pre{{font:inherit;white-space:pre-wrap;overflow-wrap:anywhere}}.draft{{padding:1rem;background:#fff0c5}}a{{color:#6551c5}}</style>
<main>{banner}<h1>Daybook Privacy Policy</h1><p>Developer: {html.escape(developer)}<br>Contact: {html.escape(contact)}</p><pre>{policy}</pre></main></html>"""
    folder = ROOT / "publishing/site"
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "privacy.html").write_text(page, encoding="utf-8")
    (folder / "index.html").write_text(page, encoding="utf-8")
    print("Prepared publishing/site/privacy.html" + (" (DRAFT)" if draft else ""))

def check_artifacts(errors):
    source = (ROOT / "app/build.gradle.kts").read_text()
    version = re.search(r'versionName = "([^"]+)"', source).group(1)
    for ext in ("apk", "aab"):
        path = ROOT / f"releases/daybook-{version}.{ext}"
        if not path.is_file():
            errors.append(f"Missing signed release artifact: {path.relative_to(ROOT)}")
            continue
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            if any(name.endswith((".gguf", ".jks", ".daybook")) for name in names):
                errors.append(f"{ext}: private/model files unexpectedly packaged")
            required = ["privacy-policy.txt", "whisper.cpp-LICENSE.txt", "llama.cpp-LICENSE.txt"]
            for name in required:
                if not any(n.endswith("/" + name) for n in names):
                    errors.append(f"{ext}: missing notice {name}")
        print(path.name, hashlib.sha256(path.read_bytes()).hexdigest())
    site = ROOT / "publishing/site/privacy.html"
    if not site.exists() or "DRAFT BETA POLICY" in site.read_text():
        errors.append("Prepare the final public policy site with complete publisher metadata")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--prepare-site", action="store_true")
    parser.add_argument("--allow-draft", action="store_true", help="Only permits draft site generation; never bypasses --check")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    values = config()
    errors = metadata_errors(values)
    if args.prepare_site:
        if errors and not args.allow_draft:
            print("\n".join(errors), file=sys.stderr)
            return 2
        prepare_site(values, draft=bool(errors))
    if args.check:
        check_artifacts(errors)
        if errors:
            print("NOT READY FOR PUBLIC SUBMISSION:\n- " + "\n- ".join(errors))
            return 2
        print("Local package checks passed. Hosted policy, device results, Play declarations, and tester requirements still need external verification.")
    elif not args.prepare_site:
        parser.print_help()
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
