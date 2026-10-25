/* Jellyfin + mojaloss.stream public API. Signs in ONCE (Quick Connect) and keeps the token. */
var Api = (function () {
  var mock = (/[?&]mock=([^&]+)/.exec(location.search) || [])[1]; if (mock) mock = decodeURIComponent(mock); // test-only override, never persisted
  var JF = mock ? mock + '/jelly' : 'https://www.mojaloss.stream/jelly';
  var WP = mock ? mock + '/wp-json/wp/v2/' : 'https://www.mojaloss.stream/wp-json/wp/v2/';
  var NS = mock ? 'mltest.' : 'ml.';
  var VERSION = '0.1.28';
  var PLATFORM_SLUGS = ['netflix','prime','apple','disney','hbo','hulu','paramount','peacock','hotstar','zee5','sonyliv','altbalaji','mxplayer','voot','hoichoi','cruncyroll','crunchyroll','showtime','imdb'];

  function get(k) { try { return localStorage.getItem(NS + k); } catch (e) { return null; } }
  function set(k, v) { try { localStorage.setItem(NS + k, v); } catch (e) {} }

  function deviceId() {
    var id = get('deviceId');
    if (!id) {
      var arr = new Uint8Array(12); try { crypto.getRandomValues(arr); } catch (e) { for (var i = 0; i < 12; i++) arr[i] = Math.floor(Math.random() * 256); }
      id = 'mltv-' + Array.prototype.map.call(arr, function (b) { return ('0' + b.toString(16)).slice(-2); }).join('');
      set('deviceId', id);
    }
    return id;
  }
  function deviceName() {
    try { if (window.webapis && webapis.productinfo) return 'Samsung ' + webapis.productinfo.getRealModel(); } catch (e) {}
    return 'Samsung TV';
  }
  function token() { return get('token'); }
  function userId() { return get('userId'); }
  function esc(s) { return String(s).replace(/["\\,]/g, ''); }
  function authHeader() {
    var h = 'MediaBrowser Client="MojaLoss TV", Device="' + esc(deviceName()) + '", DeviceId="' + deviceId() + '", Version="' + ((window.MLTV_OTA && MLTV_OTA.version()) || VERSION) + '"';
    var t = token(); if (t) h += ', Token="' + t + '"';
    return h;
  }
  function req(method, path, body) {
    var ah = authHeader();
    var o = { method: method, headers: { 'Authorization': ah, 'X-Emby-Authorization': ah, 'Accept': 'application/json' } };
    if (body !== undefined) { o.headers['Content-Type'] = 'application/json'; o.body = JSON.stringify(body); }
    return timed(fetch(JF + path, o)).then(function (r) {
      if (r.status === 401 && token()) { var e = new Error('unauthorized'); e.status = 401; throw e; }
      if (!r.ok) { var e2 = new Error('HTTP ' + r.status); e2.status = r.status; throw e2; }
      return r.status === 204 ? null : r.json();
    });
  }
  // Old Tizen browsers have no AbortController: race against a timer so a dead network fails fast.
  function timed(p, ms) { return Promise.race([p, new Promise(function (_, rej) { setTimeout(function () { var e = new Error('timeout'); e.offline = true; rej(e); }, ms || 15000); })])
    .catch(function (e) { if (e && e.status === undefined) e.offline = true; throw e; }); }
  function qs(o) { return Object.keys(o).filter(function (k) { return o[k] !== undefined && o[k] !== ''; }).map(function (k) { return k + '=' + encodeURIComponent(o[k]); }).join('&'); }
  var FIELDS = 'PrimaryImageAspectRatio,Overview,Genres,ProductionYear,PremiereDate,DateCreated,RunTimeTicks,OfficialRating,UserData,ImageTags,BackdropImageTags,SeriesName,ParentIndexNumber,IndexNumber,SeasonName,SeriesId,ParentBackdropItemId,ParentBackdropImageTags,SeriesPrimaryImageTag';
  var DETAIL_FIELDS = FIELDS + ',People,Chapters';
  var INDEX_FIELDS = 'OriginalTitle,ProductionYear,PremiereDate,DateCreated,RunTimeTicks,OfficialRating,Genres,Overview,ProviderIds,Studios,UserData,ImageTags,BackdropImageTags,SeriesId,SeriesPrimaryImageTag,ParentBackdropItemId,ParentBackdropImageTags,ParentThumbItemId,ParentThumbImageTag';
  var libCache = null;
  window.TrendingSnapshot = window.TrendingSnapshot || [{"rank":1,"pop":44,"name":"Spider-Man Brand New Day","year":2026,"kind":"Movie"},{"rank":2,"pop":36,"name":"Lanterns","year":2026,"kind":"Series"},{"rank":3,"pop":9,"name":"Modern Family","year":2009,"kind":"Series"},{"rank":4,"pop":57,"name":"Awarapan 2","year":2026,"kind":"Movie"},{"rank":5,"pop":23,"name":"Reacher","year":2022,"kind":"Series"},{"rank":6,"pop":58,"name":"MobLand","year":2025,"kind":"Series"},{"rank":7,"pop":37,"name":"Ted Lasso","year":2020,"kind":"Series"},{"rank":8,"pop":22,"name":"Koffee with Karan","year":2004,"kind":"Series"},{"rank":9,"pop":19,"name":"The Big Bang Theory","year":2007,"kind":"Series"},{"rank":10,"pop":59,"name":"VisionQuest","year":2026,"kind":"Series"},{"rank":11,"pop":60,"name":"Matchbox The Movie","year":2026,"kind":"Movie"},{"rank":12,"pop":61,"name":"Khalifa: The Ruler","year":2026,"kind":"Movie"},{"rank":13,"pop":62,"name":"Fall 2 Deadpoint","year":2026,"kind":"Movie"},{"rank":14,"pop":63,"name":"Hope","year":2026,"kind":"Movie"},{"rank":15,"pop":64,"name":"Runner","year":2026,"kind":"Movie"},{"rank":16,"pop":41,"name":"Drishyam 3","year":2026,"kind":"Movie"},{"rank":17,"pop":65,"name":"Hunkkaar: The Roar","year":2026,"kind":"Series"},{"rank":18,"pop":1,"name":"House of the Dragon","year":2022,"kind":"Series"},{"rank":19,"pop":66,"name":"Insidious Out Of The Further","year":2026,"kind":"Movie"},{"rank":20,"pop":67,"name":"The Love Hypothesis","year":2026,"kind":"Movie"},{"rank":21,"pop":68,"name":"Modha Rathri","year":2026,"kind":"Movie"},{"rank":22,"pop":69,"name":"Lust Stories 3","year":2026,"kind":"Movie"},{"rank":23,"pop":20,"name":"The Rookie","year":2018,"kind":"Series"},{"rank":24,"pop":70,"name":"Dark Matter","year":2024,"kind":"Series"},{"rank":25,"pop":71,"name":"Kurulu\u015f Osman","year":2019,"kind":"Series"},{"rank":26,"pop":72,"name":"Chumbak","year":2026,"kind":"Series"},{"rank":27,"pop":73,"name":"Bethlehem Kudumba Unit","year":2026,"kind":"Movie"},{"rank":28,"pop":74,"name":"Carrie","year":2026,"kind":"Series"},{"rank":29,"pop":2,"name":"Game of Thrones","year":2011,"kind":"Series"},{"rank":30,"pop":75,"name":"Grand Theft Auto VI An Extended Look","year":2026,"kind":"Movie"},{"rank":31,"pop":3,"name":"Mirzapur","year":2018,"kind":"Series"},{"rank":32,"pop":4,"name":"The Boys","year":2019,"kind":"Series"},{"rank":33,"pop":5,"name":"Money Heist","year":2017,"kind":"Series"},{"rank":34,"pop":6,"name":"Panchayat","year":2020,"kind":"Series"},{"rank":35,"pop":7,"name":"Pushpa","year":2021,"kind":"Movie"},{"rank":36,"pop":8,"name":"Loki","year":2021,"kind":"Series"},{"rank":37,"pop":10,"name":"The Great Indian Kapil Show","year":2024,"kind":"Series"},{"rank":38,"pop":11,"name":"Friends","year":1994,"kind":"Series"},{"rank":39,"pop":12,"name":"Stranger Things","year":2016,"kind":"Series"},{"rank":40,"pop":13,"name":"Young Sheldon","year":2017,"kind":"Series"},{"rank":41,"pop":14,"name":"Gandii Baat","year":2018,"kind":"Series"},{"rank":42,"pop":15,"name":"Squid Game","year":2021,"kind":"Series"},{"rank":43,"pop":16,"name":"Spider-Man: No Way Home","year":2021,"kind":"Movie"},{"rank":44,"pop":17,"name":"Rick and Morty","year":2013,"kind":"Series"},{"rank":45,"pop":18,"name":"Attack on Titan","year":2013,"kind":"Series"},{"rank":46,"pop":21,"name":"The Last of Us","year":2023,"kind":"Series"},{"rank":47,"pop":24,"name":"The Good Doctor","year":2017,"kind":"Series"},{"rank":48,"pop":25,"name":"How I Met Your Mother","year":2005,"kind":"Series"},{"rank":49,"pop":26,"name":"What If\u2026?","year":2021,"kind":"Series"},{"rank":50,"pop":27,"name":"The Falcon & Winter Soldier","year":2021,"kind":"Series"},{"rank":51,"pop":28,"name":"Family Guy","year":1999,"kind":"Series"},{"rank":52,"pop":29,"name":"FROM","year":2022,"kind":"Series"},{"rank":53,"pop":30,"name":"The Family Man","year":2019,"kind":"Series"},{"rank":54,"pop":31,"name":"Euphoria","year":2019,"kind":"Series"},{"rank":55,"pop":32,"name":"A Knight of the Seven Kingdoms","year":2026,"kind":"Series"},{"rank":56,"pop":33,"name":"Dhurandhar","year":2025,"kind":"Movie"},{"rank":57,"pop":34,"name":"Georgie & Mandy\u2019s First Marriage","year":2024,"kind":"Series"},{"rank":58,"pop":35,"name":"Obsession","year":2026,"kind":"Movie"},{"rank":59,"pop":38,"name":"Spartacus: House of Ashur","year":2025,"kind":"Series"},{"rank":60,"pop":39,"name":"Musafir Cafe","year":2026,"kind":"Series"},{"rank":61,"pop":40,"name":"Pluribus","year":2025,"kind":"Series"},{"rank":62,"pop":42,"name":"Off Campus","year":2026,"kind":"Series"},{"rank":63,"pop":43,"name":"Daredevil: Born Again","year":2025,"kind":"Series"},{"rank":64,"pop":45,"name":"INVINCIBLE","year":2021,"kind":"Series"},{"rank":65,"pop":46,"name":"Raakh","year":2026,"kind":"Series"},{"rank":66,"pop":47,"name":"Silo","year":2023,"kind":"Series"},{"rank":67,"pop":48,"name":"Pritam and Pedro","year":2026,"kind":"Series"},{"rank":68,"pop":49,"name":"Dhurandhar The Revenge","year":2026,"kind":"Movie"},{"rank":69,"pop":50,"name":"Project Hail Mary","year":2026,"kind":"Movie"},{"rank":70,"pop":51,"name":"Vishwanath & Sons","year":2026,"kind":"Movie"},{"rank":71,"pop":52,"name":"Agent Kim Reactivated","year":2026,"kind":"Series"},{"rank":72,"pop":53,"name":"Adarsh Baal Vidyalaya","year":2026,"kind":"Series"},{"rank":73,"pop":54,"name":"Hai Jawani Toh Ishq Hona Hai","year":2026,"kind":"Movie"},{"rank":74,"pop":55,"name":"Alpha","year":2026,"kind":"Movie"},{"rank":75,"pop":56,"name":"Spider-Noir","year":2026,"kind":"Series"},{"rank":76,"pop":76,"name":"Man Of War","year":2026,"kind":"Movie"},{"rank":77,"pop":77,"name":"Baby Do Die Do","year":2026,"kind":"Movie"},{"rank":78,"pop":78,"name":"Fallout","year":2024,"kind":"Series"},{"rank":79,"pop":79,"name":"Shaque: Trust No One","year":2026,"kind":"Series"}];

  return {
    jf: function () { return JF; },
    signedIn: function () { return !!(token() && userId()); },
    serverInfo: function () { return fetch(JF + '/System/Info/Public').then(function (r) { return r.json(); }); },
    qcInitiate: function () { return req('POST', '/QuickConnect/Initiate'); },
    qcPoll: function (secret) { return req('GET', '/QuickConnect/Connect?' + qs({ secret: secret })); },
    qcAuthenticate: function (secret) {
      return req('POST', '/Users/AuthenticateWithQuickConnect', { Secret: secret }).then(function (r) {
        set('token', r.AccessToken); set('userId', r.User.Id); set('userName', r.User.Name); return r;
      });
    },
    loginPassword: function (username, password) {
      return req('POST', '/Users/AuthenticateByName', { Username: username, Pw: password }).then(function (r) {
        set('token', r.AccessToken); set('userId', r.User.Id); set('userName', r.User.Name); return r;
      });
    },
    // Only called if the server itself rejects the token (e.g. revoked); never as a routine step.
    forgetToken: function () { try { localStorage.removeItem(NS + 'token'); localStorage.removeItem(NS + 'userId'); } catch (e) {} },
    userName: function () { return get('userName') || ''; },
    resume: function () { return req('GET', '/UserItems/Resume?' + qs({ userId: userId(), Limit: 16, MediaTypes: 'Video', Fields: FIELDS, EnableImageTypes: 'Primary,Backdrop,Thumb' })); },
    latest: function (type) { return req('GET', '/Items?' + qs({ userId: userId(), IncludeItemTypes: type, Recursive: true, SortBy: 'PremiereDate,ProductionYear,SortName', SortOrder: 'Descending', Limit: 40, Fields: FIELDS })); },
    recentlyUploaded: function (limit) { return req('GET', '/Items?' + qs({ userId: userId(), IncludeItemTypes: 'Movie,Series', Recursive: true, SortBy: 'DateCreated,SortName', SortOrder: 'Descending', Limit: limit || 40, Fields: FIELDS })); },
    boxSets: function (limit) { return req('GET', '/Items?' + qs({ userId: userId(), IncludeItemTypes: 'BoxSet', Recursive: true, SortBy: 'DateCreated,SortName', SortOrder: 'Descending', Limit: limit || 40, Fields: FIELDS })); },
    collectionItems: function (parentId) { return req('GET', '/Items?' + qs({ userId: userId(), ParentId: parentId, IncludeItemTypes: 'Movie,Series', Recursive: true, SortBy: 'PremiereDate,ProductionYear,SortName', SortOrder: 'Ascending', Limit: 100, Fields: FIELDS })); },
    item: function (id) { return req('GET', '/Items/' + id + '?' + qs({ userId: userId(), Fields: DETAIL_FIELDS })); },
    search: function (term, limit) { return req('GET', '/Items?' + qs({ userId: userId(), searchTerm: term, IncludeItemTypes: 'Movie,Series', Recursive: true, Limit: limit || 30, Fields: FIELDS })); },
    nextUp: function (seriesId, limit) { return req('GET', '/Shows/NextUp?' + qs({ userId: userId(), seriesId: seriesId, Limit: limit || (seriesId ? 1 : 16), Fields: FIELDS, EnableImageTypes: 'Primary,Backdrop,Thumb' })); },
    episodes: function (seriesId) { return req('GET', '/Shows/' + seriesId + '/Episodes?' + qs({ userId: userId(), Fields: FIELDS, Limit: 300 })); },
    similar: function (id) { return req('GET', '/Items/' + id + '/Similar?' + qs({ userId: userId(), Limit: 16, Fields: FIELDS })); },
    byPerson: function (personId, personName) {
      var q = {
        userId: userId(),
        IncludeItemTypes: 'Movie,Series',
        Recursive: true,
        SortBy: 'PremiereDate,ProductionYear,SortName',
        SortOrder: 'Descending',
        Limit: 60,
        Fields: FIELDS
      };
      if (personId) q.PersonIds = personId;
      else if (personName) q.Person = personName;
      return req('GET', '/Items?' + qs(q)).then(function (r) {
        if ((r.Items || []).length || !personName || !personId) return r;
        delete q.PersonIds;
        q.Person = personName;
        return req('GET', '/Items?' + qs(q));
      });
    },
    personImg: function (personId, tag, w) {
      if (personId && typeof personId === 'object') { tag = personId.PrimaryImageTag; personId = personId.Id; }
      return (personId && tag) ? JF + '/Items/' + personId + '/Images/Primary?' + qs({ fillWidth: w || 180, quality: 85, tag: tag }) : '';
    },
    favorites: function () { return req('GET', '/Items?' + qs({ userId: userId(), Filters: 'IsFavorite', IncludeItemTypes: 'Movie,Series', Recursive: true, SortBy: 'DateCreated', SortOrder: 'Descending', Limit: 24, Fields: FIELDS })); },
    setFavorite: function (id, on) { return req(on ? 'POST' : 'DELETE', '/UserFavoriteItems/' + id + '?' + qs({ userId: userId() })); },
    setPlayed: function (id, on) { return req(on ? 'POST' : 'DELETE', '/UserPlayedItems/' + id + '?' + qs({ userId: userId() })); },
    playbackInfo: function (id, opt) {
      opt = opt || {};
      var maxBitrate = opt.maxBitrate || 16000000;
      try { var sb = +localStorage.getItem('mltv.maxBitrate'); if (sb >= 2000000) maxBitrate = sb; } catch (e) {}
      return req('POST', '/Items/' + id + '/PlaybackInfo?' + qs({
        userId: userId(),
        MaxStreamingBitrate: maxBitrate,
        StartTimeTicks: opt.startTicks || 0,
        AudioStreamIndex: opt.audio,
        SubtitleStreamIndex: opt.sub,
        MediaSourceId: opt.source,
        EnableDirectPlay: opt.noDirect ? false : true,
        EnableDirectStream: true,
        EnableTranscoding: true,
        AllowVideoStreamCopy: true,
        AllowAudioStreamCopy: true,
        AutoOpenLiveStream: true
      }), {
        DeviceProfile: {
          MaxStreamingBitrate: maxBitrate,
          MaxStaticBitrate: maxBitrate,
          MusicStreamingTranscodingBitrate: 192000,
          DirectPlayProfiles: opt.noDirect ? [] : [
            { Type: 'Video', Container: 'mp4,m4v', VideoCodec: 'h264', AudioCodec: 'aac,mp3' }
          ],
          TranscodingProfiles: [
            {
              Type: 'Video',
              Container: 'ts',
              Protocol: 'hls',
              VideoCodec: 'h264',
              AudioCodec: 'aac,mp3',
              Context: 'Streaming',
              MaxAudioChannels: '2',
              MinSegments: 2,
              SegmentLength: 3,
              BreakOnNonKeyFrames: true
            }
          ],
          SubtitleProfiles: [{ Format: 'vtt', Method: 'External' }, { Format: 'srt', Method: 'External' }, { Format: 'ass', Method: 'Encode' }, { Format: 'pgssub', Method: 'Encode' }]
        }
      });
    },
    mediaSegments: function (id) {
      return Promise.all([
        req('GET', '/MediaSegments/' + id + '?' + qs({ includeSegmentTypes: 'Intro,Recap,Commercial,Preview,Outro' })).catch(function () { return null; }),
        req('GET', '/Episode/' + id + '/IntroTimestamps').catch(function () { return null; }),
        req('GET', '/Episode/' + id + '/IntroSkipperSegments').catch(function () { return null; }),
        req('GET', '/Items/' + id + '?' + qs({ userId: userId(), Fields: 'Chapters' })).catch(function () { return null; })
      ]).then(function (res) {
        return {
          mediaSegments: res[0],
          introTimestamps: res[1],
          introSkipper: res[2],
          chapters: (res[3] && res[3].Chapters) || []
        };
      });
    },
    subtitleUrl: function (item, src, stream) {
      return JF + '/Videos/' + item.Id + '/' + src.Id + '/Subtitles/' + stream.Index + '/0/Stream.vtt?' + qs({ api_key: token() });
    },
    streamUrl: function (item, info) {
      var src = info.MediaSources[0];
      if (src.TranscodingUrl) return JF + src.TranscodingUrl;
      return JF + '/Videos/' + item.Id + '/stream?' + qs({ static: true, mediaSourceId: src.Id, api_key: token() /* device-local only */ });
    },
    submitRequest: function (item) {
      var payload = {
        mediaType: (item && item.Type === 'Series') ? 'tv' : 'movie',
        title: (item && item.Name) || '',
        year: (item && item.ProductionYear) || null,
        platform: (item && item._platformName) || '',
        requestedBy: get('userName') || userId() || 'MojaLoss TV'
      };
      return req('POST', '/MojaLoss/Requests', payload).catch(function () {
        return { ok: true, localOnly: true };
      });
    },
    reportProgress: function (kind, body) { return req('POST', '/Sessions/Playing' + (kind ? '/' + kind : ''), body).catch(function () {}); },
    img: function (it, type, w) {
      if (!it) return '';
      var id = it.Id, tag, imgType = type || 'Primary';
      if (imgType === 'Backdrop') {
        tag = (it.BackdropImageTags || [])[0];
        if (!tag && it.ParentBackdropItemId && (it.ParentBackdropImageTags || [])[0]) {
          id = it.ParentBackdropItemId;
          tag = it.ParentBackdropImageTags[0];
        }
        if (tag && id && !it._requestable) return JF + '/Items/' + id + '/Images/Backdrop?' + qs({ fillWidth: w || 1280, quality: 85, tag: tag });
        if (it.ImageTags && it.ImageTags.Thumb && id && !it._requestable) return JF + '/Items/' + id + '/Images/Thumb?' + qs({ fillWidth: w || 720, quality: 85, tag: it.ImageTags.Thumb });
        if (it._wpPoster || it._posterUrl) return it._wpPoster || it._posterUrl;
        if (it.ImageTags && it.ImageTags.Primary && id && !it._requestable) return JF + '/Items/' + id + '/Images/Primary?' + qs({ fillWidth: w || 720, quality: 85, tag: it.ImageTags.Primary });
        return '';
      }
      tag = (it.ImageTags || {})[imgType];
      if (!tag && imgType === 'Primary' && it.SeriesId && it.SeriesPrimaryImageTag) {
        id = it.SeriesId;
        tag = it.SeriesPrimaryImageTag;
      }
      if (tag && id && !it._requestable) return JF + '/Items/' + id + '/Images/' + imgType + '?' + qs({ fillWidth: w || 260, quality: 85, tag: tag });
      if (it._wpPoster || it._posterUrl) return it._wpPoster || it._posterUrl;
      if (it.ImageTags && it.ImageTags.Thumb && id && !it._requestable) return JF + '/Items/' + id + '/Images/Thumb?' + qs({ fillWidth: w || 360, quality: 85, tag: it.ImageTags.Thumb });
      if (it.ParentThumbItemId && it.ParentThumbImageTag && !it._requestable) return JF + '/Items/' + it.ParentThumbItemId + '/Images/Thumb?' + qs({ fillWidth: w || 360, quality: 85, tag: it.ParentThumbImageTag });
      if ((it.BackdropImageTags || [])[0] && id && !it._requestable) return JF + '/Items/' + id + '/Images/Backdrop?' + qs({ fillWidth: w || 360, quality: 85, tag: it.BackdropImageTags[0] });
      if (it.ParentBackdropItemId && (it.ParentBackdropImageTags || [])[0] && !it._requestable) return JF + '/Items/' + it.ParentBackdropItemId + '/Images/Backdrop?' + qs({ fillWidth: w || 360, quality: 85, tag: it.ParentBackdropImageTags[0] });
      return '';
    },
    // Platforms & Regional categories: public WordPress categories (no auth). Shown only if the site lists posts AND they match this library.
    platforms: function () {
      var REGIONAL_SPECS = [
        { slug: 'hindi', title: 'Hindi Movies', kind: 'Movie' },
        { slug: 'hindi-tv-shows', title: 'Hindi Series', kind: 'Series' },
        { slug: 'international-movies', title: 'International Movies', kind: 'Movie' },
        { slug: 'korean-tv-shows', title: 'Korean Series', kind: 'Series' },
        { slug: 'tamil-movies', title: 'Tamil Movies', kind: 'Movie' },
        { slug: 'bengali', title: 'Bengali Movies', kind: 'Movie' }
      ];
      return timed(fetch(WP + 'categories?per_page=100&_fields=id,count,name,slug,parent')).then(function (r) { return r.json(); }).then(function (cats) {
        var tv = cats.filter(function (c) { return c.slug === 'tv-shows' && c.parent === 0; })[0];
        var list = cats.filter(function (c) { return c.parent === 0 && PLATFORM_SLUGS.indexOf(c.slug) >= 0 && c.count >= 1; })
          .sort(function (a, b) { return b.count - a.count; });
        if (!list.some(function (c) { return c.slug === 'imdb'; })) {
          list.push({ id: 'imdb', name: 'IMDb', slug: 'imdb', count: 250, parent: 0 });
        }
        var regionals = [];
        REGIONAL_SPECS.forEach(function (spec) {
          var found = cats.filter(function (c) { return c.slug === spec.slug && c.count >= 1; })[0];
          if (found) regionals.push({ id: found.id, slug: spec.slug, title: spec.title, kind: spec.kind, count: found.count });
        });
        return { tvId: tv ? tv.id : 0, list: list, regionals: regionals };
      });
    },
    trending: function () {
      var tUrl = mock ? mock + '/trending/' : 'https://www.mojaloss.stream/trending/';
      var snap = window.TrendingSnapshot || [];
      function parseHtml(htmlStr) {
        if (!htmlStr || typeof htmlStr !== 'string') return [];
        var doc = new DOMParser().parseFromString(htmlStr, 'text/html');
        var rows = doc.querySelectorAll('#listView .list-item');
        var out = [];
        for (var i = 0; i < rows.length && out.length < 40; i++) {
          var row = rows[i];
          var rEl = row.querySelector('.list-rank');
          var tEl = row.querySelector('.list-title a');
          var isMovie = !!row.querySelector('.badge.movie');
          var isTv = !!row.querySelector('.badge.tv');
          var rank = rEl ? parseInt(rEl.textContent.trim(), 10) : NaN;
          var label = tEl ? tEl.textContent.trim() : '';
          if (!rank || !label || (!isMovie && !isTv)) continue;
          var m = /\s*\((\d{4})(?:[-–\u2013]\s*\d{0,4})?\)\s*$/.exec(label);
          out.push({ rank: rank, name: m ? label.slice(0, m.index).trim() : label, year: m ? +m[1] : null, kind: isMovie ? 'Movie' : 'Series' });
        }
        out.sort(function (a, b) { return a.rank - b.rank; });
        return out;
      }
      if (!mock) {
        var periods = ['30', 'all', '365', '180'];
        return Promise.all(periods.map(function (p) {
          var u = p === '30' ? tUrl : (tUrl + '?period=' + p);
          return timed(fetch(u), 2200).then(function (r) {
            return r && r.ok ? r.text() : '';
          }).catch(function () { return ''; });
        })).then(function (htmls) {
          var pLists = htmls.map(parseHtml);
          var combined = [].concat(pLists[0] || [], pLists[1] || [], pLists[2] || [], pLists[3] || []);
          if (!combined.length) return snap;
          var popOrder = [].concat(pLists[1] || [], pLists[2] || [], pLists[3] || [], pLists[0] || [], snap.slice().sort(function (a, b) { return (a.pop || 999) - (b.pop || 999); }));
          var popMap = {}, popSeq = 0;
          popOrder.forEach(function (x) {
            var pk = String(x.name).toLowerCase().replace(/&amp;/g, 'and').replace(/&/g, 'and').replace(/[^a-z0-9]+/g, '') + ':' + x.kind;
            if (!popMap[pk]) popMap[pk] = ++popSeq;
          });
          var seen = {}, merged = [];
          combined.concat(snap).forEach(function (x) {
            var k = String(x.name).toLowerCase().replace(/&amp;/g, 'and').replace(/&/g, 'and').replace(/[^a-z0-9]+/g, '') + ':' + x.kind;
            if (!seen[k]) { seen[k] = 1; merged.push({ rank: merged.length + 1, pop: popMap[k] || (merged.length + 1), name: x.name, year: x.year, kind: x.kind }); }
          });
          window.TrendingSnapshot = merged;
          return merged;
        }, function () {
          return snap;
        });
      }
      return timed(fetch(tUrl), 5000).then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.text();
      }).then(function (htmlStr) {
        var parsed = parseHtml(htmlStr);
        return parsed.length ? parsed : [];
      }, function () {
        return [];
      });
    },
    libraryIndex: function () {
      if (libCache && !mock) return Promise.resolve(libCache);
      if (mock) {
        return req('GET', '/Items?' + qs({ userId: userId(), IncludeItemTypes: 'Movie,Series', Recursive: true, Fields: INDEX_FIELDS, Limit: 12500 }));
      }
      return Promise.all([
        req('GET', '/Items?' + qs({ userId: userId(), IncludeItemTypes: 'Series', Recursive: true, SortBy: 'PremiereDate,ProductionYear,SortName', SortOrder: 'Descending', Fields: INDEX_FIELDS, Limit: 3000 })).catch(function () { return { Items: [] }; }),
        req('GET', '/Items?' + qs({ userId: userId(), IncludeItemTypes: 'Movie', Recursive: true, SortBy: 'PremiereDate,ProductionYear,SortName', SortOrder: 'Descending', Fields: INDEX_FIELDS, Limit: 8500 })).catch(function () { return { Items: [] }; })
      ]).then(function (parts) {
        var combined = ((parts[0] && parts[0].Items) || []).concat((parts[1] && parts[1].Items) || []);
        var res = { Items: combined, TotalRecordCount: combined.length };
        if (combined.length) libCache = res;
        return res;
      });
    },
    platformTitles: function (platformId) {
      if (platformId === 'imdb' || !platformId) return Promise.resolve([]);
      return timed(fetch(WP + 'posts?' + qs({ categories: platformId, per_page: 100, orderby: 'date', order: 'desc', _fields: 'id,date,title,categories,class_list,jetpack_featured_media_url,excerpt' })), 8000).then(function (r) { return r.json(); })
        .then(function (posts) {
          if (!Array.isArray(posts)) return [];
          return posts.map(function (p) {
            var doc = new DOMParser().parseFromString((p.title && p.title.rendered) || '', 'text/html');
            var t = (doc.body.textContent || '').trim();
            var m = /\s*\((\d{4})(?:\s*[-–\u2013]\s*(\d{0,4}))?\)\s*$/.exec(t);
            var isDashRange = /\(\d{4}\s*[-–\u2013]/.test(t);
            var isOngoing = /\(\d{4}\s*[-–\u2013]\s*\)\s*$/.test(t);
            var isTvClass = (p.class_list && p.class_list.indexOf('category-tv-shows') >= 0) || (p.categories && p.categories.indexOf(1470) >= 0) || (p.categories && p.categories.indexOf(1) >= 0) || isDashRange;
            var yr = m ? +m[1] : null;
            if (yr && (yr < 1888 || yr > 2030)) yr = null;
            var yrEnd = (m && m[2]) ? +m[2] : null;
            var postYear = p.date ? parseInt(String(p.date).slice(0, 4), 10) : null;
            var exText = '';
            if (p.excerpt && p.excerpt.rendered) {
              var exDoc = new DOMParser().parseFromString(p.excerpt.rendered, 'text/html');
              exText = (exDoc.body.textContent || '').replace(/Latest:[\s\S]*$/i, '').replace(/Download[\s\S]*$/i, '').trim();
            }
            return {
              id: p.id,
              date: p.date || '',
              postYear: (postYear && postYear >= 2000 && postYear <= 2030) ? postYear : null,
              name: m ? t.slice(0, m.index).trim() : t,
              year: yr,
              yearEnd: yrEnd,
              isOngoing: isOngoing,
              kind: isTvClass ? 'Series' : 'Movie',
              poster: p.jetpack_featured_media_url || '',
              overview: exText
            };
          }).filter(function (p) { return p.name; });
        });
    }
  };
})();
