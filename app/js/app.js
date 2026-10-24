/* MojaLoss TV — screens: signin, home, platform, detail, search, player. ES5 only (Tizen 3+). */
(function () {
  var root = document.getElementById('root'), bd = document.getElementById('backdrop');
  var stack = [], screen = null, player = null, lastBack = 0;
  function each(list, fn) { Array.prototype.forEach.call(list, fn); }
  function param(k) { var m = new RegExp('[?&]' + k + '=([^&]+)').exec(location.search); return m ? decodeURIComponent(m[1]) : null; }

  var KEYS = ['MediaPlay', 'MediaPause', 'MediaPlayPause', 'MediaFastForward', 'MediaRewind', 'MediaStop', 'ColorF0Red', 'ColorF1Green'];
  var registered = [];
  try { KEYS.forEach(function (k) { tizen.tvinputdevice.registerKey(k); registered.push(k); }); } catch (e) {}
  var K = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, OK: 13, BACK: 10009, PLAY: 415, PAUSE: 19, PLAYPAUSE: 10252, FF: 417, RW: 412, STOP: 413, RED: 403, GREEN: 404 };

  function h(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return '&#' + c.charCodeAt(0) + ';'; }); }
  function toast(t) { var e = document.getElementById('toast'); e.textContent = t; e.classList.add('show'); clearTimeout(toast.t); toast.t = setTimeout(function () { e.classList.remove('show'); }, 3500); }
  function art(url) { bd.style.backgroundImage = url ? 'linear-gradient(90deg,rgba(16,27,23,.97) 30%,rgba(16,27,23,.55) 70%,rgba(16,27,23,.9)),url("' + url + '")' : ''; }
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
  function render() { Focus.onFocus = null; art(''); screen.cleanup = null; Screens[screen.name](screen.args); }

  function logoTile(p, big) {
    var L = PlatformLogos[p.slug], s = big ? 120 : 64;
    if (L) return '<span class="logo" style="width:' + s + 'px;height:' + s + 'px;background:' + L.brand + '"><svg viewBox="0 0 24 24" width="' + (s * .56) + '" height="' + (s * .56) + '"><path fill="#fff" d="' + L.path + '"/></svg></span>';
    var mono = String(p.name).replace(/[^A-Za-z0-9 ]/g, '').split(' ').filter(Boolean).slice(0, 2).map(function (w) { return w[0]; }).join('').toUpperCase();
    return '<span class="logo mono" style="width:' + s + 'px;height:' + s + 'px">' + h(mono) + '</span>';
  }
  function card(it, wide) {
    var pct = it.UserData && it.UserData.PlayedPercentage;
    var img = wide ? (Api.img(it, 'Backdrop', 420) || Api.img(it, 'Primary', 420)) : Api.img(it, 'Primary', 240);
    var title = it.Type === 'Episode' ? (it.SeriesName + ' \u00b7 S' + (it.ParentIndexNumber || 0) + 'E' + (it.IndexNumber || 0)) : it.Name;
    return '<div class="card focusable' + (wide ? ' wide' : '') + '" data-id="' + h(it.Id) + '"><div class="art" style="background-image:url(\'' + h(img) + '\')"></div>' +
      (pct ? '<div class="prog"><b style="width:' + Math.round(pct) + '%"></b></div>' : '') + '<div class="t">' + h(title) + '</div></div>';
  }
  function row(title, items, wide, id) {
    return '<section class="row"' + (id ? ' id="' + id + '"' : '') + '>' + (title ? '<h2>' + h(title) + '</h2>' : '') + '<div class="track">' + (items.length ? items.map(function (i) { return card(i, wide); }).join('') : '<div class="empty">Nothing here yet</div>') + '</div></section>';
  }
  function wireCards(map, scope, fn) {
    each((scope || root).querySelectorAll('.card'), function (c) { c.onclick = function () { var it = map[c.getAttribute('data-id')]; if (it) (fn || function (x) { go('detail', { item: x }); })(it); }; });
  }
  function artFocus(map) { Focus.onFocus = function (e) { var it = map[e.getAttribute('data-id')]; if (it) art(Api.img(it, 'Backdrop', 1920)); }; }
  function curVer() { return (window.MLTV_OTA && MLTV_OTA.version()) || '0.1.2'; }
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
    var st = window.MLTV_OTA ? MLTV_OTA.state() : { activeVersion: '0.1.2', activeSource: 'builtin', builtinVersion: '0.1.2' };
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
  if (window.MLTV_OTA) MLTV_OTA.onChange(refreshUpdateUi);
  function norm(s) { return String(s).toLowerCase().replace(/&amp;/g, '&').replace(/[^a-z0-9]+/g, ''); }

  var platformCache = null;
  function loadPlatforms() {
    if (platformCache) return Promise.resolve(platformCache);
    return Promise.all([Api.platforms(), Api.libraryIndex()]).then(function (r) {
      var dir = r[0], lib = r[1].Items || [], byName = {};
      lib.forEach(function (it) { (byName[norm(it.Name)] = byName[norm(it.Name)] || []).push(it); });
      return Promise.all(dir.list.map(function (p) {
        return Api.platformTitles(p.id).then(function (posts) {
          var seen = {}, items = [];
          posts.forEach(function (post) { (byName[norm(post.name)] || []).forEach(function (it) { if (!seen[it.Id] && (!post.year || !it.ProductionYear || Math.abs(it.ProductionYear - post.year) <= 1)) { seen[it.Id] = 1; items.push(it); } }); });
          return { id: p.id, name: p.name, slug: p.slug, items: items };
        }, function () { return { id: p.id, name: p.name, slug: p.slug, items: [] }; });
      })).then(function (ps) { platformCache = ps.filter(function (p) { return p.items.length > 0; }); return platformCache; }); // empty platforms stay hidden
    });
  }

  var Screens = {
    signin: function (a) {
      root.innerHTML = '<div class="signin glass"><div class="brand"><i></i>MojaLoss</div><h1 style="margin-top:30px">' + (a.reason === 'expired' ? 'Approve this TV again' : 'Sign in with your phone') + '</h1>' +
        '<div class="code" id="code">' + new Array(7).join('<span>\u00b7</span>') + '</div><div class="steps">On your phone open <b>MojaLoss \u2192 Settings \u2192 Account \u2192 Approve a device</b> and enter this code.<br>This TV stays signed in after that.</div>' +
        '<div class="actions" style="margin-top:36px"><div class="btn focusable" id="newcode">New code</div></div><div class="muted" id="st" style="margin-top:20px;font-size:22px"></div></div>';
      var timer, alive = true, me = screen, st = function (t) { var e = document.getElementById('st'); if (e && screen === me) e.textContent = t; };
      function stop() { alive = false; clearTimeout(timer); }
      function start() {
        clearTimeout(timer);
        Api.qcInitiate().then(function (r) {
          if (!alive) return;
          document.getElementById('code').innerHTML = String(r.Code).split('').map(function (d) { return '<span>' + h(d) + '</span>'; }).join('');
          st('Waiting for approval\u2026');
          var started = Date.now();
          (function poll() {
            if (!alive || screen !== me) return;
            if (Date.now() - started > 5 * 60 * 1000) { st('Code expired. Choose New code.'); return; }
            Api.qcPoll(r.Secret).then(function (s) {
              if (!alive) return;
              if (s.Authenticated) { stop(); Api.qcAuthenticate(r.Secret).then(function () { stack = []; go('home', {}, true); toast('Signed in as ' + Api.userName()); }, function () { st('Sign-in failed. Choose New code.'); }); }
              else timer = setTimeout(poll, 5000);
            }, function (e) { st(e && e.offline ? 'Offline \u2014 retrying\u2026' : 'Waiting for approval\u2026'); timer = setTimeout(poll, 10000); });
          })();
        }, function (e) { st(e && e.offline ? 'You are offline. Check the network, then choose New code.' : 'Quick Connect is unavailable right now.'); });
      }
      document.getElementById('newcode').onclick = start;
      start(); Focus.first();
      screen.cleanup = stop;
    },

    home: function () {
      root.innerHTML = topbar('<div class="btn focusable" id="search">Search</div><div class="btn focusable" id="update-btn">Update · v' + h(curVer()) + '</div>') +
        '<div id="ota-banner" class="ota-banner glass" style="display:none"></div>' +
        '<div class="rows"><div class="rows-inner" id="rows"><div class="empty">Loading…</div></div></div>';
      document.getElementById('search').onclick = function () { go('search'); };
      document.getElementById('update-btn').onclick = openUpdateModal;
      refreshUpdateUi();
      var me = screen;
      Promise.all([Api.resume(), Api.latest('Movie'), Api.latest('Series'), Api.favorites().catch(function () { return { Items: [] }; })]).then(function (r) {
        if (screen !== me) return;
        var map = {}; r.forEach(function (x) { (x.Items || []).forEach(function (i) { if (!map[i.Id]) map[i.Id] = i; }); });
        var html = (r[0].Items || []).length ? row('Continue Watching', r[0].Items, true, 'cw') : '';
        html += '<section class="row" id="plat" style="display:none"><div class="track" id="chips"></div></section>'; // no heading, like Android
        html += row('Latest Movies', r[1].Items || [], false, 'lm') + row('Latest Series', r[2].Items || [], false, 'ls');
        if ((r[3].Items || []).length) html += row('My List', r[3].Items, false, 'fav');
        document.getElementById('rows').innerHTML = html; wireCards(map); artFocus(map);
        Focus.first('.card');
        loadPlatforms().then(function (ps) {
          if (screen !== me || !ps.length) return;
          var chips = document.getElementById('chips'), sec = document.getElementById('plat');
          sec.style.display = '';
          chips.innerHTML = ps.map(function (p, i) { return '<div class="chip focusable" data-i="' + i + '" data-slug="' + h(p.slug) + '" aria-label="Browse ' + h(p.name) + '">' + logoTile(p) + '<span class="cn">' + h(p.name) + '</span></div>'; }).join('');
          each(chips.querySelectorAll('.chip'), function (c) { c.onclick = function () { go('platform', { p: ps[+c.getAttribute('data-i')] }); }; });
        }, function () { /* platform row simply stays hidden on failure */ });
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
      function draw(it, eps) {
        if (screen !== me) return;
        art(Api.img(it, 'Backdrop', 1920));
        var pos = it.UserData && it.UserData.PlaybackPositionTicks, resume = pos > 0 && it.Type !== 'Series', fav = it.UserData && it.UserData.IsFavorite;
        root.innerHTML = topbar() + '<div class="rows"><div class="rows-inner"><div class="detail row"><h1>' + h(it.Type === 'Episode' ? it.SeriesName : it.Name) + '</h1><div class="meta">' +
          [it.ProductionYear, it.OfficialRating, it.RunTimeTicks && fmt(it.RunTimeTicks), (it.Genres || []).slice(0, 3).join(' \u00b7 ')].filter(Boolean).map(function (m) { return '<span>' + h(m) + '</span>'; }).join('') +
          '</div>' + (it.Type === 'Episode' ? '<h2 style="margin-top:24px">S' + it.ParentIndexNumber + ' E' + it.IndexNumber + ' \u00b7 ' + h(it.Name) + '</h2>' : '') +
          '<p>' + h(it.Overview || '') + '</p><div class="actions"><div class="btn primary focusable" id="play">' + (it.Type === 'Series' ? 'Play next episode' : resume ? 'Resume from ' + clock(pos / 1e7) : 'Play') + '</div>' +
          (resume ? '<div class="btn focusable" id="restart">From the start</div>' : '') + '<div class="btn focusable" id="fav">' + (fav ? '\u2713 In My List' : '+ My List') + '</div></div></div>' +
          (eps && eps.length ? row('Episodes', eps, true, 'eps') : '') + '</div></div>';
        document.getElementById('play').onclick = function () {
          if (it.Type !== 'Series') return play(it, resume ? pos : 0);
          Api.nextUp(it.Id).then(function (n) { var ep = (n.Items || [])[0] || (eps || [])[0]; if (ep) play(ep, (ep.UserData && ep.UserData.PlaybackPositionTicks) || 0); else toast('No episodes yet'); }, onErr);
        };
        var r = document.getElementById('restart'); if (r) r.onclick = function () { play(it, 0); };
        document.getElementById('fav').onclick = function () {
          Api.setFavorite(it.Id, !fav).then(function () { it.UserData = it.UserData || {}; it.UserData.IsFavorite = !fav; toast(!fav ? 'Added to My List' : 'Removed from My List'); a.focus = '#fav'; draw(it, eps); }, onErr);
        };
        if (eps) { var m = {}; eps.forEach(function (e) { m[e.Id] = e; }); wireCards(m, document.getElementById('eps'), function (ep) { play(ep, (ep.UserData && ep.UserData.PlaybackPositionTicks) || 0); }); }
        Focus.first(a.focus || '#play'); a.focus = null;
      }
      draw(a.item);
      Api.item(a.item.Id).then(function (full) {
        a.item = full; draw(full);
        if (full.Type === 'Series') Api.episodes(full.Id).then(function (e) { draw(full, e.Items || []); }, function () {});
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
        res.innerHTML = '<div class="empty">Searching\u2026</div>';
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
      el.innerHTML = '<video autoplay playsinline></video><div class="buffer" id="buf">Buffering\u2026</div><div class="osd glass" id="osd"><div style="font-size:32px;font-weight:600">' + h(it.SeriesName ? it.SeriesName + ' \u00b7 ' + it.Name : it.Name) + '</div><div class="bar"><b id="pb" style="width:0"></b></div><div class="row2"><span id="pt">0:00</span><span>OK pause \u00b7 \u25c0\u25b6 10s \u00b7 \u25b2 audio & subtitles \u00b7 Back exit</span><span id="pd">' + clock((it.RunTimeTicks || 0) / 1e7) + '</span></div></div><div class="menu glass" id="menu" style="display:none"></div>';
      document.body.appendChild(el);
      var v = el.querySelector('video'), osd = el.querySelector('#osd'), buf = el.querySelector('#buf'), menuEl = el.querySelector('#menu'), ptEl = el.querySelector('#pt'), pbEl = el.querySelector('#pb');
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
        if (retries >= 5) { buf.textContent = 'Playback stopped: connection lost. Press OK to retry.'; buf.style.display = 'block'; retries = 0; player.waitingRetry = true; return; }
        var at = pos(), wait = Math.min(30000, 2000 * Math.pow(2, retries++));
        buf.textContent = 'Connection problem \u2014 retrying in ' + Math.round(wait / 1000) + 's\u2026'; buf.style.display = 'block';
        setTimeout(function () { if (player && player.v === v) { startSec = at; load(at); } }, wait);
      }
      v.ontimeupdate = function () { var d = v.duration || (it.RunTimeTicks || 0) / 1e7; ptEl.textContent = clock(v.currentTime); if (d && isFinite(d)) pbEl.style.width = (100 * v.currentTime / d) + '%'; };
      v.onplaying = function () { buf.style.display = 'none'; retries = 0; clearTimeout(stallT); Api.reportProgress('', base()); };
      v.onwaiting = function () { buf.textContent = 'Buffering\u2026'; buf.style.display = 'block'; clearTimeout(stallT); stallT = setTimeout(recover, 20000); };
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

  window.MLTV = { go: go, play: play, openUpdateModal: openUpdateModal, closeUpdateModal: closeUpdateModal, registeredKeys: registered, player: function () { return player; }, screen: function () { return screen && screen.name; } }; // test hooks
  if (window.__mltvBootOk) window.__mltvBootOk();
  go(Api.signedIn() ? 'home' : 'signin');
  if (window.MLTV_OTA) setTimeout(function () { MLTV_OTA.checkForUpdates(true); }, 600);
})();
