"""
DLNA / UPnP renderers (smart TVs, network speakers...): found on the network and offered as devices.

The server tells the renderer which url to play (the song converted to MP3), and drives it with
UPnP AVTransport / RenderingControl commands. Nothing to install on the renderer.
"""

from __future__ import annotations

import hashlib
import logging
import re
import socket
import threading
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass
from xml.sax.saxutils import escape

from . import media

log = logging.getLogger("serymusic.dlna")

AVTRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
RENDERING = "urn:schemas-upnp-org:service:RenderingControl:1"
SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"
# State is read from the renderer at most this often
POLL_SECONDS = 1.0
# A song stopping this close to its end finished, earlier it was stopped from the device
END_TOLERANCE = 15
# A renderer that didn't start playing after this long couldn't play the song
START_TIMEOUT = 20


@dataclass
class Renderer:
    udn: str
    name: str
    location: str
    avtransport: str
    rendering: str | None


def _tag(xml: str, name: str) -> str | None:
    match = re.search(rf"<(?:\w+:)?{name}>(.*?)</(?:\w+:)?{name}>", xml, re.S)
    return match.group(1).strip() if match else None


def describe(location: str) -> Renderer | None:
    """Reads a renderer's description, `None` when it can't play media."""
    xml = urllib.request.urlopen(location, timeout=5).read().decode("utf-8", "replace")
    services = {}
    for service in re.findall(r"<service>(.*?)</service>", xml, re.S):
        kind, control = _tag(service, "serviceType"), _tag(service, "controlURL")
        if kind and control:
            services[kind.rsplit(":", 1)[0]] = urllib.parse.urljoin(location, control)
    avtransport = services.get(AVTRANSPORT.rsplit(":", 1)[0])
    if not avtransport:
        return None
    return Renderer(
        udn=_tag(xml, "UDN") or location,
        name=(_tag(xml, "friendlyName") or "DLNA")[:40],
        location=location,
        avtransport=avtransport,
        rendering=services.get(RENDERING.rsplit(":", 1)[0]),
    )


def discover(bind_address: str = "", port: int = 0, timeout: float = 3) -> list[Renderer]:
    """
    SSDP search for renderers. Replies come back to [port], a firewall must let UDP in on it
    (unless 0: random port, fine without firewall).
    """
    message = "\r\n".join([
        "M-SEARCH * HTTP/1.1", "HOST: 239.255.255.250:1900", 'MAN: "ssdp:discover"', "MX: 2",
        f"ST: {SEARCH_TARGET}", "", "",
    ]).encode()
    locations = set()
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
        if bind_address:
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(bind_address))
        sock.bind((bind_address, port))
        sock.settimeout(0.5)
        for _ in range(2):
            sock.sendto(message, ("239.255.255.250", 1900))
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                data, _ = sock.recvfrom(65535)
            except socket.timeout:
                continue
            match = re.search(r"(?im)^location:\s*(\S+)", data.decode("utf-8", "replace"))
            if match:
                locations.add(match.group(1))

    renderers = {}
    for location in locations:
        try:
            renderer = describe(location)
        except Exception as error:
            log.debug("Can't read %s: %s", location, error)
            continue
        if renderer:
            renderers[renderer.udn] = renderer
    return list(renderers.values())


def _seconds(text: str | None) -> float:
    """`"0:03:25.120"` -> 205.12"""
    try:
        h, m, s = (text or "").split(":")
        return int(h) * 3600 + int(m) * 60 + float(s)
    except ValueError:
        return 0.0


def _clock(seconds: float) -> str:
    seconds = int(seconds)
    return f"{seconds // 3600}:{seconds // 60 % 60:02d}:{seconds % 60:02d}"


