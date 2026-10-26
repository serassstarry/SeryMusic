"use strict";

// ---------- Helpers ----------

const $ = (selector) => document.querySelector(selector);

/** Builds an element. Text always goes through textContent, never parsed as HTML. */
function el(tag, attrs = {}, children = []) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value == null || value === false) continue;
    if (key === "class") node.className = value;
    else if (key === "text") node.textContent = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? "" : value);
  }
  for (const child of [].concat(children)) {
    if (child != null && child !== false) node.append(child);
  }
  return node;
}

const ICONS = {
  heart: "M12 21 3.5 12.6A5.3 5.3 0 0 1 12 6a5.3 5.3 0 0 1 8.5 6.6z",
  more: "M12 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 6a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 6a2 2 0 1 0 0-4 2 2 0 0 0 0 4z",
  play: "M8 5v14l11-7z",
  pause: "M6 5h4v14H6zm8 0h4v14h-4z",
  close: "m6.4 5 5.6 5.6L17.6 5 19 6.4 13.4 12l5.6 5.6-1.4 1.4-5.6-5.6L6.4 19 5 17.6 10.6 12 5 6.4z",
  shuffle: "M16 3h5v5l-1.8-1.8-4.3 4.3-1.4-1.4 4.3-4.3zM3 5.4 4.4 4l15.4 15.4V16h1.5v5h-5v-1.5h3.4zM10.6 13.4l1.4 1.4-6.6 6.6L4 20z",
  playlist: "M3 5h12v2H3zm0 4h12v2H3zm0 4h8v2H3zm14-8h4v2h-2v9.5a2.5 2.5 0 1 1-2-2.45z",
  search: "M10 3a7 7 0 0 1 5.6 11.2l5.1 5.1-1.4 1.4-5.1-5.1A7 7 0 1 1 10 3zm0 2a5 5 0 1 0 0 10 5 5 0 0 0 0-10z",
  plus: "M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6z",
  radio: "M4 8h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1zm4 3a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5zm6 1v2h4v-2zM6 7l10-4 .7 1.9L11.7 7z",
  left: "M15.4 7.4 14 6l-6 6 6 6 1.4-1.4L10.8 12z",
  right: "M8.6 16.6 10 18l6-6-6-6-1.4 1.4 4.6 4.6z",
  music: "M12 3v10.6A4 4 0 1 0 14 17V7h4V3z",
};

function icon(name) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
  path.setAttribute("d", ICONS[name]);
  svg.append(path);
  return svg;
}

function formatTime(seconds) {
  if (!isFinite(seconds) || seconds < 0) seconds = 0;
  seconds = Math.floor(seconds);
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = String(seconds % 60).padStart(2, "0");
  return h ? `${h}:${String(m).padStart(2, "0")}:${s}` : `${m}:${s}`;
}

function shuffleInPlace(list) {
  for (let i = list.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [list[i], list[j]] = [list[j], list[i]];
  }
  return list;
}

const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;

/** YouTube Music names release types in English. */
const releaseType = (type) => ({ Album: "Álbum", Single: "Single", EP: "EP", Audiobook: "Audiolibro" })[type] || type || "";

/** Total length of a list of songs, e.g. "1 h 4 min" or "38 min". */
function totalLength(songs) {
  const minutes = Math.round(songs.reduce((sum, song) => sum + (song.duration || 0), 0) / 60);
  return minutes >= 60 ? `${Math.floor(minutes / 60)} h ${minutes % 60} min` : `${minutes} min`;
}
const cssUrl = (url) => `url("${encodeURI(url)}")`;

let toastTimer;
function toast(message) {
  const node = $("#toast");
  node.textContent = message;
  node.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (node.hidden = true), 3500);
}

// Per-device settings. Storage can be unavailable (private mode), the app works without it.
const local = {
  get(key, fallback) {
    try { const v = localStorage.getItem(key); return v == null ? fallback : JSON.parse(v); } catch { return fallback; }
  },
  set(key, value) {
    try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* ignored */ }
  },
};

async function api(path, { method = "GET", body } = {}) {
  const response = await fetch(path, {
    method,
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: "same-origin",
  });
  if (response.status === 401) {
    location.href = "/login";
    throw new Error("Sesión caducada");
  }
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || `Error ${response.status}`);
  return data;
}

// ---------- Library state ----------

const favorites = new Set();

async function loadFavorites() {
  const { ids } = await api("/api/favorites/ids");
  favorites.clear();
  ids.forEach((id) => favorites.add(id));
  refreshLikes();
}

function refreshLikes() {
  document.querySelectorAll("[data-like]").forEach((button) => {
    button.classList.toggle("liked", favorites.has(button.dataset.like));
  });
  const current = player.current();
  const liked = !!current && favorites.has(current.id);
  $("#like").classList.toggle("liked", liked);
  $("#fp-like").classList.toggle("liked", liked);
}

async function toggleFavorite(song) {
  const liked = favorites.has(song.id);
  liked ? favorites.delete(song.id) : favorites.add(song.id);
  refreshLikes();
  try {
    if (liked) await api(`/api/favorites/${song.id}`, { method: "DELETE" });
    else await api(`/api/favorites/${song.id}`, { method: "PUT", body: song });
    if (route.name === "favorites") render();
  } catch (error) {
    liked ? favorites.add(song.id) : favorites.delete(song.id);
    refreshLikes();
    toast(error.message);
  }
}

async function addToPlaylist(playlistId, song) {
  try {
    await api(`/api/playlists/${playlistId}/songs`, { method: "POST", body: song });
    toast("Añadida a la playlist");
  } catch (error) {
    toast(error.message);
  }
}

async function createPlaylist() {
  const name = prompt("Nombre de la playlist");
  if (!name || !name.trim()) return null;
  try {
    const { id } = await api("/api/playlists", { method: "POST", body: { name: name.trim() } });
    return id;
  } catch (error) {
    toast(error.message);
    return null;
  }
}

// ---------- Artists & albums ----------

/** Artists of a song (or album). Songs saved before artists were linked only have names. */
function songArtists(item) {
  if (item.artists && item.artists.length) return item.artists;
  return (item.artist || "").split(/,\s*/).filter(Boolean).map((name) => ({ name, id: null }));
}

/** Opens an artist page, looking the artist up by name when its id isn't known. */
async function openArtist(artist) {
  if (artist.id) {
    location.hash = `#/artist/${artist.id}`;
    return;
  }
  try {
    const { id } = await api(`/api/artist-by-name?name=${encodeURIComponent(artist.name)}`);
    location.hash = `#/artist/${id}`;
  } catch {
    toast(`No se encontró la página de ${artist.name}`);
  }
}

