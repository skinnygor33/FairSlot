// Minimal FairSlot client for the week 1 gate: log in, browse a day, hold and confirm a slot.
// The full UI (live offers over WebSocket, waitlist, bundles) comes in week 2.
'use strict';

const $ = (id) => document.getElementById(id);
const mine = new Map();   // holdId or bookingId -> {kind, label, expiresAt}

async function api(method, path, body) {
    const opts = { method, headers: {} };
    if (body !== undefined) {
        opts.headers['Content-Type'] = 'application/json';
        opts.body = JSON.stringify(body);
    }
    if (method !== 'GET') {
        // crypto.randomUUID() needs HTTPS; getRandomValues also works over plain HTTP on the VMs.
        opts.headers['Idempotency-Key'] = Array.from(crypto.getRandomValues(new Uint8Array(16)),
            (b) => b.toString(16).padStart(2, '0')).join('');
    }
    const resp = await fetch('api' + path, opts);
    const data = await resp.json().catch(() => ({}));
    if (!resp.ok) {
        const err = new Error(data.message || resp.statusText);
        err.code = data.error;
        err.status = resp.status;
        throw err;
    }
    return data;
}

function say(text, isError = false) {
    $('message').textContent = text;
    $('message').className = isError ? 'error' : '';
}

function hour(ms) {
    return new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

async function showLoggedIn(user) {
    $('login-view').hidden = true;
    $('book-view').hidden = false;
    $('who').hidden = false;
    $('user-name').textContent = user;
    const resources = await api('GET', '/resources');
    $('resource').replaceChildren(...resources.map((r) => new Option(`${r.name} (${r.id})`, r.id)));
    if (!$('date').value) {
        $('date').valueAsDate = new Date();
    }
    await refresh();
}

function showLoggedOut() {
    $('login-view').hidden = false;
    $('book-view').hidden = true;
    $('who').hidden = true;
    mine.clear();
}

async function refresh() {
    const resource = $('resource').value;
    const date = $('date').value;
    if (!resource || !date) return;
    const [day, quota] = await Promise.all([
        api('GET', `/resources/${encodeURIComponent(resource)}/availability?date=${date}`),
        api('GET', '/quota'),
    ]);
    $('quota').textContent = `${quota.used_hours}/${quota.limit_hours} h this week`;
    $('slots').replaceChildren(...day.slots.map((s) => slotButton(resource, s)));
    renderMine();
}

function slotButton(resource, slot) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = `slot ${slot.state}`;
    b.innerHTML = `<strong>${hour(slot.start)}</strong><span class="state">${slot.state}</span>`;
    if (slot.state === 'free') {
        b.addEventListener('click', () => hold(resource, slot.start));
    } else if (slot.state === 'held' || slot.state === 'booked') {
        b.title = 'Taken: click to join the waitlist';
        b.addEventListener('click', () => joinWaitlist(resource, slot.start));
    } else {
        b.disabled = true;
    }
    return b;
}

async function hold(resource, start) {
    try {
        const h = await api('POST', '/holds', { resourceId: resource, slotStart: start });
        mine.set(h.hold_id, { kind: 'hold', label: `${resource} at ${hour(start)}`, expiresAt: h.expires_at });
        say(`Held until ${hour(h.expires_at)}. Confirm to book it.`);
    } catch (e) {
        say(e.code === 'slot_taken' ? 'Someone else got this slot first.' : e.message, true);
    }
    await refresh();
}

async function joinWaitlist(resource, start) {
    try {
        const w = await api('POST', '/waitlist', { resourceId: resource, slotStart: start });
        say(`You are number ${w.position} on the waitlist.`);
    } catch (e) {
        say(e.message, true);
    }
}

async function act(id, action) {
    const item = mine.get(id);
    try {
        if (action === 'confirm') {
            const b = await api('POST', `/holds/${id}/confirm`);
            mine.delete(id);
            mine.set(b.booking_id, { kind: 'booking', label: item.label });
            say('Booked.');
        } else if (action === 'release') {
            await api('DELETE', `/holds/${id}`);
            mine.delete(id);
            say('Hold released.');
        } else if (action === 'cancel') {
            await api('POST', `/bookings/${id}/cancel`);
            mine.delete(id);
            say('Booking cancelled.');
        } else if (action === 'check-in') {
            await api('POST', `/bookings/${id}/check-in`);
            say('Checked in.');
        }
    } catch (e) {
        if (e.code === 'hold_expired') mine.delete(id);
        say(e.message, true);
    }
    await refresh();
}

function renderMine() {
    const items = [...mine.entries()].map(([id, it]) => {
        const li = document.createElement('li');
        const what = it.kind === 'hold' ? `Hold ${id} (until ${hour(it.expiresAt)})` : `Booking ${id}`;
        li.textContent = `${what}: ${it.label}`;
        const actions = it.kind === 'hold' ? ['confirm', 'release'] : ['check-in', 'cancel'];
        for (const a of actions) {
            const b = document.createElement('button');
            b.type = 'button';
            b.textContent = a;
            if (a === 'confirm') b.className = 'primary';
            b.addEventListener('click', () => act(id, a));
            li.append(b);
        }
        return li;
    });
    $('mine').replaceChildren(...items);
}

$('login-form').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const form = new FormData(ev.target);
    try {
        const r = await api('POST', '/auth/login', { username: form.get('username'), password: form.get('password') });
        say('');
        await showLoggedIn(r.user);
    } catch (e) {
        say(e.message, true);
    }
});

$('logout').addEventListener('click', async () => {
    await api('POST', '/auth/logout');
    showLoggedOut();
});

$('resource').addEventListener('change', refresh);
$('date').addEventListener('change', refresh);

api('GET', '/auth/me').then((r) => showLoggedIn(r.user)).catch(showLoggedOut);
