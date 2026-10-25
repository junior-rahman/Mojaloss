/* MojaLoss TV OTA Bootloader + Updater (ES5, Tizen 3+ safe).
   1. Loads verified OTA bundle from localStorage / IndexedDB if versionCode > BUILTIN_VERSION_CODE.
   2. Runs a 5s watchdog + try/catch: if the OTA bundle throws or fails to call window.__mltvBootOk(),
      rolls back automatically to the baked-in .wgt files so a bad update can never brick the TV.
   3. Provides window.MLTV_OTA for checking, downloading, SHA-256 verifying, and applying updates. */
(function () {
  var BUILTIN_VERSION = '0.1.27';
  var BUILTIN_VERSION_CODE = 27;
  var STORAGE_KEY = 'mltv_ota_bundle';
  var DB_NAME = 'mltv_ota_db';
  var STORE_NAME = 'bundles';

  var mock = (/[?&]mock=([^&]+)/.exec(location.search) || [])[1];
  if (mock) mock = decodeURIComponent(mock);
  var otaParam = (/[?&]ota=([^&]+)/.exec(location.search) || [])[1];
  if (otaParam) otaParam = decodeURIComponent(otaParam);

  var MANIFEST_URL = otaParam ? otaParam : (mock ? mock + '/ota/tv-manifest.json' : 'https://raw.githubusercontent.com/junior-rahman/Mojaloss/tv-ota/tv-manifest.json');
  var RELEASE_API_URL = mock ? mock + '/ota/releases/latest' : 'https://api.github.com/repos/junior-rahman/Mojaloss/releases/latest';

  /* Pure-JS SHA-256 (UTF-8 input) for Tizen file:// WebViews where crypto.subtle may be absent,
     plus Web Crypto fast path when available. Both return lowercase 64-char hex Promise. */
  function utf8Bytes(str) {
    if (window.TextEncoder) {
      var a = new TextEncoder().encode(str), out = [];
      for (var i = 0; i < a.length; i++) out.push(a[i]);
      return out;
    }
    var u = unescape(encodeURIComponent(str)), r = [];
    for (var j = 0; j < u.length; j++) r.push(u.charCodeAt(j));
    return r;
  }

  function sha256Pure(asciiStr) {
    var K = [
      0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
      0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
      0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
      0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
      0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
      0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
      0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
      0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
    ];
    var H = [0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19];
    var bytes = utf8Bytes(asciiStr);
    var bitLen = bytes.length * 8;
    bytes.push(0x80);
    while ((bytes.length % 64) !== 56) bytes.push(0);
    var hi = Math.floor(bitLen / 0x100000000), lo = bitLen >>> 0;
    bytes.push((hi >>> 24) & 255, (hi >>> 16) & 255, (hi >>> 8) & 255, hi & 255);
    bytes.push((lo >>> 24) & 255, (lo >>> 16) & 255, (lo >>> 8) & 255, lo & 255);

    function rotr(n, x) { return (x >>> n) | (x << (32 - n)); }
    var W = new Array(64);
    for (var i = 0; i < bytes.length; i += 64) {
      for (var t = 0; t < 16; t++) {
        var j = i + t * 4;
        W[t] = ((bytes[j] << 24) | (bytes[j + 1] << 16) | (bytes[j + 2] << 8) | bytes[j + 3]) >>> 0;
      }
      for (var t2 = 16; t2 < 64; t2++) {
        var s0 = rotr(7, W[t2 - 15]) ^ rotr(18, W[t2 - 15]) ^ (W[t2 - 15] >>> 3);
        var s1 = rotr(17, W[t2 - 2]) ^ rotr(19, W[t2 - 2]) ^ (W[t2 - 2] >>> 10);
        W[t2] = (W[t2 - 16] + s0 + W[t2 - 7] + s1) >>> 0;
      }
      var a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
      for (var t3 = 0; t3 < 64; t3++) {
        var S1 = rotr(6, e) ^ rotr(11, e) ^ rotr(25, e);
        var ch = (e & f) ^ ((~e) & g);
        var temp1 = (h + S1 + ch + K[t3] + W[t3]) >>> 0;
        var S0 = rotr(2, a) ^ rotr(13, a) ^ rotr(22, a);
        var maj = (a & b) ^ (a & c) ^ (b & c);
        var temp2 = (S0 + maj) >>> 0;
        h = g; g = f; f = e; e = (d + temp1) >>> 0; d = c; c = b; b = a; a = (temp1 + temp2) >>> 0;
      }
      H[0] = (H[0] + a) >>> 0; H[1] = (H[1] + b) >>> 0; H[2] = (H[2] + c) >>> 0; H[3] = (H[3] + d) >>> 0;
      H[4] = (H[4] + e) >>> 0; H[5] = (H[5] + f) >>> 0; H[6] = (H[6] + g) >>> 0; H[7] = (H[7] + h) >>> 0;
    }
    var hex = '';
    for (var k = 0; k < 8; k++) hex += ('00000000' + H[k].toString(16)).slice(-8);
    return hex;
  }

  function sha256Hex(str) {
    try {
      if (window.crypto && crypto.subtle && crypto.subtle.digest && window.TextEncoder) {
        return crypto.subtle.digest('SHA-256', new TextEncoder().encode(str)).then(function (buf) {
          var arr = new Uint8Array(buf), s = '';
          for (var i = 0; i < arr.length; i++) s += ('0' + arr[i].toString(16)).slice(-2);
          return s;
        }, function () { return sha256Pure(str); });
      }
    } catch (e) {}
    return Promise.resolve(sha256Pure(str));
  }

  /* Canonical payload string covered by bundle.sha256 */
  function bundleCanonicalPayload(b) {
    return String(b.version || '') + '\n' + String(b.versionCode || 0) + '\n' + String(b.css || '') + '\n' + String(b.js || '');
  }

  function verifyBundle(b) {
    if (!b || typeof b !== 'object' || !b.version || typeof b.versionCode !== 'number' || !b.sha256 || typeof b.js !== 'string' || typeof b.css !== 'string') {
      return Promise.reject(new Error('Invalid OTA bundle format'));
    }
    var expected = String(b.sha256).toLowerCase().trim();
    return sha256Hex(bundleCanonicalPayload(b)).then(function (actual) {
      if (actual !== expected) {
        var err = new Error('SHA-256 mismatch (expected ' + expected.slice(0, 12) + '…, got ' + actual.slice(0, 12) + '…)');
        err.code = 'SHA256_MISMATCH';
        throw err;
      }
      return b;
    });
  }

  /* Storage: localStorage + IndexedDB mirror so large bundles survive and boot can read synchronously or async */
  function readLocalBundle() {
    try {
      var raw = localStorage.getItem(STORAGE_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  }

  function evictCachesForOta() {
    try {
      var keep = { "mltv_ota_bundle": 1, "mltv_auto_update": 1, "mltv.platOrder": 1, "mltv.requests.v1": 1, "mltv.fitMode": 1, "mltv.maxBitrate": 1 };
      var toRemove = [];
      for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i);
        if (k && !keep[k] && k.indexOf("mltv.token") < 0 && k.indexOf("mltv.userId") < 0 && k.indexOf("mltv.deviceId") < 0 && k.indexOf("mltest.") !== 0 && k.indexOf("ml.") !== 0) {
          if (k.indexOf("platHubCache") >= 0 || k.indexOf("disc") >= 0 || k.indexOf("posterMap") >= 0 || k.indexOf("rollback") >= 0) {
            toRemove.push(k);
          }
        }
      }
      for (var j = 0; j < toRemove.length; j++) localStorage.removeItem(toRemove[j]);
    } catch (e) {}
  }

  function writeBundleStorage(b) {
    var rawJson = JSON.stringify(b);
    try {
      evictCachesForOta();
      localStorage.removeItem(STORAGE_KEY);
      localStorage.setItem(STORAGE_KEY, rawJson);
    } catch (e) {
      try {
        evictCachesForOta();
        localStorage.removeItem("mltv.platHubCache.v4");
        localStorage.removeItem(STORAGE_KEY);
        localStorage.setItem(STORAGE_KEY, rawJson);
      } catch (e2) {}
    }
    return new Promise(function (resolve) {
      try {
        if (!window.indexedDB) return resolve(b);
        var req = indexedDB.open(DB_NAME, 1);
        req.onupgradeneeded = function () { req.result.createObjectStore(STORE_NAME); };
        req.onsuccess = function () {
          try {
            var db = req.result, tx = db.transaction(STORE_NAME, 'readwrite');
            tx.objectStore(STORE_NAME).put(b, STORAGE_KEY);
            tx.oncomplete = function () { db.close(); resolve(b); };
            tx.onerror = function () { db.close(); resolve(b); };
          } catch (e2) { resolve(b); }
        };
        req.onerror = function () { resolve(b); };
      } catch (e3) { resolve(b); }
    });
  }

  function clearBundleStorage() {
    try { localStorage.removeItem(STORAGE_KEY); } catch (e) {}
    try {
      if (window.indexedDB) {
        var req = indexedDB.open(DB_NAME, 1);
        req.onsuccess = function () {
          try {
            var db = req.result, tx = db.transaction(STORE_NAME, 'readwrite');
            tx.objectStore(STORE_NAME).delete(STORAGE_KEY);
            tx.oncomplete = function () { db.close(); };
          } catch (e2) {}
        };
      }
    } catch (e3) {}
  }

  function fetchJson(url) {
    var u = (url.indexOf('raw.githubusercontent.com') >= 0) ? (url + (url.indexOf('?') >= 0 ? '&' : '?') + '_t=' + Date.now()) : url;
    return Promise.race([
      fetch(u, { cache: 'no-store' }),
      new Promise(function (_, rej) { setTimeout(function () { rej(new Error('timeout')); }, 12000); })
    ]).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    });
  }

  function fetchRemoteManifest() {
    return fetchJson(MANIFEST_URL).catch(function () {
      return fetchJson(RELEASE_API_URL).then(function (rel) {
        var assets = (rel && rel.assets) || [];
        for (var i = 0; i < assets.length; i++) {
          if (assets[i].name === 'tv-ota-bundle.json' || assets[i].name === 'tv-manifest.json') {
            return fetchJson(assets[i].browser_download_url);
          }
        }
        throw new Error('No OTA bundle in latest release');
      });
    });
  }

  var state = {
    builtinVersion: BUILTIN_VERSION,
    builtinVersionCode: BUILTIN_VERSION_CODE,
    activeVersion: BUILTIN_VERSION,
    activeVersionCode: BUILTIN_VERSION_CODE,
    activeSource: 'builtin',
    rolledBackFrom: null,
    lastRollbackError: null,
    availableUpdate: null,
    checking: false,
    updating: false,
    lastCheckError: null,
    listeners: []
  };

  function notify() {
    state.listeners.forEach(function (fn) { try { fn(state); } catch (e) {} });
  }

  var bootWatchdog = null;
  var bootedOk = false;

  window.__mltvBootOk = function () {
    bootedOk = true;
    if (bootWatchdog) { clearTimeout(bootWatchdog); bootWatchdog = null; }
  };

  function loadBuiltinScripts() {
    state.activeVersion = BUILTIN_VERSION;
    state.activeVersionCode = BUILTIN_VERSION_CODE;
    state.activeSource = 'builtin';
    var baseCss = document.getElementById('mltv-base-css') || document.querySelector('link[href*="app.css"]');
    if (baseCss) baseCss.disabled = false;
    var otaStyle = document.getElementById('mltv-ota-css');
    if (otaStyle && otaStyle.parentNode) otaStyle.parentNode.removeChild(otaStyle);
    var files = ['js/logos.js', 'js/api.js', 'js/focus.js', 'js/app.js'];
    function next(i) {
      if (i >= files.length) return;
      var s = document.createElement('script');
      s.src = files[i];
      s.onload = function () { next(i + 1); };
      s.onerror = function () { next(i + 1); };
      document.body.appendChild(s);
    }
    next(0);
  }

  function rollbackToBuiltin(reason, badVersion) {
    if (bootWatchdog) { clearTimeout(bootWatchdog); bootWatchdog = null; }
    clearBundleStorage();
    state.rolledBackFrom = badVersion || 'ota';
    state.lastRollbackError = String(reason || 'boot failure');
    try { localStorage.setItem('mltv_last_rollback', JSON.stringify({ from: state.rolledBackFrom, error: state.lastRollbackError, at: Date.now() })); } catch (e) {}
    loadBuiltinScripts();
  }

  function bootFromOta(bundle) {
    if (sha256Pure(bundleCanonicalPayload(bundle)) !== String(bundle.sha256 || '').toLowerCase().trim()) {
      return rollbackToBuiltin('Stored OTA bundle failed SHA-256 verification', bundle.version);
    }
    state.activeVersion = bundle.version;
    state.activeVersionCode = bundle.versionCode;
    state.activeSource = 'ota';
    bootedOk = false;

    var baseCss = document.getElementById('mltv-base-css') || document.querySelector('link[href*="app.css"]');
    if (baseCss) baseCss.disabled = true;

    var st = document.getElementById('mltv-ota-css');
    if (!st) {
      st = document.createElement('style');
      st.id = 'mltv-ota-css';
      document.head.appendChild(st);
    }
    st.textContent = bundle.css;

    if (bundle.logos && typeof bundle.logos === 'object') {
      window.PlatformLogos = bundle.logos;
    }

    bootWatchdog = setTimeout(function () {
      if (!bootedOk) rollbackToBuiltin('OTA bundle did not call __mltvBootOk within 5s', bundle.version);
    }, 5000);

    try {
      var fn = new Function(bundle.js);
      fn();
      if (!bootedOk) {
        clearTimeout(bootWatchdog);
        bootWatchdog = null;
        return rollbackToBuiltin('OTA bundle did not complete boot handshake', bundle.version);
      }
    } catch (err) {
      clearTimeout(bootWatchdog);
      bootWatchdog = null;
      return rollbackToBuiltin(err && err.message ? err.message : String(err), bundle.version);
    }
  }

  window.MLTV_OTA = {
    state: function () { return state; },
    version: function () { return state.activeVersion; },
    versionCode: function () { return state.activeVersionCode; },
    source: function () { return state.activeSource; },
    sha256Hex: sha256Hex,
    sha256Pure: sha256Pure,
    bundleCanonicalPayload: bundleCanonicalPayload,
    verifyBundle: verifyBundle,
    onChange: function (fn) { state.listeners.push(fn); },
    checkForUpdates: function (silent) {
      state.checking = true;
      state.lastCheckError = null;
      notify();
      return fetchRemoteManifest().then(function (remote) {
        state.checking = false;
        if (remote && typeof remote.versionCode === 'number' && remote.versionCode > state.activeVersionCode) {
          state.availableUpdate = remote;
        } else {
          state.availableUpdate = null;
        }
        notify();
        return { hasUpdate: !!state.availableUpdate, remote: remote, currentVersion: state.activeVersion, currentVersionCode: state.activeVersionCode };
      }, function (err) {
        state.checking = false;
        state.lastCheckError = err && err.message ? err.message : 'Could not check for updates';
        notify();
        if (!silent) throw err;
        return { hasUpdate: false, error: state.lastCheckError };
      });
    },
    installUpdate: function (bundleOrNull) {
      state.updating = true;
      state.lastCheckError = null;
      notify();
      var p = bundleOrNull ? Promise.resolve(bundleOrNull) : (state.availableUpdate ? Promise.resolve(state.availableUpdate) : fetchRemoteManifest());
      return p.then(function (bundle) {
        if (!bundle.js || !bundle.css) {
          if (bundle.bundleUrl) return fetchJson(bundle.bundleUrl);
        }
        return bundle;
      }).then(function (bundle) {
        return verifyBundle(bundle);
      }).then(function (verified) {
        return writeBundleStorage(verified);
      }).then(function (saved) {
        state.updating = false;
        state.availableUpdate = null;
        notify();
        setTimeout(function () { location.reload(); }, 150);
        return saved;
      }, function (err) {
        state.updating = false;
        state.lastCheckError = err && err.message ? err.message : 'Update failed';
        notify();
        throw err;
      });
    },
    resetToBuiltin: function () {
      clearBundleStorage();
      location.reload();
    }
  };

  /* Boot sequence: check if a newer verified OTA bundle is cached */
  var cached = readLocalBundle();
  if (cached && typeof cached.versionCode === 'number' && cached.versionCode > BUILTIN_VERSION_CODE) {
    bootFromOta(cached);
  } else {
    loadBuiltinScripts();
  }
})();