/** Artist names as links, each one opens its page. */
function artistLinks(item) {
  const span = el("span", { class: "artist-links" });
  songArtists(item).forEach((artist, i) => {
    if (i) span.append(", ");
    span.append(el("a", {
      class: "link", text: artist.name, href: artist.id ? `#/artist/${artist.id}` : "#",
      onclick: (event) => {
        event.stopPropagation();
        if (!artist.id) { event.preventDefault(); openArtist(artist); }
        if (!$("#fullplayer").hidden) fullplayer.close();
      },
    }));
  });
  return span;
}

function albumLink(song) {
  if (!song.album) return null;
  if (!song.album_id) return el("span", { text: song.album });
  return el("a", { class: "link", href: `#/album/${song.album_id}`, text: song.album, onclick: (e) => e.stopPropagation() });
}

async function playAlbum(id, shuffle = false) {
  try {
    const album = await api(`/api/album/${id}`);
    if (!album.songs.length) return toast("Este álbum no tiene canciones disponibles");
    player.playList(shuffle ? shuffleInPlace([...album.songs]) : album.songs, 0);
  } catch (error) {
    toast(error.message);
  }
}

async function playArtist(id) {
  try {
    const artist = await api(`/api/artist/${id}`);
    if (!artist.songs.length) return toast("No hay canciones de este artista");
    player.playList(artist.songs, 0);
    player.appendRadio(artist.songs[0]);
  } catch (error) {
    toast(error.message);
  }
}

// ---------- Context menu ----------

function closeMenu() {
  $("#menu").hidden = true;
}

function openMenu(anchor, items) {
  const menu = $("#menu");
  menu.replaceChildren(...items.map((item) =>
    item.label
      ? el("div", { class: "menu-label", text: item.label })
      : el("button", { text: item.text, onclick: () => { closeMenu(); item.action(); } })
  ));
  menu.hidden = false;
  const rect = anchor.getBoundingClientRect();
  const width = menu.offsetWidth, height = menu.offsetHeight;
  menu.style.left = `${Math.max(8, Math.min(rect.right - width, innerWidth - width - 8))}px`;
  menu.style.top = `${rect.bottom + height + 8 > innerHeight ? Math.max(8, rect.top - height) : rect.bottom}px`;
}

document.addEventListener("click", (event) => {
  if (!$("#menu").hidden && !event.target.closest("#menu") && !event.target.closest("[data-menu]")) closeMenu();
});

async function songMenu(anchor, song, context = {}) {
  const items = [
    { text: "Reproducir a continuación", action: () => player.playNext(song) },
    { text: "Añadir a la cola", action: () => player.enqueue(song) },
    { text: "Iniciar radio", action: () => player.startRadio(song) },
  ];
  const [firstArtist] = songArtists(song);
  if (firstArtist) items.push({ text: `Ir a ${firstArtist.name}`, action: () => openArtist(firstArtist) });
  if (song.album_id) items.push({ text: "Ir al álbum", action: () => { location.hash = `#/album/${song.album_id}`; } });
  if (context.playlistId != null) {
    items.push({ text: "Quitar de esta playlist", action: () => context.onRemove(song) });
  }
  items.push({ label: "Añadir a playlist" });
  items.push({
    text: "Nueva playlist…",
    action: async () => {
      const id = await createPlaylist();
      if (id != null) addToPlaylist(id, song);
    },
  });
  openMenu(anchor, items);
  try {
    const { playlists } = await api("/api/playlists");
    if ($("#menu").hidden) return;
    openMenu(anchor, items.concat(playlists
      .filter((p) => p.id !== context.playlistId)
      .map((p) => ({ text: p.name, action: () => addToPlaylist(p.id, song) }))));
  } catch { /* menu still works without the list */ }
}

// ---------- Building blocks ----------

function image(url, className = "") {
  return url
    ? el("img", { class: className, src: url, alt: "", loading: "lazy", referrerpolicy: "no-referrer" })
    : el("div", { class: `${className} art-fallback` }, icon("music"));
}

/** Little animated bars shown on the song playing. */
const equalizer = () => el("span", { class: "eq", "aria-hidden": "true" }, [el("i"), el("i"), el("i")]);

/**
 * @param songs list being displayed, clicking a song plays the whole list from that song
 * @param context `{ playlistId, onRemove }` in a playlist, `numbered` for track numbers, `hideAlbum`
 */
function songList(songs, context = {}) {
  if (!songs.length) return el("p", { class: "empty", text: context.emptyText || "No hay canciones." });
  return el("div", { class: "songs" }, songs.map((song, index) => {
    const details = [artistLinks(song)];
    if (!context.hideAlbum && song.album) details.push(" · ", albumLink(song));
    return el("div", { class: "song", "data-song": song.id, onclick: (event) => {
      if (event.target.closest("button, a")) return;
      player.playList(songs, index);
    } }, [
      el("div", { class: `song-art${context.numbered ? " numbered" : ""}` }, [
        context.numbered ? el("span", { class: "track-number", text: String(index + 1) }) : image(song.thumbnail),
        el("span", { class: "row-play" }, icon("play")),
        equalizer(),
      ]),
      el("div", { class: "meta" }, [
        el("div", { class: "title", text: song.title }),
        el("div", { class: "subtitle" }, details),
      ]),
      el("span", { class: "duration", text: song.duration ? formatTime(song.duration) : "" }),
      el("div", { class: "actions" }, [
        el("button", { class: `icon-btn${favorites.has(song.id) ? " liked" : ""}`, "data-like": song.id, title: "Favorito",
          onclick: () => toggleFavorite(song) }, icon("heart")),
        el("button", { class: "icon-btn", title: "Más", "data-menu": true,
          onclick: (event) => songMenu(event.currentTarget, song, context) }, icon("more")),
      ]),
    ]);
  }));
}

/** Square (or round) artwork with a title, and a play button appearing on hover. */
function card({ href = "#", art, title, subtitle, round = false, onPlay, onOpen }) {
  return el("a", { class: `card${round ? " round" : ""}`, href, onclick: (event) => {
    if (onOpen) { event.preventDefault(); onOpen(); }
  } }, [
    el("div", { class: "card-art" }, [
      typeof art === "string" || art == null ? image(art) : art,
      onPlay ? el("button", { class: "card-play", title: "Reproducir", onclick: (event) => {
        event.preventDefault();
        event.stopPropagation();
        onPlay();
      } }, icon("play")) : null,
    ]),
    el("div", { class: "card-title", text: title }),
    subtitle ? el("div", { class: "card-subtitle", text: subtitle }) : null,
  ]);
}

