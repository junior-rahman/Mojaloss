// Mock Jellyfin + WordPress for tests. Never contacts mojaloss.stream.
const http = require('http'), fs = require('fs'), path = require('path');
const APP = path.join(__dirname, '..', 'app');
const log = [];
const palette = ['#2f5d4b','#4a3b5c','#5c3b3b','#3b4f5c','#5c543b','#3b5c45','#6b4a2f','#2f4a6b'];
const titles = [['Midnight Harbor',2026,'Movie'],['The Long Field',2025,'Series'],['Copper Sky',2026,'Movie'],['Northline',2026,'Series'],['Paper Lanterns',2024,'Movie'],['Salt & Iron',2025,'Series'],['Quiet Engines',2026,'Movie'],['Glass River',2023,'Movie'],['Wild Orchard',2025,'Series'],['Kite Season',2026,'Movie'],['The Ninth Door',2024,'Series'],['Ember Coast',2025,'Movie']];
const items = titles.map(([n, y, t], i) => ({ Id: 'it' + i, Name: n, ProductionYear: y, PremiereDate: `${y}-0${(i % 9) + 1}-01`, Type: t, RunTimeTicks: (90 + i * 7) * 60e7, OfficialRating: 'TV-14', Genres: ['Drama', 'Thriller'],
  Overview: `${n} — a fixture overview used only for local UI tests. A quiet story about people, places and the choices that shape them.`, ImageTags: { Primary: 't' }, BackdropImageTags: ['b'], UserData: { PlaybackPositionTicks: 0 } }));
const resume = [Object.assign({}, items[0], { UserData: { PlaybackPositionTicks: 40 * 60e7, PlayedPercentage: 38 } }),
  { Id: 'ep1', Type: 'Episode', Name: 'Arrival', SeriesName: 'Northline', SeriesId: 'it3', SeriesPrimaryImageTag: 't', ParentBackdropItemId: 'it3', ParentBackdropImageTags: ['b'], ParentIndexNumber: 1, IndexNumber: 3, RunTimeTicks: 50 * 60e7, Overview: 'Episode fixture.', UserData: { PlaybackPositionTicks: 12 * 60e7, PlayedPercentage: 24 } },
  Object.assign({}, items[5], { UserData: { PlaybackPositionTicks: 5 * 60e7, PlayedPercentage: 70 } })];
const all = items.concat(resume.slice(1));
const cats = [{ id: 1, count: 300, name: 'TV Shows', slug: 'tv-shows', parent: 0 }, { id: 10, count: 120, name: 'Netflix', slug: 'netflix', parent: 0 }, { id: 11, count: 80, name: 'Prime Video', slug: 'prime', parent: 0 },
  { id: 12, count: 40, name: 'Apple TV+', slug: 'apple', parent: 0 }, { id: 13, count: 25, name: 'HBO Max', slug: 'hbo', parent: 0 }, { id: 14, count: 9, name: 'Hoichoi', slug: 'hoichoi', parent: 0 /* posts not in library → hidden */ }, { id: 15, count: 0, name: 'Voot', slug: 'voot', parent: 0 }, { id: 16, count: 50, name: 'Action', slug: 'action', parent: 0 }];
