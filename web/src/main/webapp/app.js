// FairSlot browser client: calendar, holds with countdown, waitlist, live offers over
// WebSocket, group (bundle) bookings and the weekly quota.
'use strict';

const $ = (id) => document.getElementById(id);

const state = {
    user: null,
    resources: [],
    slots: [],
    offers: new Map(),        // offerId -> {resource_id, slot_start, expires_at}
    ws: null,
    wsRetry: 1000,
    refreshTimer: null,
};

const MESSAGES = {
    slot_taken: 'Someone else got this slot first.',
    quota_exceeded: 'That would go over your weekly quota.',
    hold_expired: 'Your hold expired before you confirmed it.',
    offer_expired: 'The offer expired.',
    already_waiting: 'You are already on the waitlist for this slot.',
    slot_free: 'The slot is free now: book it directly.',
    too_early: 'Check-in opens 15 minutes before the start.',
    too_late: 'The check-in window has closed.',
    not_holder: 'That is not yours.',
    unavailable: 'The booking service is not answering. Try again in a moment.',
};

// ------------------------------------------------------------------ HTTP

function newKey() {
    // crypto.randomUUID() needs HTTPS; getRandomValues also works over plain HTTP on the VMs.
    return Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, '0')).join('');
}

async function api(method, path, body) {
    const opts = { method, headers: {} };
    if (body !== undefined) {
        opts.headers['Content-Type'] = 'application/json';
        opts.body = JSON.stringify(body);
    }
    if (method !== 'GET') {
        opts.headers['Idempotency-Key'] = newKey();
    }
    const resp = await fetch('api' + path, opts);
    const data = await resp.json().catch(() => ({}));
    if (!resp.ok) {
        const err = new Error(MESSAGES[data.error] || data.message || resp.statusText);
        err.code = data.error;
        err.status = resp.status;
        throw err;
    }
    return data;
}

// ------------------------------------------------------------------ helpers

function log(text, isError = false) {
    const li = document.createElement('li');
    if (isError) li.className = 'error';
    const when = document.createElement('span');
    when.className = 'when';
    when.textContent = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    li.append(when, text);
    $('log').prepend(li);
    while ($('log').children.length > 8) $('log').lastChild.remove();
}

