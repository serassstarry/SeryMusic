"""YouTube Music access: metadata through ytmusicapi, audio streams through yt-dlp."""

from __future__ import annotations

import re
import threading
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass

import yt_dlp
from ytmusicapi import YTMusic

VIDEO_ID = re.compile(r"^[A-Za-z0-9_-]{11}$")
_URL_VIDEO_ID = re.compile(r"(?:v=|youtu\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})")

# Stream urls are refreshed this long before they expire, so a song
# started right before expiry can still be played to the end.
_EXPIRY_MARGIN = 10 * 60

_local = threading.local()


def _ytm() -> YTMusic:
    # YTMusic keeps a requests session, one per thread avoids sharing it
    if not hasattr(_local, "ytm"):
        _local.ytm = YTMusic()
    return _local.ytm


def is_video_id(value: str) -> bool:
    return bool(VIDEO_ID.match(value or ""))


def _parse_duration(text: str | None) -> int:
    """`"3:05"` -> 185, `"1:02:03"` -> 3723"""
    if not text:
        return 0
    seconds = 0
    for part in str(text).split(":"):
        if not part.isdigit():
            return 0
        seconds = seconds * 60 + int(part)
    return seconds


ARTIST_ID = re.compile(r"^UC[A-Za-z0-9_-]{22}$")
ALBUM_ID = re.compile(r"^MPRE[A-Za-z0-9_-]{5,60}$")


def is_artist_id(value: str) -> bool:
    return bool(ARTIST_ID.match(value or ""))


def is_album_id(value: str) -> bool:
    return bool(ALBUM_ID.match(value or ""))


def _thumbnail(thumbnails: list | None, size: int = 544) -> str:
    if not thumbnails:
        return ""
    url = thumbnails[-1].get("url", "")
    # googleusercontent thumbnails can be resized through the url
    return re.sub(r"=w\d+-h\d+", f"=w{size}-h{size}", url)


def _artists(item: dict) -> list[dict]:
    """Artists of a song or album, with the id of their page when YouTube Music has one."""
    return [
        {"name": a["name"], "id": a.get("id") if is_artist_id(a.get("id")) else None}
        for a in item.get("artists") or [] if a.get("name")
    ]


def _song(item: dict, album: dict | None = None) -> dict | None:
    """[album] fills in what album tracks don't repeat (name, cover)."""
    video_id = item.get("videoId")
    if not is_video_id(video_id) or item.get("isAvailable") is False:
        return None
    artists = _artists(item) or (album or {}).get("artists") or []
    item_album = item.get("album") if isinstance(item.get("album"), dict) else {}
    return {
        "id": video_id,
        "title": item.get("title") or "",
        "artist": ", ".join(a["name"] for a in artists),
        "artists": artists,
        "album": item_album.get("name") or (album or {}).get("title", ""),
        "album_id": item_album.get("id") if is_album_id(item_album.get("id")) else (album or {}).get("id"),
        "duration": item.get("duration_seconds") or _parse_duration(item.get("duration") or item.get("length")),
        "thumbnail": _thumbnail(item.get("thumbnails") or item.get("thumbnail")) or (album or {}).get("thumbnail", ""),
    }


def _album_card(item: dict, artists: list[dict] | None = None) -> dict | None:
    album_id = item.get("browseId")
    if not is_album_id(album_id):
        return None
    found = _artists(item) or artists or []
    return {
        "id": album_id,
        "title": item.get("title") or "",
        "type": item.get("type") or "",
        "year": str(item.get("year") or ""),
        "artist": ", ".join(a["name"] for a in found),
        "artists": found,
        "thumbnail": _thumbnail(item.get("thumbnails")),
    }


def _artist_card(item: dict) -> dict | None:
    artist_id = item.get("browseId")
    if not is_artist_id(artist_id):
        return None
    return {
        "id": artist_id,
        "name": item.get("artist") or item.get("title") or "",
        "subscribers": item.get("subscribers") or "",
        "thumbnail": _thumbnail(item.get("thumbnails"), 400),
    }


