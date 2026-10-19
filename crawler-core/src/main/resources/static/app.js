// Shared client-side helpers for all pages.
//
// Time formatting: each <time data-fmt="..."> element decides its own format.
// - data-fmt="relative" — "5m ago" / "3h ago" / "2d ago" / "May 20" for older.
// - data-fmt="date"     — "Jun 1, 2026". Used for future calendar dates.
// - no attribute        — "10:34:21 PM · May 26" full datetime (when seconds matter).
//
// Sparklines: any <svg class="sparkline" data-series="[[epochMs,count],...]"> gets a path.
//
// Navigation: tab/link clicks push a history entry and a popstate handler
// re-renders on Back/Forward, so the browser back button returns to the previous
// page (real SPA history). Sort changes and form-POST results replace in place so
// they do not clutter history.
(function () {
  var now = new Date();
  var currentYear = now.getFullYear();

  var dateShort = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' });
  var dateLong  = new Intl.DateTimeFormat(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
  var timeOfDay = new Intl.DateTimeFormat(undefined, {
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true,
  });

  function shortOrLongDate(d) {
    return d.getFullYear() === currentYear ? dateShort.format(d) : dateLong.format(d);
  }
  function formatRelative(d) {
    var diff = now - d;
    if (diff < 0) return dateLong.format(d);
    var sec = Math.floor(diff / 1000);
    if (sec < 60) return sec + 's ago';
    var min = Math.floor(sec / 60);
    if (min < 60) return min + 'm ago';
    var hr = Math.floor(min / 60);
    if (hr < 24) return hr + 'h ago';
    var days = Math.floor(hr / 24);
    if (days < 7) return days + 'd ago';
    return shortOrLongDate(d);
  }
  function formatDateOnly(d) { return dateLong.format(d); }
  function formatDateTime(d) { return timeOfDay.format(d) + ' · ' + shortOrLongDate(d); }

  var SVGNS = 'http://www.w3.org/2000/svg';
  function svgEl(name, attrs) {
    var el = document.createElementNS(SVGNS, name);
    for (var k in attrs) el.setAttribute(k, attrs[k]);
    return el;
  }
  function parseSeries(svg) {
    try {
      var arr = JSON.parse(svg.getAttribute('data-series') || '[]');
      return arr.filter(function (p) {
        return Array.isArray(p) && p.length === 2 && !isNaN(p[0]) && !isNaN(p[1]);
      });
    } catch (e) { return []; }
  }

  // Scroll position to restore after a <main> swap (set by sort-header clicks).
  var pendingScrollLeft = null;

  // Dialog refs — live inside <main>, re-bound in initPage() after every swap.
  var dialog, dTitle, dChart, dSummary;

  function renderDetail(series, title) {
    dTitle.textContent = title || 'Inquiries';
    dChart.innerHTML = '';

    var W = 640, H = 280, mL = 44, mR = 16, mT = 16, mB = 34;
    var pW = W - mL - mR, pH = H - mT - mB;
    var values = series.map(function (p) { return p[1]; });
    var times  = series.map(function (p) { return p[0]; });
    var vMin = Math.min.apply(null, values), vMax = Math.max.apply(null, values);
    var vRange = vMax - vMin || 1;
    var t0 = times[0], tN = times[times.length - 1], tRange = tN - t0 || 1;

    function sx(t) { return mL + ((t - t0) / tRange) * pW; }
    function sy(v) { return vMin === vMax ? mT + pH / 2 : mT + (1 - (v - vMin) / vRange) * pH; }

    var svg = svgEl('svg', { viewBox: '0 0 ' + W + ' ' + H, role: 'img' });

    var yticks = vMin === vMax ? [vMin] : [vMin, Math.round((vMin + vMax) / 2), vMax];
    yticks.forEach(function (v) {
      var y = sy(v);
      svg.appendChild(svgEl('line', { class: 'grid', x1: mL, y1: y, x2: mL + pW, y2: y }));
      var t = svgEl('text', { class: 'label', x: mL - 6, y: y + 3, 'text-anchor': 'end' });
      t.textContent = v;
      svg.appendChild(t);
    });
    svg.appendChild(svgEl('line', { class: 'axis', x1: mL, y1: mT, x2: mL, y2: mT + pH }));
    svg.appendChild(svgEl('line', { class: 'axis', x1: mL, y1: mT + pH, x2: mL + pW, y2: mT + pH }));

    var pts = series.map(function (p) { return { x: sx(p[0]), y: sy(p[1]) }; });
    var yTop = mT, yBot = mT + pH;
    function clampY(y) { return Math.max(yTop, Math.min(yBot, y)); }
    var d = 'M' + pts[0].x.toFixed(1) + ',' + pts[0].y.toFixed(1);
    if (pts.length === 2) {
      d += ' L' + pts[1].x.toFixed(1) + ',' + pts[1].y.toFixed(1);
    } else {
      for (var i = 0; i < pts.length - 1; i++) {
        var p0 = pts[i - 1] || pts[i], p1 = pts[i], p2 = pts[i + 1], p3 = pts[i + 2] || p2;
        var cp1x = p1.x + (p2.x - p0.x) / 6, cp1y = clampY(p1.y + (p2.y - p0.y) / 6);
        var cp2x = p2.x - (p3.x - p1.x) / 6, cp2y = clampY(p2.y - (p3.y - p1.y) / 6);
        d += ' C' + cp1x.toFixed(1) + ',' + cp1y.toFixed(1) +
             ' ' + cp2x.toFixed(1) + ',' + cp2y.toFixed(1) +
             ' ' + p2.x.toFixed(1) + ',' + p2.y.toFixed(1);
      }
    }
    svg.appendChild(svgEl('path', { class: 'series', d: d }));

    var tip = document.createElement('div');
    tip.className = 'chart-tip';
    tip.style.display = 'none';
    series.forEach(function (p) {
      var cx = sx(p[0]), cy = sy(p[1]);
      var marker = svgEl('circle', { class: 'marker', cx: cx.toFixed(1), cy: cy.toFixed(1), r: 3 });
      svg.appendChild(marker);
      var hit = svgEl('circle', { class: 'hit', cx: cx.toFixed(1), cy: cy.toFixed(1), r: 10 });
      hit.addEventListener('mouseenter', function () {
        marker.setAttribute('r', 5);
        tip.textContent = p[1] + ' inquiries · ' + formatDateTime(new Date(p[0]));
        tip.style.display = 'block';
        var hr = hit.getBoundingClientRect(), pr = dChart.getBoundingClientRect();
        tip.style.left = (hr.left + hr.width / 2 - pr.left) + 'px';
        tip.style.top  = (hr.top  + hr.height / 2 - pr.top)  + 'px';
      });
      hit.addEventListener('mouseleave', function () {
        marker.setAttribute('r', 3);
        tip.style.display = 'none';
      });
      svg.appendChild(hit);
    });

    var xl = svgEl('text', { class: 'label', x: mL,      y: H - 8, 'text-anchor': 'start' });
    xl.textContent = shortOrLongDate(new Date(t0));
    svg.appendChild(xl);
    var xr = svgEl('text', { class: 'label', x: mL + pW, y: H - 8, 'text-anchor': 'end' });
    xr.textContent = shortOrLongDate(new Date(tN));
    svg.appendChild(xr);

    dChart.appendChild(svg);
    dChart.appendChild(tip);

    var delta = values[values.length - 1] - values[0];
    dSummary.textContent =
      'Latest: ' + values[values.length - 1] +
      ' · Change: ' + (delta >= 0 ? '+' : '') + delta +
      ' · ' + series.length + ' data points · ' +
      formatDateTime(new Date(t0)) + ' → ' + formatDateTime(new Date(tN));
  }

  // ── Shared navigation helpers ────────────────────────────────────────────

  function updateNav(path) {
    var pathName = path.split('?')[0];
    document.querySelectorAll('.app-nav a').forEach(function (a) {
      if (a.getAttribute('href') === pathName) a.setAttribute('aria-current', 'page');
      else a.removeAttribute('aria-current');
    });
  }

  // Reconcile page-specific <head> styles on navigation. swapMain() only replaces
  // <main>, but pages carry their own <style> in <head> (page-scoped tweaks; shared
  // CSS is the app.css <link>, left alone). Without this, navigating via a swap
  // keeps the previous page's <head>, so the incoming page renders with the wrong
  // CSS — e.g. a listing row that wraps correctly on a direct load overflows the
  // phone after a swap. Styles from the initial load are tagged on startup so they
  // are replaced like any other page's.
  function syncHeadStyles(doc) {
    document.head.querySelectorAll('style[data-page-style]').forEach(function (s) { s.remove(); });
    doc.head.querySelectorAll('style').forEach(function (s) {
      var clone = document.importNode(s, true);
      clone.setAttribute('data-page-style', '1');
      document.head.appendChild(clone);
    });
  }

  // Swap the current <main> with the one parsed from html, update URL and title.
  // historyMode: 'push' adds a history entry (Back returns here), 'none' leaves
  // history untouched (for popstate — the browser already moved), anything else
  // replaces in place. Returns true on success, false if either <main> was missing.
  function swapMain(html, path, historyMode) {
    var doc = new DOMParser().parseFromString(html, 'text/html');
    var newMain = doc.querySelector('main');
    var curMain = document.querySelector('main');
    if (!newMain || !curMain) return false;
    syncHeadStyles(doc); // bring the incoming page's <head> styles before it renders
    curMain.replaceWith(newMain);
    if (historyMode === 'push') history.pushState({ spa: true }, '', path);
    else if (historyMode !== 'none') history.replaceState({ spa: true }, '', path);
    document.title = doc.title;
    updateNav(path);
    if (pendingScrollLeft !== null) {
      var sc = document.querySelector('div[style*="overflow-x"]');
      if (sc) sc.scrollLeft = pendingScrollLeft;
      pendingScrollLeft = null;
    }
    try { initPage(); } catch (e) { console.error('initPage:', e); }
    return true;
  }

  // Thin top progress bar so a tab tap gives instant feedback even when the server render is slow.
  function startProgress() {
    var bar = document.getElementById('nav-progress');
    if (!bar) return;
    bar.classList.remove('done');
    void bar.offsetWidth; // restart the CSS transition
    bar.classList.add('active');
  }
  function doneProgress() {
    var bar = document.getElementById('nav-progress');
    if (!bar) return;
    bar.classList.remove('active');
    bar.classList.add('done');
  }

  // ── Background prefetch + short-lived page cache ──────────────────────────
  // Load what the user is about to need in the background instead of blocking on
  // the click. Hovering (desktop) or touching (mobile) an in-app link quietly
  // fetches its page and keeps the HTML for a few seconds; the real click then
  // swaps it in instantly. These are live dashboards, so a prefetch is only
  // trusted briefly — after a swap the entry is dropped so the next visit
  // re-reads fresh data.
  var PREFETCH_TTL = 15000;   // ms a prefetched page stays "fresh"
  var pageCache = {};         // url -> { at: epochMs, p: Promise<string|null> }

  function prefetchable(url) {
    if (!url || url.charAt(0) !== '/') return false;              // same-origin app pages only
    if (/\/(login|logout)(\/|\?|$)/.test(url)) return false;      // auth flips identity — never cache
    if (/\/(file|download|export)(\/|\?|$)/.test(url)) return false; // binary endpoints
    if (/\.[a-z0-9]{2,5}(\?|$)/i.test(url)) return false;         // has a file extension (asset/download)
    return true;
  }

  // Promise<string|null> for a page's HTML, reusing an in-flight or recent
  // prefetch of the same URL (so a hover already underway is not re-fetched on
  // click). Asks for a <main>-only fragment via X-Fragment; a server that does
  // not understand the header just returns the whole document, which swapMain()
  // handles the same way.
  function loadPage(url) {
    var hit = pageCache[url];
    if (hit && Date.now() - hit.at < PREFETCH_TTL) return hit.p;
    var p = fetch(url, { credentials: 'same-origin', headers: { 'X-Fragment': '1' } })
      .then(function (r) { return r.ok ? r.text() : null; })
      .catch(function () { return null; });
    pageCache[url] = { at: Date.now(), p: p };
    return p;
  }

  function prefetch(url) { if (prefetchable(url)) loadPage(url); }

  // Fetch (or reuse a prefetch of) a page and swap <main>. Full navigation on error.
  // opts: { push } adds a history entry, { scrollTop } jumps to the top, { fromPop }
  // means we are responding to Back/Forward (leave history and scroll to the browser).
  function navigate(url, opts) {
    opts = opts || {};
    startProgress();
    loadPage(url).then(function (html) {
      if (html === null) { window.location.href = url; return; }
      doneProgress();
      var mode = opts.fromPop ? 'none' : (opts.push ? 'push' : 'replace');
      if (!swapMain(html, url, mode)) { window.location.href = url; return; }
      if (opts.scrollTop && !opts.fromPop) window.scrollTo(0, 0);
      delete pageCache[url];   // one-shot: the next visit re-reads live data
    });
  }

  // Client-side listing sort — instant, no page reload. Reorders the cards in place.
  function setupListingSort() {
    var bar = document.getElementById('listing-sort');
    var list = document.querySelector('.listing-list');
    if (!bar || !list) return;
    var buttons = Array.prototype.slice.call(bar.querySelectorAll('button[data-sort]'));
    if (!buttons.length) return;

    function attr(card, key) {
      var v = card.getAttribute('data-' + key);
      return v == null ? '' : v;
    }
    function apply(key, dir) {
      var numeric = key === 'price' || key === 'area' || key === 'match' || key === 'applied';
      var cards = Array.prototype.slice.call(list.querySelectorAll('.listing-card'));
      cards.sort(function (a, b) {
        var primary;
        if (numeric) {
          primary = (parseFloat(attr(a, key)) || 0) - (parseFloat(attr(b, key)) || 0);
        } else {
          var x = attr(a, key), y = attr(b, key);
          primary = x < y ? -1 : (x > y ? 1 : 0);
        }
        primary = dir === 'asc' ? primary : -primary;
        if (primary !== 0) return primary;
        // Tiebreak: newest first, regardless of direction.
        var as = attr(a, 'seen'), bs = attr(b, 'seen');
        return as < bs ? 1 : (as > bs ? -1 : 0);
      });
      cards.forEach(function (c) { list.appendChild(c); });
      buttons.forEach(function (btn) { btn.classList.toggle('on', btn.getAttribute('data-sort') === key); });
    }

    var current = { key: 'match', dir: 'desc' };
    buttons.forEach(function (btn) {
      btn.addEventListener('click', function () {
        var key = btn.getAttribute('data-sort');
        if (current.key === key) {
          current.dir = current.dir === 'asc' ? 'desc' : 'asc';
        } else {
          current.key = key;
          current.dir = btn.getAttribute('data-dir') || 'desc';
        }
        apply(current.key, current.dir);
      });
    });
    apply(current.key, current.dir); // default: match first
  }

  // Build + flash a toast from script — for errors raised in fetch handlers, where there is no
  // server-rendered .toast element for initPage to activate. Mirrors that activation + auto-dismiss.
  function showToast(message, kind) {
    var toast = document.createElement('div');
    toast.className = 'toast' + (kind ? ' ' + kind : '');
    toast.textContent = message;
    document.body.appendChild(toast);
    requestAnimationFrame(function () { toast.classList.add('show'); });
    var ms = kind === 'error' ? 3000 : 2000;
    setTimeout(function () {
      toast.classList.remove('show');
      setTimeout(function () { if (toast.parentNode) toast.remove(); }, 300);
    }, ms);
  }

  // ── Per-page initialisation (runs after every navigation) ────────────────

  function initPage() {
    // Refresh the clock on every render. `now`/`currentYear` are captured once when the IIFE runs
    // (page load), but these dashboards are meant to stay open (the version badge polls every 60s),
    // and initPage re-runs on every SPA swap. Without this, formatRelative keeps subtracting from a
    // stale `now`, so a row "seen 3h ago" reads "just now" after any swap — understating age by the
    // tab's uptime, which is misleading on dashboards where freshness drives the decision to apply.
    now = new Date();
    currentYear = now.getFullYear();

    // Flash toast: slide up, then auto-dismiss (2s success / 3s error via data-timeout). Runs here so
    // it fires both on a full load and after a form POST is swapped in via swapMain().
    var toast = document.querySelector('.toast:not([data-activated])');
    if (toast) {
      toast.setAttribute('data-activated', '1');
      requestAnimationFrame(function () { toast.classList.add('show'); });
      var ms = parseInt(toast.getAttribute('data-timeout'), 10) || 2000;
      setTimeout(function () {
        toast.classList.remove('show');
        setTimeout(function () { if (toast.parentNode) toast.remove(); }, 300);
      }, ms);
    }

    setupListingSort();

    // Documents page: show the Month field only for dated types (payslip / bank statement). Lives here
    // (not an inline <script>) so it works after SPA navigation — swapMain() doesn't run inline scripts.
    var docType = document.getElementById('docType');
    var monthField = document.getElementById('monthField');
    var monthInput = document.getElementById('documentMonth');
    if (docType && monthField && monthInput) {
      var DATED = ['salarySlip', 'bankStatement'];
      var toggleMonth = function () {
        var show = DATED.indexOf(docType.value) !== -1;
        monthField.style.display = show ? '' : 'none';
        monthInput.required = show;
        if (!show) monthInput.value = '';
      };
      docType.addEventListener('change', toggleMonth);
      toggleMonth();
    }

    document.querySelectorAll('time[datetime]').forEach(function (el) {
      var raw = el.getAttribute('datetime');
      var d = new Date(raw);
      if (isNaN(d.getTime())) return;
      var mode = el.getAttribute('data-fmt') || 'datetime';
      var text;
      if (mode === 'relative') text = formatRelative(d);
      else if (mode === 'date') text = formatDateOnly(d);
      else text = formatDateTime(d);
      el.textContent = text;
      if (!el.getAttribute('title')) el.setAttribute('title', raw);
    });

    // Applied-toggle checkboxes (rebo pipeline, vesteda listings): POST the new state and reflect
    // it on the row. Merged from crawler-core's original app.js during the frontend unification;
    // a no-op on pages (roofz, ikwilhuren cards) that have no .applied-toggle inputs.
    document.querySelectorAll('.applied-toggle').forEach(function (cb) {
      cb.addEventListener('change', function (e) {
        var id = e.target.dataset.listingId;
        var applied = e.target.checked;
        var row = e.target.closest('tr, .listing-card');
        fetch('/listings/' + encodeURIComponent(id) + '/applied?applied=' + applied, {
          method: 'POST',
        })
          .then(function (r) {
            if (!r.ok) throw new Error(r.status);
            if (row) row.classList.toggle('applied', applied);
          })
          .catch(function (err) {
            e.target.checked = !applied;
            showToast('Failed to save: ' + err.message, 'error');
          });
      });
    });

    // Re-bind dialog refs (dialog lives inside <main> and is replaced on navigation).
    dialog   = document.getElementById('inquiry-detail');
    dTitle   = document.getElementById('inquiry-detail-title');
    dChart   = document.getElementById('inquiry-detail-chart');
    dSummary = document.getElementById('inquiry-detail-summary');
    var dClose = document.getElementById('inquiry-detail-close');
    if (dClose && dialog) dClose.addEventListener('click', function () { dialog.close(); });
    if (dialog) {
      dialog.addEventListener('click', function (e) { if (e.target === dialog) dialog.close(); });
    }

    document.querySelectorAll('svg.sparkline').forEach(function (svg) {
      var series = parseSeries(svg);
      if (series.length < 2) return;
      var values = series.map(function (p) { return p[1]; });
      var w = 60, h = 16;
      var min = Math.min.apply(null, values), max = Math.max.apply(null, values);
      var range = max - min || 1;
      var path = values.map(function (v, i) {
        var x = (i / (values.length - 1)) * w;
        var y = h - ((v - min) / range) * h;
        return (i === 0 ? 'M' : 'L') + x.toFixed(1) + ',' + y.toFixed(1);
      }).join(' ');
      svg.setAttribute('viewBox', '0 0 ' + w + ' ' + h);
      var p = document.createElementNS(SVGNS, 'path');
      p.setAttribute('d', path);
      svg.appendChild(p);

      if (!dialog) return;
      var title = svg.getAttribute('data-title') || 'Inquiries';
      function open() { renderDetail(series, title); dialog.showModal(); }
      svg.classList.add('sparkline-clickable');
      svg.setAttribute('role', 'button');
      svg.setAttribute('tabindex', '0');
      svg.setAttribute('aria-label', 'Show inquiry detail for ' + title);
      svg.addEventListener('click', open);
      svg.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); }
      });
    });

    // Intercept sort-header link clicks so sorting stays within the SPA.
    document.querySelectorAll('thead a[href]').forEach(function (link) {
      link.addEventListener('click', function (e) {
        var href = link.getAttribute('href');
        if (!href || href.charAt(0) !== '/') return;
        e.preventDefault();
        var sc = link.closest('div[style*="overflow-x"]') ||
                 document.querySelector('div[style*="overflow-x"]');
        pendingScrollLeft = sc ? sc.scrollLeft : null;
        navigate(href);
      });
    });

    // Intercept form POSTs so they use replaceState instead of creating new
    // history entries.
    //
    // Signing in and out are excluded, and must stay excluded. Both change who you are: Spring
    // rotates the session id and the CSRF token on authentication, and replaying that through
    // fetch + swapMain leaves the page holding stale credentials in a <main> that was rendered for
    // somebody else. The symptom is a sign-in that never works on the first attempt — you submit,
    // nothing appears to happen, you submit again and you're in. Authentication is a real
    // navigation; let the browser do it.
    document.querySelectorAll('form[method="post"]').forEach(function (form) {
      var formAction = form.getAttribute('action') || '';
      if (formAction.indexOf('/logout') !== -1 || formAction.indexOf('/login') !== -1) return;
      // File-upload forms MUST submit natively: URLSearchParams(new FormData(form)) re-encodes the
      // body as application/x-www-form-urlencoded, which silently drops the file. Let the browser
      // send the real multipart/form-data request instead of intercepting it.
      if ((form.getAttribute('enctype') || '').indexOf('multipart') !== -1) return;

      form.addEventListener('submit', function (e) {
        // If onsubmit="return confirm()" was cancelled, defaultPrevented is true.
        if (e.defaultPrevented) return;
        e.preventDefault();

        startProgress();
        fetch(form.action, {
          method: 'POST',
          body: new URLSearchParams(new FormData(form)),
          credentials: 'same-origin',
          redirect: 'follow',
        })
          .then(function (r) {
            var path = new URL(r.url).pathname;
            return r.text().then(function (html) { return { html: html, path: path }; });
          })
          .then(function (result) {
            doneProgress();
            // swapMain returns false when the response has no <main> (shouldn't
            // happen for our pages, but fall back to a replacing navigation).
            if (!swapMain(result.html, result.path)) {
              window.location.replace(result.path);
            }
          })
          .catch(function () { window.location.reload(); });
      });
    });
  }

  // ── One-time startup ─────────────────────────────────────────────────────

  // Nav link clicks push a history entry (so Back returns to the previous page)
  // and jump to the top of the new page. The header is never swapped, so these
  // listeners are added only once.
  document.querySelectorAll('.app-nav a').forEach(function (link) {
    link.addEventListener('click', function (e) {
      var href = link.getAttribute('href');
      if (!href || href.charAt(0) !== '/') return;
      if (window.location.pathname === href) { e.preventDefault(); return; }
      e.preventDefault();
      updateNav(href); // highlight the tapped tab immediately, before the fetch returns
      navigate(href, { push: true, scrollTop: true });
    });
  });

  // Back/Forward: re-render the page for whatever URL the browser restored. The
  // browser has already moved the history position, so only the content is swapped.
  window.addEventListener('popstate', function () {
    navigate(location.pathname + location.search, { fromPop: true });
  });

  // Warm the cache on the earliest hint of intent — hover (desktop), touch
  // (mobile), or keyboard focus — so the page is already loaded when the click
  // lands. Delegated on document, so it covers nav tabs and any in-app link,
  // now and after a swap. prefetchable() keeps auth flows and downloads out, and
  // loadPage() de-dupes, so this is cheap: at most one request per URL per few
  // seconds regardless of how much the pointer moves.
  function onPrefetchIntent(e) {
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (!a) return;
    var href = a.getAttribute('href');
    if (!prefetchable(href)) return;
    if (href === location.pathname + location.search) return;
    prefetch(href);
  }
  document.addEventListener('mouseover', onPrefetchIntent);
  document.addEventListener('touchstart', onPrefetchIntent, { passive: true });
  document.addEventListener('focusin', onPrefetchIntent);

  // Tag the styles that came with the initial page as page-specific, so the first
  // navigation away replaces them like any other page's (see syncHeadStyles).
  document.head.querySelectorAll('style').forEach(function (s) {
    s.setAttribute('data-page-style', '1');
  });

  // Top progress bar element (created once; the header is never swapped).
  if (!document.getElementById('nav-progress')) {
    var np = document.createElement('div');
    np.id = 'nav-progress';
    document.body.appendChild(np);
  }

  // Build-version badge (bottom-left; styled in app.css as .osmosis-ver).
  // BAKED is substituted at build time (Gradle ReplaceTokens) and rides in this
  // cacheable file, so a stale in-memory tab keeps showing its OLD number. The
  // running service reports its live version at /version (uncached); when they
  // differ the tab is a stale cached build, so the badge goes red (green = the
  // tab is on the latest). Re-checked periodically to catch a deploy that lands
  // while the tab is open. Inert (pointer-events:none) — purely informational.
  (function versionBadge() {
    if (document.getElementById('osmosis-ver')) return;
    var BAKED = '@OSMOSIS_VERSION@';
    var el = document.createElement('div');
    el.id = 'osmosis-ver';
    el.className = 'osmosis-ver';
    el.textContent = BAKED;
    el.title = 'Version ' + BAKED;
    document.body.appendChild(el);
    function check() {
      fetch('/version', { cache: 'no-store' })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (d) {
          if (!d || !d.version) return;
          var stale = d.version !== BAKED;
          el.classList.toggle('stale', stale);
          el.title = stale
            ? 'Running ' + d.version + '; this tab is showing cached ' + BAKED + '. Reload to update.'
            : 'Version ' + BAKED + ' (latest)';
        })
        .catch(function () {});
    }
    check();
    setInterval(check, 60000);
  })();

  initPage();
})();
