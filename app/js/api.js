/* Jellyfin + mojaloss.stream public API. Signs in ONCE (Quick Connect) and keeps the token. */
var Api = (function () {
  var q = new URLSearchParams(location.search);
  var mock = q.get('mock'); // test-only override, never persisted
  var JF = mock ? mock + '/jelly' : 'https://mojaloss.stream/jelly';
  var WP = mock ? mock + '/wp-json/wp/v2/' : 'https://www.mojaloss.stream/wp-json/wp/v2/';
  var NS = mock ? 'mltest.' : 'ml.';
  var VERSION = '0.1.0';
  var PLATFORM_SLUGS = ['netflix','prime','apple','disney','hbo','hulu','paramount','peacock','hotstar','zee5','sonyliv','altbalaji','mxplayer','voot','hoichoi','cruncyroll','crunchyroll','showtime'];

  function get(k) { try { return localStorage.getItem(NS + k); } catch (e) { return null; } }
  function set(k, v) { try { localStorage.setItem(NS + k, v); } catch (e) {} }

  function deviceId() {
    var id = get('deviceId');
    if (!id) {
      id = 'mltv-' + Array.prototype.map.call(crypto.getRandomValues(new Uint8Array(12)), function (b) { return ('0' + b.toString(16)).slice(-2); }).join('');
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
    var h = 'MediaBrowser Client="MojaLoss TV", Device="' + esc(deviceName()) + '", DeviceId="' + deviceId() + '", Version="' + VERSION + '"';
    var t = token(); if (t) h += ', Token="' + t + '"';
    return h;
  }
  function req(method, path, body) {
    var o = { method: method, headers: { 'Authorization': authHeader(), 'Accept': 'application/json' } };
    if (body !== undefined) { o.headers['Content-Type'] = 'application/json'; o.body = JSON.stringify(body); }
    return fetch(JF + path, o).then(function (r) {
      if (r.status === 401 && token()) { var e = new Error('unauthorized'); e.status = 401; throw e; }
      if (!r.ok) { var e2 = new Error('HTTP ' + r.status); e2.status = r.status; throw e2; }
      return r.status === 204 ? null : r.json();
    });
  }
  function qs(o) { return Object.keys(o).filter(function (k) { return o[k] !== undefined && o[k] !== ''; }).map(function (k) { return k + '=' + encodeURIComponent(o[k]); }).join('&'); }
  var FIELDS = 'PrimaryImageAspectRatio,Overview,Genres,ProductionYear,RunTimeTicks,OfficialRating,UserData,ImageTags,BackdropImageTags,SeriesName';

  return {
    signedIn: function () { return !!(token() && userId()); },
    serverInfo: function () { return fetch(JF + '/System/Info/Public').then(function (r) { return r.json(); }); },
    qcInitiate: function () { return req('POST', '/QuickConnect/Initiate'); },
    qcPoll: function (secret) { return req('GET', '/QuickConnect/Connect?' + qs({ secret: secret })); },
    qcAuthenticate: function (secret) {
      return req('POST', '/Users/AuthenticateWithQuickConnect', { Secret: secret }).then(function (r) {
        set('token', r.AccessToken); set('userId', r.User.Id); set('userName', r.User.Name); return r;
      });
    },
    // Only called if the server itself rejects the token (e.g. revoked); never as a routine step.
    forgetToken: function () { try { localStorage.removeItem(NS + 'token'); localStorage.removeItem(NS + 'userId'); } catch (e) {} },
    userName: function () { return get('userName') || ''; },
    resume: function () { return req('GET', '/UserItems/Resume?' + qs({ userId: userId(), Limit: 16, MediaTypes: 'Video', Fields: FIELDS, EnableImageTypes: 'Primary,Backdrop,Thumb' })); },
    latest: function (type) { return req('GET', '/Items?' + qs({ userId: userId(), IncludeItemTypes: type, Recursive: true, SortBy: 'PremiereDate,DateCreated', SortOrder: 'Descending', Limit: 24, Fields: FIELDS })); },
    item: function (id) { return req('GET', '/Items/' + id + '?' + qs({ userId: userId(), Fields: FIELDS })); },
    search: function (term, limit) { return req('GET', '/Items?' + qs({ userId: userId(), searchTerm: term, IncludeItemTypes: 'Movie,Series', Recursive: true, Limit: limit || 30, Fields: FIELDS })); },
    nextUp: function (seriesId) { return req('GET', '/Shows/NextUp?' + qs({ userId: userId(), seriesId: seriesId, Limit: 1, Fields: FIELDS })); },
    episodes: function (seriesId) { return req('GET', '/Shows/' + seriesId + '/Episodes?' + qs({ userId: userId(), Limit: 1, Fields: FIELDS })); },
    playbackInfo: function (id) {
      return req('POST', '/Items/' + id + '/PlaybackInfo?' + qs({ userId: userId() }), {
        DeviceProfile: {
          MaxStreamingBitrate: 40000000,
          DirectPlayProfiles: [{ Type: 'Video', Container: 'mp4,mkv,ts,m4v', VideoCodec: 'h264,hevc', AudioCodec: 'aac,ac3,eac3,mp3' }],
          TranscodingProfiles: [{ Type: 'Video', Container: 'ts', Protocol: 'hls', VideoCodec: 'h264', AudioCodec: 'aac', Context: 'Streaming', BreakOnNonKeyFrames: true }],
          SubtitleProfiles: [{ Format: 'vtt', Method: 'External' }]
        }
      });
    },
    streamUrl: function (item, info) {
      var src = info.MediaSources[0];
      if (src.TranscodingUrl) return JF + src.TranscodingUrl;
      return JF + '/Videos/' + item.Id + '/stream?' + qs({ static: true, mediaSourceId: src.Id, api_key: token() /* device-local only */ });
    },
    reportProgress: function (kind, body) { return req('POST', '/Sessions/Playing' + (kind ? '/' + kind : ''), body).catch(function () {}); },
    img: function (it, type, w) {
      var id = it.Id, tag;
      if (type === 'Backdrop') { tag = (it.BackdropImageTags || [])[0]; if (!tag && it.ParentBackdropItemId) { id = it.ParentBackdropItemId; tag = (it.ParentBackdropImageTags || [])[0]; } }
      else { tag = (it.ImageTags || {})[type]; if (!tag && it.SeriesId && it.SeriesPrimaryImageTag) { id = it.SeriesId; tag = it.SeriesPrimaryImageTag; } }
      return tag ? JF + '/Items/' + id + '/Images/' + type + '?' + qs({ fillWidth: w, quality: 85, tag: tag }) : '';
    },
    // Platforms: public WordPress categories (no auth). A platform shows only if the site lists posts AND they match this library.
    platforms: function () {
      return fetch(WP + 'categories?per_page=100&_fields=id,count,name,slug,parent').then(function (r) { return r.json(); }).then(function (cats) {
        var tv = cats.filter(function (c) { return c.slug === 'tv-shows' && c.parent === 0; })[0];
        var list = cats.filter(function (c) { return c.parent === 0 && PLATFORM_SLUGS.indexOf(c.slug) >= 0 && c.count >= 1; })
          .sort(function (a, b) { return b.count - a.count; });
        return { tvId: tv ? tv.id : 0, list: list };
      });
    },
    libraryIndex: function () { return req('GET', '/Items?' + qs({ userId: userId(), IncludeItemTypes: 'Movie,Series', Recursive: true, Fields: FIELDS, Limit: 5000 })); },
    platformTitles: function (platformId) {
      return fetch(WP + 'posts?' + qs({ categories: platformId, per_page: 12, orderby: 'date', order: 'desc', _fields: 'id,title' })).then(function (r) { return r.json(); })
        .then(function (posts) {
          return posts.map(function (p) {
            var t = (new DOMParser().parseFromString(p.title.rendered, 'text/html').body.textContent || '').trim();
            var m = /\s*\((\d{4})(?:[-–]\d{0,4})?\)\s*$/.exec(t);
            return { name: m ? t.slice(0, m.index).trim() : t, year: m ? +m[1] : null };
          }).filter(function (p) { return p.name; });
        });
    }
  };
})();
