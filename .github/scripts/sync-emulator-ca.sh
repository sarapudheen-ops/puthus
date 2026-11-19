#!/usr/bin/env bash
# A stock API-24 image has a 2016 CA store. Use the runner's current, already
# trusted CA bundle for this disposable emulator only. Never modify the APK,
# disable TLS/hostname validation, or accept a certificate fetched from a server.
set -euo pipefail

bundle=/etc/ssl/certs/ca-certificates.crt
[ -s "$bundle" ] || { echo 'Runner CA bundle is missing' >&2; exit 1; }
mkdir -p .local/device/reports .local/device/runner-cas
adb root
adb wait-for-device
adb remount
adb shell ls /system/etc/security/cacerts > .local/device/runner-cas/existing.txt
python3 - "$bundle" <<'PY'
from hashlib import sha256
from pathlib import Path
import re
import socket
import ssl
import subprocess
import sys

out = Path('.local/device/runner-cas')
existing = set(out.joinpath('existing.txt').read_text().split())
roots = re.findall(rb'-----BEGIN CERTIFICATE-----\s+.*?-----END CERTIFICATE-----', Path(sys.argv[1]).read_bytes(), re.S)
if not roots:
    raise SystemExit('No certificates in runner CA bundle')
install = out / 'install'
install.mkdir(exist_ok=True)
for pem in roots:
    result = subprocess.run(['openssl', 'x509', '-subject_hash_old', '-noout'], input=pem, capture_output=True, check=True)
    name = result.stdout.decode().strip()
    if not re.fullmatch('[0-9a-f]{8}', name):
        raise SystemExit('Invalid Android CA filename')
    suffix = 0
    while f'{name}.{suffix}' in existing:
        suffix += 1
    filename = f'{name}.{suffix}'
    existing.add(filename)
    (install / filename).write_bytes(pem + b'\n')

lines = [
    '## Disposable emulator TLS trust', '',
    'Android 24 runtime with the runner’s current system CA bundle. This is not proof that a stock 2016 CA store trusts today’s release host.',
    'Only the emulator system trust store was updated; the actual APK has no test CA, custom trust manager, or TLS/hostname-verification bypass.', '',
    f'Runner CA bundle SHA-256: `{sha256(Path(sys.argv[1]).read_bytes()).hexdigest()}`; {len(roots)} roots added without replacing existing device roots.', '',
]
# Diagnose old-image roots versus an execution-environment HTTPS proxy. This
# handshake uses normal runner certificate/hostname verification and adds NO CA.
for host in ('github.com', 'release-assets.githubusercontent.com'):
    with socket.create_connection((host, 443), timeout=20) as raw:
        with ssl.create_default_context().wrap_socket(raw, server_hostname=host) as secure:
            cert = secure.getpeercert()
            lines += [f'- `{host}`: verified issuer `{cert.get("issuer")}`; leaf SHA-256 `{sha256(secure.getpeercert(binary_form=True)).hexdigest()}`.']
Path('.local/device/reports/tls-trust.md').write_text('\n'.join(lines) + '\n')
print(f'Prepared {len(roots)} existing runner-trusted CA certificates for disposable emulator')
PY
adb push .local/device/runner-cas/install/. /system/etc/security/cacerts/
adb shell chmod 644 '/system/etc/security/cacerts/*'
adb shell chown root:root '/system/etc/security/cacerts/*'
# App and instrumentation processes have not been started yet. Their platform
# default trust managers will read this system store normally on first use.
