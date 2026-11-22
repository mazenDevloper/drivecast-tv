// 🧩 Widgets on «وضع الأعلى» (the photo view): couch-tv's widgets (MIT, cw/LICENSE-couch-tv.txt — widgets.js draws each
// one's inside), placed on an 18 × 10 grid of half cells over the 1920 × 1080 screen.
//   ✏️  edit: drag a widget to move it, drag its corner to size it, ✕ removes it (touch, pen or mouse — the web version on a
//       touch laptop); «تم» keeps it
//   ➕  the gallery: every widget with a live preview (demo data); a tap puts it in the first free place
// Kept in the settings as «cw» (JSON), so the web version and the TV share one layout through the cloud copy.
// What a widget shows is what DriveCast has (the weather of the prayer city, the TV's apps); a widget that needs something
// DriveCast doesn't have yet says so in its own box (never pretend data).
(function () {
  'use strict';
  var COLS = 18, ROWS = 10, CWID = 1920 / COLS, CHEI = 1080 / ROWS, GAP = 14;
  var AR = { clock: 'ساعة رقمية', tallclock: 'ساعة طويلة', glassclock: 'ساعة زجاجية', analog: 'ساعة بعقارب', flipclock: 'ساعة قلّابة', wordclock: 'الوقت بالكلمات',
    solar: 'ساعة الشمس', worldclock: 'ساعات العالم', countdown: 'عدّ تنازلي', date: 'التاريخ', month: 'الشهر', timer: 'مؤقّت', weather: 'الطقس', bigtemp: 'رقم كبير',
    hourly: 'كل ساعة', week: 'هذا الأسبوع', rain: 'مطر قريب', uv: 'الأشعة فوق البنفسجية', sun: 'الشروق والغروب', moon: 'القمر', air: 'جودة الهواء', surf: 'الأمواج',
    photo: 'صورة', slideshow: 'عرض صور', mosaic: 'فسيفساء صور', artframe: 'إطار فني', keep: 'كمّل المشاهدة', keeprow: 'صف كمّل المشاهدة', upnext: 'التالي',
    picks: 'مقترحات لك', app: 'تطبيق', folder: 'مجلد تطبيقات', dock: 'شريط تطبيقات', appgrid: 'شبكة تطبيقات', games: 'ألعاب', nowplaying: 'يشتغل الحين',
    soundscape: 'أصوات هادئة', queue: 'قائمة الأغاني', speakers: 'السماعات', aircon: 'المكيّف', scenes: 'مشاهد البيت', controls: 'مركز التحكم', camera: 'كاميرا',
    doorbell: 'جرس الباب', energy: 'الطاقة الشمسية', sensor: 'حسّاس', battery: 'البطاريات', bins: 'ليلة النفايات', people: 'الأشخاص', card: 'كرت مخصّص',
    quote: 'اقتباس', word: 'كلمة اليوم', news: 'العناوين', sports: 'النتائج', stocks: 'الأسواق', commute: 'المشوار', welcome: 'كلمات ترحيب', quick: 'أزرار سريعة',
    agenda: 'الأجندة', note: 'ملاحظة العائلة', list: 'قائمة', wifi: 'واي فاي الضيوف', guide: 'دليل البيت', memory: 'في مثل هذا اليوم', screentime: 'وقت الشاشة' };
  var GROUPS = [['🕐 الوقت', ['clock', 'tallclock', 'analog', 'flipclock', 'wordclock', 'solar', 'worldclock', 'countdown', 'date', 'month', 'timer']],
    ['🌤️ الطقس والسماء', ['weather', 'bigtemp', 'hourly', 'week', 'rain', 'uv', 'sun', 'moon', 'air', 'surf']],
    ['🖼️ الصور', ['photo', 'slideshow', 'mosaic', 'artframe', 'memory']],
    ['📺 المشاهدة والتطبيقات', ['keep', 'keeprow', 'upnext', 'picks', 'app', 'folder', 'dock', 'appgrid', 'games', 'screentime']],
    ['🎵 الصوت', ['nowplaying', 'soundscape', 'queue', 'speakers']],
    ['🏠 البيت الذكي', ['aircon', 'scenes', 'controls', 'camera', 'doorbell', 'energy', 'sensor', 'battery', 'bins']],
    ['📰 معلومات', ['news', 'sports', 'stocks', 'commute', 'agenda']],
    ['✍️ كلمات', ['quote', 'word', 'card', 'welcome', 'note', 'list', 'wifi', 'guide', 'people', 'quick']]];
  var NAMED = { line: [4, 1], wline: [8, 1], tile: [2, 2], wtile: [4, 2], small: [4, 4], tall: [4, 6], medium: [8, 4], wide: [12, 4], large: [8, 8], xl: [12, 6], hero: [18, 7], full: [18, 10] };
  var L = null, BAR = null, CAT = null, EDIT = false, ON = false, WXD = null, WXAT = 0;

  function sv(k, d) { try { return typeof S === 'function' ? S(k, d) : d; } catch (e) { return d; } }
  function list() { try { var l = JSON.parse(sv('cw', '[]')); return Array.isArray(l) ? l.filter(function (w) { return w && AR[w.kind]; }) : []; } catch (e) { return []; } }
  function save(l) { try { setPref('cw', JSON.stringify(l)); } catch (e) {} }
  function caps(k) { return (window.CouchWidgetCaps && window.CouchWidgetCaps.get(k)) || null; }
  function sizesOf(k) { var c = caps(k), s = c && c.sizes && c.sizes.s || ['small']; return s.map(function (n) { return NAMED[n]; }).filter(Boolean); }
  // the size a new widget (and its preview) starts at: a middle one that reads well, not the smallest
  function startOf(k) { var c = caps(k), s = (c && c.sizes && c.sizes.s) || ['small'], p = ['small', 'wtile', 'medium', 'tile', 'wide', 'line', 'wline', 'tall', 'large'];
    for (var i = 0; i < p.length; i++) if (s.indexOf(p[i]) >= 0) return NAMED[p[i]]; return NAMED[s[0]] || [4, 4]; }
  function minOf(k) { var c = caps(k); return (c && c.sizes && c.sizes.min) || [2, 1]; }
  function el(tag, cls, html) { var e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; }

  // ---- what DriveCast has, in the shape couch-tv's widgets read (env.data)
  function place() { var p = {}; try { p = P || {}; } catch (e) {} return { name: p.city || 'صلالة', lat: +(p.lat || 17.0158), lon: +(p.lon || 54.0885) }; }   // the prayer city, else Salalah (as the prayer times)
  function weatherLoad() {
    var p = place(); if (!isFinite(p.lat) || Date.now() - WXAT < 30 * 60e3) return; WXAT = Date.now();
    fetch('https://api.open-meteo.com/v1/forecast?latitude=' + p.lat + '&longitude=' + p.lon + '&current=temperature_2m,weather_code,is_day&timezone=auto'
      + '&hourly=temperature_2m,weather_code,uv_index,precipitation_probability&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max,precipitation_probability_max&minutely_15=precipitation&forecast_days=7')
      .then(function (r) { return r.json(); }).then(function (j) {
        var c = j.current, H = j.hourly || {}, D = j.daily || {}, M = j.minutely_15 || {}, at = Math.max(0, (H.time || []).indexOf((c.time || '').slice(0, 13) + ':00'));
        var o = { temp: Math.round(c.temperature_2m), code: Math.round(c.weather_code), is_day: !!c.is_day };
        o.hourly = (H.time || []).slice(at, at + 24).map(function (t, i) { return { time: t, temp: H.temperature_2m[at + i], code: H.weather_code[at + i], uv: H.uv_index[at + i], pop: H.precipitation_probability[at + i] }; });
        o.daily = (D.time || []).map(function (t, i) { return { date: t, code: D.weather_code[i], hi: D.temperature_2m_max[i], lo: D.temperature_2m_min[i], sunrise: D.sunrise[i], sunset: D.sunset[i], uv: D.uv_index_max[i], pop: D.precipitation_probability_max[i] }; });
        o.rain15 = (M.precipitation || []).slice(0, 12);
        if (o.daily.length) { o.hi = Math.round(o.daily[0].hi); o.lo = Math.round(o.daily[0].lo); o.uv = Math.round(o.daily[0].uv || 0); }
        WXD = o; if (ON) render();
      }).catch(function () { WXAT = 0; });
  }
  function apps() { try { return (APPS || []).map(function (a) { return { package: a.pkg, name: a.label }; }); } catch (e) { return []; } }
  function env(demo) {
    if (demo) return { demo: true, look: { icons: 'line' } };
    return { look: { icons: 'line' }, data: { weather: WXD, place: place(), apps: apps(),
      iconUrl: function (pkg) { try { var a = (APPS || []).find(function (x) { return x.pkg === pkg; }); return a && a.art || null; } catch (e) { return null; } } } };
  }
  function draw(w, b, demo) {
    var o = { id: w.id, kind: w.kind, w: b.w, h: b.h, opts: w.opts || {}, style: { panel: 'glass' } };
    try { return window.CouchWidgets.render(o, env(demo)); } catch (e) { return el('div', 'cw', AR[w.kind]); }
  }

  // ---- the layer
  function boxOf(w) { return { x: w.c * CWID + GAP / 2, y: w.r * CHEI + GAP / 2, w: w.w * CWID - GAP, h: w.h * CHEI - GAP }; }
  function render() {
    if (!L || !window.CouchWidgets) return;
    window.CouchWidgets.reset(L); L.textContent = '';
    if (EDIT) L.appendChild(el('div', 'dcwGrid'));
    list().forEach(function (w) {
      var b = boxOf(w), it = el('div', 'dcwIt');
      it.dataset.id = w.id;
      it.style.cssText = 'left:' + b.x + 'px;top:' + b.y + 'px;width:' + b.w + 'px;height:' + b.h + 'px';
      it.appendChild(draw(w, b, false));
      if (EDIT) {
        it.appendChild(el('div', 'dcwMove'));
        var x = el('button', 'dcwDel', '✕'); x.dataset.cw = 'del'; x.title = 'احذف'; it.appendChild(x);
        var z = el('div', 'dcwSize'); z.dataset.cw = 'size'; it.appendChild(z);
        var n = el('span', 'dcwName', AR[w.kind]); it.appendChild(n);
      }
      L.appendChild(it);
    });
    L.classList.toggle('edit', EDIT);
    document.body.classList.toggle('cwEdit', EDIT);
  }
  function free(l, w, h) {
    for (var r = 0; r + h <= ROWS; r++) for (var c = 0; c + w <= COLS; c++) {
      if (r === 0 && c < 6) continue;   // the ✏️ ➕ buttons' corner
      if (!l.some(function (o) { return c < o.c + o.w && o.c < c + w && r < o.r + o.h && o.r < r + h; })) return { c: c, r: r };
    }
    return { c: 0, r: 0 };
  }
  function addKind(k) {
    var l = list(), s = startOf(k), p = free(l, s[0], s[1]);
    l.push({ id: 'w' + Date.now().toString(36), kind: k, c: p.c, r: p.r, w: s[0], h: s[1] });
    save(l); catShow(false); edit(true);
    if (typeof toast === 'function') toast('➕ ' + AR[k] + ' — اسحبه لمكانه، وكبّره من الزاوية');
  }

  // ---- dragging (pointer events: touch, pen, mouse), in grid units of the layer as it is drawn on the screen
  var DRAG = null;
  function gridAt(e) { var r = L.getBoundingClientRect(); return { c: (e.clientX - r.left) / r.width * COLS, r: (e.clientY - r.top) / r.height * ROWS }; }
  function down(e) {
    if (!EDIT) return;
    var t = e.target, it = t.closest && t.closest('.dcwIt'); if (!it) return;
    if (t.dataset.cw === 'del') return;
    e.preventDefault(); e.stopPropagation();
    var l = list(), w = l.find(function (o) { return o.id === it.dataset.id; }); if (!w) return;
    DRAG = { id: w.id, mode: t.dataset.cw === 'size' ? 'size' : 'move', at: gridAt(e), w0: Object.assign({}, w), it: it };
    try { L.setPointerCapture(e.pointerId); } catch (x) {}
    it.classList.add('drag');
  }
  function move(e) {
    if (!DRAG) return; e.preventDefault();
    var g = gridAt(e), dc = g.c - DRAG.at.c, dr = g.r - DRAG.at.r, w = Object.assign({}, DRAG.w0);
    if (DRAG.mode === 'move') { w.c = Math.round(Math.min(COLS - w.w, Math.max(0, w.c + dc))); w.r = Math.round(Math.min(ROWS - w.h, Math.max(0, w.r + dr))); }
    else { var m = minOf(w.kind); w.w = Math.round(Math.min(COLS - w.c, Math.max(m[0], w.w + dc))); w.h = Math.round(Math.min(ROWS - w.r, Math.max(m[1], w.h + dr))); }
    DRAG.now = w;
    var b = boxOf(w); DRAG.it.style.left = b.x + 'px'; DRAG.it.style.top = b.y + 'px'; DRAG.it.style.width = b.w + 'px'; DRAG.it.style.height = b.h + 'px';
  }
  function up() {
    if (!DRAG) return;
    var d = DRAG; DRAG = null; d.it.classList.remove('drag');
    if (!d.now) return;
    var l = list(), i = l.findIndex(function (o) { return o.id === d.id; }); if (i < 0) return;
    var resized = d.now.w !== l[i].w || d.now.h !== l[i].h;
    l[i] = Object.assign(l[i], { c: d.now.c, r: d.now.r, w: d.now.w, h: d.now.h }); save(l);
    if (resized) render();   // its inside is drawn for its new size
  }
  function click(e) {
    var t = e.target.closest && e.target.closest('[data-cw]'); if (!t) {
      if (EDIT) return;
      var a = e.target.closest && e.target.closest('[data-act="app"],[data-act="open"]');
      if (a && a.dataset.arg && window.TV && TV.open) { e.stopPropagation(); TV.open(a.dataset.arg); }
      return;
    }
    e.preventDefault(); e.stopPropagation();
    var k = t.dataset.cw;
    if (k === 'edit') return edit(!EDIT);
    if (k === 'add') return catShow(true);
    if (k === 'done') return edit(false);
    if (k === 'close') return catShow(false);
    if (k === 'pick') return addKind(t.dataset.k);
    if (k === 'del') { var it = t.closest('.dcwIt'); save(list().filter(function (o) { return o.id !== it.dataset.id; })); render(); return; }
    if (k === 'clear') { if (t.dataset.sure) { save([]); render(); t.textContent = '🗑 امسح الكل'; delete t.dataset.sure; } else { t.dataset.sure = '1'; t.textContent = 'متأكد؟ اضغط مرة ثانية'; } }
  }
  function edit(on) {
    EDIT = !!on; render();
    BAR.querySelector('[data-cw="edit"]').classList.toggle('on', EDIT);
    BAR.querySelector('[data-cw="done"]').hidden = !EDIT;
    BAR.querySelector('[data-cw="clear"]').hidden = !EDIT || !list().length;
  }

  // ---- the gallery: every widget, a live preview with demo data
  function catShow(on) {
    if (!on) { CAT.classList.remove('on'); return; }
    if (!CAT.dataset.built) {
      var body = CAT.querySelector('.dcwCatB');
      GROUPS.forEach(function (g) {
        body.appendChild(el('h3', '', g[0]));
        var row = el('div', 'dcwCatR');
        g[1].forEach(function (k) {
          var s = startOf(k), fit = Math.min(300 / (s[0] * CWID), 170 / (s[1] * CHEI), 1);
          var card = el('button', 'dcwCard'); card.dataset.cw = 'pick'; card.dataset.k = k;
          var pv = el('div', 'dcwPv cd-layer'), inner = el('div', 'dcwPvI');
          var b = { w: s[0] * CWID - GAP, h: s[1] * CHEI - GAP };
          inner.style.cssText = 'width:' + b.w + 'px;height:' + b.h + 'px;transform:scale(' + fit + ')';
          inner.appendChild(draw({ id: 'pv-' + k, kind: k }, b, true)); pv.appendChild(inner);
          var c = caps(k), st = c && c.status === 'setup' ? '<i>يحتاج إعداد</i>' : '';
          card.appendChild(pv); card.appendChild(el('b', '', AR[k] + st));
          row.appendChild(card);
        });
        body.appendChild(row);
      });
      CAT.dataset.built = '1';
    }
    CAT.classList.add('on');
  }

  function build() {
    if (L) return;
    if (window.CouchWidgets) window.CouchWidgets.config.base = 'cw/';   // its demo pictures sit beside it
    L = el('div', 'dcwLayer cd-layer'); L.id = 'cwLayer';
    BAR = el('div', 'dcwBar', '<button data-cw="edit" title="عدّل الويدجت">✏️</button><button data-cw="add" title="أضف ويدجت">➕</button>'
      + '<button data-cw="clear" hidden>🗑 امسح الكل</button><button data-cw="done" class="done" hidden>✓ تم</button>');
    CAT = el('div', 'dcwCat', '<div class="dcwCatH"><b>➕ أضف ويدجت</b><span>اضغط على أي ويدجت يضاف للشاشة — بعدها اسحبه لمكانه</span><button data-cw="close">إغلاق</button></div><div class="dcwCatB"></div>');
    var host = document.getElementById('day') ? document.getElementById('day').parentNode : document.body;
    host.appendChild(L); host.appendChild(BAR); host.appendChild(CAT);
    [L, BAR, CAT].forEach(function (x) { x.addEventListener('click', click, true); });
    addEventListener('pointermove', function (e) { if (e.pointerType !== 'mouse' || e.movementX || e.movementY) document.body.classList.add('cwPtr'); }, { passive: true });
    L.addEventListener('pointerdown', down, true); L.addEventListener('pointermove', move, true);
    L.addEventListener('pointerup', up, true); L.addEventListener('pointercancel', up, true);
  }

  window.DCW = {
    show: function (on) {
      ON = !!on; build();
      document.body.classList.toggle('cwOn', ON);
      document.body.classList.toggle('cwWeb', /^https?:/.test(location.protocol));   // ✏️ ➕ always on the web; on the TV once a mouse or a touch is used
      if (!ON) { if (EDIT) edit(false); catShow(false); return; }
      weatherLoad(); render();
    },
    refresh: function () { if (ON && !DRAG) render(); },
    editing: function () { return EDIT || (CAT && CAT.classList.contains('on')); },
    back: function () { if (CAT && CAT.classList.contains('on')) { catShow(false); return true; } if (EDIT) { edit(false); return true; } return false; },
    edit: edit, gallery: catShow, add: addKind, list: list
  };
})();
