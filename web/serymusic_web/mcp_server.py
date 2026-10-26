"""
MCP server: lets an AI agent search music and control the SeryMusic players.

Served over Streamable HTTP on its own port (see `app.py`), e.g. `http://<server>:8091/mcp`.
"""

from __future__ import annotations

import hmac
import logging
from functools import partial
from typing import Any, Literal

import anyio
from mcp.server.mcpserver import Context, MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp.server.transport_security import TransportSecuritySettings
from mcp.types import ToolAnnotations
from starlette.types import ASGIApp, Receive, Scope, Send

from .remote import Remote, RemoteError

log = logging.getLogger("serymusic.mcp")

INSTRUCTIONS = """\
SeryMusic plays music from YouTube Music on the user's devices.

Music plays on a device, not here. Devices are: browsers with SeryMusic open, computers running the headless
player, network TVs and speakers (DLNA, e.g. "BRAVIA" is a Sony TV), and the live stream "Emisión", which
anyone hears by opening its listen_url in any player (VLC, a phone, a TV).
When the user asks for music, call play_music: it searches and starts playing in one step, then keeps going
with similar songs.

Without `device`, music goes to the player on the same machine you (the agent) run on, so it plays where the user
is talking to you; failing that, to the device already playing, or the last one used.
If the user says where to play ("en el salón", "on my phone"), pass that as `device`; list_devices shows the names.

If a result has a `warning`, tell the user what it says (e.g. tap the screen when the browser blocked autoplay).
If no device fits, offer the live stream: play on "Emisión" and give the user its listen_url.
If a result has a listen_url, give it to the user.
If you can play audio yourself, get_stream_url gives a direct link to the audio instead.
"""

READ_ONLY = ToolAnnotations(read_only_hint=True, open_world_hint=True)
CONTROL = ToolAnnotations(read_only_hint=False, destructive_hint=False, open_world_hint=True)


def _caller(ctx: Context) -> str | None:
    """IP the agent connects from, the player on that same machine gets the music."""
    request = ctx.request_context.request
    client = getattr(request, "client", None)
    return client.host if client else None


async def _call(function, *args, **kwargs):
    """Runs a blocking action off the event loop, errors become messages the agent can act on."""
    try:
        return await anyio.to_thread.run_sync(partial(function, *args, **kwargs))
    except RemoteError as error:
        raise ToolError(str(error)) from error
    except Exception as error:
        log.exception("MCP action failed")
        raise ToolError(f"Request to YouTube Music failed: {error}") from error


