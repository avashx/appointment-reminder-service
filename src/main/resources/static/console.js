'use strict';

// Demo console for the reminder service. Plain browser JavaScript, no build step: appointments
// from /appointments, and the service log from /demo/logs. Times are shown in the browser's own time zone.

const $ = (selector) => document.querySelector(selector);

const state = {
  lastRequest: null,      // { key, body } of the last booking, for the "retry" button
  rescheduleId: null,
  refreshing: false,
  logAfter: 0,
};

const STATUS_LABEL = {
  PENDING: 'Pending', PROCESSING: 'Processing', SENT: 'Sent', FAILED: 'Failed',
  SKIPPED: 'Skipped', EXPIRED: 'Expired', CANCELLED: 'Cancelled', SUPERSEDED: 'Superseded',
  SCHEDULED: 'Scheduled',
};

const REMINDER_KIND = { REMINDER_24H: '24h', REMINDER_2H: '2h' };

// Demo customers. After each successful booking the form moves on to the next one,
// so a recording shows a realistic mix instead of the same customer over and over.
const SAMPLE_CUSTOMERS = [
  { name: 'Aarav Sharma', phone: '+919876543210', vehicle: '2023 Tata Nexon', service: 'Oil change' },
  { name: 'Priya Iyer', phone: '+919812345678', vehicle: '2022 Maruti Suzuki Swift', service: 'Tyre rotation' },
  { name: 'Rohan Mehta', phone: '+918765432109', vehicle: '2024 Mahindra XUV700', service: 'Brake inspection' },
  { name: 'Ananya Reddy', phone: '+919900112233', vehicle: '2023 Tata Harrier', service: '30,000-km service' },
  { name: 'Vikram Singh', phone: '+917012345678', vehicle: '2022 Mahindra Thar', service: 'Oil change' },
  { name: 'Kavya Nair', phone: '+919445566778', vehicle: '2024 Tata Punch', service: 'Recall repair' },
  { name: 'Arjun Patel', phone: '+918888777666', vehicle: '2023 Maruti Suzuki Brezza', service: 'Brake inspection' },
  { name: 'Ishita Gupta', phone: '+919123456780', vehicle: '2024 Mahindra Scorpio-N', service: 'Tyre rotation' },
];
let sampleIndex = 0;

function fillSampleCustomer(form) {
  const sample = SAMPLE_CUSTOMERS[sampleIndex % SAMPLE_CUSTOMERS.length];
  form.name.value = sample.name;
  form.phone.value = sample.phone;
  form.email.value = '';
  form.vehicle.value = sample.vehicle;
  form.serviceType.value = sample.service;
}

const FIELD_FOR_ERROR = {
  dealershipId: 'dealershipId', 'customer.name': 'name', 'customer.phone': 'phone', 'customer.email': 'email',
  'customer.contactPresent': 'phone', 'customer.preferredChannelUsable': 'phone',
  scheduledAt: 'scheduledAt', vehicle: 'vehicle', serviceType: 'serviceType',
};

// ---------- helpers ----------

