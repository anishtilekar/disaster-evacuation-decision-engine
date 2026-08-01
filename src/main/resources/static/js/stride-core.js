// Shared map/session plumbing for both user-map.html and admin-map.html. No build step, no
// bundler — a plain global namespace, loaded before each page's own <script>. Lifted verbatim from
// the original combined evacuation-map.html (Phase 6 / Part 2), split out so the two role-specific
// pages (Part 4) don't duplicate the parts that are genuinely identical between them: the Leaflet
// map + layers, the CSRF-aware API helper, graph/hazard-overlay rendering, and the time slider.
window.StrideCore = (function () {
  'use strict';

  // ── State ──────────────────────────────────────────────────────────
  let graph = null;                     // GraphMapResponse
  let currentPlan = null;               // InstructionSetResponse
  let previousWaypointKeys = new Map(); // platoonId -> serialized waypoints, for repair-diff flashing
  let edgeOnsetBySlot = new Map();
  let nodeOnsetByIndex = new Map();
  let sliderBucket = 0;
  let playTimer = null;

  const PRIORITY_COLOR = { LOW: '#5585d6', MEDIUM: '#3366cc', HIGH: '#f59e0b', CRITICAL: '#e84855' };
  const ROAD_CLEAR = '#8399b5';
  const ROAD_SOON = '#f59e0b';
  const ROAD_LETHAL = '#e84855';
  const REPAIR_FLASH_MS = 4500;
  const HAZARD_SOON_WINDOW_BUCKETS = 20;

  let map = null;
  let edgesLayer, nodeHazardLayer, sheltersLayer, platoonsLayer, selectionLayer;
  const edgePolylineBySlot = new Map();
  const platoonMarkers = new Map(); // platoonId -> { marker, waypoints }

  // ── Leaflet setup ─────────────────────────────────────────────────
  function initMap(center, zoom) {
    map = L.map('map', { zoomControl: true }).setView(center || [18.53, 73.84], zoom || 14);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; OpenStreetMap contributors', maxZoom: 19
    }).addTo(map);

    edgesLayer = L.layerGroup().addTo(map);
    nodeHazardLayer = L.layerGroup().addTo(map);
    sheltersLayer = L.layerGroup().addTo(map);
    platoonsLayer = L.layerGroup().addTo(map);
    selectionLayer = L.layerGroup().addTo(map);
    return map;
  }

  // ── Small helpers ─────────────────────────────────────────────────
  function toast(message, isError) {
    const el = document.getElementById('toast');
    if (!el) return;
    el.textContent = message;
    el.className = 'show' + (isError ? ' error' : '');
    clearTimeout(toast._t);
    toast._t = setTimeout(() => { el.className = ''; }, isError ? 5000 : 2600);
  }

  // Spring Security's CSRF cookie -- readable because SecurityConfig uses
  // CookieCsrfTokenRepository.withHttpOnlyFalse(). Only state-changing methods need it; a GET
  // is never checked, and sending the header on one is harmless but pointless.
  function csrfCookie() {
    const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
    return match ? decodeURIComponent(match[1]) : null;
  }

  async function api(method, path, body) {
    const headers = body ? { 'Content-Type': 'application/json' } : {};
    if (method !== 'GET') {
      const token = csrfCookie();
      if (token) headers['X-XSRF-TOKEN'] = token;
    }
    const res = await fetch(path, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined
    });
    let payload = null;
    try { payload = await res.json(); } catch (e) { /* no body */ }
    if (!res.ok || (payload && payload.success === false)) {
      const message = (payload && payload.message) || (res.status + ' ' + res.statusText);
      toast(message, true);
      throw new Error(message);
    }
    return payload ? payload.data : null;
  }

  function fmtClock(date) {
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  }

  // Jackson serializes LocalDateTime with up to nanosecond precision (e.g. "...T05:03:57.695633100"),
  // which Date's ISO parser is not reliably able to read across browsers — only millisecond precision
  // is guaranteed. The fractional part is truncated to 3 digits before parsing; losing sub-millisecond
  // precision is immaterial for a clock label.
  function parseServerDateTime(value) {
    return new Date(value.replace(/(\.\d{3})\d*$/, '$1'));
  }

  // LocalDateTime has no timezone, so it is read back on the server as wall-clock time. Date's own
  // toISOString() reports UTC, which would silently shift "now" by the local UTC offset -- this
  // instead formats the browser's own local wall clock, matching what LocalDateTime.now() on the
  // server means when both run in the same timezone.
  function toLocalIso(date) {
    const pad = (n) => String(n).padStart(2, '0');
    return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()) +
      'T' + pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds());
  }

  async function signOut() {
    try { await api('POST', '/logout'); } catch (e) { /* falling through to the redirect regardless */ }
    window.location.href = '/login.html?logout';
  }

  function wireSignOutButton() {
    const btn = document.getElementById('signOutBtn');
    if (btn) btn.addEventListener('click', signOut);
  }

  // ── Graph loading & rendering ─────────────────────────────────────
  // onEdgeClick(edge), if given, is wired onto every road polyline -- only the admin page uses it
  // (selecting a road to block), so the user page simply omits the argument.
  async function loadGraph(onEdgeClick) {
    graph = await api('GET', '/api/graph');

    edgesLayer.clearLayers();
    edgePolylineBySlot.clear();
    const bounds = [];

    for (const node of graph.nodes) bounds.push([node.lat, node.lon]);

    for (const edge of graph.edges) {
      const from = graph.nodes[edge.fromNodeIndex];
      const to = graph.nodes[edge.toNodeIndex];
      if (!from || !to) continue;
      const poly = L.polyline([[from.lat, from.lon], [to.lat, to.lon]], {
        color: ROAD_CLEAR, weight: 2.5, opacity: 0.6
      }).addTo(edgesLayer);
      if (onEdgeClick) {
        poly.on('click', (ev) => {
          L.DomEvent.stopPropagation(ev);
          onEdgeClick(edge);
        });
      }
      edgePolylineBySlot.set(edge.slot, { poly, edge });
    }

    sheltersLayer.clearLayers();
    for (const shelter of graph.shelters) {
      L.circleMarker([shelter.lat, shelter.lon], {
        radius: 7, color: '#0d9c6c', fillColor: '#10b981', fillOpacity: 0.9, weight: 2
      }).bindPopup(
        '<b>' + shelter.shelterName + '</b><br>capacity ' + shelter.availableCapacity +
        '<br>' + shelter.status + (shelter.medicalFacility ? '<br>medical facility' : '')
      ).addTo(sheltersLayer);
    }

    if (bounds.length) map.fitBounds(bounds, { padding: [24, 24] });
    return graph;
  }

  function nearestNodeIndex(lat, lon) {
    let best = -1, bestD = Infinity;
    for (const node of graph.nodes) {
      if (!node.active) continue;
      const dLat = node.lat - lat, dLon = node.lon - lon;
      const d = dLat * dLat + dLon * dLon;
      if (d < bestD) { bestD = d; best = node.nodeIndex; }
    }
    return best;
  }

  // ── Dispatch / plan rendering ──────────────────────────────────────
  function waypointKey(waypoints) {
    return waypoints.map(w => w.nodeIndex + '@' + w.bucket).join('|');
  }

  // partyIdFilter, if given (a Set<number>), restricts rendering to just those parties -- how the
  // user page shows only its own routes from a plan that may hold everyone's. Returns the filtered
  // committed list and the full shortfalls list so the caller can render its own stats/shortfall UI;
  // this function owns only the map layer, never a page's sidebar DOM.
  function setCurrentPlan(data, highlightDiff, partyIdFilter) {
    const previous = previousWaypointKeys;
    previousWaypointKeys = new Map();
    currentPlan = data;

    const slider = document.getElementById('slider');
    if (slider && data.horizonBuckets) slider.max = data.horizonBuckets;

    platoonsLayer.clearLayers();
    platoonMarkers.clear();

    const committed = partyIdFilter
      ? data.committed.filter(p => partyIdFilter.has(p.partyId))
      : data.committed;

    for (const p of committed) {
      const key = waypointKey(p.waypoints);
      previousWaypointKeys.set(p.platoonId, key);
      const changed = highlightDiff && previous.has(p.platoonId) && previous.get(p.platoonId) !== key;
      const isNew = highlightDiff && !previous.has(p.platoonId);
      const flash = changed || isNew;

      const latlngs = p.waypoints.map(w => [w.lat, w.lon]);
      const baseColor = PRIORITY_COLOR[p.priority] || PRIORITY_COLOR.MEDIUM;
      const poly = L.polyline(latlngs, {
        color: flash ? ROAD_LETHAL : baseColor,
        weight: flash ? 5 : 3,
        opacity: 0.85
      }).bindPopup(
        'Platoon ' + p.platoonId + ' (party ' + p.partyId + ')<br>' +
        p.size + ' people, ' + p.priority + '<br>&rarr; ' + (p.destinationName || 'chosen destination')
      ).addTo(platoonsLayer);

      if (flash) {
        setTimeout(() => poly.setStyle({ color: baseColor, weight: 3 }), REPAIR_FLASH_MS);
      }

      const marker = L.circleMarker(latlngs[0], {
        radius: 5, color: '#1a2d4f', fillColor: baseColor, fillOpacity: 1, weight: 1.5
      }).addTo(platoonsLayer);

      platoonMarkers.set(p.platoonId, { marker, waypoints: p.waypoints });
    }

    updatePlatoonPositions();
    return { committed, shortfalls: data.shortfalls };
  }

  function interpolate(waypoints, bucket) {
    if (bucket <= waypoints[0].bucket) return waypoints[0];
    const last = waypoints[waypoints.length - 1];
    if (bucket >= last.bucket) return last;
    for (let i = 0; i < waypoints.length - 1; i++) {
      const a = waypoints[i], b = waypoints[i + 1];
      if (bucket >= a.bucket && bucket <= b.bucket) {
        const span = b.bucket - a.bucket;
        const frac = span === 0 ? 0 : (bucket - a.bucket) / span;
        return { lat: a.lat + (b.lat - a.lat) * frac, lon: a.lon + (b.lon - a.lon) * frac };
      }
    }
    return last;
  }

  function updatePlatoonPositions() {
    for (const { marker, waypoints } of platoonMarkers.values()) {
      const pos = interpolate(waypoints, sliderBucket);
      marker.setLatLng([pos.lat, pos.lon]);
    }
  }

  // ── Hazard overlay ──────────────────────────────────────────────────
  function updateHazardOverlay() {
    for (const [slot, { poly }] of edgePolylineBySlot) {
      const onset = edgeOnsetBySlot.get(slot);
      if (onset === undefined) {
        poly.setStyle({ color: ROAD_CLEAR, dashArray: null, weight: 2.5 });
      } else if (sliderBucket >= onset) {
        poly.setStyle({ color: ROAD_LETHAL, dashArray: null, weight: 3.5 });
      } else if (onset - sliderBucket <= HAZARD_SOON_WINDOW_BUCKETS) {
        poly.setStyle({ color: ROAD_SOON, dashArray: '4 3', weight: 3 });
      } else {
        poly.setStyle({ color: ROAD_CLEAR, dashArray: null, weight: 2.5 });
      }
    }

    nodeHazardLayer.clearLayers();
    for (const [nodeIndex, onset] of nodeOnsetByIndex) {
      if (sliderBucket < onset - HAZARD_SOON_WINDOW_BUCKETS) continue;
      const node = graph.nodes[nodeIndex];
      if (!node) continue;
      const lethal = sliderBucket >= onset;
      L.circleMarker([node.lat, node.lon], {
        radius: 5, color: lethal ? ROAD_LETHAL : ROAD_SOON,
        fillColor: lethal ? ROAD_LETHAL : ROAD_SOON, fillOpacity: 0.8, weight: 1
      }).addTo(nodeHazardLayer);
    }
  }

  async function fetchHazardTimeline() {
    const timeline = await api('GET', '/api/hazards/timeline');
    edgeOnsetBySlot = new Map();
    nodeOnsetByIndex = new Map();
    if (timeline) {
      for (const e of timeline.edgeOnsets) edgeOnsetBySlot.set(e.slot, e.lethalFromBucket);
      for (const n of timeline.nodeOnsets) nodeOnsetByIndex.set(n.nodeIndex, n.lethalFromBucket);
    }
    updateHazardOverlay();
  }

  // ── Time slider / bucket clock / play button ───────────────────────
  function updateClockLabel() {
    const label = document.getElementById('clockLabel');
    if (!label) return;
    if (currentPlan && currentPlan.sessionActive && currentPlan.sessionEpoch) {
      const epoch = parseServerDateTime(currentPlan.sessionEpoch);
      const t = new Date(epoch.getTime() + sliderBucket * currentPlan.deltaSeconds * 1000);
      label.textContent = 'bucket ' + sliderBucket + '  ·  ' + fmtClock(t);
    } else {
      label.textContent = 'bucket ' + sliderBucket;
    }
  }

  function initTimeControls() {
    const slider = document.getElementById('slider');
    const playBtn = document.getElementById('playBtn');
    if (!slider || !playBtn) return;

    slider.addEventListener('input', (ev) => {
      sliderBucket = parseInt(ev.target.value, 10);
      updateClockLabel();
      updatePlatoonPositions();
      updateHazardOverlay();
    });

    playBtn.addEventListener('click', () => {
      if (playTimer) {
        clearInterval(playTimer);
        playTimer = null;
        playBtn.textContent = '▶';
        return;
      }
      playBtn.textContent = '❚❚';
      playTimer = setInterval(() => {
        const max = parseInt(slider.max, 10);
        sliderBucket = Math.min(sliderBucket + 1, max);
        slider.value = sliderBucket;
        updateClockLabel();
        updatePlatoonPositions();
        updateHazardOverlay();
        if (sliderBucket >= max) { clearInterval(playTimer); playTimer = null; playBtn.textContent = '▶'; }
      }, 160);
    });
  }

  return {
    initMap, toast, api, fmtClock, parseServerDateTime, toLocalIso, signOut, wireSignOutButton,
    loadGraph, nearestNodeIndex, setCurrentPlan, interpolate, updatePlatoonPositions,
    updateHazardOverlay, fetchHazardTimeline, updateClockLabel, initTimeControls,
    getGraph: () => graph,
    getCurrentPlan: () => currentPlan,
    getMap: () => map,
    getLayers: () => ({ edgesLayer, nodeHazardLayer, sheltersLayer, platoonsLayer, selectionLayer }),
    getEdgePolylineBySlot: () => edgePolylineBySlot,
    getSliderBucket: () => sliderBucket,
    PRIORITY_COLOR, ROAD_CLEAR, ROAD_SOON, ROAD_LETHAL
  };
})();
