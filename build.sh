#!/bin/bash
# Build MojaLoss TV .wgt: copy app/, flatten CSS custom properties (Tizen 3 / Chromium 47 has none), sign with the 'mltv' profile.
set -eu
cd "$(dirname "$0")"
source /workspace/mojaloss-work/env.sh
rm -rf build && mkdir build && cp -r app/* build/
python3 - <<'P'
import re
s=open('build/css/app.css').read()
vars=dict(re.findall(r'--([\w-]+):([^;}]+)', re.search(r':root\{([^}]*)\}', s).group(1)))
s=re.sub(r':root\{[^}]*\}','',s)
s=re.sub(r'var\(--([\w-]+)\)', lambda m: vars[m.group(1)].strip(), s)
assert 'var(' not in s
open('build/css/app.css','w').write(s)
P
T=$HOME/tizen-studio/tools/ide/bin/tizen; PW=$(cat $HOME/.mltv-author-pw)
# The CLI cannot read its own encrypted passwords on a headless box; put them back as plain text (file is mode 600, outside git).
python3 - "$HOME/tizen-studio-data/profile/profiles.xml" "$PW" <<'Q'
import sys,re
f,pw=sys.argv[1:];s=open(f).read()
s=re.sub(r'(mltv-author.p12" password=")[^"]*',lambda m:m.group(1)+pw,s)
s=re.sub(r'(tizen-distributor-signer.p12" password=")[^"]*',lambda m:m.group(1)+'tizenpkcs12passfordsigner',s)
open(f,'w').write(s)
Q
chmod 600 "$HOME/tizen-studio-data/profile/profiles.xml"
(cd build && (sleep 3; echo "$PW"; sleep 2; echo tizenpkcs12passfordsigner; sleep 6) 2>/dev/null | script -qc "$T package -t wgt -s mltv -- ." /dev/null >/dev/null 2>&1)
V=$(grep -o 'version="[0-9.]*"' app/config.xml | head -2 | tail -1 | cut -d'"' -f2)
cp build/MojaLoss.wgt "MojaLoss-tv-$V.wgt"; unzip -l "MojaLoss-tv-$V.wgt" | grep -q signature1.xml && sha256sum "MojaLoss-tv-$V.wgt"
