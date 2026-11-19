// LiteCast service worker: network-first (always gets the newest version when online),
// falls back to the cached copy when offline so recording still works without internet.
const CACHE='litecast-v1';
const CORE=['./','./index.html','./manifest.webmanifest','./icon-192.png','./icon-512.png'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(CACHE).then(c=>c.addAll(CORE)).then(()=>self.skipWaiting()))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{
  const req=e.request;
  if(req.method!=='GET')return;
  const url=new URL(req.url);
  const same=url.origin===self.location.origin;
  const peerjsLib=url.href.startsWith('https://unpkg.com/peerjs');
  if(!same&&!peerjsLib)return;
  e.respondWith(fetch(req).then(res=>{
    if(res.ok){const copy=res.clone();const key=same&&req.mode==='navigate'?'./index.html':req;caches.open(CACHE).then(c=>c.put(key,copy))}
    return res;
  }).catch(()=>caches.match(same&&req.mode==='navigate'?'./index.html':req).then(r=>r||caches.match('./'))));
});
