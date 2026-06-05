/**
 * Service Worker for Metal Detector Audio PWA.
 *
 * Strategy:
 *   - App shell (HTML, CSS, JS, WASM, model) → cache-first after first load
 *   - Navigation requests → cache-first, network fallback to index.html
 *   - Everything else → network-first
 */
const CACHE_NAME = 'detector-audio-v2';

const APP_SHELL = [
  '/app/',
  '/app/index.html',
  '/app/styles.css',
  '/app/webApp.js',
  '/app/manifest.webmanifest',
  '/app/ort.min.js',
  '/app/ort-wasm.wasm',
  '/app/ort-wasm-simd.wasm',
  '/app/starter_model_cnn.onnx',
  '/app/icons/icon.svg',
  '/app/icons/icon-512-maskable.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      // Pre-cache what we can; ignore individual failures so install always completes
      return Promise.allSettled(APP_SHELL.map((url) => cache.add(url)));
    }).then(() => self.skipWaiting())
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
  const url = new URL(request.url);

  // Only handle same-origin /app/ requests
  if (!url.pathname.startsWith('/app/')) return;

  if (request.mode === 'navigate') {
    event.respondWith(
      caches.match('/app/index.html').then((cached) =>
        cached || fetch(request)
      )
    );
    return;
  }

  // Cache-first for app shell assets
  const isShellAsset = APP_SHELL.some((path) => url.pathname === path || url.pathname.endsWith(path));
  if (isShellAsset) {
    event.respondWith(
      caches.match(request).then((cached) => {
        if (cached) return cached;
        return fetch(request).then((response) => {
          if (response.ok) {
            const clone = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(request, clone));
          }
          return response;
        });
      })
    );
    return;
  }

  // Network-first for everything else
  event.respondWith(
    fetch(request).catch(() => caches.match(request))
  );
});
