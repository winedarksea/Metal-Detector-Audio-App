/**
 * Service Worker for Metal Detector Audio PWA.
 *
 * Strategy (chosen so code fixes actually reach returning users):
 *   - Navigations + the app shell (index.html) → NETWORK-FIRST, falling back to the
 *     cached shell when offline. Ensures a new deploy is picked up on the next online load.
 *   - Stable-named mutable code (webApp.js, styles.css) → NETWORK-FIRST, cache fallback.
 *     These keep the same filename across deploys, so a cache-first strategy would pin
 *     users to stale code forever — the bug this version fixes.
 *   - Content-hashed / immutable assets (*.wasm, ort runtime, model, worklet, icons) →
 *     CACHE-FIRST, populated on first fetch. Their names change every deploy, so caching
 *     them indefinitely is safe and gives full offline support after the first online load.
 *   - Everything else → network only.
 *
 * Bump CACHE_NAME on any change to this file's caching behavior so `activate` purges
 * stale caches from previous versions.
 */
const CACHE_NAME = 'detector-audio-v5';

// Stable-named assets that change content without changing filename. These must be
// revalidated against the network so code/style fixes are delivered, not pinned.
const NETWORK_FIRST_PATHS = new Set([
  '/app/',
  '/app/index.html',
  '/app/webApp.js',
  '/app/styles.css',
]);

// Minimal precache: just the navigation shell so the app can boot offline.
// All hashed/runtime assets are added on first fetch (see the fetch handler).
const PRECACHE = [
  '/app/',
  '/app/index.html',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) =>
      // Tolerate individual failures so install always completes.
      Promise.allSettled(PRECACHE.map((url) => cache.add(url)))
    ).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

// Network-first: try the network, cache a successful copy, fall back to cache offline.
function networkFirst(request, cacheKey) {
  return fetch(request).then((response) => {
    if (response.ok && response.status === 200 && response.type === 'basic') {
      const clone = response.clone();
      caches.open(CACHE_NAME).then((cache) => cache.put(cacheKey || request, clone));
    }
    return response;
  }).catch(() => caches.match(cacheKey || request).then((cached) => cached || Response.error()));
}

// Cache-first: serve from cache, otherwise fetch and populate the cache.
function cacheFirst(request) {
  return caches.match(request).then((cached) => {
    if (cached) return cached;
    return fetch(request).then((response) => {
      if (response.ok && response.status === 200 && response.type === 'basic') {
        const clone = response.clone();
        caches.open(CACHE_NAME).then((cache) => cache.put(request, clone));
      }
      return response;
    }).catch(() => caches.match(request));
  });
}

self.addEventListener('fetch', (event) => {
  const { request } = event;

  // Only handle same-origin GETs (POST/range/etc. pass through untouched).
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  // SPA navigations → network-first, cached shell as offline fallback.
  if (request.mode === 'navigate') {
    event.respondWith(networkFirst(request, '/app/index.html'));
    return;
  }

  // Only manage assets under /app/.
  if (!url.pathname.startsWith('/app/')) return;

  // Never cache the service worker itself (the browser must always revalidate it).
  if (url.pathname === '/app/sw.js') return;

  // Stable-named mutable code/styles → network-first so fixes are delivered.
  if (NETWORK_FIRST_PATHS.has(url.pathname)) {
    event.respondWith(networkFirst(request));
    return;
  }

  // Content-hashed / immutable assets → cache-first.
  event.respondWith(cacheFirst(request));
});
