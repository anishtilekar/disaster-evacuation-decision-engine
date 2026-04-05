/**
 * ============================================================
 * DISASTER EVACUATION DECISION ENGINE — app.js
 * Shared API layer, rendering utilities, UI helpers
 * Spring Boot backend at http://localhost:8080
 * ============================================================
 */

const API = '';  // same origin — Spring Boot serves static files

/* ══════════════════════════════════════
   CORE FETCH WRAPPER
══════════════════════════════════════ */
async function apiFetch(path, opts = {}) {
  const res = await fetch(API + path, {
    headers: { 'Content-Type': 'application/json', ...(opts.headers || {}) },
    ...opts,
  });
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try { const body = await res.json(); msg = body.message || body.error || JSON.stringify(body); } catch {}
    throw new Error(msg);
  }
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

/* ══════════════════════════════════════
   CLOCK
══════════════════════════════════════ */
function startClock() {
  const el = document.getElementById('clock');
  if (!el) return;
  const tick = () => {
    const d = new Date();
    el.textContent = d.toUTCString().replace(' GMT', ' UTC');
  };
  tick();
  setInterval(tick, 1000);
}

/* ══════════════════════════════════════
   TOAST
══════════════════════════════════════ */
function toast(title, sub, type = 'info', ms = 4200) {
  let zone = document.querySelector('.toast-zone');
  if (!zone) { zone = document.createElement('div'); zone.className = 'toast-zone'; document.body.appendChild(zone); }

  const icons = { success: '✅', error: '🚨', info: 'ℹ️' };
  const el = document.createElement('div');
  el.className = `toast ${type}`;
  el.innerHTML = `
    <span class="toast-ico">${icons[type]}</span>
    <div class="toast-body"><strong>${title}</strong><span>${sub || ''}</span></div>
    <button class="toast-x" onclick="dismissToast(this.parentElement)">✕</button>`;
  zone.appendChild(el);

  setTimeout(() => dismissToast(el), ms);
}
function dismissToast(el) {
  if (!el || el._removing) return;
  el._removing = true;
  el.classList.add('out');
  el.addEventListener('animationend', () => el.remove());
}

/* ══════════════════════════════════════
   TABLE RENDERER
  cols: [{ key, label, render? }]
══════════════════════════════════════ */
function renderTable(containerId, rows, cols) {
  const el = document.getElementById(containerId);
  if (!el) return;
  if (!rows || rows.length === 0) {
    el.innerHTML = `<div class="sv"><div class="sv-ico">📭</div><div class="sv-title">No records found</div><div class="sv-sub">The server returned an empty dataset</div></div>`;
    return;
  }
  const heads = cols.map(c => `<th>${c.label}</th>`).join('');
  const body  = rows.map(row =>
    `<tr>${cols.map(c => {
      const raw = row[c.key] !== undefined && row[c.key] !== null ? row[c.key] : null;
      return `<td>${c.render ? c.render(raw, row) : (raw !== null ? raw : '<span class="td-mono" style="opacity:.4">—</span>')}</td>`;
    }).join('')}</tr>`
  ).join('');
  el.innerHTML = `<div class="table-scroll"><table><thead><tr>${heads}</tr></thead><tbody>${body}</tbody></table></div>`;
}

function setLoading(id) {
  const el = document.getElementById(id);
  if (el) el.innerHTML = `<div class="sv"><div class="spinner"></div><div class="sv-title">Loading…</div><div class="sv-sub">Connecting to server</div></div>`;
}
function setError(id, msg) {
  const el = document.getElementById(id);
  if (el) el.innerHTML = `<div class="sv"><div class="sv-ico">⚠️</div><div class="sv-title">Could not load data</div><div class="sv-sub">${msg}</div></div>`;
}
function setCount(id, n) {
  const el = document.getElementById(id);
  if (el) el.innerHTML = `Records: <strong>${n}</strong>`;
}

