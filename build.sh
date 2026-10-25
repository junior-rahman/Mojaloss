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

def minify_css(c):
    c = re.sub(r'/\*.*?\*/', '', c, flags=re.S)
    c = re.sub(r'\s+', ' ', c)
    c = re.sub(r'\s*([{};:,>~])\s*', r'\1', c)
    return c.replace(';}', '}').strip()

def minify_js(j):
    out = []
    for line in j.splitlines():
        st = line.strip()
        if not st or st.startswith('//'):
            continue
        if '/*' in st and '*/' in st and 'http' not in st and '"' not in st and "\x27" not in st:
            st = re.sub(r'/\*.*?\*/', '', st).strip()
            if not st:
                continue
        st = re.sub(r'([,:;{}=()+\-?<>|&!])\s+', r'\1', st)
        st = re.sub(r'\s+([,:;{}=()+\-?<>|&])', r'\1', st)
        out.append(st)
    res = "\n".join(out)
    replacements = [
        ("catEntry(", "_c("),
        ("function _c(name,year,kind,rating,mins,genres,overview,trend,pop,seriesStartYear,imdbBucket)",
         "var _M=\x27Movie\x27,_S=\x27Series\x27,_R1=\x27TV-MA\x27,_R2=\x27PG-13\x27,_R3=\x27TV-14\x27,_G1=\x27Drama\x27,_G2=\x27Action\x27,_G3=\x27Thriller\x27,_G4=\x27Comedy\x27,_G5=\x27Sci-Fi\x27,_G6=\x27Crime\x27,_G7=\x27Adventure\x27,_G8=\x27Animation\x27,_G9=\x27Mystery\x27,_G0=\x27Fantasy\x27,_GF=\x27Family\x27,_GH=\x27Horror\x27,_GR=\x27Romance\x27,_GD=\x27Documentary\x27,_GB=\x27Biography\x27,_GW=\x27History\x27;function _c(name,year,kind,rating,mins,genres,overview,trend,pop,seriesStartYear,imdbBucket)"),
        (",\x27Movie\x27,", ",_M,"),
        (",\x27Series\x27,", ",_S,"),
        (",\x27TV-MA\x27,", ",_R1,"),
        (",\x27PG-13\x27,", ",_R2,"),
        (",\x27TV-14\x27,", ",_R3,"),
        ("\x27Drama\x27", "_G1"),
        ("\x27Action\x27", "_G2"),
        ("\x27Thriller\x27", "_G3"),
        ("\x27Comedy\x27", "_G4"),
        ("\x27Sci-Fi\x27", "_G5"),
        ("\x27Crime\x27", "_G6"),
        ("\x27Adventure\x27", "_G7"),
        ("\x27Animation\x27", "_G8"),
        ("\x27Mystery\x27", "_G9"),
        ("\x27Fantasy\x27", "_G0"),
        ("\x27Family\x27", "_GF"),
        ("\x27Horror\x27", "_GH"),
        ("\x27Romance\x27", "_GR"),
        ("\x27Documentary\x27", "_GD"),
        ("\x27Biography\x27", "_GB"),
        ("\x27History\x27", "_GW"),
    ]
    for old_s, new_s in replacements:
        res = res.replace(old_s, new_s)
    def trim_cat_overview(m):
        prefix, ov, suffix = m.group(1), m.group(2), m.group(3)
        if len(ov) > 36:
            cut = ov[:33].rsplit(" ", 1)[0].rstrip(".,;:") + "."
            return prefix + cut + suffix
        return m.group(0)
    res = re.sub(r"(_c\([^\n]*?,\[[^\]]*\],')((?:\\'|[^']){37,})(',[0-9])", trim_cat_overview, res)
    res = res.replace("https://www.mojaloss.stream/wp-content/uploads/", "/wp-content/uploads/")
    res = re.sub(r'\n\s*', '\n', res)
    return res

s = minify_css(s)
js_raw = "\n".join([
    open('build/js/logos.js').read(),
    open('build/js/api.js').read(),
    open('build/js/focus.js').read(),
    open('build/js/app.js').read()
])
js_bundle = minify_js(js_raw)
canonical = f"{ver}\n{vcode}\n{s}\n{js_bundle}"
sha = hashlib.sha256(canonical.encode('utf-8')).hexdigest()
bundle = {
    "version": ver,
    "versionCode": vcode,
    "sha256": sha,
    "releasedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "notes": f"v{ver}: Automatic OTA updates, quota-safe localStorage installer & cache eviction so updates install on the first click without looping, plus IMDb Hub, Popular Movies/Series of All Time, and 5-card scroll",
    "css": s,
    "js": js_bundle
}
raw_out = json.dumps(bundle, ensure_ascii=False, separators=(',', ':'))
for out in ('dist/tv-ota-bundle.json', 'dist/tv-manifest.json'):
    with open(out, 'w', encoding='utf-8') as f:
        f.write(raw_out)
print(f"OTA bundle ready: v{ver} (versionCode={vcode}, {len(raw_out)} bytes) sha256={sha}")
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
python3 - "$T" "$PW" "$DIST_PW" <<'SIGN'
import pty, os, sys, select, time

tizen_bin, pw, dist_pw = sys.argv[1:]
pid, fd = pty.fork()
if pid == 0:
    os.chdir("build")
    os.execv(tizen_bin, [tizen_bin, "package", "-t", "wgt", "-s", "mltv", "--", "."])
else:
    start = time.time()
    while time.time() - start < 20:
        r, _, _ = select.select([fd], [], [], 0.5)
        if r:
            try:
                data = os.read(fd, 4096)
            except OSError:
                break
            if not data:
                break
            low = data.decode("utf-8", "ignore").lower()
            if "yes:" in low or "(y/n)" in low or "[y/n]" in low or "default" in low:
                os.write(fd, b"y\n")
            elif "distributor" in low and "password:" in low:
                os.write(fd, (dist_pw + "\n").encode())
            elif "password:" in low:
                os.write(fd, (pw + "\n").encode())
    os.waitpid(pid, 0)
SIGN
V=$(grep -o 'version="[0-9.]*"' app/config.xml | head -2 | tail -1 | cut -d'"' -f2)
cp build/MojaLoss.wgt "MojaLoss-tv-$V.wgt"; unzip -l "MojaLoss-tv-$V.wgt" | grep -q signature1.xml && sha256sum "MojaLoss-tv-$V.wgt"