# Artist and album pages are cached a while, going back and forth between them is instant
_CACHE_SECONDS = 15 * 60
_cache: dict[tuple, tuple[float, object]] = {}
_cache_lock = threading.Lock()


def _cached(key: tuple, load):
    with _cache_lock:
        hit = _cache.get(key)
        if hit and time.time() - hit[0] < _CACHE_SECONDS:
            return hit[1]
    value = load()
    with _cache_lock:
        _cache[key] = (time.time(), value)
        if len(_cache) > 300:
            for old in sorted(_cache, key=lambda k: _cache[k][0])[:100]:
                del _cache[old]
    return value


def song(video_id: str) -> dict | None:
    details = _ytm().get_song(video_id).get("videoDetails") or {}
    if details.get("videoId") != video_id:
        return None
    channel = details.get("channelId")
    return {
        "id": video_id,
        "title": details.get("title", ""),
        "artist": details.get("author", ""),
        "artists": [{"name": details.get("author", ""), "id": channel if is_artist_id(channel) else None}],
        "album": "",
        "album_id": None,
        "duration": int(details.get("lengthSeconds") or 0),
        "thumbnail": _thumbnail((details.get("thumbnail") or {}).get("thumbnails")),
    }


def search(query: str) -> list[dict]:
    query = query.strip()
    # A pasted YouTube / YouTube Music link plays that exact song
    match = _URL_VIDEO_ID.search(query)
    if match:
        found = song(match.group(1))
        return [found] if found else []

    results = _ytm().search(query, filter="songs", limit=40)
    return [s for s in map(_song, results) if s]


def search_all(query: str) -> dict:
    """Songs, artists and albums matching [query], searched in parallel."""
    query = query.strip()
    if _URL_VIDEO_ID.search(query):
        return {"songs": search(query), "artists": [], "albums": []}

    def find(kind: str, limit: int) -> list[dict]:
        return _ytm().search(query, filter=kind, limit=limit)

    with ThreadPoolExecutor(max_workers=3) as pool:
        songs = pool.submit(find, "songs", 40)
        artists = pool.submit(find, "artists", 12)
        albums = pool.submit(find, "albums", 20)
        return {
            "songs": [s for s in map(_song, songs.result()) if s],
            "artists": [a for a in map(_artist_card, artists.result()) if a],
            "albums": [a for a in map(_album_card, albums.result()) if a],
        }


def artist(artist_id: str) -> dict:
    """Artist page: top songs, the whole discography and similar artists."""
    def load():
        data = _ytm().get_artist(artist_id)
        me = [{"name": data.get("name", ""), "id": artist_id}]

        def releases(section: str) -> list[dict]:
            shelf = data.get(section) or {}
            items = shelf.get("results") or []
            # Shelves only show a few, the complete list is a separate request
            if shelf.get("browseId") and shelf.get("params"):
                try:
                    items = _ytm().get_artist_albums(shelf["browseId"], shelf["params"]) or items
                except Exception:
                    pass
            return [a for a in (_album_card(item, me) for item in items) if a]

        songs_shelf = data.get("songs") or {}
        thumbnails = data.get("thumbnails") or []
        return {
            "id": artist_id,
            "name": data.get("name", ""),
            "description": data.get("description") or "",
            "subscribers": data.get("subscribers") or "",
            "listeners": data.get("monthlyListeners") or "",
            "thumbnail": _thumbnail(thumbnails, 400),
            "banner": _thumbnail(thumbnails, 1440),
            "songs": [s for s in map(_song, songs_shelf.get("results") or []) if s],
            # Whether "all songs" can be loaded
            "more_songs": bool(str(songs_shelf.get("browseId") or "").startswith("VL")),
            "albums": releases("albums"),
            "singles": releases("singles"),
            "related": [a for a in map(_artist_card, (data.get("related") or {}).get("results") or []) if a],
        }

    return _cached(("artist", artist_id), load)


def artist_songs(artist_id: str) -> list[dict]:
    """Every song of an artist (up to 200), most popular first."""
    def load():
        playlist = str((_ytm().get_artist(artist_id).get("songs") or {}).get("browseId") or "")
        if not playlist.startswith("VL"):
            return artist(artist_id)["songs"]
        tracks = _ytm().get_playlist(playlist[2:], limit=200).get("tracks") or []
        return [s for s in map(_song, tracks) if s]

    return _cached(("artist_songs", artist_id), load)