const albumCard = (album, withArtist = true) => card({
  href: `#/album/${album.id}`, art: album.thumbnail, title: album.title,
  subtitle: (withArtist ? [releaseType(album.type), album.artist] : [album.year, releaseType(album.type)]).filter(Boolean).join(" · "),
  onPlay: () => playAlbum(album.id),
});

const artistCard = (artist) => card({
  href: `#/artist/${artist.id}`, art: artist.thumbnail, title: artist.name, round: true,
  subtitle: artist.subscribers ? `${artist.subscribers} suscriptores` : "Artista",
  onPlay: () => playArtist(artist.id),
});

const songCard = (songs, index) => card({
  art: songs[index].thumbnail, title: songs[index].title, subtitle: songs[index].artist,
  onOpen: () => player.playList(songs, index), onPlay: () => player.playList(songs, index),
});

function playlistArt(thumbnail) {
  return thumbnail ? image(thumbnail) : el("div", { class: "art-fallback gradient" }, icon("playlist"));
}

/** Horizontal row of cards, scrollable, with arrows on big screens. */
function shelf(title, items, more = null) {
  if (!items.length) return null;
  const track = el("div", { class: "shelf-track" }, items);
  const scroll = (direction) => track.scrollBy({ left: direction * track.clientWidth * 0.85, behavior: "smooth" });
  return el("section", { class: "shelf" }, [
    el("div", { class: "section-head" }, [
      el("h2", { text: title }),
      more,
      el("div", { class: "shelf-arrows" }, [
        el("button", { class: "icon-btn", title: "Anterior", onclick: () => scroll(-1) }, icon("left")),
        el("button", { class: "icon-btn", title: "Siguiente", onclick: () => scroll(1) }, icon("right")),
      ]),
    ]),
    track,
  ]);
}

function section(title, content, more = null) {
  return el("section", { class: "block" }, [el("div", { class: "section-head" }, [el("h2", { text: title }), more]), content]);
}

const seeAll = (href, text = "Ver todo") => el("a", { class: "see-all", href, text });

function button(text, iconName, onclick, primary = false) {
  return el("button", { class: `btn${primary ? " primary" : ""}`, onclick }, [iconName ? icon(iconName) : null, text]);
}

/** Big page header with the artwork blurred behind. */
function hero({ backdrop, art, round = false, kicker, title, meta, actions = [] }) {
  const node = el("header", { class: `hero${backdrop ? " with-image" : ""}` }, [
    el("div", { class: "hero-bg" }),
    art ? el("div", { class: `hero-art${round ? " round" : ""}` }, typeof art === "string" ? image(art) : art) : null,
    el("div", { class: "hero-text" }, [
      kicker ? el("div", { class: "kicker", text: kicker }) : null,
      el("h1", { class: "hero-title", text: title }),
      meta ? el("div", { class: "hero-meta" }, meta) : null,
      actions.length ? el("div", { class: "hero-actions" }, actions) : null,
    ]),
  ]);
  if (backdrop) node.style.setProperty("--hero-image", cssUrl(backdrop));
  return node;
}

function highlightPlaying() {
  const current = player.current();
  document.querySelectorAll(".view [data-song]").forEach((row) => {
    row.classList.toggle("playing", !!current && row.dataset.song === current.id);
  });
}

// ---------- Views ----------

const route = { name: "home", params: {} };
let renderToken = 0;

