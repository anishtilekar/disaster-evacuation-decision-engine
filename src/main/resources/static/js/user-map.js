// The evacuee-facing page: submit an origin (and optionally a destination you choose yourself),
// get routed by the same STRIDE engine an admin's batch dispatch uses, and watch only your own
// route on the map. Built on window.StrideCore (stride-core.js) for everything that is genuinely
// identical to the admin page -- the map, the API helper, the hazard overlay, the time slider.
(function () {
  'use strict';

  const Core = window.StrideCore;

  // ── State ──────────────────────────────────────────────────────────
  let pickMode = 'origin'; // 'origin' | 'destination'
  let originNodeIndex = null;
  let destinationNodeIndex = null;
  let myRequestPartyIds = new Set();

  Core.initMap();
  Core.wireSignOutButton();

  // ── Origin / destination picking ───────────────────────────────────
  function updateModeButtons() {
    const originBtn = document.getElementById('modeOriginBtn');
    const destBtn = document.getElementById('modeDestinationBtn');
    originBtn.className = 'btn ' + (pickMode === 'origin' ? 'btn-primary' : 'btn-outline');
    destBtn.className = 'btn ' + (pickMode === 'destination' ? 'btn-primary' : 'btn-outline');
  }

  function renderOriginTag() {
    const tag = document.getElementById('originTag');
    if (originNodeIndex === null) {
      tag.className = 'selection-tag empty';
      tag.textContent = 'click the map to set your origin';
      return;
    }
    const node = Core.getGraph().nodes[originNodeIndex];
    tag.className = 'selection-tag';
    tag.textContent = node.name + ' (node ' + originNodeIndex + ')';
  }

  function renderDestinationTag() {
    const tag = document.getElementById('destinationTag');
    if (destinationNodeIndex === null) {
      tag.className = 'selection-tag empty';
      tag.textContent = 'optional — not set (routes to nearest shelter)';
      return;
    }
    const node = Core.getGraph().nodes[destinationNodeIndex];
    tag.className = 'selection-tag';
    tag.textContent = node.name + ' (node ' + destinationNodeIndex + ')';
  }

  function renderSelectionMarkers() {
    const layers = Core.getLayers();
    const graph = Core.getGraph();
    layers.selectionLayer.clearLayers();
    if (originNodeIndex !== null) {
      const n = graph.nodes[originNodeIndex];
      L.circleMarker([n.lat, n.lon], {
        radius: 8, color: '#3366cc', fillColor: '#dce8fb', fillOpacity: 0.9, weight: 2
      }).addTo(layers.selectionLayer);
    }
    if (destinationNodeIndex !== null) {
      const n = graph.nodes[destinationNodeIndex];
      L.circleMarker([n.lat, n.lon], {
        radius: 8, color: '#7c3aed', fillColor: '#f5f3ff', fillOpacity: 0.9, weight: 2
      }).addTo(layers.selectionLayer);
    }
  }

  Core.getMap().on('click', (ev) => {
    if (!Core.getGraph()) return;
    const idx = Core.nearestNodeIndex(ev.latlng.lat, ev.latlng.lng);
    if (idx < 0) return;
    if (pickMode === 'origin') {
      originNodeIndex = idx;
      renderOriginTag();
    } else {
      destinationNodeIndex = idx;
      renderDestinationTag();
    }
    renderSelectionMarkers();
  });

  document.getElementById('modeOriginBtn').addEventListener('click', () => {
    pickMode = 'origin';
    updateModeButtons();
  });
  document.getElementById('modeDestinationBtn').addEventListener('click', () => {
    pickMode = 'destination';
    updateModeButtons();
  });
  document.getElementById('clearDestinationBtn').addEventListener('click', () => {
    destinationNodeIndex = null;
    renderDestinationTag();
    renderSelectionMarkers();
  });

  // ── Disaster / zone discovery ───────────────────────────────────────
  // Only an admin can create a disaster, so a submission form has to discover one rather than
  // create it on demand the way the old combined page's ensureDemoDisaster() did.
  async function loadDisasters() {
    const disasters = await Core.api('GET', '/api/disasters');
    const select = document.getElementById('disasterSelect');
    const hint = document.getElementById('noDisasterHint');
    const submitBtn = document.getElementById('submitRequestBtn');
    select.innerHTML = '';

    if (!disasters.length) {
      hint.style.display = 'block';
      submitBtn.disabled = true;
      document.getElementById('zoneSelect').innerHTML = '';
      return;
    }
    hint.style.display = 'none';
    submitBtn.disabled = false;

    for (const d of disasters) {
      const opt = document.createElement('option');
      opt.value = d.disasterId;
      opt.textContent = d.disasterName + ' (' + d.disasterType + ')';
      select.appendChild(opt);
    }
    await loadZones(disasters[0].disasterId);
  }

  async function loadZones(disasterId) {
    const zones = await Core.api('GET', '/api/disasters/' + disasterId + '/zones');
    const select = document.getElementById('zoneSelect');
    select.innerHTML = '';
    for (const z of zones) {
      const opt = document.createElement('option');
      opt.value = z.zoneId;
      opt.textContent = z.zoneName + ' (' + z.riskLevel + ')';
      select.appendChild(opt);
    }
  }

  document.getElementById('disasterSelect').addEventListener('change', (ev) => {
    loadZones(ev.target.value).catch(() => {});
  });

  // ── Submit + route ──────────────────────────────────────────────────
  document.getElementById('submitRequestBtn').addEventListener('click', async () => {
    if (originNodeIndex === null) { Core.toast('Click the map to set your origin first', true); return; }
    const zoneSelect = document.getElementById('zoneSelect');
    if (!zoneSelect.value) { Core.toast('No disaster zone available to report under', true); return; }

    const name = document.getElementById('requesterName').value.trim();
    const contact = document.getElementById('contactNumber').value.trim();
    if (!name || !contact) { Core.toast('Your name and contact number are required', true); return; }

    const graph = Core.getGraph();
    const payload = {
      disasterId: Number(document.getElementById('disasterSelect').value),
      disasterZoneId: Number(zoneSelect.value),
      sourceRoadNodeId: graph.nodes[originNodeIndex].dbNodeId,
      requesterName: name,
      contactNumber: contact,
      numberOfPeople: parseInt(document.getElementById('reqPeople').value, 10) || 1,
      priority: document.getElementById('reqPriority').value,
      medicalAssistanceRequired: document.getElementById('reqMedical').checked
    };
    if (destinationNodeIndex !== null) {
      payload.destinationRoadNodeId = graph.nodes[destinationNodeIndex].dbNodeId;
    }

    try {
      const created = await Core.api('POST', '/api/evacuation-requests', payload);
      Core.toast('Request submitted — routing…');
      await routeAndRefresh(created.evacuationRequestId);
    } catch (e) { /* toasted already */ }
  });

  async function routeAndRefresh(requestId) {
    let routed = false;
    try {
      await Core.api('POST', '/api/evacuation-requests/' + requestId + '/route');
      routed = true;
    } catch (e) { /* toasted already -- request may still be PENDING, which is fine */ }

    // loadMyRequests() always runs, win or lose, so the list reflects the current status either
    // way -- and it must run *before* refreshMyPlanFromCurrent: it is what puts this (possibly
    // brand-new) requestId into myRequestPartyIds, which the plan filter reads.
    await loadMyRequests();

    // Deliberately re-fetched from /api/dispatch/plan/current rather than rendered straight from
    // the /route response above. That response is scoped to only the one party just routed (planOne
    // plans a single party), so rendering it directly would wipe every other route this user already
    // had on the map -- setCurrentPlan clears the whole platoon layer on every call. Re-fetching the
    // full session and filtering to myRequestPartyIds is what keeps every one of this user's routes
    // visible together.
    await refreshMyPlanFromCurrent(true);

    if (routed) Core.toast('Routed');
  }

  // ── My requests ─────────────────────────────────────────────────────
  async function loadMyRequests() {
    const mine = await Core.api('GET', '/api/evacuation-requests/mine');
    myRequestPartyIds = new Set(mine.map(r => r.evacuationRequestId));
    renderMyRequestsList(mine);
  }

  function statusPillClass(status) {
    if (status === 'PENDING') return 'status-pending';
    if (status === 'ASSIGNED') return 'status-assigned';
    return 'status-other';
  }

  function renderMyRequestsList(mine) {
    const list = document.getElementById('myRequestsList');
    list.innerHTML = '';
    if (!mine.length) {
      list.innerHTML = '<div class="hint">You have not submitted a request yet.</div>';
      return;
    }
    for (const r of mine) {
      const div = document.createElement('div');
      div.className = 'list-item';
      div.innerHTML =
        '<span class="status-pill ' + statusPillClass(r.status) + '">' + r.status + '</span> ' +
        '<span class="title">' + r.numberOfPeople + ' people, ' + r.priority + '</span>' +
        '<div class="meta">requested ' + new Date(r.requestedAt).toLocaleString() + '</div>';
      if (r.status === 'PENDING') {
        const actions = document.createElement('div');
        actions.className = 'row-actions';
        const btn = document.createElement('button');
        btn.className = 'btn btn-outline';
        btn.textContent = 'Try routing again';
        btn.addEventListener('click', () => routeAndRefresh(r.evacuationRequestId));
        actions.appendChild(btn);
        div.appendChild(actions);
      }
      list.appendChild(div);
    }
  }

  async function refreshMyPlanFromCurrent(highlightDiff) {
    const current = await Core.api('GET', '/api/dispatch/plan/current');
    Core.setCurrentPlan(current, !!highlightDiff, myRequestPartyIds);
  }

  document.getElementById('refreshMyRequestsBtn').addEventListener('click', async () => {
    try {
      await loadMyRequests();
      await refreshMyPlanFromCurrent(true);
      Core.toast('Refreshed');
    } catch (e) { /* toasted already */ }
  });

  // ── Boot ─────────────────────────────────────────────────────────
  (async function boot() {
    try {
      await Core.loadGraph(); // no onEdgeClick -- a USER never selects a road
      Core.initTimeControls();
      updateModeButtons();
      renderOriginTag();
      renderDestinationTag();
      await Core.fetchHazardTimeline();
      await loadDisasters();
      await loadMyRequests();
      await refreshMyPlanFromCurrent();
    } catch (e) {
      Core.toast('Startup: ' + e.message, true);
    }
  })();
})();
