/* MojaLoss TV — screens: signin, home, platform, detail, search, player. ES5 only (Tizen 3+). */
(function () {
  var root = document.getElementById('root'), bd = document.getElementById('backdrop');
  var stack = [], screen = null, player = null, lastBack = 0;
  function each(list, fn) { Array.prototype.forEach.call(list, fn); }
  function param(k) { var m = new RegExp('[?&]' + k + '=([^&]+)').exec(location.search); return m ? decodeURIComponent(m[1]) : null; }
  function fitViewport() {
    if (!root) return;
    var w = window.innerWidth || 1920, h = window.innerHeight || 1080;
    if (Math.abs(w - 1920) > 2 || Math.abs(h - 1080) > 2) {
      root.style.transform = 'scale(' + (w / 1920).toFixed(5) + ',' + (h / 1080).toFixed(5) + ')';
    } else {
      root.style.transform = 'none';
    }
  }
  fitViewport();
  window.addEventListener('resize', fitViewport);

  var KEYS = ['MediaPlay', 'MediaPause', 'MediaPlayPause', 'MediaFastForward', 'MediaRewind', 'MediaStop', 'ColorF0Red', 'ColorF1Green'];
  var registered = [];
  try { KEYS.forEach(function (k) { tizen.tvinputdevice.registerKey(k); registered.push(k); }); } catch (e) {}
  var K = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, OK: 13, BACK: 10009, PLAY: 415, PAUSE: 19, PLAYPAUSE: 10252, FF: 417, RW: 412, STOP: 413, RED: 403, GREEN: 404 };

  function h(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return '&#' + c.charCodeAt(0) + ';'; }); }
  function toast(t) { var e = document.getElementById('toast'); e.textContent = t; e.classList.add('show'); clearTimeout(toast.t); toast.t = setTimeout(function () { e.classList.remove('show'); }, 3500); }
  var bdCurUrl = '', bdTimer = null;
  function ensureBdLayers() {
    if (!bd.querySelector('.bd-layer')) {
      bd.innerHTML = '<div class="bd-layer" style="display:none"></div><div class="bd-layer" style="display:none"></div>';
    }
  }
  function applyBdUrl(url) {
    var bg = url ? 'linear-gradient(90deg, rgba(13,22,18,0.96) 0%, rgba(13,22,18,0.84) 40%, rgba(13,22,18,0.25) 76%, rgba(13,22,18,0.60) 100%), linear-gradient(180deg, rgba(13,22,18,0.32) 0%, rgba(13,22,18,0.12) 44%, rgba(13,22,18,0.96) 88%, #0D1612 100%), url("' + url + '")' : '';
    bd.style.backgroundImage = bg;
  }
  function art(url) {
    url = url || '';
    ensureBdLayers();
    if (url === bdCurUrl) return;
    var hadPrev = !!bdCurUrl;
    bdCurUrl = url;
    if (bdTimer) { clearTimeout(bdTimer); bdTimer = null; }
    if (!hadPrev || !url || param('mock')) {
      applyBdUrl(url);
    } else {
      bdTimer = setTimeout(function () {
        var img = new Image();
        img.onload = img.onerror = function () {
          if (bdCurUrl === url) applyBdUrl(url);
        };
        img.src = url;
      }, 160);
    }
  }
  function fmt(ticks) { var s = Math.floor((ticks || 0) / 1e7), m = Math.floor(s / 60); return m >= 60 ? Math.floor(m / 60) + 'h ' + (m % 60) + 'm' : m + 'm'; }
  function clock(sec) { sec = Math.max(0, Math.floor(sec || 0)); var m = Math.floor(sec / 60), s = sec % 60, hh = Math.floor(m / 60); return (hh ? hh + ':' + ('0' + (m % 60)).slice(-2) : m) + ':' + ('0' + s).slice(-2); }
  function exitApp() { try { tizen.application.getCurrentApplication().exit(); } catch (e) { window.MLTV_EXITED = true; } }

  /* Errors: 401 = server revoked this TV's token (only then do we ask for a new approval); anything else = offline/unreachable state with Retry. */
  function onErr(e) {
    if (e && e.status === 401) { Api.forgetToken(); stack = []; go('signin', { reason: 'expired' }, true); return; }
    errorState(e && e.offline ? 'You are offline' : 'MojaLoss is not responding', e && e.offline ? 'Check the TV\u2019s network connection, then try again.' : 'The server answered with an error. Try again in a moment.');
  }
  function errorState(title, body) {
    var me = screen;
    root.innerHTML = topbar() + '<div class="state glass"><h1>' + h(title) + '</h1><p class="muted">' + h(body) + '</p><div class="actions"><div class="btn primary focusable" id="retry">Try again</div></div></div>';
    document.getElementById('retry').onclick = function () { if (screen === me) render(); };
    Focus.first('#retry');
  }
  window.addEventListener('offline', function () { toast('Network connection lost'); });
  window.addEventListener('online', function () { toast('Back online'); if (player) player.recover(); });

  function go(name, args, replace) { if (screen && screen.cleanup) screen.cleanup(); if (screen && !replace) stack.push(screen); screen = { name: name, args: args || {} }; render(); }
  function back() {
    if (document.getElementById('update-modal')) { closeUpdateModal(); return; }
    if (player) { if (player.closeMenu()) return; return stopPlayer(); }
    if (screen && screen.cleanup) screen.cleanup();
    if (stack.length) { screen = stack.pop(); render(); return; }
    // root screen: double-press Back to leave the app
    if (Date.now() - lastBack < 2500) return exitApp();
    lastBack = Date.now(); toast('Press Back again to exit MojaLoss');
  }
  function render() {
    Focus.onFocus = null; art(''); screen.cleanup = null;
    root.classList.add('screen-enter');
    Screens[screen.name](screen.args);
  }

  function logoTile(p, big) {
    var L = PlatformLogos[p.slug], s = big ? 108 : 40;
    if (L) return '<span class="logo" style="width:' + s + 'px;height:' + s + 'px;background:' + L.brand + '"><svg viewBox="0 0 24 24" width="' + (s * .56) + '" height="' + (s * .56) + '"><path fill="#fff" d="' + L.path + '"/></svg></span>';
    var mono = String(p.name).replace(/[^A-Za-z0-9 ]/g, '').split(' ').filter(Boolean).slice(0, 2).map(function (w) { return w[0]; }).join('').toUpperCase();
    return '<span class="logo mono" style="width:' + s + 'px;height:' + s + 'px">' + h(mono) + '</span>';
  }
  function card(it, wide, extraAttr) {
    var pct = it.UserData && it.UserData.PlayedPercentage;
    var img = wide ? (Api.img(it, 'Backdrop', 420) || Api.img(it, 'Primary', 420)) : Api.img(it, 'Primary', 240);
    var title = it.Type === 'Episode' ? (it.SeriesName + ' \u00b7 S' + (it.ParentIndexNumber || 0) + 'E' + (it.IndexNumber || 0)) : it.Name;
    return '<div class="card focusable' + (wide ? ' wide' : '') + '" data-id="' + h(it.Id) + '"' + (extraAttr ? ' ' + extraAttr : '') + '><div class="art" style="background-image:url(\'' + h(img) + '\')"></div>' +
      (pct ? '<div class="prog"><b style="width:' + Math.round(pct) + '%"></b></div>' : '') + '<div class="t">' + h(title) + '</div></div>';
  }
  function rankCard(it, rankNum) {
    var pct = it.UserData && it.UserData.PlayedPercentage;
    var img = Api.img(it, 'Primary', 260) || Api.img(it, 'Backdrop', 360);
    return '<div class="card rank-card focusable" data-id="' + h(it.Id) + '" data-rank="' + rankNum + '">' +
      '<span class="rank-num">' + rankNum + '</span>' +
      '<div class="rank-poster"><div class="art" style="background-image:url(\'' + h(img) + '\')">' +
      '<span class="rank-badge">#' + rankNum + ' Today</span></div>' +
      (pct ? '<div class="prog"><b style="width:' + Math.round(pct) + '%"></b></div>' : '') +
      '<div class="t">' + h(it.Name) + '</div></div></div>';
  }
  function nextUpCard(it) {
    var img = Api.img(it, 'Backdrop', 420) || Api.img(it, 'Primary', 420);
    var epCode = 'S' + (it.ParentIndexNumber || 1) + 'E' + (it.IndexNumber || 1);
    var title = (it.SeriesName ? it.SeriesName + ' \u00b7 ' : '') + epCode + (it.Name ? ' \u00b7 ' + it.Name : '');
    var rt = it.RunTimeTicks ? fmt(it.RunTimeTicks) : '';
    return '<div class="card wide nextup-card focusable" data-id="' + h(it.Id) + '" data-nextup="1">' +
      '<div class="art" style="background-image:url(\'' + h(img) + '\')">' +
      '<span class="ep-badge">Next Up \u00b7 ' + h(epCode) + '</span>' +
      (rt ? '<span class="ep-runtime">' + h(rt) + '</span>' : '') +
      '</div><div class="t">' + h(title) + '</div></div>';
  }
  function nextUpRow(title, items, id) {
    return '<section class="row"' + (id ? ' id="' + id + '"' : '') + '><h2>' + h(title) + '</h2><div class="track">' +
      items.map(function (i) { return nextUpCard(i); }).join('') + '</div></section>';
  }
  function row(title, items, wide, id, extraAttr) {
    return '<section class="row"' + (id ? ' id="' + id + '"' : '') + '>' + (title ? '<h2>' + h(title) + '</h2>' : '') + '<div class="track">' + (items.length ? items.map(function (i) { return card(i, wide, extraAttr); }).join('') : '<div class="empty">Nothing here yet</div>') + '</div></section>';
  }
  function homeSkeletonHtml() {
    var wideCards = [1, 2, 3, 4].map(function () { return '<div class="skel-card wide skel-pulse"></div>'; }).join('');
    var chips = [1, 2, 3, 4, 5, 6].map(function () { return '<div class="skel-chip skel-pulse"></div>'; }).join('');
    var posters = [1, 2, 3, 4, 5, 6, 7].map(function () { return '<div class="skel-card skel-pulse"></div>'; }).join('');
    return '<div class="hero" id="home-skel-hero"><div class="hero-body">' +
      '<div class="skel-bar skel-pulse" style="width:220px"></div>' +
      '<div class="skel-title skel-pulse"></div>' +
      '<div class="skel-bar skel-pulse" style="width:320px;margin-bottom:14px"></div>' +
      '<div class="skel-bar skel-pulse" style="width:680px;margin-bottom:8px"></div>' +
      '<div class="skel-bar skel-pulse" style="width:520px;margin-bottom:16px"></div>' +
      '<div class="actions"><div class="skel-pill skel-pulse"></div><div class="skel-pill skel-pulse"></div></div>' +
      '</div></div>' +
      '<div class="rows"><div class="rows-inner" id="rows">' +
      '<section class="row"><div class="skel-bar skel-pulse" style="width:210px;margin-bottom:12px"></div><div class="track">' + wideCards + '</div></section>' +
      '<section class="row"><div class="track">' + chips + '</div></section>' +
      '<section class="row"><div class="skel-bar skel-pulse" style="width:240px;margin-bottom:12px"></div><div class="track">' + posters + '</div></section>' +
      '</div></div>';
  }
  function wireCards(map, scope, fn) {
    each((scope || root).querySelectorAll('.card'), function (c) { c.onclick = function () { var it = map[c.getAttribute('data-id')]; if (it) (fn || function (x) { go('detail', { item: x }); })(it); }; });
  }
  var currentHeroItem = null;
  function heroEyebrow(it, el) {
    if (el && el.getAttribute) {
      var rk = el.getAttribute('data-rank');
      if (rk) return 'Top 10 Trending Today \u00b7 #' + rk + (it.Type === 'Series' ? ' \u00b7 Series' : ' \u00b7 Movie');
      if (el.getAttribute('data-nextup')) return 'Next Up \u00b7 ' + (it.SeriesName || 'Series');
      var reg = el.getAttribute('data-regional');
      if (reg) return reg;
    }
    if (it._trendingRank) return 'Top 10 Trending Today \u00b7 #' + (it._displayRank || it._trendingRank);
    var pos = it.UserData && it.UserData.PlaybackPositionTicks;
    if (it.Type === 'Episode') return (pos > 0 ? 'Continue Watching \u00b7 ' : 'Next Up \u00b7 ') + (it.SeriesName || 'Series');
    if (pos > 0) return 'Continue Watching';
    if (it.Type === 'Series') return 'Featured Series';
    return 'Featured Movie';
  }
  function heroTitle(it) {
    if (it.Type === 'Episode') return (it.SeriesName ? it.SeriesName + ' \u00b7 ' : '') + 'S' + (it.ParentIndexNumber || 1) + 'E' + (it.IndexNumber || 1) + (it.Name ? ' \u00b7 ' + it.Name : '');
    return it.Name || 'Featured';
  }
  function heroMetaHtml(it) {
    var pct = (it.UserData && it.UserData.PlayedPercentage) || 0;
    var parts = [
      it.ProductionYear || (it.Type === 'Episode' && it.SeriesName ? it.SeriesName : null),
      it.OfficialRating,
      it.RunTimeTicks && fmt(it.RunTimeTicks),
      (it.Genres || []).slice(0, 3).join(' \u00b7 '),
      pct > 0 ? (Math.round(pct) + '% watched') : null
    ].filter(Boolean);
    return parts.map(function (m) { return '<span>' + h(m) + '</span>'; }).join('');
  }
  function heroSectionHtml() {
    return '<div class="hero row" id="hero">' +
      '<div class="hero-body">' +
      '<div class="hero-eyebrow" id="hero-eyebrow">Featured</div>' +
      '<div class="hero-title" id="hero-title"></div>' +
      '<div class="meta" id="hero-meta"></div>' +
      '<p class="hero-desc" id="hero-desc"></p>' +
      '<div class="actions"><div class="btn primary focusable" id="hero-play">Play</div><div class="btn focusable" id="hero-info">More Info</div></div>' +
      '</div>' +
      '</div>';
  }
  function updateHero(it, focusEl) {
    if (!it) return;
    var changed = !currentHeroItem || currentHeroItem.Id !== it.Id;
    currentHeroItem = it;
    art(Api.img(it, 'Backdrop', 1280) || Api.img(it, 'Primary', 1280));
    var heroEl = document.getElementById('hero');
    if (!heroEl) return;

    var pos = (it.UserData && it.UserData.PlaybackPositionTicks) || 0;
    var resume = pos > 0 && it.Type !== 'Series';
    var eb = document.getElementById('hero-eyebrow');
    var ti = document.getElementById('hero-title');
    var me = document.getElementById('hero-meta');
    var de = document.getElementById('hero-desc');
    var pl = document.getElementById('hero-play');
    var inf = document.getElementById('hero-info');
    if (eb) eb.textContent = heroEyebrow(it, focusEl);
    if (ti) ti.textContent = heroTitle(it);
    if (me) me.innerHTML = heroMetaHtml(it);
    if (de) de.textContent = (it.Overview && String(it.Overview).trim()) ? it.Overview : 'Stream now on MojaLoss.';
    if (pl) {
      pl.textContent = resume ? ('Resume from ' + clock(pos / 1e7)) : 'Play';
      pl.onclick = function () {
        var target = currentHeroItem || it;
        var tPos = (target.UserData && target.UserData.PlaybackPositionTicks) || 0;
        if (target.Type !== 'Series') return play(target, tPos > 0 ? tPos : 0);
        Api.nextUp(target.Id).then(function (n) {
          var ep = (n.Items || [])[0];
          if (ep) return play(ep, (ep.UserData && ep.UserData.PlaybackPositionTicks) || 0);
          return Api.episodes(target.Id).then(function (e) {
            var firstEp = (e.Items || [])[0];
            if (firstEp) play(firstEp, (firstEp.UserData && firstEp.UserData.PlaybackPositionTicks) || 0);
            else go('detail', { item: target });
          }, function () { go('detail', { item: target }); });
        }, function () { go('detail', { item: target }); });
      };
    }
    if (inf) {
      inf.textContent = it.Type === 'Series' ? 'Episodes & Info' : 'More Info';
      inf.onclick = function () { go('detail', { item: currentHeroItem || it }); };
    }
  }
  function artFocus(map) { Focus.onFocus = function (e) { var it = map[e.getAttribute('data-id')]; if (it) { if (document.getElementById('hero')) updateHero(it, e); else art(Api.img(it, 'Backdrop', 1280) || Api.img(it, 'Primary', 1280)); } }; }
  function curVer() { return (window.MLTV_OTA && MLTV_OTA.version()) || '0.1.12'; }
  function topbar(extra) { return '<div class="top"><div class="brand"><i></i>MojaLoss <span class="ver-badge" id="ver-badge">v' + h(curVer()) + '</span></div><div class="actions">' + (extra || '') + '</div></div>'; }

  /* In-app OTA Update modal & banner */
  var preModalFocus = null;
  function closeUpdateModal() {
    var m = document.getElementById('update-modal');
    if (m && m.parentNode) m.parentNode.removeChild(m);
    if (preModalFocus && document.body.contains(preModalFocus)) Focus.set(preModalFocus);
    else Focus.first('.focusable');
  }
  function openUpdateModal() {
    preModalFocus = Focus.current();
    var m = document.getElementById('update-modal');
    if (!m) {
      m = document.createElement('div');
      m.id = 'update-modal';
      m.className = 'modal-backdrop';
      document.body.appendChild(m);
    }
    var st = window.MLTV_OTA ? MLTV_OTA.state() : { activeVersion: '0.1.12', activeSource: 'builtin', builtinVersion: '0.1.12' };
    var avail = st.availableUpdate;
    var statusHtml = st.updating ? 'Downloading & verifying SHA-256 update package…' :
      st.checking ? 'Checking GitHub for updates…' :
      st.lastCheckError ? ('Update check error: ' + h(st.lastCheckError)) :
      avail ? ('New update available: v' + h(avail.version) + (avail.notes ? ' — ' + h(avail.notes) : '')) :
      'Your TV app is up to date.';
    m.innerHTML = '<div class="modal-card glass">' +
      '<div class="brand"><i></i>MojaLoss TV Update</div>' +
      '<h1 style="margin-top:22px;font-size:44px">App Version v' + h(st.activeVersion) + '</h1>' +
      '<div class="meta" style="margin-top:14px"><span>Source: ' + h(st.activeSource === 'ota' ? 'OTA Bundle' : 'Built-in WGT') + '</span><span>Baseline: v' + h(st.builtinVersion) + '</span></div>' +
      '<p class="ota-status" id="ota-modal-status">' + statusHtml + '</p>' +
      '<div class="actions" style="margin-top:32px">' +
      (avail ? '<div class="btn primary focusable" id="ota-install-now">Install v' + h(avail.version) + ' now</div>' : '') +
      '<div class="btn ' + (avail ? '' : 'primary ') + 'focusable" id="ota-check-now">Check for updates</div>' +
      (st.activeSource === 'ota' ? '<div class="btn focusable" id="ota-reset-builtin">Restore built-in v' + h(st.builtinVersion) + '</div>' : '') +
      '<div class="btn focusable" id="ota-modal-close">Close</div>' +
      '</div></div>';
    var instBtn = document.getElementById('ota-install-now');
    if (instBtn) instBtn.onclick = function () {
      var sEl = document.getElementById('ota-modal-status');
      if (sEl) sEl.textContent = 'Downloading & verifying SHA-256…';
      MLTV_OTA.installUpdate().then(function () {
        toast('Updated! Reloading…');
      }, function (err) {
        var sEl2 = document.getElementById('ota-modal-status');
        if (sEl2) sEl2.textContent = 'Update rejected: ' + (err && err.message ? err.message : 'verification failed');
        toast('Update failed verification');
      });
    };
    document.getElementById('ota-check-now').onclick = function () {
      var sEl = document.getElementById('ota-modal-status');
      if (sEl) sEl.textContent = 'Checking GitHub for updates…';
      MLTV_OTA.checkForUpdates(true).then(function (res) {
        if (document.getElementById('update-modal')) openUpdateModal();
        if (res && res.hasUpdate) toast('Update v' + res.remote.version + ' is ready');
        else if (!res.error) toast('Already on the latest version (v' + curVer() + ')');
      });
    };
    var rstBtn = document.getElementById('ota-reset-builtin');
    if (rstBtn) rstBtn.onclick = function () { MLTV_OTA.resetToBuiltin(); };
    document.getElementById('ota-modal-close').onclick = closeUpdateModal;
    Focus.first(avail ? '#ota-install-now' : '#ota-check-now');
  }
  function refreshUpdateUi() {
    if (!window.MLTV_OTA) return;
    var st = MLTV_OTA.state(), b = document.getElementById('ota-banner'), u = document.getElementById('update-btn');
    if (u) {
      u.innerHTML = st.availableUpdate ? ('Update · v' + h(st.availableUpdate.version)) : ('Update · v' + h(st.activeVersion));
      if (st.availableUpdate) u.classList.add('has-update'); else u.classList.remove('has-update');
    }
    if (b) {
      if (st.availableUpdate) {
        b.style.display = 'flex';
        b.innerHTML = '<span><b>Update available (v' + h(st.availableUpdate.version) + ')</b>' + (st.availableUpdate.notes ? ' — ' + h(st.availableUpdate.notes) : '') + '</span><span class="pill on">Press OK to update now</span>';
        b.classList.add('focusable');
        b.onclick = function () {
          toast('Updating to v' + st.availableUpdate.version + '…');
          MLTV_OTA.installUpdate().catch(function (err) { toast('Update failed: ' + (err && err.message ? err.message : 'error')); });
        };
      } else {
        b.style.display = 'none';
        b.classList.remove('focusable');
      }
    }
  }
  if (window.MLTV_OTA) {
    MLTV_OTA.onChange(refreshUpdateUi);
    if (!param('mock') && !param('ota')) {
      var origCheck = MLTV_OTA.checkForUpdates;
      MLTV_OTA.checkForUpdates = function (silent) {
        var st = MLTV_OTA.state();
        st.checking = true; st.lastCheckError = null; refreshUpdateUi();
        var commitApi = 'https://api.github.com/repos/junior-rahman/Mojaloss/commits/tv-ota?_t=' + Date.now();
        return fetch(commitApi, { cache: 'no-store' }).then(function (r) {
          if (!r.ok) throw new Error('HTTP ' + r.status);
          return r.json();
        }).then(function (c) {
          var ref = (c && c.sha) ? c.sha : 'tv-ota';
          return fetch('https://raw.githubusercontent.com/junior-rahman/Mojaloss/' + ref + '/tv-manifest.json', { cache: 'no-store' });
        }).then(function (r) {
          if (!r.ok) throw new Error('HTTP ' + r.status);
          return r.json();
        }).then(function (remote) {
          st.checking = false;
          st.availableUpdate = (remote && typeof remote.versionCode === 'number' && remote.versionCode > st.activeVersionCode) ? remote : null;
          refreshUpdateUi();
          return { hasUpdate: !!st.availableUpdate, remote: remote, currentVersion: st.activeVersion, currentVersionCode: st.activeVersionCode };
        }).catch(function () {
          return origCheck.call(MLTV_OTA, silent);
        });
      };
    }
  }
  function norm(s) { return String(s || '').toLowerCase().replace(/&amp;/g, '&').replace(/\s*\(\d{4}\)\s*$/, '').replace(/[^a-z0-9]+/g, ''); }
  function baseNorm(s) { return String(s || '').toLowerCase().replace(/&amp;/g, '&').split(/[:\-–]/)[0].replace(/[^a-z0-9]+/g, ''); }

  function runBatch(items, limit, fn) {
    var results = new Array(items.length), idx = 0;
    function worker() {
      if (idx >= items.length) return Promise.resolve();
      var i = idx++;
      return Promise.resolve(fn(items[i], i)).then(function (r) { results[i] = r; return worker(); });
    }
    var pool = [];
    for (var w = 0; w < Math.min(limit, items.length); w++) pool.push(worker());
    return Promise.all(pool).then(function () { return results; });
  }

  var discoveryCache = null;
  var DISC_KEY = 'mltv.disc.v3';
  function loadDiscovery(seedItems, onTrendingReady) {
    if (!discoveryCache && !param('mock')) {
      try {
        var raw = localStorage.getItem(DISC_KEY);
        if (raw) {
          var parsed = JSON.parse(raw);
          if (parsed && parsed.ts && (Date.now() - parsed.ts < 30 * 60 * 1000) && parsed.data && parsed.data.trending && parsed.data.trending.length) {
            discoveryCache = parsed.data;
          }
        }
      } catch (e) {}
    }
    if (discoveryCache) {
      if (onTrendingReady && discoveryCache.trending) onTrendingReady(discoveryCache.trending);
      return Promise.resolve(discoveryCache);
    }
    var libPromise = Api.libraryIndex().catch(function () { return { Items: [] }; }).then(function (libRes) {
      var combined = ((libRes && libRes.Items) || []).concat(seedItems || []);
      var byName = {}, byBase = {}, seenLib = {};
      combined.forEach(function (it) {
        if (!it || !it.Id || seenLib[it.Id]) return;
        seenLib[it.Id] = 1;
        var k = norm(it.Name);
        if (k) (byName[k] = byName[k] || []).push(it);
        var bk = baseNorm(it.Name);
        if (bk && bk.length >= 4) (byBase[bk] = byBase[bk] || []).push(it);
      });
      return { all: combined, byName: byName, byBase: byBase };
    });

    // 1. Resolve & render Top 10 Trending Today FIRST (never blocked by WordPress requests!)
    var trendingPromise = Promise.all([
      libPromise,
      Api.trending().catch(function () { return []; })
    ]).then(function (r1) {
      var libData = r1[0], trends = r1[1] || [];
      if (!trends.length) return [];
      var byName = libData.byName, byBase = libData.byBase;
      var trendSeen = {}, trendingItems = [];

      function pushTrending(it, rankNum) {
        if (!it || !it.Id || trendSeen[it.Id] || trendingItems.length >= 10) return false;
        trendSeen[it.Id] = 1;
        var copy = {};
        for (var k in it) if (Object.prototype.hasOwnProperty.call(it, k)) copy[k] = it[k];
        copy._trendingRank = rankNum || (trendingItems.length + 1);
        copy._displayRank = trendingItems.length + 1;
        trendingItems.push(copy);
        return true;
      }

      function findInLookup(tr) {
        var cands = (byName[norm(tr.name)] || []).concat(byBase[baseNorm(tr.name)] || []);
        // Pass 1: strict kind + year
        for (var i = 0; i < cands.length; i++) {
          var it = cands[i];
          if (!trendSeen[it.Id] && (!tr.kind || it.Type === tr.kind) && (!tr.year || !it.ProductionYear || Math.abs(it.ProductionYear - tr.year) <= 1)) return it;
        }
        // Pass 2: relaxed year/kind
        for (var j = 0; j < cands.length; j++) {
          var it2 = cands[j];
          if (!trendSeen[it2.Id] && (!tr.kind || it2.Type === tr.kind)) return it2;
        }
        return null;
      }

      var unmatched = [];
      trends.forEach(function (tr) {
        if (trendingItems.length >= 10) return;
        var match = findInLookup(tr);
        if (match) pushTrending(match, tr.rank);
        else if (unmatched.length < 14) unmatched.push(tr);
      });

      // Fallback A: If libraryIndex timed out or had < 10 matches, query Jellyfin /Items?searchTerm=... directly
      var searchStep = (trendingItems.length < 10 && unmatched.length && !param('mock')) ? runBatch(unmatched, 4, function (tr) {
        if (trendingItems.length >= 10) return null;
        return Api.search(tr.name, 6).then(function (sr) {
          var hits = (sr && sr.Items) || [];
          var want = norm(tr.name), bwant = baseNorm(tr.name);
          for (var i = 0; i < hits.length; i++) {
            var hIt = hits[i];
            if (norm(hIt.Name) === want || (bwant.length >= 4 && baseNorm(hIt.Name) === bwant)) return { it: hIt, rank: tr.rank };
          }
          return null;
        }, function () { return null; });
      }).then(function (found) {
        found.forEach(function (f) { if (f && f.it) pushTrending(f.it, f.rank); });
      }) : Promise.resolve();

      return searchStep.then(function () {
        // Fallback B: On real TV, if still < 10 items, top up from seedItems/library so Trending row never disappears
        if (trendingItems.length < 10 && !param('mock')) {
          var pool = (seedItems || []).concat(libData.all || []);
          for (var p = 0; p < pool.length && trendingItems.length < 10; p++) {
            var cand = pool[p];
            if (cand && (cand.Type === 'Movie' || cand.Type === 'Series')) pushTrending(cand, trendingItems.length + 1);
          }
        }
        if (onTrendingReady && trendingItems.length) onTrendingReady(trendingItems);
        return trendingItems;
      });
    });

    // 2. Resolve Platforms & Regional shelves in throttled batches of 3
    return Promise.all([
      libPromise,
      trendingPromise,
      Api.platforms().catch(function () { return { tvId: 0, list: [], regionals: [] }; })
    ]).then(function (r2) {
      var libData = r2[0], trendingItems = r2[1] || [], dir = r2[2] || { list: [], regionals: [] };
      var byName = libData.byName, byBase = libData.byBase;
      function matchPosts(posts, wantKind) {
        var seen = {}, items = [];
        posts.forEach(function (post) {
          var exact = byName[norm(post.name)] || [];
          var base = (!exact.length && baseNorm(post.name).length >= 4) ? (byBase[baseNorm(post.name)] || []) : [];
          var cands = exact.concat(base);
          var added = false;
          cands.forEach(function (it) {
            if (!seen[it.Id] && (!wantKind || it.Type === wantKind) && (!post.year || !it.ProductionYear || Math.abs(it.ProductionYear - post.year) <= 1)) {
              seen[it.Id] = 1;
              items.push(it);
              added = true;
            }
          });
          if (!added) {
            cands.forEach(function (it) {
              if (!seen[it.Id] && (!wantKind || it.Type === wantKind)) {
                seen[it.Id] = 1;
                items.push(it);
              }
            });
          }
        });
        return items;
      }

      var platPromise = runBatch(dir.list || [], 3, function (p) {
        return Api.platformTitles(p.id).then(function (posts) {
          return { id: p.id, name: p.name, slug: p.slug, items: matchPosts(posts, null) };
        }, function () { return { id: p.id, name: p.name, slug: p.slug, items: [] }; });
      }).then(function (ps) { return ps.filter(function (p) { return p && p.items.length > 0; }); });

      var regPromise = runBatch(dir.regionals || [], 3, function (reg) {
        return Api.platformTitles(reg.id).then(function (posts) {
          return { id: reg.id, slug: reg.slug, title: reg.title, kind: reg.kind, items: matchPosts(posts, reg.kind) };
        }, function () { return { id: reg.id, slug: reg.slug, title: reg.title, kind: reg.kind, items: [] }; });
      }).then(function (rs) { return rs.filter(function (reg) { return reg && reg.items.length > 0; }); });

      return Promise.all([platPromise, regPromise]).then(function (res) {
        discoveryCache = { platforms: res[0], regionals: res[1], trending: trendingItems };
        if (!param('mock')) { try { localStorage.setItem(DISC_KEY, JSON.stringify({ ts: Date.now(), data: discoveryCache })); } catch (e) {} }
        return discoveryCache;
      });
    });
  }

  var Screens = {
    signin: function (a) {
      root.innerHTML = '<div id="ota-banner" class="ota-banner glass" style="display:none"></div>' +
        '<div class="signin glass"><div class="brand"><i></i>MojaLoss <span class="ver-badge">v' + h(curVer()) + '</span></div><h1 style="margin-top:26px">' + (a.reason === 'expired' ? 'Approve this TV again' : 'Approve this TV from your phone') + '</h1>' +
        '<div style="margin-top:16px;font-size:21px;font-weight:600;letter-spacing:1.2px;text-transform:uppercase;color:#6BC497">6-Digit TV Code (enter this on your phone)</div>' +
        '<div class="code" id="code"><div style="font-size:28px;color:#93A89E;padding:18px 0">Generating 6-digit TV code\u2026</div></div>' +
        '<div class="steps">1. On your signed-in phone, open <b>MojaLoss \u2192 Settings (gear icon) \u2192 Approve a device</b>.<br>2. Type the <b>6-digit code shown above on this TV</b> into your phone and tap <b>Approve</b>.<br>This TV signs in automatically and stays signed in.</div>' +
        '<div class="actions" style="margin-top:34px"><div class="btn primary focusable" id="newcode">New code</div><div class="btn focusable" id="update-btn">Update \u00b7 v' + h(curVer()) + '</div></div><div class="muted" id="st" style="margin-top:20px;font-size:22px"></div></div>';
      var uBtn = document.getElementById('update-btn');
      if (uBtn) uBtn.onclick = openUpdateModal;
      refreshUpdateUi();
      var timer, alive = true, me = screen, st = function (t) { var e = document.getElementById('st'); if (e && screen === me) e.textContent = t; };
      function stop() { alive = false; clearTimeout(timer); }
      function start() {
        clearTimeout(timer);
        var codeEl = document.getElementById('code');
        if (codeEl) codeEl.innerHTML = '<div style="font-size:28px;color:#93A89E;padding:18px 0">Generating 6-digit TV code\u2026</div>';
        st('Connecting to www.mojaloss.stream\u2026');
        Api.qcInitiate().then(function (r) {
          if (!alive) return;
          document.getElementById('code').innerHTML = String(r.Code).split('').map(function (d) { return '<span>' + h(d) + '</span>'; }).join('');
          st('Waiting for you to enter ' + r.Code + ' on your phone\u2026');
          var started = Date.now();
          (function poll() {
            if (!alive || screen !== me) return;
            if (Date.now() - started > 5 * 60 * 1000) { st('Code expired. Press OK on New code.'); return; }
            Api.qcPoll(r.Secret).then(function (s) {
              if (!alive) return;
              if (s.Authenticated) { stop(); Api.qcAuthenticate(r.Secret).then(function () { stack = []; go('home', {}, true); toast('Signed in as ' + Api.userName()); }, function () { st('Sign-in failed. Choose New code.'); }); }
              else timer = setTimeout(poll, 5000);
            }, function (e) { st(e && e.offline ? 'Offline \u2014 retrying\u2026' : 'Waiting for approval\u2026'); timer = setTimeout(poll, 10000); });
          })();
        }, function (e) {
          var cEl = document.getElementById('code');
          if (cEl) cEl.innerHTML = '<div style="font-size:26px;color:#E5A93C;padding:18px 0">Could not load code \u2014 press New code</div>';
          st(e && e.offline ? 'You are offline. Check the network, then choose New code.' : 'Quick Connect is unavailable right now (' + (e && e.message ? e.message : 'error') + ').');
        });
      }
      document.getElementById('newcode').onclick = start;
      start(); Focus.first('#newcode');
      screen.cleanup = stop;
    },

    home: function () {
      root.innerHTML = topbar('<div class="btn focusable" id="search">Search</div><div class="btn focusable" id="update-btn">Update · v' + h(curVer()) + '</div>') +
        '<div id="ota-banner" class="ota-banner glass" style="display:none"></div>' +
        homeSkeletonHtml();
      document.getElementById('search').onclick = function () { go('search'); };
      document.getElementById('update-btn').onclick = openUpdateModal;
      refreshUpdateUi();
      var me = screen;
      Promise.all([
        Api.resume(),
        Api.latest('Movie'),
        Api.latest('Series'),
        Api.favorites().catch(function () { return { Items: [] }; }),
        Api.nextUp(undefined, 16).catch(function () { return { Items: [] }; })
      ]).then(function (r) {
        if (screen !== me) return;
        var skelHero = document.getElementById('home-skel-hero');
        if (skelHero && skelHero.parentNode) skelHero.parentNode.removeChild(skelHero);
        var map = {};
        r.forEach(function (x) { (x.Items || []).forEach(function (i) { if (!map[i.Id]) map[i.Id] = i; }); });
        var cwItems = r[0].Items || [];
        var cwIds = {};
        cwItems.forEach(function (i) { cwIds[i.Id] = 1; });
        var nextUpItems = (r[4].Items || []).filter(function (i) { return !cwIds[i.Id]; });
        var firstHero = cwItems[0] || nextUpItems[0] || (r[1].Items || [])[0] || (r[2].Items || [])[0] || (r[3].Items || [])[0] || null;
        var rowsBox = document.getElementById('rows');
        if (firstHero && rowsBox && rowsBox.parentNode && !document.getElementById('hero')) {
          var heroWrap = document.createElement('div');
          heroWrap.innerHTML = heroSectionHtml();
          rowsBox.parentNode.parentNode.insertBefore(heroWrap.firstChild, rowsBox.parentNode);
        }
        var html = '';
        html += cwItems.length ? row('Continue Watching', cwItems, true, 'cw') : '';
        html += '<section class="row" id="plat" style="display:none"><div class="track" id="chips"></div></section>'; // no heading, like Android
        html += '<section class="row" id="trending" style="display:none"></section>';
        if (nextUpItems.length) html += nextUpRow('Next Up', nextUpItems, 'nextup');
        html += row('Latest Movies', r[1].Items || [], false, 'lm') + row('Latest Series', r[2].Items || [], false, 'ls');
        html += '<div id="regional-rows"></div>';
        if ((r[3].Items || []).length) html += row('My List', r[3].Items, false, 'fav');
        rowsBox.innerHTML = html;
        if (firstHero) { updateHero(firstHero); }
        wireCards(map); artFocus(map);
        Focus.first('.card');
        function renderTrendingSection(tr) {
          if (screen !== me || !tr || !tr.length) return;
          var trSec = document.getElementById('trending');
          if (trSec && !trSec.children.length) {
            tr.forEach(function (it) { map[it.Id] = it; });
            trSec.style.display = '';
            trSec.innerHTML = '<h2>Top 10 Trending Today</h2><div class="track">' + tr.map(function (it, idx) { return rankCard(it, idx + 1); }).join('') + '</div>';
            wireCards(map, trSec);
            if (Focus.refreshRows) Focus.refreshRows();
          }
        }
        var homeSeed = [].concat(r[1].Items || [], r[2].Items || [], r[3].Items || []);
        loadDiscovery(homeSeed, renderTrendingSection).then(function (disc) {
          if (screen !== me || !disc) return;
          renderTrendingSection(disc.trending || []);
          var ps = disc.platforms || [];
          if (ps.length) {
            var chips = document.getElementById('chips'), sec = document.getElementById('plat');
            if (chips && sec) {
              sec.style.display = '';
              chips.innerHTML = ps.map(function (p, i) { return '<div class="chip focusable" data-i="' + i + '" data-slug="' + h(p.slug) + '" aria-label="Browse ' + h(p.name) + '">' + logoTile(p) + '<span class="cn">' + h(p.name) + '</span></div>'; }).join('');
              each(chips.querySelectorAll('.chip'), function (c) { c.onclick = function () { go('platform', { p: ps[+c.getAttribute('data-i')] }); }; });
            }
          }
          var regs = disc.regionals || [];
          var regBox = document.getElementById('regional-rows');
          if (regs.length && regBox) {
            regs.forEach(function (reg) { (reg.items || []).forEach(function (it) { if (!map[it.Id]) map[it.Id] = it; }); });
            regBox.innerHTML = regs.map(function (reg) {
              return row(reg.title, reg.items, false, 'reg-' + reg.slug, 'data-regional="' + h(reg.title) + '"');
            }).join('');
            wireCards(map, regBox);
          }
          if (Focus.refreshRows) Focus.refreshRows();
        }, function () { /* discovery rows simply stay hidden on failure */ });
      }, onErr);
    },

    platform: function (a) {
      var kind = a.kind || 'all', sort = a.sort || 'latest', p = a.p;
      var items = p.items.filter(function (i) { return kind === 'all' || (kind === 'movie' ? i.Type === 'Movie' : i.Type === 'Series'); });
      function rel(x) { return String(x.PremiereDate || (x.ProductionYear ? x.ProductionYear + '-00' : '') || ''); }
      items.sort(sort === 'latest' ? function (x, y) { return rel(y).localeCompare(rel(x)); } : function (x, y) { return x.Name.localeCompare(y.Name); });
      var pill = function (id, label, on) { return '<div class="pill focusable' + (on ? ' on' : '') + '" data-f="' + id + '">' + label + '</div>'; };
      root.innerHTML = '<div class="top"><div class="plogo" aria-label="' + h(p.name) + '">' + logoTile(p, true) + '</div></div><div class="filters" style="margin:6px 0 26px">' +
        pill('all', 'All', kind === 'all') + pill('movie', 'Movies', kind === 'movie') + pill('series', 'Series', kind === 'series') + '<span style="width:40px"></span>' +
        pill('latest', 'Newest release', sort === 'latest') + pill('az', 'A\u2013Z', sort === 'az') + '</div><div class="rows"><div class="rows-inner">' +
        (items.length ? row(items.length + (items.length === 1 ? ' title' : ' titles'), items) : '<div class="empty">No ' + (kind === 'movie' ? 'movies' : 'series') + ' from ' + h(p.name) + ' in your library</div>') + '</div></div>';
      var map = {}; items.forEach(function (i) { map[i.Id] = i; }); wireCards(map); artFocus(map);
      each(root.querySelectorAll('.pill'), function (e) { e.onclick = function () { var f = e.getAttribute('data-f'); if (f === 'latest' || f === 'az') a.sort = f; else a.kind = f; a.focus = f; render(); }; });
      Focus.first(a.focus ? '.pill[data-f="' + a.focus + '"]' : (items.length ? '.card' : '.pill'));
    },

    detail: function (a) {
      var me = screen;
      var epsList = a.eps || null;
      var nextUpEp = a.nextUpEp || null;
      var similarItems = a.similar || [];
      var seriesItem = a.seriesItem || null;
      var isLoading = true;
      var lastPreviewEpId = null;
      function epCard(ep, isNext) {
        var eNum = ep.IndexNumber != null ? ep.IndexNumber : 1;
        var pct = ep.UserData && ep.UserData.PlayedPercentage;
        var played = ep.UserData && ep.UserData.Played;
        var img = Api.img(ep, 'Backdrop', 420) || Api.img(ep, 'Primary', 420);
        var badge = isNext ? '<span class="ep-badge">Next Up</span>' : (played ? '<span class="ep-badge watched">\u2713 Watched</span>' : '');
        var rt = ep.RunTimeTicks ? '<span class="ep-runtime">' + h(fmt(ep.RunTimeTicks)) + '</span>' : '';
        var name = ep.Name || ('Episode ' + eNum);
        var sub = (ep.Overview && String(ep.Overview).trim()) ? ep.Overview : 'Stream this episode on MojaLoss.';
        return '<div class="card wide ep-card focusable" data-id="' + h(ep.Id) + '"><div class="art" style="background-image:url(\'' + h(img) + '\')">' + badge + rt +
          '<div class="ep-info"><div class="ep-title"><span class="ep-num">E' + eNum + '</span><span class="t" style="padding:0">' + h(name) + '</span></div><div class="ep-sub">' + h(sub) + '</div></div></div>' +
          (pct ? '<div class="prog"><b style="width:' + Math.round(pct) + '%"></b></div>' : '') +
          '</div>';
      }
      function personCard(p, idx) {
        var img = Api.personImg(p, p.PrimaryImageTag, 180);
        var initials = String(p.Name || '').replace(/[^A-Za-z0-9 ]/g, '').split(' ').filter(Boolean).slice(0, 2).map(function (w) { return w[0]; }).join('').toUpperCase() || '?';
        var av = img ? '<div class="person-avatar" style="background-image:url(\'' + h(img) + '\')"></div>' : '<div class="person-avatar">' + h(initials) + '</div>';
        var role = p.Role || p.Type || '';
        return '<div class="person-card focusable" data-person-idx="' + idx + '">' + av + '<div class="p-name">' + h(p.Name) + '</div>' + (role ? '<div class="p-role">' + h(role) + '</div>' : '') + '</div>';
      }
      function resolveActiveEp(it, eps) {
        if (it && it.Type === 'Episode') {
          if (eps && eps.length) {
            for (var k = 0; k < eps.length; k++) {
              var sameCode = it.ParentIndexNumber != null && it.IndexNumber != null && eps[k].ParentIndexNumber === it.ParentIndexNumber && eps[k].IndexNumber === it.IndexNumber;
              if (sameCode || (it.ParentIndexNumber == null && eps[k].Id === it.Id)) {
                if (it.UserData && it.UserData.PlaybackPositionTicks > 0) {
                  eps[k].UserData = eps[k].UserData || {};
                  eps[k].UserData.PlaybackPositionTicks = it.UserData.PlaybackPositionTicks;
                  if (it.UserData.PlayedPercentage) eps[k].UserData.PlayedPercentage = it.UserData.PlayedPercentage;
                }
                return eps[k];
              }
            }
          }
          return it;
        }
        if (nextUpEp) return nextUpEp;
        if (!eps || !eps.length) return null;
        for (var i = 0; i < eps.length; i++) {
          if (eps[i].UserData && eps[i].UserData.PlaybackPositionTicks > 0) return eps[i];
        }
        for (var j = 0; j < eps.length; j++) {
          if (!eps[j].UserData || !eps[j].UserData.Played) return eps[j];
        }
        return eps[0];
      }
      function epPlayLabel(nx) {
        if (!nx) return 'Play next episode';
        var sNum = nx.ParentIndexNumber != null ? nx.ParentIndexNumber : 1;
        var eNum = nx.IndexNumber != null ? nx.IndexNumber : 1;
        var code = 'S' + sNum + 'E' + eNum;
        var pos = nx.UserData && nx.UserData.PlaybackPositionTicks;
        if (pos > 0) return 'Resume ' + code + ' from ' + clock(pos / 1e7);
        return 'Play ' + code + (nx.Name ? ' \u00b7 ' + nx.Name : '');
      }
      function detailEyebrowText(it, seasonCount, epCount) {
        if (it.Type === 'Series') {
          var sTxt = seasonCount ? (seasonCount + (seasonCount === 1 ? ' SEASON' : ' SEASONS')) : '';
          var eTxt = epCount ? (epCount + (epCount === 1 ? ' EPISODE' : ' EPISODES')) : '';
          return ['SERIES', sTxt, eTxt].filter(Boolean).join(' \u00b7 ');
        }
        if (it.Type === 'Episode') {
          var sNum = it.ParentIndexNumber != null ? it.ParentIndexNumber : 1;
          var eNum = it.IndexNumber != null ? it.IndexNumber : 1;
          var sTxt2 = seasonCount ? (seasonCount + (seasonCount === 1 ? ' SEASON' : ' SEASONS')) : '';
          var eTxt2 = epCount ? (epCount + (epCount === 1 ? ' EPISODE' : ' EPISODES')) : '';
          return ['EPISODE \u00b7 S' + sNum + 'E' + eNum, sTxt2, eTxt2].filter(Boolean).join(' \u00b7 ');
        }
        return 'FEATURE FILM' + (it.ProductionYear ? ' \u00b7 ' + it.ProductionYear : '');
      }
      function detailCreditsHtml(it, sIt) {
        var ppl = (it.People && it.People.length) ? it.People : ((sIt && sIt.People) || []);
        if (!ppl.length) return '';
        var actors = [], dirs = [];
        ppl.forEach(function (p) {
          if (!p || !p.Name) return;
          if (p.Type === 'Director' || p.Type === 'Creator') { if (dirs.length < 2) dirs.push(p.Name); }
          else if (!p.Type || p.Type === 'Actor') { if (actors.length < 3) actors.push(p.Name); }
        });
        var segs = [];
        if (actors.length) segs.push('<b>Starring</b> ' + h(actors.join(', ')));
        if (dirs.length) segs.push('<b>Directed by</b> ' + h(dirs.join(', ')));
        return segs.length ? '<div class="detail-credits" id="detail-credits">' + segs.join(' \u00b7 ') + '</div>' : '';
      }
      function updateEpPreview(ep, isNext) {
        if (!ep) return;
        var el = document.getElementById('ep-preview');
        if (!el) return;
        var changed = lastPreviewEpId && lastPreviewEpId !== ep.Id;
        lastPreviewEpId = ep.Id;
        var sNum = ep.ParentIndexNumber != null ? ep.ParentIndexNumber : 1;
        var eNum = ep.IndexNumber != null ? ep.IndexNumber : 1;
        var pct = (ep.UserData && ep.UserData.PlayedPercentage) || 0;
        var played = ep.UserData && ep.UserData.Played;
        var status = isNext ? 'Next Up' : (pct > 0 ? (Math.round(pct) + '% watched') : (played ? 'Watched' : 'Unwatched'));
        var metaParts = [ep.RunTimeTicks ? fmt(ep.RunTimeTicks) : null, status].filter(Boolean);
        var desc = (ep.Overview && String(ep.Overview).trim()) ? ep.Overview : 'Stream this episode on MojaLoss.';
        el.innerHTML = '<div class="ep-prev-head"><span class="ep-prev-tag" id="ep-prev-tag">S' + sNum + ' \u00b7 E' + eNum + ' \u2014 ' + h(ep.Name || ('Episode ' + eNum)) + '</span>' +
          '<span class="ep-prev-meta" id="ep-prev-meta">' + h(metaParts.join(' \u00b7 ')) + '</span></div>' +
          '<div class="ep-prev-desc" id="ep-prev-desc">' + h(desc) + '</div>';
        var artCard = document.getElementById('detail-art-card');
        var artLbl = document.getElementById('detail-art-label');
        var epArt = Api.img(ep, 'Backdrop', 560) || Api.img(ep, 'Primary', 560);
        if (artCard && epArt) artCard.style.backgroundImage = 'url(\'' + epArt + '\')';
        if (artLbl) artLbl.textContent = 'S' + sNum + ' \u00b7 E' + eNum + ' \u2014 ' + (ep.Name || ('Episode ' + eNum));
        if (changed) {
          el.classList.remove('hero-swap');
          void el.offsetWidth;
          el.classList.add('hero-swap');
        }
      }
      function draw(it) {
        if (screen !== me) return;
        var isEpisodic = it.Type === 'Series' || it.Type === 'Episode';
        var bgUrl = Api.img(it, 'Backdrop', 1280) || (seriesItem && Api.img(seriesItem, 'Backdrop', 1280)) || Api.img(it, 'Primary', 1280);
        art(bgUrl);
        var pos = it.UserData && it.UserData.PlaybackPositionTicks, resume = pos > 0 && !isEpisodic;
        var favTarget = (it.Type === 'Episode' && seriesItem) ? seriesItem : it;
        var fav = favTarget.UserData && favTarget.UserData.IsFavorite;
        var nx = isEpisodic ? resolveActiveEp(it, epsList) : null;
        var nxPos = (nx && nx.UserData && nx.UserData.PlaybackPositionTicks) || 0;
        var canRestart = resume || (isEpisodic && nxPos > 0);
        var playLabel = isEpisodic ? epPlayLabel(nx) : (resume ? 'Resume from ' + clock(pos / 1e7) : 'Play');
        var watchedTarget = isEpisodic ? (nx || it) : it;
        var isPlayed = !!(watchedTarget.UserData && watchedTarget.UserData.Played);

        var rawPeople = (it.People && it.People.length) ? it.People : ((seriesItem && seriesItem.People) || []);
        var people = (rawPeople.filter(function (p) {
          return !p.Type || p.Type === 'Actor' || p.Type === 'Director' || p.Type === 'Creator' || p.Type === 'Writer';
        })).slice(0, 14);

        var seasonsHtml = '', epsHtml = '', seasonEps = [], seasonNums = [];
        if (epsList && epsList.length) {
          var bySeason = {};
          epsList.forEach(function (ep) {
            var sNum = ep.ParentIndexNumber != null ? ep.ParentIndexNumber : 1;
            if (!bySeason[sNum]) { bySeason[sNum] = []; seasonNums.push(sNum); }
            bySeason[sNum].push(ep);
          });
          seasonNums.sort(function (x, y) { return x === 0 ? 1 : y === 0 ? -1 : x - y; });
          if (a.season == null || !bySeason[a.season]) {
            var defS = nx && nx.ParentIndexNumber != null && bySeason[nx.ParentIndexNumber] ? nx.ParentIndexNumber : seasonNums[0];
            a.season = defS;
          }
          seasonEps = (bySeason[a.season] || []).slice().sort(function (x, y) { return (x.IndexNumber || 0) - (y.IndexNumber || 0); });
          var jumpHtml = '';
          if (people.length || (similarItems && similarItems.length)) {
            jumpHtml = '<span class="pill-sep"></span>' +
              (people.length ? '<div class="pill focusable" data-jump="#cast-row .person-card">Cast &amp; Crew</div>' : '') +
              ((similarItems && similarItems.length) ? '<div class="pill focusable" data-jump="#similar-row .card">More Like This</div>' : '');
          }
          seasonsHtml = '<div class="filters row" id="season-pills">' + seasonNums.map(function (sNum) {
            var label = (sNum === 0 ? 'Specials' : ('Season ' + sNum)) + ' \u00b7 ' + bySeason[sNum].length;
            return '<div class="pill focusable' + (sNum === a.season ? ' on' : '') + '" data-season="' + sNum + '">' + h(label) + '</div>';
          }).join('') + jumpHtml + '</div>';
          epsHtml = '<section class="row" id="eps"><div class="ep-preview" id="ep-preview"></div><div class="track">' +
            seasonEps.map(function (ep) { return epCard(ep, nx && nx.Id === ep.Id); }).join('') + '</div></section>';
        } else if (isEpisodic && isLoading) {
          seasonsHtml = '<div class="filters" id="season-pills-skel"><div class="skel-pill skel-pulse" style="height:44px;width:148px"></div><div class="skel-pill skel-pulse" style="height:44px;width:148px"></div></div>';
          epsHtml = '<section class="row" id="eps-skel"><div class="loading-inline"><span class="spinner-ring"></span><span>Loading episodes\u2026</span></div><div class="track">' +
            [1, 2, 3, 4].map(function () { return '<div class="skel-card ep skel-pulse"></div>'; }).join('') + '</div></section>';
        }

        var castHtml = people.length ? ('<section class="row" id="cast-row"><h2>Cast &amp; Crew</h2><div class="track">' +
          people.map(function (p, idx) { return personCard(p, idx); }).join('') + '</div></section>') : '';

        var simHtml = (similarItems && similarItems.length) ? row('More Like This', similarItems, false, 'similar-row') : '';

        var eyebrow = detailEyebrowText(it, seasonNums.length, epsList ? epsList.length : 0);
        var credits = detailCreditsHtml(it, seriesItem);
        var metaYear = it.ProductionYear || (seriesItem && seriesItem.ProductionYear);
        var metaRating = it.OfficialRating || (seriesItem && seriesItem.OfficialRating);
        var metaGenres = ((it.Genres && it.Genres.length) ? it.Genres : ((seriesItem && seriesItem.Genres) || [])).slice(0, 3).join(' \u00b7 ');
        var headerOverview = (it.Overview && String(it.Overview).trim()) ? it.Overview : ((seriesItem && seriesItem.Overview) || '');
        var descHtml = headerOverview
          ? ('<p id="detail-desc">' + h(headerOverview) + '</p>')
          : (isLoading ? '<div id="detail-desc" style="margin:10px 0 14px"><div class="skel-bar skel-pulse" style="width:680px;margin-bottom:8px"></div><div class="skel-bar skel-pulse" style="width:480px"></div></div>' : '<p id="detail-desc"></p>');

        var artCardImg = (nx && (Api.img(nx, 'Backdrop', 560) || Api.img(nx, 'Primary', 560))) || Api.img(it, 'Backdrop', 560) || Api.img(it, 'Primary', 560) || bgUrl;
        var artCardCaption = nx ? ('S' + (nx.ParentIndexNumber || 1) + ' \u00b7 E' + (nx.IndexNumber || 1) + ' \u2014 ' + (nx.Name || 'Next Up')) : (it.Name || 'Featured');

        root.innerHTML = topbar() + '<div class="rows"><div class="rows-inner"><div class="detail row" id="detail-top">' +
          '<div class="detail-main">' +
          '<div class="hero-eyebrow" id="detail-eyebrow">' + h(eyebrow) + '</div>' +
          '<h1>' + h(it.Type === 'Episode' ? (it.SeriesName || (seriesItem && seriesItem.Name) || it.Name) : it.Name) + '</h1><div class="meta">' +
          [metaYear, metaRating, it.RunTimeTicks && fmt(it.RunTimeTicks), metaGenres].filter(Boolean).map(function (m) { return '<span>' + h(m) + '</span>'; }).join('') +
          '</div>' + (it.Type === 'Episode' ? '<h2 style="margin-top:12px">S' + (it.ParentIndexNumber || 1) + ' E' + (it.IndexNumber || 1) + ' \u00b7 ' + h(it.Name) + '</h2>' : '') +
          descHtml + credits +
          '<div class="actions"><div class="btn primary focusable" id="play">' + h(playLabel) + '</div>' +
          (canRestart ? '<div class="btn focusable" id="restart">From the start</div>' : '') +
          '<div class="btn focusable" id="fav">' + (fav ? '\u2713 In My List' : '+ My List') + '</div>' +
          '<div class="btn focusable" id="watched-btn">' + (isPlayed ? '\u2713 Watched' : 'Mark watched') + '</div></div>' +
          '</div>' +
          '<div class="detail-art-card" id="detail-art-card" style="background-image:url(\'' + h(artCardImg) + '\')"><span class="detail-art-label" id="detail-art-label">' + h(artCardCaption) + '</span></div>' +
          '</div>' +
          seasonsHtml + epsHtml + castHtml + simHtml + '</div></div>';

        if (seasonEps.length) {
          var initPreviewEp = (nx && nx.ParentIndexNumber === a.season) ? nx : seasonEps[0];
          updateEpPreview(initPreviewEp, nx && initPreviewEp && nx.Id === initPreviewEp.Id);
        }

        document.getElementById('play').onclick = function () {
          if (!isEpisodic) return play(it, resume ? pos : 0);
          var targetEp = resolveActiveEp(it, epsList);
          if (targetEp) return play(targetEp, (targetEp.UserData && targetEp.UserData.PlaybackPositionTicks) || 0);
          var sId = it.Type === 'Series' ? it.Id : it.SeriesId;
          Api.nextUp(sId).then(function (n) { var ep = (n.Items || [])[0] || (epsList || [])[0]; if (ep) play(ep, (ep.UserData && ep.UserData.PlaybackPositionTicks) || 0); else toast('No episodes yet'); }, onErr);
        };
        var r = document.getElementById('restart');
        if (r) r.onclick = function () {
          if (isEpisodic && nx) return play(nx, 0);
          play(it, 0);
        };
        document.getElementById('fav').onclick = function () {
          Api.setFavorite(favTarget.Id, !fav).then(function () { favTarget.UserData = favTarget.UserData || {}; favTarget.UserData.IsFavorite = !fav; toast(!fav ? 'Added to My List' : 'Removed from My List'); a.focus = '#fav'; draw(it); }, onErr);
        };
        var wBtn = document.getElementById('watched-btn');
        if (wBtn) wBtn.onclick = function () {
          Api.setPlayed(watchedTarget.Id, !isPlayed).then(function () {
            watchedTarget.UserData = watchedTarget.UserData || {};
            watchedTarget.UserData.Played = !isPlayed;
            if (!isPlayed) { watchedTarget.UserData.PlaybackPositionTicks = 0; watchedTarget.UserData.PlayedPercentage = 0; }
            toast(!isPlayed ? 'Marked as watched' : 'Marked as unwatched');
            a.focus = '#watched-btn';
            draw(it);
          }, onErr);
        };
        each(root.querySelectorAll('#season-pills .pill'), function (pillEl) {
          pillEl.onclick = function () {
            var jumpSel = pillEl.getAttribute('data-jump');
            if (jumpSel) { Focus.first(jumpSel); return; }
            var sNum = +pillEl.getAttribute('data-season');
            a.season = sNum;
            a.focus = '#season-pills .pill[data-season="' + sNum + '"]';
            draw(it);
          };
        });
        var focusMap = {};
        if (epsList && epsList.length) {
          var m = {}; epsList.forEach(function (e) { m[e.Id] = e; focusMap[e.Id] = e; });
          wireCards(m, document.getElementById('eps'), function (ep) { play(ep, (ep.UserData && ep.UserData.PlaybackPositionTicks) || 0); });
        }
        if (people.length) {
          each(root.querySelectorAll('#cast-row .person-card'), function (pc) {
            pc.onclick = function () {
              var p = people[+pc.getAttribute('data-person-idx')];
              if (p && p.Name) go('search', { term: p.Name });
            };
          });
        }
        if (similarItems && similarItems.length) {
          var sm = {}; similarItems.forEach(function (s) { sm[s.Id] = s; focusMap[s.Id] = s; });
          wireCards(sm, document.getElementById('similar-row'), function (sIt) { go('detail', { item: sIt }); });
        }
        Focus.onFocus = function (el) {
          if (!el) return;
          var id = el.getAttribute && el.getAttribute('data-id');
          if (id && focusMap[id]) {
            var fIt = focusMap[id];
            art(Api.img(fIt, 'Backdrop', 1280) || Api.img(fIt, 'Primary', 1280) || bgUrl);
            if (el.classList.contains('ep-card')) {
              updateEpPreview(fIt, nx && nx.Id === fIt.Id);
            }
          } else if (el.closest && el.closest('#detail-top')) {
            art(bgUrl);
          }
        };
        Focus.first(a.focus || '#play'); a.focus = null;
      }
      draw(a.item);
      var initSeriesId = a.item.Type === 'Series' ? a.item.Id : (a.item.SeriesId || null);
      Promise.all([
        Api.item(a.item.Id),
        Api.similar(initSeriesId || a.item.Id).catch(function () { return { Items: [] }; }),
        initSeriesId ? Api.episodes(initSeriesId).catch(function () { return { Items: [] }; }) : Promise.resolve(null),
        initSeriesId ? Api.nextUp(initSeriesId).catch(function () { return { Items: [] }; }) : Promise.resolve(null),
        (a.item.Type === 'Episode' && initSeriesId) ? Api.item(initSeriesId).catch(function () { return null; }) : Promise.resolve(null)
      ]).then(function (res) {
        if (screen !== me) return;
        isLoading = false;
        var full = res[0];
        a.item = full;
        similarItems = a.similar = (res[1] && res[1].Items) || [];
        seriesItem = a.seriesItem = res[4] || seriesItem;
        var resolvedSeriesId = full.Type === 'Series' ? full.Id : (full.SeriesId || initSeriesId);
        if (resolvedSeriesId) {
          epsList = a.eps = (res[2] && res[2].Items) || [];
          nextUpEp = a.nextUpEp = (res[3] && res[3].Items && res[3].Items[0]) || null;
          if (!res[2]) {
            Promise.all([
              Api.episodes(resolvedSeriesId).catch(function () { return { Items: [] }; }),
              Api.nextUp(resolvedSeriesId).catch(function () { return { Items: [] }; }),
              (full.Type === 'Episode' && !seriesItem) ? Api.item(resolvedSeriesId).catch(function () { return null; }) : Promise.resolve(seriesItem),
              (!similarItems.length) ? Api.similar(resolvedSeriesId).catch(function () { return { Items: [] }; }) : Promise.resolve({ Items: similarItems })
            ]).then(function (r2) {
              if (screen !== me) return;
              epsList = a.eps = (r2[0] && r2[0].Items) || [];
              nextUpEp = a.nextUpEp = (r2[1] && r2[1].Items && r2[1].Items[0]) || nextUpEp;
              seriesItem = a.seriesItem = r2[2] || seriesItem;
              similarItems = a.similar = (r2[3] && r2[3].Items) || similarItems;
              draw(full);
            });
            return;
          }
        }
        draw(full);
      }, function (e) { if (e && e.status === 401) onErr(e); });
    },

    search: function (a) {
      var term = a.term || '';
      var keys = 'abcdefghijklmnopqrstuvwxyz0123456789'.split('');
      root.innerHTML = topbar() + '<div class="searchbox glass" id="sb"></div><div class="keys">' +
        keys.map(function (k) { return '<div class="key focusable" data-k="' + k + '">' + k.toUpperCase() + '</div>'; }).join('') +
        '<div class="key w2 focusable" data-k=" ">Space</div><div class="key w2 focusable" data-k="del">Delete</div></div><div class="rows" style="height:520px"><div class="rows-inner" id="res"></div></div>';
      var t, me = screen, seq = 0;
      function box() { document.getElementById('sb').innerHTML = term ? h(term) : '<span class="muted">Search movies and series</span>'; }
      function run() {
        var res = document.getElementById('res'), my = ++seq;
        if (term.trim().length < 2) { res.innerHTML = ''; return; }
        res.innerHTML = '<div class="loading-inline"><span class="spinner-ring"></span><span>Searching \u201c' + h(term.trim()) + '\u201d\u2026</span></div><div class="track">' +
          [1, 2, 3, 4, 5, 6].map(function () { return '<div class="skel-card skel-pulse"></div>'; }).join('') + '</div>';
        Api.search(term.trim()).then(function (r) {
          if (screen !== me || my !== seq) return; // ignore stale answers
          var map = {}; (r.Items || []).forEach(function (i) { map[i.Id] = i; });
          res.innerHTML = (r.Items || []).length ? row('Results', r.Items) : '<div class="empty">No matches for \u201c' + h(term.trim()) + '\u201d</div>'; wireCards(map, res);
        }, function (e) { if (e && e.status === 401) return onErr(e); if (my === seq) res.innerHTML = '<div class="empty">' + (e && e.offline ? 'You are offline.' : 'Search failed.') + ' Press OK on a key to try again.</div>'; });
      }
      each(root.querySelectorAll('.key'), function (k) { k.onclick = function () { var v = k.getAttribute('data-k'); term = v === 'del' ? term.slice(0, -1) : term + v; a.term = term; box(); clearTimeout(t); t = setTimeout(run, 450); }; });
      box(); Focus.first('.key'); if (term) run();
      screen.cleanup = function () { clearTimeout(t); };
    }
  };

  /* Player: HTML5 video (Tizen plays HLS natively). Server-side audio/subtitle choice via PlaybackInfo; external VTT tracks rendered by the browser. */
  function play(it, startTicks, opt) {
    opt = opt || {};
    Api.playbackInfo(it.Id, opt).then(function (info) {
      if (player) player.teardown();
      var src = info.MediaSources[0], streams = src.MediaStreams || [];
      var audios = streams.filter(function (s) { return s.Type === 'Audio'; }), subs = streams.filter(function (s) { return s.Type === 'Subtitle'; });
      var audioIdx = opt.audio != null ? opt.audio : src.DefaultAudioStreamIndex, subIdx = opt.sub != null ? opt.sub : -1;
      var url = Api.streamUrl(it, info), sessionId = info.PlaySessionId, retries = 0, stallT, hideT, tick, menu = null, ended = false;
      var el = document.createElement('div'); el.id = 'player';
      el.innerHTML = '<video autoplay playsinline></video><div class="buffer" id="buf"><span class="spinner-ring"></span><span id="buf-txt">Buffering\u2026</span></div><div class="osd glass" id="osd"><div style="font-size:32px;font-weight:600">' + h(it.SeriesName ? it.SeriesName + ' \u00b7 ' + it.Name : it.Name) + '</div><div class="bar"><b id="pb" style="width:0"></b></div><div class="row2"><span id="pt">0:00</span><span>OK pause \u00b7 \u25c0\u25b6 10s \u00b7 \u25b2 audio & subtitles \u00b7 Back exit</span><span id="pd">' + clock((it.RunTimeTicks || 0) / 1e7) + '</span></div></div><div class="menu glass" id="menu" style="display:none"></div>';
      document.body.appendChild(el);
      var v = el.querySelector('video'), osd = el.querySelector('#osd'), buf = el.querySelector('#buf'), bufTxt = el.querySelector('#buf-txt'), menuEl = el.querySelector('#menu'), ptEl = el.querySelector('#pt'), pbEl = el.querySelector('#pb');
      function setBuf(msg, showBuf) { if (bufTxt) bufTxt.textContent = msg; else buf.textContent = msg; buf.style.display = showBuf ? 'flex' : 'none'; }
      var subStream = subs.filter(function (s) { return s.Index === subIdx; })[0];
      if (subStream && (subStream.DeliveryMethod === 'External' || subStream.IsExternal || subStream.IsTextSubtitleStream)) {
        var tr = document.createElement('track'); tr.kind = 'subtitles'; tr.default = true; tr.src = subStream.DeliveryUrl ? Api.jf() + subStream.DeliveryUrl : Api.subtitleUrl(it, src, subStream); v.appendChild(tr);
        tr.track && (tr.track.mode = 'showing');
      }
      var startSec = (startTicks || 0) / 1e7;
      function load(at) { v.src = url; v.load(); v.addEventListener('loadedmetadata', function once() { v.removeEventListener('loadedmetadata', once); if (at > 0) v.currentTime = at; var p = v.play(); if (p && p.catch) p.catch(function () {}); }); }
      load(startSec);
      function pos() { return v.currentTime || startSec; }
      function base() { return { ItemId: it.Id, PlaySessionId: sessionId, MediaSourceId: src.Id, PositionTicks: Math.floor(pos() * 1e7), IsPaused: v.paused, AudioStreamIndex: audioIdx, SubtitleStreamIndex: subIdx, PlayMethod: src.TranscodingUrl ? 'Transcode' : 'DirectPlay', CanSeek: true }; }
      function show() { osd.classList.remove('hide'); clearTimeout(hideT); hideT = setTimeout(function () { if (!v.paused && !menu) osd.classList.add('hide'); }, 4000); }
      function recover() {
        // network drop: retry from the last position with backoff (2s, 4s, 8s, 16s, 30s), then give up with a message
        if (ended || !player) return;
        if (retries >= 5) { setBuf('Playback stopped: connection lost. Press OK to retry.', true); retries = 0; player.waitingRetry = true; return; }
        var at = pos(), wait = Math.min(30000, 2000 * Math.pow(2, retries++));
        setBuf('Connection problem \u2014 retrying in ' + Math.round(wait / 1000) + 's\u2026', true);
        setTimeout(function () { if (player && player.v === v) { startSec = at; load(at); } }, wait);
      }
      v.ontimeupdate = function () { var d = v.duration || (it.RunTimeTicks || 0) / 1e7; ptEl.textContent = clock(v.currentTime); if (d && isFinite(d)) pbEl.style.width = (100 * v.currentTime / d) + '%'; };
      v.onplaying = function () { setBuf('', false); retries = 0; clearTimeout(stallT); Api.reportProgress('', base()); };
      v.onwaiting = function () { setBuf('Buffering\u2026', true); clearTimeout(stallT); stallT = setTimeout(recover, 20000); };
      v.onerror = function () { recover(); };
      v.onended = function () {
        ended = true; Api.reportProgress('Stopped', base());
        if (it.Type === 'Episode' && it.SeriesId) Api.nextUp(it.SeriesId).then(function (n) { var nx = (n.Items || [])[0]; if (nx && nx.Id !== it.Id) { toast('Up next: ' + nx.Name); play(nx, 0); } else stopPlayer(); }, function () { stopPlayer(); });
        else stopPlayer();
      };
      tick = setInterval(function () { if (!v.paused) Api.reportProgress('Progress', base()); }, 10000);
      function openMenu() {
        var label = function (s) { return h(s.DisplayTitle || s.Language || ('Track ' + s.Index)); };
        menuEl.innerHTML = '<h2>Audio</h2>' + (audios.length ? audios.map(function (s) { return '<div class="opt focusable' + (s.Index === audioIdx ? ' on' : '') + '" data-a="' + s.Index + '">' + label(s) + '</div>'; }).join('') : '<div class="empty">Default</div>') +
          '<h2 style="margin-top:24px">Subtitles</h2><div class="opt focusable' + (subIdx === -1 ? ' on' : '') + '" data-s="-1">Off</div>' + subs.map(function (s) { return '<div class="opt focusable' + (s.Index === subIdx ? ' on' : '') + '" data-s="' + s.Index + '">' + label(s) + '</div>'; }).join('');
        menuEl.style.display = 'block'; menu = true;
        each(menuEl.querySelectorAll('.opt'), function (o) { o.onclick = function () {
          var na = o.hasAttribute('data-a') ? +o.getAttribute('data-a') : audioIdx, ns = o.hasAttribute('data-s') ? +o.getAttribute('data-s') : subIdx;
          if (na === audioIdx && ns === subIdx) return closeMenu();
          Api.reportProgress('Stopped', base()); var at = pos(); player.teardown(); player = null;
          play(it, Math.floor(at * 1e7), { audio: na, sub: ns, source: src.Id }); // restart stream at the same spot with the new tracks
        }; });
        Focus.first('#menu .opt.on') ;
      }
      function closeMenu() { if (!menu) return false; menu = null; menuEl.style.display = 'none'; Focus.set(null); show(); return true; }
      player = { el: el, v: v, it: it, recover: recover, closeMenu: closeMenu, base: base,
        teardown: function () { clearInterval(tick); clearTimeout(stallT); v.onerror = v.onended = v.onwaiting = v.ontimeupdate = v.onplaying = null; v.removeAttribute('src'); try { v.load(); } catch (e) {} el.parentNode && el.parentNode.removeChild(el); },
        stop: function () { if (!ended) Api.reportProgress('Stopped', base()); this.teardown(); },
        keys: function (k) {
          if (menu) { if (k === K.UP) Focus.move('U'); else if (k === K.DOWN) Focus.move('D'); else if (k === K.OK) { var c = Focus.current(); c && c.onclick && c.onclick(); } return true; }
          show();
          if (player.waitingRetry && k === K.OK) { player.waitingRetry = false; recover(); return true; }
          if (k === K.OK || k === K.PLAYPAUSE) { if (v.paused) v.play(); else v.pause(); }
          else if (k === K.PLAY) v.play(); else if (k === K.PAUSE) v.pause();
          else if (k === K.RIGHT || k === K.FF) v.currentTime = Math.min((v.duration || 1e9) - 1, v.currentTime + (k === K.FF ? 30 : 10));
          else if (k === K.LEFT || k === K.RW) v.currentTime = Math.max(0, v.currentTime - (k === K.RW ? 30 : 10));
          else if (k === K.UP || k === K.DOWN) openMenu();
          else if (k === K.STOP) stopPlayer();
          return true;
        } };
      show();
    }, onErr);
  }
  function stopPlayer() { if (player) { var p = player; player = null; p.stop(); if (screen && screen.name === 'detail') render(); } }

  document.addEventListener('keydown', function (e) {
    var k = e.keyCode;
    if (k === K.BACK || k === 8 || k === 27) { e.preventDefault(); return back(); }
    if (player) { e.preventDefault(); player.keys(k); return; }
    var map = { 37: 'L', 38: 'U', 39: 'R', 40: 'D' };
    if (map[k]) { e.preventDefault(); Focus.move(map[k]); }
    else if (k === K.OK) { var c = Focus.current(); if (c && c.onclick) c.onclick(); }
  });
  document.addEventListener('mouseover', function (e) { var f = e.target.closest && e.target.closest('.focusable'); if (f) Focus.set(f); });
  document.addEventListener('visibilitychange', function () { if (document.hidden && player) player.v.pause(); }); // Smart Hub / source switch

  window.MLTV = { go: go, play: play, fitViewport: fitViewport, homeSkeletonHtml: homeSkeletonHtml, openUpdateModal: openUpdateModal, closeUpdateModal: closeUpdateModal, registeredKeys: registered, player: function () { return player; }, screen: function () { return screen && screen.name; } }; // test hooks
  if (window.__mltvBootOk) window.__mltvBootOk();
  go(Api.signedIn() ? 'home' : 'signin');
  if (window.MLTV_OTA) setTimeout(function () { MLTV_OTA.checkForUpdates(true); }, 600);
})();