function parseRoute() {
  const [path, query = ""] = location.hash.replace(/^#/, "").split("?");
  const parts = path.split("/").filter(Boolean);
  route.params = Object.fromEntries(new URLSearchParams(query));
  if (parts[0] === "playlist" && /^\d+$/.test(parts[1] || "")) {
    route.name = "playlist";
    route.params.id = Number(parts[1]);
  } else if ((parts[0] === "artist" || parts[0] === "album") && /^[A-Za-z0-9_-]+$/.test(parts[1] || "")) {
    route.name = parts[0];
    route.params.id = parts[1];
  } else {
    route.name = ["search", "favorites", "playlists"].includes(parts[0]) ? parts[0] : "home";
  }
  document.querySelectorAll("[data-nav]").forEach((link) => {
    const active = link.dataset.nav === route.name || (route.name === "playlist" && link.dataset.nav === "playlists");
    link.classList.toggle("active", active);
  });
}

function greeting() {
  const hour = new Date().getHours();
  if (hour >= 6 && hour < 13) return "Buenos días";
  if (hour >= 13 && hour < 21) return "Buenas tardes";
  return "Buenas noches";
}

const views = {
  async home(show) {
    const [history, favs, lists] = await Promise.all([
      api("/api/history?limit=24"), api("/api/favorites"), api("/api/playlists"),
    ]);
    const recent = history.songs;
    const nothingYet = !recent.length && !favs.songs.length && !lists.playlists.length;
    show(
      el("header", { class: "home-head" }, [
        el("h1", { text: greeting() }),
        el("div", { class: "device-line" }, [
          el("span", { text: `Este dispositivo se llama «${device.name}».` }),
          el("button", { text: "Cambiar nombre", onclick: () => device.rename() }),
        ]),
      ]),
      recent.length ? el("div", { class: "quick-grid" }, recent.slice(0, 6).map((song, index) =>
        el("button", { class: "quick-tile", "data-song": song.id, onclick: () => player.playList(recent, index) }, [
          image(song.thumbnail), el("span", { class: "title", text: song.title }), equalizer(),
        ]))) : null,
      nothingYet ? el("div", { class: "welcome" }, [
        el("h2", { text: "Tu música, en cualquier dispositivo" }),
        el("p", { text: "Busca una canción, un artista o un álbum para empezar." }),
        el("a", { class: "btn primary", href: "#/search" }, [icon("search"), "Buscar música"]),
      ]) : null,
      shelf("Escuchado recientemente", recent.map((_, i) => songCard(recent, i))),
      shelf("Tus favoritos", favs.songs.slice(0, 20).map((_, i) => songCard(favs.songs, i)), seeAll("#/favorites")),
      shelf("Tus playlists", lists.playlists.map((p) => card({
        href: `#/playlist/${p.id}`, art: playlistArt(p.thumbnail), title: p.name, subtitle: plural(p.count, "canción", "canciones"),
      })), seeAll("#/playlists")),
      recent.length ? el("div", { class: "footer-actions" }, el("button", { class: "btn ghost", text: "Borrar historial", onclick: async () => {
        if (!confirm("¿Borrar el historial de reproducción?")) return;
        await api("/api/history", { method: "DELETE" });
        render();
      } })) : null,
    );
  },

  async search(show, token) {
    const query = route.params.q || "";
    const input = el("input", { type: "search", value: query, placeholder: "Canciones, artistas, álbumes o un enlace de YouTube", "aria-label": "Buscar" });
    const form = el("form", { class: "search-box", onsubmit: (event) => {
      event.preventDefault();
      const q = input.value.trim();
      if (q) location.hash = `#/search?q=${encodeURIComponent(q)}`;
    } }, [icon("search"), input, el("button", { class: "btn primary", type: "submit", text: "Buscar" })]);
    const results = el("div", { class: "results" });
    show(el("h1", { class: "page-title", text: "Buscar" }), form, results);
    if (!query) {
      input.focus();
      results.replaceChildren(el("p", { class: "empty", text: "Escribe el nombre de una canción, un artista o un grupo." }));
      return;
    }
    results.replaceChildren(el("div", { class: "spinner" }));
    const { songs, artists, albums } = await api(`/api/search?q=${encodeURIComponent(query)}`);
    if (token !== renderToken) return;
    if (!songs.length && !artists.length && !albums.length) {
      results.replaceChildren(el("p", { class: "empty", text: "Sin resultados." }));
      return;
    }

    // The artist best matching the search, next to the first songs
    const top = artists[0];
    const topBlock = top ? el("div", { class: "top-results" }, [
      section("Mejor resultado", el("a", { class: "top-card", href: `#/artist/${top.id}` }, [
        el("div", { class: "top-art" }, image(top.thumbnail)),
        el("div", { class: "top-name", text: top.name }),
        el("div", { class: "top-kind", text: top.subscribers ? `Artista · ${top.subscribers} suscriptores` : "Artista" }),
        el("button", { class: "card-play visible", title: "Reproducir", onclick: (event) => {
          event.preventDefault();
          playArtist(top.id);
        } }, icon("play")),
      ])),
      section("Canciones", songList(songs.slice(0, 4))),
    ]) : null;

    const allSongs = top ? songs.slice(4) : songs;
    const songsBox = el("div");
    const showSongs = (limit) => {
      songsBox.replaceChildren(songList(allSongs.slice(0, limit)));
      if (allSongs.length > limit) {
        songsBox.append(el("button", { class: "btn ghost more", text: `Ver más canciones (${allSongs.length - limit})`,
          onclick: () => showSongs(allSongs.length) }));
      }
      highlightPlaying();
    };
    showSongs(8);

    results.replaceChildren(...[
      topBlock,
      allSongs.length ? section(top ? "Más canciones" : "Canciones", songsBox) : null,
      shelf("Artistas", artists.map(artistCard)),
      shelf("Álbumes y singles", albums.map((a) => albumCard(a))),
    ].filter(Boolean));
    highlightPlaying();
  },

  async artist(show, token) {
    const artist = await api(`/api/artist/${route.params.id}`);
    const stats = [
      artist.subscribers && `${artist.subscribers} suscriptores`,
      artist.listeners && `${artist.listeners} oyentes mensuales`,
    ].filter(Boolean).join(" · ");
    const songsBox = el("div", {}, songList(artist.songs, { numbered: true }));
    const loadAll = artist.more_songs ? el("button", { class: "btn ghost more", text: "Ver todas las canciones", onclick: async () => {
      loadAll.disabled = true;
      loadAll.textContent = "Cargando…";
      try {
        const { songs } = await api(`/api/artist/${artist.id}/songs`);
        songsBox.replaceChildren(songList(songs, { numbered: true }));
        loadAll.remove();
        highlightPlaying();
      } catch (error) {
        toast(error.message);
        loadAll.disabled = false;
        loadAll.textContent = "Ver todas las canciones";
      }
    } }) : null;
    const shuffleAll = async () => {
      try {
        const { songs } = artist.more_songs ? await api(`/api/artist/${artist.id}/songs`) : artist;
        player.playList(shuffleInPlace([...songs]), 0);
      } catch (error) {
        toast(error.message);
      }
    };
    const about = artist.description ? el("p", { class: "about clamp", text: artist.description, onclick: (event) => {
      event.currentTarget.classList.toggle("clamp");
    } }) : null;
    if (token !== renderToken) return;
    show(
      hero({
        backdrop: artist.banner, art: artist.thumbnail, round: true, kicker: "Artista", title: artist.name, meta: stats,
        actions: artist.songs.length ? [
          button("Reproducir", "play", () => playArtist(artist.id), true),
          button("Aleatorio", "shuffle", shuffleAll),
          button("Radio", "radio", () => player.startRadio(artist.songs[0])),
        ] : [],
      }),
      artist.songs.length ? section("Éxitos", el("div", {}, [songsBox, loadAll])) : null,
      shelf("Álbumes", artist.albums.map((a) => albumCard(a, false))),
      shelf("Singles y EP", artist.singles.map((a) => albumCard(a, false))),
      shelf("Artistas similares", artist.related.map(artistCard)),
      about ? section("Acerca de", about) : null,
    );
  },

  async album(show) {
    const album = await api(`/api/album/${route.params.id}`);
    show(
      hero({
        backdrop: album.banner, art: album.thumbnail, kicker: releaseType(album.type) || "Álbum", title: album.title,
        meta: [artistLinks(album), album.year ? ` · ${album.year}` : "", ` · ${plural(album.songs.length, "canción", "canciones")}`,
          album.songs.length ? ` · ${totalLength(album.songs)}` : ""],
        actions: album.songs.length ? [
          button("Reproducir", "play", () => player.playList(album.songs, 0), true),
          button("Aleatorio", "shuffle", () => player.playList(shuffleInPlace([...album.songs]), 0)),
        ] : [],
      }),
      songList(album.songs, { numbered: true, hideAlbum: true, emptyText: "Este álbum no tiene canciones disponibles." }),
    );
  },

  async favorites(show) {
    const { songs } = await api("/api/favorites");
    show(
      hero({
        art: el("div", { class: "art-fallback gradient big" }, icon("heart")), kicker: "Tu colección", title: "Favoritos",
        meta: plural(songs.length, "canción", "canciones"),
        actions: songs.length ? [
          button("Reproducir", "play", () => player.playList(songs, 0), true),
          button("Aleatorio", "shuffle", () => player.playList(shuffleInPlace([...songs]), 0)),
        ] : [],
      }),
      songList(songs, { emptyText: "Pulsa el corazón de una canción para guardarla aquí." }),
    );
  },

  async playlists(show) {
    const { playlists } = await api("/api/playlists");
    const create = async () => {
      const id = await createPlaylist();
      if (id != null) location.hash = `#/playlist/${id}`;
    };
    show(
      el("div", { class: "header-row" }, [el("h1", { class: "page-title", text: "Playlists" })]),
      el("div", { class: "grid" }, [
        card({ art: el("div", { class: "art-fallback dashed" }, icon("plus")), title: "Nueva playlist", onOpen: create }),
        ...playlists.map((p) => card({
          href: `#/playlist/${p.id}`, art: playlistArt(p.thumbnail), title: p.name, subtitle: plural(p.count, "canción", "canciones"),
        })),
      ]),
    );
  },

  async playlist(show) {
    const playlist = await api(`/api/playlists/${route.params.id}`);
    const remove = async (song) => {
      await api(`/api/playlists/${playlist.id}/songs/${song.id}`, { method: "DELETE" });
      render();
    };
    const list = songList(playlist.songs, { playlistId: playlist.id, onRemove: remove, emptyText: "Esta playlist está vacía. Añade canciones desde el menú ⋮ de cualquier canción." });
    makeSortable(list, ".song", async (ids) => {
      await api(`/api/playlists/${playlist.id}/order`, { method: "PUT", body: { ids } });
      render();
    });
    const songs = playlist.songs;
    show(
      hero({
        backdrop: songs[0]?.thumbnail, art: playlistArt(songs[0]?.thumbnail), kicker: "Playlist", title: playlist.name,
        meta: plural(songs.length, "canción", "canciones"),
        actions: [
          songs.length ? button("Reproducir", "play", () => player.playList(songs, 0), true) : null,
          songs.length ? button("Aleatorio", "shuffle", () => player.playList(shuffleInPlace([...songs]), 0)) : null,
          button("Renombrar", null, async () => {
            const name = prompt("Nuevo nombre", playlist.name);
            if (!name || !name.trim()) return;
            await api(`/api/playlists/${playlist.id}`, { method: "PATCH", body: { name: name.trim() } });
            render();
          }),
          button("Eliminar", null, async () => {
            if (!confirm(`¿Eliminar la playlist "${playlist.name}"?`)) return;
            await api(`/api/playlists/${playlist.id}`, { method: "DELETE" });
            location.hash = "#/playlists";
          }),
        ].filter(Boolean),
      }),
      list,
    );
  },
};

async function render() {
  const token = ++renderToken;
  const view = $("#view");
  let shown = false;
  const show = (...nodes) => {
    if (token !== renderToken) return;
    shown = true;
    clearTimeout(spinner);
    view.replaceChildren(...nodes.filter(Boolean));
    highlightPlaying();
  };
  // Keep the old page while loading unless it takes a while, avoids flashing a spinner.
  // Pages showing something before loading (search) never get it.
  const spinner = setTimeout(() => {
    if (!shown && token === renderToken) view.replaceChildren(el("div", { class: "spinner" }));
  }, 250);
  try {
    await views[route.name](show, token);
  } catch (error) {
    show(el("p", { class: "empty", text: error.message }));
  } finally {
    clearTimeout(spinner);
  }
  if (token === renderToken) view.scrollTop = 0;
}

/** Drag & drop reordering of `selector` children, [onDrop] gets the new order of `data-song` ids. */
function makeSortable(container, selector, onDrop) {
  let dragged = null;
  container.querySelectorAll(selector).forEach((item) => {
    item.draggable = true;
    item.addEventListener("dragstart", () => { dragged = item; item.classList.add("dragging"); });
    item.addEventListener("dragend", () => {
      item.classList.remove("dragging");
      container.querySelectorAll(".drop-target").forEach((n) => n.classList.remove("drop-target"));
    });
    item.addEventListener("dragover", (event) => {
      if (!dragged || dragged === item) return;
      event.preventDefault();
      container.querySelectorAll(".drop-target").forEach((n) => n.classList.remove("drop-target"));
      item.classList.add("drop-target");
    });
    item.addEventListener("drop", (event) => {
      event.preventDefault();
      if (!dragged || dragged === item) return;
      item.before(dragged);
      dragged = null;
      onDrop([...container.querySelectorAll(selector)].map((n) => n.dataset.song));
    });
  });
}

window.addEventListener("hashchange", () => { closeMenu(); parseRoute(); render(); });

// ---------- Player ----------

const audio = $("#audio");
const CODEC = audio.canPlayType('audio/webm; codecs="opus"') ? "opus" : "aac";

const player = {
  queue: [],
  index: -1,
  repeat: local.get("repeat", "off"), // off | all | one
  failures: 0,
  loadingRadio: false,
  pendingSeek: null,

  current() { return this.queue[this.index] || null; },

  playList(songs, index) {
    this.queue = songs.map((s) => ({ ...s }));
    this.load(index, true);
  },

  load(index, autoplay) {
    const song = this.queue[index];
    if (!song) return;
    this.index = index;
    this.failures = 0;
    this.recorded = false;
    this.restored = false;
    audio.preload = "auto";
    audio.src = `/api/stream/${song.id}?codec=${CODEC}`;
    if (autoplay) tryPlay();
    updateNowPlaying();
    renderPanel();
    saveState();
  },

  toggle() {
    if (!this.current()) return;
    audio.paused ? tryPlay() : audio.pause();
  },

  async next(auto = false) {
    if (!this.queue.length) return;
    if (auto && this.repeat === "one") {
      audio.currentTime = 0;
      tryPlay();
      return;
    }
    if (this.index + 1 < this.queue.length) return this.load(this.index + 1, true);
    if (this.repeat === "all") return this.load(0, true);
    // End of queue: keep playing similar songs, like the radio of the app
    const added = await this.appendRadio(this.current());
    if (added) this.load(this.index + 1, true);
  },

  previous() {
    if (audio.currentTime > 3 || this.index <= 0) {
      audio.currentTime = 0;
      return;
    }
    this.load(this.index - 1, true);
  },

  playNext(song) {
    if (!this.current()) return this.playList([song], 0);
    this.queue.splice(this.index + 1, 0, { ...song });
    renderPanel();
    saveState();
    toast("Se reproducirá a continuación");
  },

  enqueue(song) {
    if (!this.current()) return this.playList([song], 0);
    this.queue.push({ ...song });
    renderPanel();
    saveState();
    toast("Añadida a la cola");
  },

  remove(index) {
    if (index === this.index) {
      this.queue.splice(index, 1);
      if (this.queue.length) this.load(Math.min(index, this.queue.length - 1), !audio.paused);
      else this.clear();
      return;
    }
    this.queue.splice(index, 1);
    if (index < this.index) this.index--;
    renderPanel();
    saveState();
  },

  move(from, to) {
    const [song] = this.queue.splice(from, 1);
    this.queue.splice(to, 0, song);
    if (this.index === from) this.index = to;
    else if (from < this.index && to >= this.index) this.index--;
    else if (from > this.index && to <= this.index) this.index++;
    renderPanel();
    saveState();
  },

  clear() {
    this.queue = [];
    this.index = -1;
    audio.removeAttribute("src");
    audio.load();
    updateNowPlaying();
    renderPanel();
    saveState();
  },

  shuffleRest() {
    if (this.index < 0) return;
    const rest = this.queue.splice(this.index + 1);
    this.queue.push(...shuffleInPlace(rest));
    renderPanel();
    saveState();
    toast("Cola mezclada");
  },

  async startRadio(song) {
    this.playList([song], 0);
    await this.appendRadio(song);
  },

  async appendRadio(song) {
    if (!song || this.loadingRadio) return false;
    this.loadingRadio = true;
    try {
      const { songs } = await api(`/api/radio/${song.id}`);
      const known = new Set(this.queue.map((s) => s.id));
      const fresh = songs.filter((s) => !known.has(s.id));
      this.queue.push(...fresh);
      renderPanel();
      saveState();
      return fresh.length > 0;
    } catch (error) {
      toast(`No se pudo cargar la radio: ${error.message}`);
      return false;
    } finally {
      this.loadingRadio = false;
    }
  },
};

function updateNowPlaying() {
  const song = player.current();
  $("#now-title").textContent = song ? song.title : "Nada en reproducción";
  $("#now-artist").replaceChildren(song ? artistLinks(song) : "");
  const img = $("#now-thumb");
  if (song && song.thumbnail) img.src = song.thumbnail;
  else img.removeAttribute("src");
  // The cover tints the whole page, softly
  document.body.style.setProperty("--ambient-image", song && song.thumbnail ? cssUrl(song.thumbnail) : "none");
  document.title = song ? `${song.title} · SeryMusic` : "SeryMusic";
  refreshLikes();
  highlightPlaying();
  fullplayer.update();
  lyrics.reset();

  if ("mediaSession" in navigator) {
    navigator.mediaSession.metadata = song
      ? new MediaMetadata({
          title: song.title,
          artist: song.artist,
          album: song.album,
          artwork: song.thumbnail ? [{ src: song.thumbnail, sizes: "544x544" }] : [],
        })
      : null;
  }
}

function setFill(range) {
  const max = Number(range.max) || 0;
  range.style.setProperty("--fill", `${max ? (Number(range.value) / max) * 100 : 0}%`);
}

// Both seek bars (player bar and full screen) behave the same
let seeking = false;
for (const [range, label] of [[$("#seek"), "#time-current"], [$("#fp-range"), "#fp-current"]]) {
  range.addEventListener("input", () => { seeking = true; $(label).textContent = formatTime(range.value); setFill(range); });
  range.addEventListener("change", () => { audio.currentTime = Number(range.value); seeking = false; });
}

function updateProgress() {
  if (seeking) return;
  for (const [range, label] of [[$("#seek"), "#time-current"], [$("#fp-range"), "#fp-current"]]) {
    range.value = Math.floor(audio.currentTime);
    $(label).textContent = formatTime(audio.currentTime);
    setFill(range);
  }
}

audio.addEventListener("loadedmetadata", () => {
  for (const [range, label] of [[$("#seek"), "#time-total"], [$("#fp-range"), "#fp-total"]]) {
    range.max = Math.floor(audio.duration) || 0;
    $(label).textContent = formatTime(audio.duration);
  }
  if (player.pendingSeek != null) {
    audio.currentTime = player.pendingSeek;
    player.pendingSeek = null;
  }
});
audio.addEventListener("timeupdate", () => {
  updateProgress();
  lyrics.update(audio.currentTime);
  // Counts as listened after 10 seconds
  const song = player.current();
  if (song && !player.recorded && audio.currentTime >= 10) {
    player.recorded = true;
    api("/api/history", { method: "POST", body: song }).catch(() => {});
  }
});
audio.addEventListener("play", () => { setPlayIcon(true); saveState(); });
audio.addEventListener("playing", () => { setBlocked(false); device.report(); });
audio.addEventListener("pause", () => { setPlayIcon(false); saveState(); });
audio.addEventListener("ended", () => player.next(true));
audio.addEventListener("error", () => {
  const song = player.current();
  if (!song || !audio.getAttribute("src")) return;
  // One retry: the server fetches a fresh stream url when YouTube rejects the old one
  if (player.failures === 0) {
    player.failures++;
    const position = audio.currentTime;
    audio.src = `/api/stream/${song.id}?codec=${CODEC}&retry=${Date.now()}`;
    player.pendingSeek = position || null;
    tryPlay();
    return;
  }
  toast(`No se puede reproducir "${song.title}"`);
  // A queue restored at start up waits for the user instead of skipping on its own
  if (!player.restored && player.index + 1 < player.queue.length) setTimeout(() => player.next(), 1500);
});

/** Browsers refuse to start audio until the user interacted with the page, e.g. a command from an agent. */
let blocked = false;
function tryPlay() {
  return audio.play().then(() => setBlocked(false)).catch((error) => {
    if (error && error.name === "NotAllowedError") setBlocked(true);
  });
}
function setBlocked(value) {
  if (blocked === value) return;
  blocked = value;
  $("#unlock").hidden = !value;
  device.report();
}
$("#unlock").addEventListener("click", () => tryPlay());

function setPlayIcon(playing) {
  const path = ICONS[playing ? "pause" : "play"];
  $("#play-icon").firstElementChild.setAttribute("d", path);
  $("#fp-play-icon").setAttribute("d", path);
  $("#play").title = $("#fp-play").title = playing ? "Pausar" : "Reproducir";
  // Equalizer bars only move while music plays
  document.body.classList.toggle("is-playing", playing);
  if ("mediaSession" in navigator) navigator.mediaSession.playbackState = playing ? "playing" : "paused";
}

$("#play").addEventListener("click", () => player.toggle());
$("#next").addEventListener("click", () => player.next());
$("#prev").addEventListener("click", () => player.previous());
$("#shuffle").addEventListener("click", () => player.shuffleRest());
$("#like").addEventListener("click", () => { const song = player.current(); if (song) toggleFavorite(song); });

function updateRepeat() {
  const button = $("#repeat");
  button.classList.toggle("active", player.repeat !== "off");
  $("#repeat-one").hidden = player.repeat !== "one";
  button.title = { off: "Repetir: no", all: "Repetir: toda la cola", one: "Repetir: esta canción" }[player.repeat];
}
$("#repeat").addEventListener("click", () => {
  player.repeat = { off: "all", all: "one", one: "off" }[player.repeat];
  local.set("repeat", player.repeat);
  updateRepeat();
});
updateRepeat();

const volume = $("#volume");
volume.value = audio.volume = local.get("volume", 1);
setFill(volume);
function setVolume(level) {
  audio.volume = Math.max(0, Math.min(level, 1));
  volume.value = audio.volume;
  local.set("volume", audio.volume);
  setFill(volume);
  device.report();
}
volume.addEventListener("input", () => setVolume(Number(volume.value)));

if ("mediaSession" in navigator) {
  const handlers = {
    play: () => tryPlay(),
    pause: () => audio.pause(),
    previoustrack: () => player.previous(),
    nexttrack: () => player.next(),
    seekto: (details) => { audio.currentTime = details.seekTime; },
  };
  for (const [action, handler] of Object.entries(handlers)) {
    try { navigator.mediaSession.setActionHandler(action, handler); } catch { /* unsupported action */ }
  }
}

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") {
    closeMenu();
    if (!$("#fullplayer").hidden) fullplayer.close();
    return;
  }
  if (event.target.closest("input, textarea") || event.ctrlKey || event.metaKey || event.altKey) return;
  if (event.code === "Space") { event.preventDefault(); player.toggle(); }
  else if (event.key === "ArrowRight") audio.currentTime = Math.min(audio.currentTime + 5, audio.duration || 0);
  else if (event.key === "ArrowLeft") audio.currentTime = Math.max(audio.currentTime - 5, 0);
});

