// A small hash-routed client. The server owns every rule; this only displays them.

const view = document.getElementById('view');
const clock = document.getElementById('clock');
const stage = document.getElementById('stage');

let status = null;
let leaveView = () => {};
let onStatus = () => {};
// A player that's in picture-in-picture while another page is showing.
let floating = null;

// ---- helpers ----

function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === null || value === undefined || value === false) continue;
    if (key.startsWith('on')) el.addEventListener(key.slice(2), value);
    else if (key === 'class') el.className = value;
    else if (key in el && key !== 'list') el[key] = value;
    else el.setAttribute(key, value);
  }
  el.append(...children.flat().filter((c) => c !== null && c !== undefined && c !== false));
  return el;
}

// replaceChildren would turn a null into the text "null".
function fill(el, ...children) {
  el.replaceChildren(...children.filter(Boolean));
}

function icon(name) {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('class', 'icon');
  svg.setAttribute('aria-hidden', 'true');
  const use = document.createElementNS(ns, 'use');
  use.setAttribute('href', `/vendor/lucide.svg#${name}`);
  svg.append(use);
  return svg;
}

function iconButton(name, label, attrs = {}) {
  return h('button', { type: 'button', ...attrs, class: `icon-button ${attrs.class ?? ''}`, title: label, 'aria-label': label }, icon(name));
}

class ApiError extends Error {
  constructor(code, data) {
    super(data?.message ?? `Request failed (${code})`);
    this.code = code;
  }
}

async function api(path, options = {}) {
  const init = { method: options.method ?? 'GET' };
  if (options.body) {
    init.headers = { 'Content-Type': 'application/json' };
    init.body = JSON.stringify(options.body);
  }
  const response = await fetch(path, init);
  const data = response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok) throw new ApiError(response.status, data);
  return data;
}

function duration(seconds) {
  if (seconds === null || seconds === undefined) return '';
  const total = Math.round(seconds);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const secs = String(total % 60).padStart(2, '0');
  return hours ? `${hours}:${String(minutes).padStart(2, '0')}:${secs}` : `${minutes}:${secs}`;
}

