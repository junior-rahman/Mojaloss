#!/usr/bin/env python3
"""Run the Gradle wrapper through the standard environment-provided network proxy."""
import os
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlparse

project = Path(__file__).resolve().parent.parent
env = os.environ.copy()
runtime = project.parent / "toolchains"
if not env.get("JAVA_HOME") and (runtime / "jdk17-local").is_dir():
    env["JAVA_HOME"] = str(runtime / "jdk17-local")
if not env.get("ANDROID_HOME") and (runtime / "android-sdk").is_dir():
    env["ANDROID_HOME"] = str(runtime / "android-sdk")
wrapper = project / "gradlew"
executable = str(wrapper) if wrapper.is_file() else str(runtime / "gradle-8.13/bin/gradle")
if not Path(executable).is_file():
    sys.exit("Gradle wrapper or Gradle 8.13 is required.")
properties = []
proxy = env.get("HTTPS_PROXY") or env.get("HTTP_PROXY")
if proxy:
    parsed = urlparse(proxy)
    if parsed.hostname and parsed.port:
        for scheme in ("http", "https"):
            properties += [f"-D{scheme}.proxyHost={parsed.hostname}", f"-D{scheme}.proxyPort={parsed.port}"]
        properties += ["-Dhttp.nonProxyHosts=localhost|127.*", "-Dhttps.nonProxyHosts=localhost|127.*"]
raise SystemExit(subprocess.call([executable, *properties, *sys.argv[1:]], cwd=project, env=env))