// ---------- Now playing, full screen ----------

const fullplayer = {
  open() {
    if (!player.current()) return;
    $("#fullplayer").hidden = false;
    document.body.classList.add("fp-open");
    this.update();
    updateProgress();
  },

  close() {
    $("#fullplayer").hidden = true;
    document.body.classList.remove("fp-open");
  },

  update() {
    const song = player.current();
    if (!song) return this.close();
    $("#fp-title").textContent = song.title;
    $("#fp-artist").replaceChildren(artistLinks(song));
    const cover = $("#fp-cover");
    if (song.thumbnail) cover.src = song.thumbnail;
    else cover.removeAttribute("src");
    $("#fp-backdrop").style.setProperty("--fp-image", song.thumbnail ? cssUrl(song.thumbnail) : "none");
  },
};

$("#now-open").addEventListener("click", () => fullplayer.open());
$("#now-title").addEventListener("click", () => fullplayer.open());
$("#fp-close").addEventListener("click", () => fullplayer.close());
$("#fp-play").addEventListener("click", () => player.toggle());
$("#fp-next").addEventListener("click", () => player.next());
$("#fp-prev").addEventListener("click", () => player.previous());
$("#fp-shuffle").addEventListener("click", () => player.shuffleRest());
$("#fp-like").addEventListener("click", () => { const song = player.current(); if (song) toggleFavorite(song); });
$("#fp-queue").addEventListener("click", () => { fullplayer.close(); if (panelTab !== "queue") openPanel("queue"); });
$("#fp-lyrics").addEventListener("click", () => { fullplayer.close(); if (panelTab !== "lyrics") openPanel("lyrics"); });