function hour(ms) {
    return new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function dayLabel(ms) {
    return new Date(ms).toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' });
}

function left(expiresAt) {
    const s = Math.max(0, Math.round((expiresAt - Date.now()) / 1000));
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

function resourceName(id) {
    const r = state.resources.find((x) => x.id === id);
    return r ? r.name : id;
}

function button(label, onClick, primary = false) {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = label;
    if (primary) b.className = 'primary';
    b.addEventListener('click', (ev) => {
        ev.stopPropagation();
        onClick();
    });
    return b;
}

function countdown(expiresAt, prefix) {
    const span = document.createElement('span');
    span.className = 'state';
    span.dataset.expires = expiresAt;
    span.dataset.prefix = prefix;
    span.textContent = `${prefix} ${left(expiresAt)}`;
    return span;
}

/** Runs an action, logs its outcome and refreshes the view. */
async function run(action, okText) {
    try {
        await action();
        if (okText) log(okText);
    } catch (e) {
        log(e.message, true);
    }
    await refresh();
}

// ------------------------------------------------------------------ views

async function showLoggedIn(user) {
    state.user = user;
    $('login-view').hidden = true;
    $('book-view').hidden = false;
    $('who').hidden = false;
    $('user-name').textContent = user;

    state.resources = await api('GET', '/resources');
    const rooms = state.resources.filter((r) => r.kind === 'room');
    const equipment = state.resources.filter((r) => r.kind !== 'room');
    const group = (label, list) => {
        const g = document.createElement('optgroup');
        g.label = label;
        g.append(...list.map((r) => new Option(r.name, r.id)));
        return g;
    };
    $('resource').replaceChildren(group('Rooms', rooms), group('Equipment', equipment));
    const form = $('bundle-form');
    form.room.replaceChildren(...rooms.map((r) => new Option(r.name, r.id)));
    form.equipment.replaceChildren(...equipment.map((r) => new Option(r.name, r.id)));
    form.hour.replaceChildren(...Array.from({ length: 13 }, (_, i) => new Option(`${String(8 + i).padStart(2, '0')}:00`, 8 + i)));
    // valueAsDate would use the UTC date, which is still "yesterday" just after midnight in Europe.
    const now = new Date();
    const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
    if (!$('date').value) $('date').value = today;
    if (!form.date.value) form.date.value = today;

    connectWs();
    await refresh();
}

function showLoggedOut() {
    state.user = null;
    state.offers.clear();
    if (state.ws) state.ws.close();
    $('login-view').hidden = false;
    $('book-view').hidden = true;
    $('who').hidden = true;
}

async function refresh() {
    const resource = $('resource').value;
    const date = $('date').value;
    if (!state.user || !resource || !date) return;
    try {
        const [day, quota] = await Promise.all([
            api('GET', `/resources/${encodeURIComponent(resource)}/availability?date=${date}`),
            api('GET', '/quota'),
        ]);
        state.slots = day.slots;
        for (const s of day.slots) {
            if (s.offer_id) state.offers.set(s.offer_id, { resource_id: resource, slot_start: s.start, expires_at: s.expires_at });
        }
        renderQuota(quota);
        renderSlots(resource);
        renderOffers();
    } catch (e) {
        if (e.status === 401) showLoggedOut();
        else log(e.message, true);
    }
}

/** Coalesces bursts of live events into one refresh. */
function refreshSoon() {
    clearTimeout(state.refreshTimer);
    state.refreshTimer = setTimeout(refresh, 150);
}

function renderQuota(q) {
    $('quota-text').textContent = `${q.used_hours}/${q.limit_hours} h`;
    $('quota-fill').style.width = `${Math.min(100, (100 * q.used_hours) / q.limit_hours)}%`;
}

function renderSlots(resource) {
    $('slots').replaceChildren(...state.slots.map((s) => renderSlot(resource, s)));
}

function renderSlot(resource, s) {
    const div = document.createElement('div');
    const time = document.createElement('span');
    time.className = 'time';
    time.textContent = `${hour(s.start)} – ${hour(s.start + 3600000)}`;
    const label = document.createElement('span');
    label.className = 'state';
    const actions = document.createElement('div');
    actions.className = 'actions';
    div.append(time);

    if (s.state === 'free') {
        div.className = 'slot free';
        label.textContent = 'free · click to hold';
        div.addEventListener('click', () => run(async () => {
            const h = await api('POST', '/holds', { resourceId: resource, slotStart: s.start });
            log(`Held ${resourceName(resource)} at ${hour(s.start)}. Confirm within ${left(h.expires_at)}.`);
        }));
        div.append(label);
    } else if (s.state !== 'mine') {
        div.className = `slot ${s.state}`;
        label.textContent = `${s.state} · click to join the waitlist`;
        div.addEventListener('click', () => run(async () => {
            const w = await api('POST', '/waitlist', { resourceId: resource, slotStart: s.start });
            log(`You are number ${w.position} on the waitlist for ${hour(s.start)}. We will notify you here.`);
        }));
        div.append(label);
    } else if (s.hold_id) {
        div.className = 'slot mine';
        div.append(s.expires_at ? countdown(s.expires_at, 'held by you ·') : Object.assign(label, { textContent: 'held by you' }));
        actions.append(
            button('Confirm', () => run(() => api('POST', `/holds/${s.hold_id}/confirm`), 'Booked.'), true),
            button('Release', () => run(() => api('DELETE', `/holds/${s.hold_id}`), 'Hold released.')));
        div.append(actions);
    } else if (s.booking_id) {
        div.className = 'slot mine';
        label.textContent = 'booked by you';
        actions.append(
            button('Check in', () => run(() => api('POST', `/bookings/${s.booking_id}/check-in`), 'Checked in.')),
            button('Cancel', () => run(() => api('POST', `/bookings/${s.booking_id}/cancel`), 'Booking cancelled.')));
        div.append(label, actions);
    } else if (s.offer_id) {
        div.className = 'slot offer';
        div.append(countdown(s.expires_at, 'offered to you ·'));
        actions.append(
            button('Accept', () => acceptOffer(s.offer_id), true),
            button('Decline', () => declineOffer(s.offer_id)));
        div.append(actions);
    }
    return div;
}

function renderOffers() {
    const now = Date.now();
    for (const [id, o] of state.offers) {
        if (o.expires_at && o.expires_at < now) state.offers.delete(id);
    }
    $('offers').replaceChildren(...[...state.offers.entries()].map(([id, o]) => {
        const div = document.createElement('div');
        div.className = 'offer';
        const text = document.createElement('span');
        text.textContent = `A slot opened up for you: ${resourceName(o.resource_id)}, ${dayLabel(o.slot_start)} ${hour(o.slot_start)}. `;
        text.append(countdown(o.expires_at, 'Accept within'));
        const actions = document.createElement('span');
        actions.append(button('Accept', () => acceptOffer(id), true), button('Decline', () => declineOffer(id)));
        div.append(text, actions);
        return div;
    }));
}

async function acceptOffer(id) {
    await run(async () => {
        try {
            await api('POST', `/offers/${id}/accept`);
        } finally {
            state.offers.delete(id);
        }
    }, 'Offer accepted: the slot is booked.');
}

async function declineOffer(id) {
    await run(async () => {
        try {
            await api('POST', `/offers/${id}/decline`);
        } finally {
            state.offers.delete(id);
        }
    }, 'Offer declined: it passes to the next person.');
}

// ------------------------------------------------------------------ live updates

function connectWs() {
    if (!state.user || (state.ws && state.ws.readyState <= WebSocket.OPEN)) return;
    const url = new URL('ws', location.href);
    url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(url);
    state.ws = ws;
    ws.onopen = () => { state.wsRetry = 1000; };
    ws.onmessage = (ev) => onEvent(JSON.parse(ev.data));
    ws.onclose = () => {
        $('live').className = 'live off';
        if (state.ws === ws && state.user) {
            setTimeout(connectWs, state.wsRetry);
            state.wsRetry = Math.min(state.wsRetry * 2, 15000);
        }
    };
}

function onEvent(e) {
    const current = $('resource').value;
    switch (e.type) {
        case 'hello':
            $('live').className = 'live on';
            break;
        case 'slot_taken':
        case 'slot_freed':
            if (e.resource_id === current) refreshSoon();
            break;
        case 'offer':
            state.offers.set(e.offer_id, e);
            log(`A slot opened up for you: ${resourceName(e.resource_id)} at ${hour(e.slot_start)}. Accept within ${left(e.expires_at)}.`);
            renderOffers();
            refreshSoon();
            break;
        case 'offer_expired':
            state.offers.delete(e.offer_id);
            log('Your offer expired and passed to the next person.', true);
            renderOffers();
            refreshSoon();
            break;
        case 'hold_expiring':
            log(`Your hold expires in ${left(e.expires_at)}: confirm it now.`, true);
            break;
        case 'hold_expired':
            log('Your hold expired and the slot was released.', true);
            refreshSoon();
            break;
        case 'checkin_missed':
            log('You missed the check-in: the booking was released.', true);
            refreshSoon();
            break;
        default:
            break;
    }
}

// Countdowns tick every second; ping keeps idle proxies from closing the socket.
setInterval(() => {
    for (const el of document.querySelectorAll('[data-expires]')) {
        el.textContent = `${el.dataset.prefix} ${left(Number(el.dataset.expires))}`;
    }
}, 1000);
setInterval(() => {
    if (state.ws && state.ws.readyState === WebSocket.OPEN) state.ws.send('ping');
}, 25000);

// ------------------------------------------------------------------ forms

$('login-form').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const form = new FormData(ev.target);
    try {
        const r = await api('POST', '/auth/login', { username: form.get('username'), password: form.get('password') });
        ev.target.reset();
        await showLoggedIn(r.user);
    } catch (e) {
        log(e.message, true);
    }
});

$('bundle-form').addEventListener('submit', (ev) => {
    ev.preventDefault();
    const f = ev.target;
    const start = new Date(`${f.date.value}T${String(f.hour.value).padStart(2, '0')}:00`).getTime();
    run(async () => {
        await api('POST', '/bookings/bundle', {
            items: [{ resourceId: f.room.value, slotStart: start }, { resourceId: f.equipment.value, slotStart: start }],
        });
        log(`Booked ${resourceName(f.room.value)} and ${resourceName(f.equipment.value)} at ${hour(start)}.`);
    });
});

$('logout').addEventListener('click', async () => {
    await api('POST', '/auth/logout').catch(() => {});
    showLoggedOut();
});

$('resource').addEventListener('change', refresh);
$('date').addEventListener('change', refresh);

api('GET', '/auth/me').then((r) => showLoggedIn(r.user)).catch(showLoggedOut);