function esc(value) {
  return String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

async function api(path, { method = 'GET', body, headers = {} } = {}) {
  const response = await fetch(path, {
    method,
    headers: body ? { 'Content-Type': 'application/json', ...headers } : headers,
    body,
  });
  const text = await response.text();
  return { status: response.status, headers: response.headers, body: text ? JSON.parse(text) : null };
}

function newIdempotencyKey() {
  if (window.crypto?.randomUUID) return crypto.randomUUID();
  return 'key-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
}

function pad(n) { return String(n).padStart(2, '0'); }

/** A Date as the value a datetime-local input expects, in the browser's own zone. */
function toLocalInput(date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function minutesFromNow(minutes) {
  return toLocalInput(new Date(Date.now() + minutes * 60_000));
}

/** The datetime-local value is the browser's local time; toISOString() turns it into an unambiguous UTC instant. */
function inputToIso(value) {
  return value ? new Date(value).toISOString() : null;
}

function formatTime(iso, { seconds = false, date = true } = {}) {
  if (!iso) return '';
  return new Intl.DateTimeFormat('en-US', {
    ...(date ? { weekday: 'short', month: 'short', day: 'numeric' } : {}),
    hour: 'numeric', minute: '2-digit', ...(seconds ? { second: '2-digit' } : {}),
    timeZoneName: 'short',
  }).format(new Date(iso));
}

function relative(iso) {
  const seconds = Math.round((new Date(iso).getTime() - Date.now()) / 1000);
  const abs = Math.abs(seconds);
  let text;
  if (abs < 60) text = `${abs} s`;
  else if (abs < 3600) text = `${Math.floor(abs / 60)}:${pad(abs % 60)}`;
  else if (abs < 86_400) text = `${Math.floor(abs / 3600)} h ${Math.floor((abs % 3600) / 60)} min`;
  else text = `${Math.round(abs / 86_400)} days`;
  return seconds >= 0 ? `in ${text}` : `${text} ago`;
}

function duration(seconds) {
  if (seconds % 3600 === 0) return `${seconds / 3600} h`;
  if (seconds % 60 === 0) return `${seconds / 60} min`;
  return `${seconds} s`;
}

function statusPill(status) {
  return `<span class="status ${esc(status)}">${esc(STATUS_LABEL[status] ?? status)}</span>`;
}

// ---------- rendering ----------

/** Every reminder is due at "scheduledAt minus its offset", so the latest booking reveals the configured offsets. */
function renderTiming(appointments) {
  const latest = appointments.find((a) => a.reminders.some((r) => r.revision === a.revision));
  if (!latest) {
    $('#timing').innerHTML = '';
    return;
  }
  const offsets = latest.reminders
    .filter((r) => r.revision === latest.revision)
    .map((r) => ({ type: r.type, seconds: Math.round((new Date(latest.scheduledAt) - new Date(r.dueAt)) / 1000) }));
  const isDemo = offsets.some((o) => o.type === 'REMINDER_24H' && o.seconds !== 86_400);
  $('#timing').innerHTML = offsets
    .map((o) => `${REMINDER_KIND[o.type]} reminder <strong>${duration(o.seconds)}</strong> before`)
    .join(' · ') + (isDemo ? ' · demo timing' : '');
}

function renderCounts(appointments) {
  const counts = {};
  appointments.forEach((a) => a.reminders.forEach((r) => { counts[r.status] = (counts[r.status] ?? 0) + 1; }));
  const order = ['PENDING', 'PROCESSING', 'SENT', 'SKIPPED', 'EXPIRED', 'CANCELLED', 'SUPERSEDED', 'FAILED'];
  $('#counts').innerHTML = order
    .map((status) => `<dt>${esc(STATUS_LABEL[status])}</dt><dd>${counts[status] ?? 0}</dd>`)
    .join('');
}

function reminderLine(reminder) {
  let when = '';
  let note = '';
  switch (reminder.status) {
    case 'PENDING':
      // A reminder back in PENDING after an attempt is waiting for a retry or was reclaimed after a crash.
      when = reminder.attemptCount === 0
        ? `due <span data-due="${esc(reminder.dueAt)}">${esc(relative(reminder.dueAt))}</span>`
        : 'waiting to retry';
      note = reminder.attemptCount === 0 ? '' : reminder.statusReason;
      break;
    case 'PROCESSING':
      when = 'provider call in flight';
      break;
    case 'SENT':
      when = esc(formatTime(reminder.sentAt, { seconds: true, date: false }));
      note = reminder.statusReason;
      break;
    case 'SKIPPED':
      when = 'window had passed at booking';
      break;
    default:
      when = esc(reminder.statusReason ?? '');
  }
  const tries = reminder.attemptCount > 1 ? ` · ${reminder.attemptCount} tries` : '';
  const revision = reminder.revision > 1 ? `<span class="sub">rev ${reminder.revision}</span>` : '';
  const title = [`key ${reminder.idempotencyKey}`, `due ${reminder.dueAt}`,
    reminder.providerMessageId ? `provider message id ${reminder.providerMessageId}` : null].filter(Boolean).join('\n');
  return `
    <div class="reminder" title="${esc(title)}">
      <span class="kind">${REMINDER_KIND[reminder.type]}</span>
      ${statusPill(reminder.status)}
      <span class="when">${when}${tries}</span>${revision}
    </div>${note ? `<div class="reminder-note">${esc(note)}</div>` : ''}`;
}

/** Reminders replaced by a reschedule are collapsed into one line, so the current ones stand out. */
function remindersCell(appointment) {
  const superseded = appointment.reminders.filter((r) => r.status === 'SUPERSEDED');
  const shown = appointment.reminders.filter((r) => r.status !== 'SUPERSEDED');
  const lines = shown.map(reminderLine);
  if (superseded.length > 0) {
    lines.push(`<div class="reminder"><span class="kind"></span><span class="when">${superseded.length} superseded by reschedule</span></div>`);
  }
  return lines.join('');
}

function renderAppointments(appointments) {
  $('#appointments-empty').hidden = appointments.length > 0;
  $('#appointments').innerHTML = appointments.map((a) => {
    const started = new Date(a.scheduledAt).getTime() <= Date.now();
    const closed = a.status === 'CANCELLED' || started;
    const details = [a.vehicle, a.serviceType].filter(Boolean).join(' · ');
    return `
      <tr class="${a.status === 'CANCELLED' ? 'cancelled' : ''}">
        <td class="id-cell">${esc(a.id)}</td>
        <td>${esc(a.customerName)}<span class="sub">${esc(details || '—')} · ${a.preferredChannel === 'SMS' ? 'Text' : 'Email'}</span></td>
        <td class="nowrap">
          ${esc(formatTime(a.scheduledAt))}
          <span class="sub">Dealership ${esc(a.dealershipId)} · ${a.status === 'CANCELLED' ? statusPill('CANCELLED') : `<span data-due="${esc(a.scheduledAt)}">${esc(relative(a.scheduledAt))}</span>`}</span>
        </td>
        <td>${remindersCell(a)}</td>
        <td class="row-actions">
          <button class="btn-link" data-action="reschedule" data-id="${esc(a.id)}" ${closed ? 'disabled' : ''}>Reschedule</button>
          <button class="btn-link danger" data-action="cancel" data-id="${esc(a.id)}" ${closed ? 'disabled' : ''}>Cancel</button>
        </td>
      </tr>`;
  }).join('');
}

/** Countdowns change every second; update them in place instead of re-rendering the table. */
function tick() {
  document.querySelectorAll('[data-due]').forEach((el) => { el.textContent = relative(el.dataset.due); });
}

function setConnection(up) {
  const el = $('#connection');
  el.dataset.state = up ? 'up' : 'down';
  el.textContent = up ? 'Live' : 'Service unreachable';
}

function renderLogs(snapshot) {
  const list = $('#logs');
  const empty = $('#logs-empty');
  if (snapshot.reset) {
    list.replaceChildren();
    state.logAfter = 0;
  }
  const nearBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 48;
  for (const line of snapshot.lines) {
    const item = document.createElement('li');
    item.dataset.level = line.level;
    item.innerHTML = `<time>${esc(formatTime(line.at, { seconds: true, date: false }))}</time><span>${esc(line.message)}</span>`;
    list.appendChild(item);
    state.logAfter = Math.max(state.logAfter, line.id);
  }
  const hasLines = list.children.length > 0;
  list.hidden = !hasLines;
  empty.hidden = hasLines;
  if (hasLines && (snapshot.reset || nearBottom)) list.scrollTop = list.scrollHeight;
}

async function refresh() {
  if (state.refreshing) return;
  state.refreshing = true;
  try {
    try {
      const appointments = (await api('/appointments?limit=15')).body;
      renderAppointments(appointments);
      renderCounts(appointments);
      renderTiming(appointments);
      setConnection(true);
    } catch (error) {
      setConnection(false);
    }
    try {
      const logs = (await api('/demo/logs?after=' + state.logAfter)).body;
      if (logs && Array.isArray(logs.lines)) renderLogs(logs);
    } catch (error) {
      // The appointment table still works if the log feed is unavailable.
    }
  } finally {
    state.refreshing = false;
  }
}

// ---------- booking ----------

function showResult(kind, html) {
  const el = $('#result');
  el.className = `result ${kind}`;
  el.innerHTML = html;
  el.hidden = false;
}

function showProblem(response) {
  const problem = response.body ?? {};
  const errors = problem.errors ?? {};
  Object.keys(errors).forEach((field) => {
    const input = document.querySelector(`[name="${FIELD_FOR_ERROR[field] ?? field}"]`);
    if (input) input.setAttribute('aria-invalid', 'true');
  });
  const list = Object.entries(errors).map(([field, message]) => `<li><code>${esc(field)}</code> ${esc(message)}</li>`).join('');
  showResult('error', `<strong>${esc(response.status)} ${esc(problem.title ?? 'Error')}</strong> · ${esc(problem.detail ?? '')}${list ? `<ul>${list}</ul>` : ''}`);
}

function describeReminders(appointment) {
  return appointment.reminders
    .map((r) => `${REMINDER_KIND[r.type]} ${STATUS_LABEL[r.status].toLowerCase()}`)
    .join(', ');
}

async function sendBooking(request) {
  const response = await api('/appointments', {
    method: 'POST', body: request.body, headers: { 'Idempotency-Key': request.key },
  });
  if (response.status === 201) {
    sampleIndex++;
    fillSampleCustomer($('#book'));
    showResult('ok', `<strong>201 Created</strong> · appointment #${esc(response.body.id)} (${esc(describeReminders(response.body))}).
      Idempotency-Key <code>${esc(request.key.slice(0, 8))}…</code>`);
  } else if (response.status === 200 && response.headers.get('Idempotent-Replayed')) {
    showResult('replay', `<strong>200 OK · Idempotent-Replayed</strong> · same key and body, so appointment
      #${esc(response.body.id)} was returned and nothing new was created.`);
  } else {
    showProblem(response);
  }
  $('#replay').disabled = !(response.status === 201 || response.status === 200);
  refresh();
}

function readBooking(form) {
  const data = new FormData(form);
  const text = (name) => (data.get(name) ?? '').toString().trim() || null;
  return {
    dealershipId: Number(data.get('dealershipId')),
    customer: {
      name: text('name'), phone: text('phone'), email: text('email'), preferredChannel: data.get('channel'),
    },
    vehicle: text('vehicle'),
    serviceType: text('serviceType'),
    scheduledAt: inputToIso(text('scheduledAt')),
  };
}

// ---------- appointment actions ----------

async function cancelAppointment(id) {
  const response = await api(`/appointments/${id}/cancel`, { method: 'POST' });
  if (response.status !== 200) {
    showResult('error', `<strong>${esc(response.status)}</strong> · ${esc(response.body?.detail ?? 'Could not cancel')}`);
  }
  refresh();
}

function openReschedule(id) {
  state.rescheduleId = id;
  $('#reschedule-label').textContent = `#${id}`;
  $('#reschedule-at').value = minutesFromNow(5);
  $('#reschedule-error').textContent = '';
  $('#reschedule').hidden = false;
  $('#reschedule-at').focus();
}

async function saveReschedule() {
  const response = await api(`/appointments/${state.rescheduleId}`, {
    method: 'PATCH', body: JSON.stringify({ scheduledAt: inputToIso($('#reschedule-at').value) }),
  });
  if (response.status === 200) {
    $('#reschedule').hidden = true;
    state.rescheduleId = null;
  } else {
    const errors = Object.values(response.body?.errors ?? {}).join('; ');
    $('#reschedule-error').textContent = `${response.status}: ${errors || response.body?.detail || 'failed'}`;
  }
  refresh();
}

// ---------- wiring ----------

async function init() {
  const form = $('#book');
  fillSampleCustomer(form);
  form.scheduledAt.value = minutesFromNow(3);

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    form.querySelectorAll('[aria-invalid]').forEach((el) => el.removeAttribute('aria-invalid'));
    // A new booking gets a new Idempotency-Key; "Retry last request" reuses it, byte for byte.
    state.lastRequest = { key: newIdempotencyKey(), body: JSON.stringify(readBooking(form)) };
    sendBooking(state.lastRequest);
  });

  $('#replay').addEventListener('click', () => state.lastRequest && sendBooking(state.lastRequest));

  $('#quick').addEventListener('click', (event) => {
    const minutes = event.target.dataset?.minutes;
    if (minutes) form.scheduledAt.value = minutesFromNow(Number(minutes));
  });

  $('#appointments').addEventListener('click', (event) => {
    const button = event.target.closest('button[data-action]');
    if (!button || button.disabled) return;
    const id = Number(button.dataset.id);
    if (button.dataset.action === 'cancel') cancelAppointment(id);
    if (button.dataset.action === 'reschedule') openReschedule(id);
  });

  $('#reschedule-save').addEventListener('click', saveReschedule);
  $('#reschedule-cancel').addEventListener('click', () => { $('#reschedule').hidden = true; });

  await refresh();
  setInterval(refresh, 2000);
  setInterval(tick, 1000);
}

init().catch(() => setConnection(false));
