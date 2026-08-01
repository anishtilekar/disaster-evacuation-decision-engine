// The operator console: seed demo demand, run dispatch/LNS, report hazards, block roads, and
// manage shelters -- everything that changes the world an evacuee is routed through. Built on
// window.StrideCore (stride-core.js) for the map, the API helper, the hazard overlay, and the time
// slider, which are identical to the user page.
(function () {
  'use strict';

  const Core = window.StrideCore;

  // ── State ──────────────────────────────────────────────────────────
  let demoDisasterId = null;
  let demoZoneId = null;
  let selectedEdge = null;       // {slot, edgeDbId, distanceKm}
  let mapMode = 'none';          // 'none' | 'hazard' | 'shelter'
  let hazardOrigin = null;       // {lat, lon}
  let shelterOrigin = null;      // {lat, lon}

  Core.initMap();
  Core.wireSignOutButton();

  function randomContactNumber() {
    let n = '9';
    for (let i = 0; i < 9; i++) n += Math.floor(Math.random() * 10);
    return n;
  }

  // ── Road selection (for blocking) ──────────────────────────────────
  function selectEdge(edge) {
    selectedEdge = edge;
    document.getElementById('edgeTag').className = 'selection-tag';
    document.getElementById('edgeTag').textContent =
      'slot ' + edge.slot + ' (road #' + edge.edgeDbId + '), ' + edge.distanceKm.toFixed(2) + ' km';
    const bySlot = Core.getEdgePolylineBySlot();
    for (const { poly } of bySlot.values()) poly.setStyle({ dashArray: null });
    const entry = bySlot.get(edge.slot);
    if (entry) { entry.poly.setStyle({ color: '#7c3aed', weight: 4, dashArray: '6 4' }); entry.poly.bringToFront(); }
  }

  // ── Map click: hazard-origin or shelter-location placement only ────
  // (edge selection is wired directly onto each polyline by loadGraph)
  Core.getMap().on('click', (ev) => {
    if (!Core.getGraph()) return;
    const layers = Core.getLayers();

    if (mapMode === 'hazard') {
      hazardOrigin = { lat: ev.latlng.lat, lon: ev.latlng.lng };
      mapMode = 'none';
      document.getElementById('placeHazardBtn').textContent = 'Place hazard origin on map';
      document.getElementById('hazardTag').className = 'selection-tag';
      document.getElementById('hazardTag').textContent =
        hazardOrigin.lat.toFixed(5) + ', ' + hazardOrigin.lon.toFixed(5);
      layers.selectionLayer.clearLayers();
      L.circleMarker([hazardOrigin.lat, hazardOrigin.lon], {
        radius: 6, color: '#e84855', fillColor: '#e84855', fillOpacity: 0.9
      }).addTo(layers.selectionLayer);
      return;
    }

    if (mapMode === 'shelter') {
      shelterOrigin = { lat: ev.latlng.lat, lon: ev.latlng.lng };
      mapMode = 'none';
      document.getElementById('placeShelterBtn').textContent = 'Place shelter location on map';
      document.getElementById('shelterCoordTag').className = 'selection-tag';
      document.getElementById('shelterCoordTag').textContent =
        shelterOrigin.lat.toFixed(5) + ', ' + shelterOrigin.lon.toFixed(5);
      layers.selectionLayer.clearLayers();
      L.circleMarker([shelterOrigin.lat, shelterOrigin.lon], {
        radius: 6, color: '#10b981', fillColor: '#10b981', fillOpacity: 0.9
      }).addTo(layers.selectionLayer);
      return;
    }
  });

  // ── Demo disaster bootstrap ────────────────────────────────────────
  async function ensureDemoDisaster() {
    if (demoDisasterId && demoZoneId) return;
    const center = Core.getMap().getCenter();
    const disaster = await Core.api('POST', '/api/disasters', {
      disasterName: 'STRIDE Demo Disaster',
      disasterType: 'FLOOD',
      severityLevel: 'HIGH',
      affectedRegion: 'Demo ward',
      latitude: center.lat,
      longitude: center.lng,
      impactRadius: 1.0
    });
    demoDisasterId = disaster.disasterId;
    const zone = await Core.api('POST', '/api/disasters/zones', {
      disasterId: demoDisasterId,
      zoneName: 'Demo Zone',
      riskLevel: 'HIGH',
      latitude: center.lat,
      longitude: center.lng,
      population: 5000,
      evacuationRequired: true
    });
    demoZoneId = zone.zoneId;
    document.getElementById('disasterTag').className = 'selection-tag';
    document.getElementById('disasterTag').textContent =
      'disaster #' + demoDisasterId + ' / zone #' + demoZoneId;
  }

  document.getElementById('seedDisasterBtn').addEventListener('click', async () => {
    try { await ensureDemoDisaster(); Core.toast('Demo disaster ready'); } catch (e) { /* toasted */ }
  });

  document.getElementById('reloadGraphBtn').addEventListener('click', async () => {
    try { await Core.loadGraph(selectEdge); Core.toast('Graph reloaded'); } catch (e) { /* toasted */ }
  });

  // ── Random demand seeding ───────────────────────────────────────────
  async function refreshPendingCount() {
    const pending = await Core.api('GET', '/api/evacuation-requests?status=PENDING');
    document.getElementById('statPending').textContent = pending.length;
  }

  document.getElementById('addRandomBtn').addEventListener('click', async () => {
    const graph = Core.getGraph();
    const n = parseInt(document.getElementById('randomCount').value, 10) || 1;
    const activeNodes = graph.nodes.filter(nd => nd.active);
    if (!activeNodes.length) { Core.toast('Graph has no active nodes', true); return; }
    try {
      await ensureDemoDisaster();
      const priorities = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
      const jobs = [];
      for (let i = 0; i < n; i++) {
        const node = activeNodes[Math.floor(Math.random() * activeNodes.length)];
        const people = 1 + Math.floor(Math.random() * 12);
        const priority = priorities[Math.floor(Math.random() * priorities.length)];
        jobs.push(Core.api('POST', '/api/evacuation-requests', {
          disasterId: demoDisasterId,
          disasterZoneId: demoZoneId,
          sourceRoadNodeId: node.dbNodeId,
          requesterName: 'Demo Party',
          contactNumber: randomContactNumber(),
          numberOfPeople: people,
          priority,
          medicalAssistanceRequired: Math.random() < 0.1
        }));
      }
      await Promise.all(jobs);
      Core.toast(n + ' random requests added');
      await refreshPendingCount();
    } catch (e) { /* toasted */ }
  });

  // ── Dispatch / plan rendering ──────────────────────────────────────
  function renderShortfalls(shortfalls) {
    const list = document.getElementById('shortfallList');
    list.innerHTML = '';
    for (const s of shortfalls) {
      const div = document.createElement('div');
      div.className = 'shortfall-item';
      div.textContent = 'Party ' + s.partyId + ': ' + s.unroutedSize + ' people unrouted (wave ' + s.failedAtWaveIndex + ')';
      list.appendChild(div);
    }
  }

  function applyPlan(data, highlightDiff) {
    const { committed, shortfalls } = Core.setCurrentPlan(data, highlightDiff, null);
    document.getElementById('statSession').textContent = data.sessionActive ? 'active' : 'inactive';
    document.getElementById('statPlatoons').textContent = committed.length;
    document.getElementById('statPeople').textContent = committed.reduce((sum, p) => sum + p.size, 0);
    renderShortfalls(shortfalls);
  }

  async function refreshCurrentPlan(highlightDiff) {
    const data = await Core.api('GET', '/api/dispatch/plan/current');
    applyPlan(data, highlightDiff);
  }

  document.getElementById('planBtn').addEventListener('click', async () => {
    try {
      const data = await Core.api('POST', '/api/dispatch/plan');
      applyPlan(data, false);
      await refreshPendingCount();
      Core.toast('Plan run: ' + data.committed.length + ' platoons committed');
    } catch (e) { /* toasted */ }
  });

  document.getElementById('improveBtn').addEventListener('click', async () => {
    const iterations = parseInt(document.getElementById('lnsIterations').value, 10) || 1;
    try {
      const result = await Core.api('POST', '/api/dispatch/improve?maxIterations=' + iterations);
      Core.toast('LNS: ' + result.accepted + '/' + result.iterationsRun + ' iterations accepted');
      await refreshCurrentPlan(true);
    } catch (e) { /* toasted */ }
  });

  // ── Hazard reporting ────────────────────────────────────────────────
  document.getElementById('placeHazardBtn').addEventListener('click', () => {
    mapMode = 'hazard';
    document.getElementById('placeHazardBtn').textContent = 'Click the map…';
  });

  document.getElementById('postHazardBtn').addEventListener('click', async () => {
    if (!hazardOrigin) { Core.toast('Place the hazard origin on the map first', true); return; }
    try {
      await Core.api('POST', '/api/hazards', {
        description: 'Reported from the admin console',
        hazardType: document.getElementById('hazardType').value,
        origin: { latitude: hazardOrigin.lat, longitude: hazardOrigin.lon },
        initialRadiusMeters: parseFloat(document.getElementById('hazardRadius').value) || 0,
        growthRateMetersPerMinute: parseFloat(document.getElementById('hazardGrowth').value) || 1,
        eventStartTime: Core.toLocalIso(new Date())
      });
      Core.toast('Hazard reported — timeline recompiled, affected platoons repaired');
      await Core.fetchHazardTimeline();
      await refreshCurrentPlan(true);
      await loadActiveHazards();
    } catch (e) { /* toasted */ }
  });

  // A front recedes or gets contained in real life -- it doesn't just keep growing. This list is
  // what makes that representable: resolving a hazard is a capacity-restoring event (mirrors
  // unblocking a road below), so it never strands anyone and never needs a repair pass -- only
  // plans made from here on see the cleared area. The improvement pass is still a separate,
  // deliberate action, so a toast nudges toward it rather than auto-running it.
  async function loadActiveHazards() {
    const hazards = await Core.api('GET', '/api/hazards');
    const list = document.getElementById('activeHazardsList');
    list.innerHTML = '';
    if (!hazards.length) { list.innerHTML = '<div class="hint">None active.</div>'; return; }
    for (const h of hazards) {
      const div = document.createElement('div');
      div.className = 'list-item';
      div.innerHTML =
        '<span class="title">' + h.hazardType + '</span>' +
        '<div class="meta">radius ' + h.initialRadiusMeters + 'm, growing ' +
        h.growthRateMetersPerMinute + ' m/min since ' + new Date(h.eventStartTime).toLocaleTimeString() + '</div>';
      const actions = document.createElement('div');
      actions.className = 'row-actions';
      const resolveBtn = document.createElement('button');
      resolveBtn.className = 'btn btn-outline';
      resolveBtn.textContent = 'Mark resolved';
      resolveBtn.addEventListener('click', async () => {
        try {
          await Core.api('PATCH', '/api/hazards/' + h.hazardEventId + '/resolve');
          Core.toast('Hazard resolved — try "Run improvement pass" to see routes get shorter');
          await Core.fetchHazardTimeline();
          await refreshCurrentPlan(true);
          await loadActiveHazards();
        } catch (e) { /* toasted */ }
      });
      actions.appendChild(resolveBtn);
      div.appendChild(actions);
      list.appendChild(div);
    }
  }

  // ── Block a road ────────────────────────────────────────────────────
  document.getElementById('blockRoadBtn').addEventListener('click', async () => {
    if (!selectedEdge) { Core.toast('Click a road on the map first', true); return; }
    try {
      await ensureDemoDisaster();
      await Core.api('POST', '/api/graph/blocked-roads', {
        disasterId: demoDisasterId,
        roadEdgeId: selectedEdge.edgeDbId,
        blockageReason: document.getElementById('blockReason').value,
        active: true
      });
      Core.toast('Road blocked — timeline recompiled, affected platoons repaired');
      await Core.fetchHazardTimeline();
      await refreshCurrentPlan(true);
      await loadActiveBlockedRoads();
    } catch (e) { /* toasted */ }
  });

  // Debris gets cleared, a restriction lifts -- capacity comes back, same reasoning as hazard
  // resolution above (and the same reason GraphAdminService.unblockRoad never calls repair).
  async function loadActiveBlockedRoads() {
    const blocks = await Core.api('GET', '/api/graph/blocked-roads');
    const list = document.getElementById('activeBlockedRoadsList');
    list.innerHTML = '';
    if (!blocks.length) { list.innerHTML = '<div class="hint">None active.</div>'; return; }
    for (const b of blocks) {
      const div = document.createElement('div');
      div.className = 'list-item';
      div.innerHTML =
        '<span class="title">' + (b.roadEdgeName || ('road #' + b.roadEdgeId)) + '</span>' +
        '<div class="meta">' + b.blockageReason + ' since ' + new Date(b.blockedAt).toLocaleTimeString() + '</div>';
      const actions = document.createElement('div');
      actions.className = 'row-actions';
      const unblockBtn = document.createElement('button');
      unblockBtn.className = 'btn btn-outline';
      unblockBtn.textContent = 'Unblock';
      unblockBtn.addEventListener('click', async () => {
        try {
          await Core.api('PATCH', '/api/graph/blocked-roads/' + b.blockedRoadId + '/unblock');
          Core.toast('Road unblocked — try "Run improvement pass" to see routes get shorter');
          await Core.fetchHazardTimeline();
          await refreshCurrentPlan(true);
          await loadActiveBlockedRoads();
        } catch (e) { /* toasted */ }
      });
      actions.appendChild(unblockBtn);
      div.appendChild(actions);
      list.appendChild(div);
    }
  }

  // ── Shelters ────────────────────────────────────────────────────────
  document.getElementById('placeShelterBtn').addEventListener('click', () => {
    mapMode = 'shelter';
    document.getElementById('placeShelterBtn').textContent = 'Click the map…';
  });

  async function loadShelters() {
    const shelters = await Core.api('GET', '/api/shelters');
    const list = document.getElementById('shelterList');
    list.innerHTML = '';
    if (!shelters.length) { list.innerHTML = '<div class="hint">No shelters yet.</div>'; return; }
    for (const s of shelters) {
      const div = document.createElement('div');
      div.className = 'list-item';
      div.innerHTML =
        '<span class="title">' + s.shelterName + '</span>' +
        '<div class="meta">' + s.shelterStatus + ' &middot; capacity ' + s.capacity +
        ' (available ' + s.availableCapacity + ')</div>';

      const actions = document.createElement('div');
      actions.className = 'row-actions';

      const toggleBtn = document.createElement('button');
      toggleBtn.className = 'btn btn-outline';
      toggleBtn.textContent = s.shelterStatus === 'AVAILABLE' ? 'Close' : 'Reopen';
      toggleBtn.addEventListener('click', async () => {
        try {
          await Core.api('PATCH', '/api/shelters/' + s.shelterId, {
            shelterStatus: s.shelterStatus === 'AVAILABLE' ? 'TEMPORARILY_CLOSED' : 'AVAILABLE'
          });
          Core.toast('Shelter updated');
          await loadShelters();
          await Core.loadGraph(selectEdge);
        } catch (e) { /* toasted */ }
      });
      actions.appendChild(toggleBtn);
      div.appendChild(actions);
      list.appendChild(div);
    }
  }

  document.getElementById('addShelterBtn').addEventListener('click', async () => {
    if (!shelterOrigin) { Core.toast('Place the shelter location on the map first', true); return; }
    const name = document.getElementById('shelterName').value.trim();
    const contact = document.getElementById('shelterContact').value.trim();
    const capacity = parseInt(document.getElementById('shelterCapacity').value, 10) || 0;
    if (!name || !contact || capacity <= 0) {
      Core.toast('Name, contact number, and a positive capacity are required', true);
      return;
    }
    try {
      await Core.api('POST', '/api/shelters', {
        shelterName: name,
        location: document.getElementById('shelterLocation').value.trim() || name,
        coordinate: { latitude: shelterOrigin.lat, longitude: shelterOrigin.lon },
        capacity,
        medicalFacility: document.getElementById('shelterMedical').checked,
        foodSupply: document.getElementById('shelterFood').checked,
        waterSupply: document.getElementById('shelterWater').checked,
        contactNumber: contact
      });
      Core.toast('Shelter added — graph reloaded');
      shelterOrigin = null;
      document.getElementById('shelterCoordTag').className = 'selection-tag empty';
      document.getElementById('shelterCoordTag').textContent = 'not set';
      document.getElementById('shelterName').value = '';
      document.getElementById('shelterLocation').value = '';
      document.getElementById('shelterContact').value = '';
      await loadShelters();
      await Core.loadGraph(selectEdge);
    } catch (e) { /* toasted */ }
  });

  // ── All requests ────────────────────────────────────────────────────
  function statusPillClass(status) {
    if (status === 'PENDING') return 'status-pending';
    if (status === 'ASSIGNED') return 'status-assigned';
    return 'status-other';
  }

  async function loadAllRequests() {
    const status = document.getElementById('requestStatusFilter').value;
    const requests = await Core.api('GET', '/api/evacuation-requests?status=' + status);
    const list = document.getElementById('allRequestsList');
    list.innerHTML = '';
    if (!requests.length) { list.innerHTML = '<div class="hint">None.</div>'; return; }
    for (const r of requests) {
      const div = document.createElement('div');
      div.className = 'list-item';
      div.innerHTML =
        '<span class="status-pill ' + statusPillClass(r.status) + '">' + r.status + '</span> ' +
        '<span class="title">' + r.requesterName + '</span>' +
        '<div class="meta">' + r.numberOfPeople + ' people, ' + r.priority + ' &middot; zone ' + r.zoneName + '</div>';
      list.appendChild(div);
    }
  }

  document.getElementById('loadAllRequestsBtn').addEventListener('click', () => {
    loadAllRequests().catch(() => { /* toasted */ });
  });

  // ── Boot ─────────────────────────────────────────────────────────
  (async function boot() {
    try {
      await Core.loadGraph(selectEdge);
      Core.initTimeControls();
      await refreshPendingCount();
      await Core.fetchHazardTimeline();
      await refreshCurrentPlan(false);
      await loadShelters();
      await loadAllRequests();
      await loadActiveHazards();
      await loadActiveBlockedRoads();
    } catch (e) {
      Core.toast('Startup: ' + e.message, true);
    }
  })();
})();