/* ══════════════════════════════════════
   BADGE HELPERS
══════════════════════════════════════ */
function severityBadge(v) {
  if (v === null || v === undefined) return dash();
  const s = String(v).toLowerCase();
  if (['critical','extreme','high','5','4'].some(x => s.includes(x))) return `<span class="badge b-red">🔴 ${v}</span>`;
  if (['medium','moderate','3'].some(x => s.includes(x)))              return `<span class="badge b-amber">🟠 ${v}</span>`;
  if (['low','minor','1','2'].some(x => s.includes(x)))                return `<span class="badge b-green">🟢 ${v}</span>`;
  return `<span class="badge b-blue">${v}</span>`;
}
function riskBadge(v) {
  if (v === null || v === undefined) return dash();
  const s = String(v).toLowerCase();
  if (s.includes('high') || s.includes('extreme') || s.includes('critical')) return `<span class="badge b-red">${v}</span>`;
  if (s.includes('medium') || s.includes('moderate'))                         return `<span class="badge b-amber">${v}</span>`;
  if (s.includes('low'))                                                       return `<span class="badge b-green">${v}</span>`;
  return `<span class="badge b-gray">${v}</span>`;
}
function statusBadge(v) {
  if (v === null || v === undefined) return dash();
  const s = String(v).toLowerCase();
  if (['pending'].some(x => s.includes(x)))              return `<span class="badge b-amber">${v}</span>`;
  if (['approved','active','open','completed'].some(x => s.includes(x))) return `<span class="badge b-green">${v}</span>`;
  if (['rejected','cancelled','closed'].some(x => s.includes(x)))        return `<span class="badge b-red">${v}</span>`;
  if (['processing','assigned'].some(x => s.includes(x)))                return `<span class="badge b-blue">${v}</span>`;
  return `<span class="badge b-gray">${v}</span>`;
}
function boolBadge(v) {
  if (v === true  || v === 'true'  || v === 1)  return `<span class="badge b-green">✓ Yes</span>`;
  if (v === false || v === 'false' || v === 0)  return `<span class="badge b-gray">✗ No</span>`;
  return dash();
}
function occBar(cur, cap) {
  if (cur == null || !cap) return cur != null ? cur : '—';
  const pct  = Math.min(100, Math.round((cur / cap) * 100));
  const color = pct >= 90 ? 'var(--coral)' : pct >= 65 ? 'var(--amber)' : 'var(--green)';
  return `<div class="occ-bar">
    <div class="occ-meta"><span>${Number(cur).toLocaleString()}&nbsp;/&nbsp;${Number(cap).toLocaleString()}</span><span>${pct}%</span></div>
    <div class="occ-track"><div class="occ-fill" style="width:${pct}%;background:${color};"></div></div>
  </div>`;
}
function fmtDate(v) {
  if (!v) return '—';
  try { return new Date(v).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' }); }
  catch { return v; }
}
function fmtNum(v)  { return v != null ? Number(v).toLocaleString() : '—'; }
function dash()     { return `<span class="td-mono" style="opacity:.35">—</span>`; }

/* ══════════════════════════════════════
   API: DISASTERS
══════════════════════════════════════ */
async function fetchDisasters(tableId = 'tbl-disasters', countId = 'cnt-disasters') {
  setLoading(tableId);
  try {
    const data = await apiFetch('/api/disasters');
    const cols = [
      { key: 'disasterId',   label: 'ID',          render: v => `<span class="td-id">#${v}</span>` },
      { key: 'disasterName', label: 'Name',         render: v => `<span class="td-name">${v}</span>` },
      { key: 'disasterType', label: 'Type',         render: v => v ? `<span class="badge b-blue">${v}</span>` : dash() },
      { key: 'severityLevel',label: 'Severity',     render: v => severityBadge(v) },
      { key: 'affectedRegion',label: 'Region',      render: v => v ? `<span class="td-mono">${v}</span>` : dash() },
      { key: 'description',  label: 'Description',  render: v => v ? `<span style="max-width:180px;display:inline-block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">${v}</span>` : dash() },
    ];
    renderTable(tableId, data, cols);
    setCount(countId, data.length);
    return data;
  } catch (err) {
    setError(tableId, err.message);
    toast('Failed to load disasters', err.message, 'error');
    return [];
  }
}

async function postDisaster(payload) {
  return apiFetch('/api/disasters', { method: 'POST', body: JSON.stringify(payload) });
}

/* ══════════════════════════════════════
   API: ZONES
══════════════════════════════════════ */
async function fetchZones(tableId = 'tbl-zones', countId = 'cnt-zones') {
  setLoading(tableId);
  try {
    const data = await apiFetch('/api/zones');
    const cols = [
      { key: 'zoneId',    label: 'ID',         render: v => `<span class="td-id">#${v}</span>` },
      { key: 'zoneName',  label: 'Zone Name',  render: v => `<span class="td-name">${v}</span>` },
      { key: 'riskLevel', label: 'Risk Level', render: v => riskBadge(v) },
      { key: 'population',label: 'Population', render: v => `<span class="td-mono">${fmtNum(v)}</span>` },
      { key: 'latitude',  label: 'Lat',        render: v => v != null ? `<span class="td-mono">${v}</span>` : dash() },
      { key: 'longitude', label: 'Lng',        render: v => v != null ? `<span class="td-mono">${v}</span>` : dash() },
    ];
    renderTable(tableId, data, cols);
    setCount(countId, data.length);
    return data;
  } catch (err) {
    setError(tableId, err.message);
    toast('Failed to load zones', err.message, 'error');
    return [];
  }
}

async function postZone(payload) {
  return apiFetch('/api/zones', { method: 'POST', body: JSON.stringify(payload) });
}

/* ══════════════════════════════════════
   API: SHELTERS  (read-only)
══════════════════════════════════════ */
async function fetchShelters(tableId = 'tbl-shelters', countId = 'cnt-shelters') {
  setLoading(tableId);
  try {
    const data = await apiFetch('/api/shelters');
    const cols = [
      { key: 'shelterId',        label: 'ID',         render: v => `<span class="td-id">#${v}</span>` },
      { key: 'shelterName',      label: 'Shelter',    render: v => `<span class="td-name">${v}</span>` },
      { key: 'location',         label: 'Location',   render: v => v ? `<span class="td-mono">${v}</span>` : dash() },
      { key: 'capacity',         label: 'Capacity',   render: v => `<span class="td-mono">${fmtNum(v)}</span>` },
      { key: 'currentOccupancy', label: 'Occupancy',  render: (v, row) => occBar(v, row.capacity) },
      { key: 'medicalFacility',  label: 'Medical',    render: v => boolBadge(v) },
      { key: 'status',           label: 'Status',     render: v => statusBadge(v) },
    ];
    renderTable(tableId, data, cols);
    setCount(countId, data.length);
    return data;
  } catch (err) {
    setError(tableId, err.message);
    toast('Failed to load shelters', err.message, 'error');
    return [];
  }
}

/* ══════════════════════════════════════
   API: EVACUATION REQUESTS
══════════════════════════════════════ */
async function fetchRequests(tableId = 'tbl-requests', countId = 'cnt-requests') {
  setLoading(tableId);
  try {
    const data = await apiFetch('/api/requests');
    const cols = [
      { key: 'requestId',            label: 'ID',        render: v => `<span class="td-id">#${v}</span>` },
      { key: 'user',                 label: 'User ID',   render: (v) => v?.userId ? `<span class="td-mono">#${v.userId}</span>` : dash() },
      { key: 'zone',                 label: 'Zone ID',   render: (v) => v?.zoneId ? `<span class="td-mono">#${v.zoneId}</span>` : dash() },
      { key: 'assignedShelter',      label: 'Shelter',   render: (v) => v?.shelterId ? `<span class="td-mono">#${v.shelterId}</span>` : dash() },
      { key: 'numberOfPeople',       label: 'People',    render: v => `<span class="td-mono">${fmtNum(v)}</span>` },
      { key: 'medicalAssistanceNeeded', label: 'Medical',    render: v => boolBadge(v) },
      { key: 'transportationNeeded', label: 'Transport', render: v => boolBadge(v) },
      { key: 'requestStatus',        label: 'Status',    render: v => statusBadge(v) },
    ];
    renderTable(tableId, data, cols);
    setCount(countId, data.length);
    return data;
  } catch (err) {
    setError(tableId, err.message);
    toast('Failed to load requests', err.message, 'error');
    return [];
  }
}

/**
 * Submit evacuation request.
 * Sends EXACTLY the structure the Spring Boot controller expects.
 */
async function submitEvacuationRequest({ userId, zoneId, shelterId, numberOfPeople, medicalAssistanceNeeded, transportationNeeded }) {
  const payload = {
    user:              { userId: Number(userId) },
    zone:              { zoneId: Number(zoneId) },
    assignedShelter:   { shelterId: Number(shelterId) },
    requestStatus:     'PENDING',
    numberOfPeople:    Number(numberOfPeople),
    medicalAssistanceNeeded: Boolean(medicalAssistanceNeeded),
    transportationNeeded:    Boolean(transportationNeeded),
  };
  return apiFetch('/api/requests', { method: 'POST', body: JSON.stringify(payload) });
}

/* ══════════════════════════════════════
   DASHBOARD STATS
══════════════════════════════════════ */
async function loadDashboardStats() {
  const jobs = [
    { id: 'stat-disasters', id2: 'nc-disasters', url: '/api/disasters' },
    { id: 'stat-zones',     id2: 'nc-zones',     url: '/api/zones'     },
    { id: 'stat-shelters',  id2: 'nc-shelters',  url: '/api/shelters'  },
    { id: 'stat-requests',  id2: 'nc-requests',  url: '/api/requests'  },
  ];
  jobs.forEach(async ({ id, id2, url }) => {
    const elStat = document.getElementById(id);
    const elCard = document.getElementById(id2);
    try {
      const data = await apiFetch(url);
      const n = Array.isArray(data) ? data.length : '?';
      if (elStat) { elStat.textContent = n; elStat.classList.remove('loading'); }
      if (elCard) elCard.textContent = n;
    } catch {
      if (elStat) { elStat.textContent = 'Err'; }
    }
  });
}

/* ══════════════════════════════════════
   SIDEBAR ACTIVE STATE + MOBILE TOGGLE
══════════════════════════════════════ */
function initNav() {
  const cur = location.pathname.split('/').pop() || 'index.html';
  document.querySelectorAll('.nav-link[data-page]').forEach(a => {
    if (a.dataset.page === cur) a.classList.add('active');
  });

  const sidebar = document.getElementById('sidebar');
  const ham     = document.getElementById('ham');
  const ov      = document.getElementById('mob-ov');

  const open  = () => { sidebar?.classList.add('open'); ov?.classList.add('show'); };
  const close = () => { sidebar?.classList.remove('open'); ov?.classList.remove('show'); };

  ham?.addEventListener('click', open);
  ov?.addEventListener('click', close);
}

/* ══════════════════════════════════════
   BOOT
══════════════════════════════════════ */
document.addEventListener('DOMContentLoaded', () => {
  initNav();
  startClock();
});
