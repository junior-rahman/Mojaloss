/* Ultra-fast O(1) D-pad navigation & full-screen viewport shelf manager for Tizen TV */
var Focus = (function () {
  var cur = null;
  function scopeRoot() { return document.getElementById('player') || document.getElementById('update-modal') || document; }
  function vis(e) { return e && !e.closest('.detail-tab-hidden') && (e.offsetParent !== null || !!e.closest('#player')); }
  function all() {
    var nl = scopeRoot().querySelectorAll('.focusable'), out = [];
    for (var i = 0; i < nl.length; i++) if (vis(nl[i])) out.push(nl[i]);
    return out;
  }
  function getScaleY() {
    var rootEl = document.getElementById('root');
    if (rootEl) {
      var rh = rootEl.getBoundingClientRect().height;
      if (rh > 100 && Math.abs(rh - 1080) > 2) return rh / 1080;
    }
    return 1;
  }
  function rowOffsetTop(row, inner) {
    if (!row || !inner) return 0;
    var rRect = row.getBoundingClientRect();
    var iRect = inner.getBoundingClientRect();
    return Math.round((rRect.top - iRect.top) / (getScaleY() || 1));
  }
  function visibleRows(inner) {
    var ch = inner ? inner.children : [], out = [];
    for (var i = 0; i < ch.length; i++) {
      var el = ch[i];
      if (el.classList && el.classList.contains('row') && !el.classList.contains('detail-tab-hidden') && el.style.display !== 'none') {
        if (el.querySelector('.focusable')) out.push(el);
      } else if (el.id === 'regional-rows') {
        var rch = el.children;
        for (var j = 0; j < rch.length; j++) {
          if (rch[j].classList && rch[j].classList.contains('row') && !rch[j].classList.contains('detail-tab-hidden') && rch[j].style.display !== 'none' && rch[j].querySelector('.focusable')) {
            out.push(rch[j]);
          }
        }
      }
    }
    return out;
  }
  function updateRowVisibility(inner, activeRow, y, isHome) {
    var rows = visibleRows(inner);
    var activeIdx = rows.indexOf(activeRow);
    if (activeIdx < 0) activeIdx = 0;
    for (var i = 0; i < rows.length; i++) {
      var r = rows[i];
      var isAbove = isHome ? (i < activeIdx) : (y > 0 && i < activeIdx);
      r.classList.toggle('row-above', isAbove);
      r.classList.toggle('row-active', !isAbove && (i === activeIdx || !isHome));
      r.classList.toggle('row-next', isHome && i === activeIdx + 1);
      r.classList.toggle('row-far', isHome && i > activeIdx + 1);
    }
  }
  function syncInnerScroll(inner, row, force) {
    if (!inner || !row) return;
    if (!force && inner._lastRow === row && inner.style.transform) return;
    inner._lastRow = row;
    var rows = visibleRows(inner);
    var idx = rows.indexOf(row);
    var isHome = !!document.getElementById('hero');
    var rawTop = (idx <= 0 || row.id === 'hero' || !rows[0]) ? 0 : Math.max(0, rowOffsetTop(row, inner) - rowOffsetTop(rows[0], inner));
    var y = rawTop;
    if (!isHome) {
      var rowH = row.offsetHeight || 240;
      if (idx <= 0) y = 0;
      else if (row.id !== 'cast-row' && row.id !== 'similar-row' && (rawTop + rowH <= 840)) y = 0;
    }
    inner.style.transform = y <= 0 ? 'translateY(0px)' : 'translate3d(0,' + (-y) + 'px,0)';
    updateRowVisibility(inner, row, y, isHome);
  }
  function set(e) {
    if (!e) return;
    if (cur && cur !== e) cur.classList.remove('focused');
    cur = e; e.classList.add('focused');

    var parentRow = e.closest('section.row') || e.closest('.row');
    if (parentRow) parentRow._lastFocused = e;
    var parentKeys = e.closest && e.closest('.keys');
    if (parentKeys) parentKeys._lastFocused = e;
    var parentRes = e.closest && e.closest('#res');
    if (parentRes && e.classList && e.classList.contains('card')) parentRes._lastFocused = e;

    var bGrid = e.closest && (e.closest('.browse-grid') || e.closest('.search-grid'));
    if (bGrid) {
      var gOff = Math.max(0, e.offsetTop - 180);
      if (e.offsetTop < 340) gOff = 0;
      bGrid.style.transform = 'translate3d(0,' + (-gOff) + 'px,0)';
    }
    var track = e.closest('.track');
    if (track) {
      var visW = (track.parentNode && track.parentNode.clientWidth) || 1920;
      var ch = track.children;
      var tIdx = Array.prototype.indexOf.call(ch, e);
      var screenEdgeOff = Math.max(0, e.offsetLeft + e.offsetWidth + 112 - visW);
      var afterFifthOff = 0;
      if (tIdx >= 5 && ch[4]) {
        afterFifthOff = Math.max(0, (e.offsetLeft + e.offsetWidth) - (ch[4].offsetLeft + ch[4].offsetWidth));
      }
      var off = (tIdx <= 0) ? 0 : Math.max(screenEdgeOff, afterFifthOff);
      var tx = 'translate3d(' + (-off) + 'px,0,0)';
      if (track._tx !== tx) {
        track._tx = tx;
        track.style.transform = tx;
      }
    }
    var inner = e.closest('.rows-inner') || document.getElementById('rows');
    if (inner && e.closest('.rows-inner')) {
      var row = e.closest('section.row') || e.closest('.row') || e;
      syncInnerScroll(inner, row, false);
    } else if (inner && (e.closest('#hero') || e.closest('.top'))) {
      var rows0 = visibleRows(inner);
      if (rows0.length && (!inner.style.transform || e.closest('.top') || !e.closest('.side-stage'))) {
        inner._lastRow = rows0[0];
        inner.style.transform = 'translateY(0px)';
        updateRowVisibility(inner, rows0[0], 0, !!document.getElementById('hero'));
      }
    }
    if (Focus.onFocus) Focus.onFocus(e);
  }
  function move(dir) {
    var rootScope = scopeRoot();
    if (!cur || !vis(cur) || !rootScope.contains(cur)) return set(all()[0]);

    // 1. O(1) Left/Right inside tracks, action bars, filter pills, and topbar
    if (dir === 'L' || dir === 'R') {
      var p = cur.parentNode;
      if (p && p.classList && (p.classList.contains('track') || p.classList.contains('cast-works-track') || p.classList.contains('actions') || p.classList.contains('filters'))) {
        var sib = dir === 'R' ? cur.nextElementSibling : cur.previousElementSibling;
        while (sib) {
          if (sib.classList && sib.classList.contains('focusable') && vis(sib)) return set(sib);
          sib = dir === 'R' ? sib.nextElementSibling : sib.previousElementSibling;
        }
        var sideHero = document.querySelector('#hero.side-stage');
        if (sideHero) {
          if (dir === 'L' && p.classList.contains('track') && cur.classList.contains('card') && cur.closest('.side-rows')) {
            sideHero._returnCard = cur;
            var hPlayBtn = document.getElementById('hero-play');
            if (hPlayBtn && vis(hPlayBtn)) return set(hPlayBtn);
          } else if (dir === 'R' && cur.closest('#hero.side-stage')) {
            if (sideHero._returnCard && vis(sideHero._returnCard)) return set(sideHero._returnCard);
            var sInner = document.getElementById('rows');
            var sRows = visibleRows(sInner);
            if (sRows.length) {
              var activeR = sInner._lastRow || sRows[0];
              var retT = (activeR && activeR._lastFocused && vis(activeR._lastFocused)) ? activeR._lastFocused : (activeR && activeR.querySelector('.focusable'));
              if (retT) return set(retT);
            }
          }
        }
        return;
      }
    }

    // 2. O(1) Up/Down between shelves in .rows-inner, Hero stage, and Topbar
    var inner = cur.closest('.rows-inner') || document.getElementById('rows') || document.querySelector('.rows-inner');
    var hero = document.getElementById('hero');
    if (inner && rootScope === document) {
      var rows = visibleRows(inner);
      var curRow = cur.closest('section.row') || cur.closest('.row');
      if (curRow && inner.contains(curRow)) {
        if (curRow.id === 'cast-row') {
          if (dir === 'D' && cur.classList && cur.classList.contains('person-card')) {
            var cwCard = curRow.querySelector('#cast-works-track .card.focusable');
            if (cwCard && vis(cwCard)) { curRow._lastPerson = cur; return set(cwCard); }
          } else if (dir === 'U' && cur.closest && cur.closest('#cast-works-track')) {
            var pTarget = (curRow._lastPerson && vis(curRow._lastPerson)) ? curRow._lastPerson : curRow.querySelector('.person-card.focusable');
            if (pTarget) return set(pTarget);
          }
        }
        var rIdx = rows.indexOf(curRow);
        if (dir === 'D') {
          if (rIdx >= 0 && rIdx + 1 < rows.length) {
            var nextR = rows[rIdx + 1];
            var targetD = (nextR._lastFocused && vis(nextR._lastFocused)) ? nextR._lastFocused : nextR.querySelector('.focusable');
            if (targetD) return set(targetD);
          }
          return;
        }
        if (dir === 'U') {
          if (rIdx > 0) {
            var prevR = rows[rIdx - 1];
            var targetU = (prevR._lastFocused && vis(prevR._lastFocused)) ? prevR._lastFocused : prevR.querySelector('.focusable');
            if (targetU) return set(targetU);
          }
          if (hero) {
            var hPlay = document.getElementById('hero-play');
            if (hPlay && vis(hPlay)) return set(hPlay);
          }
        }
      } else if (hero && cur.closest('#hero')) {
        if (dir === 'D' && rows.length) {
          if (hero._returnCard && vis(hero._returnCard)) return set(hero._returnCard);
          var firstR = rows[0];
          var targetFirst = (firstR._lastFocused && vis(firstR._lastFocused)) ? firstR._lastFocused : firstR.querySelector('.focusable');
          if (targetFirst) return set(targetFirst);
        }
        if (dir === 'U') {
          var otaB = document.getElementById('ota-banner');
          if (otaB && vis(otaB) && otaB.classList.contains('focusable')) return set(otaB);
          var uBtn = document.getElementById('update-btn') || document.getElementById('search');
          if (uBtn && vis(uBtn)) return set(uBtn);
        }
      } else if (cur.closest('.top')) {
        if (dir === 'D') {
          var hPlay2 = document.getElementById('hero-play');
          if (hPlay2 && vis(hPlay2)) return set(hPlay2);
          if (rows.length) return set(rows[0].querySelector('.focusable'));
        }
      }
    }

    // 2b. O(1) Split Search (#tv-021-netflix-search): 6-col keyboard <-> 5-col results grid
    var searchSplit = cur.closest && cur.closest('#tv-021-netflix-search');
    if (searchSplit && rootScope === document) {
      var keysBox = searchSplit.querySelector('.keys');
      var resBox = document.getElementById('res');
      if (keysBox && keysBox.contains(cur)) {
        var kList = Array.prototype.slice.call(keysBox.querySelectorAll('.key.focusable'));
        var kIdx = kList.indexOf(cur);
        if (kIdx >= 0) {
          if (kIdx < 36) {
            var kr = Math.floor(kIdx / 6), kc = kIdx % 6;
            if (dir === 'L') { if (kc > 0) return set(kList[kIdx - 1]); return; }
            if (dir === 'R') {
              if (kc < 5) return set(kList[kIdx + 1]);
              if (resBox) {
                var rTarget = (resBox._lastFocused && vis(resBox._lastFocused)) ? resBox._lastFocused : resBox.querySelector('.card.focusable');
                if (rTarget) return set(rTarget);
              }
              return;
            }
            if (dir === 'U') { if (kr > 0) return set(kList[kIdx - 6]); return; }
            if (dir === 'D') {
              if (kr < 5) return set(kList[kIdx + 6]);
              var actIdx = 36 + Math.min(2, Math.floor(kc / 2));
              if (kList[actIdx]) return set(kList[actIdx]);
              return;
            }
          } else {
            var aIdx = kIdx - 36;
            if (dir === 'L') { if (kIdx > 36) return set(kList[kIdx - 1]); return; }
            if (dir === 'R') {
              if (kIdx + 1 < kList.length) return set(kList[kIdx + 1]);
              if (resBox) {
                var rTarget2 = (resBox._lastFocused && vis(resBox._lastFocused)) ? resBox._lastFocused : resBox.querySelector('.card.focusable');
                if (rTarget2) return set(rTarget2);
              }
              return;
            }
            if (dir === 'U') {
              var upIdx = 30 + Math.min(5, aIdx * 2);
              if (kList[upIdx]) return set(kList[upIdx]);
              return;
            }
            if (dir === 'D') return;
          }
        }
      } else if (resBox && resBox.contains(cur)) {
        var cList = Array.prototype.slice.call(resBox.querySelectorAll('.card.focusable'));
        var cIdx = cList.indexOf(cur);
        var cols = 5;
        if (cIdx >= 0) {
          var cr = Math.floor(cIdx / cols), cc = cIdx % cols;
          if (dir === 'R') {
            if (cc < cols - 1 && cIdx + 1 < cList.length) return set(cList[cIdx + 1]);
            return;
          }
          if (dir === 'L') {
            if (cc > 0) return set(cList[cIdx - 1]);
            if (keysBox) {
              var kBack = (keysBox._lastFocused && vis(keysBox._lastFocused)) ? keysBox._lastFocused : (keysBox.querySelector('.key[data-k="f"]') || keysBox.querySelector('.key.focusable'));
              if (kBack) return set(kBack);
            }
            return;
          }
          if (dir === 'D') {
            if (cIdx + cols < cList.length) return set(cList[cIdx + cols]);
            var lastRow = Math.floor((cList.length - 1) / cols);
            if (lastRow > cr) return set(cList[cList.length - 1]);
            return;
          }
          if (dir === 'U') {
            if (cIdx - cols >= 0) return set(cList[cIdx - cols]);
            return;
          }
        }
      }
    }

    // 3. Spatial fallback for Platform filters, Search keyboard, and Modals
    var list = all(); if (!list.length) return;
    var a = cur.getBoundingClientRect(), ax = a.left + a.width / 2, ay = a.top + a.height / 2, best = null, bd = 1e9;
    for (var i = 0; i < list.length; i++) {
      var e = list[i]; if (e === cur) continue;
      var b = e.getBoundingClientRect(), bx = b.left + b.width / 2, by = b.top + b.height / 2, dx = bx - ax, dy = by - ay;
      var ok = dir === 'L' ? dx < -5 : dir === 'R' ? dx > 5 : dir === 'U' ? dy < -5 : dy > 5; if (!ok) continue;
      var main = (dir === 'L' || dir === 'R') ? Math.abs(dx) : Math.abs(dy), cross = (dir === 'L' || dir === 'R') ? Math.abs(dy) : Math.abs(dx);
      if ((dir === 'L' || dir === 'R') && cross > a.height / 2) continue;
      var d = main + cross * 2.5; if (d < bd) { bd = d; best = e; }
    }
    if (best) set(best);
  }
  function refreshRows() {
    var inner = document.querySelector('.rows-inner') || document.getElementById('rows');
    if (inner && cur) {
      var r = (cur.closest && (cur.closest('section.row') || cur.closest('.row'))) || visibleRows(inner)[0];
      if (r && inner.contains(r)) syncInnerScroll(inner, r, true);
      else if (visibleRows(inner)[0]) updateRowVisibility(inner, visibleRows(inner)[0], 0, !!document.getElementById('hero'));
    }
  }
  return {
    set: set,
    move: move,
    current: function () { return cur; },
    cur: function () { return cur; },
    first: function (sel) { set(document.querySelector(sel || '.focusable')); },
    refreshRows: refreshRows,
    refresh: refreshRows,
    onFocus: null
  };
})();