def find_artist(name: str) -> str | None:
    """Id of the artist page best matching [name], for songs saved without it."""
    def load():
        results = _ytm().search(name, filter="artists", limit=5)
        cards = [a for a in map(_artist_card, results) if a]
        exact = [a for a in cards if a["name"].lower() == name.lower()]
        return (exact or cards or [{}])[0].get("id")

    return _cached(("find_artist", name.lower()), load)


def album(album_id: str) -> dict:
    def load():
        data = _ytm().get_album(album_id)
        info = {
            "id": album_id,
            "title": data.get("title", ""),
            "type": data.get("type") or "",
            "year": str(data.get("year") or ""),
            "artists": _artists(data),
            "description": data.get("description") or "",
            "duration": data.get("duration") or "",
            "thumbnail": _thumbnail(data.get("thumbnails")),
            "banner": _thumbnail(data.get("thumbnails"), 1080),
        }
        info["artist"] = ", ".join(a["name"] for a in info["artists"])
        info["songs"] = [s for s in (_song(t, info) for t in data.get("tracks") or []) if s]
        return info

    return _cached(("album", album_id), load)


def radio(video_id: str) -> list[dict]:
    """Songs similar to [video_id], used to keep playing when the queue ends."""
    tracks = _ytm().get_watch_playlist(videoId=video_id, radio=True, limit=30).get("tracks") or []
    return [s for s in map(_song, tracks) if s and s["id"] != video_id]


@dataclass(frozen=True)
class Stream:
    url: str
    headers: dict
    mime_type: str
    size: int
    expires_at: float


# Browsers that can't decode opus (Safari) get AAC instead
_FORMATS = {
    "opus": "bestaudio[acodec=opus]/bestaudio[ext=m4a]/bestaudio",
    "aac": "bestaudio[ext=m4a]/bestaudio",
}

_streams: dict[tuple[str, str], Stream] = {}
_streams_lock = threading.Lock()
# One lock per song, so 2 requests for the same song don't both run yt-dlp
_resolving: dict[tuple[str, str], threading.Lock] = {}


def _extract(video_id: str, codec: str) -> Stream:
    options = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
        "format": _FORMATS[codec],
        # Without a JavaScript runtime, yt-dlp only gets songs from a client that refuses some of them
        # (e.g. songs made for kids: "This video is not available"). It uses whichever is installed.
        "js_runtimes": {"deno": {}, "node": {}, "bun": {}},
    }
    with yt_dlp.YoutubeDL(options) as ydl:
        info = ydl.extract_info(f"https://music.youtube.com/watch?v={video_id}", download=False)

    url = info["url"]
    query = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
    size = info.get("filesize") or int((query.get("clen") or ["0"])[0])
    expires_at = float((query.get("expire") or [time.time() + 3600])[0])
    mime_type = "audio/webm" if info.get("ext") == "webm" else "audio/mp4"
    return Stream(url, dict(info.get("http_headers") or {}), mime_type, int(size), expires_at)


def stream(video_id: str, codec: str = "aac", refresh: bool = False) -> Stream:
    """
    Url of the audio of [video_id]. Urls are cached until they're about to expire,
    [refresh] forces a new one (e.g. when YouTube rejected the cached url).
    """
    if codec not in _FORMATS:
        codec = "aac"
    key = (video_id, codec)

    with _streams_lock:
        lock = _resolving.setdefault(key, threading.Lock())
    with lock:
        with _streams_lock:
            cached = _streams.get(key)
        if cached and not refresh and cached.expires_at - _EXPIRY_MARGIN > time.time():
            return cached

        resolved = _extract(video_id, codec)
        with _streams_lock:
            _streams[key] = resolved
            # Drop expired urls so the cache doesn't grow forever
            now = time.time()
            for k in [k for k, s in _streams.items() if s.expires_at < now]:
                del _streams[k]
        return resolved
