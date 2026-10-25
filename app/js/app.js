/* MojaLoss TV — screens: signin, home, platform, detail, search, player. ES5 only (Tizen 3+). */
(function () {
  var root = document.getElementById('root'), bd = document.getElementById('backdrop');
  var stack = [], screen = null, player = null, lastBack = 0;
  function each(list, fn) { Array.prototype.forEach.call(list, fn); }
  function param(k) { var m = new RegExp('[?&]' + k + '=([^&]+)').exec(location.search); return m ? decodeURIComponent(m[1]) : null; }
  function fitViewport() {
    if (document.getElementById('mltv-ota-css')) {
      var baseCss = document.getElementById('mltv-base-css') || document.querySelector('link[href*="app.css"]');
      if (baseCss) baseCss.disabled = true;
    }
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
  var bdCurUrl = '', bdTimer = null, bdLayerIdx = 0;
  var bgTrailerTimer = null, bgTrailerStopTimer = null, bgTrailerUnloadTimer = null, bgTrailerVolTimer = null, bgTrailerItem = null, bgTrailerSeq = 0;
  var activeSpotlightTrack = null, spotlightRestoreTimer = null;
  function ensureBdLayers() {
    if (!bd.querySelector('.bd-layer')) {
      bd.innerHTML = '<div class="bd-layer active"></div><div class="bd-layer"></div>' +
        '<video id="bg-trailer-video" muted playsinline preload="none"></video>' +
        '<div id="bg-trailer-shade"></div>';
    }
  }
  function enterTrailerSpotlight() {
    var fEl = Focus.current() || document.querySelector('.focusable.focused');
    var fCard = (fEl && fEl.classList && (fEl.classList.contains('card') || fEl.classList.contains('person-card'))) ? fEl : null;
    document.body.classList.add('trailer-spotlight');
    if (fCard) {
      document.body.classList.add('has-spotlight-card');
      fCard.classList.add('spotlight-card');
      var fRow = fCard.closest ? (fCard.closest('section.row') || fCard.closest('.row')) : null;
      if (fRow) fRow.classList.add('spotlight-row');
      var fTrack = fCard.closest ? fCard.closest('.track') : null;
      if (fTrack) {
        if (spotlightRestoreTimer) { clearTimeout(spotlightRestoreTimer); spotlightRestoreTimer = null; }
        fTrack.classList.remove('spotlight-restoring');
        fTrack._preSpotlightTx = fTrack._tx || fTrack.style.transform || 'translate3d(0px,0,0)';
        fTrack.classList.add('spotlight-track');
        var targetShift = Math.max(0, (fCard.offsetLeft || 0) - 64);
        fTrack.style.transform = 'translate3d(' + (-targetShift) + 'px,0,0)';
        activeSpotlightTrack = fTrack;
      }
    } else {
      document.body.classList.remove('has-spotlight-card');
    }
  }
  function exitTrailerSpotlight() {
    var wasSpotlight = document.body.classList.contains('trailer-spotlight');
    document.body.classList.remove('trailer-spotlight');
    document.body.classList.remove('has-spotlight-card');
    each(document.querySelectorAll('.spotlight-card'), function (el) { el.classList.remove('spotlight-card'); });
    each(document.querySelectorAll('.spotlight-row'), function (el) { el.classList.remove('spotlight-row'); });
    if (activeSpotlightTrack) {
      var trk = activeSpotlightTrack;
      activeSpotlightTrack = null;
      trk.classList.remove('spotlight-track');
      if (wasSpotlight) {
        trk.classList.add('spotlight-restoring');
        if (trk._preSpotlightTx != null) {
          trk.style.transform = trk._preSpotlightTx;
          trk._tx = trk._preSpotlightTx;
        }
        if (spotlightRestoreTimer) clearTimeout(spotlightRestoreTimer);
        spotlightRestoreTimer = setTimeout(function () {
          trk.classList.remove('spotlight-restoring');
          spotlightRestoreTimer = null;
        }, 720);
      }
    }
  }
  function stopBgPreview(immediate) {
    if (bgTrailerTimer) { clearTimeout(bgTrailerTimer); bgTrailerTimer = null; }
    if (bgTrailerStopTimer) { clearTimeout(bgTrailerStopTimer); bgTrailerStopTimer = null; }
    if (bgTrailerVolTimer) { clearInterval(bgTrailerVolTimer); bgTrailerVolTimer = null; }
    bgTrailerSeq++;
    bgTrailerItem = null;
    exitTrailerSpotlight();
    if (!bd) return;
    var wasPlaying = bd.classList.contains('trailer-playing');
    bd.classList.remove('trailer-playing');
    var v = document.getElementById('bg-trailer-video');
    if (v) {
      v.onplaying = v.onended = v.onerror = v.onloadedmetadata = v.onseeked = v.ontimeupdate = null;
      try { v.pause(); } catch (e) {}
      var doUnload = function () {
        bgTrailerUnloadTimer = null;
        if (bd && bd.classList.contains('trailer-playing')) return;
        if (v.getAttribute('src')) {
          v.removeAttribute('src');
          try { v.load(); } catch (e2) {}
        }
      };
      if (bgTrailerUnloadTimer) { clearTimeout(bgTrailerUnloadTimer); bgTrailerUnloadTimer = null; }
      if (immediate || !wasPlaying || param('mock')) {
        doUnload();
      } else {
        bgTrailerUnloadTimer = setTimeout(doUnload, 650);
      }
    }
  }
  function resolveBgPreviewTarget(it) {
    if (!it || !it.Id || it._requestable || it._isCollection || String(it.Id).indexOf('req:') === 0 || String(it.Id).indexOf('col:') === 0) {
      return Promise.resolve(null);
    }
    return Api.localTrailers(it.Id).then(function (trailers) {
      if (trailers && trailers.length && trailers[0] && trailers[0].Id) {
        return { playItem: trailers[0], startSec: 0, isTrailer: true };
      }
      return Api.item(it.Id).catch(function () { return it; }).then(function (full) {
        var rt = (full && full.RemoteTrailers) || it.RemoteTrailers || [];
        for (var i = 0; i < rt.length; i++) {
          var u = rt[i] && rt[i].Url ? String(rt[i].Url) : '';
          if (/\.(mp4|m4v|m3u8)(\?|$)/i.test(u)) {
            return { directUrl: u, startSec: 0, isTrailer: true };
          }
        }
        if (it.Type === 'Series') {
          return Api.nextUp(it.Id, 1).catch(function () { return { Items: [] }; }).then(function (nu) {
            var ep = (nu && nu.Items && nu.Items[0]) || null;
            if (ep && ep.Id) return { playItem: ep, startSec: param('mock') ? 2 : 180, isTrailer: false };
            return Api.episodes(it.Id).catch(function () { return { Items: [] }; }).then(function (eps) {
              var ep0 = (eps && eps.Items && eps.Items[0]) || null;
              return (ep0 && ep0.Id) ? { playItem: ep0, startSec: param('mock') ? 2 : 180, isTrailer: false } : null;
            });
          });
        }
        var durSec = ((full && full.RunTimeTicks) || it.RunTimeTicks || 0) / 1e7;
        var clipStart = param('mock') ? 2 : (durSec > 1200 ? 600 : (durSec > 300 ? 120 : 10));
        return { playItem: full || it, startSec: clipStart, isTrailer: false };
      });
    });
  }
  function startBgPreviewNow(it, mySeq) {
    if (player || !screen || (screen.name !== 'home' && screen.name !== 'platform' && screen.name !== 'detail')) return;
    ensureBdLayers();
    if (bgTrailerUnloadTimer) { clearTimeout(bgTrailerUnloadTimer); bgTrailerUnloadTimer = null; }
    var v = document.getElementById('bg-trailer-video');
    if (!v) return;
    resolveBgPreviewTarget(it).then(function (target) {
      if (!target || mySeq !== bgTrailerSeq || player) return;
      if (target.directUrl) {
        return { url: target.directUrl, startSec: target.startSec || 0, isTrailer: !!target.isTrailer };
      }
      var pItem = target.playItem;
      if (!pItem || !pItem.Id) return null;
      var sTicks = Math.floor((target.startSec || 0) * 1e7);
      return Api.playbackInfo(pItem.Id, { startTicks: sTicks, maxBitrate: 8000000 }).then(function (info) {
        if (!info || !info.MediaSources || !info.MediaSources[0]) return null;
        var mSrc = info.MediaSources[0];
        if (param('mock') && mSrc.TranscodingUrl && mSrc.TranscodingUrl.indexOf('/videos/mock/') === 0) {
          return { url: Api.jf() + '/Videos/' + pItem.Id + '/stream?static=true&mediaSourceId=' + mSrc.Id + '&api_key=mock-token', startSec: target.startSec || 0, isTranscode: false, isTrailer: !!target.isTrailer };
        }
        return { url: Api.streamUrl(pItem, info), startSec: target.startSec || 0, isTranscode: !!mSrc.TranscodingUrl, isTrailer: !!target.isTrailer };
      });
    }).then(function (stream) {
      if (!stream || !stream.url || mySeq !== bgTrailerSeq || player) return;
      var revealed = false, needSeek = false, seekDone = false, targetStart = 0;
      v.muted = false;
      v.volume = 0.02;
      function checkReadyToReveal() {
        if (revealed || mySeq !== bgTrailerSeq || player) return;
        if (needSeek && !seekDone) return;
        if (v.readyState < 3 || v.paused) return;
        var minAdvance = param('mock') ? 0.01 : 0.12;
        if (v.currentTime < targetStart + minAdvance) return;
        revealed = true;
        var doReveal = function () {
          if (mySeq !== bgTrailerSeq || player) return;
          bd.classList.add('trailer-playing');
          enterTrailerSpotlight();
          if (!v.muted) {
            var vol = 0.04;
            if (bgTrailerVolTimer) clearInterval(bgTrailerVolTimer);
            bgTrailerVolTimer = setInterval(function () {
              if (mySeq !== bgTrailerSeq || !bd.classList.contains('trailer-playing') || v.muted) {
                clearInterval(bgTrailerVolTimer);
                bgTrailerVolTimer = null;
                return;
              }
              vol = Math.min(0.65, vol + 0.08);
              try { v.volume = vol; } catch (e) {}
              if (vol >= 0.65) { clearInterval(bgTrailerVolTimer); bgTrailerVolTimer = null; }
            }, 80);
          }
          if (bgTrailerStopTimer) clearTimeout(bgTrailerStopTimer);
          var maxPlayMs = stream.isTrailer ? 140000 : 38000;
          bgTrailerStopTimer = setTimeout(function () {
            if (mySeq === bgTrailerSeq) stopBgPreview(false);
          }, maxPlayMs);
        };
        if (window.requestAnimationFrame) window.requestAnimationFrame(doReveal);
        else doReveal();
      }
      v.onloadedmetadata = function () {
        if (mySeq !== bgTrailerSeq || player) return;
        if (stream.startSec > 0 && !stream.isTranscode) {
          var d = v.duration;
          if (!d || !isFinite(d) || stream.startSec < d - 5) {
            targetStart = stream.startSec;
            needSeek = true;
            seekDone = false;
            try { v.currentTime = stream.startSec; } catch (e) { needSeek = false; }
          }
        }
        var pr = v.play();
        if (pr && pr.catch) {
          pr.catch(function () {
            if (mySeq !== bgTrailerSeq || player) return;
            v.muted = true;
            var pr2 = v.play();
            if (pr2 && pr2.catch) pr2.catch(function () {});
          });
        }
      };
      v.onseeked = function () {
        if (mySeq !== bgTrailerSeq || player) return;
        seekDone = true;
        checkReadyToReveal();
      };
      v.onplaying = checkReadyToReveal;
      v.ontimeupdate = checkReadyToReveal;
      v.onended = v.onerror = function () {
        if (mySeq === bgTrailerSeq) stopBgPreview(false);
      };
      v.src = stream.url;
      try { v.load(); } catch (e) {}
    }, function () {});
  }
  function scheduleBgPreview(it) {
    stopBgPreview(false);
    if (player || !it || !it.Id || it._requestable || it._isCollection || String(it.Id).indexOf('req:') === 0 || String(it.Id).indexOf('col:') === 0) return;
    if (!screen || (screen.name !== 'home' && screen.name !== 'platform' && screen.name !== 'detail')) return;
    bgTrailerItem = it;
    var mySeq = ++bgTrailerSeq;
    var delayMs = window.__mltvTrailerDelayMs || 3000;
    bgTrailerTimer = setTimeout(function () {
      bgTrailerTimer = null;
      if (mySeq === bgTrailerSeq && !player) startBgPreviewNow(it, mySeq);
    }, delayMs);
  }
  function applyBdUrl(url) {
    ensureBdLayers();
    var cssBg = url ? ('url("' + url + '")') : '';
    bd.style.backgroundImage = cssBg;
    var layers = bd.querySelectorAll('.bd-layer');
    if (layers && layers.length >= 2) {
      var nextIdx = 1 - bdLayerIdx;
      var nextLayer = layers[nextIdx], prevLayer = layers[bdLayerIdx];
      nextLayer.style.backgroundImage = cssBg || 'none';
      nextLayer.classList.add('active');
      prevLayer.classList.remove('active');
      bdLayerIdx = nextIdx;
    }
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
      }, 140);
    }
  }
  function fmt(ticks) { var s = Math.floor((ticks || 0) / 1e7), m = Math.floor(s / 60); return m >= 60 ? Math.floor(m / 60) + 'h ' + (m % 60) + 'm' : m + 'm'; }
  function clock(sec) { sec = Math.max(0, Math.floor(sec || 0)); var m = Math.floor(sec / 60), s = sec % 60, hh = Math.floor(m / 60); return (hh ? hh + ':' + ('0' + (m % 60)).slice(-2) : m) + ':' + ('0' + s).slice(-2); }
  function exitApp() { try { tizen.application.getCurrentApplication().exit(); } catch (e) { window.MLTV_EXITED = true; } }

  /* Errors: 401 = server revoked this TV's token (only then do we ask for a new approval); anything else = offline/unreachable state with Retry. */
  var homeDataCache = null;
  function onErr(e) {
    if (e && e.status === 401) { homeDataCache = null; Api.forgetToken(); stack = []; go('signin', { reason: 'expired' }, true); return; }
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
    if (stack.length) { screen = stack.pop(); screen.args = screen.args || {}; screen.args.fromBack = true; render(); return; }
    // root screen: double-press Back to leave the app
    if (Date.now() - lastBack < 2500) return exitApp();
    lastBack = Date.now(); toast('Press Back again to exit MojaLoss');
  }
  function render() {
    stopBgPreview(); Focus.onFocus = null; art(''); screen.cleanup = null;
    root.classList.add('screen-enter');
    Screens[screen.name](screen.args);
  }

  function logoTile(p, big) {
    var L = PlatformLogos[p.slug], s = big ? 108 : 40;
    if (L) return '<span class="logo" style="width:' + s + 'px;height:' + s + 'px;background:' + L.brand + '"><svg viewBox="0 0 24 24" width="' + (s * .56) + '" height="' + (s * .56) + '"><path fill="#fff" d="' + L.path + '"/></svg></span>';
    var mono = String(p.name).replace(/[^A-Za-z0-9 ]/g, '').split(' ').filter(Boolean).slice(0, 2).map(function (w) { return w[0]; }).join('').toUpperCase();
    return '<span class="logo mono" style="width:' + s + 'px;height:' + s + 'px">' + h(mono) + '</span>';
  }
  var REQUESTS_KEY = 'mltv.requests.v1';
  var requestedMap = {};
  try {
    var rawReqs = localStorage.getItem(REQUESTS_KEY);
    if (rawReqs) requestedMap = JSON.parse(rawReqs) || {};
  } catch (e) { requestedMap = {}; }
  function isTitleRequested(id) {
    return !!(id && requestedMap[id]);
  }
  function requestTitle(it, onDone) {
    if (!it || !it.Id) return;
    requestedMap[it.Id] = {
      id: it.Id,
      name: it.Name,
      year: it.ProductionYear || null,
      type: it.Type || 'Movie',
      platform: it._platformName || '',
      requestedAt: Date.now()
    };
    try { localStorage.setItem(REQUESTS_KEY, JSON.stringify(requestedMap)); } catch (e) {}
    Api.submitRequest(it);
    each(document.querySelectorAll('.card[data-id="' + it.Id + '"] .req-badge'), function (b) {
      b.textContent = '\u2713 Requested';
      b.classList.add('done');
    });
    toast('Requested \u201c' + (it.Name || 'Title') + '\u201d for MojaLoss server');
    if (onDone) onDone();
  }
  var sectionRegistry = {}, rowAutoSeq = 0;
  var POSTER_MAP_KEY = 'mltv.posterMap.v1';
  var posterCacheMap = {};
  try {
    var rawPm = localStorage.getItem(POSTER_MAP_KEY);
    if (rawPm) posterCacheMap = JSON.parse(rawPm) || {};
  } catch (e) { posterCacheMap = {}; }

  function releaseSortKey(x) {
    if (!x) return '0000-00-00';
    var yr = x.ProductionYear ? String(x.ProductionYear) : (x.PremiereDate ? String(x.PremiereDate).slice(0, 4) : '0000');
    var pd = x.PremiereDate ? String(x.PremiereDate).slice(0, 10) : (yr + '-00-00');
    if (pd.slice(0, 4) !== yr && yr !== '0000') pd = yr + pd.slice(4);
    return yr + '|' + pd + '|' + String(x._wpDate || '');
  }
  function sortByReleaseDateDesc(list) {
    return (list || []).slice().sort(function (a, b) {
      if (!!a._requestable !== !!b._requestable) return a._requestable ? 1 : -1;
      var ka = releaseSortKey(a), kb = releaseSortKey(b);
      if (ka !== kb) return kb.localeCompare(ka);
      return String(a.Name || '').localeCompare(String(b.Name || ''));
    });
  }
  function sortByUploadDateDesc(list) {
    return (list || []).slice().sort(function (a, b) {
      var da = String(a.DateCreated || a.PremiereDate || a.ProductionYear || '');
      var db = String(b.DateCreated || b.PremiereDate || b.ProductionYear || '');
      if (da !== db) return db.localeCompare(da);
      return String(a.Name || '').localeCompare(String(b.Name || ''));
    });
  }

  function posterFallbackHtml(it, title) {
    var typeLbl = (it._platformName ? it._platformName + ' \u00b7 ' : '') + (it.Type === 'Episode' ? 'Episode' : (it.Type === 'BoxSet' ? 'Collection' : (it.Type || 'Movie')));
    var metaLbl = typeLbl + (it.ProductionYear ? ' \u00b7 ' + it.ProductionYear : '');
    return '<div class="poster-fallback" data-fb-meta="' + h(metaLbl) + '" data-fb-title="' + h(title || it.Name || 'Untitled') + '"></div>';
  }
  function resolveCardImage(it, wide) {
    var img = wide ? (Api.img(it, 'Backdrop', 420) || Api.img(it, 'Primary', 420)) : (Api.img(it, 'Primary', 260) || Api.img(it, 'Backdrop', 360));
    if (!img && it && it.Name) {
      var pk = String(it.Name).toLowerCase().replace(/[^a-z0-9]+/g, '') + ':' + (it.ProductionYear || '');
      if (posterCacheMap[pk]) img = posterCacheMap[pk];
    }
    if (img && img.indexOf('/wp-content/') === 0) img = 'https://www.mojaloss.stream' + img;
    return img || '';
  }
  function card(it, wide, extraAttr) {
    var pct = it.UserData && it.UserData.PlayedPercentage;
    var img = resolveCardImage(it, wide);
    var title = it.Type === 'Episode' ? (it.SeriesName + ' \u00b7 S' + (it.ParentIndexNumber || 0) + 'E' + (it.IndexNumber || 0)) : it.Name;
    var isReq = !!it._requestable;
    var reqDone = isReq && isTitleRequested(it.Id);
    var reqBadge = isReq ? ('<span class="req-badge' + (reqDone ? ' done' : '') + '">' + (reqDone ? '\u2713 Requested' : 'Request') + '</span>') : '';
    var colBadge = it._collectionCount ? ('<span class="on-server-badge">' + it._collectionCount + ' Titles</span>') : '';
    var artStyle = img ? ('background-image:url(\'' + h(img) + '\')') : '';
    var imgTag = img ? ('<img class="card-img" src="' + h(img) + '" alt="" onerror="this.style.display=\'none\';this.parentNode.style.backgroundImage=\'none\'">') : '';
    return '<div class="card focusable' + (wide ? ' wide' : '') + (isReq ? ' req-card' : '') + '" data-id="' + h(it.Id) + '"' + (extraAttr ? ' ' + extraAttr : '') + '><div class="art" style="' + artStyle + '">' +
      posterFallbackHtml(it, title) + imgTag + reqBadge + colBadge +
      '</div>' +
      (pct ? '<div class="prog"><b style="width:' + Math.round(pct) + '%"></b></div>' : '') + '<div class="t">' + h(title) + '</div></div>';
  }
  function rankCard(it, rankNum) {
    var pct = it.UserData && it.UserData.PlayedPercentage;
    var img = resolveCardImage(it, false);
    var isReq = !!it._requestable;
    var reqDone = isReq && isTitleRequested(it.Id);
    var reqBadge = isReq ? ('<span class="req-badge' + (reqDone ? ' done' : '') + '">' + (reqDone ? '\u2713 Requested' : 'Request') + '</span>') : '';
    var artStyle = img ? ('background-image:url(\'' + h(img) + '\')') : '';
    var imgTag = img ? ('<img class="card-img" src="' + h(img) + '" alt="" onerror="this.style.display=\'none\';this.parentNode.style.backgroundImage=\'none\'">') : '';
    return '<div class="card rank-card focusable' + (isReq ? ' req-card' : '') + '" data-id="' + h(it.Id) + '" data-rank="' + rankNum + '">' +
      '<span class="rank-num">' + rankNum + '</span>' +
      '<div class="rank-poster"><div class="art" style="' + artStyle + '">' +
      posterFallbackHtml(it, it.Name) + imgTag + reqBadge +
      '<span class="rank-badge">#' + rankNum + ' Today</span></div>' +
      (pct ? '<div class="prog"><b style="width:' + Math.round(pct) + '%"></b></div>' : '') +
      '<div class="t">' + h(it.Name) + '</div></div></div>';
  }
  function seeAllCardHtml(rowId, title, totalCount, wide) {
    if (!totalCount || totalCount < 20) return '';
    return '<div class="see-all-card' + (wide ? ' wide' : '') + ' focusable" data-see-all="' + h(rowId) + '" data-title="' + h(title || 'All Titles') + '">' +
      '<div class="see-all-icon">\u2192</div>' +
      '<div><div class="see-all-title">See All</div><div class="see-all-sub">' + totalCount + (totalCount === 1 ? ' Title' : ' Titles') + '</div></div>' +
    '</div>';
  }
  function nextUpCard(it) {
    var img = resolveCardImage(it, true);
    var epCode = 'S' + (it.ParentIndexNumber || 1) + 'E' + (it.IndexNumber || 1);
    var title = (it.SeriesName ? it.SeriesName + ' \u00b7 ' : '') + epCode + (it.Name ? ' \u00b7 ' + it.Name : '');
    var rt = it.RunTimeTicks ? fmt(it.RunTimeTicks) : '';
    var artStyle = img ? ('background-image:url(\'' + h(img) + '\')') : '';
    var imgTag = img ? ('<img class="card-img" src="' + h(img) + '" alt="" onerror="this.style.display=\'none\';this.parentNode.style.backgroundImage=\'none\'">') : '';
    return '<div class="card wide nextup-card focusable" data-id="' + h(it.Id) + '" data-nextup="1">' +
      '<div class="art" style="' + artStyle + '">' +
      posterFallbackHtml(it, title) + imgTag +
      '<span class="ep-badge">Next Up \u00b7 ' + h(epCode) + '</span>' +
      (rt ? '<span class="ep-runtime">' + h(rt) + '</span>' : '') +
      '</div><div class="t">' + h(title) + '</div></div>';
  }
  function nextUpRow(title, items, id) {
    var rId = id || ('row-' + (++rowAutoSeq));
    sectionRegistry[rId] = { id: rId, title: title, items: items, wide: true };
    var vis = items.slice(0, 20);
    return '<section class="row" id="' + h(rId) + '"><h2>' + h(title) + '</h2><div class="track">' +
      vis.map(function (i) { return nextUpCard(i); }).join('') +
      (items.length >= 20 ? seeAllCardHtml(rId, title, items.length, true) : '') +
      '</div></section>';
  }
  function row(title, items, wide, id, extraAttr, sortMode) {
    var rId = id || ('row-' + (++rowAutoSeq));
    var sorted = (sortMode === 'upload') ? sortByUploadDateDesc(items) : ((sortMode === 'preserve') ? (items || []).slice() : sortByReleaseDateDesc(items));
    sectionRegistry[rId] = { id: rId, title: title || 'All Titles', items: sorted, wide: !!wide, sortMode: sortMode || 'release' };
    var vis = sorted.slice(0, 20);
    return '<section class="row" id="' + h(rId) + '">' + (title ? '<h2>' + h(title) + '</h2>' : '') + '<div class="track">' +
      (vis.length ? (vis.map(function (i) { return card(i, wide, extraAttr); }).join('') + (sorted.length >= 20 ? seeAllCardHtml(rId, title || 'All Titles', sorted.length, wide) : '')) : '<div class="empty">Nothing here yet</div>') +
      '</div></section>';
  }
  function rankRow(title, items, id) {
    var rId = id || ('row-' + (++rowAutoSeq));
    sectionRegistry[rId] = { id: rId, title: title || 'Trending', items: items, wide: false, sortMode: 'preserve' };
    var vis = items.slice(0, 20);
    return '<section class="row" id="' + h(rId) + '"><h2>' + h(title) + '</h2><div class="track">' +
      vis.map(function (it, idx) { return rankCard(it, idx + 1); }).join('') +
      (items.length >= 20 ? seeAllCardHtml(rId, title, items.length, false) : '') +
      '</div></section>';
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
  function expandToDetail(it) {
    if (!it) return;
    if (it._isCollection) {
      if (it._collectionItems && it._collectionItems.length) {
        go('browse', { id: it.Id, title: it.Name, items: it._collectionItems, sortMode: 'release' });
        return;
      }
      Api.collectionItems(it.Id).then(function (r) {
        go('browse', { id: it.Id, title: it.Name, items: (r && r.Items) || [], sortMode: 'release' });
      }, function () { go('detail', { item: it }); });
      return;
    }
    window.__lastExpandedFromHome = it.Id;
    go('detail', { item: it });
  }
  function wireCards(map, scope, fn) {
    var rootEl = scope || root;
    each(rootEl.querySelectorAll('.card'), function (c) {
      c.onclick = function () {
        var it = map[c.getAttribute('data-id')];
        if (!it) return;
        if (it._isCollection) return expandToDetail(it);
        if (fn) return fn(it);
        go('detail', { item: it });
      };
    });
    each(rootEl.querySelectorAll('.see-all-card'), function (sa) {
      sa.onclick = function () {
        var secId = sa.getAttribute('data-see-all');
        var sec = sectionRegistry[secId];
        if (sec) go('browse', { id: sec.id, title: sec.title, items: sec.items, sortMode: sec.sortMode || 'release' });
      };
    });
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
    if (it.Type === 'Series') return 'Series' + (it.ProductionYear ? ' \u00b7 ' + it.ProductionYear : '');
    return 'Movie' + (it.ProductionYear ? ' \u00b7 ' + it.ProductionYear : '');
  }
  function heroTitle(it) {
    if (it.Type === 'Episode') return (it.SeriesName ? it.SeriesName + ' \u00b7 ' : '') + 'S' + (it.ParentIndexNumber || 1) + 'E' + (it.IndexNumber || 1) + (it.Name ? ' \u00b7 ' + it.Name : '');
    return it.Name || '';
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
  var homePlatBySlug = {};
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
  var topHeroSectionHtml = heroSectionHtml;
  function tryUpgradeReqItem(it, cardEl) {
    if (!it || !it._requestable || !it.Name) return it;
    var m = findServerMatch({ name: it.Name, year: it.ProductionYear, kind: it.Type }, globalLibLookup, true);
    if (!m || !m.Id) return it;
    var upgraded = cloneServerItemForPlatform(m, it._platformSlug || '', it._platformName || '');
    if (it._platTrendRank) upgraded._platTrendRank = it._platTrendRank;
    if (it._platPopRank) upgraded._platPopRank = it._platPopRank;
    if (it._platCatalogYear) upgraded._platCatalogYear = it._platCatalogYear;
    if (it._posterUrl && !upgraded._wpPoster) upgraded._wpPoster = it._posterUrl;
    for (var k in upgraded) {
      if (Object.prototype.hasOwnProperty.call(upgraded, k)) it[k] = upgraded[k];
    }
    delete it._requestable;
    if (cardEl && cardEl.classList) {
      cardEl.classList.remove('req-card');
      var b = cardEl.querySelector && cardEl.querySelector('.req-badge');
      if (b && b.parentNode) b.parentNode.removeChild(b);
    }
    return it;
  }
  function updateHero(it, focusEl) {
    if (!it) return;
    if (it._requestable) tryUpgradeReqItem(it, focusEl);
    currentHeroItem = it;
    art(Api.img(it, 'Backdrop', 1280) || Api.img(it, 'Primary', 1280));
    scheduleBgPreview(it);
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
    if (de) {
      if (it.Overview && String(it.Overview).trim()) {
        de.textContent = it.Overview;
      } else {
        de.textContent = 'Stream now on MojaLoss.';
        if (it.Id && !it._requestable && !it._overviewFetching && String(it.Id).indexOf('col:') !== 0) {
          it._overviewFetching = 1;
          Api.item(it.Id).then(function (full) {
            if (full && full.Overview) {
              it.Overview = full.Overview;
              if (currentHeroItem && currentHeroItem.Id === it.Id) {
                var deNow = document.getElementById('hero-desc');
                if (deNow) deNow.textContent = full.Overview;
              }
            }
          }, function () {});
        }
      }
    }
    if (it._requestable) {
      var isReqd = isTitleRequested(it.Id);
      if (eb) eb.textContent = (it._platformName ? it._platformName + ' \u00b7 ' : '') + (isReqd ? 'Requested' : 'Available to Request');
      if (pl) {
        pl.textContent = isReqd ? '\u2713 Requested' : '+ Request Title';
        pl.onclick = function () {
          var target = currentHeroItem || it;
          requestTitle(target, function () { updateHero(target, focusEl); });
        };
      }
    } else if (pl) {
      pl.textContent = resume ? ('Resume from ' + clock(pos / 1e7)) : 'Play';
      pl.onclick = function () {
        var target = currentHeroItem || it;
        if (target._requestable) {
          requestTitle(target, function () { updateHero(target, focusEl); });
          return;
        }
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
  function artFocus(map) {
    Focus.onFocus = function (e) {
      if (!e) return;
      if (e.classList && e.classList.contains('chip') && document.getElementById('hero')) {
        stopBgPreview();
        var pName = e.getAttribute('data-name') || e.textContent || 'Platform';
        var eb = document.getElementById('hero-eyebrow');
        var ti = document.getElementById('hero-title');
        var me = document.getElementById('hero-meta');
        var de = document.getElementById('hero-desc');
        if (eb) eb.textContent = movingChip ? 'Reorder Streaming Platforms' : 'Streaming Platform \u00b7 Hold OK to Move';
        if (ti) ti.textContent = pName;
        if (me) me.innerHTML = '<span>Streaming Catalog</span><span>Hold OK to reorder</span>';
        if (de) de.textContent = 'Browse ' + pName + ' movies and series on MojaLoss. Press OK to open, or hold OK to move and reorder this platform.';
        return;
      }
      var it = map[e.getAttribute('data-id')];
      if (it) {
        if (document.getElementById('hero')) updateHero(it, e);
        else art(Api.img(it, 'Backdrop', 1280) || Api.img(it, 'Primary', 1280));
      }
    };
  }
  function curVer() { return (window.MLTV_OTA && MLTV_OTA.version()) || '0.1.41'; }
  function topbar(extra) { return '<div class="top"><div class="brand"><i></i>MojaLoss <span class="ver-badge" id="ver-badge">v' + h(curVer()) + '</span></div><div class="actions">' + (extra || '') + '</div></div>'; }

  /* In-app OTA Update modal, quota-safe storage & automatic OTA updates */
  var AUTO_UPDATE_KEY = "mltv_auto_update";
  var pendingAutoUpdate = null;
  var autoInstallingVer = null;

  function evictHeavyStorage(forInstall) {
    try {
      var stalePrefixes = ["mltv.disc", "mltv.discovery", "mltv.platHubCache.v1", "mltv.platHubCache.v2", "mltv.platHubCache.v3", "mltv_ota_rollback"];
      var keysToDelete = [];
      for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i);
        if (!k) continue;
        for (var j = 0; j < stalePrefixes.length; j++) {
          if (k.indexOf(stalePrefixes[j]) === 0) { keysToDelete.push(k); break; }
        }
        if (forInstall && (k.indexOf("mltv.platHubCache") === 0 || k.indexOf("mltv.posterMap") === 0)) {
          keysToDelete.push(k);
        }
      }
      for (var d = 0; d < keysToDelete.length; d++) {
        try { localStorage.removeItem(keysToDelete[d]); } catch (e2) {}
      }
    } catch (e) {}
  }
  evictHeavyStorage(false);

  function isAutoUpdateOn() {
    try { return localStorage.getItem(AUTO_UPDATE_KEY) !== "0"; } catch (e) { return true; }
  }
  function setAutoUpdateOn(on) {
    try { localStorage.setItem(AUTO_UPDATE_KEY, on ? "1" : "0"); } catch (e) {}
    refreshUpdateUi();
  }

  function maybeTriggerAutoUpdate(res) {
    if (!res || !res.hasUpdate || !res.remote || !window.MLTV_OTA) return;
    if (document.getElementById("update-modal")) return;
    if (!isAutoUpdateOn()) return;
    if (window.MLTV_OTA && MLTV_OTA.pinned && MLTV_OTA.pinned()) return;
    var isTestEnv = !!(param("mock") || param("ota"));
    if (isTestEnv && !window.__mltvForceAutoUpdate) return;
    var targetVer = res.remote.version || String(res.remote.versionCode || "");
    if (autoInstallingVer === targetVer) return;
    if (typeof player !== "undefined" && player) {
      pendingAutoUpdate = res.remote;
      toast("Update v" + targetVer + " ready — will auto-install after playback");
      return;
    }
    pendingAutoUpdate = null;
    autoInstallingVer = targetVer;
    toast("Auto-updating to v" + targetVer + "…");
    var sEl = document.getElementById("ota-modal-status");
    if (sEl) sEl.textContent = "Auto-updating to v" + targetVer + "…";
    MLTV_OTA.installUpdate(res.remote).then(function () {
      toast("Updated to v" + targetVer + "! Reloading…");
    }, function (err) {
      autoInstallingVer = null;
      var sEl2 = document.getElementById("ota-modal-status");
      if (sEl2) sEl2.textContent = "Update rejected: " + (err && err.message ? err.message : "verification failed");
    });
  }

  var preModalFocus = null;
  function closeUpdateModal() {
    var m = document.getElementById("update-modal");
    if (m && m.parentNode) m.parentNode.removeChild(m);
    if (preModalFocus && document.body.contains(preModalFocus)) Focus.set(preModalFocus);
    else Focus.first(".focusable");
  }
  function openUpdateModal() {
    if (typeof stopBgPreview === "function") stopBgPreview(true);
    var existing = document.getElementById("update-modal");
    if (existing) return;
    preModalFocus = Focus.current();
    var m = document.getElementById("update-modal");
    if (!m) {
      m = document.createElement("div");
      m.id = "update-modal";
      m.className = "modal-backdrop";
      (document.getElementById("root") || document.body).appendChild(m);
    }
    var st = window.MLTV_OTA ? MLTV_OTA.state() : { activeVersion: "0.1.41", activeSource: "builtin", builtinVersion: "0.1.41", pin: null };
    var autoOn = isAutoUpdateOn();
    m.innerHTML = "<div class=\"modal-card update-card\">" +
      "<div class=\"update-top\"><div class=\"brand\"><i></i>MojaLoss</div><div class=\"update-kicker\">Update</div></div>" +
      "<h1 class=\"update-ver\" id=\"ota-latest-ver\">v" + h(st.activeVersion) + "</h1>" +
      "<p class=\"update-lead\" id=\"ota-modal-status\">Loading versions…</p>" +
      "<div id=\"ota-latest-action\"></div>" +
      "<div class=\"update-rule\"></div>" +
      "<div class=\"ver-head\">Last version</div>" +
      "<div id=\"ota-last\"></div>" +
      "<div id=\"ota-older\" hidden></div>" +
      "<div class=\"update-actions\">" +
      "<div class=\"btn focusable\" id=\"ota-show-older\">Other versions</div>" +
      "<div class=\"btn focusable" + (autoOn ? " is-on" : "") + "\" id=\"ota-auto-toggle\">Auto-Update " + (autoOn ? "On" : "Off") + "</div>" +
      "<div class=\"btn focusable\" id=\"ota-modal-close\">Close</div>" +
      "</div></div>";
    function say(text) {
      var el = document.getElementById("ota-modal-status");
      if (el) el.textContent = text;
    }
    function failText(err) { return (err && err.message) ? err.message : "Could not switch versions"; }
    function switchTo(entry, label) {
      say(label);
      MLTV_OTA.useVersion({
        version: entry.version,
        versionCode: entry.versionCode,
        bundleUrl: entry.bundleUrl,
        latest: !!entry.latest
      }).then(function (res) {
        if (res && res.already) say("Already on v" + entry.version + ".");
      }, function (err) { say(failText(err)); });
    }
    function versionsWithPackages(list) {
      var out = [];
      for (var i = 0; i < (list || []).length; i++) {
        var v = list[i];
        if (v && v.bundleUrl && !v.blocked) out.push(v);
      }
      return out;
    }
    function paintVersions(list) {
      if (!document.getElementById("update-modal")) return;
      var ranked = versionsWithPackages(list);
      var title = document.getElementById("ota-latest-ver");
      var action = document.getElementById("ota-latest-action");
      var last = document.getElementById("ota-last");
      if (!ranked.length) {
        if (title) title.textContent = "v" + st.activeVersion;
        say("Couldn't load versions. You are on v" + st.activeVersion + ".");
        if (last) last.innerHTML = "";
        return;
      }
      var newest = ranked[0];
      var previous = ranked[1] || null;
      var onLatest = newest.versionCode === st.activeVersionCode;
      if (title) title.textContent = "v" + newest.version;
      say(onLatest ? "Already on the latest version." : ("You are on v" + st.activeVersion + "."));
      if (action) {
        action.innerHTML = onLatest ? "" : "<div class=\"btn primary focusable\" id=\"ota-go-latest\">Update to v" + h(newest.version) + "</div>";
        var up = document.getElementById("ota-go-latest");
        if (up) up.onclick = function () { switchTo({ version: newest.version, versionCode: newest.versionCode, bundleUrl: newest.bundleUrl, latest: true }, "Updating to v" + newest.version + "…"); };
      }
      if (last) {
        if (!previous) last.innerHTML = "<p class=\"update-lead\">No earlier version yet.</p>";
        else if (previous.versionCode === st.activeVersionCode) last.innerHTML = "<div class=\"ver-row is-selected\"><div class=\"ver-meta\"><b>v" + h(previous.version) + "</b><span>You are on the last version</span></div></div>";
        else last.innerHTML = "<div class=\"ver-row is-selected\"><div class=\"ver-meta\"><b>v" + h(previous.version) + "</b><span>The version before the latest</span></div></div><div class=\"btn primary focusable\" id=\"ota-go-back\">Go back to last version</div>";
        var back = document.getElementById("ota-go-back");
        if (back) back.onclick = function () { switchTo(previous, "Going back to v" + previous.version + "…"); };
      }
      var older = document.getElementById("ota-older");
      if (older) {
        var more = "";
        for (var n = 2; n < ranked.length; n++) {
          more += "<div class=\"ver-row focusable\" data-code=\"" + ranked[n].versionCode + "\"><div class=\"ver-meta\"><b>v" + h(ranked[n].version) + "</b><span>Earlier version</span></div></div>";
        }
        older.innerHTML = more;
        older.hidden = true;
        var rows = older.querySelectorAll(".ver-row");
        for (var b = 0; b < rows.length; b++) {
          rows[b].onclick = function () {
            var code = +this.getAttribute("data-code");
            for (var k = 0; k < ranked.length; k++) {
              if (ranked[k].versionCode === code) { switchTo(ranked[k], "Switching to v" + ranked[k].version + "…"); return; }
            }
          };
        }
      }
      var show = document.getElementById("ota-show-older");
      if (show) show.textContent = "Other versions";
      var focusEl = document.getElementById("ota-go-latest") || document.getElementById("ota-go-back") || document.getElementById("ota-modal-close");
      if (focusEl) Focus.set(focusEl);
    }
    var showBtn = document.getElementById("ota-show-older");
    showBtn.onclick = function () {
      var older = document.getElementById("ota-older");
      if (!older) return;
      older.hidden = !older.hidden;
      showBtn.textContent = older.hidden ? "Other versions" : "Hide versions";
      if (!older.hidden) {
        var row = older.querySelector(".ver-row");
        if (row) Focus.set(row);
      }
    };
    document.getElementById("ota-auto-toggle").onclick = function () {
      var next = !isAutoUpdateOn();
      setAutoUpdateOn(next);
      this.textContent = "Auto-Update " + (next ? "On" : "Off");
      if (next) this.classList.add("is-on"); else this.classList.remove("is-on");
    };
    document.getElementById("ota-modal-close").onclick = closeUpdateModal;
    function paintPair(newest, previous) {
      paintVersions([newest, previous].concat([]).filter(function (v) { return v && v.version; }));
    }
    function loadScreen() {
      var manifest = "https://raw.githubusercontent.com/junior-rahman/Mojaloss/tv-ota/tv-manifest.json";
      var check = (window.MLTV_OTA && MLTV_OTA.checkForUpdates) ? MLTV_OTA.checkForUpdates(true) : Promise.reject(new Error("No updater"));
      check.then(function (res) {
        var remote = res && res.remote;
        var prev = remote && remote.previous;
        if (!remote || typeof remote.versionCode !== "number" || !prev || !prev.bundleUrl) return null;
        paintPair(
          { version: remote.version, versionCode: remote.versionCode, bundleUrl: manifest },
          { version: prev.version, versionCode: prev.versionCode, bundleUrl: prev.bundleUrl }
        );
        if (MLTV_OTA.listVersions) {
          MLTV_OTA.listVersions().then(function (list) {
            if (!document.getElementById("update-modal") || !list) return;
            var older = document.getElementById("ota-older");
            if (!older) return;
            var more = "";
            for (var n = 0; n < list.length; n++) {
              var v = list[n];
              if (!v || !v.bundleUrl || v.blocked) continue;
              if (v.versionCode === remote.versionCode || v.versionCode === prev.versionCode) continue;
              more += "<div class=\"ver-row focusable\" data-code=\"" + v.versionCode + "\" data-url=\"" + h(v.bundleUrl) + "\" data-ver=\"" + h(v.version) + "\"><div class=\"ver-meta\"><b>v" + h(v.version) + "</b><span>Earlier version</span></div></div>";
            }
            if (!more) return;
            var wasOpen = !older.hidden;
            older.innerHTML = more;
            older.hidden = !wasOpen;
            var rows = older.querySelectorAll(".ver-row");
            for (var b = 0; b < rows.length; b++) {
              rows[b].onclick = function () {
                switchTo({ version: this.getAttribute("data-ver"), versionCode: +this.getAttribute("data-code"), bundleUrl: this.getAttribute("data-url") }, "Switching to v" + this.getAttribute("data-ver") + "…");
              };
            }
          }, function () {});
        }
        return true;
      }).then(function (done) {
        if (done) return;
        if (window.MLTV_OTA && MLTV_OTA.listVersions) {
          MLTV_OTA.listVersions().then(paintVersions, function (err) { say(failText(err) + " You are on v" + st.activeVersion + "."); });
        } else {
          say("You are on v" + st.activeVersion + ".");
        }
      }, function () {
        if (window.MLTV_OTA && MLTV_OTA.listVersions) {
          MLTV_OTA.listVersions().then(paintVersions, function (err) { say(failText(err) + " You are on v" + st.activeVersion + "."); });
        } else {
          say("You are on v" + st.activeVersion + ".");
        }
      });
    }
    loadScreen();
  }
  function refreshUpdateUi() {
    if (!window.MLTV_OTA) return;
    var st = MLTV_OTA.state(), b = document.getElementById("ota-banner"), u = document.getElementById("update-btn");
    if (u) {
      u.innerHTML = st.updating ? "Updating…" : (st.availableUpdate ? ("Update · v" + h(st.availableUpdate.version)) : ("Update · v" + h(st.activeVersion)));
      if (st.availableUpdate || st.updating) u.classList.add("has-update"); else u.classList.remove("has-update");
    }
    if (b) {
      if (st.availableUpdate && st.pin) {
        b.style.display = "flex";
        b.innerHTML = "<span><b>Latest is v" + h(st.availableUpdate.version) + "</b> — staying on v" + h(st.pin.version) + "</span><span class=\"pill on\">OK defaults to latest</span>";
        b.classList.add("focusable");
        b.onclick = function () {
          toast("Switching to the latest version…");
          MLTV_OTA.useLatest().catch(function (err) { toast("Could not switch: " + (err && err.message ? err.message : "error")); });
        };
      } else if (st.availableUpdate) {
        b.style.display = "flex";
        b.innerHTML = "<span><b>Update available (v" + h(st.availableUpdate.version) + ")</b>" + (st.availableUpdate.notes ? " — " + h(st.availableUpdate.notes) : "") + "</span><span class=\"pill on\">Press OK to update now</span>";
        b.classList.add("focusable");
        b.onclick = function () {
          toast("Updating to v" + st.availableUpdate.version + "…");
          MLTV_OTA.installUpdate(st.availableUpdate).catch(function (err) { toast("Update failed: " + (err && err.message ? err.message : "error")); });
        };
      } else {
        b.style.display = "none";
        b.classList.remove("focusable");
      }
    }
  }
  if (window.MLTV_OTA) {
    var supportsOlderBoot = typeof MLTV_OTA.pinned === "function";
    function readVersionPin() {
      try {
        var raw = localStorage.getItem("mltv_version_pin");
        if (!raw) return null;
        var p = JSON.parse(raw);
        if (!p || typeof p.versionCode !== "number") return null;
        return { version: String(p.version || ""), versionCode: p.versionCode };
      } catch (e) { return null; }
    }
    function writeVersionPin(info) {
      try {
        if (!info) localStorage.removeItem("mltv_version_pin");
        else localStorage.setItem("mltv_version_pin", JSON.stringify({ version: info.version, versionCode: info.versionCode }));
      } catch (e) {}
      try { MLTV_OTA.state().pin = readVersionPin(); } catch (e2) {}
    }
    function versionCodeFromName(version) {
      var tm = /^(\d+)\.(\d+)\.(\d+)$/.exec(String(version || ""));
      if (!tm) return null;
      return (+tm[1]) * 10000 + ((+tm[2]) - 1) * 100 + (+tm[3]);
    }
    function parseReleaseList(data) {
      var arr = Array.isArray(data) ? data : [];
      var out = [];
      for (var i = 0; i < arr.length; i++) {
        var r = arr[i] || {};
        if (typeof r.versionCode === "number" && r.version) {
          out.push({ version: String(r.version), versionCode: r.versionCode, notes: r.notes || "", bundleUrl: r.bundleUrl || "" });
          continue;
        }
        var tm = /^tv-v(\d+\.\d+\.\d+)$/.exec(String(r.tag_name || ""));
        if (!tm) continue;
        var code = versionCodeFromName(tm[1]);
        if (code == null) continue;
        var bundleUrl = "";
        var assets = r.assets || [];
        for (var a = 0; a < assets.length; a++) {
          if (!assets[a]) continue;
          if (assets[a].name === "tv-manifest.json") bundleUrl = assets[a].browser_download_url || bundleUrl;
          if (assets[a].name === "tv-ota-bundle.json") { bundleUrl = assets[a].browser_download_url || ""; break; }
        }
        if (!bundleUrl) continue;
        out.push({ version: tm[1], versionCode: code, notes: String(r.name || r.body || "").split("\n")[0].slice(0, 120), bundleUrl: bundleUrl });
      }
      return out;
    }
    function releasesUrl() {
      var mock = param("mock");
      if (mock) return mock.replace(/\/$/, "") + "/ota/versions";
      return "https://raw.githubusercontent.com/junior-rahman/Mojaloss/tv-ota/tv-versions.json?_t=" + Date.now();
    }
    function applyVersionChoice(entry, asLatest) {
      if (asLatest) { writeVersionPin(null); setAutoUpdateOn(true); }
      else { writeVersionPin({ version: entry.version, versionCode: entry.versionCode }); setAutoUpdateOn(false); }
    }
    try { MLTV_OTA.state().pin = readVersionPin(); } catch (e) {}
    MLTV_OTA.pinned = function () { return readVersionPin(); };
    MLTV_OTA.listVersions = function () {
      var st = MLTV_OTA.state();
      var map = {};
      function add(v) {
        if (!v || typeof v.versionCode !== "number") return;
        var prev = map[v.versionCode];
        if (!prev) {
          map[v.versionCode] = { version: String(v.version || ""), versionCode: v.versionCode, notes: v.notes || "", bundleUrl: v.bundleUrl || "", builtin: !!v.builtin };
          return;
        }
        if (v.builtin) prev.builtin = true;
        if (v.notes && !prev.notes) prev.notes = v.notes;
        if (v.bundleUrl && !prev.bundleUrl) prev.bundleUrl = v.bundleUrl;
      }
      add({ version: st.builtinVersion, versionCode: st.builtinVersionCode, notes: "Copy saved on this TV", builtin: true });
      add({ version: st.activeVersion, versionCode: st.activeVersionCode, notes: "" });
      return fetch(releasesUrl(), { cache: "no-store" }).then(function (r) {
        if (!r.ok) throw new Error("HTTP " + r.status);
        return r.json();
      }).then(parseReleaseList, function () { return []; }).then(function (remote) {
        for (var i = 0; i < remote.length; i++) add(remote[i]);
        var list = [];
        for (var k in map) if (Object.prototype.hasOwnProperty.call(map, k)) list.push(map[k]);
        list.sort(function (a, b) { return b.versionCode - a.versionCode; });
        var kept = [];
        var builtinRow = null;
        for (var n = 0; n < list.length; n++) {
          list[n].current = list[n].versionCode === st.activeVersionCode;
          list[n].blocked = list[n].versionCode < st.builtinVersionCode && !supportsOlderBoot;
          if (list[n].builtin) builtinRow = list[n];
          if (kept.length < 8) kept.push(list[n]);
        }
        if (builtinRow && kept.indexOf(builtinRow) < 0) kept.push(builtinRow);
        if (kept.length) kept[0].latest = true;
        return kept;
      });
    };
    MLTV_OTA.useVersion = function (entry) {
      if (!entry || typeof entry.versionCode !== "number") return Promise.reject(new Error("Unknown version"));
      var st = MLTV_OTA.state();
      var asLatest = !!entry.latest;
      if (entry.blocked || (entry.versionCode < st.builtinVersionCode && !supportsOlderBoot)) {
        return Promise.reject(new Error("This TV can only go back as far as v" + st.builtinVersion + "."));
      }
      if (entry.versionCode === st.activeVersionCode) {
        if (asLatest) applyVersionChoice(entry, true);
        return Promise.resolve({ already: true, version: st.activeVersion });
      }
      if (entry.versionCode === st.builtinVersionCode) {
        applyVersionChoice({ version: st.builtinVersion, versionCode: st.builtinVersionCode }, asLatest);
        MLTV_OTA.resetToBuiltin();
        return Promise.resolve({ version: st.builtinVersion });
      }
      if (!entry.bundleUrl) return Promise.reject(new Error("No package for v" + entry.version));
      return fetch(entry.bundleUrl, { cache: "no-store" }).then(function (r) {
        if (!r.ok) throw new Error("HTTP " + r.status);
        return r.json();
      }).then(function (bundle) {
        return MLTV_OTA.verifyBundle ? MLTV_OTA.verifyBundle(bundle) : bundle;
      }).then(function (verified) {
        applyVersionChoice(entry, asLatest);
        return MLTV_OTA.installUpdate(verified, asLatest ? { pin: null } : { pin: { version: entry.version, versionCode: entry.versionCode } });
      });
    };
    MLTV_OTA.useLatest = function () {
      return MLTV_OTA.listVersions().then(function (list) {
        var latest = null;
        for (var i = 0; i < list.length; i++) if (!latest || list[i].versionCode > latest.versionCode) latest = list[i];
        if (!latest) return { already: true, version: MLTV_OTA.version() };
        return MLTV_OTA.useVersion({ version: latest.version, versionCode: latest.versionCode, notes: latest.notes, bundleUrl: latest.bundleUrl, builtin: latest.builtin, latest: true });
      });
    };
    MLTV_OTA.isAutoUpdateEnabled = isAutoUpdateOn;
    MLTV_OTA.setAutoUpdateEnabled = setAutoUpdateOn;
    MLTV_OTA.triggerAutoUpdate = function () {
      var st = MLTV_OTA.state();
      if (st && st.availableUpdate) {
        var prevForce = window.__mltvForceAutoUpdate;
        window.__mltvForceAutoUpdate = true;
        maybeTriggerAutoUpdate({ hasUpdate: true, remote: st.availableUpdate });
        window.__mltvForceAutoUpdate = prevForce;
      }
    };
    var origInstall = MLTV_OTA.installUpdate;
    MLTV_OTA.installUpdate = function (bundleOrNull, opt) {
      evictHeavyStorage(true);
      var prevRaw = null;
      try { prevRaw = localStorage.getItem("mltv_ota_bundle"); } catch (e) {}
      var arch = Promise.resolve();
      if (prevRaw && MLTV_OTA.archiveBundle) {
        try { arch = MLTV_OTA.archiveBundle(JSON.parse(prevRaw)) || Promise.resolve(); } catch (e3) {}
      }
      var st = MLTV_OTA.state();
      var candidate = bundleOrNull || (st && st.availableUpdate) || null;
      return arch.then(function () {
        return origInstall.call(MLTV_OTA, candidate, opt);
      }).then(function (verified) {
        try {
          var checkRaw = localStorage.getItem("mltv_ota_bundle");
          var checkObj = checkRaw ? JSON.parse(checkRaw) : null;
          if (!checkObj || checkObj.versionCode !== verified.versionCode) {
            evictHeavyStorage(true);
            localStorage.removeItem("mltv_ota_bundle");
            localStorage.setItem("mltv_ota_bundle", JSON.stringify(verified));
          }
        } catch (e2) {}
        return verified;
      });
    };
    MLTV_OTA.onChange(refreshUpdateUi);
    var baseCheck = MLTV_OTA.checkForUpdates;
    MLTV_OTA.checkForUpdates = function (silent) {
      var st = MLTV_OTA.state();
      if (param("mock") || param("ota")) {
        return baseCheck.call(MLTV_OTA, silent).then(function (res) {
          if (res && res.hasUpdate) maybeTriggerAutoUpdate(res);
          return res;
        });
      }
      st.checking = true; st.lastCheckError = null; refreshUpdateUi();
      return fetch("https://raw.githubusercontent.com/junior-rahman/Mojaloss/tv-ota/tv-manifest.json?_t=" + Date.now(), { cache: "no-store" }).then(function (r) {
        if (!r.ok) throw new Error("HTTP " + r.status);
        return r.json();
      }).then(function (remote) {
        st.checking = false;
        st.availableUpdate = (remote && typeof remote.versionCode === "number" && remote.versionCode > st.activeVersionCode) ? remote : null;
        refreshUpdateUi();
        var res = { hasUpdate: !!st.availableUpdate, remote: remote, currentVersion: st.activeVersion, currentVersionCode: st.activeVersionCode };
        if (res.hasUpdate) maybeTriggerAutoUpdate(res);
        return res;
      }).catch(function () {
        return fetch("https://raw.githubusercontent.com/junior-rahman/Mojaloss/tv-ota/tv-manifest.json?_t=" + Date.now(), { cache: "no-store" }).then(function (r2) {
          if (!r2.ok) throw new Error("HTTP " + r2.status);
          return r2.json();
        }).then(function (remote2) {
          st.checking = false;
          st.availableUpdate = (remote2 && typeof remote2.versionCode === "number" && remote2.versionCode > st.activeVersionCode) ? remote2 : null;
          refreshUpdateUi();
          var res2 = { hasUpdate: !!st.availableUpdate, remote: remote2, currentVersion: st.activeVersion, currentVersionCode: st.activeVersionCode };
          if (res2.hasUpdate) maybeTriggerAutoUpdate(res2);
          return res2;
        }).catch(function () {
          return baseCheck.call(MLTV_OTA, silent).then(function (res3) {
            if (res3 && res3.hasUpdate) maybeTriggerAutoUpdate(res3);
            return res3;
          });
        });
      });
    };
  }
  function norm(s) {
    return String(s || '').toLowerCase()
      .replace(/&amp;/g, 'and').replace(/&/g, 'and')
      .replace(/\s*[\(\[]\d{4}(?:\s*[-–\u2013]\s*\d{0,4})?\s*[\)\]]\s*$/, '')
      .replace(/[^a-z0-9]+/g, '');
  }
  function noArtNorm(s) {
    return norm(s).replace(/^(?:the|a|an)(?=[a-z0-9]{3})/, '');
  }
  function baseNorm(s) {
    return String(s || '').toLowerCase()
      .replace(/&amp;/g, 'and').replace(/&/g, 'and')
      .split(/[:\-–\u2013\u2014]/)[0]
      .replace(/\s*[\(\[]\d{4}(?:\s*[-–\u2013]\s*\d{0,4})?\s*[\)\]]\s*$/, '')
      .replace(/[^a-z0-9]+/g, '');
  }
  function stemNorm(s) {
    var b = noArtNorm(baseNorm(s));
    if (b.length >= 4 && /y$/.test(b)) return b.slice(0, -1);
    if (b.length >= 5 && /s$/.test(b)) return b.slice(0, -1);
    return b;
  }

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

  var globalLibLookup = { all: [], byName: {}, byNoArt: {}, byBase: {}, byImdb: {}, byTmdb: {} };
  function indexServerItems(items) {
    var byName = globalLibLookup.byName, byNoArt = globalLibLookup.byNoArt, byBase = globalLibLookup.byBase, byImdb = globalLibLookup.byImdb, byTmdb = globalLibLookup.byTmdb;
    var seen = {};
    globalLibLookup.all.forEach(function (x) { if (x && x.Id) seen[x.Id] = 1; });
    (items || []).forEach(function (it) {
      if (!it || !it.Id || it._requestable) return;
      if (!seen[it.Id]) {
        seen[it.Id] = 1;
        globalLibLookup.all.push(it);
      }
      if (it.ProviderIds) {
        if (it.ProviderIds.Imdb) byImdb[String(it.ProviderIds.Imdb).toLowerCase()] = it;
        if (it.ProviderIds.Tmdb) byTmdb[String(it.ProviderIds.Tmdb) + ':' + (it.Type || '')] = it;
      }
      [it.Name, it.OriginalTitle].forEach(function (rawName) {
        if (!rawName) return;
        var k = norm(rawName);
        if (k) (byName[k] = byName[k] || []).push(it);
        var na = noArtNorm(rawName);
        if (na) (byNoArt[na] = byNoArt[na] || []).push(it);
        var bn = baseNorm(rawName);
        if (bn && bn.length >= 4) (byBase[bn] = byBase[bn] || []).push(it);
      });
    });
    return globalLibLookup;
  }

  function itemReleaseYear(it) {
    if (!it) return null;
    if (it.ProductionYear) return +it.ProductionYear;
    if (it.PremiereDate) {
      var y = parseInt(String(it.PremiereDate).slice(0, 4), 10);
      if (y > 1880 && y < 2100) return y;
    }
    return null;
  }

  function findServerMatch(entry, lookup, allowRelaxedKind) {
    if (!entry || !entry.name) return null;
    lookup = lookup || globalLibLookup;
    if (entry.imdbId && lookup.byImdb && lookup.byImdb[String(entry.imdbId).toLowerCase()]) {
      return lookup.byImdb[String(entry.imdbId).toLowerCase()];
    }
    if (entry.tmdbId && lookup.byTmdb && lookup.byTmdb[String(entry.tmdbId) + ':' + (entry.kind || '')]) {
      return lookup.byTmdb[String(entry.tmdbId) + ':' + (entry.kind || '')];
    }
    var k = norm(entry.name), na = noArtNorm(entry.name);
    var cands = [].concat(
      lookup.byName[k] || [],
      lookup.byNoArt[na] || []
    );
    var seen = {}, unique = [];
    for (var u = 0; u < cands.length; u++) {
      var c = cands[u];
      if (c && c.Id && !c._requestable && !seen[c.Id]) {
        if (norm(c.Name) === k || (c.OriginalTitle && norm(c.OriginalTitle) === k) || noArtNorm(c.Name) === na) {
          seen[c.Id] = 1;
          unique.push(c);
        }
      }
    }
    if (!unique.length && allowRelaxedKind && entry.year) {
      var bn = baseNorm(entry.name);
      if (bn && bn.length >= 5 && lookup.byBase && lookup.byBase[bn]) {
        var bList = lookup.byBase[bn];
        for (var bIdx = 0; bIdx < bList.length; bIdx++) {
          var bCand = bList[bIdx];
          if (bCand && bCand.Id && !bCand._requestable && !seen[bCand.Id] && baseNorm(bCand.Name) === bn) {
            seen[bCand.Id] = 1;
            unique.push(bCand);
          }
        }
      }
    }
    if (!unique.length) return null;

    var wantYear = entry.year ? +entry.year : null;
    var wantYearEnd = entry.yearEnd ? +entry.yearEnd : null;
    var wantKind = entry.kind || null;

    // Pass 1: Strict exact title + exact Type + exact Release Year (itYear === wantYear)
    if (wantYear) {
      for (var i = 0; i < unique.length; i++) {
        var it = unique[i];
        var y = itemReleaseYear(it);
        if ((!wantKind || it.Type === wantKind) && y === wantYear) {
          if (entry.poster && !it._wpPoster) it._wpPoster = entry.poster;
          return it;
        }
      }
      // Pass 2: Strict exact title + exact Type + ±1 year tolerance (for Dec/Jan festival vs wide release)
      for (var j = 0; j < unique.length; j++) {
        var it2 = unique[j];
        var y2 = itemReleaseYear(it2);
        if ((!wantKind || it2.Type === wantKind) && y2 && Math.abs(y2 - wantYear) <= 1) {
          if (entry.poster && !it2._wpPoster) it2._wpPoster = entry.poster;
          return it2;
        }
      }
      // Pass 3: For multi-season TV Series where wantYear is premiere/season year and server stores a different season year
      if (wantKind === 'Series') {
        for (var s = 0; s < unique.length; s++) {
          var itS = unique[s];
          var yS = itemReleaseYear(itS);
          if (itS.Type === 'Series' && yS && ((wantYearEnd && yS >= wantYear - 1 && yS <= wantYearEnd + 1) || (entry.seriesStartYear && Math.abs(yS - entry.seriesStartYear) <= 1) || ((entry.isOngoing || allowRelaxedKind) && yS >= wantYear - 1))) {
            if (entry.poster && !itS._wpPoster) itS._wpPoster = entry.poster;
            return itS;
          }
        }
      }
      // Pass 4: Server item has exact title + exact Type, and NO ProductionYear metadata on server (no year conflict)
      for (var n = 0; n < unique.length; n++) {
        var itN = unique[n];
        var yN = itemReleaseYear(itN);
        if ((!wantKind || itN.Type === wantKind) && !yN) {
          if (entry.poster && !itN._wpPoster) itN._wpPoster = entry.poster;
          if (!itN.ProductionYear && wantYear) itN.ProductionYear = wantYear;
          return itN;
        }
      }
      // Pass 5: For MojaLoss's own trending/category posts (allowRelaxedKind === true), allow ±2yr or Series match
      if (allowRelaxedKind) {
        for (var r = 0; r < unique.length; r++) {
          var itR = unique[r];
          var yR = itemReleaseYear(itR);
          if ((!wantKind || itR.Type === wantKind) && (!yR || Math.abs(yR - wantYear) <= 2 || itR.Type === 'Series')) {
            if (entry.poster && !itR._wpPoster) itR._wpPoster = entry.poster;
            return itR;
          }
        }
      }
      // If wantYear was specified and NO candidate matched the year, DO NOT match a different year (e.g. never match 2017 to 2026)!
      return null;
    }

    // Only when entry.year was NOT specified (e.g. plain text search): match exact title + Type
    for (var m = 0; m < unique.length; m++) {
      var it3 = unique[m];
      if (!wantKind || allowRelaxedKind || it3.Type === wantKind) {
        if (entry.poster && !it3._wpPoster) it3._wpPoster = entry.poster;
        return it3;
      }
    }
    return null;
  }

  var discoveryCache = null;
  var DISC_KEY = 'mltv.disc.v9';
  var latestMostWatchedItems = [], latestTrendingItems = [];
  var PLAT_ORDER_KEY = 'mltv.platOrder';
  var PLAT_HUB_CACHE_KEY = 'mltv.platHubCache.v4';
  var PLAT_HUB_CACHE_VER = 2;
  var platformHubCache = {};
  try {
    var rawHubCache = localStorage.getItem(PLAT_HUB_CACHE_KEY);
    if (rawHubCache) {
      var parsedHub = JSON.parse(rawHubCache) || {};
      Object.keys(parsedHub).forEach(function (k) {
        if (parsedHub[k] && parsedHub[k].ver === PLAT_HUB_CACHE_VER) platformHubCache[k] = parsedHub[k];
      });
    }
  } catch (e) { platformHubCache = {}; }

  function catEntry(name, year, kind, rating, mins, genres, overview, trend, pop, seriesStartYear, imdbBucket) {
    return { name: name, year: year, kind: kind, rating: rating, mins: mins, genres: genres, overview: overview, trend: trend || 0, pop: pop || 0, seriesStartYear: seriesStartYear || null, imdbBucket: imdbBucket || '' };
  }
  var PLATFORM_CATALOG_DB = {
    apple: [
      // Apple TV+ Current 2025-2026 Trending + Movies & Series
      catEntry('The Gorge', 2025, 'Movie', 'PG-13', 127, ['Action', 'Sci-Fi', 'Thriller'], 'Two highly trained operatives are appointed to posts in guard towers on opposite sides of a vast and highly classified gorge.', 1, 5),
      catEntry('Severance', 2025, 'Series', 'TV-MA', 56, ['Sci-Fi', 'Thriller', 'Drama'], 'Mark and the Macrodata Refinement team uncover the true purpose of Lumon Industries\' severed floor.', 2, 1, 2022),
      catEntry('F1', 2025, 'Movie', 'PG-13', 155, ['Action', 'Drama', 'Sport'], 'A veteran Formula One driver comes out of retirement to mentor a rookie prodigy on the world championship grid.', 3, 6),
      catEntry('Silo', 2025, 'Series', 'TV-MA', 54, ['Sci-Fi', 'Drama', 'Mystery'], 'Outside the silo walls, Juliette discovers a secret that changes the fate of the last ten thousand survivors.', 4, 2, 2023),
      catEntry('The Studio', 2025, 'Series', 'TV-MA', 35, ['Comedy'], 'The newly appointed head of a legacy Hollywood movie studio scrambles to keep cinema alive in an era of corporate chaos.', 5, 0, 2025),
      catEntry('Slow Horses', 2025, 'Series', 'TV-MA', 50, ['Drama', 'Thriller'], 'Jackson Lamb leads a team of British intelligence agents who serve in a dumping ground department of MI5 due to their career-ending mistakes.', 6, 4, 2022),
      catEntry('Foundation', 2025, 'Series', 'TV-14', 58, ['Drama', 'Sci-Fi'], 'A complex saga of humans scattered on planets throughout the galaxy all living under the rule of the Galactic Empire.', 7, 8, 2021),
      catEntry('Lucky', 2026, 'Series', 'TV-MA', 52, ['Crime', 'Drama', 'Thriller'], 'A young woman who left behind the life of crime she was raised in must embrace her darker, criminal side one final time in a desperate attempt to escape her past.', 8, 0, 2026),
      catEntry('Fountain of Youth', 2025, 'Movie', 'PG-13', 125, ['Action', 'Adventure', 'Mystery'], 'Two estranged siblings partner on a global heist to find the mythological Fountain of Youth.', 9, 0),
      catEntry('Dope Thief', 2025, 'Series', 'TV-MA', 52, ['Crime', 'Drama'], 'Two lifelong Philadelphia friends pose as DEA agents to rob an unknown countryside stash house, uncovering a massive narcotics corridor.', 10, 0, 2025),
      catEntry('Your Friends & Neighbors', 2025, 'Series', 'TV-MA', 54, ['Drama'], 'After being fired in disgrace, a hedge fund manager resorts to stealing from his wealthy neighbors\' homes in the ultra-affluent village of Westmont.', 11, 0, 2025),
      catEntry('Prime Target', 2025, 'Series', 'TV-MA', 50, ['Drama', 'Thriller'], 'A brilliant young math postgraduate is on the brink of a major breakthrough when he realizes an unseen enemy is trying to destroy his idea.', 12, 0, 2025),
      catEntry('Echo Valley', 2025, 'Movie', 'R', 112, ['Drama', 'Thriller'], 'A mother horses-trains on a quiet farm in Pennsylvania until her daughter arrives covered in someone else\'s blood.', 13, 0),
      catEntry('Highest 2 Lowest', 2025, 'Movie', 'R', 134, ['Crime', 'Drama', 'Thriller'], 'A music mogul is targeted with a ransom plot that forces him into a life-or-death moral dilemma.', 14, 0),
      catEntry('Murderbot', 2025, 'Series', 'TV-MA', 42, ['Action', 'Sci-Fi'], 'A self-hacking security android who is horrified by human emotion must hide its free will and complete a dangerous assignment.', 15, 0, 2025),
      // Apple TV+ Popular Classics & Movies/Series Catalog
      catEntry('Ted Lasso', 2020, 'Series', 'TV-MA', 45, ['Comedy', 'Drama', 'Sport'], 'American college football coach Ted Lasso heads to London to manage AFC Richmond, a struggling English Premier League soccer team.', 0, 3, 2020),
      catEntry('The Morning Show', 2019, 'Series', 'TV-MA', 58, ['Drama'], 'An inside look at the lives of the people who help America wake up in the morning, exploring the unique challenges faced by the team.', 0, 7, 2019),
      catEntry('For All Mankind', 2019, 'Series', 'TV-MA', 60, ['Drama', 'Sci-Fi'], 'In an alternative version of 1969, the Soviet Union beats the United States to the Moon, and the space race continues on for decades.', 0, 9, 2019),
      catEntry('Killers of the Flower Moon', 2023, 'Movie', 'R', 206, ['Crime', 'Drama', 'History'], 'Members of the Osage tribe in the United States are murdered under mysterious circumstances in the 1920s, sparking a major FBI investigation.', 0, 10),
      catEntry('Presumed Innocent', 2024, 'Series', 'TV-MA', 52, ['Crime', 'Drama', 'Mystery'], 'A horrific murder upends the Chicago Prosecuting Attorney\'s Office when one of its own is suspected of the crime.', 0, 11, 2024),
      catEntry('Dark Matter', 2024, 'Series', 'TV-MA', 55, ['Drama', 'Sci-Fi', 'Thriller'], 'A physicist is abducted into an alternate version of his life and must fight through a multiverse of lives he could have lived to return to his family.', 0, 12, 2024),
      catEntry('CODA', 2021, 'Movie', 'PG-13', 111, ['Drama', 'Music'], 'As a CODA (Child of Deaf Adults) Ruby is the only hearing person in her deaf family and finds herself torn between pursuing her love of music and her fear of abandoning her parents.', 0, 13),
      catEntry('Greyhound', 2020, 'Movie', 'PG-13', 91, ['Action', 'Drama', 'History'], 'Several months after the U.S. entry into World War II, an inexperienced U.S. Navy commander must lead an Allied convoy being stalked by a German submarine wolf pack.', 0, 14),
      catEntry('Wolfs', 2024, 'Movie', 'R', 108, ['Action', 'Comedy', 'Crime'], 'Two rival professional fixers are forced to work together when they are both hired to cover up the same high-profile crime.', 0, 15),
      catEntry('Blitz', 2024, 'Movie', 'PG-13', 120, ['Drama', 'History', 'War'], 'In World War II London, nine-year-old George embarks on a perilous journey back home during the German Blitz.', 0, 0),
      catEntry('The Instigators', 2024, 'Movie', 'R', 101, ['Action', 'Comedy', 'Crime'], 'A desperate father and an ex-con are thrown together to rob a corrupt politician, only for the heist to go sideways.', 0, 0),
      catEntry('Fly Me to the Moon', 2024, 'Movie', 'PG-13', 132, ['Comedy', 'Romance'], 'A sharp marketing maven is brought in to fix NASA\'s public image and stage a backup Apollo 11 moon landing.', 0, 0),
      catEntry('Napoleon', 2023, 'Movie', 'R', 158, ['Action', 'Biography', 'Drama'], 'An epic look at Napoleon Bonaparte\'s origins and his swift, ruthless climb to emperor.', 0, 0),
      catEntry('Argylle', 2024, 'Movie', 'PG-13', 139, ['Action', 'Comedy', 'Thriller'], 'A reclusive author who writes espionage novels about a secret agent realizes the plot of the new book she is writing is mirroring real-world covert actions.', 0, 0),
      catEntry('The Family Plan', 2023, 'Movie', 'PG-13', 118, ['Action', 'Comedy'], 'A suburban dad must take his unsuspecting family on the run when his past as an elite government assassin catches up to him.', 0, 0),
      catEntry('Ghosted', 2023, 'Movie', 'PG-13', 116, ['Action', 'Adventure', 'Comedy'], 'Cole falls head over heels for enigmatic Sadie, but then makes the shocking discovery that she is a secret agent.', 0, 0),
      catEntry('Tetris', 2023, 'Movie', 'R', 118, ['Biography', 'Drama', 'Thriller'], 'The story of how one of the world\'s most popular video games found its way to avid players around the globe during the Cold War.', 0, 0),
      catEntry('Finch', 2021, 'Movie', 'PG-13', 115, ['Adventure', 'Drama', 'Sci-Fi'], 'On a post-apocalyptic Earth, a robot built to protect the life of his dying creator\'s beloved dog learns what it means to be human.', 0, 0),
      catEntry('Spirited', 2022, 'Movie', 'PG-13', 127, ['Comedy', 'Family', 'Musical'], 'A musical version of Charles Dickens\'s story of a miserly misanthrope who is taken on a magical journey.', 0, 0),
      catEntry('Emancipation', 2022, 'Movie', 'R', 132, ['Action', 'Drama', 'History'], 'A runaway slave forges through the swamps of Louisiana on a tortuous journey to escape plantation owners.', 0, 0),
      catEntry('Luck', 2022, 'Movie', 'PG', 105, ['Animation', 'Adventure', 'Comedy'], 'Suddenly finding herself in the never-before-seen Land of Luck, the unluckiest person in the world must unite with the magical creatures there to turn her luck around.', 0, 0),
      catEntry('Palmer', 2021, 'Movie', 'R', 110, ['Drama'], 'An ex-convict strikes up a friendship with a boy from a troubled home.', 0, 0),
      catEntry('Sharper', 2023, 'Movie', 'R', 116, ['Crime', 'Drama', 'Thriller'], 'Motivations are suspect, and expectations are turned upside down, as a con artist takes on Manhattan billionaires.', 0, 0),
      catEntry('Shrinking', 2023, 'Series', 'TV-MA', 36, ['Comedy', 'Drama'], 'A grieving therapist starts to break the rules and tell his clients exactly what he thinks.', 0, 0, 2023),
      catEntry('Bad Monkey', 2024, 'Series', 'TV-MA', 50, ['Comedy', 'Crime', 'Drama'], 'A detective turned restaurant inspector in Southern Florida is pulled into a world of greed and corruption.', 0, 0, 2024),
      catEntry('Disclaimer', 2024, 'Series', 'TV-MA', 54, ['Drama', 'Thriller'], 'A celebrated journalist discovers she is the central character in a novel that threatens to expose her darkest secret.', 0, 0, 2024),
      catEntry('Masters of the Air', 2024, 'Series', 'TV-MA', 58, ['Action', 'Drama', 'War'], 'During World War II, airmen of the 100th Bomb Group risk their lives conducting bombing raids over Nazi Germany.', 0, 0, 2024),
      catEntry('Monarch: Legacy of Monsters', 2023, 'Series', 'TV-14', 50, ['Action', 'Adventure', 'Sci-Fi'], 'After surviving Godzilla\'s attack on San Francisco, Cate is shaken by a shocking secret about her family.', 0, 0, 2023),
      catEntry('Pachinko', 2022, 'Series', 'TV-MA', 55, ['Drama', 'History'], 'The hopes and dreams of a Korean immigrant family across four generations.', 0, 0, 2022),
      catEntry('Hijack', 2023, 'Series', 'TV-MA', 48, ['Drama', 'Thriller'], 'When Flight KA29 is hijacked during its seven-hour journey from Dubai to London, Sam Nelson tries to save everyone on board.', 0, 0, 2023),
      catEntry('Black Bird', 2022, 'Series', 'TV-MA', 56, ['Biography', 'Crime', 'Drama'], 'Jimmy Keene is offered a deal to befriend a suspected serial killer in maximum security.', 0, 0, 2022)
    ],
    netflix: [
      // Netflix Current 2025-2026 Trending + Movies & Series (mirrors Apple TV+ structure)
      catEntry('Wake Up Dead Man: A Knives Out Mystery', 2025, 'Movie', 'PG-13', 140, ['Mystery', 'Crime', 'Drama'], 'Detective Benoit Blanc investigates his most dangerous and personal impossible crime inside a secluded parish.', 1, 7),
      catEntry('The Electric State', 2025, 'Movie', 'PG-13', 128, ['Sci-Fi', 'Adventure', 'Action'], 'An orphaned teenager traverses the American West with a sweet-natured robot and an eccentric drifter in search of her younger brother.', 2, 0),
      catEntry('Havoc', 2025, 'Movie', 'R', 115, ['Action', 'Crime', 'Thriller'], 'After a drug deal gone wrong, a bruised detective must fight his way through a criminal underworld to rescue a politician\'s estranged son.', 3, 0),
      catEntry('Frankenstein', 2025, 'Movie', 'R', 138, ['Drama', 'Horror', 'Sci-Fi'], 'Guillermo del Toro\'s adaptation of Mary Shelley\'s classic tale of Victor Frankenstein and his monstrous creation.', 4, 0),
      catEntry('Happy Gilmore 2', 2025, 'Movie', 'PG-13', 112, ['Comedy', 'Sport'], 'Happy Gilmore returns to the golf course to compete against a new generation of powerhouse players.', 5, 0),
      catEntry('RIP', 2025, 'Movie', 'R', 122, ['Action', 'Crime', 'Thriller'], 'A team of Miami police officers discovers millions of dollars in cash inside a derelict stash house, fraying trust among the squad.', 6, 0),
      catEntry('Adolescence', 2025, 'Series', 'TV-MA', 54, ['Crime', 'Drama', 'Thriller'], 'When a 13-year-old is accused of the murder of a classmate, his family, therapist, and the detective in charge are left asking what really happened.', 7, 0, 2025),
      catEntry('Zero Day', 2025, 'Series', 'TV-MA', 52, ['Drama', 'Thriller'], 'A former U.S. President is called out of retirement to find the source of a deadly nationwide cyberattack, only to discover a vast web of lies.', 8, 0, 2025),
      catEntry('American Primeval', 2025, 'Series', 'TV-MA', 56, ['Action', 'Drama', 'Western'], 'A mother and son fleeing from their past form a found family while confronting a harsh landscape of freedom and cruelty in the American West.', 9, 0, 2025),
      catEntry('Running Point', 2025, 'Series', 'TV-MA', 34, ['Comedy', 'Sport'], 'A reformed party girl must prove herself as a businesswoman when she is unexpectedly put in charge of her family\'s pro basketball team.', 10, 0, 2025),
      catEntry('The Residence', 2025, 'Series', 'TV-MA', 50, ['Comedy', 'Crime', 'Mystery'], 'A brilliant, eccentric detective must solve a murder inside the White House residence during a high-stakes state dinner.', 11, 0, 2025),
      catEntry('Back in Action', 2025, 'Movie', 'PG-13', 114, ['Action', 'Comedy'], 'Years after giving up life as CIA spies to start a family, Emily and Matt are dragged back into the world of espionage when their cover is blown.', 12, 0),
      catEntry('Dept. Q', 2025, 'Series', 'TV-MA', 55, ['Crime', 'Drama', 'Mystery'], 'A brash but brilliant detective is assigned to head a new cold-case department in Edinburgh from a basement office.', 13, 0, 2025),
      catEntry('Sirens', 2025, 'Series', 'TV-MA', 48, ['Comedy', 'Drama'], 'Over one explosive weekend at a lavish beach estate, a woman grows suspicious of her sister\'s cult-like billionaire boss.', 14, 0, 2025),
      catEntry('Dorothy', 2026, 'Movie', 'PG-13', 118, ['Drama', 'Mystery'], 'A contemporary mystery unfolding across a storm-swept coastal town.', 15, 0),
      catEntry('Below', 2026, 'Movie', 'R', 109, ['Thriller', 'Sci-Fi'], 'A deep-sea salvage crew encounters an uncharted signal beneath the ocean floor.', 16, 0),
      // Netflix Popular Classics & Movies/Series Catalog
      catEntry('Stranger Things', 2016, 'Series', 'TV-14', 60, ['Sci-Fi', 'Thriller', 'Drama'], 'In a small Indiana town, a group of young friends witness supernatural forces and secret government exploits.', 0, 1, 2016),
      catEntry('Squid Game', 2021, 'Series', 'TV-MA', 58, ['Thriller', 'Drama'], 'Hundreds of cash-strapped players accept a strange invitation to compete in children\'s games for a tempting prize with deadly high stakes.', 0, 2, 2021),
      catEntry('Wednesday', 2022, 'Series', 'TV-14', 50, ['Comedy', 'Crime', 'Fantasy'], 'Wednesday Addams investigates a monstrous killing spree while mastering her emerging psychic ability at Nevermore Academy.', 0, 3, 2022),
      catEntry('Money Heist', 2017, 'Series', 'TV-MA', 50, ['Action', 'Crime', 'Drama'], 'An unusual group of robbers attempt to carry out the most perfect robbery in Spanish history.', 0, 4, 2017),
      catEntry('Black Mirror', 2011, 'Series', 'TV-MA', 64, ['Sci-Fi', 'Drama', 'Thriller'], 'An anthology series exploring a twisted, high-tech multiverse where humanity\'s greatest innovations and darkest instincts collide.', 0, 5, 2011),
      catEntry('The Night Agent', 2023, 'Series', 'TV-MA', 52, ['Action', 'Drama', 'Thriller'], 'Low-level FBI agent Peter Sutherland is catapulted into a vast conspiracy involving a mole at the highest levels of government.', 0, 6, 2023),
      catEntry('One Piece', 2023, 'Series', 'TV-14', 55, ['Action', 'Adventure', 'Fantasy'], 'In a seafaring world, a young pirate captain sets out with his crew to attain the title of Pirate King.', 0, 8, 2023),
      catEntry('Carry-On', 2024, 'Movie', 'PG-13', 119, ['Action', 'Thriller'], 'A young TSA agent fights to outsmart a mysterious traveler who blackmails him into letting a dangerous package slip onto a Christmas Eve flight.', 0, 9),
      catEntry('Rebel Ridge', 2024, 'Movie', 'TV-MA', 131, ['Action', 'Crime', 'Thriller'], 'An ex-Marine grapples his way through a web of small-town corruption.', 0, 10),
      catEntry('Glass Onion', 2022, 'Movie', 'PG-13', 139, ['Comedy', 'Crime', 'Mystery'], 'Tech billionaire Miles Bron invites his friends for a getaway on his private Greek island.', 0, 11),
      catEntry('Extraction 2', 2023, 'Movie', 'R', 122, ['Action', 'Thriller'], 'Back from the brink of death, commando Tyler Rake embarks on a dangerous mission.', 0, 12),
      catEntry('Arcane', 2021, 'Series', 'TV-14', 44, ['Animation', 'Action', 'Adventure'], 'Set in Utopian Piltover and the oppressed underground of Zaun, the story follows the origins of two iconic League champions.', 0, 13, 2021),
      catEntry('Dark', 2017, 'Series', 'TV-MA', 60, ['Crime', 'Drama', 'Mystery'], 'A family saga with a supernatural twist, set in a German town.', 0, 14, 2017),
      catEntry('The Crown', 2016, 'Series', 'TV-MA', 58, ['Biography', 'Drama', 'History'], 'Follows the political rivalries and romance of Queen Elizabeth II\'s reign.', 0, 15, 2016),
      catEntry('Hit Man', 2024, 'Movie', 'R', 115, ['Comedy', 'Crime', 'Romance'], 'A professor moonlighting as a fake hit man for his city police department descends into dangerous territory.', 0, 0),
      catEntry('Society of the Snow', 2024, 'Movie', 'R', 144, ['Adventure', 'Biography', 'Drama'], 'The flight of a rugby team crashes on a glacier in the Andes.', 0, 0),
      catEntry('Damsel', 2024, 'Movie', 'PG-13', 110, ['Action', 'Adventure', 'Fantasy'], 'A dutiful damsel agrees to marry a handsome prince, only to find the royal family has recruited her as a sacrifice.', 0, 0),
      catEntry('Beverly Hills Cop: Axel F', 2024, 'Movie', 'R', 118, ['Action', 'Comedy', 'Crime'], 'Detective Axel Foley returns to the beat in Beverly Hills.', 0, 0),
      catEntry('The Six Triple Eight', 2024, 'Movie', 'PG-13', 127, ['Drama', 'History', 'War'], 'During World War II, the only US Women\'s Army Corps unit of color stationed overseas takes on an impossible mission.', 0, 0),
      catEntry('Woman of the Hour', 2024, 'Movie', 'R', 95, ['Crime', 'Drama', 'Thriller'], 'An aspiring actress in 1970s Los Angeles crosses paths with a prolific serial killer on a televised dating game show.', 0, 0),
      catEntry('The Killer', 2023, 'Movie', 'R', 118, ['Action', 'Adventure', 'Crime'], 'After a fateful near-miss, an assassin battles his employers, and himself, on an international manhunt.', 0, 0),
      catEntry('Leave the World Behind', 2023, 'Movie', 'R', 141, ['Drama', 'Mystery', 'Thriller'], 'A family\'s getaway to a luxurious rental home takes an ominous turn when a cyberattack knocks out their devices.', 0, 0),
      catEntry('All Quiet on the Western Front', 2022, 'Movie', 'R', 148, ['Action', 'Drama', 'War'], 'A young German soldier\'s terrifying experiences and distress on the western front during World War I.', 0, 0),
      catEntry('Guillermo del Toro\'s Pinocchio', 2022, 'Movie', 'PG', 117, ['Animation', 'Drama', 'Family'], 'A father\'s wish magically brings a wooden boy to life in Italy.', 0, 0),
      catEntry('Enola Holmes 2', 2022, 'Movie', 'PG-13', 129, ['Adventure', 'Crime', 'Mystery'], 'Enola Holmes takes on her first official case to find a missing girl.', 0, 0),
      catEntry('3 Body Problem', 2024, 'Series', 'TV-MA', 56, ['Adventure', 'Drama', 'Sci-Fi'], 'A fateful decision in 1960s China echoes across space and time to a group of scientists in the present day.', 0, 0, 2024),
      catEntry('The Gentlemen', 2024, 'Series', 'TV-MA', 50, ['Action', 'Comedy', 'Crime'], 'When aristocratic Eddie inherits the family estate, he discovers that it\'s home to a huge weed empire.', 0, 0, 2024),
      catEntry('Nobody Wants This', 2024, 'Series', 'TV-MA', 30, ['Comedy', 'Romance'], 'An agnostic podcast host and an unconventional rabbi on the rebound walk into a party and walk out together.', 0, 0, 2024),
      catEntry('The Diplomat', 2023, 'Series', 'TV-MA', 50, ['Drama', 'Thriller'], 'In the midst of an international crisis, a career diplomat lands in a high-profile job.', 0, 0, 2023),
      catEntry('Beef', 2023, 'Series', 'TV-MA', 38, ['Comedy', 'Drama'], 'Two people let a road rage incident burrow into their minds.', 0, 0, 2023),
      catEntry('Blue Eye Samurai', 2023, 'Series', 'TV-MA', 48, ['Animation', 'Action', 'Drama'], 'Driven by a dream of revenge in Edo-period Japan, a young warrior cuts a bloody path toward her destiny.', 0, 0, 2023),
      catEntry('Lupin', 2021, 'Series', 'TV-MA', 46, ['Action', 'Crime', 'Drama'], 'Inspired by the adventures of Arsène Lupin, gentleman thief Assane Diop sets out to avenge his father.', 0, 0, 2021),
      catEntry('Ozark', 2017, 'Series', 'TV-MA', 60, ['Crime', 'Drama', 'Thriller'], 'A financial advisor drags his family from Chicago to the Missouri Ozarks.', 0, 0, 2017)
    ],
    prime: [
      // Prime Video Current 2025-2026 Trending + Movies & Series (mirrors Apple TV+ structure)
      catEntry('The Accountant 2', 2025, 'Movie', 'R', 128, ['Action', 'Crime', 'Thriller'], 'Christian Wolff applies his brilliant mind and illegal methods to reconstruct the unsolved puzzle of a Treasury chief\'s murder.', 1, 5),
      catEntry('Spider-Noir', 2026, 'Series', 'TV-MA', 50, ['Action', 'Crime', 'Drama'], 'An aging down-on-his-luck private investigator in 1930s New York grapples with his past life as the city\'s only superhero.', 2, 0, 2026),
      catEntry('Carrie', 2026, 'Series', 'TV-MA', 54, ['Horror', 'Drama', 'Thriller'], 'A bold reimagining of Stephen King\'s classic tale of telekinetic teenager Carrie White.', 3, 0, 2026),
      catEntry('Heads of State', 2025, 'Movie', 'PG-13', 118, ['Action', 'Comedy', 'Thriller'], 'The UK Prime Minister and US President must set aside their rivalry to thwart a global conspiracy after Air Force One is shot down.', 4, 0),
      catEntry('Another Simple Favor', 2025, 'Movie', 'R', 120, ['Comedy', 'Crime', 'Mystery'], 'Stephanie Smothers and Emily Nelson reunite on the beautiful island of Capri, Italy, for Emily\'s extravagant wedding to a wealthy Italian businessman.', 5, 0),
      catEntry('The Rainmaker', 2025, 'Series', 'TV-MA', 52, ['Crime', 'Drama', 'Thriller'], 'Fresh out of law school, Rudy Baylor goes head-to-head with a courtroom lion and uncovers two connected conspiracies surrounding a young man\'s death.', 6, 0, 2025),
      catEntry('Off Campus', 2026, 'Series', 'TV-MA', 50, ['Drama', 'Romance', 'Sport'], 'Elite college ice hockey players navigate rivalry, pressure, and romance off the rink.', 7, 0, 2026),
      catEntry('G20', 2025, 'Movie', 'R', 110, ['Action', 'Thriller'], 'When the G20 summit comes under siege in Cape Town, U.S. President Danielle Sutton becomes the number one target and must outsmart the enemy to protect her family.', 8, 0),
      catEntry('Holland', 2025, 'Movie', 'R', 108, ['Mystery', 'Thriller'], 'A teacher in a quaint Midwestern town suspects her husband of living a double life, only to uncover a far darker secret.', 9, 0),
      catEntry('On Call', 2025, 'Series', 'TV-MA', 30, ['Action', 'Crime', 'Drama'], 'A veteran training officer and her rookie partner respond to high-adrenaline emergency calls on the streets of Long Beach, California.', 10, 0, 2025),
      catEntry('The Bondsman', 2025, 'Series', 'TV-MA', 35, ['Action', 'Horror', 'Thriller'], 'A murdered bounty hunter is resurrected by the Devil to trap and send back demons that have escaped from the prison of Hell.', 11, 0, 2025),
      catEntry('Butterfly', 2025, 'Series', 'TV-MA', 50, ['Action', 'Drama', 'Thriller'], 'An enigmatic former US intelligence operative living in South Korea has his life blown to pieces when the consequences of a past decision catch up with him.', 12, 0, 2025),
      catEntry('You\'re Cordially Invited', 2025, 'Movie', 'R', 109, ['Comedy'], 'When two weddings are accidentally double-booked at a remote island venue, the bride\'s father and the other bride\'s sister go head-to-head.', 13, 0),
      catEntry('A Love Other Than Yours', 2026, 'Series', 'TV-14', 48, ['Drama', 'Romance'], 'Two strangers bound by an unexpected promise cross paths again years later.', 14, 0, 2026),
      catEntry('Love Of Your Life', 2026, 'Movie', 'R', 116, ['Drama', 'Romance'], 'A young woman embarks on a transformative journey after losing the love of her life.', 15, 0),
      catEntry('Masterplan', 2026, 'Movie', 'PG-13', 122, ['Action', 'Crime', 'Thriller'], 'A legendary safecracker assembles an international crew for an impossible vault heist in Paris.', 16, 0),
      // Prime Video Popular Classics & Movies/Series Catalog
      catEntry('Reacher', 2022, 'Series', 'TV-MA', 52, ['Action', 'Crime', 'Thriller'], 'Itinerant former military policeman Jack Reacher solves crimes and metes out his own brand of street justice.', 0, 1, 2022),
      catEntry('Fallout', 2024, 'Series', 'TV-MA', 60, ['Sci-Fi', 'Action', 'Adventure'], 'In a future, post-apocalyptic Los Angeles brought about by nuclear decimation, citizens must live in underground bunkers to protect themselves.', 0, 2, 2024),
      catEntry('The Boys', 2019, 'Series', 'TV-MA', 62, ['Action', 'Sci-Fi', 'Drama'], 'A group of vigilantes set out to take down corrupt superheroes who abuse their superpowers.', 0, 3, 2019),
      catEntry('Invincible', 2021, 'Series', 'TV-MA', 48, ['Animation', 'Action', 'Adventure'], 'Mark Grayson inherits his father\'s superpowers and must defend Earth against the Viltrumite Empire.', 0, 4, 2021),
      catEntry('The Family Man', 2019, 'Series', 'TV-MA', 50, ['Action', 'Comedy', 'Drama'], 'A working man from the National Investigation Agency tries to protect the nation from terrorism while managing middle-class family life.', 0, 6, 2019),
      catEntry('Road House', 2024, 'Movie', 'R', 121, ['Action', 'Thriller'], 'Ex-UFC fighter Dalton takes a job as a bouncer at a Florida Keys roadhouse.', 0, 7),
      catEntry('Saltburn', 2023, 'Movie', 'R', 131, ['Comedy', 'Drama', 'Thriller'], 'A student at Oxford University finds himself drawn into the world of a charming and aristocratic classmate.', 0, 8),
      catEntry('Air', 2023, 'Movie', 'R', 111, ['Drama', 'Sport'], 'Follows the history of shoe salesman Sonny Vaccaro and Nike\'s pursuit of Michael Jordan.', 0, 9),
      catEntry('The Covenant', 2023, 'Movie', 'R', 123, ['Action', 'Drama', 'War'], 'During the war in Afghanistan, a local interpreter risks his own life to carry an injured sergeant across grueling terrain.', 0, 10),
      catEntry('Sound of Metal', 2020, 'Movie', 'R', 120, ['Drama', 'Music'], 'A heavy-metal drummer\'s life is thrown into freefall when he begins to lose his hearing.', 0, 11),
      catEntry('The Tomorrow War', 2021, 'Movie', 'PG-13', 138, ['Action', 'Adventure', 'Sci-Fi'], 'A family man is drafted to fight in a future war where the fate of humanity relies on his ability to confront the past.', 0, 12),
      catEntry('The Lord of the Rings: The Rings of Power', 2022, 'Series', 'TV-14', 65, ['Action', 'Adventure', 'Drama'], 'Epic drama set thousands of years before the events of J.R.R. Tolkien\'s The Hobbit and The Lord of the Rings.', 0, 13, 2022),
      catEntry('Mirzapur', 2018, 'Series', 'TV-MA', 55, ['Action', 'Crime', 'Drama'], 'A shocking incident at a wedding procession ignites a series of events entangling the lives of two families in Mirzapur.', 0, 14, 2018),
      catEntry('Panchayat', 2020, 'Series', 'TV-14', 40, ['Comedy', 'Drama'], 'An engineering graduate joins as secretary of a Panchayat office in a remote village.', 0, 15, 2020),
      catEntry('The Idea of You', 2024, 'Movie', 'R', 115, ['Comedy', 'Drama', 'Romance'], 'Solène begins an unexpected romance with Hayes Campbell.', 0, 0),
      catEntry('Unstoppable', 2024, 'Movie', 'PG-13', 116, ['Biography', 'Drama', 'Sport'], 'The inspiring true story of Anthony Robles, who was born with one leg and won a national wrestling championship.', 0, 0),
      catEntry('My Old Ass', 2024, 'Movie', 'R', 89, ['Comedy', 'Drama', 'Romance'], 'An 18-year-old\'s mushroom trip brings her face-to-face with her wisecracking 39-year-old future self.', 0, 0),
      catEntry('Red One', 2024, 'Movie', 'PG-13', 123, ['Action', 'Adventure', 'Comedy'], 'After Santa Claus is kidnapped, the North Pole\'s Head of Security must team up with the world\'s most infamous bounty hunter.', 0, 0),
      catEntry('Challengers', 2024, 'Movie', 'R', 131, ['Drama', 'Romance', 'Sport'], 'Tashi, a former tennis prodigy turned coach, transforms her husband into a world-famous grand slam champion.', 0, 0),
      catEntry('Thirteen Lives', 2022, 'Movie', 'PG-13', 147, ['Action', 'Biography', 'Drama'], 'A rescue mission is assembled in Thailand where a group of young boys and their soccer coach are trapped in underground caves.', 0, 0),
      catEntry('A Million Miles Away', 2023, 'Movie', 'PG', 121, ['Biography', 'Drama'], 'A migrant farmworker, José Hernández, embarks on a decades-long journey to become a NASA astronaut.', 0, 0),
      catEntry('One Night in Miami', 2020, 'Movie', 'R', 114, ['Drama'], 'Icons Muhammad Ali, Malcolm X, Sam Cooke, and Jim Brown gather to discuss their roles in the Civil Rights Movement.', 0, 0),
      catEntry('Samaritan', 2022, 'Movie', 'PG-13', 102, ['Action', 'Drama', 'Fantasy'], 'A young boy learns that a superhero who was thought to have gone missing twenty years ago may still be around.', 0, 0),
      catEntry('Gen V', 2023, 'Series', 'TV-MA', 50, ['Action', 'Adventure', 'Comedy'], 'From the world of The Boys comes Gen V, exploring the first generation of superheroes to know about Compound V.', 0, 0, 2023),
      catEntry('Mr. & Mrs. Smith', 2024, 'Series', 'TV-MA', 50, ['Action', 'Comedy', 'Crime'], 'Two lonely strangers land jobs working for a mysterious spy agency.', 0, 0, 2024),
      catEntry('Cross', 2024, 'Series', 'TV-MA', 54, ['Action', 'Crime', 'Mystery'], 'Alex Cross is a decorated D.C. homicide detective and forensic psychologist who digs into the psyches of killers.', 0, 0, 2024),
      catEntry('The Terminal List', 2022, 'Series', 'TV-MA', 55, ['Action', 'Drama', 'Thriller'], 'A former Navy SEAL officer investigates why his entire platoon was ambushed during a high-stakes covert mission.', 0, 0, 2022),
      catEntry('The Wheel of Time', 2021, 'Series', 'TV-14', 58, ['Action', 'Adventure', 'Drama'], 'Set in a high fantasy world where magic exists, but only some can access it, a woman crosses paths with five young men and women.', 0, 0, 2021),
      catEntry('Jack Ryan', 2018, 'Series', 'TV-MA', 52, ['Action', 'Drama', 'Thriller'], 'Up-and-coming CIA analyst Jack Ryan is thrust into dangerous field assignments.', 0, 0, 2018),
      catEntry('The Marvelous Mrs. Maisel', 2017, 'Series', 'TV-MA', 56, ['Comedy', 'Drama'], 'A housewife in 1958 decides to become a stand-up comic.', 0, 0, 2017),
      catEntry('Fleabag', 2016, 'Series', 'TV-MA', 27, ['Comedy', 'Drama'], 'A young woman tries to cope with life in London whilst coming to terms with a recent tragedy.', 0, 0, 2016)
    ],
    hbo: [
      // HBO / Max Current 2025-2026 Trending + Movies & Series (mirrors Apple TV+ structure)
      catEntry('Lanterns', 2026, 'Series', 'TV-MA', 58, ['Action', 'Mystery', 'Sci-Fi'], 'Hal Jordan and John Stewart investigate a terrestrial murder in the American heartland that leads to a cosmic conspiracy.', 1, 0, 2026),
      catEntry('A Knight of the Seven Kingdoms', 2026, 'Series', 'TV-MA', 55, ['Fantasy', 'Adventure', 'Drama'], 'Ser Duncan the Tall and his young squire Egg wander Westeros a century before the War of the Five Kings.', 2, 0, 2026),
      catEntry('Sinners', 2025, 'Movie', 'R', 137, ['Drama', 'Horror', 'Thriller'], 'Twin brothers return to their 1930s Mississippi hometown looking to start over, only to discover an even greater evil waiting in the shadows.', 3, 0),
      catEntry('Mickey 17', 2025, 'Movie', 'R', 139, ['Adventure', 'Comedy', 'Sci-Fi'], 'An expendable employee on a human expedition sent to colonize the ice world Niflheim refuses to let his replacement clone take his place.', 4, 0),
      catEntry('The Pitt', 2025, 'Series', 'TV-MA', 52, ['Drama'], 'A realistic examination of the challenges facing healthcare workers in today\'s America as seen through the lens of frontline heroes at a Pittsburgh hospital.', 5, 0, 2025),
      catEntry('Superman', 2025, 'Movie', 'PG-13', 142, ['Action', 'Adventure', 'Sci-Fi'], 'Clark Kent reconciles his Kryptonian heritage with his human upbringing in Smallville while standing as a beacon of hope in a cynical world.', 6, 0),
      catEntry('IT: Welcome to Derry', 2025, 'Series', 'TV-MA', 56, ['Drama', 'Horror', 'Mystery'], 'Set in the 1960s in the run-up to the events of Stephen King\'s IT, uncovering the origins of the curse haunting Derry, Maine.', 7, 0, 2025),
      catEntry('Task', 2025, 'Series', 'TV-MA', 58, ['Crime', 'Drama'], 'An FBI agent heads a task force in the working-class suburbs of Philadelphia to put an end to a string of violent robberies led by an unsuspecting family man.', 8, 0, 2025),
      catEntry('Companion', 2025, 'Movie', 'R', 97, ['Sci-Fi', 'Thriller'], 'A weekend getaway with friends at a remote lakeside cabin descends into chaos after it is revealed that one of the guests is not what they seem.', 9, 0),
      catEntry('Duster', 2025, 'Series', 'TV-MA', 52, ['Action', 'Crime', 'Drama'], 'In the 1972 Southwest, the first Black female FBI agent heads to the region and recruits a gutsy getaway driver in a bold effort to take down a growing crime syndicate.', 10, 0, 2025),
      catEntry('Alto Knights', 2025, 'Movie', 'R', 123, ['Biography', 'Crime', 'Drama'], 'Two of New York\'s most notorious organized crime bosses, Frank Costello and Vito Genovese, vie for control of the city\'s streets.', 11, 0),
      catEntry('Final Destination: Bloodlines', 2025, 'Movie', 'R', 110, ['Horror', 'Thriller'], 'Plagued by a violent recurring nightmare, a college student heads home to track down the one person who might be able to break the cycle and save her family.', 12, 0),
      catEntry('Weapons', 2025, 'Movie', 'R', 128, ['Horror', 'Mystery', 'Thriller'], 'An interrelated, multistory horror epic about the disappearance of high school students in a small town.', 13, 0),
      catEntry('The Bride!', 2026, 'Movie', 'R', 126, ['Drama', 'Horror', 'Sci-Fi'], 'In 1930s Chicago, Frankenstein asks Dr. Euphronius to help create a companion, sparking a volatile romance and a radical social movement.', 14, 0),
      catEntry('Peacemaker', 2025, 'Series', 'TV-MA', 46, ['Action', 'Adventure', 'Comedy'], 'Christopher Smith continues his mission to achieve peace at any cost, no matter how many people he has to kill to get it.', 15, 0, 2022),
      catEntry('Dune: Prophecy', 2025, 'Series', 'TV-MA', 60, ['Action', 'Adventure', 'Sci-Fi'], 'Ten thousand years before the ascension of Paul Atreides, two Harkonnen sisters combat forces that threaten the future of humankind and establish the fabled Bene Gesserit.', 16, 0, 2024),
      // HBO / Max Popular Classics & Movies/Series Catalog
      catEntry('The Last of Us', 2023, 'Series', 'TV-MA', 60, ['Drama', 'Action', 'Sci-Fi'], 'After a global pandemic destroys civilization, a hardened survivor takes charge of a 14-year-old girl who may be humanity\'s last hope.', 0, 1, 2023),
      catEntry('House of the Dragon', 2022, 'Series', 'TV-MA', 66, ['Drama', 'Fantasy', 'Action'], 'An internal succession war within House Targaryen at the height of its power, 172 years before the birth of Daenerys Targaryen.', 0, 2, 2022),
      catEntry('Dune: Part Two', 2024, 'Movie', 'PG-13', 166, ['Action', 'Adventure', 'Sci-Fi'], 'Paul Atreides unites with Chani and the Fremen while on a warpath of revenge.', 0, 3),
      catEntry('The Batman', 2022, 'Movie', 'PG-13', 176, ['Action', 'Crime', 'Drama'], 'When a sadistic serial killer begins murdering key political figures in Gotham, Batman investigates the city\'s hidden corruption.', 0, 4),
      catEntry('The White Lotus', 2021, 'Series', 'TV-MA', 60, ['Comedy', 'Drama'], 'The exploits of various guests and employees of a luxury resort chain over the span of a week.', 0, 5, 2021),
      catEntry('The Penguin', 2024, 'Series', 'TV-MA', 58, ['Crime', 'Drama'], 'Following the events of The Batman, Oswald Cobblepot makes a play to seize the reins of the crime world in Gotham.', 0, 6, 2024),
      catEntry('Barbie', 2023, 'Movie', 'PG-13', 114, ['Adventure', 'Comedy', 'Fantasy'], 'Barbie and Ken are having the time of their lives in the colorful and seemingly perfect world of Barbie Land.', 0, 7),
      catEntry('Succession', 2018, 'Series', 'TV-MA', 60, ['Drama'], 'The Roy family controls the biggest media and entertainment company in the world.', 0, 8, 2018),
      catEntry('True Detective', 2014, 'Series', 'TV-MA', 60, ['Crime', 'Drama', 'Mystery'], 'Anthology series in which police investigations unearth the personal and professional secrets of those involved.', 0, 9, 2014),
      catEntry('Chernobyl', 2019, 'Series', 'TV-MA', 65, ['Drama', 'History', 'Thriller'], 'In April 1986, an explosion at the Chernobyl nuclear power plant becomes one of the world\'s worst man-made catastrophes.', 0, 10, 2019),
      catEntry('The Sopranos', 1999, 'Series', 'TV-MA', 55, ['Crime', 'Drama'], 'New Jersey mob boss Tony Soprano deals with personal and professional issues.', 0, 11, 1999),
      catEntry('Game of Thrones', 2011, 'Series', 'TV-MA', 58, ['Action', 'Adventure', 'Drama'], 'Nine noble families fight for control over the lands of Westeros, while an ancient enemy returns after being dormant for millennia.', 0, 12, 2011),
      catEntry('Euphoria', 2019, 'Series', 'TV-MA', 55, ['Drama'], 'A look at life for a group of high school students as they grapple with issues of drugs, sex, and violence.', 0, 13, 2019),
      catEntry('The Wire', 2002, 'Series', 'TV-MA', 60, ['Crime', 'Drama', 'Thriller'], 'The Baltimore drug scene, as seen through the eyes of drug dealers and law enforcement.', 0, 14, 2002),
      catEntry('Band of Brothers', 2001, 'Series', 'TV-MA', 60, ['Drama', 'History', 'War'], 'The story of Easy Company of the U.S. Army 101st Airborne Division and their mission in World War II Europe.', 0, 15, 2001),
      catEntry('Furiosa: A Mad Max Saga', 2024, 'Movie', 'R', 148, ['Action', 'Adventure', 'Sci-Fi'], 'The origin story of renegade warrior Furiosa before her encounter and teamup with Mad Max.', 0, 0),
      catEntry('Beetlejuice Beetlejuice', 2024, 'Movie', 'PG-13', 105, ['Comedy', 'Fantasy'], 'After a family tragedy, three generations of the Deetz family return home to Winter River.', 0, 0),
      catEntry('Godzilla x Kong: The New Empire', 2024, 'Movie', 'PG-13', 115, ['Action', 'Adventure', 'Sci-Fi'], 'Two ancient titans, Godzilla and Kong, clash in an epic battle.', 0, 0),
      catEntry('Juror #2', 2024, 'Movie', 'PG-13', 114, ['Crime', 'Drama', 'Thriller'], 'While serving as a juror in a high-profile murder trial, a family man finds himself struggling with a serious moral dilemma.', 0, 0),
      catEntry('Caddo Lake', 2024, 'Movie', 'PG-13', 99, ['Drama', 'Mystery', 'Thriller'], 'When an eight-year-old girl mysteriously vanishes, a series of past deaths and disappearances start to link together.', 0, 0),
      catEntry('Love Lies Bleeding', 2024, 'Movie', 'R', 104, ['Action', 'Crime', 'Romance'], 'Reclusive gym manager Lou falls hard for Jackie, an ambitious bodybuilder headed through town to Las Vegas.', 0, 0),
      catEntry('Civil War', 2024, 'Movie', 'R', 109, ['Action', 'Drama', 'Thriller'], 'A journey across a dystopian future America, following a team of military-embedded journalists as they race against time to reach DC.', 0, 0),
      catEntry('Wonka', 2023, 'Movie', 'PG', 116, ['Adventure', 'Comedy', 'Family'], 'A young and poor Willy Wonka discovers that the chocolate industry is run by a cartel of greedy chocolatiers.', 0, 0),
      catEntry('Elvis', 2022, 'Movie', 'PG-13', 159, ['Biography', 'Drama', 'Music'], 'The life of American music icon Elvis Presley.', 0, 0),
      catEntry('King Richard', 2021, 'Movie', 'PG-13', 144, ['Biography', 'Drama', 'Sport'], 'A look at how tennis superstars Venus and Serena Williams became who they are.', 0, 0),
      catEntry('Judas and the Black Messiah', 2021, 'Movie', 'R', 126, ['Biography', 'Drama', 'History'], 'William O\'Neal infiltrates the Illinois chapter of the Black Panther Party.', 0, 0),
      catEntry('Hacks', 2021, 'Series', 'TV-MA', 32, ['Comedy', 'Drama'], 'Explores a dark mentorship between Deborah Vance, a legendary Las Vegas comedian, and an entitled 25-year-old.', 0, 0, 2021),
      catEntry('The Franchise', 2024, 'Series', 'TV-MA', 30, ['Comedy'], 'The crew of an unloved franchise movie fights for their place in a savage and unruly cinematic universe.', 0, 0, 2024),
      catEntry('Industry', 2020, 'Series', 'TV-MA', 52, ['Drama'], 'Young bankers and traders make their way in the financial world in the aftermath of the 2008 collapse.', 0, 0, 2020),
      catEntry('The Night Of', 2016, 'Series', 'TV-MA', 60, ['Crime', 'Drama', 'Mystery'], 'After a night of partying with a woman he picked up, a man wakes up to find her stabbed to death and is charged with her murder.', 0, 0, 2016),
      catEntry('Mare of Easttown', 2021, 'Series', 'TV-MA', 58, ['Crime', 'Drama', 'Mystery'], 'A detective in a small Pennsylvania town investigates a local murder while trying to keep her life from falling apart.', 0, 0, 2021)
    ],
    hoichoi: [
      catEntry('Karagar', 2024, 'Series', 'TV-14', 45, ['Mystery', 'Thriller', 'Drama'], 'A mute prisoner appears inside Cell 145, which has been sealed for fifty years.', 1, 1, 2022),
      catEntry('Mahanagar', 2024, 'Series', 'TV-14', 48, ['Crime', 'Thriller', 'Drama'], 'Over one fateful night inside a Dhaka police station, OC Harun navigates power, bribery, and survival.', 2, 2, 2021),
      catEntry('Taqdeer', 2020, 'Series', 'TV-MA', 42, ['Crime', 'Drama', 'Thriller'], 'A freezer-van driver in Dhaka discovers a dead body inside his truck.', 0, 3, 2020),
      catEntry('Eken Babu', 2024, 'Series', 'TV-14', 40, ['Comedy', 'Mystery'], 'Eccentric Bengali detective Ekendra Sen solves baffling murder mysteries.', 3, 4, 2018),
      catEntry('Byomkesh', 2023, 'Series', 'TV-14', 45, ['Crime', 'Mystery', 'Thriller'], 'Truth-seeker Byomkesh Bakshi investigates complex crimes in colonial Calcutta.', 0, 5, 2017),
      catEntry('Ballabhpurer Roopkotha', 2022, 'Movie', 'PG-13', 136, ['Comedy', 'Horror'], 'The debt-ridden royal scion of a crumbling palace attempts to sell his ancestral property.', 0, 6),
      catEntry('Karnasubarner Guptodhon', 2022, 'Movie', 'PG-13', 138, ['Adventure', 'Mystery'], 'History professor Subarna Sen embarks on a treasure hunt.', 0, 7),
      catEntry('Dasham Avatar', 2023, 'Movie', 'TV-14', 148, ['Crime', 'Thriller'], 'Two cops join forces to track down a serial killer.', 0, 8)
    ],
    imdb: [
      // Top Movies on IMDb Last 30 Days (22 entries)
      catEntry('Spider-Man Brand New Day', 2026, 'Movie', 'PG-13', 142, ['Action', 'Adventure', 'Sci-Fi'], 'Peter Parker faces a new street-level threat in New York while rebuilding his life from scratch.', 1, 1, null, '30d'),
      catEntry('Project Hail Mary', 2026, 'Movie', 'PG-13', 152, ['Sci-Fi', 'Adventure', 'Drama'], 'An astronaut wakes up alone on a spacecraft with amnesia and must use science to save Earth from an extinction-level event.', 2, 2, null, '30d'),
      catEntry('Dhurandhar The Revenge', 2026, 'Movie', 'R', 148, ['Action', 'Thriller'], 'A covert operative returns for a high-stakes mission across international borders.', 3, 0, null, '30d'),
      catEntry('Awarapan 2', 2026, 'Movie', 'TV-14', 136, ['Action', 'Crime', 'Drama'], 'A solitary hitman seeks redemption when he is pulled back into the underworld.', 4, 0, null, '30d'),
      catEntry('Drishyam 3', 2026, 'Movie', 'TV-14', 155, ['Crime', 'Drama', 'Mystery'], 'Georgekutty faces the ultimate test as new evidence threatens to unravel his family\'s secret.', 5, 0, null, '30d'),
      catEntry('Matchbox The Movie', 2026, 'Movie', 'PG-13', 118, ['Action', 'Adventure'], 'High-octane pursuit across continents as an elite stunt driver is framed for a heist.', 6, 0, null, '30d'),
      catEntry('Fall 2 Deadpoint', 2026, 'Movie', 'PG-13', 108, ['Thriller', 'Survival'], 'Two climbers find themselves stranded atop a remote Alpine tower during an incoming storm.', 7, 0, null, '30d'),
      catEntry('Runner', 2026, 'Movie', 'R', 114, ['Action', 'Thriller'], 'A courier with three hours to live races through a locked-down city to deliver a vital package.', 8, 0, null, '30d'),
      catEntry('Khalifa: The Ruler', 2026, 'Movie', 'TV-MA', 146, ['Action', 'Crime', 'Drama'], 'The rise of a syndicate kingpin who reshapes the balance of power along the coast.', 9, 0, null, '30d'),
      catEntry('The Love Hypothesis', 2026, 'Movie', 'PG-13', 112, ['Comedy', 'Romance'], 'A Ph.D. candidate stages a fake relationship with a notoriously demanding young professor.', 10, 0, null, '30d'),
      catEntry('Lust Stories 3', 2026, 'Movie', 'TV-MA', 132, ['Drama', 'Romance'], 'An anthology of four modern stories exploring love, desire, and secrets in contemporary India.', 11, 0, null, '30d'),
      catEntry('Hope', 2026, 'Movie', 'R', 138, ['Sci-Fi', 'Thriller'], 'Residents of a secluded harbor town confront an inexplicable phenomenon at the edge of the forest.', 12, 0, null, '30d'),
      catEntry('Insidious Out Of The Further', 2026, 'Movie', 'PG-13', 109, ['Horror', 'Mystery', 'Thriller'], 'A family unlocks a door to the Further that refuses to close.', 13, 0, null, '30d'),
      catEntry('Man Of War', 2026, 'Movie', 'R', 129, ['Action', 'War', 'Drama'], 'A decorated commander leads a rogue unit behind enemy lines to extract hostages.', 14, 0, null, '30d'),
      catEntry('Obsession', 2026, 'Movie', 'R', 116, ['Mystery', 'Thriller'], 'A restoration architect uncovers a decades-old obsession hidden inside a historic estate.', 15, 0, null, '30d'),
      catEntry('Alpha', 2026, 'Movie', 'PG-13', 134, ['Action', 'Spy', 'Thriller'], 'Two elite field agents partner on a mission that exposes a mole inside their own agency.', 16, 0, null, '30d'),
      catEntry('Vishwanath & Sons', 2026, 'Movie', 'PG-13', 140, ['Drama', 'Family'], 'Three generations of a family business clash over legacy and ambition.', 17, 0, null, '30d'),
      catEntry('Hai Jawani Toh Ishq Hona Hai', 2026, 'Movie', 'PG-13', 131, ['Comedy', 'Romance'], 'A whirlwind summer trip turns into a comedic tangle of mistaken identities.', 18, 0, null, '30d'),
      catEntry('Baby Do Die Do', 2026, 'Movie', 'TV-14', 120, ['Comedy', 'Crime'], 'An ordinary couple accidentally intercepts a suitcase sought by two rival gangs.', 19, 0, null, '30d'),
      catEntry('Bethlehem Kudumba Unit', 2026, 'Movie', 'PG-13', 128, ['Comedy', 'Drama', 'Family'], 'A close-knit neighborhood association bands together when an unexpected crisis hits their street.', 20, 0, null, '30d'),
      catEntry('Modha Rathri', 2026, 'Movie', 'TV-14', 124, ['Mystery', 'Thriller'], 'Over the course of a single rain-soaked night, three strangers cross paths at a highway diner.', 21, 0, null, '30d'),
      catEntry('Dhurandhar', 2025, 'Movie', 'R', 144, ['Action', 'Thriller'], 'An intelligence shadow war unfolds across three capitals.', 22, 0, null, '30d'),

      // Top Shows on IMDb Last 30 Days (22 entries)
      catEntry('Lanterns', 2026, 'Series', 'TV-MA', 58, ['Action', 'Mystery', 'Sci-Fi'], 'Hal Jordan and John Stewart investigate a terrestrial murder in the American heartland that leads to a cosmic conspiracy.', 1, 1, 2026, '30d'),
      catEntry('VisionQuest', 2026, 'Series', 'TV-14', 52, ['Action', 'Sci-Fi', 'Drama'], 'Vision attempts to regain his memory and humanity after the events of Westview.', 2, 0, 2026, '30d'),
      catEntry('A Knight of the Seven Kingdoms', 2026, 'Series', 'TV-MA', 55, ['Fantasy', 'Adventure', 'Drama'], 'Ser Duncan the Tall and his young squire Egg wander Westeros a century before the War of the Five Kings.', 3, 2, 2026, '30d'),
      catEntry('Spider-Noir', 2026, 'Series', 'TV-MA', 50, ['Action', 'Crime', 'Drama'], 'An aging down-on-his-luck private investigator in 1930s New York grapples with his past life as the city\'s only superhero.', 4, 0, 2026, '30d'),
      catEntry('Carrie', 2026, 'Series', 'TV-MA', 54, ['Horror', 'Drama', 'Thriller'], 'A bold reimagining of Stephen King\'s classic tale of telekinetic teenager Carrie White.', 5, 0, 2026, '30d'),
      catEntry('Lucky', 2026, 'Series', 'TV-MA', 52, ['Crime', 'Drama', 'Thriller'], 'A young woman who left behind a life of crime must embrace her darker side one final time.', 6, 0, 2026, '30d'),
      catEntry('MobLand', 2025, 'Series', 'TV-MA', 56, ['Crime', 'Drama'], 'Two warring London crime families collide as their loyal fixer races to keep their empire intact.', 7, 0, 2025, '30d'),
      catEntry('Hunkkaar: The Roar', 2026, 'Series', 'TV-MA', 48, ['Action', 'Crime', 'Drama'], 'A fearless grassroots leader rises against a ruthless mining cartel.', 8, 0, 2026, '30d'),
      catEntry('Chumbak', 2026, 'Series', 'TV-14', 44, ['Comedy', 'Drama'], 'Small-town dreamers pull off an audacious scheme that attracts national attention.', 9, 0, 2026, '30d'),
      catEntry('Musafir Cafe', 2026, 'Series', 'TV-14', 45, ['Drama', 'Romance'], 'Strangers seeking a fresh start cross paths at a quiet hillside cafe in the Himalayas.', 10, 0, 2026, '30d'),
      catEntry('Off Campus', 2026, 'Series', 'TV-MA', 50, ['Drama', 'Romance', 'Sport'], 'Elite college ice hockey players navigate rivalry, pressure, and romance off the rink.', 11, 0, 2026, '30d'),
      catEntry('Raakh', 2026, 'Series', 'TV-MA', 52, ['Crime', 'Thriller'], 'Ashes of an unsolved arson case reignite when a forensic investigator uncovers a cover-up.', 12, 0, 2026, '30d'),
      catEntry('Pritam and Pedro', 2026, 'Series', 'TV-14', 46, ['Action', 'Comedy', 'Crime'], 'Two mismatched detectives team up along the Goa coast to solve high-society crimes.', 13, 0, 2026, '30d'),
      catEntry('Agent Kim Reactivated', 2026, 'Series', 'TV-14', 54, ['Action', 'Comedy', 'Thriller'], 'A retired black-ops operative is pulled out of suburban life when her old unit is compromised.', 14, 0, 2026, '30d'),
      catEntry('Adarsh Baal Vidyalaya', 2026, 'Series', 'TV-14', 40, ['Comedy', 'Drama'], 'Students and teachers at a quirky boarding school prepare for a historic inter-school championship.', 15, 0, 2026, '30d'),
      catEntry('Daredevil: Born Again', 2025, 'Series', 'TV-MA', 54, ['Action', 'Crime', 'Drama'], 'Matt Murdock and Wilson Fisk find themselves on an inevitable collision course in New York.', 16, 0, 2025, '30d'),
      catEntry('Spartacus: House of Ashur', 2025, 'Series', 'TV-MA', 58, ['Action', 'Drama', 'History'], 'An alternate history exploring what might have happened had Ashur survived Mount Vesuvius.', 17, 0, 2025, '30d'),
      catEntry('Pluribus', 2025, 'Series', 'TV-MA', 55, ['Sci-Fi', 'Drama', 'Thriller'], 'Vince Gilligan\'s unsettling sci-fi drama about the most miserable person on Earth tasked with saving the world from happiness.', 18, 0, 2025, '30d'),
      catEntry('The Studio', 2025, 'Series', 'TV-MA', 35, ['Comedy'], 'The newly appointed head of a legacy Hollywood studio scrambles to keep cinema alive.', 19, 0, 2025, '30d'),
      catEntry('Dope Thief', 2025, 'Series', 'TV-MA', 52, ['Crime', 'Drama'], 'Two Philadelphia friends pose as DEA agents to rob a countryside stash house.', 20, 0, 2025, '30d'),
      catEntry('Your Friends & Neighbors', 2025, 'Series', 'TV-MA', 54, ['Drama'], 'A disgraced hedge fund manager resorts to stealing from his wealthy neighbors.', 21, 0, 2025, '30d'),
      catEntry('Murderbot', 2025, 'Series', 'TV-MA', 42, ['Action', 'Sci-Fi'], 'A self-hacking security android must hide its free will while protecting its clients.', 22, 0, 2025, '30d'),

      // Top Movies on IMDb This Year (22 entries)
      catEntry('The Gorge', 2025, 'Movie', 'PG-13', 127, ['Action', 'Sci-Fi', 'Thriller'], 'Two operatives guard opposite sides of a classified gorge hiding an ancient evil.', 1, 0, null, 'year'),
      catEntry('F1', 2025, 'Movie', 'PG-13', 155, ['Action', 'Drama', 'Sport'], 'A veteran Formula One driver returns to the grid to mentor a rookie phenom.', 2, 0, null, 'year'),
      catEntry('Wake Up Dead Man: A Knives Out Mystery', 2025, 'Movie', 'PG-13', 140, ['Mystery', 'Crime', 'Drama'], 'Detective Benoit Blanc investigates his most dangerous impossible crime inside a secluded parish.', 3, 0, null, 'year'),
      catEntry('The Electric State', 2025, 'Movie', 'PG-13', 128, ['Sci-Fi', 'Adventure', 'Action'], 'A teenager and her robot companion cross the retro-futuristic American West.', 4, 0, null, 'year'),
      catEntry('Frankenstein', 2025, 'Movie', 'R', 138, ['Drama', 'Horror', 'Sci-Fi'], 'Guillermo del Toro\'s adaptation of Mary Shelley\'s classic tale.', 5, 0, null, 'year'),
      catEntry('The Accountant 2', 2025, 'Movie', 'R', 128, ['Action', 'Crime', 'Thriller'], 'Christian Wolff applies his brilliant mind to solve the murder of a Treasury chief.', 6, 0, null, 'year'),
      catEntry('Havoc', 2025, 'Movie', 'R', 115, ['Action', 'Crime', 'Thriller'], 'A bruised detective fights through a criminal underworld to rescue a politician\'s estranged son.', 7, 0, null, 'year'),
      catEntry('Fountain of Youth', 2025, 'Movie', 'PG-13', 125, ['Action', 'Adventure', 'Mystery'], 'Two estranged siblings partner on a global heist to find the mythological Fountain of Youth.', 8, 0, null, 'year'),
      catEntry('Highest 2 Lowest', 2025, 'Movie', 'R', 134, ['Crime', 'Drama', 'Thriller'], 'A music mogul is targeted with a ransom plot that forces a life-or-death moral dilemma.', 9, 0, null, 'year'),
      catEntry('Echo Valley', 2025, 'Movie', 'R', 112, ['Drama', 'Thriller'], 'A mother on a quiet Pennsylvania farm faces a crisis when her daughter arrives covered in blood.', 10, 0, null, 'year'),
      catEntry('Happy Gilmore 2', 2025, 'Movie', 'PG-13', 112, ['Comedy', 'Sport'], 'Happy Gilmore returns to the golf course to compete against a new generation.', 11, 0, null, 'year'),
      catEntry('RIP', 2025, 'Movie', 'R', 122, ['Action', 'Crime', 'Thriller'], 'Miami police officers discover millions in cash inside a derelict stash house.', 12, 0, null, 'year'),
      catEntry('Spider-Man Brand New Day', 2026, 'Movie', 'PG-13', 142, ['Action', 'Adventure', 'Sci-Fi'], 'Peter Parker faces a new street-level threat in New York.', 13, 0, null, 'year'),
      catEntry('Project Hail Mary', 2026, 'Movie', 'PG-13', 152, ['Sci-Fi', 'Adventure', 'Drama'], 'An astronaut wakes up alone on a spacecraft with a mission to save Earth.', 14, 0, null, 'year'),
      catEntry('Dhurandhar The Revenge', 2026, 'Movie', 'R', 148, ['Action', 'Thriller'], 'A covert operative returns for a high-stakes mission.', 15, 0, null, 'year'),
      catEntry('Drishyam 3', 2026, 'Movie', 'TV-14', 155, ['Crime', 'Drama', 'Mystery'], 'Georgekutty faces the ultimate test as new evidence surfaces.', 16, 0, null, 'year'),
      catEntry('Awarapan 2', 2026, 'Movie', 'TV-14', 136, ['Action', 'Crime', 'Drama'], 'A solitary hitman seeks redemption.', 17, 0, null, 'year'),
      catEntry('Dhurandhar', 2025, 'Movie', 'R', 144, ['Action', 'Thriller'], 'An intelligence shadow war unfolds across three capitals.', 18, 0, null, 'year'),
      catEntry('Hope', 2026, 'Movie', 'R', 138, ['Sci-Fi', 'Thriller'], 'Residents of a secluded harbor town confront an inexplicable phenomenon.', 19, 0, null, 'year'),
      catEntry('Man Of War', 2026, 'Movie', 'R', 129, ['Action', 'War', 'Drama'], 'A decorated commander leads a rogue unit behind enemy lines.', 20, 0, null, 'year'),
      catEntry('Alpha', 2026, 'Movie', 'PG-13', 134, ['Action', 'Spy', 'Thriller'], 'Two elite field agents expose a mole inside their agency.', 21, 0, null, 'year'),
      catEntry('Matchbox The Movie', 2026, 'Movie', 'PG-13', 118, ['Action', 'Adventure'], 'An elite stunt driver is framed for an international heist.', 22, 0, null, 'year'),

      // Top Shows on IMDb This Year (22 entries)
      catEntry('Severance', 2025, 'Series', 'TV-MA', 56, ['Sci-Fi', 'Thriller', 'Drama'], 'Mark and the Macrodata Refinement team uncover the true purpose of Lumon Industries.', 1, 0, 2022, 'year'),
      catEntry('The Last of Us', 2025, 'Series', 'TV-MA', 60, ['Drama', 'Action', 'Sci-Fi'], 'Five years after their journey across the United States, Joel and Ellie\'s peaceful life in Jackson is shattered.', 2, 0, 2023, 'year'),
      catEntry('Stranger Things', 2025, 'Series', 'TV-14', 60, ['Sci-Fi', 'Thriller', 'Drama'], 'In the final chapter of Hawkins, the party bands together one last time.', 3, 0, 2016, 'year'),
      catEntry('Silo', 2025, 'Series', 'TV-MA', 54, ['Sci-Fi', 'Drama', 'Mystery'], 'Juliette discovers a secret that changes the fate of the last ten thousand survivors.', 4, 0, 2023, 'year'),
      catEntry('Reacher', 2025, 'Series', 'TV-MA', 52, ['Action', 'Crime', 'Thriller'], 'Jack Reacher goes undercover inside a ruthless smuggling syndicate.', 5, 0, 2022, 'year'),
      catEntry('The White Lotus', 2025, 'Series', 'TV-MA', 60, ['Comedy', 'Drama'], 'A new group of guests and staff check into an exclusive Thai resort.', 6, 0, 2021, 'year'),
      catEntry('Slow Horses', 2025, 'Series', 'TV-MA', 50, ['Drama', 'Thriller'], 'Jackson Lamb leads a team of British intelligence outcasts at Slough House.', 7, 0, 2022, 'year'),
      catEntry('Squid Game', 2025, 'Series', 'TV-MA', 58, ['Thriller', 'Drama'], 'Player 456 returns to the arena determined to dismantle the deadly games.', 8, 0, 2021, 'year'),
      catEntry('Wednesday', 2025, 'Series', 'TV-14', 50, ['Comedy', 'Crime', 'Fantasy'], 'Wednesday Addams returns to Nevermore Academy to confront a new supernatural mystery.', 9, 0, 2022, 'year'),
      catEntry('Black Mirror', 2025, 'Series', 'TV-MA', 64, ['Sci-Fi', 'Drama', 'Thriller'], 'New satirical tales exploring near-future technology and human obsession.', 10, 0, 2011, 'year'),
      catEntry('Invincible', 2025, 'Series', 'TV-MA', 48, ['Animation', 'Action', 'Adventure'], 'Mark Grayson defends Earth against the Viltrumite Empire.', 11, 0, 2021, 'year'),
      catEntry('Fallout', 2026, 'Series', 'TV-MA', 60, ['Sci-Fi', 'Action', 'Adventure'], 'Across the wasteland of New Vegas, Lucy and the Ghoul hunt for the truth behind Vault-Tec.', 12, 0, 2024, 'year'),
      catEntry('House of the Dragon', 2026, 'Series', 'TV-MA', 66, ['Drama', 'Fantasy', 'Action'], 'Fire and blood engulf Westeros as rival Targaryen factions clash.', 13, 0, 2022, 'year'),
      catEntry('The Boys', 2026, 'Series', 'TV-MA', 62, ['Action', 'Sci-Fi', 'Drama'], 'Butcher and the Boys risk everything in a final showdown against Homelander.', 14, 0, 2019, 'year'),
      catEntry('Foundation', 2025, 'Series', 'TV-14', 58, ['Drama', 'Sci-Fi'], 'Exiles across the galaxy fight to preserve knowledge as the Galactic Empire fractures.', 15, 0, 2021, 'year'),
      catEntry('The Night Agent', 2025, 'Series', 'TV-MA', 52, ['Action', 'Drama', 'Thriller'], 'Peter Sutherland is catapulted into a vast conspiracy at the highest levels of government.', 16, 0, 2023, 'year'),
      catEntry('One Piece', 2025, 'Series', 'TV-14', 55, ['Action', 'Adventure', 'Fantasy'], 'Monkey D. Luffy and the Straw Hat Pirates sail toward the Grand Line.', 17, 0, 2023, 'year'),
      catEntry('The Family Man', 2025, 'Series', 'TV-MA', 50, ['Action', 'Comedy', 'Drama'], 'Srikant Tiwari balances middle-class family life with covert national security operations.', 18, 0, 2019, 'year'),
      catEntry('Lanterns', 2026, 'Series', 'TV-MA', 58, ['Action', 'Mystery', 'Sci-Fi'], 'Hal Jordan and John Stewart investigate a cosmic mystery.', 19, 0, 2026, 'year'),
      catEntry('A Knight of the Seven Kingdoms', 2026, 'Series', 'TV-MA', 55, ['Fantasy', 'Adventure', 'Drama'], 'Ser Duncan the Tall and Egg journey across Westeros.', 20, 0, 2026, 'year'),
      catEntry('Daredevil: Born Again', 2025, 'Series', 'TV-MA', 54, ['Action', 'Crime', 'Drama'], 'Matt Murdock and Wilson Fisk collide in New York.', 21, 0, 2025, 'year'),
      catEntry('MobLand', 2025, 'Series', 'TV-MA', 56, ['Crime', 'Drama'], 'Two warring London crime families collide.', 22, 0, 2025, 'year'),

      // Top Movies on IMDb All Time (24 entries)
      catEntry('The Shawshank Redemption', 1994, 'Movie', 'R', 142, ['Drama'], 'Over the course of several years, two convicts form a friendship, seeking consolation and, eventually, redemption through basic compassion.', 0, 1, null, 'alltime'),
      catEntry('The Godfather', 1972, 'Movie', 'R', 175, ['Crime', 'Drama'], 'The aging patriarch of an organized crime dynasty transfers control of his clandestine empire to his reluctant youngest son.', 0, 2, null, 'alltime'),
      catEntry('The Dark Knight', 2008, 'Movie', 'PG-13', 152, ['Action', 'Crime', 'Drama'], 'When the menace known as the Joker wreaks havoc and chaos on the people of Gotham, Batman must accept one of the greatest psychological and physical tests of his ability to fight injustice.', 0, 3, null, 'alltime'),
      catEntry('The Godfather Part II', 1974, 'Movie', 'R', 202, ['Crime', 'Drama'], 'The early life and career of Vito Corleone in 1920s New York City is portrayed, while his son, Michael, expands and tightens his grip on the family crime syndicate.', 0, 4, null, 'alltime'),
      catEntry('12 Angry Men', 1957, 'Movie', 'Approved', 96, ['Crime', 'Drama'], 'The jury in a New York City murder trial is frustrated by a single member whose skeptical caution forces them to more carefully consider the evidence before jumping to a hasty verdict.', 0, 5, null, 'alltime'),
      catEntry('Schindler\'s List', 1993, 'Movie', 'R', 195, ['Biography', 'Drama', 'History'], 'In German-occupied Poland during World War II, industrialist Oskar Schindler gradually becomes concerned for his Jewish workforce after witnessing their persecution by the Nazis.', 0, 6, null, 'alltime'),
      catEntry('The Lord of the Rings: The Return of the King', 2003, 'Movie', 'PG-13', 201, ['Action', 'Adventure', 'Drama'], 'Gandalf and Aragorn lead the World of Men against Sauron\'s army to draw his gaze from Frodo and Sam as they approach Mount Doom with the One Ring.', 0, 7, null, 'alltime'),
      catEntry('Pulp Fiction', 1994, 'Movie', 'R', 154, ['Crime', 'Drama'], 'The lives of two mob hitmen, a boxer, a gangster and his wife, and a pair of diner bandits intertwine in four tales of violence and redemption.', 0, 8, null, 'alltime'),
      catEntry('The Lord of the Rings: The Fellowship of the Ring', 2001, 'Movie', 'PG-13', 178, ['Action', 'Adventure', 'Drama'], 'A meek Hobbit from the Shire and eight companions set out on a journey to destroy the powerful One Ring and save Middle-earth from the Dark Lord Sauron.', 0, 9, null, 'alltime'),
      catEntry('Inception', 2010, 'Movie', 'PG-13', 148, ['Action', 'Adventure', 'Sci-Fi'], 'A thief who steals corporate secrets through the use of dream-sharing technology is given the inverse task of planting an idea into the mind of a C.E.O.', 0, 10, null, 'alltime'),
      catEntry('Fight Club', 1999, 'Movie', 'R', 139, ['Drama'], 'An insomniac office worker and a devil-may-care soap maker form an underground fight club that evolves into much more.', 0, 11, null, 'alltime'),
      catEntry('Forrest Gump', 1994, 'Movie', 'PG-13', 142, ['Drama', 'Romance'], 'The history of the United States from the 1950s to the \'70s unfolds from the perspective of an Alabama man with an IQ of 75, who yearns to be reunited with his childhood sweetheart.', 0, 12, null, 'alltime'),
      catEntry('The Lord of the Rings: The Two Towers', 2002, 'Movie', 'PG-13', 179, ['Action', 'Adventure', 'Drama'], 'While Frodo and Sam edge closer to Mordor with the help of the shifty Gollum, the divided fellowship makes a stand against Sauron\'s new ally, Saruman.', 0, 13, null, 'alltime'),
      catEntry('Interstellar', 2014, 'Movie', 'PG-13', 169, ['Adventure', 'Drama', 'Sci-Fi'], 'When Earth becomes uninhabitable in the future, a farmer and ex-NASA pilot, Joseph Cooper, is tasked to pilot a spacecraft, along with a team of researchers, to find a new planet for humans.', 0, 14, null, 'alltime'),
      catEntry('The Matrix', 1999, 'Movie', 'R', 136, ['Action', 'Sci-Fi'], 'When a beautiful stranger leads computer hacker Neo to a forbidding underworld, he discovers the shocking truth: the life he knows is the elaborate deception of an evil cyber-intelligence.', 0, 15, null, 'alltime'),
      catEntry('Goodfellas', 1990, 'Movie', 'R', 145, ['Biography', 'Crime', 'Drama'], 'The story of Henry Hill and his life in the mafia, covering his relationship with his wife Karen and his mob partners Jimmy Conway and Tommy DeVito.', 0, 16, null, 'alltime'),
      catEntry('Se7en', 1995, 'Movie', 'R', 127, ['Crime', 'Drama', 'Mystery'], 'Two detectives, a rookie and a veteran, hunt a serial killer who uses the seven deadly sins as his motives.', 0, 17, null, 'alltime'),
      catEntry('Dune: Part Two', 2024, 'Movie', 'PG-13', 166, ['Action', 'Adventure', 'Sci-Fi'], 'Paul Atreides unites with Chani and the Fremen while on a warpath of revenge against the conspirators who destroyed his family.', 0, 18, null, 'alltime'),
      catEntry('Oppenheimer', 2023, 'Movie', 'R', 180, ['Biography', 'Drama', 'History'], 'The story of American scientist J. Robert Oppenheimer and his role in the development of the atomic bomb.', 0, 19, null, 'alltime'),
      catEntry('Parasite', 2019, 'Movie', 'R', 132, ['Drama', 'Thriller'], 'Greed and class discrimination threaten the newly formed symbiotic relationship between the wealthy Park family and the destitute Kim clan.', 0, 20, null, 'alltime'),
      catEntry('Whiplash', 2014, 'Movie', 'R', 106, ['Drama', 'Music'], 'A promising young drummer enrolls at a cut-throat music conservatory where his dreams of greatness are mentored by an instructor who will stop at nothing to realize a student\'s potential.', 0, 21, null, 'alltime'),
      catEntry('Gladiator', 2000, 'Movie', 'R', 155, ['Action', 'Adventure', 'Drama'], 'A former Roman General sets out to exact vengeance against the corrupt emperor who murdered his family and sent him into slavery.', 0, 22, null, 'alltime'),
      catEntry('The Prestige', 2006, 'Movie', 'PG-13', 130, ['Drama', 'Mystery', 'Sci-Fi'], 'After a tragic accident, two stage magicians in 1890s London engage in a battle to create the ultimate illusion while sacrificing everything they have to outwit each other.', 0, 23, null, 'alltime'),
      catEntry('Spider-Man: No Way Home', 2021, 'Movie', 'PG-13', 148, ['Action', 'Adventure', 'Fantasy'], 'With Spider-Man\'s identity now revealed, Peter asks Doctor Strange for help. When a spell goes wrong, dangerous foes from other worlds start to appear.', 0, 24, null, 'alltime'),

      // Top Shows on IMDb All Time (24 entries)
      catEntry('Breaking Bad', 2008, 'Series', 'TV-MA', 49, ['Crime', 'Drama', 'Thriller'], 'A chemistry teacher diagnosed with inoperable lung cancer turns to manufacturing and selling methamphetamine with a former student in order to secure his family\'s future.', 0, 1, 2008, 'alltime'),
      catEntry('Band of Brothers', 2001, 'Series', 'TV-MA', 60, ['Drama', 'History', 'War'], 'The story of Easy Company of the U.S. Army 101st Airborne Division and their mission in World War II Europe, from Operation Overlord to V-J Day.', 0, 2, 2001, 'alltime'),
      catEntry('Chernobyl', 2019, 'Series', 'TV-MA', 65, ['Drama', 'History', 'Thriller'], 'In April 1986, an explosion at the Chernobyl nuclear power plant in the Union of Soviet Socialist Republics becomes one of the world\'s worst man-made catastrophes.', 0, 3, 2019, 'alltime'),
      catEntry('The Wire', 2002, 'Series', 'TV-MA', 59, ['Crime', 'Drama', 'Thriller'], 'The Baltimore drug scene, as seen through the eyes of drug dealers and law enforcement.', 0, 4, 2002, 'alltime'),
      catEntry('Avatar: The Last Airbender', 2005, 'Series', 'TV-Y7', 24, ['Animation', 'Action', 'Adventure'], 'In a war-torn world of elemental magic, a young boy reawakens to undertake a dangerous mystic quest to fulfill his destiny as the Avatar.', 0, 5, 2005, 'alltime'),
      catEntry('The Sopranos', 1999, 'Series', 'TV-MA', 55, ['Crime', 'Drama'], 'New Jersey mob boss Tony Soprano deals with personal and professional issues in his home and business life that affect his mental state.', 0, 6, 1999, 'alltime'),
      catEntry('Game of Thrones', 2011, 'Series', 'TV-MA', 57, ['Action', 'Adventure', 'Drama'], 'Nine noble families fight for control over the lands of Westeros, while an ancient enemy returns after being dormant for millennia.', 0, 7, 2011, 'alltime'),
      catEntry('Better Call Saul', 2015, 'Series', 'TV-MA', 46, ['Crime', 'Drama'], 'The trials and tribulations of criminal lawyer Jimmy McGill in the years leading up to his fateful run-in with Walter White and Jesse Pinkman.', 0, 8, 2015, 'alltime'),
      catEntry('Sherlock', 2010, 'Series', 'TV-14', 88, ['Crime', 'Drama', 'Mystery'], 'A modern update finds the famous sleuth and his doctor partner solving crime in 21st-century London.', 0, 9, 2010, 'alltime'),
      catEntry('Attack on Titan', 2013, 'Series', 'TV-MA', 24, ['Animation', 'Action', 'Adventure'], 'After his hometown is destroyed and his mother is killed, young Eren Jaeger vows to cleanse the earth of the giant humanoid Titans.', 0, 10, 2013, 'alltime'),
      catEntry('Succession', 2018, 'Series', 'TV-MA', 60, ['Drama'], 'The Roy family is known for controlling the biggest media and entertainment company in the world. However, their world changes when their father steps down.', 0, 11, 2018, 'alltime'),
      catEntry('True Detective', 2014, 'Series', 'TV-MA', 60, ['Crime', 'Drama', 'Mystery'], 'Seasonal anthology series in which police investigations unearth the personal and professional secrets of those involved, both within and outside the law.', 0, 12, 2014, 'alltime'),
      catEntry('Arcane', 2021, 'Series', 'TV-14', 44, ['Animation', 'Action', 'Adventure'], 'Set in Utopian Piltover and the oppressed underground of Zaun, the story follows the origins of two iconic League champions.', 0, 13, 2021, 'alltime'),
      catEntry('Fargo', 2014, 'Series', 'TV-MA', 53, ['Crime', 'Drama', 'Thriller'], 'Various chronicles of deception, intrigue, and murder in and around frozen Minnesota.', 0, 14, 2014, 'alltime'),
      catEntry('The Office', 2005, 'Series', 'TV-14', 22, ['Comedy'], 'A mockumentary on a group of typical office workers, where the workday consists of ego clashes, inappropriate behavior, and tedium.', 0, 15, 2005, 'alltime'),
      catEntry('Friends', 1994, 'Series', 'TV-14', 22, ['Comedy', 'Romance'], 'Follows the personal and professional lives of six twenty to thirty-something-year-old friends living in Manhattan.', 0, 16, 1994, 'alltime'),
      catEntry('Dark', 2017, 'Series', 'TV-MA', 60, ['Crime', 'Drama', 'Mystery'], 'A family saga with a supernatural twist, set in a German town where the disappearance of two young children exposes the relationships among four families.', 0, 17, 2017, 'alltime'),
      catEntry('Peaky Blinders', 2013, 'Series', 'TV-MA', 58, ['Crime', 'Drama'], 'A gangster family epic set in 1900s England, centering on a gang who sew razor blades in the peaks of their caps, and their fierce boss Tommy Shelby.', 0, 18, 2013, 'alltime'),
      catEntry('Modern Family', 2009, 'Series', 'TV-PG', 22, ['Comedy', 'Drama', 'Romance'], 'Three different but related families face trials and tribulations in their own uniquely comedic ways.', 0, 19, 2009, 'alltime'),
      catEntry('The Big Bang Theory', 2007, 'Series', 'TV-PG', 22, ['Comedy', 'Romance'], 'A woman who moves into an apartment across the hall from two brilliant but socially awkward physicists shows them how little they know about life outside of the laboratory.', 0, 20, 2007, 'alltime'),
      catEntry('Money Heist', 2017, 'Series', 'TV-MA', 50, ['Action', 'Crime', 'Drama'], 'An unusual group of robbers attempt to carry out the most perfect robbery in Spanish history - stealing 2.4 billion euros from the Royal Mint of Spain.', 0, 21, 2017, 'alltime'),
      catEntry('Ted Lasso', 2020, 'Series', 'TV-MA', 45, ['Comedy', 'Drama', 'Sport'], 'American college football coach Ted Lasso heads to London to manage AFC Richmond, a struggling English Premier League soccer team.', 0, 22, 2020, 'alltime'),
      catEntry('Mirzapur', 2018, 'Series', 'TV-MA', 55, ['Action', 'Crime', 'Drama'], 'A shocking incident at a wedding procession ignites a series of events entangling the lives of two families in the lawless city of Mirzapur.', 0, 23, 2018, 'alltime'),
      catEntry('Panchayat', 2020, 'Series', 'TV-14', 40, ['Comedy', 'Drama'], 'A comedy-drama, which captures the journey of an engineering graduate Abhishek, who for lack of a better job option joins as secretary of a Panchayat office in a remote village of Uttar Pradesh.', 0, 24, 2020, 'alltime')
    ]
  };

  function makeCatalogFallbackItem(entry, slug, pName) {
    var k = norm(entry.name);
    var yr = entry.year || 2025;
    return {
      Id: 'req:' + slug + ':' + k + ':' + yr,
      Name: entry.name,
      ProductionYear: yr,
      PremiereDate: entry.date ? String(entry.date).slice(0, 10) : (yr + '-01-01'),
      Type: entry.kind || 'Movie',
      OfficialRating: entry.rating || 'TV-14',
      RunTimeTicks: (entry.mins || 110) * 60e7,
      Genres: entry.genres || ['Drama'],
      Overview: entry.overview || ('Available in the ' + pName + ' catalog. Request this title to add it to your MojaLoss server.'),
      People: entry.people || [],
      UserData: { PlaybackPositionTicks: 0 },
      _posterUrl: entry.poster || '',
      _wpDate: entry.date || '',
      _requestable: true,
      _platformSlug: slug,
      _platformName: pName,
      _platTrendRank: entry.trend || 0,
      _platPopRank: entry.pop || 0,
      _imdbBucket: entry.imdbBucket || ''
    };
  }

  function cloneServerItemForPlatform(it, slug, pName) {
    var copy = {};
    for (var k in it) {
      if (Object.prototype.hasOwnProperty.call(it, k)) copy[k] = it[k];
    }
    delete copy._platTrendRank;
    delete copy._platPopRank;
    delete copy._platCatalogYear;
    delete copy._imdbBucket;
    delete copy._imdbBuckets;
    copy._platformSlug = slug;
    copy._platformName = pName;
    return copy;
  }

  function keyYr(name, yr, kind) {
    return norm(name) + ':' + (yr || '') + ':' + (kind || '');
  }
  function keyNK(name, kind) {
    return norm(name) + ':' + (kind || '');
  }
  function resolvePlatformCatalog(p, posts, lookup) {
    var slug = (p && p.slug) || '';
    var pName = (p && p.name) || 'Platform';
    lookup = lookup || globalLibLookup;
    var effectivePosts = (posts && posts.length) ? posts : ((p && p.posts) || []);
    var seenIds = {}, seenKeyYr = {}, seenNameKind = {}, serverMatched = [], unmatchedEntries = [];
    function addServerItem(rawIt, metaEntry, isCurated) {
      if (!rawIt || !rawIt.Id) return;
      var existing = seenIds[rawIt.Id];
      var it = existing || cloneServerItemForPlatform(rawIt, slug, pName);
      if (metaEntry) {
        if (metaEntry.poster && !it._wpPoster) it._wpPoster = metaEntry.poster;
        if (metaEntry.date && !it._wpDate) it._wpDate = metaEntry.date;
        if (metaEntry.trend && (isCurated || !it._platTrendRank)) {
          it._platTrendRank = metaEntry.trend;
          if (metaEntry.year >= 2025) it._platCatalogYear = metaEntry.year;
        }
        if (metaEntry.pop && (isCurated || !it._platPopRank)) {
          it._platPopRank = metaEntry.pop;
        }
        if (metaEntry.imdbBucket) {
          it._imdbBuckets = it._imdbBuckets || {};
          it._imdbBuckets[metaEntry.imdbBucket] = 1;
          if (!it._imdbBucket) it._imdbBucket = metaEntry.imdbBucket;
        }
      }
      var y = itemReleaseYear(it);
      seenKeyYr[keyYr(it.Name, y, it.Type)] = it;
      seenNameKind[keyNK(it.Name, it.Type)] = it;
      if (metaEntry && metaEntry.name) {
        seenKeyYr[keyYr(metaEntry.name, metaEntry.year || y, metaEntry.kind || it.Type)] = it;
        seenNameKind[keyNK(metaEntry.name, metaEntry.kind || it.Type)] = it;
      }
      if (existing) return;
      seenIds[it.Id] = it;
      serverMatched.push(it);
    }

    // 1. Process curated PLATFORM_CATALOG_DB first so explicit platform Trending (1..16) and Popular (1..15) ranks always take priority
    var unmatchedByNK = {};
    (PLATFORM_CATALOG_DB[slug] || []).forEach(function (entry) {
      var m = findServerMatch(entry, lookup, true);
      if (m) {
        addServerItem(m, entry, true);
      } else {
        var copyEntry = {};
        for (var k in entry) if (Object.prototype.hasOwnProperty.call(entry, k)) copyEntry[k] = entry[k];
        unmatchedEntries.push(copyEntry);
        unmatchedByNK[keyNK(copyEntry.name, copyEntry.kind || 'Movie')] = copyEntry;
      }
    });

    // 2. Process live WordPress posts for this platform (enriching posters/dates on existing entries and adding new 2025/2026 & catalog posts)
    (effectivePosts || []).forEach(function (post, idx) {
      if (!post || !post.name) return;
      var pEntry = {
        name: post.name,
        year: post.year,
        yearEnd: post.yearEnd,
        kind: post.kind || 'Movie',
        poster: post.poster || '',
        date: post.date || '',
        overview: post.overview || '',
        trend: (post.year && post.year >= 2025 && idx < 20) ? (20 + idx) : 0,
        pop: (post.year && post.year < 2025 && idx < 25) ? (20 + idx) : 0
      };
      var m = findServerMatch(pEntry, lookup, true);
      if (m) {
        addServerItem(m, pEntry, false);
      } else {
        var nk = keyNK(pEntry.name, pEntry.kind);
        if (unmatchedByNK[nk]) {
          if (pEntry.poster && !unmatchedByNK[nk].poster) unmatchedByNK[nk].poster = pEntry.poster;
          if (pEntry.date && !unmatchedByNK[nk].date) unmatchedByNK[nk].date = pEntry.date;
        } else {
          unmatchedEntries.push(pEntry);
          unmatchedByNK[nk] = pEntry;
        }
      }
    });

    // 3. Include any remaining pre-matched server items on p.items
    ((p && p.items) || []).forEach(function (it) {
      if (it && !it._requestable) {
        addServerItem(it, {
          poster: it._wpPoster || '',
          date: it._wpDate || '',
          trend: it._platTrendRank || 0,
          pop: it._platPopRank || 0,
          year: it._platCatalogYear || itemReleaseYear(it),
          imdbBucket: it._imdbBucket || ''
        }, false);
      }
    });

    var catalogFallbacks = [];
    unmatchedEntries.forEach(function (entry) {
      var ky = keyYr(entry.name, entry.year, entry.kind);
      if (!norm(entry.name) || seenKeyYr[ky]) return;
      seenKeyYr[ky] = 1;
      var fb = makeCatalogFallbackItem(entry, slug, pName);
      if (entry.trend && entry.year >= 2025) fb._platCatalogYear = entry.year;
      catalogFallbacks.push(fb);
    });
    return {
      onServer: serverMatched,
      catalogFallbacks: catalogFallbacks,
      all: serverMatched.concat(catalogFallbacks)
    };
  }
  function doubleCheckPlatformWithServerSearch(p, onUpdated) {
    if (!p || !p.allItems || p._serverSearchChecked) return Promise.resolve(p);
    p._serverSearchChecked = true;
    var toVerify = (p.allItems || []).filter(function (x) { return x && x._requestable; });
    if (!toVerify.length) return Promise.resolve(p);
    var changed = false;
    return runBatch(toVerify.slice(0, 12), 4, function (reqIt) {
      var m0 = findServerMatch({ name: reqIt.Name, year: reqIt.ProductionYear, kind: reqIt.Type }, globalLibLookup, true);
      if (m0) return m0;
      return Api.search(reqIt.Name, 10).then(function (sr) {
        var hits = (sr && sr.Items) || [];
        if (hits.length) indexServerItems(hits);
        return findServerMatch({ name: reqIt.Name, year: reqIt.ProductionYear, kind: reqIt.Type }, globalLibLookup, true);
      }, function () { return null; });
    }).then(function (resolvedList) {
      var replacedByReqId = {};
      for (var i = 0; i < toVerify.length && i < resolvedList.length; i++) {
        if (resolvedList[i] && resolvedList[i].Id) {
          var clone = cloneServerItemForPlatform(resolvedList[i], p.slug || '', p.name || 'Platform');
          var reqOrig = toVerify[i];
          if (reqOrig._platTrendRank) clone._platTrendRank = reqOrig._platTrendRank;
          if (reqOrig._platPopRank) clone._platPopRank = reqOrig._platPopRank;
          if (reqOrig._platCatalogYear) clone._platCatalogYear = reqOrig._platCatalogYear;
          if (reqOrig._posterUrl && !clone._wpPoster) clone._wpPoster = reqOrig._posterUrl;
          if (reqOrig._wpDate && !clone._wpDate) clone._wpDate = reqOrig._wpDate;
          if (reqOrig._imdbBucket) clone._imdbBucket = reqOrig._imdbBucket;
          if (reqOrig._imdbBuckets) clone._imdbBuckets = reqOrig._imdbBuckets;
          replacedByReqId[reqOrig.Id] = clone;
          changed = true;
        }
      }
      if (changed) {
        var seen = {}, nextItems = [], nextAll = [];
        (p.items || []).forEach(function (it) { if (it && !it._requestable && !seen[it.Id]) { seen[it.Id] = 1; nextItems.push(it); nextAll.push(it); } });
        (p.allItems || []).forEach(function (it) {
          var real = replacedByReqId[it.Id] || (!it._requestable ? it : null);
          if (real) {
            if (!seen[real.Id]) { seen[real.Id] = 1; nextItems.push(real); nextAll.push(real); }
          } else if (!seen[it.Id]) {
            seen[it.Id] = 1;
            nextAll.push(it);
          }
        });
        p.items = nextItems;
        p.allItems = nextAll;
        savePlatformHubEntry(p);
        if (onUpdated) onUpdated(p, replacedByReqId);
      }
      return p;
    });
  }

  function savePlatformHubEntry(p) {
    if (!p || !p.slug) return;
    if (!p.allItems) {
      var res = resolvePlatformCatalog(p, p.posts || [], globalLibLookup);
      p.items = res.onServer;
      p.allItems = res.all;
    }
    platformHubCache[p.slug] = { ver: PLAT_HUB_CACHE_VER, id: p.id, name: p.name, slug: p.slug, posts: p.posts || [], items: p.items || [], allItems: p.allItems || [], ts: Date.now() };
    try {
      var slimHub = {};
      Object.keys(platformHubCache).forEach(function (k) {
        var entry = platformHubCache[k];
        if (entry) slimHub[k] = { ver: PLAT_HUB_CACHE_VER, id: entry.id, name: entry.name, slug: entry.slug, ts: entry.ts };
      });
      localStorage.setItem(PLAT_HUB_CACHE_KEY, JSON.stringify(slimHub));
    } catch (e) {}
  }

  function applyPlatformOrder(list) {
    var arr = (list || []).slice();
    var saved = [];
    try { saved = JSON.parse(localStorage.getItem(PLAT_ORDER_KEY) || '[]') || []; } catch (e) { saved = []; }
    if (!saved.length) return arr;
    var orderMap = {};
    for (var i = 0; i < saved.length; i++) orderMap[saved[i]] = i + 1;
    arr.sort(function (a, b) {
      var oa = orderMap[a.slug] || 9999, ob = orderMap[b.slug] || 9999;
      return oa - ob;
    });
    return arr;
  }
  var movingChip = null, movingOrigOrder = null, okHoldTimer = null, suppressNextChipClick = false;
  function enterPlatformReorder(chip) {
    if (!chip || !chip.classList || !chip.classList.contains('chip')) return;
    movingChip = chip;
    var track = chip.parentNode;
    movingOrigOrder = [];
    if (track) each(track.querySelectorAll('.chip'), function (c) { movingOrigOrder.push(c.getAttribute('data-slug')); });
    chip.classList.add('reordering');
    if (!chip.querySelector('.chip-move-hint')) {
      var hint = document.createElement('span');
      hint.className = 'chip-move-hint';
      hint.textContent = '\u25c0 \u25b6 Move \u00b7 OK Save';
      chip.appendChild(hint);
    }
    var pName = chip.getAttribute('data-name') || 'platform';
    toast('Moving ' + pName + ' \u2014 use \u25c0 \u25b6 to move, OK to save');
    if (Focus.onFocus) Focus.onFocus(chip);
  }
  function movePlatformChip(dir) {
    if (!movingChip || !movingChip.parentNode) return;
    var track = movingChip.parentNode;
    if (dir === 'L' && movingChip.previousElementSibling) {
      track.insertBefore(movingChip, movingChip.previousElementSibling);
    } else if (dir === 'R' && movingChip.nextElementSibling) {
      track.insertBefore(movingChip.nextElementSibling, movingChip);
    }
    Focus.set(movingChip);
  }
  function finishPlatformReorder(save) {
    if (!movingChip) return;
    var chip = movingChip;
    var track = chip.parentNode;
    chip.classList.remove('reordering');
    var hint = chip.querySelector('.chip-move-hint');
    if (hint && hint.parentNode) hint.parentNode.removeChild(hint);
    if (!save && movingOrigOrder && track) {
      var bySlug = {};
      each(track.querySelectorAll('.chip'), function (c) { bySlug[c.getAttribute('data-slug')] = c; });
      movingOrigOrder.forEach(function (slug) { if (bySlug[slug]) track.appendChild(bySlug[slug]); });
      toast('Reorder cancelled');
    } else if (save && track) {
      var newOrder = [];
      each(track.querySelectorAll('.chip'), function (c) { newOrder.push(c.getAttribute('data-slug')); });
      try { localStorage.setItem(PLAT_ORDER_KEY, JSON.stringify(newOrder)); } catch (e) {}
      if (discoveryCache && discoveryCache.platforms) {
        discoveryCache.platforms = applyPlatformOrder(discoveryCache.platforms);
        /* discoveryCache kept in memory to preserve localStorage quota for OTA */
      }
      toast('Platform order saved');
    }
    movingChip = null;
    movingOrigOrder = null;
    Focus.set(chip);
  }
  function loadDiscovery(seedItems, onTrendingReady) {
    if (seedItems && seedItems.length) indexServerItems(seedItems);
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
      return indexServerItems(combined);
    });

    // 1. Resolve & render Top 10 Trending Today (strictly Today / 30d rank 1..10) + Most-Watched All-Time (strictly pop rank)
    var trendingPromise = Promise.all([
      libPromise,
      Api.trending().catch(function () { return []; })
    ]).then(function (r1) {
      var libData = r1[0], rawTrends = r1[1] || [];
      var trends = rawTrends.filter(function (tr) { return tr && (!tr.rank || tr.rank <= 30); });
      if (!trends.length) trends = rawTrends.slice(0, 30);
      if (!trends.length) return [];
      var trendSeen = {}, trendingItems = [], mwSeen = {}, mostWatchedItems = [];

      function pushMostWatched(it, rankNum, popNum) {
        if (!it || !it.Id || it._requestable || mwSeen[it.Id]) return;
        mwSeen[it.Id] = 1;
        it._mwPopRank = popNum || rankNum || 999;
        mostWatchedItems.push(it);
      }
      function pushTrending(it, rankNum, trMeta) {
        if (!it || !it.Id || trendSeen[it.Id] || trendingItems.length >= 10) return false;
        trendSeen[it.Id] = 1;
        var copy = {};
        for (var k in it) if (Object.prototype.hasOwnProperty.call(it, k)) copy[k] = it[k];
        if (trMeta) {
          if (trMeta.poster && !copy._wpPoster) copy._wpPoster = trMeta.poster;
          if (trMeta.year && !copy.ProductionYear) copy.ProductionYear = trMeta.year;
        }
        copy._trendingRank = rankNum || (trendingItems.length + 1);
        copy._displayRank = trendingItems.length + 1;
        trendingItems.push(copy);
        return true;
      }

      var matchedByIdx = new Array(trends.length);
      var unmatchedIdxs = [];
      for (var i = 0; i < trends.length; i++) {
        var tr = trends[i];
        var match = findServerMatch(tr, libData, true);
        if (match) {
          matchedByIdx[i] = match;
        } else if (i < 18 && unmatchedIdxs.length < 15) {
          unmatchedIdxs.push(i);
        }
      }

      var allSnap = ((window.TrendingSnapshot) || []).slice().sort(function (a, b) {
        return (a.pop || a.rank || 999) - (b.pop || b.rank || 999);
      });
      var unmatchedPop = [];
      allSnap.forEach(function (trP) {
        if (!trP || (trP.year && trP.year < 2000)) return;
        var mp = findServerMatch(trP, libData, true);
        if (mp) {
          pushMostWatched(mp, trP.rank, trP.pop || trP.rank);
        } else if (unmatchedPop.length < 10) {
          unmatchedPop.push(trP);
        }
      });

      var searchStep = (unmatchedIdxs.length && !param('mock')) ? runBatch(unmatchedIdxs, 4, function (idx) {
        var tr = trends[idx];
        return Api.search(tr.name, 8).then(function (sr) {
          var hits = (sr && sr.Items) || [];
          if (hits.length) indexServerItems(hits);
          var m = findServerMatch(tr, globalLibLookup, true);
          if (!m && hits.length) {
            var want = norm(tr.name), bwant = baseNorm(tr.name);
            for (var hIdx = 0; hIdx < hits.length; hIdx++) {
              var hIt = hits[hIdx];
              if (hIt && (!tr.kind || hIt.Type === tr.kind) && (norm(hIt.Name) === want || (bwant.length >= 4 && baseNorm(hIt.Name) === bwant))) {
                m = hIt;
                break;
              }
            }
          }
          if (m) matchedByIdx[idx] = m;
          return m;
        }, function () { return null; });
      }) : Promise.resolve();

      var popSearchStep = (unmatchedPop.length && !param('mock')) ? runBatch(unmatchedPop, 3, function (trP) {
        return Api.search(trP.name, 6).then(function (sr) {
          var hits = (sr && sr.Items) || [];
          if (hits.length) indexServerItems(hits);
          var m = findServerMatch(trP, globalLibLookup, true);
          if (m) pushMostWatched(m, trP.rank, trP.pop || trP.rank);
          return m;
        }, function () { return null; });
      }) : Promise.resolve();

      return Promise.all([searchStep, popSearchStep]).then(function () {
        for (var j = 0; j < trends.length && trendingItems.length < 10; j++) {
          if (matchedByIdx[j]) {
            pushTrending(matchedByIdx[j], trends[j].rank, trends[j]);
          }
        }
        if (trendingItems.length < 10 && !param('mock')) {
          var pool = (seedItems || []).concat(libData.all || []);
          for (var p = 0; p < pool.length && trendingItems.length < 10; p++) {
            var cand = pool[p];
            if (cand && (cand.Type === 'Movie' || cand.Type === 'Series')) pushTrending(cand, trendingItems.length + 1, null);
          }
        }
        mostWatchedItems.sort(function (a, b) { return (a._mwPopRank || 999) - (b._mwPopRank || 999); });
        latestMostWatchedItems = mostWatchedItems;
        latestTrendingItems = trendingItems;
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
      function matchPosts(posts, wantKind) {
        var seen = {}, items = [];
        posts.forEach(function (post) {
          var m = findServerMatch({ name: post.name, year: post.year, kind: wantKind || post.kind }, libData, !wantKind);
          if (m && !seen[m.Id] && (!wantKind || m.Type === wantKind)) {
            seen[m.Id] = 1;
            items.push(m);
          }
        });
        return items;
      }

      var platPromise = runBatch(dir.list || [], 3, function (p) {
        return Api.platformTitles(p.id).then(function (posts) {
          var matched = matchPosts(posts, null);
          var baseObj = { id: p.id, name: p.name, slug: p.slug, posts: posts || [], items: matched };
          var resolved = resolvePlatformCatalog(baseObj, posts || [], libData);
          baseObj.items = resolved.onServer;
          baseObj.allItems = resolved.all;
          return baseObj;
        }, function () {
          var baseObj2 = { id: p.id, name: p.name, slug: p.slug, posts: [], items: [] };
          var resolved2 = resolvePlatformCatalog(baseObj2, [], libData);
          baseObj2.items = resolved2.onServer;
          baseObj2.allItems = resolved2.all;
          return baseObj2;
        });
      }).then(function (ps) {
        if (!ps.some(function (p) { return p && p.slug === 'imdb'; })) {
          var imdbBase = { id: 'imdb', name: 'IMDb', slug: 'imdb', items: [] };
          var imdbRes = resolvePlatformCatalog(imdbBase, [], libData);
          imdbBase.items = imdbRes.onServer;
          imdbBase.allItems = imdbRes.all;
          ps.push(imdbBase);
        }
        var filtered = ps.filter(function (p) {
          if (!p) return false;
          var totalCount = (p.allItems && p.allItems.length) ? p.allItems.length : ((p.items && p.items.length) || 0);
          return totalCount >= 20 && (p.items.length > 0 || p.slug === 'imdb');
        });
        return filtered;
      });

      var regPromise = runBatch(dir.regionals || [], 3, function (reg) {
        return Api.platformTitles(reg.id).then(function (posts) {
          return { id: reg.id, slug: reg.slug, title: reg.title, kind: reg.kind, items: matchPosts(posts, reg.kind) };
        }, function () { return { id: reg.id, slug: reg.slug, title: reg.title, kind: reg.kind, items: [] }; });
      }).then(function (rs) { var minReg = param("mock") ? 1 : 20; return rs.filter(function (reg) { return reg && reg.items && reg.items.length >= minReg; }); });

      return Promise.all([platPromise, regPromise]).then(function (res) {
        discoveryCache = { platforms: res[0], regionals: res[1], trending: trendingItems, mostWatched: latestMostWatchedItems };
        /* discoveryCache kept in memory to preserve localStorage quota for OTA */
        return discoveryCache;
      });
    });
  }

  var PLATFORM_DISPLAY_NAMES = {
    "imdb": "IMDb",
    "apple-tv": "Apple TV+",
    "netflix": "Netflix",
    "prime-video": "Prime Video",
    "disney-plus": "Disney+",
    "max": "Max",
    "hulu": "Hulu",
    "peacock": "Peacock",
    "paramount-plus": "Paramount+",
    "sony-liv": "Sony LIV",
    "zee5": "ZEE5",
    "hoichoi": "Hoichoi",
    "chorki": "Chorki",
    "toffee": "Toffee",
    "bongobd": "Bongo"
  };

  function buildAllTimePopularByKind(wantKind, lookup) {
    var db = lookup || globalLibLookup;
    var out = [], seenIds = {}, seenNK = {};
    function addServerItem(it) {
      if (!it || !it.Id || it._requestable) return;
      if (String(it.Id).indexOf("req:") === 0) return;
      if ((it.Type || "") !== wantKind) return;
      var yr = itemReleaseYear(it) || 0;
      if (yr < 2000) return;
      if (seenIds[it.Id]) return;
      var nk = keyNK(it.Name || it.OriginalTitle || "", wantKind);
      if (nk && seenNK[nk]) return;
      seenIds[it.Id] = 1;
      if (nk) seenNK[nk] = 1;
      var clean = {};
      for (var k in it) {
        if (Object.prototype.hasOwnProperty.call(it, k) && k !== "_requestable" && k !== "_imdbBucket" && k !== "_imdbBuckets") {
          clean[k] = it[k];
        }
      }
      if (clean._platSlug === "imdb" || clean._platformSlug === "imdb") {
        delete clean._platSlug;
        delete clean._platName;
        delete clean._platformSlug;
        delete clean._platformName;
      }
      out.push(clean);
    }

    // 1. MojaLoss Most-Watched titles in strict popularity order (?period=all #1..#28 -> 365d -> 180d -> 30d)
    var popSnap = ((window.TrendingSnapshot) || []).slice().sort(function (a, b) {
      return (a.pop || a.rank || 999) - (b.pop || b.rank || 999);
    });
    popSnap.forEach(function (tr) {
      if (!tr || (tr.kind && tr.kind !== wantKind)) return;
      if (tr.year && tr.year < 2000) return;
      var m = findServerMatch({ name: tr.name, year: tr.year, kind: wantKind }, db, true);
      if (m && m.Id && !m._requestable) addServerItem(m);
    });
    (latestMostWatchedItems || []).forEach(function (it) { addServerItem(it); });
    if (discoveryCache) {
      ((discoveryCache.mostWatched) || []).forEach(function (it) { addServerItem(it); });
    }
    (latestTrendingItems || []).forEach(function (it) { addServerItem(it); });
    if (discoveryCache) {
      ((discoveryCache.trending) || []).forEach(function (it) { addServerItem(it); });
    }

    // 2. Non-IMDb Streaming Platform Most-Watched / Flagship Popular titles ordered by popularity rank (pop 1..15 first, then trend 1..16)
    var platPriority = { netflix: 1, hbo: 2, prime: 3, apple: 4, hoichoi: 5 };
    var platCands = [];
    Object.keys(PLATFORM_CATALOG_DB || {}).forEach(function (slug) {
      if (slug === "imdb") return;
      var pOrd = platPriority[slug] || 99;
      (PLATFORM_CATALOG_DB[slug] || []).forEach(function (entry) {
        if (!entry || (entry.kind || "Movie") !== wantKind) return;
        if ((entry.year || 0) < 2000) return;
        if (!(entry.pop > 0 || (entry.trend > 0 && (entry.year || 0) <= 2025 && entry.name !== "The Rainmaker"))) return;
        platCands.push({ entry: entry, pOrd: pOrd });
      });
    });
    platCands.sort(function (a, b) {
      var aHasPop = a.entry.pop > 0 ? 0 : 1, bHasPop = b.entry.pop > 0 ? 0 : 1;
      if (aHasPop !== bHasPop) return aHasPop - bHasPop;
      var aRank = a.entry.pop > 0 ? a.entry.pop : (a.entry.trend || 999);
      var bRank = b.entry.pop > 0 ? b.entry.pop : (b.entry.trend || 999);
      if (aRank !== bRank) return aRank - bRank;
      return a.pOrd - b.pOrd;
    });
    platCands.forEach(function (c) {
      var m = findServerMatch(c.entry, db, false);
      if (m && m.Id && !m._requestable) {
        if (!c.entry.pop && (itemReleaseYear(m) || 0) >= 2026) return;
        addServerItem(m);
      }
    });

    return out;
  }

  function buildServerCollections(allServerItems, boxSets) {
    var cols = [], seenIds = {};
    (boxSets || []).forEach(function (bs) {
      if (!bs || !bs.Id || seenIds[bs.Id]) return;
      seenIds[bs.Id] = 1;
      var copy = {};
      for (var k in bs) if (Object.prototype.hasOwnProperty.call(bs, k)) copy[k] = bs[k];
      copy.Type = 'BoxSet';
      copy._isCollection = true;
      copy._collectionCount = bs.ChildCount || 0;
      cols.push(copy);
    });
    // Also build real server-backed Genre / Library Collections from real server items
    var byGenre = {}, genreOrder = ['Action', 'Thriller', 'Sci-Fi', 'Drama', 'Crime', 'Comedy', 'Mystery', 'Adventure', 'Animation'];
    (allServerItems || []).forEach(function (it) {
      if (!it || !it.Id || it._requestable || (it.Type !== 'Movie' && it.Type !== 'Series')) return;
      (it.Genres || []).forEach(function (g) {
        if (!g) return;
        if (!byGenre[g]) byGenre[g] = [];
        if (byGenre[g].indexOf(it) < 0) byGenre[g].push(it);
      });
    });
    Object.keys(byGenre).forEach(function (g) {
      if (genreOrder.indexOf(g) < 0) genreOrder.push(g);
    });
    genreOrder.forEach(function (g) {
      var gItems = byGenre[g] || [];
      if (gItems.length < 2) return;
      var sortedG = sortByReleaseDateDesc(gItems);
      var lead = sortedG[0];
      var colId = 'col:genre:' + norm(g);
      if (seenIds[colId]) return;
      seenIds[colId] = 1;
      cols.push({
        Id: colId,
        Name: g + ' Collection',
        Type: 'BoxSet',
        ProductionYear: itemReleaseYear(lead) || 2026,
        Genres: [g],
        Overview: gItems.length + ' ' + g.toLowerCase() + ' movies and series available to stream on MojaLoss.',
        ImageTags: lead.ImageTags || {},
        BackdropImageTags: lead.BackdropImageTags || [],
        _wpPoster: lead._wpPoster || lead._posterUrl || '',
        _isCollection: true,
        _collectionCount: gItems.length,
        _collectionItems: sortedG
      });
    });
    return cols;
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

    home: function (a) {
      a = a || {};
      var me = screen;
      function paintHome(r) {
        if (screen !== me) return;
        var skelHero = document.getElementById('home-skel-hero');
        if (skelHero && skelHero.parentNode) skelHero.parentNode.removeChild(skelHero);
        var map = {};
        r.forEach(function (x) { ((x && x.Items) || []).forEach(function (i) { if (i && i.Id && !map[i.Id]) map[i.Id] = i; }); });
        var cwItems = (r[0] && r[0].Items) || [];
        var cwIds = {};
        cwItems.forEach(function (i) { cwIds[i.Id] = 1; });
        var nextUpItems = ((r[4] && r[4].Items) || []).filter(function (i) { return !cwIds[i.Id]; });
        var recentUploadItems = ((r[5] && r[5].Items) || []).length ? (r[5].Items || []) : [].concat((r[1] && r[1].Items) || [], (r[2] && r[2].Items) || []);
        var serverPool = [].concat((r[1] && r[1].Items) || [], (r[2] && r[2].Items) || [], recentUploadItems);
        if (serverPool.length) indexServerItems(serverPool);
        var popMoviesAllTime = buildAllTimePopularByKind("Movie", globalLibLookup);
        var popSeriesAllTime = buildAllTimePopularByKind("Series", globalLibLookup);
        popMoviesAllTime.forEach(function (it) { map[it.Id] = it; });
        popSeriesAllTime.forEach(function (it) { map[it.Id] = it; });
        var collectionsList = buildServerCollections(serverPool, (r[6] && r[6].Items) || []);
        collectionsList.forEach(function (c) { map[c.Id] = c; });

        var firstHero = cwItems[0] || nextUpItems[0] || ((r[1] && r[1].Items) || [])[0] || ((r[2] && r[2].Items) || [])[0] || ((r[3] && r[3].Items) || [])[0] || null;
        var rowsBox = document.getElementById('rows');
        if (firstHero && rowsBox && rowsBox.parentNode && !document.getElementById('hero')) {
          var heroWrap = document.createElement('div');
          heroWrap.innerHTML = heroSectionHtml();
          rowsBox.parentNode.parentNode.insertBefore(heroWrap.firstChild, rowsBox.parentNode);
        }
        var html = '';
        html += cwItems.length ? row('Continue Watching', cwItems, true, 'cw', '', 'preserve') : '';
        html += '<section class="row" id="plat" style="display:none"><div class="track" id="chips"></div></section>'; // no heading, like Android
        html += '<section class="row" id="trending" style="display:none"></section>';
        if (nextUpItems.length) html += nextUpRow('Next Up', nextUpItems, 'nextup');
        html += row('Latest Movies', (r[1] && r[1].Items) || [], false, 'lm', '', 'release') + row('Latest Series', (r[2] && r[2].Items) || [], false, 'ls', '', 'release');
        if (recentUploadItems.length) html += row('Recently Uploaded', recentUploadItems, false, 'recently-uploaded', 'data-regional="Recently Uploaded"', 'upload');
        html += popMoviesAllTime.length ? row('Popular Movies of All Time', popMoviesAllTime, false, 'popular-movies-alltime', 'data-regional="Popular Movies of All Time"', 'preserve') : '<section class="row" id="popular-movies-alltime" style="display:none"></section>';
        html += popSeriesAllTime.length ? row('Popular Series of All Time', popSeriesAllTime, false, 'popular-series-alltime', 'data-regional="Popular Series of All Time"', 'preserve') : '<section class="row" id="popular-series-alltime" style="display:none"></section>';
        html += '<div id="regional-rows"></div>';
        if (collectionsList.length) html += row('Collections', collectionsList, false, 'collections', 'data-regional="Collections"', 'preserve');
        if (((r[3] && r[3].Items) || []).length) html += row('My List', r[3].Items, false, 'fav', '', 'release');
        rowsBox.innerHTML = html;
        if (firstHero) { updateHero(firstHero); }
        wireCards(map); artFocus(map);
        function refreshAllTimePopularRows() {
          if (screen !== me) return;
          var pmSec = document.getElementById('popular-movies-alltime');
          if (pmSec) {
            var freshPopMovies = buildAllTimePopularByKind("Movie", globalLibLookup);
            freshPopMovies.forEach(function (it) { map[it.Id] = it; });
            if (freshPopMovies.length) {
              var pmFocused = Focus.current() && pmSec.contains(Focus.current());
              var pmWrap = document.createElement('div');
              pmWrap.innerHTML = row('Popular Movies of All Time', freshPopMovies, false, 'popular-movies-alltime', 'data-regional="Popular Movies of All Time"', 'preserve');
              if (pmWrap.firstChild) {
                pmSec.style.display = '';
                pmSec.innerHTML = pmWrap.firstChild.innerHTML;
                wireCards(map, pmSec);
                if (pmFocused) Focus.first('#popular-movies-alltime .card');
              }
            }
          }
          var psSec = document.getElementById('popular-series-alltime');
          if (psSec) {
            var freshPopSeries = buildAllTimePopularByKind("Series", globalLibLookup);
            freshPopSeries.forEach(function (it) { map[it.Id] = it; });
            if (freshPopSeries.length) {
              var psFocused = Focus.current() && psSec.contains(Focus.current());
              var psWrap = document.createElement('div');
              psWrap.innerHTML = row('Popular Series of All Time', freshPopSeries, false, 'popular-series-alltime', 'data-regional="Popular Series of All Time"', 'preserve');
              if (psWrap.firstChild) {
                psSec.style.display = '';
                psSec.innerHTML = psWrap.firstChild.innerHTML;
                wireCards(map, psSec);
                if (psFocused) Focus.first('#popular-series-alltime .card');
              }
            }
          }
        }
        function renderTrendingSection(tr) {
          if (screen !== me) return;
          if (tr && tr.length) {
            var trSec = document.getElementById('trending');
            if (trSec && !trSec.children.length) {
              tr.forEach(function (it) { map[it.Id] = it; });
              var visTr = tr.slice(0, 20);
              sectionRegistry['trending'] = { id: 'trending', title: 'Top 10 Trending Today', items: tr, wide: false, sortMode: 'preserve' };
              trSec.style.display = '';
              trSec.innerHTML = '<h2>Top 10 Trending Today</h2><div class="track">' + visTr.map(function (it, idx) { return rankCard(it, idx + 1); }).join('') + (tr.length >= 20 ? seeAllCardHtml('trending', 'Top 10 Trending Today', tr.length, false) : '') + '</div>';
              wireCards(map, trSec);
            }
          }
          refreshAllTimePopularRows();
          if (Focus.refreshRows) Focus.refreshRows();
        }
        function applyDiscoveryToHome(disc) {
          if (screen !== me || !disc) return;
          renderTrendingSection(disc.trending || []);
          disc.platforms = applyPlatformOrder(disc.platforms || []);
          var ps = (disc.platforms || []).filter(function (p) {
            if (!p) return false;
            var totalCount = (p.allItems && p.allItems.length) ? p.allItems.length : ((p.items && p.items.length) || 0);
            return totalCount >= 20 && ((p.items && p.items.length > 0) || p.slug === 'imdb');
          });
          if (ps.length) {
            var chips = document.getElementById('chips'), sec = document.getElementById('plat');
            if (chips && sec) {
              var platBySlug = {};
              ps.forEach(function (p) { platBySlug[p.slug] = p; homePlatBySlug[p.slug] = p; savePlatformHubEntry(p); });
              sec.style.display = '';
              chips.innerHTML = ps.map(function (p, i) { return '<div class="chip focusable" data-i="' + i + '" data-slug="' + h(p.slug) + '" data-name="' + h(p.name) + '" aria-label="Browse ' + h(p.name) + '">' + logoTile(p) + '<span class="cn">' + h(p.name) + '</span></div>'; }).join('');
              each(chips.querySelectorAll('.chip'), function (c) {
                c.onclick = function () {
                  if (suppressNextChipClick) { suppressNextChipClick = false; return; }
                  if (movingChip) { finishPlatformReorder(true); return; }
                  var slug = c.getAttribute('data-slug');
                  var targetP = platBySlug[slug] || ps[+c.getAttribute('data-i')];
                  if (targetP) go('platform', { p: targetP });
                };
              });
            }
          }
          var minRegH = param("mock") ? 1 : 20; var regs = (disc.regionals || []).filter(function (reg) { return reg && reg.items && reg.items.length >= minRegH; });
          var regBox = document.getElementById('regional-rows');
          if (regs.length && regBox) {
            regs.forEach(function (reg) { (reg.items || []).forEach(function (it) { if (!map[it.Id]) map[it.Id] = it; }); });
            regBox.innerHTML = regs.map(function (reg) {
              return row(reg.title, reg.items, false, 'reg-' + reg.slug, 'data-regional="' + h(reg.title) + '"', 'release');
            }).join('');
            wireCards(map, regBox);
          }
          refreshAllTimePopularRows();
          if (Focus.refreshRows) Focus.refreshRows();
        }
        if (discoveryCache) {
          applyDiscoveryToHome(discoveryCache);
        } else {
          var homeSeed = [].concat((r[1] && r[1].Items) || [], (r[2] && r[2].Items) || [], (r[3] && r[3].Items) || []);
          loadDiscovery(homeSeed, renderTrendingSection).then(applyDiscoveryToHome, function () {});
        }
        Focus.first(a.focus || '.card');
        a.focus = null;
      }

      if (a.fromBack && homeDataCache) {
        a.fromBack = false;
        root.innerHTML = topbar('<div class="btn focusable" id="search">Search</div><div class="btn focusable" id="update-btn">Update · v' + h(curVer()) + '</div>') +
          '<div id="ota-banner" class="ota-banner glass" style="display:none"></div>' +
          '<div class="rows"><div class="rows-inner" id="rows"></div></div>';
        document.getElementById('search').onclick = function () { go('search'); };
        document.getElementById('update-btn').onclick = openUpdateModal;
        refreshUpdateUi();
        paintHome(homeDataCache);
        Promise.all([
          Api.resume().catch(function () { return null; }),
          Api.favorites().catch(function () { return null; })
        ]).then(function (bg) {
          if (!homeDataCache) return;
          if (bg[0] && bg[0].Items) homeDataCache[0] = bg[0];
          if (bg[1] && bg[1].Items) homeDataCache[3] = bg[1];
        });
        return;
      }

      root.innerHTML = topbar('<div class="btn focusable" id="search">Search</div><div class="btn focusable" id="update-btn">Update · v' + h(curVer()) + '</div>') +
        '<div id="ota-banner" class="ota-banner glass" style="display:none"></div>' +
        homeSkeletonHtml();
      document.getElementById('search').onclick = function () { go('search'); };
      document.getElementById('update-btn').onclick = openUpdateModal;
      refreshUpdateUi();
      Promise.all([
        Api.resume(),
        Api.latest('Movie'),
        Api.latest('Series'),
        Api.favorites().catch(function () { return { Items: [] }; }),
        Api.nextUp(undefined, 16).catch(function () { return { Items: [] }; }),
        Api.recentlyUploaded(40).catch(function () { return { Items: [] }; }),
        Api.boxSets(40).catch(function () { return { Items: [] }; })
      ]).then(function (r) {
        homeDataCache = r;
        paintHome(r);
      }, onErr);
    },

    platform: function (a) {
      var me = screen;
      var searchOpen = !!a.searchOpen, searchTerm = a.searchTerm || '', p = a.p || {};
      if ((!p.items || !p.items.length) && p.slug && platformHubCache[p.slug]) {
        p = platformHubCache[p.slug];
        a.p = p;
      }
      if (!param('mock') && globalLibLookup.all.length < 50 && !a._libLoadedOnce) {
        root.innerHTML = topbar('<div class="btn focusable" id="plat-search-btn">Search ' + h(p.name || 'Platform') + '</div>') + homeSkeletonHtml();
        Api.libraryIndex().then(function (libRes) {
          if (libRes && libRes.Items) indexServerItems(libRes.Items);
          a._libLoadedOnce = true;
          if (screen === me) render();
        }, function () {
          a._libLoadedOnce = true;
          if (screen === me) render();
        });
        return;
      }
      var resolved = resolvePlatformCatalog(p, p.posts || [], globalLibLookup);
      p.items = resolved.onServer;
      p.allItems = resolved.all;
      savePlatformHubEntry(p);

      var allItems = (p.allItems || p.items || []).slice();
      var newReleases = sortByReleaseDateDesc(allItems);
      var movies = sortByReleaseDateDesc(allItems.filter(function (i) { return i.Type === 'Movie'; }));
      var series = sortByReleaseDateDesc(allItems.filter(function (i) { return i.Type === 'Series'; }));

      // 1. Trending on [Platform]: ONLY current 2025-2026 releases or explicitly flagged current trending items
      var trendingPool = allItems.filter(function (x) {
        var yr = x._platCatalogYear || itemReleaseYear(x) || 0;
        return yr >= 2025 || (x._platTrendRank && x._platTrendRank > 0);
      }).sort(function (x, y) {
        var tx = (x._platTrendRank && x._platTrendRank > 0) ? x._platTrendRank : (x._requestable ? 99 : 50);
        var ty = (y._platTrendRank && y._platTrendRank > 0) ? y._platTrendRank : (y._requestable ? 99 : 50);
        if (tx !== ty) return tx - ty;
        return releaseSortKey(y).localeCompare(releaseSortKey(x));
      });
      if (!trendingPool.length) trendingPool = newReleases.slice(0, 20);

      // 2. Popular on [Platform]: All-time flagship popular shows & movies on that platform
      var popularPool = allItems.filter(function (x) {
        return (x._platPopRank && x._platPopRank > 0) || !x._requestable;
      }).sort(function (x, y) {
        var px = (x._platPopRank && x._platPopRank > 0) ? x._platPopRank : (x._requestable ? 99 : 50);
        var py = (y._platPopRank && y._platPopRank > 0) ? y._platPopRank : (y._requestable ? 99 : 50);
        if (px !== py) return px - py;
        return releaseSortKey(y).localeCompare(releaseSortKey(x));
      });
      if (!popularPool.length) popularPool = allItems.slice(0, 20);

      var shelvesHtml = '';
      if (p.slug === 'imdb' && allItems.length) {
        var hasBucket = function (it, b) { return (it._imdbBucket === b) || (it._imdbBuckets && it._imdbBuckets[b]); };
        var imdbMovies30d = allItems.filter(function (x) { return x.Type === 'Movie' && (hasBucket(x, '30d') || itemReleaseYear(x) >= 2026); });
        var imdbShows30d = allItems.filter(function (x) { return x.Type === 'Series' && (hasBucket(x, '30d') || itemReleaseYear(x) >= 2026); });
        var imdbMoviesYear = allItems.filter(function (x) { return x.Type === 'Movie' && (hasBucket(x, 'year') || itemReleaseYear(x) >= 2025); });
        var imdbShowsYear = allItems.filter(function (x) { return x.Type === 'Series' && (hasBucket(x, 'year') || itemReleaseYear(x) >= 2025); });
        var imdbMoviesAllTime = allItems.filter(function (x) { return x.Type === 'Movie' && (hasBucket(x, 'alltime') || !x._requestable); });
        var imdbShowsAllTime = allItems.filter(function (x) { return x.Type === 'Series' && (hasBucket(x, 'alltime') || !x._requestable); });
        shelvesHtml += row('Top Movies on IMDb Last 30 Days', imdbMovies30d.length ? imdbMovies30d : movies, false, 'plat-imdb-movies-30d', '', 'preserve');
        shelvesHtml += row('Top Shows on IMDb Last 30 Days', imdbShows30d.length ? imdbShows30d : series, false, 'plat-imdb-shows-30d', '', 'preserve');
        shelvesHtml += row('Top Movies on IMDb This Year', imdbMoviesYear.length ? imdbMoviesYear : movies, false, 'plat-imdb-movies-year', '', 'preserve');
        shelvesHtml += row('Top Shows on IMDb This Year', imdbShowsYear.length ? imdbShowsYear : series, false, 'plat-imdb-shows-year', '', 'preserve');
        shelvesHtml += row('Top Movies on IMDb All Time', imdbMoviesAllTime.length ? imdbMoviesAllTime : movies, false, 'plat-imdb-movies-alltime', '', 'preserve');
        shelvesHtml += row('Top Shows on IMDb All Time', imdbShowsAllTime.length ? imdbShowsAllTime : series, false, 'plat-imdb-shows-alltime', '', 'preserve');
      } else if (allItems.length) {
        shelvesHtml += rankRow('Trending on ' + p.name, trendingPool, 'plat-trending');
        shelvesHtml += row('New Releases', newReleases, false, 'plat-catalog', '', 'release');
        shelvesHtml += row('Popular on ' + p.name, popularPool, false, 'plat-popular', '', 'preserve');
        if (movies.length) shelvesHtml += row('Movies', movies, false, 'plat-movies', '', 'release');
        if (series.length) shelvesHtml += row('Series', series, false, 'plat-series', '', 'release');
      } else {
        shelvesHtml = '<div class="empty">No titles from ' + h(p.name) + ' in your library</div>';
      }

      var keys = 'abcdefghijklmnopqrstuvwxyz0123456789'.split('');
      var searchHtml = '<div class="plat-search-wrap' + (searchOpen ? ' open' : '') + '" id="plat-search-wrap">' +
        '<div class="plat-search-bar" id="plat-search-box">' + (searchTerm ? h(searchTerm) : '<span class="muted">Search ' + h(p.name) + ' or MojaLoss library\u2026</span>') + '</div>' +
        '<div class="plat-keys">' +
          keys.map(function (k) { return '<div class="key focusable" data-pk="' + k + '">' + k.toUpperCase() + '</div>'; }).join('') +
          '<div class="key w2 focusable" data-pk=" ">Space</div><div class="key w2 focusable" data-pk="del">Delete</div><div class="key w2 focusable" data-pk="clear">Clear</div>' +
        '</div>' +
      '</div>';

      root.innerHTML = topbar('<div class="btn focusable" id="plat-search-btn">' + (searchOpen ? ('Search: ' + h(searchTerm || 'Open')) : ('Search ' + h(p.name))) + '</div>') +
        searchHtml +
        heroSectionHtml() +
        '<div class="rows" id="plat-rows"><div class="rows-inner" id="rows">' + shelvesHtml + '</div></div>';

      var map = {};
      allItems.forEach(function (i) { map[i.Id] = i; });
      if (trendingPool[0]) updateHero(trendingPool[0]);
      wireCards(map);
      artFocus(map);

      var sBtn = document.getElementById('plat-search-btn');
      if (sBtn) {
        sBtn.onclick = function () {
          a.searchOpen = !searchOpen;
          a.focus = '#plat-search-btn';
          render();
        };
      }

      function runPlatformSearch(q) {
        a.searchTerm = searchTerm = String(q == null ? searchTerm : q);
        var sb = document.getElementById('plat-search-box');
        if (sb) sb.innerHTML = searchTerm ? h(searchTerm) : ('<span class="muted">Search ' + h(p.name) + ' or MojaLoss library\u2026</span>');
        var rowsBox = document.getElementById('rows');
        if (!rowsBox) return Promise.resolve([]);
        var qTrim = searchTerm.trim();
        if (!qTrim) {
          rowsBox.innerHTML = shelvesHtml;
          wireCards(map, rowsBox);
          if (Focus.refreshRows) Focus.refreshRows();
          return Promise.resolve(allItems);
        }
        var qNorm = norm(qTrim);
        return Api.search(qTrim, 25).catch(function () { return { Items: [] }; }).then(function (sr) {
          if (screen !== me) return [];
          var serverHits = (sr && sr.Items) || [];
          if (serverHits.length) indexServerItems(serverHits);
          var reResolved = resolvePlatformCatalog(p, p.posts || [], globalLibLookup);
          p.items = reResolved.onServer;
          p.allItems = reResolved.all;
          allItems = p.allItems.slice();

          var seenRes = {}, results = [];
          function pushResult(it) {
            if (!it || !it.Id || seenRes[it.Id]) return;
            seenRes[it.Id] = 1;
            map[it.Id] = it;
            results.push(it);
          }
          serverHits.forEach(pushResult);
          allItems.forEach(function (it) {
            var n = norm(it.Name);
            if (n.indexOf(qNorm) >= 0) {
              var serverMatch = it._requestable ? findServerMatch({ name: it.Name, year: it.ProductionYear, kind: it.Type }, globalLibLookup, false) : it;
              pushResult(serverMatch || it);
            }
          });
          if (!results.length && qTrim.length >= 2) {
            pushResult(makeCatalogFallbackItem({ name: qTrim, year: 2026, kind: 'Movie' }, p.slug || 'platform', p.name || 'Platform'));
          }
          rowsBox.innerHTML = row('Search Results in ' + p.name + ' (' + results.length + ')', results, false, 'plat-search-results', '', 'preserve');
          wireCards(map, rowsBox);
          if (results[0]) updateHero(results[0]);
          if (Focus.refreshRows) Focus.refreshRows();
          return results;
        });
      }
      window.__mltvSearchPlatform = runPlatformSearch;

      var searchTimer = null;
      each(root.querySelectorAll('.plat-keys .key'), function (kEl) {
        kEl.onclick = function () {
          var kv = kEl.getAttribute('data-pk');
          if (kv === 'del') searchTerm = searchTerm.slice(0, -1);
          else if (kv === 'clear') searchTerm = '';
          else searchTerm += kv;
          a.searchTerm = searchTerm;
          var sb = document.getElementById('plat-search-box');
          if (sb) sb.innerHTML = searchTerm ? h(searchTerm) : ('<span class="muted">Search ' + h(p.name) + ' or MojaLoss library\u2026</span>');
          clearTimeout(searchTimer);
          searchTimer = setTimeout(function () { runPlatformSearch(searchTerm); }, 250);
        };
      });

      if (searchOpen && searchTerm) runPlatformSearch(searchTerm);

      var ensureLib = globalLibLookup.all.length > 50 ? Promise.resolve() : Api.libraryIndex().then(function (libRes) {
        if (libRes && libRes.Items) indexServerItems(libRes.Items);
      }, function () {});
      ensureLib.then(function () {
        return doubleCheckPlatformWithServerSearch(p, function (updatedP, replacedByReqId) {
          if (screen !== me || searchTerm || !replacedByReqId) return;
          Object.keys(replacedByReqId).forEach(function (reqId) {
            var realIt = replacedByReqId[reqId];
            if (!realIt || !realIt.Id) return;
            map[realIt.Id] = realIt;
            map[reqId] = realIt;
            each(root.querySelectorAll('.card[data-id="' + reqId + '"]'), function (cEl) {
              cEl.setAttribute('data-id', realIt.Id);
              cEl.classList.remove('req-card');
              var bEl = cEl.querySelector('.req-badge');
              if (bEl && bEl.parentNode) bEl.parentNode.removeChild(bEl);
              var newImg = resolveCardImage(realIt, cEl.classList.contains('wide'));
              if (newImg) {
                var artDiv = cEl.querySelector('.art');
                if (artDiv) artDiv.style.backgroundImage = "url('" + newImg + "')";
                var imgEl = cEl.querySelector('.card-img');
                if (imgEl) { imgEl.src = newImg; imgEl.style.display = ''; }
              }
              if (Focus.current() === cEl) updateHero(realIt, cEl);
            });
          });
        });
      });

      Focus.first(a.focus || (allItems.length ? '#plat-rows .card' : '#plat-search-btn'));
      a.focus = null;
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
        return (it.Type ? String(it.Type).toUpperCase() : 'MOVIE') + (it.ProductionYear ? ' \u00b7 ' + it.ProductionYear : '');
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
        var isReqItem = !!it._requestable;
        var isReqDone = isReqItem && isTitleRequested(it.Id);
        var nx = (!isReqItem && isEpisodic) ? resolveActiveEp(it, epsList) : null;
        var nxPos = (nx && nx.UserData && nx.UserData.PlaybackPositionTicks) || 0;
        var canRestart = !isReqItem && (resume || (isEpisodic && nxPos > 0));
        var playLabel = isReqItem ? (isReqDone ? '\u2713 Requested' : '+ Request Title') : (isEpisodic ? epPlayLabel(nx) : (resume ? 'Resume from ' + clock(pos / 1e7) : 'Play'));
        var watchedTarget = isEpisodic ? (nx || it) : it;
        var isPlayed = !!(watchedTarget.UserData && watchedTarget.UserData.Played);

        var rawPeople = (it.People && it.People.length) ? it.People : ((seriesItem && seriesItem.People) || []);
        var people = (rawPeople.filter(function (p) {
          return !p.Type || p.Type === 'Actor' || p.Type === 'Director' || p.Type === 'Creator' || p.Type === 'Writer';
        })).slice(0, 14);

        var hasEps = !!(epsList && epsList.length);
        var seasonsHtml = '', epsHtml = '', seasonEps = [], seasonNums = [];
        if (hasEps) {
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
          seasonsHtml = '<div class="filters row" id="season-pills">' + seasonNums.map(function (sNum) {
            var label = (sNum === 0 ? 'Specials' : ('Season ' + sNum)) + ' \u00b7 ' + bySeason[sNum].length;
            return '<div class="pill focusable' + (sNum === a.season ? ' on' : '') + '" data-season="' + sNum + '">' + h(label) + '</div>';
          }).join('') + '</div>';
          epsHtml = '<section class="row" id="eps"><div class="ep-preview" id="ep-preview"></div><div class="track">' +
            seasonEps.map(function (ep) { return epCard(ep, nx && nx.Id === ep.Id); }).join('') + '</div></section>';
        } else if (isEpisodic && isLoading) {
          seasonsHtml = '<div class="filters" id="season-pills-skel"><div class="skel-pill skel-pulse" style="height:44px;width:148px"></div><div class="skel-pill skel-pulse" style="height:44px;width:148px"></div></div>';
          epsHtml = '<section class="row" id="eps-skel"><div class="loading-inline"><span class="spinner-ring"></span><span>Loading episodes\u2026</span></div><div class="track">' +
            [1, 2, 3, 4].map(function () { return '<div class="skel-card ep skel-pulse"></div>'; }).join('') + '</div></section>';
        }

        var castHtml = people.length ? ('<section class="row" id="cast-row"><h2>Cast &amp; Crew</h2><div class="track">' +
          people.map(function (p, idx) { return personCard(p, idx); }).join('') + '</div>' +
          '<div class="cast-preview" id="cast-preview" style="display:none">' +
            '<div class="cast-prev-head">' +
              '<div class="cast-prev-left"><div class="cast-prev-avatar" id="cast-prev-avatar"></div><div><div class="cast-prev-sub" id="cast-prev-sub"></div><div class="cast-prev-title" id="cast-prev-title"></div></div></div>' +
            '</div>' +
            '<div class="cast-works-track" id="cast-works-track"></div>' +
          '</div>' +
          '</section>') : '';

        var simHtml = (similarItems && similarItems.length) ? ('<section class="row" id="similar-row"><h2>More Like This</h2><div class="track">' +
          similarItems.map(function (i) { return card(i, false); }).join('') + '</div>' +
          '<div class="sim-preview" id="sim-preview" style="display:none">' +
            '<div class="sim-prev-main">' +
              '<div>' +
                '<div class="sim-prev-top"><span class="sim-prev-eyebrow" id="sim-prev-eyebrow"></span></div>' +
                '<div class="sim-prev-title" id="sim-prev-title"></div>' +
                '<div class="meta" id="sim-prev-meta" style="margin-top:0"></div>' +
                '<div class="sim-prev-desc" id="sim-prev-desc"></div>' +
              '</div>' +
              '<div class="sim-prev-footer"><div class="sim-prev-credits" id="sim-prev-credits"></div></div>' +
            '</div>' +
          '</div>' +
          '</section>') : '';

        var eyebrow = detailEyebrowText(it, seasonNums.length, epsList ? epsList.length : 0);
        var credits = detailCreditsHtml(it, seriesItem);
        var metaYear = it.ProductionYear || (seriesItem && seriesItem.ProductionYear);
        var metaRating = it.OfficialRating || (seriesItem && seriesItem.OfficialRating);
        var metaGenres = ((it.Genres && it.Genres.length) ? it.Genres : ((seriesItem && seriesItem.Genres) || [])).slice(0, 3).join(' \u00b7 ');
        var headerOverview = (it.Overview && String(it.Overview).trim()) ? it.Overview : ((seriesItem && seriesItem.Overview) || '');
        var descHtml = headerOverview
          ? ('<p id="detail-desc">' + h(headerOverview) + '</p>')
          : (isLoading ? '<div id="detail-desc" style="margin:10px 0 14px"><div class="skel-bar skel-pulse" style="width:680px;margin-bottom:8px"></div><div class="skel-bar skel-pulse" style="width:480px"></div></div>' : '<p id="detail-desc"></p>');

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
          '</div>' +
          seasonsHtml + epsHtml + castHtml + simHtml + '</div></div>';

        function updateSimPreview(sIt) {
          var el = document.getElementById('sim-preview');
          if (!el) return;
          if (!sIt) { if (el.style.display !== 'none') { el.style.display = 'none'; if (Focus.refreshRows) Focus.refreshRows(); } return; }
          var wasHidden = el.style.display === 'none';
          el.style.display = 'flex';
          if (wasHidden && Focus.refreshRows) Focus.refreshRows();
          var kindTxt = sIt.Type ? String(sIt.Type).toUpperCase() : 'MOVIE';
          var eb = document.getElementById('sim-prev-eyebrow');
          var ti = document.getElementById('sim-prev-title');
          var me = document.getElementById('sim-prev-meta');
          var de = document.getElementById('sim-prev-desc');
          var cr = document.getElementById('sim-prev-credits');
          var artEl = document.getElementById('sim-prev-art');
          var artTag = document.getElementById('sim-prev-art-tag');
          if (eb) eb.textContent = [kindTxt, sIt.ProductionYear].filter(Boolean).join(' \u00b7 ');
          if (ti) ti.textContent = sIt.Name || '';
          if (me) me.innerHTML = heroMetaHtml(sIt);
          if (de) de.textContent = (sIt.Overview && String(sIt.Overview).trim()) ? sIt.Overview : '';
          if (cr) {
            var credHtml = detailCreditsHtml(sIt, null);
            cr.innerHTML = credHtml ? credHtml.replace(/^<div[^>]*>|<\/div>$/g, '') : '';
          }
          if (artEl) {
            var sImg = Api.img(sIt, 'Backdrop', 720) || Api.img(sIt, 'Primary', 520);
            artEl.style.backgroundImage = sImg ? 'url(\'' + sImg + '\')' : '';
          }
          if (artTag) {
            var tagTxt = [kindTxt, sIt.ProductionYear, sIt.OfficialRating].filter(Boolean).join(' \u00b7 ');
            artTag.textContent = tagTxt;
            artTag.style.display = tagTxt ? '' : 'none';
          }
        }
        function setDetailTab(tabName, focusSel) {
          activeTab = a.activeTab = tabName;
          var epsEl = document.getElementById('eps');
          var epPrevEl = document.getElementById('ep-preview');
          var castEl = document.getElementById('cast-row');
          var simEl = document.getElementById('similar-row');
          var simPrevEl = document.getElementById('sim-preview');
          var castPrevEl = document.getElementById('cast-preview');
          if (epsEl) epsEl.classList.toggle('detail-tab-hidden', tabName !== 'eps');
          if (epPrevEl) epPrevEl.style.display = tabName === 'eps' ? '' : 'none';
          if (castEl) castEl.classList.toggle('detail-tab-hidden', tabName !== 'cast');
          if (simEl) simEl.classList.toggle('detail-tab-hidden', tabName !== 'similar');
          if (simPrevEl) simPrevEl.style.display = 'none';
          if (castPrevEl) castPrevEl.style.display = 'none';
          each(root.querySelectorAll('#season-pills .pill'), function (p) {
            var isJump = p.getAttribute('data-jump');
            if (isJump) {
              var want = isJump.indexOf('#cast-row') === 0 ? 'cast' : 'similar';
              p.classList.toggle('on', tabName === want);
            } else {
              var sN = +p.getAttribute('data-season');
              p.classList.toggle('on', tabName === 'eps' && sN === a.season);
            }
          });
          if (Focus.refreshRows) Focus.refreshRows();
          if (focusSel) Focus.first(focusSel);
        }
        var personWorksCache = {};
        function updateCastPreview(p) {
          var castPrevEl = document.getElementById('cast-preview');
          if (!castPrevEl) return;
          if (!p) { if (castPrevEl.style.display !== 'none') { castPrevEl.style.display = 'none'; if (Focus.refreshRows) Focus.refreshRows(); } return; }
          var wasCastHidden = castPrevEl.style.display === 'none';
          castPrevEl.style.display = 'flex';
          if (wasCastHidden && Focus.refreshRows) Focus.refreshRows();
          var subEl = document.getElementById('cast-prev-sub');
          var titleEl = document.getElementById('cast-prev-title');
          var avEl = document.getElementById('cast-prev-avatar');
          var trackEl = document.getElementById('cast-works-track');
          if (!titleEl || !trackEl) return;
          var roleTxt = (p.Type === 'Director' || p.Type === 'Creator') ? ('DIRECTED BY \u00b7 ' + (p.Role || p.Type)) : ('STARRING AS ' + (p.Role ? String(p.Role).toUpperCase() : 'CAST'));
          if (subEl) subEl.textContent = roleTxt + ' \u00b7 ON MOJALOSS';
          titleEl.textContent = 'Titles Featuring ' + (p.Name || '');
          if (avEl) {
            var pImg = Api.personImg(p, p.PrimaryImageTag, 180);
            var initials = String(p.Name || '').replace(/[^A-Za-z0-9 ]/g, '').split(' ').filter(Boolean).slice(0, 2).map(function (w) { return w[0]; }).join('').toUpperCase() || '?';
            if (pImg) { avEl.style.backgroundImage = 'url(\'' + pImg + '\')'; avEl.textContent = ''; }
            else { avEl.style.backgroundImage = ''; avEl.textContent = initials; }
          }
          var key = p.Id || p.Name;
          function renderWorks(list) {
            if (!document.getElementById('cast-works-track')) return;
            var items = (list && list.length) ? list.slice(0, 8) : [it].concat(similarItems || []).slice(0, 8);
            var wMap = {};
            items.forEach(function (x) { if (x && x.Id) { wMap[x.Id] = x; focusMap[x.Id] = x; } });
            trackEl.innerHTML = items.map(function (x) { return card(x, false); }).join('');
            wireCards(wMap, trackEl, function (xIt) { go('detail', { item: xIt }); });
          }
          if (personWorksCache[key]) {
            renderWorks(personWorksCache[key]);
          } else {
            trackEl.innerHTML = [1, 2, 3, 4, 5, 6].map(function () { return '<div class="skel-card skel-pulse" style="width:176px;height:256px"></div>'; }).join('');
            Api.byPerson(p.Id, p.Name).then(function (r) {
              var found = (r && r.Items) || [];
              personWorksCache[key] = found;
              renderWorks(found);
            }, function () { renderWorks([]); });
          }
        }
        window.MLTV_setDetailTab = setDetailTab;
        if (seasonEps.length) {
          var initPreviewEp = (nx && nx.ParentIndexNumber === a.season) ? nx : seasonEps[0];
          updateEpPreview(initPreviewEp, nx && initPreviewEp && nx.Id === initPreviewEp.Id);
        }

        document.getElementById('play').onclick = function () {
          if (it._requestable) {
            requestTitle(it, function () { a.focus = '#play'; draw(it); });
            return;
          }
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
          Api.setFavorite(favTarget.Id, !fav).then(function () {
            favTarget.UserData = favTarget.UserData || {};
            favTarget.UserData.IsFavorite = !fav;
            if (homeDataCache && homeDataCache[3] && Array.isArray(homeDataCache[3].Items)) {
              homeDataCache[3].Items = !fav
                ? [favTarget].concat(homeDataCache[3].Items.filter(function (x) { return x && x.Id !== favTarget.Id; }))
                : homeDataCache[3].Items.filter(function (x) { return x && x.Id !== favTarget.Id; });
            }
            toast(!fav ? 'Added to My List' : 'Removed from My List');
            a.focus = '#fav';
            draw(it);
          }, onErr);
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
              if (p && (p.Id || p.Name)) go('person', { person: p, fallbackItems: [it].concat(similarItems || []) });
            };
          });
        }
        if (similarItems && similarItems.length) {
          var sm = {}; similarItems.forEach(function (s) { sm[s.Id] = s; focusMap[s.Id] = s; });
          wireCards(sm, document.getElementById('similar-row'), function (sIt) { go('detail', { item: sIt }); });
        }
        Focus.onFocus = function (el) {
          if (!el) return;
          var inSim = !!(el.closest && el.closest('#similar-row'));
          var inCast = !!(el.closest && el.closest('#cast-row'));
          if (!inSim) updateSimPreview(null);
          if (!inCast) updateCastPreview(null);
          var id = el.getAttribute && el.getAttribute('data-id');
          if (el.classList && el.classList.contains('person-card')) {
            var pIdx = +el.getAttribute('data-person-idx');
            if (people[pIdx]) updateCastPreview(people[pIdx]);
            return;
          }
          if (id && focusMap[id]) {
            var fIt = focusMap[id];
            art(Api.img(fIt, 'Backdrop', 1280) || Api.img(fIt, 'Primary', 1280) || bgUrl);
            scheduleBgPreview(fIt);
            if (el.classList.contains('ep-card')) {
              updateEpPreview(fIt, nx && nx.Id === fIt.Id);
            } else if (inSim) {
              updateSimPreview(fIt);
            }
          } else if (el.closest && el.closest('#detail-top')) {
            art(bgUrl);
            scheduleBgPreview(it);
          }
        };
        Focus.first(a.focus || '#play'); a.focus = null;
      }
      if (a.item && a.item._requestable) {
        var matchedSync = findServerMatch({ name: a.item.Name, year: a.item.ProductionYear, kind: a.item.Type }, globalLibLookup, true);
        if (matchedSync && matchedSync.Id) {
          a.item = cloneServerItemForPlatform(matchedSync, a.item._platformSlug || '', a.item._platformName || '');
        } else {
          isLoading = false;
          draw(a.item);
          return;
        }
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

    person: function (a) {
      var me = screen, p = a.person || {};
      var roleLabel = (p.Type === 'Director' || p.Type === 'Creator') ? 'Director & Creator' : (p.Role ? ('Actor \u00b7 ' + p.Role) : 'Cast & Crew');
      root.innerHTML = topbar() +
        topHeroSectionHtml() +
        '<div class="rows"><div class="rows-inner" id="person-rows">' +
          '<section class="row"><div class="loading-inline"><span class="spinner-ring"></span><span>Loading titles with ' + h(p.Name || '') + '\u2026</span></div><div class="track">' +
          [1, 2, 3, 4, 5, 6].map(function () { return '<div class="skel-card skel-pulse"></div>'; }).join('') +
          '</div></section>' +
        '</div></div>';
      var eb = document.getElementById('hero-eyebrow');
      var ti = document.getElementById('hero-title');
      var de = document.getElementById('hero-desc');
      if (eb) eb.textContent = 'CAST & CREW \u00b7 ' + roleLabel.toUpperCase();
      if (ti) ti.textContent = p.Name || 'Filmography';
      if (de) de.textContent = 'Browse movies and series featuring ' + (p.Name || 'this artist') + ' on MojaLoss.';
      Api.byPerson(p.Id, p.Name).then(function (r) {
        if (screen !== me) return;
        var items = (r && r.Items && r.Items.length) ? r.Items : (a.fallbackItems || []);
        var movies = items.filter(function (x) { return x && x.Type === 'Movie'; });
        var series = items.filter(function (x) { return x && x.Type === 'Series'; });
        var map = {};
        items.forEach(function (x) { if (x && x.Id) map[x.Id] = x; });
        var pRows = document.getElementById('person-rows');
        if (!pRows) return;
        var html = '';
        var pEyebrowAttr = 'data-regional="' + h((p.Name || 'Artist').toUpperCase() + ' \u00b7 ' + roleLabel.toUpperCase() + ' \u00b7 ' + items.length + (items.length === 1 ? ' TITLE' : ' TITLES')) + '"';
        if (movies.length && series.length) {
          html += row('Movies with ' + (p.Name || ''), movies, false, 'person-movies', pEyebrowAttr);
          html += row('Series with ' + (p.Name || ''), series, false, 'person-series', pEyebrowAttr);
        } else {
          html += row('Titles Featuring ' + (p.Name || ''), items, false, 'person-all', pEyebrowAttr);
        }
        pRows.innerHTML = html;
        if (items[0]) {
          updateHero(items[0]);
          if (eb) eb.textContent = (p.Name || 'Artist').toUpperCase() + ' \u00b7 ' + items.length + (items.length === 1 ? ' TITLE' : ' TITLES');
        }
        wireCards(map, pRows, function (xIt) { go('detail', { item: xIt }); });
        artFocus(map);
        Focus.first('#person-rows .card');
      }, function () {
        if (screen !== me) return;
        var pRows = document.getElementById('person-rows');
        if (pRows) pRows.innerHTML = '<div class="empty">Could not load titles for ' + h(p.Name || '') + '</div>';
      });
    },

    browse: function (a) {
      var title = a.title || 'All Titles';
      var rawItems = (a.items || []).slice();
      var kind = a.kind || 'all';
      var genre = a.genre || 'all';
      var sort = a.sort || (a.sortMode === 'upload' ? 'upload' : (a.sortMode === 'preserve' ? 'preserve' : 'release'));

      var genresMap = {}, genresList = [];
      rawItems.forEach(function (it) {
        ((it && it.Genres) || []).forEach(function (g) {
          if (g && !genresMap[g] && genresList.length < 7) { genresMap[g] = 1; genresList.push(g); }
        });
      });
      var hasMovies = rawItems.some(function (x) { return x && x.Type === 'Movie'; });
      var hasSeries = rawItems.some(function (x) { return x && x.Type === 'Series'; });

      var filtered = rawItems.filter(function (it) {
        if (!it) return false;
        if (kind === 'movie' && it.Type !== 'Movie') return false;
        if (kind === 'series' && it.Type !== 'Series') return false;
        if (genre !== 'all' && (!it.Genres || it.Genres.indexOf(genre) < 0)) return false;
        return true;
      });
      if (sort === 'upload') filtered = sortByUploadDateDesc(filtered);
      else if (sort === 'az') filtered = filtered.slice().sort(function (x, y) { return String(x.Name || '').localeCompare(String(y.Name || '')); });
      else if (sort !== 'preserve') filtered = sortByReleaseDateDesc(filtered);

      var bPill = function (attr, val, lbl, on) {
        return '<div class="pill focusable' + (on ? ' on' : '') + '" data-' + attr + '="' + h(val) + '">' + h(lbl) + '</div>';
      };
      var typePills = (hasMovies && hasSeries)
        ? (bPill('bkind', 'all', 'All (' + rawItems.length + ')', kind === 'all') + bPill('bkind', 'movie', 'Movies', kind === 'movie') + bPill('bkind', 'series', 'Series', kind === 'series') + '<span class="pill-sep"></span>')
        : '';
      var genrePills = genresList.length
        ? (bPill('bgenre', 'all', 'All Genres', genre === 'all') + genresList.map(function (g) { return bPill('bgenre', g, g, genre === g); }).join('') + '<span class="pill-sep"></span>')
        : '';
      var sortPills = (a.sortMode === 'preserve' ? bPill('bsort', 'preserve', 'Most Popular', sort === 'preserve') : '') +
        bPill('bsort', 'release', 'Recently Released', sort === 'release') +
        (a.sortMode === 'upload' ? bPill('bsort', 'upload', 'Recently Uploaded', sort === 'upload') : '') +
        bPill('bsort', 'az', 'A\u2013Z', sort === 'az');

      root.innerHTML = '<div id="screen-browse" style="display:flex;flex-direction:column;width:1920px;height:1080px">' +
        topbar('<div class="muted" style="font-size:22px;font-weight:700">' + h(title) + ' \u00b7 ' + filtered.length + (filtered.length === 1 ? ' Title' : ' Titles') + '</div>') +
        '<div class="filters" id="browse-filters">' + typePills + genrePills + sortPills + '</div>' +
        '<div class="browse-stage"><div class="browse-grid" id="browse-grid">' +
          (filtered.length ? filtered.map(function (it) { return card(it, false); }).join('') : '<div class="empty">No matching titles</div>') +
        '</div></div>' +
      '</div>';

      var map = {};
      filtered.forEach(function (it) { map[it.Id] = it; });
      wireCards(map, document.getElementById('browse-grid'));
      artFocus(map);
      if (filtered[0]) art(Api.img(filtered[0], 'Backdrop', 1280) || Api.img(filtered[0], 'Primary', 1280));

      each(root.querySelectorAll('#browse-filters .pill'), function (pEl) {
        pEl.onclick = function () {
          if (pEl.hasAttribute('data-bkind')) { a.kind = pEl.getAttribute('data-bkind'); a.focus = '.pill[data-bkind="' + a.kind + '"]'; }
          else if (pEl.hasAttribute('data-bgenre')) { a.genre = pEl.getAttribute('data-bgenre'); a.focus = '.pill[data-bgenre="' + a.genre + '"]'; }
          else if (pEl.hasAttribute('data-bsort')) { a.sort = pEl.getAttribute('data-bsort'); a.focus = '.pill[data-bsort="' + a.sort + '"]'; }
          render();
        };
      });
      Focus.first(a.focus || (filtered.length ? '#browse-grid .card' : '#browse-filters .pill'));
      a.focus = null;
    },

    search: function (a) {
      a = a || {};
      var term = a.term || '';
      var keys = 'abcdefghijklmnopqrstuvwxyz0123456789'.split('');
      root.innerHTML = topbar('<div class="muted" style="font-size:20px;font-weight:700">Search Library &amp; Catalog</div>') +
        '<div class="search-split" id="tv-021-netflix-search">' +
          '<div class="search-left">' +
            '<div class="searchbox glass" id="sb"><span id="q" class="searchbox"></span></div>' +
            '<div class="keys">' +
              keys.map(function (k) { return '<div class="key focusable" data-k="' + k + '">' + k.toUpperCase() + '</div>'; }).join('') +
              '<div class="key w2 focusable" data-k=" ">Space</div>' +
              '<div class="key w2 focusable" data-k="del">Delete</div>' +
              '<div class="key w2 focusable" data-k="clear">Clear</div>' +
            '</div>' +
            '<div class="search-hint">Use D-pad to type \u00b7 Press \u25b6 from right edge to browse results</div>' +
          '</div>' +
          '<div class="search-right" id="res"></div>' +
        '</div>';

      var t, me = screen, seq = 0, map = {};
      artFocus(map);

      function box() {
        var qEl = document.getElementById('q') || document.getElementById('sb');
        if (qEl) qEl.innerHTML = term ? h(term) : '<span class="muted">Search movies and series</span>';
      }

      function buildTopSearchesPool() {
        var out = [], seenIds = {}, seenNK = {};
        function addIt(it) {
          if (!it || !it.Id || seenIds[it.Id]) return;
          var nk = keyNK(it.Name || '', it.Type || 'Movie');
          if (nk && seenNK[nk]) return;
          seenIds[it.Id] = 1;
          if (nk) seenNK[nk] = 1;
          map[it.Id] = it;
          out.push(it);
        }
        if (discoveryCache && discoveryCache.trending) {
          discoveryCache.trending.forEach(addIt);
        }
        if (homeDataCache) {
          ((homeDataCache[1] && homeDataCache[1].Items) || []).slice(0, 8).forEach(addIt);
          ((homeDataCache[2] && homeDataCache[2].Items) || []).slice(0, 8).forEach(addIt);
        }
        buildAllTimePopularByKind('Movie', globalLibLookup).slice(0, 12).forEach(addIt);
        buildAllTimePopularByKind('Series', globalLibLookup).slice(0, 12).forEach(addIt);
        return out.slice(0, 25);
      }

      function renderDefaultTopSearches() {
        var res = document.getElementById('res');
        if (!res) return;
        var items = buildTopSearchesPool();
        res.innerHTML = '<div class="search-res-head"><h2 class="search-res-title">Top Searches &amp; Popular</h2><span class="search-res-sub">' + items.length + (items.length === 1 ? ' Title' : ' Titles') + '</span></div>' +
          '<div class="search-grid-stage"><div class="search-grid" id="search-grid">' +
            (items.length ? items.map(function (it) { return card(it, false); }).join('') : '<div class="empty">Loading popular titles\u2026</div>') +
          '</div></div>';
        wireCards(map, res);
        if (items[0]) art(Api.img(items[0], 'Backdrop', 1280) || Api.img(items[0], 'Primary', 1280));
      }

      function findCatalogSearchMatches(qTrim) {
        var qNorm = norm(qTrim);
        if (!qNorm || qNorm.length < 3) return [];
        var matches = [], seenNK = {};
        Object.keys(PLATFORM_CATALOG_DB || {}).forEach(function (slug) {
          var pName = PLATFORM_DISPLAY_NAMES[slug] || slug;
          (PLATFORM_CATALOG_DB[slug] || []).forEach(function (entry) {
            if (!entry || !entry.name) return;
            var n = norm(entry.name);
            if (n.indexOf(qNorm) < 0) return;
            var kind = entry.kind || 'Movie';
            var nk = keyNK(entry.name, kind);
            if (!nk || seenNK[nk]) return;
            seenNK[nk] = 1;
            var m = findServerMatch(entry, globalLibLookup, false);
            if (m && m.Id) {
              matches.push(cloneServerItemForPlatform(m, slug, pName));
            } else {
              matches.push(makeCatalogFallbackItem(entry, slug, pName));
            }
          });
        });
        return matches;
      }

      function run() {
        var res = document.getElementById('res'), my = ++seq;
        if (!res) return;
        var qTrim = term.trim();
        if (!qTrim) {
          renderDefaultTopSearches();
          return;
        }
        if (qTrim.length < 2) return;
        res.innerHTML = '<div class="search-res-head"><h2 class="search-res-title">Searching \u201c' + h(qTrim) + '\u201d\u2026</h2><span class="spinner-ring"></span></div>' +
          '<div class="search-grid-stage"><div class="search-grid">' +
          [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map(function () { return '<div class="skel-card skel-pulse"></div>'; }).join('') + '</div></div>';
        Api.search(qTrim, 30).then(function (r) {
          if (screen !== me || my !== seq) return;
          var serverItems = (r && r.Items) || [];
          if (serverItems.length) indexServerItems(serverItems);
          var combined = [], seenIds = {}, seenNK = {};
          function pushHit(it) {
            if (!it || !it.Id || seenIds[it.Id]) return;
            var nk = keyNK(it.Name || '', it.Type || 'Movie') + ':' + (it.ProductionYear || '');
            if (nk && seenNK[nk]) return;
            seenIds[it.Id] = 1;
            if (nk) seenNK[nk] = 1;
            map[it.Id] = it;
            combined.push(it);
          }
          serverItems.forEach(pushHit);
          if (qTrim.length >= 3) {
            findCatalogSearchMatches(qTrim).forEach(pushHit);
          }
          if (combined.length) {
            res.innerHTML = '<div class="search-res-head"><h2 class="search-res-title">Results for \u201c' + h(qTrim) + '\u201d</h2><span class="search-res-sub">' + combined.length + (combined.length === 1 ? ' Title' : ' Titles') + '</span></div>' +
              '<div class="search-grid-stage"><div class="search-grid" id="search-grid">' +
                combined.map(function (it) { return card(it, false); }).join('') +
              '</div></div>';
            wireCards(map, res);
            if (combined[0]) art(Api.img(combined[0], 'Backdrop', 1280) || Api.img(combined[0], 'Primary', 1280));
          } else {
            res.innerHTML = '<div class="search-res-head"><h2 class="search-res-title">Results for \u201c' + h(qTrim) + '\u201d</h2></div>' +
              '<div class="empty">No matches for \u201c' + h(qTrim) + '\u201d</div>';
          }
        }, function (e) {
          if (e && e.status === 401) return onErr(e);
          if (my === seq) res.innerHTML = '<div class="empty">' + (e && e.offline ? 'You are offline.' : 'Search failed.') + ' Press OK on a key to try again.</div>';
        });
      }

      each(root.querySelectorAll('.key'), function (k) {
        k.onclick = function () {
          var v = k.getAttribute('data-k');
          if (v === 'del') term = term.slice(0, -1);
          else if (v === 'clear') term = '';
          else term = term + v;
          a.term = term;
          box();
          clearTimeout(t);
          if (!term.trim()) {
            seq++;
            renderDefaultTopSearches();
          } else {
            t = setTimeout(run, 300);
          }
        };
      });

      box();
      if (term.trim()) {
        run();
      } else {
        renderDefaultTopSearches();
        if (!globalLibLookup.all.length && !homeDataCache) {
          Promise.all([
            Api.latest('Movie').catch(function () { return { Items: [] }; }),
            Api.latest('Series').catch(function () { return { Items: [] }; })
          ]).then(function (r) {
            if (screen !== me || term.trim()) return;
            var latestPool = [].concat((r[0] && r[0].Items) || [], (r[1] && r[1].Items) || []);
            if (latestPool.length) {
              indexServerItems(latestPool);
              renderDefaultTopSearches();
            }
          });
        }
      }
      Focus.first(a.focus || '.key');
      a.focus = null;
      screen.cleanup = function () { clearTimeout(t); };
    }
  };

  /* Player: HTML5 video (Tizen plays HLS natively). Server-side audio/subtitle choice via PlaybackInfo; external VTT tracks rendered by the browser. */
  var LANG_NAMES = { eng: 'English', hin: 'Hindi', ben: 'Bengali', tam: 'Tamil', tel: 'Telugu', mal: 'Malayalam', kan: 'Kannada', mar: ' Marathi', urd: 'Urdu', kor: 'Korean', jpn: 'Japanese', zho: 'Chinese', chi: 'Chinese', spa: 'Spanish', fre: 'French', fra: 'French', ger: 'German', deu: 'German', ita: 'Italian', por: 'Portuguese', rus: 'Russian', ara: 'Arabic', tur: 'Turkish' };
  function isUndLang(lang) {
    var l = String(lang || '').trim().toLowerCase();
    return !l || l === 'und' || l === 'unknown' || l === 'mul' || l === 'zxx' || l === 'mis';
  }
  function formatAudioBadge(s) {
    if (!s) return '';
    var parts = [];
    if (!isUndLang(s.Language)) parts.push(String(s.Language).trim().toUpperCase());
    if (s.Codec) parts.push(String(s.Codec).trim().toUpperCase());
    if (s.ChannelLayout) parts.push(String(s.ChannelLayout).trim().toUpperCase());
    if (parts.length) return parts.join(' \u00b7 ');
    var dt = String(s.DisplayTitle || '').replace(/\b(?:und|undetermined|default)\b[\s\-–·:]*/gi, '').replace(/[\s\-–·:]+$/, '').trim();
    return dt ? dt.toUpperCase() : '';
  }
  function formatSubBadge(s, hasSubs) {
    if (!s) return hasSubs ? 'CC OFF' : '';
    if (!isUndLang(s.Language)) return 'CC \u00b7 ' + String(s.Language).trim().toUpperCase();
    var dt = String(s.DisplayTitle || '').replace(/\b(?:und|undetermined|default)\b[\s\-–·:]*/gi, '').replace(/[\s\-–·:]+$/, '').trim();
    return dt ? ('CC \u00b7 ' + dt.toUpperCase()) : 'CC ON';
  }
  function formatTrackLabel(s, isSub) {
    if (!s) return '';
    var dt = String(s.DisplayTitle || '').trim();
    if (dt) {
      dt = dt.replace(/^und(?:etermined)?\b[\s\-–·:]*/i, '').replace(/\b(?:und|undetermined)\b[\s\-–·:]*/gi, '').trim();
      if (dt) return h(dt);
    }
    if (!isUndLang(s.Language)) {
      var l = String(s.Language).trim().toLowerCase();
      return h(LANG_NAMES[l] || String(s.Language));
    }
    return h('Track ' + (s.Index != null ? s.Index : 1));
  }
  function parseMediaSegments(raw, item) {
    raw = raw || {};
    var out = [], seen = {};
    function addSeg(type, startSec, endSec) {
      startSec = Number(startSec || 0);
      endSec = Number(endSec || 0);
      if (!isFinite(startSec) || !isFinite(endSec) || endSec <= startSec + 2) return;
      var normType = /recap/i.test(type) ? 'Recap' : (/outro|credit/i.test(type) ? 'Outro' : 'Intro');
      var key = normType + ':' + Math.round(startSec);
      if (seen[key]) return;
      seen[key] = 1;
      var label = normType === 'Recap' ? 'Skip Recap' : (normType === 'Outro' ? 'Skip Credits' : 'Skip Intro');
      out.push({ type: normType, label: label, startSec: startSec, endSec: endSec });
    }
    var msItems = (raw.mediaSegments && raw.mediaSegments.Items) || (item && item.MediaSegments) || [];
    msItems.forEach(function (seg) {
      if (!seg) return;
      var st = seg.StartTicks != null ? (seg.StartTicks / 1e7) : (seg.Start != null ? seg.Start : 0);
      var et = seg.EndTicks != null ? (seg.EndTicks / 1e7) : (seg.End != null ? seg.End : 0);
      addSeg(seg.Type || 'Intro', st, et);
    });
    [raw.introTimestamps, raw.introSkipper].forEach(function (pl) {
      if (!pl || typeof pl !== 'object') return;
      if (pl.Introduction && (pl.Introduction.Valid !== false)) addSeg('Intro', pl.Introduction.IntroStart, pl.Introduction.IntroEnd);
      if (pl.Credits && (pl.Credits.Valid !== false)) addSeg('Outro', pl.Credits.IntroStart, pl.Credits.IntroEnd);
      if (pl.IntroStart != null && pl.IntroEnd != null) addSeg('Intro', pl.IntroStart, pl.IntroEnd);
    });
    var chaps = (raw.chapters && raw.chapters.length ? raw.chapters : (item && item.Chapters)) || [];
    var durSec = (item && item.RunTimeTicks) ? (item.RunTimeTicks / 1e7) : 0;
    var isMovieItem = !!(item && item.Type === 'Movie');
    for (var i = 0; i < chaps.length; i++) {
      var ch = chaps[i];
      if (!ch || ch.StartPositionTicks == null) continue;
      var cStart = ch.StartPositionTicks / 1e7;
      var cEnd = (i + 1 < chaps.length && chaps[i + 1].StartPositionTicks != null) ? (chaps[i + 1].StartPositionTicks / 1e7) : durSec;
      var name = String(ch.Name || '').trim();
      if (isMovieItem) {
        if (cStart <= 15 && cEnd <= 62 && /\b(intro|opening logos|studio logos|logos|title card)\b/i.test(name)) {
          addSeg('Intro', cStart, Math.max(cStart + 15, cEnd - 2));
        } else if (/\b(credits|end credits|ending|ed|outro)\b/i.test(name)) {
          addSeg('Outro', cStart, cEnd);
        }
      } else {
        if (/\b(intro|opening|op|theme song|title sequence|main titles|opening credits|logos|studio logos)\b/i.test(name)) addSeg('Intro', cStart, cEnd);
        else if (/\b(recap|previously on)\b/i.test(name)) addSeg('Recap', cStart, cEnd);
        else if (/\b(credits|end credits|ending|ed|outro)\b/i.test(name)) addSeg('Outro', cStart, cEnd);
      }
    }
    var hasIntro = out.some(function (s) { return s.type === 'Intro'; });
    if (!hasIntro && chaps.length >= 2 && chaps[0] && chaps[1] && chaps[1].StartPositionTicks != null) {
      var c0 = (chaps[0].StartPositionTicks || 0) / 1e7, c1 = chaps[1].StartPositionTicks / 1e7;
      if (isMovieItem) {
        if (c0 <= 5 && c1 >= 25 && c1 <= 55) { addSeg('Intro', c0, Math.max(22, c1 - 2)); hasIntro = true; }
      } else if (c0 <= 10 && c1 >= 20 && c1 <= 160) {
        addSeg('Intro', c0, c1); hasIntro = true;
      }
    }
    if (!hasIntro && isMovieItem && !raw._initOnly && (durSec >= 1200 || !durSec)) {
      addSeg('Intro', 3, 46);
    }
    out.sort(function (a, b) { return a.startSec - b.startSec; });
    return out;
  }
  function play(it, startTicks, opt) {
    stopBgPreview();
    opt = opt || {};
    opt.startTicks = startTicks || 0;
    Api.playbackInfo(it.Id, opt).then(function (info) {
      if (player) player.teardown();
      var src = info.MediaSources[0], streams = src.MediaStreams || [];
      var audios = streams.filter(function (s) { return s.Type === 'Audio'; }), subs = streams.filter(function (s) { return s.Type === 'Subtitle'; });
      var audioIdx = opt.audio != null ? opt.audio : src.DefaultAudioStreamIndex, subIdx = opt.sub != null ? opt.sub : -1;
      var curAudio = audios.filter(function (s) { return s.Index === audioIdx; })[0] || audios[0] || null;
      var curSub = subs.filter(function (s) { return s.Index === subIdx; })[0] || null;
      var curAudioLabel = formatAudioBadge(curAudio);
      var curSubLabel = formatSubBadge(curSub, subs.length > 0);
      var url = Api.streamUrl(it, info), sessionId = info.PlaySessionId, retries = 0, stallT, bufShowT, hideT, hudT, seekCommitT, tick, menu = null, ended = false;
      var scrubStreak = 0, lastScrubAt = 0, pendingSeekSec = null, lastProgressSec = -1, nextEpCandidate = null;
      var FIT_KEY = 'mltv.fitMode';
      var fitMode = 'fit';
      try { var savedFit = localStorage.getItem(FIT_KEY); if (savedFit === 'fill' || savedFit === 'stretch' || savedFit === 'fit') fitMode = savedFit; } catch (e) {}
      function fitBadgeText(m) { return m === 'fill' ? 'ZOOM TO FIT' : (m === 'stretch' ? 'STRETCH TO FIT' : 'ORIGINAL'); }
      function fitHudText(m) { return m === 'fill' ? 'Zoom to Fit' : (m === 'stretch' ? 'Stretch to Fit' : 'Original'); }
      var eyebrowTxt = it.SeriesName
        ? (String(it.SeriesName).toUpperCase() + ' \u00b7 S' + (it.ParentIndexNumber || 1) + ' E' + (it.IndexNumber || 1))
        : '';
      var el = document.createElement('div'); el.id = 'player';
      el.innerHTML = '<video autoplay preload="auto" playsinline></video>' +
        '<div class="player-top" id="player-top">' +
          '<div>' + (eyebrowTxt ? '<div class="player-eyebrow" id="player-eyebrow">' + h(eyebrowTxt) + '</div>' : '') + '<div class="player-title" id="player-title">' + h(it.Name || '') + '</div></div>' +
          '<div class="player-badges">' +
            (curAudioLabel ? '<span class="p-badge" id="player-audio-badge">' + h(curAudioLabel) + '</span>' : '') +
            (curSubLabel ? '<span class="p-badge' + (curSub ? ' accent' : '') + '" id="player-sub-badge">' + h(curSubLabel) + '</span>' : '') +
            '<span class="p-badge' + (fitMode !== 'fit' ? ' accent' : '') + '" id="player-fit-badge">' + fitBadgeText(fitMode) + '</span>' +
          '</div>' +
        '</div>' +
        '<div class="player-hud" id="player-hud"></div>' +
        '<div class="buffer" id="buf" style="display:none"><span class="spinner-ring"></span><span id="buf-txt">Buffering\u2026</span></div>' +
        '<div class="skip-intro-btn" id="skip-intro-btn" style="display:none"><span class="skip-icon">\u23ed</span><span id="skip-intro-label">Skip Intro</span><span class="skip-key-badge">OK</span></div>' +
        '<div class="upnext-card" id="upnext-card"><div class="upnext-eyebrow">Up Next</div><div class="upnext-title" id="upnext-title"></div><div class="upnext-sub" id="upnext-sub">Playing automatically when this episode ends</div></div>' +
        '<div class="osd" id="osd">' +
          '<div class="bar"><span class="pb-buf" id="pb-buf" style="width:0%"></span><b id="pb" style="width:0%"><span class="pb-thumb" id="pb-thumb"></span></b></div>' +
          '<div class="row2">' +
            '<div class="time-group"><span id="pt" class="time-cur">0:00</span><span class="time-sep">/</span><span id="pd" class="time-tot">' + clock((it.RunTimeTicks || 0) / 1e7) + '</span></div>' +
            '<div class="row2-right">' +
              '<div class="osd-hints" id="osd-hints"><span class="hint-pill" id="ok-hint-pill"><b>OK</b> Play</span><span class="hint-pill"><b>\u25c0 \u25b6</b> Seek 10s</span><span class="hint-pill"><b>\u25b2</b> Audio &amp; Subtitles</span><span class="hint-pill" id="fit-hint-pill"><b>\u25bc</b> Fit to Screen</span><span class="hint-pill"><b>Back</b> Exit</span></div>' +
              '<span class="time-rem" id="prem">-' + clock((it.RunTimeTicks || 0) / 1e7) + '</span>' +
            '</div>' +
          '</div>' +
        '</div>' +
        '<div class="menu glass" id="menu" style="display:none"></div>';
      document.body.appendChild(el);
      var v = el.querySelector('video'), topEl = el.querySelector('#player-top'), hudEl = el.querySelector('#player-hud'), fitBadgeEl = el.querySelector('#player-fit-badge'), osd = el.querySelector('#osd'), premEl = el.querySelector('#prem'), buf = el.querySelector('#buf'), bufTxt = el.querySelector('#buf-txt'), menuEl = el.querySelector('#menu'), ptEl = el.querySelector('#pt'), pdEl = el.querySelector('#pd'), pbEl = el.querySelector('#pb'), pbBufEl = el.querySelector('#pb-buf'), skipBtnEl = el.querySelector('#skip-intro-btn'), skipLblEl = el.querySelector('#skip-intro-label'), upNextEl = el.querySelector('#upnext-card'), upNextTitle = el.querySelector('#upnext-title'), upNextSub = el.querySelector('#upnext-sub');
      var segments = parseMediaSegments({ _initOnly: true }, it), activeSegment = null, skippedSegments = {};
      function checkActiveSegment(curSec) {
        if (!skipBtnEl) return;
        var found = null;
        for (var i = 0; i < segments.length; i++) {
          var s = segments[i];
          var skKey = s.type + ':' + Math.round(s.startSec);
          if (!skippedSegments[skKey] && curSec >= s.startSec && curSec < s.endSec - 0.5) {
            found = s;
            break;
          }
        }
        activeSegment = found;
        if (found && !menu) {
          if (skipLblEl) skipLblEl.textContent = found.label;
          skipBtnEl.classList.toggle('osd-up', !osd.classList.contains('hide'));
          skipBtnEl.style.display = 'inline-flex';
        } else {
          skipBtnEl.style.display = 'none';
        }
      }
      function triggerSkipSegment() {
        if (!activeSegment) return false;
        var seg = activeSegment;
        skippedSegments[seg.type + ':' + Math.round(seg.startSec)] = 1;
        activeSegment = null;
        if (skipBtnEl) skipBtnEl.style.display = 'none';
        var target = seg.endSec;
        pendingSeekSec = null;
        v.currentTime = target;
        renderTimeline(target);
        flashHud('\u23ed ' + seg.label.replace(/^Skip\s+/i, 'Skipped '), false);
        return true;
      }
      if (skipBtnEl) skipBtnEl.onclick = triggerSkipSegment;
      Api.mediaSegments(it.Id).then(function (raw) {
        var detected = parseMediaSegments(raw, it);
        if (detected.length) {
          segments = detected;
          if (player && player.v === v) checkActiveSegment(pos());
        }
      }, function () {});
      function applyFitClass(m) {
        v.classList.remove('fit-contain', 'fit-cover', 'fit-fill');
        v.classList.add(m === 'fill' ? 'fit-cover' : (m === 'stretch' ? 'fit-fill' : 'fit-contain'));
        var ar = (v.videoWidth && v.videoHeight) ? (v.videoWidth / v.videoHeight) : 1.777;
        if (m === 'fill') {
          v.style.objectFit = 'cover';
          v.style.transform = (ar > 1.95) ? 'scale(1.02)' : 'scale(1.34)';
        } else if (m === 'stretch') {
          v.style.objectFit = 'fill';
          v.style.transform = 'none';
        } else {
          v.style.objectFit = 'contain';
          v.style.transform = 'none';
        }
        if (fitBadgeEl) {
          fitBadgeEl.textContent = fitBadgeText(m);
          fitBadgeEl.classList.toggle('accent', m !== 'fit');
        }
        if (menuEl) {
          each(menuEl.querySelectorAll('.fit-opt'), function (fo) {
            fo.classList.toggle('on', fo.getAttribute('data-fit') === m);
          });
        }
      }
      function setFitMode(m, silent) {
        if (m !== 'fill' && m !== 'stretch' && m !== 'fit') m = 'fit';
        fitMode = m;
        try { localStorage.setItem(FIT_KEY, m); } catch (e) {}
        applyFitClass(m);
        if (!silent) flashHud(fitHudText(m), false);
      }
      function cycleFitMode() {
        var next = fitMode === 'fit' ? 'fill' : (fitMode === 'fill' ? 'stretch' : 'fit');
        setFitMode(next, false);
      }
      applyFitClass(fitMode);
      function setBuf(msg, showBuf) {
        if (!showBuf && bufShowT) { clearTimeout(bufShowT); bufShowT = null; }
        if (bufTxt) bufTxt.textContent = msg;
        else buf.textContent = msg;
        buf.style.display = showBuf ? 'flex' : 'none';
      }
      function flashHud(txt, iconOnly) {
        if (!hudEl) return;
        hudEl.textContent = txt;
        hudEl.classList.toggle('icon-only', !!iconOnly);
        hudEl.style.display = 'inline-flex';
        clearTimeout(hudT);
        hudT = setTimeout(function () { if (hudEl) hudEl.style.display = 'none'; }, 750);
      }
      function updateBufferedBar() {
        if (!pbBufEl) return;
        var d = v.duration || (it.RunTimeTicks || 0) / 1e7;
        if (!d || !isFinite(d) || d <= 0 || !v.buffered || !v.buffered.length) return;
        var cur = pos(), end = 0;
        for (var i = 0; i < v.buffered.length; i++) {
          if (v.buffered.start(i) <= cur + 1 && v.buffered.end(i) > end) end = v.buffered.end(i);
        }
        if (!end) end = v.buffered.end(v.buffered.length - 1);
        pbBufEl.style.width = Math.min(100, Math.max(0, 100 * end / d)) + '%';
      }
      var subStream = subs.filter(function (s) { return s.Index === subIdx; })[0];
      if (subStream && (subStream.DeliveryMethod === 'External' || subStream.IsExternal || subStream.IsTextSubtitleStream)) {
        var tr = document.createElement('track'); tr.kind = 'subtitles'; tr.default = true; tr.src = subStream.DeliveryUrl ? Api.jf() + subStream.DeliveryUrl : Api.subtitleUrl(it, src, subStream); v.appendChild(tr);
        tr.track && (tr.track.mode = 'showing');
      }
      if (it.Type === 'Episode' && it.SeriesId) {
        Api.nextUp(it.SeriesId).then(function (n) {
          var cand = (n && n.Items && n.Items[0]) || null;
          if (cand && cand.Id !== it.Id) {
            nextEpCandidate = cand;
            if (upNextTitle) upNextTitle.textContent = 'S' + (cand.ParentIndexNumber || 1) + 'E' + (cand.IndexNumber || 1) + ' \u00b7 ' + (cand.Name || 'Next Episode');
          }
        }, function () {});
      }
      var startSec = (startTicks || 0) / 1e7;
      function load(at) {
        v.src = url;
        v.load();
        v.addEventListener('loadedmetadata', function once() {
          v.removeEventListener('loadedmetadata', once);
          applyFitClass(fitMode);
          if (at > 0) v.currentTime = at;
          var p = v.play();
          if (p && p.catch) p.catch(function () {});
        });
      }
      load(startSec);
      function pos() { return pendingSeekSec != null ? pendingSeekSec : (v.currentTime || startSec); }
      function base() { return { ItemId: it.Id, PlaySessionId: sessionId, MediaSourceId: src.Id, PositionTicks: Math.floor(pos() * 1e7), IsPaused: v.paused, AudioStreamIndex: audioIdx, SubtitleStreamIndex: subIdx, PlayMethod: src.TranscodingUrl ? 'Transcode' : 'DirectPlay', CanSeek: true }; }
      function showPausedOverlay() {
        clearTimeout(hideT);
        osd.classList.add('paused');
        osd.classList.remove('hide');
        if (topEl) topEl.classList.remove('hide');
      }
      function hideAllOverlays() {
        clearTimeout(hideT);
        osd.classList.remove('paused');
        osd.classList.add('hide');
        if (topEl) topEl.classList.add('hide');
      }
      function showSeekBarOnly() {
        if (!v.paused && topEl) topEl.classList.add('hide');
        osd.classList.toggle('paused', !!v.paused);
        osd.classList.remove('hide');
        clearTimeout(hideT);
        hideT = setTimeout(function () {
          if (!v.paused && !menu) osd.classList.add('hide');
        }, 2400);
      }
      function show() {
        osd.classList.remove('hide');
        if (topEl) topEl.classList.remove('hide');
        clearTimeout(hideT);
        hideT = setTimeout(function () {
          if (!v.paused && !menu) {
            osd.classList.add('hide');
            if (topEl) topEl.classList.add('hide');
          }
        }, 2200);
      }
      var recovering = false;
      function recover() {
        // network drop or DirectPlay stall: if DirectPlay failed on real server, fall back to HLS transcode/remux automatically
        if (ended || !player) return;
        if (!src.TranscodingUrl && !opt.noDirect && !param('mock') && retries === 0) {
          var atHls = pos();
          player.teardown(); player = null;
          play(it, Math.floor(atHls * 1e7), { audio: audioIdx, sub: subIdx, source: src.Id, noDirect: true });
          return;
        }
        if (retries >= 5) { recovering = false; setBuf('Playback stopped: connection lost. Press OK to retry.', true); retries = 0; player.waitingRetry = true; return; }
        recovering = true;
        var at = pos(), wait = Math.min(30000, 2000 * Math.pow(2, retries++));
        setBuf('Connection problem \u2014 retrying in ' + Math.round(wait / 1000) + 's\u2026', true);
        setTimeout(function () { if (player && player.v === v) { recovering = false; startSec = at; load(at); } }, wait);
      }
      function renderTimeline(curSec) {
        checkActiveSegment(curSec);
        var d = v.duration || (it.RunTimeTicks || 0) / 1e7;
        ptEl.textContent = clock(curSec);
        if (d && isFinite(d) && d > 0) {
          pdEl.textContent = clock(d);
          var rem = Math.max(0, d - curSec);
          if (premEl) premEl.textContent = '-' + clock(rem);
          pbEl.style.width = Math.min(100, Math.max(0, 100 * curSec / d)) + '%';
          if (upNextEl && nextEpCandidate && rem <= 30 && d > 45 && !menu) {
            upNextEl.style.display = 'flex';
            if (upNextSub) upNextSub.textContent = 'Starting in ' + Math.max(1, Math.ceil(rem)) + 's';
          }
        }
      }
      v.ontimeupdate = function () {
        if (pendingSeekSec == null) renderTimeline(v.currentTime);
        updateBufferedBar();
        if (!recovering && lastProgressSec >= 0 && Math.abs(v.currentTime - lastProgressSec) > 0.12) {
          setBuf('', false);
          clearTimeout(stallT);
        }
        lastProgressSec = v.currentTime;
      };
      function syncPauseUi() {
        if (!osd) return;
        osd.classList.toggle('paused', !!v.paused);
      }
      v.onprogress = updateBufferedBar;
      v.oncanplay = function () { if (!recovering) setBuf('', false); };
      v.oncanplaythrough = function () { if (!recovering) setBuf('', false); };
      v.onplay = function () { syncPauseUi(); };
      v.onpause = function () { if (!ended) showPausedOverlay(); };
      v.onplaying = function () { recovering = false; setBuf('', false); syncPauseUi(); retries = 0; clearTimeout(stallT); Api.reportProgress('', base()); };
      v.onwaiting = function () {
        clearTimeout(bufShowT);
        bufShowT = setTimeout(function () {
          if (player && player.v === v && !v.paused && v.readyState < 3) setBuf('Buffering\u2026', true);
        }, 750);
        clearTimeout(stallT);
        stallT = setTimeout(recover, 16000);
      };
      v.onerror = function () { recover(); };
      v.onended = function () {
        ended = true; Api.reportProgress('Stopped', base());
        if (it.Type === 'Episode' && it.SeriesId) Api.nextUp(it.SeriesId).then(function (n) { var nx = (n.Items || [])[0]; if (nx && nx.Id !== it.Id) { toast('Up next: ' + nx.Name); play(nx, 0); } else stopPlayer(); }, function () { stopPlayer(); });
        else stopPlayer();
      };
      tick = setInterval(function () { if (!v.paused) Api.reportProgress('Progress', base()); }, 10000);
      function seekBy(dir, isFF) {
        var now = Date.now();
        if (now - lastScrubAt < 450) scrubStreak = Math.min(6, scrubStreak + 1);
        else scrubStreak = 0;
        lastScrubAt = now;
        var step = isFF ? 30 : (scrubStreak >= 4 ? 60 : scrubStreak >= 2 ? 30 : 10);
        var delta = dir * step;
        var dur = (v.duration && isFinite(v.duration)) ? v.duration : ((it.RunTimeTicks || 0) / 1e7 || 1e9);
        var baseSec = pendingSeekSec != null ? pendingSeekSec : v.currentTime;
        var targetSec = Math.max(0, Math.min(dur - 1, baseSec + delta));
        pendingSeekSec = targetSec;
        renderTimeline(targetSec);
        clearTimeout(seekCommitT);
        seekCommitT = setTimeout(function () {
          if (pendingSeekSec != null && player && player.v === v) {
            v.currentTime = pendingSeekSec;
            pendingSeekSec = null;
          }
        }, 120);
        flashHud((delta > 0 ? '\u25b6\u25b6 +' : '\u25c0\u25c0 ') + delta + 's', false);
      }
      function openMenu() {
        if (!v.paused) {
          clearTimeout(hideT);
          osd.classList.add('hide');
          if (topEl) topEl.classList.add('hide');
        }
        menuEl.innerHTML = '<h2>Audio</h2>' + (audios.length ? audios.map(function (s) { return '<div class="opt focusable' + (s.Index === audioIdx ? ' on' : '') + '" data-a="' + s.Index + '">' + formatTrackLabel(s, false) + '</div>'; }).join('') : '<div class="empty">Original Audio</div>') +
          '<h2 style="margin-top:24px">Subtitles</h2><div class="opt focusable' + (subIdx === -1 ? ' on' : '') + '" data-s="-1">Off</div>' + subs.map(function (s) { return '<div class="opt focusable' + (s.Index === subIdx ? ' on' : '') + '" data-s="' + s.Index + '">' + formatTrackLabel(s, true) + '</div>'; }).join('') +
          '<h2 style="margin-top:22px">Screen Fit</h2><div class="fit-row">' +
            '<div class="fit-opt focusable' + (fitMode === 'fit' ? ' on' : '') + '" data-fit="fit">Original</div>' +
            '<div class="fit-opt focusable' + (fitMode === 'fill' ? ' on' : '') + '" data-fit="fill">Zoom to Fit</div>' +
            '<div class="fit-opt focusable' + (fitMode === 'stretch' ? ' on' : '') + '" data-fit="stretch">Stretch to Fit</div>' +
          '</div>';
        menuEl.style.display = 'block'; menu = true;
        if (upNextEl) upNextEl.style.display = 'none';
        each(menuEl.querySelectorAll('.opt'), function (o) { o.onclick = function () {
          var na = o.hasAttribute('data-a') ? +o.getAttribute('data-a') : audioIdx, ns = o.hasAttribute('data-s') ? +o.getAttribute('data-s') : subIdx;
          if (na === audioIdx && ns === subIdx) return closeMenu();
          Api.reportProgress('Stopped', base()); var at = pos(); player.teardown(); player = null;
          play(it, Math.floor(at * 1e7), { audio: na, sub: ns, source: src.Id }); // restart stream at the same spot with the new tracks
        }; });
        each(menuEl.querySelectorAll('.fit-opt'), function (fo) {
          fo.onclick = function () {
            setFitMode(fo.getAttribute('data-fit'), false);
            closeMenu();
          };
        });
        Focus.first('#menu .opt.on');
      }
      function closeMenu() {
        if (!menu) return false;
        menu = null;
        menuEl.style.display = 'none';
        Focus.set(null);
        if (v.paused) showPausedOverlay();
        else hideAllOverlays();
        return true;
      }
      function moveMenuFocus(k) {
        var cur = Focus.current();
        var opts = Array.prototype.slice.call(menuEl.querySelectorAll('.opt.focusable'));
        var fits = Array.prototype.slice.call(menuEl.querySelectorAll('.fit-opt.focusable'));
        var optIdx = opts.indexOf(cur);
        var fitIdx = fits.indexOf(cur);
        if (optIdx >= 0) {
          if (k === K.UP && optIdx > 0) Focus.set(opts[optIdx - 1]);
          else if (k === K.DOWN) {
            if (optIdx + 1 < opts.length) Focus.set(opts[optIdx + 1]);
            else if (fits.length) Focus.set(menuEl.querySelector('.fit-opt.on') || fits[0]);
          }
        } else if (fitIdx >= 0) {
          if (k === K.LEFT && fitIdx > 0) Focus.set(fits[fitIdx - 1]);
          else if (k === K.RIGHT && fitIdx + 1 < fits.length) Focus.set(fits[fitIdx + 1]);
          else if (k === K.UP && opts.length) Focus.set(opts[opts.length - 1]);
        } else if (opts.length) {
          Focus.set(opts[0]);
        }
      }
      player = { el: el, v: v, it: it, recover: recover, closeMenu: closeMenu, base: base,
        fitMode: function () { return fitMode; },
        setFitMode: setFitMode,
        cycleFitMode: cycleFitMode,
        segments: function () { return segments; },
        setSegments: function (s) { segments = s || []; checkActiveSegment(pos()); },
        activeSegment: function () { return activeSegment; },
        triggerSkipSegment: triggerSkipSegment,
        parseMediaSegments: parseMediaSegments,
        formatAudioBadge: formatAudioBadge,
        formatTrackLabel: formatTrackLabel,
        showUpNext: function (titleTxt) {
          if (!upNextEl) return;
          if (upNextTitle && titleTxt) upNextTitle.textContent = titleTxt;
          upNextEl.style.display = 'flex';
        },
        teardown: function () { clearInterval(tick); clearTimeout(stallT); clearTimeout(bufShowT); clearTimeout(hideT); clearTimeout(hudT); clearTimeout(seekCommitT); v.onerror = v.onended = v.onwaiting = v.ontimeupdate = v.onplaying = v.onprogress = v.oncanplay = v.oncanplaythrough = v.onpause = v.onplay = null; v.removeAttribute('src'); try { v.load(); } catch (e) {} el.parentNode && el.parentNode.removeChild(el); },
        stop: function () { if (!ended) Api.reportProgress('Stopped', base()); this.teardown(); },
        keys: function (k) {
          if (menu) {
            if (k === K.UP || k === K.DOWN || k === K.LEFT || k === K.RIGHT) moveMenuFocus(k);
            else if (k === K.OK) { var c = Focus.current(); c && c.onclick && c.onclick(); }
            return true;
          }
          if (player.waitingRetry && k === K.OK) { player.waitingRetry = false; recover(); return true; }
          if (k === K.OK && activeSegment && !v.paused) {
            triggerSkipSegment();
            return true;
          }
          if (k === K.OK || k === K.PLAYPAUSE) {
            if (v.paused) { v.play(); hideAllOverlays(); flashHud('\u25b6', true); }
            else { v.pause(); showPausedOverlay(); flashHud('\u275a\u275a', true); }
          }
          else if (k === K.PLAY) { v.play(); hideAllOverlays(); flashHud('\u25b6', true); }
          else if (k === K.PAUSE) { v.pause(); showPausedOverlay(); flashHud('\u275a\u275a', true); }
          else if (k === K.RIGHT || k === K.FF) { showSeekBarOnly(); seekBy(1, k === K.FF); }
          else if (k === K.LEFT || k === K.RW) { showSeekBarOnly(); seekBy(-1, k === K.RW); }
          else if (k === K.UP) openMenu();
          else if (k === K.DOWN) { if (!v.paused) hideAllOverlays(); cycleFitMode(); }
          else if (k === K.STOP) stopPlayer();
          return true;
        } };
      show();
    }, onErr);
  }
  function stopPlayer() { if (player) { var p = player; player = null; p.stop(); if (screen && screen.name === 'detail') render(); if (pendingAutoUpdate) { var u = pendingAutoUpdate; pendingAutoUpdate = null; setTimeout(function () { maybeTriggerAutoUpdate({ hasUpdate: true, remote: u }); }, 300); } } }

  document.addEventListener('keydown', function (e) {
    var k = e.keyCode;
    if (!player) stopBgPreview();
    if (movingChip) {
      e.preventDefault();
      if (k === K.LEFT) movePlatformChip('L');
      else if (k === K.RIGHT) movePlatformChip('R');
      else if (k === K.OK || k === K.UP || k === K.DOWN) finishPlatformReorder(true);
      else if (k === K.BACK || k === 8 || k === 27) finishPlatformReorder(false);
      return;
    }
    if (k === K.BACK || k === 8 || k === 27) { e.preventDefault(); return back(); }
    if (player) { e.preventDefault(); player.keys(k); return; }
    var map = { 37: 'L', 38: 'U', 39: 'R', 40: 'D' };
    if (map[k]) {
      if (okHoldTimer) { clearTimeout(okHoldTimer); okHoldTimer = null; }
      e.preventDefault();

      Focus.move(map[k]);
    } else if (k === K.OK) {
      var c = Focus.current();
      if (c && c.classList && c.classList.contains('chip') && c.closest && c.closest('#plat')) {
        e.preventDefault();
        if (e.repeat) {
          if (okHoldTimer) { clearTimeout(okHoldTimer); okHoldTimer = null; }
          suppressNextChipClick = true;
          enterPlatformReorder(c);
          return;
        }
        if (!okHoldTimer) {
          okHoldTimer = setTimeout(function () {
            okHoldTimer = null;
            var cur = Focus.current();
            if (cur === c && !movingChip) {
              suppressNextChipClick = true;
              enterPlatformReorder(c);
            }
          }, 500);
        }
        return;
      }
      if (c && c.onclick) c.onclick();
    }
  });
  document.addEventListener('keyup', function (e) {
    if (e.keyCode !== K.OK) return;
    if (okHoldTimer) {
      clearTimeout(okHoldTimer);
      okHoldTimer = null;
      var c = Focus.current();
      if (!movingChip && c && c.classList && c.classList.contains('chip') && c.onclick) {
        c.onclick();
      }
    }
    suppressNextChipClick = false;
  });
  document.addEventListener('mouseover', function (e) { var f = e.target.closest && e.target.closest('.focusable'); if (f) Focus.set(f); });
  document.addEventListener('visibilitychange', function () { if (document.hidden && player) player.v.pause(); }); // Smart Hub / source switch

  window.MLTV = {
    go: go,
    expandToDetail: expandToDetail,
    play: play,
    fitViewport: fitViewport,
    homeSkeletonHtml: homeSkeletonHtml,
    openUpdateModal: openUpdateModal,
    closeUpdateModal: closeUpdateModal,
    startPlatformMove: function (slug) {
      var c = document.querySelector('#plat .chip' + (slug ? '[data-slug="' + slug + '"]' : '.focused')) || Focus.current();
      if (c && c.classList && c.classList.contains('chip')) enterPlatformReorder(c);
    },
    platformHubCache: function () { return platformHubCache; },
    findServerMatch: findServerMatch,
    scheduleBgPreview: scheduleBgPreview,
    stopBgPreview: stopBgPreview,
    bgTrailerState: function () { var v = document.getElementById('bg-trailer-video'); return { playing: !!(bd && bd.classList.contains('trailer-playing')), spotlight: document.body.classList.contains('trailer-spotlight'), hasTimer: !!bgTrailerTimer, itemId: bgTrailerItem && bgTrailerItem.Id, src: v ? v.getAttribute('src') : null, paused: v ? v.paused : true }; },
    buildAllTimePopularByKind: buildAllTimePopularByKind,
    indexServerItems: indexServerItems,
    sectionRegistry: function () { return sectionRegistry; },
    searchPlatform: function (q) { return window.__mltvSearchPlatform ? window.__mltvSearchPlatform(q) : Promise.resolve([]); },
    requestTitle: requestTitle,
    isTitleRequested: isTitleRequested,
    registeredKeys: registered,
    player: function () { return player; },
    screen: function () { return screen && screen.name; }
  }; // test hooks
  if (window.__mltvBootOk) window.__mltvBootOk();
  go(Api.signedIn() ? 'home' : 'signin');
  if (window.MLTV_OTA) {
    setTimeout(function () { MLTV_OTA.checkForUpdates(true); }, 600);
    if (!param("mock") && !param("ota")) {
      setInterval(function () {
        if (!document.hidden && window.MLTV_OTA && !MLTV_OTA.state().checking && !MLTV_OTA.state().updating) {
          MLTV_OTA.checkForUpdates(true);
        }
      }, 180000);
      document.addEventListener("visibilitychange", function () {
        if (!document.hidden && window.MLTV_OTA && !MLTV_OTA.state().checking && !MLTV_OTA.state().updating) {
          MLTV_OTA.checkForUpdates(true);
        }
      });
    }
  }
})();
