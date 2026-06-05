/**
 * Service Worker for Metal Detector Audio PWA.
 *
 * Strategy:
 *   - Navigation requests → serve cached app shell (index.html), fall back to network.
 *   - Same-origin /app/ assets → cache-first, then cache-on-fetch. Because the Compose
 *     build emits content-hashed wasm filenames that change every deploy, we cannot
 *     hardcode them; instead every successful GET is cached as it is fetched. This makes
 *     the app (app wasm, skiko wasm, ONNX runtime, model, worklet) fully available offline
 *     after the first online load, with no filename coupling.
 *   - Everything else → network only.
 */
const CACHE_NAME = 'detector-audio-v3';

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

self.addEventListener('fetch', (event) => {
  const { request } = event;

  // Only handle same-origin GETs (POST/range/etc. pass through untouched).
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  // SPA navigations → cached shell, network fallback.
  if (request.mode === 'navigate') {
    event.respondWith(
      caches.match('/app/index.html').then((cached) => cached || fetch(request))
    );
    return;
  }

  // Only manage assets under /app/.
  if (!url.pathname.startsWith('/app/')) return;

  // Never cache the service worker itself (must always revalidate).
  if (url.pathname === '/app/sw.js') return;

  // Cache-first, then populate the cache on a successful network fetch.
  event.respondWith(
    caches.match(request).then((cached) => {
      if (cached) return cached;
      return fetch(request).then((response) => {
        // Cache complete, same-origin, OK responses (skip opaque/partial).
        if (response.ok && response.status === 200 && response.type === 'basic') {
          const clone = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(request, clone));
        }
        return response;
      }).catch(() => caches.match(request));
    })
  );
});