// Queue & position are kept on the server, playback resumes on any device
let saveTimer;
function saveState() {
  device.report();
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    api("/api/state", { method: "PUT", body: {
      queue: player.queue, index: Math.max(player.index, 0), position: audio.currentTime || 0,
    } }).catch(() => {});
  }, 1000);
}
setInterval(() => { if (!audio.paused) saveState(); }, 15000);

async function restoreState() {
  try {
    const state = await api("/api/state");
    if (!state.queue || !state.queue.length) return;
    player.queue = state.queue;
    player.index = Math.min(state.index || 0, state.queue.length - 1);
    player.pendingSeek = state.position || null;
    player.restored = true;
    const song = player.current();
    audio.src = `/api/stream/${song.id}?codec=${CODEC}`;
    audio.preload = "metadata";
    updateNowPlaying();
    renderPanel();
  } catch { /* start with an empty queue */ }
}

// ---------- Side panel: queue & lyrics ----------

let panelTab = null;

function openPanel(tab) {
  panelTab = panelTab === tab ? null : tab;
  $("#panel").hidden = !panelTab;
  $("#queue-btn").classList.toggle("active", panelTab === "queue");
  $("#lyrics-btn").classList.toggle("active", panelTab === "lyrics");
  document.querySelectorAll("[data-panel]").forEach((b) => b.classList.toggle("active", b.dataset.panel === panelTab));
  renderPanel();
}

