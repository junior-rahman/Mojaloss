#!/bin/bash
# Build MojaLoss TV .wgt + OTA single-file bundles (dist/tv-ota-bundle.json and dist/tv-manifest.json).
set -eu
cd "$(dirname "$0")"
source /workspace/mojaloss-work/env.sh
rm -rf build dist && mkdir -p build dist && cp -r app/* build/
python3 - <<'P'
import re, json, hashlib, datetime

s = open('build/css/app.css').read()
vars = dict(re.findall(r'--([\w-]+):([^;}]+)', re.search(r':root\{([^}]*)\}', s).group(1)))
s = re.sub(r':root\{[^}]*\}', '', s)
s = re.sub(r'var\(--([\w-]+)\)', lambda m: vars[m.group(1)].strip(), s)
assert 'var(' not in s
open('build/css/app.css', 'w').write(s)

cfg = open('app/config.xml').read()
ver = re.search(r'<widget[^>]+version="([0-9.]+)"', cfg).group(1)
parts = [int(x) for x in ver.split('.')]
vcode = parts[0] * 10000 + (parts[1] - 1) * 100 + parts[2]

logos_src = open('build/js/logos.js').read()
logos_obj = json.loads(re.search(r'var PlatformLogos\s*=\s*(\{.*\});', logos_src, re.S).group(1))
js_bundle = "\n".join([
    open('build/js/logos.js').read(),
    open('build/js/api.js').read(),
    open('build/js/focus.js').read(),
    open('build/js/app.js').read()
])
canonical = f"{ver}\n{vcode}\n{s}\n{js_bundle}"
sha = hashlib.sha256(canonical.encode('utf-8')).hexdigest()
bundle = {
    "version": ver,
    "versionCode": vcode,
    "sha256": sha,
    "releasedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "notes": f"MojaLoss TV v{ver} OTA bundle",
    "css": s,
    "js": js_bundle,
    "logos": logos_obj
}
for out in ('dist/tv-ota-bundle.json', 'dist/tv-manifest.json'):
    with open(out, 'w') as f:
        json.dump(bundle, f, indent=2)
print(f"OTA bundle ready: v{ver} (versionCode={vcode}) sha256={sha}")
P
T=$HOME/tizen-studio/tools/ide/bin/tizen; PW=$(cat $HOME/.mltv-author-pw)
# If Samsung VD certificates exist in ~/SamsungCertificate/mltv, use them; otherwise use fallback Tizen certs.
python3 - "$HOME/tizen-studio-data/profile/profiles.xml" "$PW" <<'Q'
import os, sys, re
f, pw = sys.argv[1:]
sam_dir = os.path.expanduser('~/SamsungCertificate/mltv')
if os.path.exists(os.path.join(sam_dir, 'author.p12')) and os.path.exists(os.path.join(sam_dir, 'distributor.p12')):
    ca_dir = os.path.abspath('samsung-cert/ca')
    xml = f"""<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<profiles active="mltv" version="3.1">
<profile name="mltv">
<profileitem ca="{ca_dir}/vd_tizen_dev_author_ca.cer" distributor="0" key="{sam_dir}/author.p12" password="{pw}" rootca=""/>
<profileitem ca="{ca_dir}/vd_tizen_dev_public2.crt" distributor="1" key="{sam_dir}/distributor.p12" password="{pw}" rootca=""/>
<profileitem ca="" distributor="2" key="" password="" rootca=""/>
</profile>
</profiles>
"""
    open(f, 'w').write(xml)
    open('/tmp/.mltv_dist_pw', 'w').write(pw)
else:
    s = open(f).read()
    s = re.sub(r'(mltv-author.p12" password=")[^"]*', lambda m: m.group(1) + pw, s)
    s = re.sub(r'(tizen-distributor-signer.p12" password=")[^"]*', lambda m: m.group(1) + 'tizenpkcs12passfordsigner', s)
    open(f, 'w').write(s)
    open('/tmp/.mltv_dist_pw', 'w').write('tizenpkcs12passfordsigner')
Q
chmod 600 "$HOME/tizen-studio-data/profile/profiles.xml"
DIST_PW=$(cat /tmp/.mltv_dist_pw); rm -f /tmp/.mltv_dist_pw
(cd build && (sleep 3; echo "$PW"; sleep 2; echo "$DIST_PW"; sleep 6) 2>/dev/null | script -qc "$T package -t wgt -s mltv -- ." /dev/null >/dev/null 2>&1)
V=$(grep -o 'version="[0-9.]*"' app/config.xml | head -2 | tail -1 | cut -d'"' -f2)
cp build/MojaLoss.wgt "MojaLoss-tv-$V.wgt"; unzip -l "MojaLoss-tv-$V.wgt" | grep -q signature1.xml && sha256sum "MojaLoss-tv-$V.wgt"