function minutesText(seconds) {
  const minutes = Math.ceil(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const rest = minutes % 60;
  return rest ? `${Math.floor(minutes / 60)}h ${rest}m` : `${Math.floor(minutes / 60)}h`;
}

function timeOfDay(timestamp) {
  return new Date(timestamp * 1000).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

function failure(error) {
  return h('p', { class: 'problem' }, error.message);
}

function unlockWaiting(s) {
  return s.unlock_starts_at && s.unlock_starts_at > s.now;
}

// ---- status ----

async function refreshStatus() {
  try {
    status = await api('/api/status');
  } catch {
    return;
  }
  renderClock();
  onStatus(status);
}

function renderClock() {
  const s = status;
  clock.className = 'clock';
  if (unlockWaiting(s)) {
    clock.textContent = `unlock in ${Math.ceil(s.unlock_starts_at - s.now)}s`;
  } else if (!s.can_watch) {
    clock.classList.add('out');
    clock.textContent = 'no time left';
  } else {
    if (s.remaining_seconds <= 600) clock.classList.add('low');
    clock.textContent = `${minutesText(s.remaining_seconds)} left`;
  }
}

function unlockControl() {
  const box = h('div', { class: 'stack' });
  const draw = () => {
    box.replaceChildren();
    const s = status;
    if (unlockWaiting(s)) {
      box.append(h('p', {}, `Adding ${s.unlock_minutes} min in ${Math.ceil(s.unlock_starts_at - s.now)}s.`));
    } else if (!s.budget_spent) {
      // Unlocks only open once the budget is gone, so there's nothing to offer yet.
    } else if (s.unlocks_left > 0) {
      const error = h('p', { class: 'problem' });
      box.append(
        h('div', { class: 'row' },
          h('button', {
            onclick: async () => {
              try {
                await api('/api/unlock', { method: 'POST' });
                await refreshStatus();
              } catch (e) {
                error.textContent = e.message;
              }
            },
          }, `Use an unlock (${s.unlocks_left} left)`),
          h('span', { class: 'muted' }, `+${s.unlock_minutes} min, after a ${s.unlock_wait_seconds}s wait`),
        ),
        error,
      );
    } else {
      box.append(h('p', { class: 'muted' }, 'No unlocks left today.'));
    }
  };
  draw();
  box.redraw = draw;
  return box;
}

function outOfTimePanel() {
  const control = unlockControl();
  const panel = h('section', { class: 'panel stack' },
    h('h2', {}, 'No time left today'),
    h('p', { class: 'muted' }, `Your budget resets at ${timeOfDay(status.resets_at)}.`),
    control,
  );
  panel.redraw = control.redraw;
  return panel;
}

// ---- lists ----

function resultRow(video, { href = `#/watch/${video.id}`, meta = null } = {}) {
  const thumb = h('div', { class: 'thumb' });
  thumb.append(h('img', {
    src: `/thumb/${video.id}`,
    alt: '',
    loading: 'lazy',
    onerror: (event) => event.target.remove(),
  }));
  if (video.duration) thumb.append(h('span', { class: 'length' }, duration(video.duration)));
  return h('a', { class: 'result', href },
    thumb,
    h('div', {},
      h('div', { class: 'title' }, video.title),
      video.channel && h('div', { class: 'muted' }, video.channel),
      meta && h('div', { class: 'muted' }, meta),
    ),
  );
}

function resultList(results) {
  if (!results.length) return h('p', { class: 'muted' }, 'Nothing found.');
  return h('div', { class: 'results' }, results.map(resultRow));
}

function withLoading(container, label, load) {
  container.replaceChildren(h('p', { class: 'muted' }, label));
  load().catch((error) => {
    if (error.code === 402) container.replaceChildren(outOfTimePanel());
    else container.replaceChildren(failure(error));
  });
}

// ---- pages ----

function searchPage(query) {
  const input = h('input', {
    type: 'search',
    value: query ?? '',
    placeholder: 'What did you come here for?',
    'aria-label': 'Search',
    autocomplete: 'off',
  });
  const results = h('div', { class: 'stack' });
  const form = h('form', {
    class: 'search',
    onsubmit: (event) => {
      event.preventDefault();
      const q = input.value.trim();
      if (/^https?:\/\//i.test(q)) location.href = `/share?url=${encodeURIComponent(q)}`;
      else if (q) location.hash = `#/search/${encodeURIComponent(q)}`;
    },
  }, input, iconButton('search', 'Search', { class: 'primary', type: 'submit' }));

  const summary = h('div', { class: 'stack' });
  let locked = null;
  const drawSummary = (s) => {
    if (!s.can_watch) {
      if (!locked) {
        locked = outOfTimePanel();
        summary.replaceChildren(locked);
      } else {
        locked.redraw();
      }
      form.hidden = true;
      return;
    }
    locked = null;
    form.hidden = false;
    if (query) {
      summary.replaceChildren();
      return;
    }
    const used = s.budget_seconds ? Math.min(1, s.used_seconds / s.budget_seconds) : 1;
    summary.replaceChildren(
      h('section', { class: 'panel stack' },
        h('h1', {}, `${minutesText(s.remaining_seconds)} left today`),
        h('div', { class: 'meter', role: 'img', 'aria-label': `${Math.round(used * 100)}% used` },
          h('div', { style: `width:${used * 100}%` })),
        h('p', { class: 'muted' }, `Resets at ${timeOfDay(s.resets_at)}.`),
      ),
    );
  };

  view.replaceChildren(h('div', { class: 'stack' }, form, summary, results));
  onStatus = drawSummary;
  if (status) drawSummary(status);
  if (query) {
    withLoading(results, 'Searching…', async () => {
      const data = await api(`/api/search?q=${encodeURIComponent(query)}`);
      results.replaceChildren(resultList(data.results));
    });
  } else if (matchMedia('(pointer: fine)').matches) {
    input.focus();
  }
}

function channelsPage() {
  const list = h('div', { class: 'stack' });
  view.replaceChildren(h('div', { class: 'stack' }, h('h1', {}, 'Channels'), list));
  withLoading(list, 'Loading…', async () => {
    const { subscriptions } = await api('/api/subscriptions');
    if (!subscriptions.length) {
      list.replaceChildren(h('p', { class: 'muted' }, 'No channels yet. Open a video and subscribe to its channel.'));
      return;
    }
    list.replaceChildren(h('table', {}, h('tbody', {}, subscriptions.map((c) =>
      h('tr', {}, h('td', {}, h('a', { href: `#/channel/${c.channel_id}` }, c.name)))))));
  });
}

function channelPage(id) {
  const body = h('div', { class: 'stack' });
  view.replaceChildren(body);
  const more = h('div', { class: 'stack' });

  const loadPage = async (list, offset) => {
    const data = await api(`/api/channel/${encodeURIComponent(id)}?offset=${offset}`);
    list.append(...data.results.map((video) => resultRow(video)));
    more.replaceChildren();
    if (data.next_offset !== null) {
      more.append(h('div', {}, h('button', {
        onclick: () => withLoading(more, 'Loading…', () => loadPage(list, data.next_offset)),
      }, 'Load more')));
    }
    return data;
  };

  withLoading(body, 'Loading channel…', async () => {
    const list = h('div', { class: 'results' });
    const data = await loadPage(list, 0);
    const { subscriptions } = await api('/api/subscriptions');
    body.replaceChildren(
      h('div', { class: 'row spread' }, h('h1', {}, data.name),
        subscribeButton(id, data.name, subscriptions.some((c) => c.channel_id === id))),
      list.childElementCount || data.next_offset !== null ? list : h('p', { class: 'muted' }, 'Nothing found.'),
      more,
    );
  });
}

function subscribeButton(id, name, initial) {
  let subscribed = initial;
  const button = h('button', {
    onclick: async () => {
      if (subscribed) await api(`/api/subscriptions/${encodeURIComponent(id)}`, { method: 'DELETE' });
      else await api('/api/subscriptions', { method: 'POST', body: { channel_id: id, name } });
      subscribed = !subscribed;
      button.textContent = subscribed ? 'Unsubscribe' : 'Subscribe';
    },
  }, subscribed ? 'Unsubscribe' : 'Subscribe');
  return button;
}

function usagePage() {
  const usage = h('div', { class: 'stack' });
  const settings = h('div', { class: 'stack' });
  view.replaceChildren(h('div', { class: 'stack' }, h('h1', {}, 'Usage'), usage, h('h2', {}, 'Limits'), settings));
  withLoading(usage, 'Loading…', async () => {
    const data = await api('/api/history');
    fill(usage,
      h('table', {},
        h('thead', {}, h('tr', {},
          h('th', { class: 'muted' }, 'Day'),
          h('th', { class: 'muted num' }, 'Budget'),
          h('th', { class: 'muted num' }, 'Unlocked'))),
        h('tbody', {}, data.days.map((d) => h('tr', {},
          h('td', {}, d.day),
          h('td', { class: 'num' }, minutesText(d.charged_seconds)),
          h('td', { class: 'num' }, minutesText(d.unlocked_seconds)))))),
      h('p', {}, h('a', { href: '#/history' }, 'See what you watched')),
    );
  });
  drawSettings(settings);
}

function historyPage() {
  const q = h('input', { type: 'search', placeholder: 'Title or channel', 'aria-label': 'Search history' });
  const start = h('input', { type: 'date', 'aria-label': 'From' });
  const end = h('input', { type: 'date', 'aria-label': 'To' });
  const list = h('div', { class: 'results' });
  const status = h('div', { class: 'stack' });
  let offset = 0;

  async function loadPage() {
    const params = new URLSearchParams({ q: q.value, offset: String(offset) });
    if (start.value) params.set('start', start.value);
    if (end.value) params.set('end', end.value);
    const { videos } = await api(`/api/history/videos?${params}`);
    if (!offset && !videos.length) {
      status.replaceChildren(h('p', { class: 'muted' }, 'Nothing watched matches that.'));
      return;
    }
    list.append(...videos.map((v) => resultRow(
      { id: v.video_id, title: v.title, channel: v.channel },
      { meta: `${v.day} · ${minutesText(v.charged_seconds + v.unlocked_seconds)}` },
    )));
    offset += videos.length;
    status.replaceChildren();
    if (videos.length === 50) {
      status.append(h('div', {}, h('button', { onclick: () => withLoading(status, 'Loading…', loadPage) }, 'Load more')));
    }
  }

  const reload = () => {
    offset = 0;
    list.replaceChildren();
    withLoading(status, 'Loading…', loadPage);
  };

  view.replaceChildren(h('div', { class: 'stack' },
    h('h1', {}, 'History'),
    h('form', {
      class: 'filters',
      onsubmit: (event) => {
        event.preventDefault();
        reload();
      },
    },
      h('label', { class: 'grow' }, 'search', q),
      h('label', {}, 'from', start),
      h('label', {}, 'to', end),
      iconButton('funnel', 'Filter', { class: 'primary', type: 'submit' })),
    list,
    status));
  reload();
}

function playlistsPage(importing) {
  const list = h('div', { class: 'stack' });
  const name = h('input', { placeholder: 'New playlist name', 'aria-label': 'Playlist name' });
  const url = h('input', { placeholder: 'YouTube playlist link', 'aria-label': 'YouTube playlist link', value: importing ?? '' });
  const error = h('p', { class: 'problem', role: 'status' });

  const submit = (action) => async (event) => {
    event.preventDefault();
    error.textContent = '';
    const button = event.target.querySelector('button');
    button.disabled = true;
    try {
      const { id } = await action();
      location.hash = `#/playlist/${id}`;
    } catch (e) {
      error.textContent = e.message;
    } finally {
      button.disabled = false;
    }
  };

  const load = () => withLoading(list, 'Loading…', async () => {
    const { playlists } = await api('/api/playlists');
    if (!playlists.length) {
      list.replaceChildren(h('p', { class: 'muted' }, 'No playlists yet.'));
      return;
    }
    list.replaceChildren(h('table', {}, h('tbody', {}, playlists.map((p) => h('tr', {},
      h('td', {}, h('a', { href: `#/playlist/${p.id}` }, p.name)),
      h('td', { class: 'num muted' }, `${p.count} ${p.count === 1 ? 'video' : 'videos'}`))))));
  });

  view.replaceChildren(h('div', { class: 'stack' },
    h('h1', {}, 'Playlists'),
    h('form', {
      class: 'search',
      onsubmit: submit(() => api('/api/playlists', { method: 'POST', body: { name: name.value } })),
    }, name, iconButton('plus', 'Create playlist', { class: 'primary', type: 'submit' })),
    h('form', {
      class: 'search',
      onsubmit: submit(() => api('/api/playlists/import', { method: 'POST', body: { url: url.value } })),
    }, url, iconButton('import', 'Import playlist', { type: 'submit' })),
    error,
    list));
  load();
}

function playlistPage(id) {
  const body = h('div', { class: 'stack' });
  view.replaceChildren(body);
  const load = () => withLoading(body, 'Loading…', async () => {
    const playlist = await api(`/api/playlists/${id}`);
    body.replaceChildren(
      h('div', { class: 'row spread' },
        h('h1', {}, playlist.name),
        iconButton('trash-2', 'Delete playlist', {
          onclick: async () => {
            if (!confirm(`Delete "${playlist.name}"?`)) return;
            await api(`/api/playlists/${id}`, { method: 'DELETE' });
            location.hash = '#/playlists';
          },
        })),
      playlist.items.length
        ? h('div', { class: 'results' }, playlist.items.map((video) => h('div', { class: 'item-row' },
            resultRow(video, { href: `#/watch/${video.id}?list=${id}` }),
            iconButton('x', `Remove ${video.title}`, {
              onclick: async () => {
                await api(`/api/playlists/${id}/items/${video.id}`, { method: 'DELETE' });
                load();
              },
            }))))
        : h('p', { class: 'muted' }, 'Nothing here yet. Open a video and add it from there.'),
    );
  });
  load();
}

const FIELDS = [
  ['daily_minutes', 'Minutes per day'],
  ['unlocks_per_day', 'Unlocks per day'],
  ['unlock_minutes', 'Minutes per unlock'],
  ['unlock_wait_seconds', 'Seconds to wait before an unlock'],
];

function drawSettings(container) {
  withLoading(container, 'Loading…', async () => {
    const data = await api('/api/settings');
    const inputs = {};
    const note = h('p', { role: 'status', class: 'muted' },
      `Tighter limits apply now. Looser limits wait ${data.cooling_off_hours} hours.`);
    const fields = FIELDS.map(([key, label]) => {
      const pending = data.pending.find((p) => p.key === key);
      inputs[key] = h('input', {
        type: 'number',
        min: 0,
        value: pending ? pending.value : data.settings[key],
        id: key,
      });
      return h('label', { class: 'field', for: key }, h('span', {}, label), inputs[key]);
    });
    const pending = data.pending.map((change) =>
      h('div', { class: 'row spread' },
        h('span', {}, `${FIELDS.find(([k]) => k === change.key)[1]} → ${change.value}, on ${new Date(change.apply_at * 1000).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' })}`),
        iconButton('x', 'Cancel this change', {
          onclick: async () => {
            await api(`/api/settings/pending/${change.id}`, { method: 'DELETE' });
            drawSettings(container);
          },
        })));
    fill(container,
      h('form', {
        class: 'panel stack',
        onsubmit: async (event) => {
          event.preventDefault();
          const changes = {};
          for (const [key] of FIELDS) changes[key] = Number(inputs[key].value);
          try {
            await api('/api/settings', { method: 'POST', body: { changes } });
            await refreshStatus();
            drawSettings(container);
          } catch (error) {
            note.textContent = error.message;
            note.className = 'problem';
          }
        },
      }, fields, note, h('div', {}, h('button', { class: 'primary', type: 'submit' }, 'Save'))),
      pending.length
        ? h('section', { class: 'panel stack' }, h('h3', {}, 'Waiting to apply'), pending)
        : null,
    );
  });
}

// ---- player ----

function watchPage(id, listId, startAt) {
  const body = h('div', { class: 'stack' }, h('p', { class: 'muted' }, 'Loading video…'));
  view.replaceChildren(body);
  let player = null;
  let gone = false;
  leaveView = () => {
    gone = true;
    if (player) park(player);
  };
  stage.classList.remove('floating');
  stage.inert = false;
  if (floating) {
    const kept = floating;
    floating = null;
    if (kept.id === id) {
      player = kept;
      kept.show(body);
      if (startAt !== null) kept.el.currentTime = startAt;
      return;
    }
    kept.destroy();
  }
  api(`/api/video/${id}`).then(
    async (video) => {
      if (gone) return;
      const { subscriptions } = await api('/api/subscriptions');
      if (gone) return;
      player = buildPlayer(body, video, subscriptions, listId, startAt);
    },
    (error) => {
      if (gone) return;
      if (error.code === 402) {
        const panel = outOfTimePanel();
        body.replaceChildren(panel);
        onStatus = (s) => {
          if (s.can_watch) route();
          else panel.redraw();
        };
      } else {
        body.replaceChildren(failure(error));
      }
    },
  );
}

// Leaving the watch page ends playback, unless the video is in its own window.
function park(player) {
  if (document.pictureInPictureElement === player.el) {
    floating = player;
    stage.classList.add('floating');
    stage.inert = true;
  } else {
    player.destroy();
  }
}

// Saved per device, because the right volume depends on the speakers.
function rememberVolume(el) {
  const saved = localStorage.getItem('volume');
  if (saved !== null) el.volume = Math.min(1, Math.max(0, Number(saved)));
  el.muted = localStorage.getItem('muted') === 'true';
  el.addEventListener('volumechange', () => {
    localStorage.setItem('volume', String(el.volume));
    localStorage.setItem('muted', String(el.muted));
  });
}

const YOUTUBE_LINK = /^https?:\/\/(?:(?:www|m|music)\.)?(?:youtube\.com|youtu\.be)\//i;

function linkTo(url) {
  // A link to YouTube stays in the app.
  return YOUTUBE_LINK.test(url)
    ? h('a', { href: `/share?url=${encodeURIComponent(url)}` }, url)
    : h('a', { href: url, target: '_blank', rel: 'noopener noreferrer' }, url);
}

function timestampSeconds(text) {
  const parts = text.split(':').map(Number);
  if (parts.slice(1).some((n) => n >= 60)) return null;
  return parts.reduce((total, n) => total * 60 + n, 0);
}

// `player` is what timestamps act on: { duration, seek }.
function linkified(text, player) {
  return text.split(/(https?:\/\/\S+|(?<![\w:.])(?:\d{1,2}:)?\d{1,2}:\d{2}(?![\w:]))/).map((part, i) => {
    if (!(i % 2)) return part;
    if (/^https?:/i.test(part)) return linkTo(part);
    const seconds = timestampSeconds(part);
    if (seconds === null || seconds > player.duration) return part;
    return h('a', {
      href: `#/watch/${player.id}?t=${seconds}`,
      onclick: (event) => {
        event.preventDefault();
        player.seek(seconds);
      },
    }, part);
  });
}

function descriptionSection(text, player) {
  if (!text.trim()) return null;
  return h('details', { class: 'panel' },
    h('summary', {}, 'Description'),
    h('p', { class: 'prose' }, linkified(text, player)));
}

function commentsSection(player) {
  const box = h('div', { class: 'stack' });
  let loaded = false;
  return h('details', {
    class: 'panel',
    ontoggle: (event) => {
      if (loaded || !event.target.open) return;
      loaded = true;
      withLoading(box, 'Loading comments…', async () => {
        const { comments } = await api(`/api/video/${player.id}/comments`);
        if (!comments.length) {
          box.replaceChildren(h('p', { class: 'muted' }, 'No comments.'));
          return;
        }
        box.replaceChildren(...comments.map((c) =>
          h('article', { class: 'comment' },
            h('div', { class: 'row' },
              h('span', { class: 'title' }, c.author),
              h('span', { class: 'muted' }, [c.pinned && 'pinned', c.when, c.likes && `${c.likes.toLocaleString()} likes`].filter(Boolean).join(' · '))),
            h('p', { class: 'prose' }, linkified(c.text, player)))));
      });
    },
  }, h('summary', {}, 'Comments'), box);
}

function addToPlaylist(video) {
  const note = h('span', { class: 'muted', role: 'status' });
  const items = h('div', { class: 'menu-items' });
  const name = h('input', { placeholder: 'New playlist', 'aria-label': 'New playlist name', autocomplete: 'off' });
  let busy = false;

  const add = async (id, label) => {
    await api(`/api/playlists/${id}/items`, {
      method: 'POST',
      body: { id: video.id, title: video.title, channel: video.channel, duration: video.duration },
    });
    note.textContent = `Added to ${label}`;
  };

  const attempt = async (action) => {
    if (busy) return;
    busy = true;
    try {
      await action();
      close();
    } catch (error) {
      note.textContent = error.message;
    } finally {
      busy = false;
    }
  };

  const menu = h('div', { class: 'menu', hidden: true },
    items,
    h('form', {
      class: 'search',
      onsubmit: (event) => {
        event.preventDefault();
        attempt(async () => {
          const { id } = await api('/api/playlists', { method: 'POST', body: { name: name.value } });
          await add(id, name.value.trim());
          name.value = '';
        });
      },
    }, name, iconButton('plus', 'Create playlist and add this video', { class: 'primary', type: 'submit' })));

  const toggle = iconButton('list-plus', 'Add to playlist', {
    'aria-expanded': 'false',
    onclick: () => (menu.hidden ? open() : close()),
  });
  const picker = h('div', {}, toggle, menu);

  const onOutside = (event) => {
    if (!picker.contains(event.target)) close();
  };
  const onKey = (event) => {
    if (event.key === 'Escape') close();
  };

  function close() {
    menu.hidden = true;
    toggle.setAttribute('aria-expanded', 'false');
    document.removeEventListener('pointerdown', onOutside);
    document.removeEventListener('keydown', onKey);
  }

  function open() {
    menu.hidden = false;
    note.textContent = '';
    toggle.setAttribute('aria-expanded', 'true');
    document.addEventListener('pointerdown', onOutside);
    document.addEventListener('keydown', onKey);
    api('/api/playlists').then(({ playlists }) => {
      items.replaceChildren(...playlists.map((p) => h('button', {
        type: 'button',
        class: 'menu-item',
        onclick: () => attempt(() => add(p.id, p.name)),
      }, p.name)));
    }, (error) => { note.textContent = error.message; });
  }

  return [picker, note];
}

// A link, never a timer: moving on stays a decision.
function nextInPlaylist(videoId, listId, onFound) {
  const box = h('div', {});
  if (!listId) return box;
  api(`/api/playlists/${listId}`).then((playlist) => {
    const index = playlist.items.findIndex((item) => item.id === videoId);
    const next = playlist.items[index + 1];
    if (index < 0 || !next) return;
    const link = () => h('a', { href: `#/watch/${next.id}?list=${listId}` }, `Next in ${playlist.name}: ${next.title}`);
    box.append(link());
    onFound(link);
  }).catch(() => {});
  return box;
}

function setPressed(button, on) {
  button.setAttribute('aria-pressed', String(on));
}

function buildPlayer(body, video, subscriptions, listId, startAt) {
  const positionKey = `position:${video.id}`;
  const el = h('video', { controls: true, playsInline: true, preload: 'auto' });
  rememberVolume(el);
  const notice = h('div', { class: 'stack' });
  const qualitySelect = h('select', { 'aria-label': 'Quality' }, h('option', { value: '-1' }, 'auto'));
  const speedSelect = h('select', { 'aria-label': 'Speed' },
    ['1', '1.25', '1.5', '1.75', '2'].map((v) => h('option', { value: v }, `${v}x`)));
  const audioToggle = iconButton('headphones', 'Audio only');
  const theaterToggle = iconButton('rectangle-horizontal', 'Theater', { class: 'theater-toggle' });
  const pipToggle = document.pictureInPictureEnabled
    ? iconButton('picture-in-picture-2', 'Picture in picture')
    : null;
  const timestamps = {
    id: video.id,
    duration: video.duration,
    seek: (seconds) => {
      el.currentTime = seconds;
      el.play().catch(() => {});
      window.scrollTo(0, 0);
    },
  };

  let nextLink = null;
  const details = (
    h('div', { class: 'stack' },
      h('h2', {}, video.title),
      h('div', { class: 'row spread' },
        video.channel_id
          ? h('a', { href: `#/channel/${video.channel_id}` }, video.channel)
          : h('span', { class: 'muted' }, video.channel),
        video.channel_id
          ? subscribeButton(video.channel_id, video.channel, subscriptions.some((c) => c.channel_id === video.channel_id))
          : null),
      h('div', { class: 'row player-tools' },
        speedSelect,
        qualitySelect,
        audioToggle,
        pipToggle,
        theaterToggle,
        addToPlaylist(video)),
      nextInPlaylist(video.id, listId, (link) => { nextLink = link; }),
      descriptionSection(video.description, timestamps),
      commentsSection(timestamps),
    )
  );
  stage.replaceChildren(el);
  body.replaceChildren(notice, details);

  let hls = null;
  let statusHandler = () => {};
  let audioOnly = false;
  let blocked = false;

  function showOutOfTime() {
    if (blocked) return;
    blocked = true;
    const panel = outOfTimePanel();
    notice.replaceChildren(panel);
    statusHandler = (s) => {
      if (s.can_watch) {
        blocked = false;
        notice.replaceChildren();
        statusHandler = () => {};
        onStatus = statusHandler;
        const resumeAt = el.currentTime;
        load(resumeAt, true);
      } else {
        panel.redraw();
      }
    };
    // Out of time in the mini window: close it, which brings this page back.
    if (floating === player) document.exitPictureInPicture().catch(() => {});
    else onStatus = statusHandler;
  }

  function load(startAt, autoplay) {
    const source = audioOnly
      ? `/media/${video.id}/${video.audio_format}/index.m3u8`
      : `/media/${video.id}/master.m3u8`;
    hls?.destroy();
    hls = null;
    if (window.Hls?.isSupported()) {
      hls = new window.Hls({
        // Everything buffered is charged, so keep the lead short.
        maxBufferLength: 12,
        maxMaxBufferLength: 20,
        backBufferLength: 30,
        capLevelToPlayerSize: true,
        startPosition: startAt,
      });
      hls.on(window.Hls.Events.MANIFEST_PARSED, (_, data) => {
        qualitySelect.disabled = audioOnly;
        qualitySelect.replaceChildren(h('option', { value: '-1' }, 'auto'),
          ...(audioOnly ? [] : data.levels.map((level, i) => h('option', { value: String(i) }, `${level.height}p`)).reverse()));
        if (autoplay) el.play().catch(() => {});
      });
      hls.on(window.Hls.Events.ERROR, (_, data) => {
        if (data.response?.code === 402) {
          hls.stopLoad();
          showOutOfTime();
        } else if (data.fatal) {
          notice.replaceChildren(failure(new Error('Playback failed. YouTube may have refused this stream.')));
        }
      });
      hls.loadSource(source);
      hls.attachMedia(el);
    } else {
      // Browsers without MSE (iPhone Safari) play HLS natively.
      el.src = source;
      el.currentTime = startAt;
      if (autoplay) el.play().catch(() => {});
    }
    el.playbackRate = Number(speedSelect.value);
  }

  qualitySelect.addEventListener('change', () => {
    if (hls) hls.currentLevel = Number(qualitySelect.value);
  });
  speedSelect.addEventListener('change', () => {
    el.playbackRate = Number(speedSelect.value);
  });
  audioToggle.addEventListener('click', () => {
    audioOnly = !audioOnly;
    setPressed(audioToggle, audioOnly);
    load(el.currentTime, !el.paused);
  });

  const applyTheater = (on) => {
    stage.classList.toggle('theater', on);
    setPressed(theaterToggle, on);
    localStorage.setItem('theater', String(on));
  };
  applyTheater(localStorage.getItem('theater') === 'true');
  theaterToggle.addEventListener('click', () => applyTheater(!stage.classList.contains('theater')));

  pipToggle?.addEventListener('click', async () => {
    try {
      if (document.pictureInPictureElement === el) await document.exitPictureInPicture();
      else await el.requestPictureInPicture();
    } catch {
      notice.replaceChildren(failure(new Error(audioOnly
        ? 'Picture in picture needs video. Turn off audio only first.'
        : "Your browser wouldn't open picture in picture.")));
    }
  });
  el.addEventListener('enterpictureinpicture', () => {
    if (pipToggle) setPressed(pipToggle, true);
  });
  el.addEventListener('leavepictureinpicture', () => {
    if (pipToggle) setPressed(pipToggle, false);
    if (floating !== player) return;
    // The window's X pauses the video. "Back to tab" and running out of time don't.
    if (el.paused && !blocked) {
      floating = null;
      player.destroy();
    } else {
      location.hash = `#/watch/${video.id}`;
    }
  });

  // Nobody needs 1080p with the screen off, so drop to the lowest track until they're back.
  // A mini window is still on screen when the tab is hidden, so it keeps its quality.
  const onVisibility = () => {
    if (!hls || audioOnly) return;
    const unseen = document.hidden && document.pictureInPictureElement !== el;
    hls.autoLevelCapping = unseen ? 0 : -1;
  };
  document.addEventListener('visibilitychange', onVisibility);

  let lastSaved = 0;
  el.addEventListener('timeupdate', () => {
    if (Math.abs(el.currentTime - lastSaved) > 5) {
      lastSaved = el.currentTime;
      localStorage.setItem(positionKey, String(el.currentTime));
    }
  });
  el.addEventListener('ended', () => {
    localStorage.removeItem(positionKey);
    if (floating === player) document.exitPictureInPicture().catch(() => {});
    // The end of a video is the end. There is no next video.
    notice.replaceChildren(h('section', { class: 'panel stack' },
      h('h3', {}, 'That was the video.'),
      nextLink && h('div', {}, nextLink()),
      h('div', {}, h('a', { href: '#/' }, 'Back to search'))));
  });

  if ('mediaSession' in navigator) {
    navigator.mediaSession.metadata = new MediaMetadata({ title: video.title, artist: video.channel, album: 'anti-social' });
    const session = navigator.mediaSession;
    session.setActionHandler('play', () => el.play());
    session.setActionHandler('pause', () => el.pause());
    session.setActionHandler('seekbackward', () => { el.currentTime = Math.max(0, el.currentTime - 10); });
    session.setActionHandler('seekforward', () => { el.currentTime += 10; });
    session.setActionHandler('seekto', (details) => { el.currentTime = details.seekTime; });
  }

  const saved = Number(localStorage.getItem(positionKey) ?? 0);
  load(startAt ?? (saved > 5 ? saved : -1), true);

  onStatus = () => {};
  const player = {
    id: video.id,
    el,
    show(newBody) {
      newBody.replaceChildren(notice, details);
      onStatus = statusHandler;
    },
    destroy() {
      document.removeEventListener('visibilitychange', onVisibility);
      hls?.destroy();
      el.pause();
      el.removeAttribute('src');
      el.load();
      el.remove();
      stage.classList.remove('floating', 'theater');
      stage.inert = false;
    },
  };
  return player;
}

// ---- router ----

function route() {
  leaveView();
  leaveView = () => {};
  onStatus = () => {};
  const [path, query] = location.hash.replace(/^#\/?/, '').split('?');
  const parts = path.split('/');
  const [page, arg] = [parts[0], parts.slice(1).join('/')];
  const params = new URLSearchParams(query);
  const sections = { channel: 'channels', channels: 'channels', usage: 'usage', history: 'history', playlists: 'playlists', playlist: 'playlists', watch: '' };
  const section = sections[page] ?? 'search';
  for (const link of document.querySelectorAll('[data-nav]')) {
    link.classList.toggle('active', link.dataset.nav === section);
  }
  window.scrollTo(0, 0);
  if (page === 'search') searchPage(decodeURIComponent(arg));
  else if (page === 'watch') watchPage(arg, params.get('list'), params.has('t') ? Number(params.get('t')) : null);
  else if (page === 'channels') channelsPage();
  else if (page === 'channel') channelPage(arg);
  else if (page === 'usage') usagePage();
  else if (page === 'history') historyPage();
  else if (page === 'playlists') playlistsPage(params.get('import'));
  else if (page === 'playlist') playlistPage(arg);
  else searchPage(null);
}

window.addEventListener('hashchange', route);
setInterval(refreshStatus, 5000);
await refreshStatus();
route();