$("#queue-btn").addEventListener("click", () => openPanel("queue"));
$("#lyrics-btn").addEventListener("click", () => openPanel("lyrics"));
$("#panel-close").addEventListener("click", () => openPanel(panelTab));
document.querySelectorAll("[data-panel]").forEach((b) => b.addEventListener("click", () => openPanel(b.dataset.panel)));

function renderPanel() {
  if (panelTab === "queue") renderQueue();
  else if (panelTab === "lyrics") lyrics.render();
}

function renderQueue() {
  const body = $("#panel-body");
  if (!player.queue.length) {
    body.replaceChildren(el("p", { class: "empty", text: "La cola está vacía." }));
    return;
  }
  const list = el("div", { class: "songs" }, player.queue.map((song, index) =>
    el("div", { class: `song queue-item${index === player.index ? " playing" : ""}`, "data-song": song.id, "data-index": index,
      onclick: (event) => { if (!event.target.closest("button, a")) player.load(index, true); } }, [
      el("div", { class: "song-art" }, [image(song.thumbnail), equalizer()]),
      el("div", { class: "meta" }, [el("div", { class: "title", text: song.title }), el("div", { class: "subtitle" }, artistLinks(song))]),
      el("div", { class: "actions" }, el("button", { class: "icon-btn", title: "Quitar de la cola", onclick: () => player.remove(index) }, icon("close"))),
    ])
  ));
  // Reordering by index, the same song can be in the queue twice
  let dragged = null;
  list.querySelectorAll(".queue-item").forEach((item) => {
    item.draggable = true;
    item.addEventListener("dragstart", () => { dragged = Number(item.dataset.index); item.classList.add("dragging"); });
    item.addEventListener("dragend", () => item.classList.remove("dragging"));
    item.addEventListener("dragover", (event) => { if (dragged != null) event.preventDefault(); });
    item.addEventListener("drop", (event) => {
      event.preventDefault();
      const target = Number(item.dataset.index);
      if (dragged != null && dragged !== target) player.move(dragged, target);
      dragged = null;
    });
  });
  body.replaceChildren(
    el("div", { class: "header-row" }, [
      el("button", { class: "btn", onclick: () => player.shuffleRest() }, [icon("shuffle"), "Mezclar"]),
      el("button", { class: "btn", text: "Vaciar", onclick: () => player.clear() }),
    ]),
    list,
  );
  const current = list.querySelector(".playing");
  if (current) current.scrollIntoView({ block: "nearest" });
}

