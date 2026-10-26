"""Outputs that send audio over the network: DLNA renderers and the live stream."""

import re
import shutil
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from serymusic_web import create_app, dlna, media
from serymusic_web.live import LiveBroadcast

FFMPEG = media.ffmpeg_path()
needs_ffmpeg = pytest.mark.skipif(not FFMPEG, reason="ffmpeg is not installed")


class FakeRenderer(BaseHTTPRequestHandler):
    """Answers like a TV: device description and AVTransport / RenderingControl SOAP."""

    state = {"transport": "NO_MEDIA_PRESENT", "volume": 8, "uri": None, "metadata": None, "position": 0}
    calls: list[str] = []

    def log_message(self, *args):
        pass

    def _reply(self, body: str, content_type="text/xml"):
        data = body.encode()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self._reply("""<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
            <friendlyName>BRAVIA KDL-48W705C</friendlyName><UDN>uuid:tv-1</UDN><serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
              <controlURL>/upnp/control/RenderingControl</controlURL></service>
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
              <controlURL>/upnp/control/AVTransport</controlURL></service>
            </serviceList></device></root>""")

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"])).decode()
        action = self.headers["SOAPAction"].strip('"').split("#")[1]
        self.calls.append(action)
        state = self.state
        values = {}
        if action == "SetAVTransportURI":
            state["uri"] = re.search(r"<CurrentURI>(.*?)</CurrentURI>", body).group(1)
            state["metadata"] = re.search(r"<CurrentURIMetaData>(.*?)</CurrentURIMetaData>", body, re.S).group(1)
        elif action == "Play":
            state["transport"] = "PLAYING"
        elif action == "Pause":
            state["transport"] = "PAUSED_PLAYBACK"
        elif action == "Stop":
            state["transport"] = "STOPPED"
        elif action == "SetVolume":
            state["volume"] = int(re.search(r"<DesiredVolume>(\d+)<", body).group(1))
        elif action == "GetVolume":
            values["CurrentVolume"] = state["volume"]
        elif action == "GetTransportInfo":
            values["CurrentTransportState"] = state["transport"]
        elif action == "GetPositionInfo":
            p = state["position"]
            values["RelTime"] = f"{p // 3600}:{p // 60 % 60:02d}:{p % 60:02d}"
        fields = "".join(f"<{k}>{v}</{k}>" for k, v in values.items())
        self._reply(f'<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>'
                    f'<u:{action}Response xmlns:u="x">{fields}</u:{action}Response></s:Body></s:Envelope>')


@pytest.fixture
def renderer():
    FakeRenderer.state = {"transport": "NO_MEDIA_PRESENT", "volume": 8, "uri": None, "metadata": None, "position": 0}
    FakeRenderer.calls = []
    server = ThreadingHTTPServer(("127.0.0.1", 0), FakeRenderer)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield dlna.describe(f"http://127.0.0.1:{server.server_port}/dmr.xml")
    server.shutdown()


def test_dlna_output(renderer, monkeypatch):
    monkeypatch.setattr(dlna, "POLL_SECONDS", 0)
    assert renderer.name == "BRAVIA KDL-48W705C"
    output = dlna.DlnaOutput(renderer)
    # Starts at the TV's own volume, never louder
    assert output.volume == 0.08

    song = {"id": "dN3y8kVNplA", "title": "DESPECHÁ & co", "artist": "ROSALÍA", "duration": 157,
            "thumbnail": "https://yt3.googleusercontent.com/a"}
    output.load("http://192.168.1.250:8090/api/transcode/dN3y8kVNplA.mp3?token=abc", 0, 157, song)
    state = FakeRenderer.state
    assert state["transport"] == "PLAYING"
    assert state["uri"] == "http://192.168.1.250:8090/api/transcode/dN3y8kVNplA.mp3?token=abc"
    # Song details reach the TV, escaped twice (XML inside SOAP)
    assert "DESPECH" in state["metadata"] and "&amp;amp;" in state["metadata"]

    assert output.get("time-pos") == 0 and output.ended is None
    output.command("set_property", "volume", 20)
    assert state["volume"] == 20

    # Stopped from the TV remote mid-song: not the end of the song
    state["position"] = 30
    output.get("time-pos")
    state["transport"] = "STOPPED"
    output.get("time-pos")
    assert output.ended == "stopped"

    # Reaching the end moves to the next song
    output.load("http://x/a.mp3?token=1", 0, 157, song)
    output.get("time-pos")
    state["position"], state["transport"] = 155, "STOPPED"
    output.get("time-pos")
    assert output.ended == "eof"


