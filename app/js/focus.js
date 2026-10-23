/* Spatial D-pad navigation: picks nearest .focusable in the pressed direction. */
var Focus = (function () {
  var cur = null;
  function all() { return Array.prototype.slice.call(document.querySelectorAll('.focusable')).filter(function (e) { return e.offsetParent !== null || e.closest('#player'); }); }
  function rect(e) { return e.getBoundingClientRect(); }
  function set(e) {
    if (!e) return; if (cur) cur.classList.remove('focused');
    cur = e; e.classList.add('focused');
    var track = e.closest('.track');
    if (track) { var vis = track.parentNode.clientWidth; var off = Math.max(0, e.offsetLeft + e.offsetWidth + 160 - vis); if (e === track.children[0]) off = 0; track.style.transform = 'translateX(' + (-off) + 'px)'; }
    var inner = e.closest('.rows-inner');
    if (inner) { var row = e.closest('.row') || e; inner.style.transform = 'translateY(' + (-Math.max(0, row.offsetTop - 10)) + 'px)'; }
    if (Focus.onFocus) Focus.onFocus(e);
  }
  function move(dir) {
    var list = all(); if (!cur || list.indexOf(cur) < 0) return set(list[0]);
    var a = rect(cur), ax = a.left + a.width / 2, ay = a.top + a.height / 2, best = null, bd = 1e9;
    list.forEach(function (e) {
      if (e === cur) return; var b = rect(e), bx = b.left + b.width / 2, by = b.top + b.height / 2, dx = bx - ax, dy = by - ay;
      var ok = dir === 'L' ? dx < -5 : dir === 'R' ? dx > 5 : dir === 'U' ? dy < -5 : dy > 5; if (!ok) return;
      var main = (dir === 'L' || dir === 'R') ? Math.abs(dx) : Math.abs(dy), cross = (dir === 'L' || dir === 'R') ? Math.abs(dy) : Math.abs(dx);
      // rows: horizontal moves stay in the same row; vertical moves prefer the closest row
      if ((dir === 'L' || dir === 'R') && cross > a.height / 2) return;
      var d = main + cross * 2.5; if (d < bd) { bd = d; best = e; }
    });
    if (best) set(best);
  }
  return { set: set, move: move, current: function () { return cur; }, first: function (sel) { set(document.querySelector(sel || '.focusable')); }, onFocus: null };
})();