class DlnaOutput:
    """Output for the headless player (see `headless.Mpv` for the interface), playing on a renderer."""

    media_format = "mp3"
    fallback_format = "mp3"

    def __init__(self, renderer: Renderer):
        self.renderer = renderer
        self.ended: str | None = None
        self._lock = threading.Lock()
        self._url: str | None = None
        self._duration = 0.0
        self._expect_playing = False
        self._state = "NO_MEDIA_PRESENT"
        self._position = 0.0
        self._polled = 0.0
        self._loaded_at = 0.0
        self._offset = 0.0
        # Starts at the renderer's own volume, music never blasts a TV left at a low volume
        self.volume = self._read_volume()

    def _soap(self, control: str, service: str, action: str, **args) -> str:
        body = "".join(f"<{key}>{escape(str(value))}</{key}>" for key, value in args.items())
        envelope = (
            '<?xml version="1.0" encoding="utf-8"?>'
            '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
            f'<u:{action} xmlns:u="{service}">{body}</u:{action}></s:Body></s:Envelope>'
        )
        request = urllib.request.Request(control, envelope.encode(), {
            "Content-Type": 'text/xml; charset="utf-8"',
            "SOAPAction": f'"{service}#{action}"',
        })
        with self._lock:
            return urllib.request.urlopen(request, timeout=8).read().decode("utf-8", "replace")

    def _transport(self, action: str, **args) -> str:
        return self._soap(self.renderer.avtransport, AVTRANSPORT, action, InstanceID=0, **args)

    def _read_volume(self) -> float:
        if not self.renderer.rendering:
            return 0.5
        try:
            reply = self._soap(self.renderer.rendering, RENDERING, "GetVolume", InstanceID=0, Channel="Master")
            return int(_tag(reply, "CurrentVolume") or 50) / 100
        except Exception:
            return 0.5

    @staticmethod
    def _metadata(url: str, title: str, artist: str, cover: str) -> str:
        """DIDL-Lite description of the song, shown by TVs and needed by some to play at all."""
        cover_tag = f"<upnp:albumArtURI>{escape(cover)}</upnp:albumArtURI>" if cover else ""
        return (
            '<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
            'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
            '<item id="1" parentID="0" restricted="1">'
            f"<dc:title>{escape(title)}</dc:title><upnp:artist>{escape(artist)}</upnp:artist>{cover_tag}"
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>"
            f'<res protocolInfo="http-get:*:audio/mpeg:{media.DLNA_MP3_FEATURES}">{escape(url)}</res>'
            "</item></DIDL-Lite>"
        )

    def load(self, url: str, start: float = 0, duration: float = 0, song: dict | None = None) -> None:
        self.ended = None
        song = song or {}
        if start > 0:
            # Converted streams can't seek, the conversion starts at the right time instead
            url += f"&start={start:.0f}"
        self._url, self._duration = url, max(duration - start, 0)
        self._expect_playing = False
        try:
            self._transport("Stop")
        except Exception:
            pass  # Nothing was playing
        metadata = self._metadata(url, song.get("title") or "SeryMusic", song.get("artist", ""), song.get("thumbnail", ""))
        self._transport("SetAVTransportURI", CurrentURI=url, CurrentURIMetaData=metadata)
        self._transport("Play", Speed=1)
        # Until the renderer reports PLAYING, STOPPED only means it's still loading
        self._state = "LOADING"
        self._loaded_at = time.monotonic()
        # The converted stream starts at `start`, the renderer counts from 0
        self._offset = start
        self._position = 0.0
        self._expect_playing = True
        self._polled = 0

    def _poll(self) -> None:
        if time.monotonic() - self._polled < POLL_SECONDS:
            return
        self._polled = time.monotonic()
        try:
            state = _tag(self._transport("GetTransportInfo"), "CurrentTransportState") or "STOPPED"
            self._position = _seconds(_tag(self._transport("GetPositionInfo"), "RelTime"))
        except Exception as error:
            log.debug("%s didn't answer: %s", self.renderer.name, error)
            return
        if self._state == "LOADING" and state != "PLAYING":
            if self._expect_playing and time.monotonic() - self._loaded_at > START_TIMEOUT:
                self._expect_playing = False
                self.ended = "error"
            return
        if self._expect_playing and state in ("STOPPED", "NO_MEDIA_PRESENT") and self._state in ("PLAYING", "TRANSITIONING"):
            self._expect_playing = False
            near_end = not self._duration or self._position >= self._duration - END_TOLERANCE
            self.ended = "eof" if near_end else "stopped"
        self._state = state

    def command(self, *args):
        if args[:2] == ("set_property", "pause"):
            if args[2]:
                self._transport("Pause")
            else:
                self._transport("Play", Speed=1)
                self._expect_playing = True
        elif args[:2] == ("set_property", "volume") and self.renderer.rendering:
            self._soap(self.renderer.rendering, RENDERING, "SetVolume", InstanceID=0, Channel="Master",
                       DesiredVolume=max(0, min(int(args[2]), 100)))
            self.volume = int(args[2]) / 100
        elif args[0] == "seek" and self._url:
            try:
                self._transport("Seek", Unit="REL_TIME", Target=_clock(float(args[1])))
            except Exception:
                # Converted streams usually can't seek: start the song over
                self.load(self._url.split("&start=")[0], float(args[1]), self._duration)
        elif args[0] == "quit":
            self.close()

    def get(self, name: str, default=None):
        self._poll()
        if name == "time-pos":
            return self._offset + self._position
        if name == "idle-active":
            return self._state in ("STOPPED", "NO_MEDIA_PRESENT") and not self._expect_playing
        return default

    def describe(self) -> dict:
        return {"kind": "dlna"}

    def close(self) -> None:
        if self._expect_playing:
            try:
                self._transport("Stop")
            except Exception:
                pass
        self._expect_playing = False


class DlnaManager:
    """Searches the network every [interval] seconds, each renderer found becomes a device."""

    def __init__(self, server: str, media_base: str, password: str | None, bind_address: str = "",
                 port: int = 0, interval: int = 60):
        self.server, self.media_base, self.password = server, media_base, password
        self.bind_address, self.port, self.interval = bind_address, port, interval
        self.players: dict[str, object] = {}
        self._missed: dict[str, int] = {}

    def start(self) -> None:
        threading.Thread(target=self._loop, name="dlna", daemon=True).start()

    def _loop(self) -> None:
        from .headless import HeadlessPlayer

        while True:
            try:
                found = {r.udn: r for r in discover(self.bind_address, self.port)}
            except OSError as error:
                log.warning("DLNA search failed: %s", error)
                found = {}
            for udn, renderer in found.items():
                self._missed[udn] = 0
                if udn not in self.players:
                    try:
                        device_id = "dlna-" + hashlib.sha256(udn.encode()).hexdigest()[:16]
                        player = HeadlessPlayer(self.server, renderer.name, self.password, output=DlnaOutput(renderer),
                                                device_id=device_id, media_base=self.media_base)
                        player.start()
                        self.players[udn] = player
                        log.info("DLNA device found: %s (%s)", renderer.name, renderer.location)
                    except Exception:
                        log.exception("Can't use DLNA device %s", renderer.name)
            # Renderers turned off disappear after 3 searches without answer
            for udn in list(self.players):
                if udn not in found:
                    self._missed[udn] = self._missed.get(udn, 0) + 1
                    if self._missed[udn] >= 3:
                        log.info("DLNA device gone: %s", self.players[udn].name)
                        self.players.pop(udn).stop()
            time.sleep(self.interval)
