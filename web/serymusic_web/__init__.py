"""SeryMusic web: a personal YouTube Music player served from any machine with Python."""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import os
import queue
import re
import secrets
import threading
import time
from datetime import timedelta
from pathlib import Path

import requests
from flask import Flask, Response, abort, jsonify, redirect, request, send_from_directory, session, stream_with_context

from . import media, youtube
from .db import Library
from .devices import DeviceHub
from .remote import Remote

log = logging.getLogger("serymusic")

STATIC_DIR = Path(__file__).parent / "static"
# Audio is fetched from YouTube in pieces of this size, big requests get throttled
UPSTREAM_CHUNK = 1024 * 1024
MAX_LOGIN_FAILURES = 5
LOGIN_LOCKOUT = 5 * 60
DEVICE_ID = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
# Comment sent to idle event streams, keeps proxies from closing them
KEEPALIVE_SECONDS = 10


def _read_or_create(path: Path, factory) -> str:
    if not path.exists():
        path.write_text(factory(), encoding="utf-8")
        try:
            path.chmod(0o600)
        except OSError:
            pass
    return path.read_text(encoding="utf-8").strip()


def parse_range(header: str | None, size: int) -> tuple[int, int] | None:
    """
    Byte range requested by [header] (`bytes=start-end`, `bytes=start-` or `bytes=-suffix`)
    as inclusive `(start, end)`, whole file when there's no header.

    @return `None` when the range can't be satisfied
    """
    if not header:
        return (0, size - 1) if size > 0 else None
    unit, _, spec = header.partition("=")
    if unit.strip() != "bytes" or "," in spec:
        return None
    start_text, _, end_text = spec.strip().partition("-")
    try:
        if start_text == "":
            suffix = int(end_text)
            if suffix <= 0:
                return None
            start, end = max(size - suffix, 0), size - 1
        else:
            start = int(start_text)
            end = int(end_text) if end_text else size - 1
    except ValueError:
        return None
    end = min(end, size - 1)
    if start < 0 or start > end:
        return None
    return start, end


def _clean_song(body) -> dict:
    """Song sent by the browser, every field is checked before it reaches the database."""
    if not isinstance(body, dict) or not youtube.is_video_id(body.get("id")):
        abort(400, "Invalid song")
    thumbnail = str(body.get("thumbnail") or "")
    try:
        duration = max(0, min(int(body.get("duration") or 0), 24 * 3600))
    except (TypeError, ValueError):
        duration = 0
    # Artists and album of the song, kept so their pages stay one click away
    artists = []
    for artist in body.get("artists") if isinstance(body.get("artists"), list) else []:
        if isinstance(artist, dict) and artist.get("name"):
            artist_id = artist.get("id")
            artists.append({"name": str(artist["name"])[:100],
                            "id": artist_id if youtube.is_artist_id(artist_id) else None})
    album_id = body.get("album_id")
    return {
        "id": body["id"],
        "title": str(body.get("title") or "")[:300],
        "artist": str(body.get("artist") or "")[:300],
        "artists": artists[:10],
        "album": str(body.get("album") or "")[:300],
        "album_id": album_id if youtube.is_album_id(album_id) else None,
        "duration": duration,
        "thumbnail": thumbnail[:1000] if thumbnail.startswith("https://") else "",
    }


def _device_name(value) -> str | None:
    name = " ".join(str(value or "").split())[:40]
    return name or None


def _playlist_name(body) -> str:
    name = str((body or {}).get("name") or "").strip()[:100]
    if not name:
        abort(400, "Name is required")
    return name


