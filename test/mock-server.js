// Mock Jellyfin + WordPress for tests. Never contacts mojaloss.stream.
const http = require('http'), fs = require('fs'), path = require('path'), crypto = require('crypto');
const APP = path.join(__dirname, '..', 'app');
const log = [];
const palette = ['#2f5d4b','#4a3b5c','#5c3b3b','#3b4f5c','#5c543b','#3b5c45','#6b4a2f','#2f4a6b'];
const titles = [['Midnight Harbor',2026,'Movie'],['The Long Field',2025,'Series'],['Copper Sky',2026,'Movie'],['Northline',2026,'Series'],['Paper Lanterns',2024,'Movie'],['Salt & Iron',2025,'Series'],['Quiet Engines',2026,'Movie'],['Glass River',2023,'Movie'],['Wild Orchard',2025,'Series'],['Kite Season',2026,'Movie'],['The Ninth Door',2024,'Series'],['Ember Coast',2025,'Movie'],['Lucky',2017,'Movie'],['The Gorge',2025,'Movie']];
const peopleFixture = [
  { Id: 'p1', Name: 'Amina Rahman', Role: 'Detective Mira', Type: 'Actor', PrimaryImageTag: 'p' },
  { Id: 'p2', Name: 'Dev Sen', Role: 'Captain Kabir', Type: 'Actor', PrimaryImageTag: 'p' },
  { Id: 'p3', Name: 'Nadia Chowdhury', Role: 'Elena', Type: 'Actor' },
  { Id: 'p4', Name: 'Tariq Anwar', Role: 'Director', Type: 'Director' }
];
const items = titles.map(([n, y, t], i) => ({ Id: 'it' + i, Name: n, ProductionYear: y, PremiereDate: `${y}-0${(i % 9) + 1}-01`, Type: t, RunTimeTicks: (90 + i * 7) * 60e7, OfficialRating: 'TV-14', Genres: ['Drama', 'Thriller'],
  People: peopleFixture,
  Overview: `${n} — a fixture overview used only for local UI tests. A quiet story about people, places and the choices that shape them.`, ImageTags: { Primary: 't' }, BackdropImageTags: ['b'], UserData: { PlaybackPositionTicks: 0 } }));
const resume = [Object.assign({}, items[0], { UserData: { PlaybackPositionTicks: 40 * 60e7, PlayedPercentage: 38 } }),
  { Id: 'ep1', Type: 'Episode', Name: 'Arrival', SeriesName: 'Northline', SeriesId: 'it3', SeriesPrimaryImageTag: 't', ParentBackdropItemId: 'it3', ParentBackdropImageTags: ['b'], ParentIndexNumber: 1, IndexNumber: 3, RunTimeTicks: 50 * 60e7, Overview: 'Cut off from command, the team makes landfall at the northern relay.', UserData: { PlaybackPositionTicks: 12 * 60e7, PlayedPercentage: 24 } },
  Object.assign({}, items[5], { UserData: { PlaybackPositionTicks: 5 * 60e7, PlayedPercentage: 70 } })];
const all = items.concat(resume.slice(1));
const cats = [{ id: 1, count: 300, name: 'TV Shows', slug: 'tv-shows', parent: 0 }, { id: 10, count: 120, name: 'Netflix', slug: 'netflix', parent: 0 }, { id: 11, count: 80, name: 'Prime Video', slug: 'prime', parent: 0 },
  { id: 12, count: 40, name: 'Apple TV+', slug: 'apple', parent: 0 }, { id: 13, count: 25, name: 'HBO Max', slug: 'hbo', parent: 0 }, { id: 14, count: 9, name: 'Hoichoi', slug: 'hoichoi', parent: 0 /* posts not in library → hidden */ }, { id: 15, count: 0, name: 'Voot', slug: 'voot', parent: 0 }, { id: 16, count: 50, name: 'Action', slug: 'action', parent: 0 },
  { id: 20, count: 90, name: 'Hindi Movies', slug: 'hindi', parent: 0 }, { id: 21, count: 45, name: 'Hindi', slug: 'hindi-tv-shows', parent: 1 }, { id: 22, count: 60, name: 'International Movies', slug: 'international-movies', parent: 0 }, { id: 23, count: 0, name: 'Bengali Movies', slug: 'bengali', parent: 0 }];
