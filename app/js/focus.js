/* Ultra-fast O(1) D-pad navigation & full-screen viewport shelf manager for Tizen TV */
var Focus = (function () {
  var cur = null;
  function scopeRoot() { return document.getElementById('player') || document.getElementById('update-modal') || document; }
  function vis(e) { return e && (e.offsetParent !== null || !!e.closest('#player')); }
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
      if (el.classList && el.classList.contains('row') && el.style.display !== 'none') {
        if (el.querySelector('.focusable')) out.push(el);
      } else if (el.id === 'regional-rows') {
        var rch = el.children;
        for (var j = 0; j < rch.length; j++) {
          if (rch[j].classList && rch[j].classList.contains('row') && rch[j].style.display !== 'none' && rch[j].querySelector('.focusable')) {
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
      if (idx <= 0 || (rawTop + rowH <= 720)) y = 0;
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

    var track = e.closest('.track');
    if (track) {
      var visW = (track.parentNode && track.parentNode.clientWidth) || 1808;
      var off = Math.max(0, e.offsetLeft + e.offsetWidth + 96 - visW);
      if (e === track.children[0]) off = 0;
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
      if (rows0.length) {
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
      if (p && p.classList && (p.classList.contains('track') || p.classList.contains('actions') || p.classList.contains('filters'))) {
        var sib = dir === 'R' ? cur.nextElementSibling : cur.previousElementSibling;
        while (sib) {
          if (sib.classList && sib.classList.contains('focusable') && vis(sib)) return set(sib);
          sib = dir === 'R' ? sib.nextElementSibling : sib.previousElementSibling;
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
    first: function (sel) { set(document.querySelector(sel || '.focusable')); },
    refreshRows: refreshRows,
    refresh: refreshRows,
    onFocus: null
  };
})();