def create_app(data_dir: Path | None = None, password: str | None = None, no_password: bool | None = None) -> Flask:
    data_dir = Path(data_dir or os.environ.get("SERYMUSIC_DATA") or Path(__file__).parent.parent / "data")
    data_dir.mkdir(parents=True, exist_ok=True)

    app = Flask(__name__, static_folder=None)
    app.secret_key = _read_or_create(data_dir / "secret_key", lambda: secrets.token_hex(32))
    app.config.update(
        PERMANENT_SESSION_LIFETIME=timedelta(days=30),
        SESSION_COOKIE_HTTPONLY=True,
        SESSION_COOKIE_SAMESITE="Lax",
        SESSION_COOKIE_SECURE=os.environ.get("SERYMUSIC_SECURE_COOKIE") == "1",
        MAX_CONTENT_LENGTH=1024 * 1024,
    )
    if os.environ.get("SERYMUSIC_BEHIND_PROXY") == "1":
        from werkzeug.middleware.proxy_fix import ProxyFix
        app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1, x_proto=1)

    # Trusted networks (e.g. home LAN) can go without a password
    if no_password is None:
        no_password = os.environ.get("SERYMUSIC_NO_PASSWORD") == "1"
    app.config["NO_PASSWORD"] = no_password
    password = password or os.environ.get("SERYMUSIC_PASSWORD")
    if not password and not no_password:
        # No password configured: generate one, `app.py` tells where to find it
        app.config["PASSWORD_FILE"] = data_dir / "password.txt"
        password = _read_or_create(app.config["PASSWORD_FILE"], lambda: secrets.token_urlsafe(12))

    # Agents connecting to the MCP server must send it, unless there's no password
    app.config["MCP_PASSWORD"] = None if no_password else password

    library = Library(data_dir / "serymusic.db")
    hub = DeviceHub()

    def stream_token(video_id: str) -> str:
        """Lets someone without session (e.g. an AI agent) play this one song."""
        return hmac.new(app.secret_key.encode(), f"stream:{video_id}".encode(), hashlib.sha256).hexdigest()[:32]

    def stream_url(video_id: str) -> str:
        base = (app.config.get("PUBLIC_URL") or "").rstrip("/")
        return f"{base}/api/stream/{video_id}?codec=aac&token={stream_token(video_id)}"

    remote = Remote(hub, library, stream_url)
    app.extensions["serymusic"] = {"library": library, "hub": hub, "remote": remote}
    app.config["PUBLIC_URL"] = os.environ.get("SERYMUSIC_PUBLIC_URL", "")
    # How the server reaches itself (ffmpeg reads songs through it), set by app.py
    app.config["SELF_URL"] = ""
    app.config["LIVE_TOKEN"] = stream_token("live")
    http = requests.Session()
    failures: dict[str, tuple[int, float]] = {}
    failures_lock = threading.Lock()

    # Auth

    def logged_in() -> bool:
        return no_password or session.get("auth") is True

    @app.before_request
    def require_login():
        if request.endpoint in ("login", "login_page", "static_file") or logged_in():
            return None
        if request.endpoint in ("stream", "transcode"):
            video_id = (request.view_args or {}).get("video_id", "")
            if hmac.compare_digest(request.args.get("token", ""), stream_token(video_id)):
                return None
        if request.endpoint == "live" and hmac.compare_digest(request.args.get("token", ""), stream_token("live")):
            return None
        if request.path.startswith("/api/"):
            return jsonify(error="Not logged in"), 401
        return redirect("/login")

    @app.get("/login")
    def login_page():
        if no_password:
            return redirect("/")
        return send_from_directory(STATIC_DIR, "login.html")

    @app.post("/login")
    def login():
        if no_password:
            return redirect("/")
        ip = request.remote_addr or "?"
        with failures_lock:
            count, until = failures.get(ip, (0, 0))
            if count >= MAX_LOGIN_FAILURES and until > time.time():
                return redirect("/login?error=locked")

        given = request.form.get("password", "")
        if hmac.compare_digest(given.encode(), password.encode()):
            with failures_lock:
                failures.pop(ip, None)
            session.clear()
            session.permanent = True
            session["auth"] = True
            return redirect("/")

        with failures_lock:
            count = failures.get(ip, (0, 0))[0] + 1
            failures[ip] = (count, time.time() + LOGIN_LOCKOUT)
        time.sleep(1)
        return redirect("/login?error=1")

    @app.post("/logout")
    def logout():
        session.clear()
        return redirect("/login")

    @app.get("/api/config")
    def config():
        return jsonify(password=not no_password)

    # Remote control: every open player is a device that receives commands

    def device_id_or_404(device_id: str) -> str:
        if not DEVICE_ID.match(device_id):
            abort(404)
        return device_id

    @app.get("/api/devices")
    def devices():
        return jsonify(devices=hub.devices())

    @app.get("/api/devices/<device_id>/events")
    def device_events(device_id):
        device_id_or_404(device_id)
        commands = hub.connect(device_id, _device_name(request.args.get("name")), request.remote_addr)

        def body():
            try:
                yield "retry: 3000\n\n"
                while True:
                    try:
                        command = commands.get(timeout=KEEPALIVE_SECONDS)
                    except queue.Empty:
                        # Also how a closed tab gets noticed: writing to it fails
                        yield ": keepalive\n\n"
                        continue
                    yield f"data: {json.dumps(command)}\n\n"
            finally:
                hub.disconnect(device_id, commands)

        return Response(stream_with_context(body()), mimetype="text/event-stream",
                        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    @app.post("/api/devices/<device_id>/bye")
    def device_bye(device_id):
        hub.leave(device_id_or_404(device_id))
        return jsonify(ok=True)

    @app.post("/api/devices/<device_id>/state")
    def device_state(device_id):
        device_id_or_404(device_id)
        body = request.get_json(silent=True)
        if not isinstance(body, dict):
            abort(400, "State must be an object")
        song = body.get("song")
        upcoming = body.get("upcoming") if isinstance(body.get("upcoming"), list) else []
        kind = body.get("kind") if body.get("kind") in ("browser", "speaker", "dlna", "live") else "browser"
        listen_url = str(body.get("listen_url") or "")[:500]
        try:
            state = {
                "kind": kind,
                "listen_url": listen_url if listen_url.startswith(("http://", "https://")) else "",
                "song": _clean_song(song) if song else None,
                "playing": bool(body.get("playing")),
                "blocked": bool(body.get("blocked")),
                "position": max(0.0, float(body.get("position") or 0)),
                "volume": max(0.0, min(float(body.get("volume") or 0), 1.0)),
                "upcoming": [_clean_song(s) for s in upcoming[:5]],
            }
        except (TypeError, ValueError):
            abort(400, "position and volume must be numbers")
        hub.report(device_id, _device_name(body.get("name")), state, request.remote_addr)
        return jsonify(ok=True)

    # Pages

    @app.get("/")
    def index():
        return send_from_directory(STATIC_DIR, "index.html")

    @app.get("/static/<path:filename>")
    def static_file(filename):
        return send_from_directory(STATIC_DIR, filename)

    @app.after_request
    def security_headers(response: Response):
        response.headers.setdefault(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' https: data:; media-src 'self'; "
            "style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'",
        )
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("Referrer-Policy", "no-referrer")
        return response

    @app.errorhandler(400)
    @app.errorhandler(404)
    def client_error(error):
        if request.path.startswith("/api/"):
            return jsonify(error=error.description), error.code
        return error

    # YouTube

    def upstream_error(error: Exception):
        log.warning("YouTube request failed: %s", error)
        message = str(error).removeprefix("ERROR: ").split("\n")[0][:200]
        return jsonify(error=message or "YouTube request failed"), 502

    @app.get("/api/search")
    def search():
        query = request.args.get("q", "").strip()[:200]
        if not query:
            return jsonify(songs=[], artists=[], albums=[])
        try:
            return jsonify(youtube.search_all(query))
        except Exception as error:
            return upstream_error(error)

    @app.get("/api/artist/<artist_id>")
    def artist(artist_id):
        if not youtube.is_artist_id(artist_id):
            abort(404, "Unknown artist")
        try:
            return jsonify(youtube.artist(artist_id))
        except Exception as error:
            return upstream_error(error)

    @app.get("/api/artist/<artist_id>/songs")
    def artist_songs(artist_id):
        if not youtube.is_artist_id(artist_id):
            abort(404, "Unknown artist")
        try:
            return jsonify(songs=youtube.artist_songs(artist_id))
        except Exception as error:
            return upstream_error(error)

    @app.get("/api/artist-by-name")
    def artist_by_name():
        """For songs saved without the id of their artist."""
        name = request.args.get("name", "").strip()[:100]
        if not name:
            abort(400, "name is required")
        try:
            artist_id = youtube.find_artist(name)
        except Exception as error:
            return upstream_error(error)
        if not artist_id:
            abort(404, "Artist not found")
        return jsonify(id=artist_id)

    @app.get("/api/album/<album_id>")
    def album(album_id):
        if not youtube.is_album_id(album_id):
            abort(404, "Unknown album")
        try:
            return jsonify(youtube.album(album_id))
        except Exception as error:
            return upstream_error(error)

    @app.get("/api/radio/<video_id>")
    def radio(video_id):
        if not youtube.is_video_id(video_id):
            abort(404)
        try:
            return jsonify(songs=youtube.radio(video_id))
        except Exception as error:
            return upstream_error(error)

    @app.get("/api/stream/<video_id>")
    def stream(video_id):
        if not youtube.is_video_id(video_id):
            abort(404)
        codec = request.args.get("codec", "aac")
        try:
            audio = youtube.stream(video_id, codec)
        except Exception as error:
            return upstream_error(error)

        if audio.size <= 0:
            return jsonify(error="Unknown audio size"), 502
        byte_range = parse_range(request.headers.get("Range"), audio.size)
        if byte_range is None:
            return Response(status=416, headers={"Content-Range": f"bytes */{audio.size}"})
        start, end = byte_range

        def fetch(position: int, last: int):
            nonlocal audio
            for attempt in range(2):
                headers = {**audio.headers, "Range": f"bytes={position}-{last}"}
                upstream = http.get(audio.url, headers=headers, stream=True, timeout=(10, 30))
                # Url expired or got rejected, get a fresh one and retry once
                if upstream.status_code in (403, 410) and attempt == 0:
                    upstream.close()
                    audio = youtube.stream(video_id, codec, refresh=True)
                    continue
                upstream.raise_for_status()
                return upstream

        # First piece is fetched before answering, so a failure still gets a proper error status
        first_last = min(start + UPSTREAM_CHUNK - 1, end)
        try:
            first = fetch(start, first_last)
        except Exception as error:
            return upstream_error(error)

        def body():
            position, last, upstream = start, first_last, first
            while True:
                with upstream:
                    for chunk in upstream.iter_content(64 * 1024):
                        position += len(chunk)
                        yield chunk
                if position <= last:
                    log.warning("YouTube sent less data than requested for %s", video_id)
                    return
                if position > end:
                    return
                last = min(position + UPSTREAM_CHUNK - 1, end)
                try:
                    upstream = fetch(position, last)
                except Exception as error:
                    # Headers are already sent, all that can be done is to end the response early
                    log.warning("Streaming %s stopped at byte %d: %s", video_id, position, error)
                    return

        partial = request.headers.get("Range") is not None
        headers = {
            "Content-Type": audio.mime_type,
            "Content-Length": str(end - start + 1),
            "Accept-Ranges": "bytes",
            "Cache-Control": "private, max-age=3600",
            "X-Accel-Buffering": "no",
        }
        if partial:
            headers["Content-Range"] = f"bytes {start}-{end}/{audio.size}"
        return Response(stream_with_context(body()), status=206 if partial else 200, headers=headers)

    @app.get("/api/streamlink/<video_id>")
    def stream_link(video_id):
        """
        Path of a song's audio that works without session, for players like mpv or a TV.
        `format`: `opus` or `aac` as YouTube serves them, `mp3` converted for devices that need it (DLNA).
        """
        if not youtube.is_video_id(video_id):
            abort(404)
        media_format = request.args.get("format") or request.args.get("codec")
        token = stream_token(video_id)
        if media_format == "mp3":
            return jsonify(path=f"/api/transcode/{video_id}.mp3?token={token}")
        codec = "opus" if media_format == "opus" else "aac"
        return jsonify(path=f"/api/stream/{video_id}?codec={codec}&token={token}")

    @app.route("/api/transcode/<video_id>.mp3", methods=["GET", "HEAD"])
    def transcode(video_id):
        """Song converted to MP3 on the fly, the format every DLNA TV and speaker plays."""
        if not youtube.is_video_id(video_id):
            abort(404)
        ffmpeg = media.ffmpeg_path()
        if not ffmpeg:
            return jsonify(error="ffmpeg is not installed"), 503
        headers = {
            "Content-Type": "audio/mpeg",
            "Cache-Control": "no-cache",
            "transferMode.dlna.org": "Streaming",
            "contentFeatures.dlna.org": media.DLNA_MP3_FEATURES,
        }
        if request.method == "HEAD":
            return Response(status=200, headers=headers)
        try:
            start = max(0.0, float(request.args.get("start") or 0))
        except ValueError:
            start = 0.0
        source = f"{app.config['SELF_URL'] or request.host_url.rstrip('/')}/api/stream/{video_id}?codec=aac&token={stream_token(video_id)}"
        return Response(stream_with_context(media.mp3_stream(ffmpeg, source, start)), headers=headers)

    @app.get("/live")
    @app.get("/live.mp3")
    def live():
        """Continuous stream of the "Emisión" device, any player can open it."""
        broadcast = app.extensions["serymusic"].get("live")
        if broadcast is None:
            return jsonify(error="Live stream is off, it needs ffmpeg"), 503
        return Response(stream_with_context(broadcast.listen()), headers={
            "Content-Type": "audio/mpeg",
            "Cache-Control": "no-cache",
            "icy-name": "SeryMusic",
            "transferMode.dlna.org": "Streaming",
            "contentFeatures.dlna.org": media.DLNA_MP3_FEATURES,
        })

    @app.get("/api/lyrics")
    def lyrics():
        params = {
            "track_name": request.args.get("title", "")[:300],
            "artist_name": request.args.get("artist", "")[:300].split(",")[0],
        }
        duration = request.args.get("duration", "")
        if duration.isdigit() and int(duration) > 0:
            params["duration"] = duration
        try:
            response = http.get("https://lrclib.net/api/get", params=params, timeout=10,
                                headers={"User-Agent": "SeryMusic web (https://github.com/serassstarry/SeryMusic)"})
        except requests.RequestException as error:
            return upstream_error(error)
        if response.status_code == 404:
            return jsonify(synced=None, plain=None)
        if not response.ok:
            return jsonify(error=f"Lyrics service returned {response.status_code}"), 502
        data = response.json()
        return jsonify(synced=data.get("syncedLyrics"), plain=data.get("plainLyrics"))

    # Library

    @app.get("/api/favorites")
    def favorites():
        return jsonify(songs=library.favorites())

    @app.get("/api/favorites/ids")
    def favorite_ids():
        return jsonify(ids=library.favorite_ids())

    @app.put("/api/favorites/<video_id>")
    def add_favorite(video_id):
        song = _clean_song(request.get_json(silent=True))
        if song["id"] != video_id:
            abort(400, "Song id doesn't match url")
        library.add_favorite(song)
        return jsonify(ok=True)

    @app.delete("/api/favorites/<video_id>")
    def remove_favorite(video_id):
        library.remove_favorite(video_id)
        return jsonify(ok=True)

    @app.get("/api/playlists")
    def playlists():
        return jsonify(playlists=library.playlists())

    @app.post("/api/playlists")
    def create_playlist():
        playlist_id = library.create_playlist(_playlist_name(request.get_json(silent=True)))
        return jsonify(id=playlist_id), 201

    @app.get("/api/playlists/<int:playlist_id>")
    def playlist(playlist_id):
        found = library.playlist(playlist_id)
        if found is None:
            abort(404, "Playlist not found")
        return jsonify(found)

    @app.patch("/api/playlists/<int:playlist_id>")
    def rename_playlist(playlist_id):
        if not library.rename_playlist(playlist_id, _playlist_name(request.get_json(silent=True))):
            abort(404, "Playlist not found")
        return jsonify(ok=True)

    @app.delete("/api/playlists/<int:playlist_id>")
    def delete_playlist(playlist_id):
        if not library.delete_playlist(playlist_id):
            abort(404, "Playlist not found")
        return jsonify(ok=True)

    @app.post("/api/playlists/<int:playlist_id>/songs")
    def add_to_playlist(playlist_id):
        if not library.add_to_playlist(playlist_id, _clean_song(request.get_json(silent=True))):
            abort(404, "Playlist not found")
        return jsonify(ok=True)

    @app.delete("/api/playlists/<int:playlist_id>/songs/<video_id>")
    def remove_from_playlist(playlist_id, video_id):
        library.remove_from_playlist(playlist_id, video_id)
        return jsonify(ok=True)

    @app.put("/api/playlists/<int:playlist_id>/order")
    def reorder_playlist(playlist_id):
        ids = (request.get_json(silent=True) or {}).get("ids")
        if not isinstance(ids, list) or not all(isinstance(i, str) for i in ids):
            abort(400, "ids must be a list of song ids")
        library.reorder_playlist(playlist_id, ids)
        return jsonify(ok=True)

    @app.get("/api/history")
    def history():
        limit = max(1, min(request.args.get("limit", 50, type=int), 200))
        return jsonify(songs=library.history(limit))

    @app.post("/api/history")
    def add_history():
        library.add_history(_clean_song(request.get_json(silent=True)))
        return jsonify(ok=True)

    @app.delete("/api/history")
    def clear_history():
        library.clear_history()
        return jsonify(ok=True)

    @app.get("/api/state")
    def get_state():
        return jsonify(library.get_state("player") or {})

    @app.put("/api/state")
    def set_state():
        state = request.get_json(silent=True)
        if not isinstance(state, dict):
            abort(400, "State must be an object")
        queue = state.get("queue")
        if not isinstance(queue, list):
            abort(400, "queue must be a list")
        try:
            index = max(0, int(state.get("index") or 0))
            position = max(0.0, float(state.get("position") or 0))
        except (TypeError, ValueError):
            abort(400, "index and position must be numbers")
        clean = {"queue": [_clean_song(song) for song in queue[:500]], "index": index, "position": position}
        library.set_state("player", clean)
        return jsonify(ok=True)

    return app
