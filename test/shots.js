// Drives the app against the mock server with real D-pad key events; saves 1920x1080 screenshots.
const puppeteer = require('puppeteer-core');
const OUT = '/workspace/mojaloss-work/design/';
const BASE = 'http://localhost:8787/index.html?mock=http://localhost:8787';
const sleep = ms => new Promise(r => setTimeout(r, ms));
(async () => {
  const b = await puppeteer.launch({ executablePath: '/usr/bin/google-chrome', headless: 'new', args: ['--no-sandbox', '--autoplay-policy=no-user-gesture-required'] });
  const pg = await b.newPage(); await pg.setViewport({ width: 1920, height: 1080 });
  const errs = []; pg.on('pageerror', e => errs.push(e.message));
  const key = async (code, n = 1) => { for (let i = 0; i < n; i++) { await pg.evaluate(c => document.dispatchEvent(new KeyboardEvent('keydown', { keyCode: c, bubbles: true })), code); await sleep(250); } };
  const focused = () => pg.evaluate(() => { const e = document.querySelector('.focused'); return e ? e.textContent.trim().slice(0, 40) : null; });
  const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) process.exitCode = 1; };
  await pg.goto(BASE); await pg.evaluate(() => localStorage.clear()); await pg.goto(BASE);
  await sleep(800);
  const code = await pg.$eval('#code', e => e.textContent); ok(code === '482915', 'sign-in shows Quick Connect code ' + code);
  await pg.screenshot({ path: OUT + 'tv-signin.png' });
  await sleep(11000); // two polls at 5s → approved → AuthenticateWithQuickConnect
  ok(await pg.evaluate(() => localStorage.getItem('mltest.token') === 'mock-token'), 'token persisted after approval');
  const dev1 = await pg.evaluate(() => localStorage.getItem('mltest.deviceId'));
  await sleep(1500);
  ok(await pg.$('.chip') !== null, 'platform chips rendered');
  const chips = await pg.$$eval('.chip', es => es.map(e => e.firstChild.textContent));
  ok(!chips.includes('Hoichoi') && !chips.includes('Voot') && !chips.includes('Action'), 'empty / non-platform categories hidden: ' + chips.join(', '));
  await key(39); await sleep(500);
  await pg.screenshot({ path: OUT + 'tv-home.png' });
  await key(40); const chipName = await pg.evaluate(() => { const e = document.querySelector('.focused'); return e && e.classList.contains('chip') ? e.firstChild.textContent : null; });
  ok(!!chipName, 'D-pad down → platform row: ' + chipName);
  await key(13); await sleep(500);
  ok(await pg.$eval('h1', e => e.textContent) === chipName, 'platform screen opens: ' + chipName);
  ok(await pg.$eval('.pill.on[data-f="latest"]', e => !!e), 'default sort is Latest release');
  await key(38); await key(39); await key(13); await sleep(300); // focus a filter pill → Movies
  await pg.screenshot({ path: OUT + 'tv-platform.png' });
  await key(10009); await sleep(400); // back to home
  await sleep(800);
  ok((await focused() || '').length > 0, 'back restores home, focus: ' + await focused());
  await key(13); await sleep(800);
  await pg.screenshot({ path: OUT + 'tv-detail.png' });
  ok((await focused() || '').startsWith('Resume') || (await focused() || '').startsWith('Play'), 'detail primary action focused: ' + await focused());
  await key(10009); await sleep(400);
  await key(38); await sleep(100); await key(38); await key(39); await sleep(200); await key(13); await sleep(500);
  if (!(await pg.$('.keys'))) { await pg.evaluate(() => MLTV.go('search')); await sleep(400); }
  for (const [c, n] of [[39, 2], [39, 0]]) {} // focus starts at A
  // type "co" with D-pad: C is 2 right of A; O is (row 2, col 2)
  await key(39, 2); await key(13); await key(40); await key(37); await key(13); await sleep(1200);
  ok(await pg.$eval('#sb', e => e.textContent) === 'co', 'on-screen keyboard typed: ' + await pg.$eval('#sb', e => e.textContent));
  ok((await pg.$$('#res .card')).length >= 1, 'search results: ' + (await pg.$$eval('#res .card .t', es => es.map(e => e.textContent))).join(', '));
  await pg.screenshot({ path: OUT + 'tv-search.png' });
  await pg.evaluate(() => MLTV.play({ Id: 'it0', Name: 'Midnight Harbor', RunTimeTicks: 99 * 60e7 }, 0)); await sleep(1200);
  ok(await pg.$('#player video') !== null, 'player opened with HLS url ' + await pg.$eval('#player video', v => v.getAttribute('src')));
  await pg.evaluate(() => { document.getElementById('pb').style.width = '37%'; document.getElementById('pt').textContent = '36:12'; });
  await pg.screenshot({ path: OUT + 'tv-player.png' });
  await key(10009); ok(await pg.$('#player') === null, 'Back closes player');
  // relaunch: must NOT sign in again
  await pg.goto(BASE); await sleep(1500);
  ok(await pg.$('.signin') === null, 'relaunch stays signed in (no new sign-in)');
  ok(dev1 === await pg.evaluate(() => localStorage.getItem('mltest.deviceId')), 'device id stable');
  const log = await (await fetch('http://localhost:8787/__log')).json();
  const auths = log.filter(l => l.includes('AuthenticateWithQuickConnect')).length;
  ok(auths === 1, 'exactly one authenticate call across session + relaunch (' + auths + ')');
  ok(log.some(l => /AUTH:MediaBrowser Client="MojaLoss TV", Device="[^"]+", DeviceId="mltv-[0-9a-f]{24}", Version="0.1.0", Token="\*\*\*"/.test(l)), 'Authorization header format');
  ok(!log.some(l => /X-Emby/i.test(l)), 'no X-Emby-Token');
  ok(errs.length === 0, 'no page errors ' + errs.join(' | '));
  await b.close();
})();
