#!/usr/bin/env python3
"""
1-Click Samsung TV Certificate Generator + WGT Signer for MojaLoss TV.
Listens on http://localhost:4794/signin/callback (official Samsung Certificate Extension OAuth port),
requests official Samsung VD (TV) Author & Distributor certificates for DUID H3CMR3DM47BTG,
updates Tizen profiles.xml, and re-signs MojaLoss-tv-0.1.2.wgt automatically.
"""
import http.server
import json
import os
import subprocess
import sys
import threading
import urllib.parse
import webbrowser

DUID = os.environ.get("TV_DUID", "H3CMR3DM47BTG")
LOGIN_URL = "https://account.samsung.com/mobile/account/check.do?serviceID=v285zxnl3h&actionID=StartOAuth2&accessToken=Y&redirect_uri=http://localhost:4794/signin/callback"
AUTHOR_URL = "https://svdca.samsungqbe.com/apis/v3/authors"
DIST_URL = "https://svdca.samsungqbe.com/apis/v3/distributors"

HERE = os.path.dirname(os.path.abspath(__file__))
TV_DIR = os.path.dirname(HERE)
CA_DIR = os.path.join(HERE, "ca")
OUT_DIR = os.path.expanduser("~/SamsungCertificate/mltv")

def run(cmd):
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"Command failed: {cmd}\n{r.stderr}")
    return r.stdout

def generate_and_sign(access_token, user_id, email):
    os.makedirs(OUT_DIR, exist_ok=True)
    pw_file = os.path.expanduser("~/.mltv-author-pw")
    pw = open(pw_file).read().strip() if os.path.exists(pw_file) else "mltvpass1234"

    author_ca = os.path.join(CA_DIR, "vd_tizen_dev_author_ca.cer")
    dist_ca = os.path.join(CA_DIR, "vd_tizen_dev_public2.crt")

    print(f"[1/4] Generating Author CSR & requesting Samsung VD Author Certificate for {email}...")
    author_key = os.path.join(OUT_DIR, "author.key.pem")
    author_csr = os.path.join(OUT_DIR, "author.csr")
    author_crt = os.path.join(OUT_DIR, "author.crt")
    author_chain = os.path.join(OUT_DIR, "author-and-ca.crt")
    author_p12 = os.path.join(OUT_DIR, "author.p12")

    run(f'openssl genrsa -out "{author_key}" 2048')
    run(f'openssl req -new -key "{author_key}" -out "{author_csr}" -subj "/CN=MojaLossTV"')
    run(
        f'curl -sS -f -X POST "{AUTHOR_URL}" '
        f'-F "access_token={access_token}" '
        f'-F "user_id={user_id}" '
        f'-F "platform=VD" '
        f'-F "csr=@{author_csr};filename=author.csr" '
        f'--output "{author_crt}"'
    )
    with open(author_chain, "wb") as out:
        a_bytes = open(author_crt, "rb").read()
        out.write(a_bytes)
        if not a_bytes.endswith(b"\n"):
            out.write(b"\n")
        out.write(open(author_ca, "rb").read())
    run(f'openssl pkcs12 -export -out "{author_p12}" -inkey "{author_key}" -in "{author_chain}" -name usercertificate -legacy -passout "pass:{pw}"')

    print(f"[2/4] Generating Distributor CSR & requesting Samsung VD Distributor Certificate for DUID={DUID}...")
    dist_key = os.path.join(OUT_DIR, "distributor.key.pem")
    dist_csr = os.path.join(OUT_DIR, "distributor.csr")
    dist_crt = os.path.join(OUT_DIR, "distributor.crt")
    dist_chain = os.path.join(OUT_DIR, "distributor-and-ca.crt")
    dist_p12 = os.path.join(OUT_DIR, "distributor.p12")

    san = f"URI:URN:tizen:packageid=,URI:URN:tizen:deviceid={DUID}"
    run(f'openssl genrsa -out "{dist_key}" 2048')
    run(f'openssl req -new -key "{dist_key}" -out "{dist_csr}" -subj "/CN=TizenSDK/emailAddress={email}" -addext "subjectAltName = {san}"')
    run(
        f'curl -sS -f -X POST "{DIST_URL}" '
        f'-F "access_token={access_token}" '
        f'-F "user_id={user_id}" '
        f'-F "platform=VD" '
        f'-F "privilege_level=Public" '
        f'-F "developer_type=Individual" '
        f'-F "csr=@{dist_csr};filename=distributor.csr" '
        f'--output "{dist_crt}"'
    )
    with open(dist_chain, "wb") as out:
        d_bytes = open(dist_crt, "rb").read()
        out.write(d_bytes)
        if not d_bytes.endswith(b"\n"):
            out.write(b"\n")
        out.write(open(dist_ca, "rb").read())
    run(f'openssl pkcs12 -export -out "{dist_p12}" -inkey "{dist_key}" -in "{dist_chain}" -name usercertificate -legacy -passout "pass:{pw}"')

    print("[3/4] Updating Tizen Studio signing profile (profiles.xml)...")
    prof_dir = os.path.expanduser("~/tizen-studio-data/profile")
    os.makedirs(prof_dir, exist_ok=True)
    prof_xml = os.path.join(prof_dir, "profiles.xml")
    xml_content = f"""<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<profiles active="mltv" version="3.1">
<profile name="mltv">
<profileitem ca="{author_ca}" distributor="0" key="{author_p12}" password="{pw}" rootca=""/>
<profileitem ca="{dist_ca}" distributor="1" key="{dist_p12}" password="{pw}" rootca=""/>
<profileitem ca="" distributor="2" key="" password="" rootca=""/>
</profile>
</profiles>
"""
    with open(prof_xml, "w") as f:
        f.write(xml_content)
    os.chmod(prof_xml, 0o600)

    print("[4/4] Re-signing MojaLoss-tv-0.1.2.wgt with Samsung TV certificate...")
    run(f'cd "{TV_DIR}" && ./build.sh')
    print("\nSUCCESS! MojaLoss-tv-0.1.2.wgt is now signed for Samsung TV DUID " + DUID)

class CallbackHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _handle_code(self, code_str):
        data = json.loads(code_str)
        access_token = data.get("access_token")
        user_id = data.get("userId")
        email = data.get("inputEmailID") or "developer@samsung.com"
        generate_and_sign(access_token, user_id, email)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        if u.path == "/signin/callback":
            qs = urllib.parse.parse_qs(u.query)
            code = (qs.get("code") or [None])[0]
            if code:
                try:
                    self._handle_code(code)
                    self.send_response(200)
                    self.send_header("Content-Type", "text/html; charset=utf-8")
                    self.end_headers()
                    self.wfile.write(b"<html><body style='font-family:sans-serif;background:#101B17;color:#EDF3EC;padding:40px'><h1>Samsung TV Certificate Created & WGT Signed!</h1><p>MojaLoss-tv-0.1.2.wgt is now signed for DUID H3CMR3DM47BTG.</p></body></html>")
                    threading.Thread(target=self.server.shutdown, daemon=True).start()
                    return
                except Exception as e:
                    self.send_response(500)
                    self.end_headers()
                    self.wfile.write(f"Error: {e}".encode())
                    return
        self.send_response(302)
        self.send_header("Location", LOGIN_URL)
        self.end_headers()

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8")
        qs = urllib.parse.parse_qs(body)
        code = (qs.get("code") or [None])[0]
        if not code:
            for part in urllib.parse.unquote(body).split("&"):
                if part.startswith("code="):
                    code = part[5:]
        if code:
            try:
                self._handle_code(code)
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.end_headers()
                self.wfile.write(b"<html><body style='font-family:sans-serif;background:#101B17;color:#EDF3EC;padding:40px'><h1>Samsung TV Certificate Created & WGT Signed!</h1><p>MojaLoss-tv-0.1.2.wgt is now signed for DUID H3CMR3DM47BTG.</p></body></html>")
                threading.Thread(target=self.server.shutdown, daemon=True).start()
                return
            except Exception as e:
                self.send_response(500)
                self.end_headers()
                self.wfile.write(f"Error: {e}".encode())
                return
        self.send_response(400)
        self.end_headers()
        self.wfile.write(b"Missing code")

if __name__ == "__main__":
    if len(sys.argv) == 4:
        generate_and_sign(sys.argv[1], sys.argv[2], sys.argv[3])
        sys.exit(0)
    srv = http.server.HTTPServer(("127.0.0.1", 4794), CallbackHandler)
    print("Listening on http://localhost:4794/signin/callback")
    print("Sign-in URL:\n" + LOGIN_URL)
    srv.serve_forever()