const posts = { 10: [0, 1, 2, 3, 4, 9], 11: [5, 6, 7], 12: [8, 10], 13: [11, 2], 14: [] };
let qc = { secret: 'mock-secret', code: '482915', polls: 0 };
function svg(id, w, h) { const i = parseInt(String(id).replace(/\D/g, '')) || 0; const it = all.find(x => x.Id === id) || {}; const c = palette[i % palette.length];
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${c}"/><stop offset="1" stop-color="#0d1712"/></linearGradient></defs><rect width="100%" height="100%" fill="url(#g)"/><circle cx="${w*.7}" cy="${h*.3}" r="${Math.min(w,h)*.25}" fill="#B4D2BD" opacity=".14"/><text x="24" y="${h-36}" font-family="Arial" font-size="${Math.round(w/11)}" font-weight="700" fill="#EDF3EC" opacity=".9">${(it.Name||'').replace(/&/g,'&amp;')}</text></svg>`; }
const json = (res, o, code = 200) => { res.writeHead(code, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }); res.end(JSON.stringify(o)); };
http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x'), p = u.pathname;
  log.push(req.method + ' ' + p + (req.headers.authorization ? ' AUTH:' + req.headers.authorization.replace(/Token="[^"]+"/, 'Token="***"') : ''));
  if (p === '/__log') return json(res, log);
  if (p.startsWith('/jelly/') && !p.includes('/Images/') && !p.includes('/QuickConnect/') && !p.includes('/System/Info') && !p.includes('AuthenticateWithQuickConnect') && !/Token="mock-token"/.test(req.headers.authorization || '')) return json(res, { error: 'unauthorized' }, 401);
  if (p === '/jelly/System/Info/Public') return json(res, { ServerName: 'Mock', Version: '10.10.0' });
  if (p === '/jelly/QuickConnect/Initiate') { qc.polls = 0; log.length = 0; } if (p === '/jelly/QuickConnect/Initiate') return json(res, { Secret: qc.secret, Code: qc.code, Authenticated: false });
  if (p === '/jelly/QuickConnect/Connect') { qc.polls++; return json(res, { Secret: qc.secret, Code: qc.code, Authenticated: qc.polls >= 3 }); }
  if (p === '/jelly/Users/AuthenticateWithQuickConnect') return json(res, { AccessToken: 'mock-token', User: { Id: 'u1', Name: 'Juel' } });
  if (p === '/jelly/UserItems/Resume') return json(res, { Items: resume });
  if (p === '/jelly/Shows/NextUp' || /\/Episodes$/.test(p)) return json(res, { Items: [resume[1]] });
  if (/\/PlaybackInfo$/.test(p)) return json(res, { PlaySessionId: 'ps1', MediaSources: [{ Id: 'ms1', TranscodingUrl: '/videos/mock/master.m3u8' }] });
  if (p.startsWith('/jelly/Sessions/Playing')) { res.writeHead(204); return res.end(); }
  let m = p.match(/^\/jelly\/Items\/([^/]+)\/Images\/(\w+)/);
  if (m) { const bdp = m[2] === 'Backdrop'; res.writeHead(200, { 'Content-Type': 'image/svg+xml' }); return res.end(svg(m[1], bdp ? 1920 : 240, bdp ? 1080 : 360)); }
  m = p.match(/^\/jelly\/Items\/([^/]+)$/); if (m) return json(res, all.find(x => x.Id === m[1]) || {});
  if (p === '/jelly/Items') { let r = items.slice(); const t = u.searchParams.get('IncludeItemTypes'), s = u.searchParams.get('searchTerm');
    if (t && t !== 'Movie,Series') r = r.filter(x => x.Type === t); if (s) r = r.filter(x => x.Name.toLowerCase().includes(s.toLowerCase()));
    return json(res, { Items: r, TotalRecordCount: r.length }); }
  if (p === '/wp-json/wp/v2/categories') return json(res, cats);
  if (p === '/wp-json/wp/v2/posts') { const c = +u.searchParams.get('categories'); return json(res, (posts[c] || []).map(i => ({ id: i, title: { rendered: `${items[i].Name.replace('&', '&amp;')} (${items[i].ProductionYear}${items[i].Type === 'Series' ? '-' : ''})` } })).concat(c === 14 ? [{ id: 99, title: { rendered: 'Not In Library (2020)' } }] : [])); }
  let f = path.join(APP, p === '/' ? 'index.html' : p); if (!f.startsWith(APP) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { res.writeHead(404); return res.end(); }
  const ext = path.extname(f); res.writeHead(200, { 'Content-Type': { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png' }[ext] || 'application/octet-stream' }); fs.createReadStream(f).pipe(res);
}).listen(8787, () => console.log('mock on 8787'));