const lyrics = {
  songId: null,
  lines: null,   // synced lines: [{ time, text }]
  plain: null,
  status: "idle", // idle | loading | done | error
  currentLine: -1,

  reset() {
    this.songId = null;
    this.lines = this.plain = null;
    this.status = "idle";
    this.currentLine = -1;
    if (panelTab === "lyrics") this.render();
  },

  async load(song) {
    this.songId = song.id;
    this.status = "loading";
    const params = new URLSearchParams({ title: song.title, artist: song.artist, duration: song.duration || "" });
    try {
      const data = await api(`/api/lyrics?${params}`);
      if (this.songId !== song.id) return;
      this.lines = data.synced ? parseLrc(data.synced) : null;
      this.plain = data.plain;
      this.status = "done";
    } catch {
      if (this.songId !== song.id) return;
      this.status = "error";
    }
    if (panelTab === "lyrics") this.render();
  },

  render() {
    const body = $("#panel-body");
    const song = player.current();
    if (!song) return body.replaceChildren(el("p", { class: "empty", text: "Reproduce una canción para ver su letra." }));
    if (this.songId !== song.id) this.load(song);
    if (this.status === "loading") return body.replaceChildren(el("div", { class: "spinner" }));
    if (this.status === "error") return body.replaceChildren(el("p", { class: "empty", text: "No se pudo cargar la letra." }));
    if (this.lines && this.lines.length) {
      this.currentLine = -1;
      body.replaceChildren(el("div", { class: "lyrics" }, this.lines.map((line) =>
        el("p", { text: line.text || "♪", onclick: () => { audio.currentTime = line.time; } }))));
      this.update(audio.currentTime);
      return;
    }
    if (this.plain) return body.replaceChildren(el("div", { class: "lyrics plain" }, el("p", { text: this.plain })));
    body.replaceChildren(el("p", { class: "empty", text: "No hay letra para esta canción." }));
  },

  update(time) {
    if (panelTab !== "lyrics" || !this.lines) return;
    let index = -1;
    for (let i = 0; i < this.lines.length && this.lines[i].time <= time + 0.2; i++) index = i;
    if (index === this.currentLine) return;
    const nodes = $("#panel-body").querySelectorAll(".lyrics p");
    nodes[this.currentLine]?.classList.remove("current");
    this.currentLine = index;
    const node = nodes[index];
    if (node) {
      node.classList.add("current");
      node.scrollIntoView({ block: "center", behavior: "smooth" });
    }
  },
};

/** `[01:02.34] text` lines to `{ time: 62.34, text }`, sorted by time. */
function parseLrc(text) {
  const lines = [];
  for (const raw of text.split(/\r?\n/)) {
    const stamps = [...raw.matchAll(/\[(\d+):(\d+(?:[.:]\d+)?)\]/g)];
    if (!stamps.length) continue;
    const content = raw.replace(/\[[^\]]*\]/g, "").trim();
    for (const [, min, sec] of stamps) lines.push({ time: Number(min) * 60 + Number(sec.replace(":", ".")), text: content });
  }
  return lines.sort((a, b) => a.time - b.time);
}

// ---------- Remote control (AI agents, through the MCP server) ----------

function defaultDeviceName() {
  const ua = navigator.userAgent;
  if (/iPhone/.test(ua)) return "iPhone";
  if (/iPad/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1)) return "iPad";
  if (/Android/.test(ua)) return /Mobile/.test(ua) ? "Móvil Android" : "Tablet Android";
  if (/Windows/.test(ua)) return "PC Windows";
  if (/Macintosh/.test(ua)) return "Mac";
  if (/CrOS/.test(ua)) return "Chromebook";
  return "Linux";
}

const device = {
  id: null,
  name: null,
  events: null,
  reportTimer: null,

  start() {
    this.id = local.get("deviceId", null);
    if (!this.id || !/^[A-Za-z0-9_-]{8,64}$/.test(this.id)) {
      const random = crypto.getRandomValues(new Uint8Array(12));
      this.id = Array.from(random, (b) => b.toString(16).padStart(2, "0")).join("");
      local.set("deviceId", this.id);
    }
    this.name = local.get("deviceName", null) || defaultDeviceName();
    this.connect();
    // Heartbeat, the server forgets players that stay silent
    setInterval(() => this.report(), 10000);
    window.addEventListener("pagehide", () => navigator.sendBeacon(`/api/devices/${this.id}/bye`));
  },

  connect() {
    if (this.events) this.events.close();
    // EventSource reconnects by itself when the server restarts
    this.events = new EventSource(`/api/devices/${this.id}/events?name=${encodeURIComponent(this.name)}`);
    this.events.onmessage = (event) => {
      try { this.handle(JSON.parse(event.data)); } catch (error) { console.error(error); }
    };
    this.events.onopen = () => this.report();
  },

  rename() {
    const name = prompt("Nombre de este dispositivo (para pedirle música desde un asistente)", this.name);
    if (!name || !name.trim()) return;
    this.name = name.trim().slice(0, 40);
    local.set("deviceName", this.name);
    this.connect();
    if (route.name === "home") render();
  },

  handle(command) {
    switch (command.type) {
      case "play": player.playList(command.songs, command.index || 0); break;
      case "pause": case "stop": audio.pause(); break;
      case "resume": if (player.current()) tryPlay(); break;
      case "next": player.next(); break;
      case "previous": player.previous(); break;
      case "enqueue": command.next ? player.playNext(command.song) : player.enqueue(command.song); break;
      case "volume": setVolume(Number(command.level)); break;
      case "library":
        loadFavorites().catch(() => {});
        if (["favorites", "playlists", "playlist"].includes(route.name)) render();
        break;
    }
  },

  /** Tells the server what's playing, debounced. */
  report() {
    if (!this.id) return;
    clearTimeout(this.reportTimer);
    this.reportTimer = setTimeout(() => {
      fetch(`/api/devices/${this.id}/state`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify({
          kind: "browser",
          name: this.name,
          song: player.current(),
          playing: !audio.paused && !!player.current(),
          blocked,
          position: audio.currentTime || 0,
          volume: audio.volume,
          upcoming: player.queue.slice(player.index + 1, player.index + 6),
        }),
      }).catch(() => {});
    }, 150);
  },
};

// ---------- Start ----------

device.start();
parseRoute();
render();
// Without password there's nothing to log out from
api("/api/config").then((config) => { $(".logout").hidden = !config.password; }).catch(() => {});
loadFavorites().catch(() => {});
restoreState();