const posts = { 10: [0, 1, 2, 3, 4, 9], 11: [5, 6, 7], 12: [8, 10], 13: [11, 2], 14: [], 20: [2, 6, 9, 11], 21: [1, 3, 8], 22: [4, 7, 0] };
const state = { revoked: false, favs: { it2: true }, played: {}, reports: [], direct: false, otaMode: 'none' };
function makeOtaBundle(mode) {
  const base = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'dist', 'tv-ota-bundle.json'), 'utf8'));
  if (mode === 'none') return base; // v0.1.9 (versionCode 9) -> no update
  const ver = '0.1.24', vcode = 24;
  let css = base.css + '\n.ver-badge{border-color:#B4D2BD}';
  let js = base.js.replace(/0\.1\.23/g, '0.1.24');
  if (mode === 'crash') {
    js = 'throw new Error("Simulated OTA boot crash in 0.1.10");';
  }
  const canonical = `${ver}\n${vcode}\n${css}\n${js}`;
  const sha = mode === 'tampered'
    ? 'deadbeef00000000000000000000000000000000000000000000000000000000'
    : crypto.createHash('sha256').update(canonical, 'utf8').digest('hex');
  return {
    version: ver,
    versionCode: vcode,
    sha256: sha,
    releasedAt: '2026-10-24T20:00:00Z',
    notes: 'Faster platform rows & instant OTA updates',
    css: css,
    js: js,
    logos: base.logos
  };
}
const s1Overviews = [
  'An encrypted distress beacon pulls the crew toward an uncharted ice shelf.',
  'A second signal reveals someone is broadcasting from inside the station.',
  'Cut off from command, the team makes landfall at the northern relay.',
  'With the storm closing in, the crew must cross the fissure before dawn.'
];
const s2Overviews = [
  'Six months after the crossing, a supply convoy vanishes along the ridge.',
  'Spring melt exposes the bunker beneath Sector 4 and its last recording.'
];
const eps = [
  ...[1,2,3,4].map(n => ({ Id: 'ep' + n, Type: 'Episode', Name: ['Pilot','Signal','Arrival','Crossing'][n-1], SeriesName: 'Northline', SeriesId: 'it3', SeriesPrimaryImageTag: 't', ParentBackdropItemId: 'it3', ParentBackdropImageTags: ['b'], BackdropImageTags: ['b'], ParentIndexNumber: 1, IndexNumber: n, RunTimeTicks: 50 * 60e7, ImageTags: { Primary: 't' }, Overview: s1Overviews[n-1], UserData: { PlaybackPositionTicks: n === 3 ? 20e7 : 0, PlayedPercentage: n === 3 ? 24 : 0, Played: n < 3 } })),
  ...[1,2].map(n => ({ Id: 'ep2_' + n, Type: 'Episode', Name: ['Whiteout','Thaw'][n-1], SeriesName: 'Northline', SeriesId: 'it3', SeriesPrimaryImageTag: 't', ParentBackdropItemId: 'it3', ParentBackdropImageTags: ['b'], BackdropImageTags: ['b'], ParentIndexNumber: 2, IndexNumber: n, RunTimeTicks: 54 * 60e7, ImageTags: { Primary: 't' }, Overview: s2Overviews[n-1], UserData: { PlaybackPositionTicks: 0, PlayedPercentage: 0, Played: false } }))
];
let qc = { secret: 'mock-secret', code: '482915', polls: 0 };
function svg(id, w, h) { const i = parseInt(String(id).replace(/\D/g, '')) || 0; const it = all.find(x => x.Id === id) || {}; const c = palette[i % palette.length];
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${c}"/><stop offset="1" stop-color="#0d1712"/></linearGradient></defs><rect width="100%" height="100%" fill="url(#g)"/><circle cx="${w*.7}" cy="${h*.3}" r="${Math.min(w,h)*.25}" fill="#B4D2BD" opacity=".14"/><text x="24" y="${h-36}" font-family="Arial" font-size="${Math.round(w/11)}" font-weight="700" fill="#EDF3EC" opacity=".9">${(it.Name||'').replace(/&/g,'&amp;')}</text></svg>`; }
const json = (res, o, code = 200) => { res.writeHead(code, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }); res.end(JSON.stringify(o)); };
http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x'), p = u.pathname;
  log.push(req.method + ' ' + p + (req.headers.authorization ? ' AUTH:' + req.headers.authorization.replace(/Token="[^"]+"/, 'Token="***"') : ''));
  if (p === '/__log') return json(res, log);
  if (p === '/__state') { if (u.searchParams.get('revoked')) state.revoked = u.searchParams.get('revoked') === '1'; if (u.searchParams.get('direct')) state.direct = u.searchParams.get('direct') === '1'; if (u.searchParams.get('ota')) state.otaMode = u.searchParams.get('ota'); return json(res, { revoked: state.revoked, reports: state.reports, otaMode: state.otaMode }); }
  if (p === '/ota/tv-manifest.json' || p === '/ota/tv-ota-bundle.json') return json(res, makeOtaBundle(state.otaMode));
  if (p === '/ota/releases/latest') return json(res, { tag_name: 'tv-v0.1.9', assets: [{ name: 'tv-ota-bundle.json', browser_download_url: 'http://localhost:' + (process.env.PORT || 8787) + '/ota/tv-ota-bundle.json' }] });
  if (p === '/fixtures/clip.mp4') { const f = path.join(__dirname, 'fixtures/clip.mp4'), st = fs.statSync(f), r = req.headers.range;
    if (r) { const [a, b] = r.replace('bytes=', '').split('-'); const s0 = +a, e0 = b ? +b : st.size - 1; res.writeHead(206, { 'Content-Range': `bytes ${s0}-${e0}/${st.size}`, 'Accept-Ranges': 'bytes', 'Content-Length': e0 - s0 + 1, 'Content-Type': 'video/mp4' }); return fs.createReadStream(f, { start: s0, end: e0 }).pipe(res); }
    res.writeHead(200, { 'Content-Length': st.size, 'Content-Type': 'video/mp4', 'Accept-Ranges': 'bytes' }); return fs.createReadStream(f).pipe(res); }
  if (/\/Subtitles\/\d+\/0\/Stream\.vtt$/.test(p)) { res.writeHead(200, { 'Content-Type': 'text/vtt' }); return res.end('WEBVTT\n\n00:00.000 --> 00:59.000\nMock subtitle line\n'); }
  if (state.revoked && p.startsWith('/jelly/') && /Token=/.test(req.headers.authorization || '')) return json(res, { error: 'revoked' }, 401);
  if (p.startsWith('/jelly/') && !p.includes('/Images/') && !p.includes('/QuickConnect/') && !p.includes('/System/Info') && !p.includes('AuthenticateWithQuickConnect') && !/Token="mock-token"/.test(req.headers.authorization || '') && u.searchParams.get('api_key') !== 'mock-token') return json(res, { error: 'unauthorized' }, 401);
  if (p === '/jelly/System/Info/Public') return json(res, { ServerName: 'Mock', Version: '10.10.0' });
  if (p === '/jelly/QuickConnect/Initiate') { qc.polls = 0; log.length = 0; } if (p === '/jelly/QuickConnect/Initiate') return json(res, { Secret: qc.secret, Code: qc.code, Authenticated: false });
  if (p === '/jelly/QuickConnect/Connect') { qc.polls++; return json(res, { Secret: qc.secret, Code: qc.code, Authenticated: qc.polls >= 3 }); }
  if (p === '/jelly/Users/AuthenticateWithQuickConnect') return json(res, { AccessToken: 'mock-token', User: { Id: 'u1', Name: 'Juel' } });
  if (p === '/jelly/UserItems/Resume') return json(res, { Items: resume });
  if (p === '/trending/') {
    const tOrder = [9, 3, 0, 2, 1, 5, 6, 8, 4, 11];
    const htmlRows = tOrder.map((idx, rIdx) => {
      const it = items[idx];
      const bType = it.Type === 'Series' ? 'tv' : 'movie';
      return `<div class="list-item"><div class="list-rank">${rIdx + 1}</div><div class="list-title"><a href="https://www.mojaloss.stream/mock-${idx}/">${it.Name.replace('&', '&amp;')} (${it.ProductionYear}${it.Type === 'Series' ? '-' : ''})</a></div><div class="list-meta"><span class="badge ${bType}">${bType}</span></div></div>`;
    }).join('');
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
    return res.end(`<div id="listView">${htmlRows}</div>`);
  }
  if (p === '/jelly/Shows/NextUp') {
    const sid = u.searchParams.get('seriesId');
    return json(res, { Items: sid ? [eps[2]] : [eps[3], eps[4]] });
  }
  if (/\/Episodes$/.test(p)) return json(res, { Items: eps });
  if (p.startsWith('/jelly/UserFavoriteItems/')) { const id = p.split('/').pop(); state.favs[id] = req.method === 'POST'; return json(res, { IsFavorite: state.favs[id] }); }
  if (p.startsWith('/jelly/UserPlayedItems/')) { const id = p.split('/').pop(); state.played[id] = req.method === 'POST'; return json(res, { Played: state.played[id] }); }
  let msMatch = p.match(/^\/jelly\/MediaSegments\/([^/]+)$/);
  if (msMatch) {
    return json(res, { Items: [
      { Id: 'seg-intro-' + msMatch[1], ItemId: msMatch[1], Type: 'Intro', StartTicks: 18 * 1e7, EndTicks: 42 * 1e7 },
      { Id: 'seg-outro-' + msMatch[1], ItemId: msMatch[1], Type: 'Outro', StartTicks: 52 * 1e7, EndTicks: 59 * 1e7 }
    ] });
  }
  if (/\/PlaybackInfo$/.test(p)) { const a = u.searchParams.get('AudioStreamIndex');
    return json(res, { PlaySessionId: 'ps' + (a || '1'), MediaSources: [Object.assign({ Id: 'ms1', DefaultAudioStreamIndex: 1, MediaStreams: [{ Type: 'Video', Index: 0 }, { Type: 'Audio', Index: 1, DisplayTitle: 'English - AAC Stereo', Language: 'eng' }, { Type: 'Audio', Index: 2, DisplayTitle: 'Hindi - AC3 5.1', Language: 'hin' }, { Type: 'Subtitle', Index: 3, DisplayTitle: 'English (SRT)', IsExternal: true, IsTextSubtitleStream: true, DeliveryMethod: 'External', DeliveryUrl: '/Videos/x/ms1/Subtitles/3/0/Stream.vtt' }] },
      state.direct ? { DirectStreamUrl: null } : { TranscodingUrl: '/videos/mock/master.m3u8?AudioStreamIndex=' + (a || 1) })] }); }
  if (p.startsWith('/jelly/Sessions/Playing')) { let body = ''; req.on('data', c => body += c); req.on('end', () => { try { state.reports.push(Object.assign({ kind: p.split('/').pop() }, JSON.parse(body))); } catch (e) {} }); res.writeHead(204); return res.end(); }
  let m = p.match(/^\/jelly\/Items\/([^/]+)\/Images\/(\w+)/);
  if (m) { const bdp = m[2] === 'Backdrop'; res.writeHead(200, { 'Content-Type': 'image/svg+xml' }); return res.end(svg(m[1], bdp ? 1920 : 240, bdp ? 1080 : 360)); }
  m = p.match(/^\/jelly\/Items\/([^/]+)\/Similar$/); if (m) { const sim = items.filter(x => x.Id !== m[1]).slice(0, 6); return json(res, { Items: sim, TotalRecordCount: sim.length }); }
  m = p.match(/^\/jelly\/Items\/([^/]+)$/); if (m) { const it = JSON.parse(JSON.stringify(resume.find(x => x.Id === m[1]) || eps.find(x => x.Id === m[1]) || all.find(x => x.Id === m[1]) || {})); it.UserData = Object.assign({}, it.UserData, { IsFavorite: !!state.favs[m[1]] }); return json(res, it); }
  if (p === '/jelly/Items') { let r = items.slice(); const t = u.searchParams.get('IncludeItemTypes'), s = u.searchParams.get('searchTerm'), pid = u.searchParams.get('PersonIds') || u.searchParams.get('Person');
    if (t && t !== 'Movie,Series') r = r.filter(x => x.Type === t); if (s) r = r.filter(x => x.Name.toLowerCase().includes(s.toLowerCase()));
    if (pid) r = r.slice(0, 8);
    if (t === 'BoxSet') return json(res, { Items: [{ Id: 'box1', Name: 'Northern Saga Collection', Type: 'BoxSet', ProductionYear: 2026, ChildCount: 4, Genres: ['Drama', 'Thriller'], ImageTags: { Primary: 't' }, BackdropImageTags: ['b'] }], TotalRecordCount: 1 });
    if (u.searchParams.get('Filters') === 'IsFavorite') r = items.filter(x => state.favs[x.Id]);
    return json(res, { Items: r, TotalRecordCount: r.length }); }
  m = p.match(/^\/jelly\/Videos\/([^/]+)\/stream$/); if (m) { res.writeHead(302, { Location: '/fixtures/clip.mp4' }); return res.end(); }
  if (p === '/wp-json/wp/v2/categories') return json(res, cats);
  if (p === '/wp-json/wp/v2/posts') { const c = +u.searchParams.get('categories'); return json(res, (posts[c] || []).map(i => ({ id: i, title: { rendered: `${items[i].Name.replace('&', '&amp;')} (${items[i].ProductionYear}${items[i].Type === 'Series' ? '-' : ''})` } })).concat(c === 14 ? [{ id: 99, title: { rendered: 'Not In Library (2020)' } }] : [])); }
  let f = path.join(APP, p === '/' ? 'index.html' : p); if (!f.startsWith(APP) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { res.writeHead(404); return res.end(); }
  const ext = path.extname(f); res.writeHead(200, { 'Content-Type': { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png' }[ext] || 'application/octet-stream' }); fs.createReadStream(f).pipe(res);
}).listen(+(process.env.PORT || 8787), () => console.log('mock on ' + (process.env.PORT || 8787)));