def build_server(remote: Remote) -> MCPServer:
    server = MCPServer(name="SeryMusic", title="SeryMusic", instructions=INSTRUCTIONS, version="1.0")

    @server.tool(annotations=READ_ONLY)
    async def search_music(query: str, limit: int = 10) -> list[dict[str, Any]]:
        """Search songs on YouTube Music. Returns id, title, artist, album and duration (seconds) of each song."""
        return await _call(remote.search, query, limit)

    @server.tool(annotations=CONTROL)
    async def play_music(ctx: Context, query: str | None = None, song_id: str | None = None,
                         device: str | None = None, radio: bool = True) -> dict[str, Any]:
        """
        Play a song right away on a device, replacing its queue.

        query: what the user asked for, e.g. "despechá rosalía"; the best match plays.
        song_id: exact song from search_music, instead of query.
        device: name of the device to play on; default is the player on your machine, else the one playing or last used.
        radio: keep playing similar songs afterwards (default true).
        """
        return await _call(remote.play, query, song_id, device, radio, near=_caller(ctx))

    @server.tool(annotations=CONTROL)
    async def play_playlist(ctx: Context, name: str, shuffle: bool = False, device: str | None = None) -> dict[str, Any]:
        """Play one of the user's playlists, or their liked songs with name "favorites". list_playlists shows the names."""
        return await _call(remote.play_collection, name, shuffle, device, near=_caller(ctx))

    @server.tool(annotations=CONTROL)
    async def add_to_queue(ctx: Context, query: str | None = None, song_id: str | None = None,
                           play_next: bool = False, device: str | None = None) -> dict[str, Any]:
        """Add a song to the queue of a device without interrupting the current one. play_next puts it right after the current song."""
        return await _call(remote.enqueue, query, song_id, play_next, device, near=_caller(ctx))

    @server.tool(annotations=CONTROL)
    async def control_playback(ctx: Context, action: Literal["pause", "resume", "next", "previous", "stop"],
                               device: str | None = None) -> dict[str, Any]:
        """Pause, resume, skip to the next song, go back to the previous one, or stop."""
        return await _call(remote.control, action, device, near=_caller(ctx))

    @server.tool(annotations=CONTROL)
    async def set_volume(ctx: Context, level: int, device: str | None = None) -> dict[str, Any]:
        """Set the volume of a device, from 0 (mute) to 100."""
        return await _call(remote.set_volume, level, device, near=_caller(ctx))

    @server.tool(annotations=READ_ONLY)
    async def now_playing(ctx: Context, device: str | None = None) -> dict[str, Any]:
        """What a device is playing: song, position, volume and the next songs in its queue."""
        return await _call(remote.now_playing, device, near=_caller(ctx))

    @server.tool(annotations=READ_ONLY)
    async def list_devices(ctx: Context) -> list[dict[str, Any]]:
        """
        Devices running a SeryMusic player. Only the ones with can_be_controlled=true can play music;
        same_device_as_you marks the player on the machine you run on.
        """
        return await _call(remote.devices, near=_caller(ctx))

    @server.tool(annotations=READ_ONLY)
    async def list_playlists() -> list[dict[str, Any]]:
        """The user's playlists with their number of songs, plus "favorites" (liked songs)."""
        return await _call(remote.playlists)

    @server.tool(annotations=ToolAnnotations(read_only_hint=False, destructive_hint=False, idempotent_hint=True))
    async def like_song(ctx: Context, song_id: str | None = None, device: str | None = None) -> dict[str, Any]:
        """Add a song to the user's favorites. Without song_id, the song playing on the device is liked."""
        return await _call(remote.like, song_id, device, near=_caller(ctx))

    @server.tool(annotations=READ_ONLY)
    async def get_stream_url(query: str | None = None, song_id: str | None = None) -> dict[str, Any]:
        """
        Direct link to the audio of a song (AAC/m4a over HTTP, supports range requests), for agents
        that play audio themselves instead of using a SeryMusic player.
        """
        return await _call(remote.get_stream_url, query, song_id)

    return server


class _RequirePassword:
    """With a password configured, agents must send it as `Authorization: Bearer <password>`."""

    def __init__(self, app: ASGIApp, password: str):
        self.app = app
        self.expected = f"Bearer {password}".encode()

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "http":
            given = dict(scope.get("headers") or []).get(b"authorization", b"")
            if not hmac.compare_digest(given, self.expected):
                await send({"type": "http.response.start", "status": 401,
                            "headers": [(b"content-type", b"application/json"), (b"www-authenticate", b"Bearer")]})
                await send({"type": "http.response.body", "body": b'{"error":"Send the SeryMusic password as a Bearer token"}'})
                return
        await self.app(scope, receive, send)


def build_app(remote: Remote, password: str | None, allowed_hosts: list[str] | None = None) -> ASGIApp:
    """
    ASGI app serving MCP at `/mcp`.

    [allowed_hosts] turns on DNS rebinding protection (a web page making the user's browser call
    this server), off by default because the LAN address of the server isn't known.
    """
    security = TransportSecuritySettings(
        enable_dns_rebinding_protection=bool(allowed_hosts),
        allowed_hosts=allowed_hosts or [],
    )
    app = build_server(remote).streamable_http_app(
        streamable_http_path="/mcp",
        # No session state to lose when the server restarts
        stateless_http=True,
        transport_security=security,
        host="0.0.0.0",
    )
    return _RequirePassword(app, password) if password else app
