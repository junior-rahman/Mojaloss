/* MojaLoss TV — screens: signin, home, platform, detail, search, player. */
(function () {
  var root = document.getElementById('root'), bd = document.getElementById('backdrop');
  var stack = [], screen = null, player = null, keyHandler = null;
  var reduced = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;

  try { ['MediaPlay','MediaPause','MediaPlayPause','MediaFastForward','MediaRewind','MediaStop'].forEach(function (k) { tizen.tvinputdevice.registerKey(k); }); } catch (e) {}

  function h(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return '&#' + c.charCodeAt(0) + ';'; }); }
  function toast(t) { var e = document.getElementById('toast'); e.textContent = t; e.classList.add('show'); clearTimeout(toast.t); toast.t = setTimeout(function () { e.classList.remove('show'); }, 3000); }
  function art(url) { if (url) { bd.style.setProperty('--art', 'url("' + url + '")'); bd.classList.add('art'); } else bd.classList.remove('art'); }
  function fmt(ticks) { var s = Math.floor((ticks || 0) / 1e7), m = Math.floor(s / 60); return m >= 60 ? Math.floor(m / 60) + 'h ' + (m % 60) + 'm' : m + 'm'; }
  function clock(sec) { sec = Math.max(0, Math.floor(sec)); var m = Math.floor(sec / 60), s = sec % 60, hh = Math.floor(m / 60); return (hh ? hh + ':' + ('0' + (m % 60)).slice(-2) : m) + ':' + ('0' + s).slice(-2); }
  function onErr(e) { if (e && e.status === 401) { Api.forgetToken(); toast('This TV was signed out by the server. Approve it again from your phone.'); go('signin', {}, true); } else toast('Could not reach MojaLoss. Check the connection.'); }

  function go(name, args, replace) { if (screen && !replace) stack.push(screen); screen = { name: name, args: args || {} }; render(); }
  function back() { if (player) return stopPlayer(); if (stack.length) { screen = stack.pop(); render(); } else { try { tizen.application.getCurrentApplication().exit(); } catch (e) {} } }
  function render() { keyHandler = null; art(''); Screens[screen.name](screen.args); }

  function card(it, wide) {
    var pct = it.UserData && it.UserData.PlayedPercentage;
    var img = wide ? (Api.img(it, 'Backdrop', 420) || Api.img(it, 'Primary', 420)) : Api.img(it, 'Primary', 240);
    var title = it.Type === 'Episode' ? (it.SeriesName + ' · S' + (it.ParentIndexNumber || 0) + 'E' + (it.IndexNumber || 0)) : it.Name;
    return '<div class="card focusable' + (wide ? ' wide' : '') + '" data-id="' + h(it.Id) + '"><div class="art" style="background-image:url(\'' + h(img) + '\')"></div>' +
      (pct ? '<div class="prog"><b style="width:' + Math.round(pct) + '%"></b></div>' : '') + '<div class="t">' + h(title) + '</div></div>';
  }
  function row(title, items, wide, extra) {
    return '<section class="row"><h2>' + h(title) + '</h2>' + (extra || '') + '<div class="track">' + (items.length ? items.map(function (i) { return card(i, wide); }).join('') : '<div class="empty">Nothing here yet</div>') + '</div></section>';
  }
  function wireCards(map) {
    root.querySelectorAll('.card').forEach(function (c) { c.onclick = function () { var it = map[c.dataset.id]; if (it) go('detail', { item: it }); }; });
  }
  function topbar(extra) { return '<div class="top"><div class="brand"><i></i>MojaLoss</div><div class="actions">' + (extra || '') + '</div></div>'; }
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
        }).catch(function () { return { id: p.id, name: p.name, items: [] }; });
      })).then(function (ps) { platformCache = ps.filter(function (p) { return p.items.length > 0; }); return platformCache; }); // hide empty platforms
    });
  }

  var Screens = {
    signin: function () {
      root.innerHTML = '<div class="signin glass"><div class="brand"><i></i>MojaLoss</div><h1 style="margin-top:30px">Sign in with your phone</h1>' +
        '<div class="code" id="code">' + '<span>·</span>'.repeat(6) + '</div><div class="steps">On your phone open <b>MojaLoss → Settings → Account → Approve a device</b> and enter this code.<br>This TV stays signed in after that.</div>' +
        '<div class="actions" style="margin-top:36px"><div class="btn focusable" id="newcode">New code</div></div><div class="muted" id="st" style="margin-top:20px;font-size:22px"></div></div>';
      var timer, alive = true, me = screen;
      function stop() { alive = false; clearTimeout(timer); }
      function start() {
        Api.qcInitiate().then(function (r) {
          if (!alive) return;
          document.getElementById('code').innerHTML = String(r.Code).split('').map(function (d) { return '<span>' + h(d) + '</span>'; }).join('');
          document.getElementById('st').textContent = 'Waiting for approval…';
          var started = Date.now();
          (function poll() {
            if (!alive || screen !== me) return;
            if (Date.now() - started > 5 * 60 * 1000) { document.getElementById('st').textContent = 'Code expired. Choose New code.'; return; }
            Api.qcPoll(r.Secret).then(function (s) {
              if (!alive) return;
              if (s.Authenticated) { stop(); Api.qcAuthenticate(r.Secret).then(function () { stack = []; go('home', {}, true); toast('Signed in as ' + Api.userName()); }, onErr); }
              else timer = setTimeout(poll, 5000);
            }, function () { timer = setTimeout(poll, 10000); });
          })();
        }, function () { document.getElementById('st').textContent = 'Quick Connect is unavailable right now.'; });
      }
      document.getElementById('newcode').onclick = function () { clearTimeout(timer); start(); };
      start(); Focus.first();
      screen.cleanup = stop;
    },

    home: function () {
      root.innerHTML = topbar('<div class="btn focusable" id="search">Search</div>') + '<div class="rows"><div class="rows-inner" id="rows"><div class="empty">Loading…</div></div></div>';
      document.getElementById('search').onclick = function () { go('search'); };
      var me = screen;
      Promise.all([Api.resume(), Api.latest('Movie'), Api.latest('Series')]).then(function (r) {
        if (screen !== me) return;
        var map = {}; [r[0], r[1], r[2]].forEach(function (x) { (x.Items || []).forEach(function (i) { if (!map[i.Id]) map[i.Id] = i; }); });
        var html = (r[0].Items || []).length ? row('Continue Watching', r[0].Items, true) : '';
        html += '<section class="row" id="plat"><div class="track" id="chips"></div></section>';
        html += row('Latest Movies', r[1].Items || []) + row('Latest Series', r[2].Items || []);
        document.getElementById('rows').innerHTML = html; wireCards(map);
        if (!(r[0].Items || []).length) document.getElementById('plat').style.display = 'none';
        Focus.first('.card') ;
        Focus.onFocus = function (e) { var it = map[e.dataset.id]; if (it) art(Api.img(it, 'Backdrop', 1920)); };
        loadPlatforms().then(function (ps) {
          if (screen !== me) return;
          var chips = document.getElementById('chips'), sec = document.getElementById('plat');
          if (!ps.length) { sec.style.display = 'none'; return; }
          sec.style.display = '';
          chips.innerHTML = ps.map(function (p, i) { return '<div class="chip focusable" data-i="' + i + '">' + h(p.name) + '<small>' + p.items.length + '</small></div>'; }).join('');
          chips.querySelectorAll('.chip').forEach(function (c) { c.onclick = function () { go('platform', { p: ps[+c.dataset.i] }); }; });
        }, function () { var s = document.getElementById('plat'); if (s) s.style.display = 'none'; });
      }, onErr);
    },

    platform: function (a) {
      var kind = a.kind || 'all', sort = a.sort || 'latest', p = a.p;
      var items = p.items.filter(function (i) { return kind === 'all' || (kind === 'movie' ? i.Type === 'Movie' : i.Type === 'Series'); });
      items.sort(sort === 'latest' ? function (x, y) { return String(y.PremiereDate || y.ProductionYear || '').localeCompare(String(x.PremiereDate || x.ProductionYear || '')); } : function (x, y) { return x.Name.localeCompare(y.Name); });
      var pill = function (id, label, on) { return '<div class="pill focusable' + (on ? ' on' : '') + '" data-f="' + id + '">' + label + '</div>'; };
      root.innerHTML = topbar() + '<h1>' + h(p.name) + '</h1><div class="filters" style="margin:26px 0">' +
        pill('all', 'All', kind === 'all') + pill('movie', 'Movies', kind === 'movie') + pill('series', 'Series', kind === 'series') + '<span style="width:40px"></span>' +
        pill('latest', 'Latest release', sort === 'latest') + pill('az', 'A–Z', sort === 'az') + '</div><div class="rows"><div class="rows-inner">' + row(items.length + ' titles', items) + '</div></div>';
      var map = {}; items.forEach(function (i) { map[i.Id] = i; }); wireCards(map);
      root.querySelectorAll('.pill').forEach(function (e) { e.onclick = function () { var f = e.dataset.f; if (f === 'latest' || f === 'az') a.sort = f; else a.kind = f; a.focus = f; render(); }; });
      Focus.onFocus = function (e) { var it = map[e.dataset.id]; if (it) art(Api.img(it, 'Backdrop', 1920)); };
      Focus.first(a.focus ? '.pill[data-f="' + a.focus + '"]' : '.card');
    },

    detail: function (a) {
      var it = a.item, me = screen;
      function draw(it) {
        art(Api.img(it, 'Backdrop', 1920));
        var resume = it.UserData && it.UserData.PlaybackPositionTicks > 0;
        root.innerHTML = topbar() + '<div class="detail"><h1>' + h(it.Type === 'Episode' ? it.SeriesName : it.Name) + '</h1><div class="meta">' +
          [it.ProductionYear, it.OfficialRating, it.RunTimeTicks && fmt(it.RunTimeTicks), (it.Genres || []).slice(0, 3).join(' · ')].filter(Boolean).map(function (m) { return '<span>' + h(m) + '</span>'; }).join('') +
          '</div>' + (it.Type === 'Episode' ? '<h2 style="margin-top:24px">S' + it.ParentIndexNumber + ' E' + it.IndexNumber + ' · ' + h(it.Name) + '</h2>' : '') +
          '<p>' + h(it.Overview || '') + '</p><div class="actions"><div class="btn primary focusable" id="play">' + (it.Type === 'Series' ? 'Play next episode' : resume ? 'Resume ' + clock(it.UserData.PlaybackPositionTicks / 1e7) : 'Play') + '</div>' +
          (resume ? '<div class="btn focusable" id="restart">From the start</div>' : '') + '</div></div>';
        document.getElementById('play').onclick = function () {
          if (it.Type !== 'Series') return play(it, resume ? it.UserData.PlaybackPositionTicks : 0);
          Api.nextUp(it.Id).then(function (n) { return (n.Items || []).length ? n : Api.episodes(it.Id); }).then(function (n) { var ep = (n.Items || [])[0]; if (ep) play(ep, (ep.UserData && ep.UserData.PlaybackPositionTicks) || 0); else toast('No episodes yet'); }, onErr);
        };
        var r = document.getElementById('restart'); if (r) r.onclick = function () { play(it, 0); };
        Focus.first('#play');
      }
      draw(it);
      Api.item(it.Id).then(function (full) { if (screen === me) draw(full); }, function () {});
    },

    search: function (a) {
      var term = a.term || '';
      var keys = 'abcdefghijklmnopqrstuvwxyz0123456789'.split('');
      root.innerHTML = topbar() + '<div class="searchbox glass" id="sb">' + (term ? h(term) : '<span class="muted">Search movies and series</span>') + '</div><div class="keys">' +
        keys.map(function (k) { return '<div class="key focusable" data-k="' + k + '">' + k.toUpperCase() + '</div>'; }).join('') +
        '<div class="key w2 focusable" data-k=" ">Space</div><div class="key w2 focusable" data-k="del">Delete</div></div><div class="rows" style="height:520px"><div class="rows-inner" id="res"></div></div>';
      var t, me = screen;
      function run() {
        var res = document.getElementById('res');
        if (term.trim().length < 2) { res.innerHTML = ''; return; }
        Api.search(term.trim()).then(function (r) { if (screen !== me) return; var map = {}; (r.Items || []).forEach(function (i) { map[i.Id] = i; }); res.innerHTML = row('Results', r.Items || []); wireCards(map); }, onErr);
      }
      root.querySelectorAll('.key').forEach(function (k) { k.onclick = function () { term = k.dataset.k === 'del' ? term.slice(0, -1) : term + k.dataset.k; a.term = term; document.getElementById('sb').innerHTML = term ? h(term) : '<span class="muted">Search movies and series</span>'; clearTimeout(t); t = setTimeout(run, 450); }; });
      Focus.first('.key'); if (term) run();
    }
  };

  /* Player: HTML5 video (Tizen 3+ plays HLS natively); AVPlay could be swapped in for 4K/HDR later. */
  function play(it, startTicks) {
    Api.playbackInfo(it.Id).then(function (info) {
      var url = Api.streamUrl(it, info), sessionId = info.PlaySessionId;
      var el = document.createElement('div'); el.id = 'player';
      el.innerHTML = '<video autoplay playsinline></video><div class="osd glass" id="osd"><div style="font-size:32px;font-weight:600">' + h(it.SeriesName ? it.SeriesName + ' · ' + it.Name : it.Name) + '</div><div class="bar"><b id="pb" style="width:0"></b></div><div class="row2"><span id="pt">0:00</span><span>OK pause · ◀▶ 10s · Back to exit</span><span id="pd">' + clock((it.RunTimeTicks || 0) / 1e7) + '</span></div></div>';
      document.body.appendChild(el);
      var v = el.querySelector('video'), osd = el.querySelector('#osd'), hideT, tick;
      v.src = url; if (startTicks) v.currentTime = startTicks / 1e7;
      function base() { return { ItemId: it.Id, PlaySessionId: sessionId, MediaSourceId: info.MediaSources[0].Id, PositionTicks: Math.floor(v.currentTime * 1e7), IsPaused: v.paused, PlayMethod: info.MediaSources[0].TranscodingUrl ? 'Transcode' : 'DirectPlay' }; }
      function show() { osd.classList.remove('hide'); clearTimeout(hideT); hideT = setTimeout(function () { if (!v.paused) osd.classList.add('hide'); }, 4000); }
      v.ontimeupdate = function () { var d = v.duration || (it.RunTimeTicks || 0) / 1e7; document.getElementById('pt').textContent = clock(v.currentTime); if (d) document.getElementById('pb').style.width = (100 * v.currentTime / d) + '%'; };
      v.onplaying = function () { Api.reportProgress('', base()); };
      v.onerror = function () { toast('This video could not play on the TV.'); };
      tick = setInterval(function () { Api.reportProgress('Progress', base()); }, 10000);
      player = { el: el, v: v, stop: function () { clearInterval(tick); Api.reportProgress('Stopped', base()); v.removeAttribute('src'); v.load(); el.remove(); } };
      keyHandler = function (k) {
        show();
        if (k === 13 || k === 10252 || k === 415 || k === 19) { if (v.paused) v.play(); else v.pause(); }
        else if (k === 39 || k === 417) v.currentTime += 10; else if (k === 37 || k === 412) v.currentTime = Math.max(0, v.currentTime - 10);
        else if (k === 413) stopPlayer();
        return true;
      };
      show();
    }, onErr);
  }
  function stopPlayer() { if (player) { player.stop(); player = null; keyHandler = null; } }

  document.addEventListener('keydown', function (e) {
    var k = e.keyCode;
    if (k === 10009 || k === 8 || k === 27) { e.preventDefault(); if (screen && screen.cleanup) screen.cleanup(); return back(); }
    if (player && keyHandler && keyHandler(k)) { e.preventDefault(); return; }
    var map = { 37: 'L', 38: 'U', 39: 'R', 40: 'D' };
    if (map[k]) { e.preventDefault(); Focus.move(map[k]); }
    else if (k === 13) { var c = Focus.current(); if (c && c.onclick) c.onclick(); }
  });
  document.addEventListener('mouseover', function (e) { var f = e.target.closest && e.target.closest('.focusable'); if (f) Focus.set(f); });

  window.MLTV = { go: go, play: play }; // test hooks
  var start = new URLSearchParams(location.search).get('screen');
  go(Api.signedIn() ? 'home' : 'signin');
  if (start && window.MLTV_TEST) window.MLTV_TEST(start);
})();