def test_dlna_start_timeout(renderer, monkeypatch):
    monkeypatch.setattr(dlna, "POLL_SECONDS", 0)
    monkeypatch.setattr(dlna, "START_TIMEOUT", 0.2)
    output = dlna.DlnaOutput(renderer)
    output.load("http://x/a.mp3?token=1", 0, 100)
    FakeRenderer.state["transport"] = "STOPPED"  # the TV couldn't open it
    output.get("time-pos")
    assert output.ended is None  # still loading
    time.sleep(0.3)
    output.get("time-pos")
    assert output.ended == "error"


@pytest.fixture
def tone(tmp_path):
    path = tmp_path / "tone.mp3"
    subprocess.run([FFMPEG, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                    "-c:a", "libmp3lame", str(path)], check=True)
    return str(path)


def mp3_frames(data: bytes) -> int:
    # MPEG-1 layer 3 frame headers
    return len(re.findall(rb"\xff[\xfa\xfb]", data))


@needs_ffmpeg
def test_live_broadcast(tone):
    broadcast = LiveBroadcast(FFMPEG, "http://pi:8090/live")
    try:
        received = bytearray()
        listener = broadcast.listen()
        threading.Thread(target=lambda: [received.extend(c) for c in listener], daemon=True).start()
        time.sleep(1.5)
        silence = len(received)
        assert silence > 0 and mp3_frames(received) > 10  # silence while nothing plays

        broadcast.load(tone, 0, 2)
        time.sleep(1)
        assert broadcast.get("time-pos") >= 0.9
        assert broadcast.describe() == {"kind": "live", "listen_url": "http://pi:8090/live"}
        deadline = time.time() + 10
        while broadcast.ended is None and time.time() < deadline:
            time.sleep(0.1)
        assert broadcast.ended == "eof"
        # The song was sent at playback speed, ~24 KB per second at 192 kbps
        assert len(received) - silence > 30_000

        broadcast.command("set_property", "pause", True)
        assert broadcast.listeners == 1
    finally:
        broadcast.close()


@needs_ffmpeg
def test_live_pause_keeps_position(tone):
    broadcast = LiveBroadcast(FFMPEG, "http://pi:8090/live")
    try:
        broadcast.load(tone, 0, 2)
        time.sleep(0.6)
        broadcast.command("set_property", "pause", True)
        paused_at = broadcast.get("time-pos")
        time.sleep(0.5)
        assert broadcast.get("time-pos") == paused_at
        broadcast.command("set_property", "pause", False)
        time.sleep(0.3)
        assert broadcast.get("time-pos") > paused_at
        assert broadcast.ended is None
    finally:
        broadcast.close()


def test_transcode_and_live_endpoints(tmp_path, monkeypatch):
    calls = []

    def fake_stream(ffmpeg, source, start=0):
        calls.append((source, start))
        yield b"\xff\xfbmp3"

    monkeypatch.setattr(media, "ffmpeg_path", lambda: "ffmpeg")
    monkeypatch.setattr(media, "mp3_stream", fake_stream)
    app = create_app(data_dir=tmp_path, password="pw")
    app.config["SELF_URL"] = "http://127.0.0.1:8090"
    client = app.test_client()

    link = None
    client.post("/login", data={"password": "pw"})
    link = client.get("/api/streamlink/dN3y8kVNplA?format=mp3").json["path"]
    client.post("/logout")
    assert link.startswith("/api/transcode/dN3y8kVNplA.mp3?token=")

    # The TV has no session, the token is enough
    head = client.head(link)
    assert head.status_code == 200 and head.headers["Content-Type"] == "audio/mpeg"
    assert "DLNA.ORG_PN=MP3" in head.headers["contentFeatures.dlna.org"]
    response = client.get(link + "&start=42")
    assert response.data == b"\xff\xfbmp3"
    assert calls[0][0].startswith("http://127.0.0.1:8090/api/stream/dN3y8kVNplA?codec=aac&token=")
    assert calls[0][1] == 42
    assert client.get("/api/transcode/dN3y8kVNplA.mp3?token=wrong").status_code == 401

    # No live stream configured
    assert client.get(f"/live?token={app.config['LIVE_TOKEN']}").status_code == 503
    assert client.get("/live").status_code == 302
