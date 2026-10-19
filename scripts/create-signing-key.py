#!/usr/bin/env python3
"""Create a private local release-signing key; never replaces an existing key."""
from pathlib import Path
import os
import secrets
import subprocess

root = Path(__file__).resolve().parent.parent
folder = root / ".tools" / "signing"
folder.mkdir(parents=True, exist_ok=True)
folder.chmod(0o700)
keystore = folder / "daybook.jks"
password = folder / "keystore.pass"
if keystore.exists():
    if not password.exists():
        raise SystemExit("Signing key exists but its password file is missing. Restore it; do not replace this key.")
    print("Existing personal signing key retained.")
else:
    if password.exists():
        raise SystemExit("Password file already exists without its key. Restore the key or inspect this folder before proceeding.")
    password.write_text(secrets.token_urlsafe(36))
    password.chmod(0o600)
    java_root = Path(os.environ.get("JAVA_HOME", str(root / ".tools" / "android" / "jdk")))
    subprocess.run([
        str(java_root / "bin" / "keytool"), "-genkeypair", "-keystore", str(keystore),
        "-storepass:file", str(password), "-keypass:file", str(password), "-alias", "daybook",
        "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000", "-dname", "CN=Daybook Personal",
        "-storetype", "PKCS12", "-noprompt"
    ], check=True)
    keystore.chmod(0o600)
    print("Personal signing key created in ignored .tools/signing/. Keep this folder safe for future app updates.")
