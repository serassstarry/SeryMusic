import json
import threading
import time

import pytest
from mcp import Client

from serymusic_web import create_app, remote as remote_module, youtube
from serymusic_web.devices import DeviceHub
from serymusic_web.mcp_server import build_server
from serymusic_web.remote import RemoteError

SONG = {"id": "dN3y8kVNplA", "title": "DESPECHÁ", "artist": "ROSALÍA", "album": "MOTOMAMI +",
        "duration": 157, "thumbnail": "https://yt3.googleusercontent.com/a"}
RADIO = [{**SONG, "id": f"radio{i:06d}", "title": f"Radio {i}"} for i in range(3)]
OTHER = {**SONG, "id": "J-7knuczw2E", "title": "MONACO", "artist": "Bad Bunny"}


@pytest.fixture
def anyio_backend():
    return "asyncio"


@pytest.fixture(autouse=True)
def fake_youtube(monkeypatch):
    songs = {s["id"]: s for s in [SONG, OTHER, *RADIO]}
    monkeypatch.setattr(youtube, "search", lambda q: [OTHER] if "monaco" in q.lower() else ([SONG] if "rosalia" in q.lower() else []))
    monkeypatch.setattr(youtube, "song", lambda i: songs.get(i))
    monkeypatch.setattr(youtube, "radio", lambda i: RADIO)
    monkeypatch.setattr(remote_module, "CONFIRM_TIMEOUT", 2)


class FakePlayer:
    """Behaves like a browser tab: obeys commands and reports its state."""

    def __init__(self, hub: DeviceHub, device_id: str, name: str, autoplay_allowed: bool = True, kind: str = "browser"):
        self.hub, self.id, self.name, self.kind = hub, device_id, name, kind
        self.autoplay_allowed = autoplay_allowed
        self.queue, self.index, self.playing, self.blocked, self.volume = [], 0, False, False, 1.0
        self.commands = hub.connect(device_id, name)
        self.received = []
        self.running = True
        self.report()
        threading.Thread(target=self.loop, daemon=True).start()

    def report(self):
        song = self.queue[self.index] if self.queue else None
        self.hub.report(self.id, self.name, {"kind": self.kind, "song": song, "playing": self.playing, "blocked": self.blocked,
                                             "position": 0, "volume": self.volume,
                                             "upcoming": self.queue[self.index + 1:self.index + 6]})

    def loop(self):
        while self.running:
            try:
                command = self.commands.get(timeout=0.1)
            except Exception:
                continue
            self.received.append(command)
            kind = command["type"]
            if kind == "play":
                self.queue, self.index = command["songs"], command["index"]
                self.playing, self.blocked = self.autoplay_allowed, not self.autoplay_allowed
            elif kind in ("pause", "stop"):
                self.playing = False
            elif kind == "resume":
                self.playing = True
            elif kind == "next" and self.index + 1 < len(self.queue):
                self.index += 1
            elif kind == "volume":
                self.volume = command["level"]
            elif kind == "enqueue":
                self.queue.insert(self.index + 1, command["song"]) if command["next"] else self.queue.append(command["song"])
            self.report()

    def close(self):
        self.running = False
        self.hub.disconnect(self.id, self.commands)


@pytest.fixture
def app(tmp_path):
    return create_app(data_dir=tmp_path, password="pw")


@pytest.fixture
def services(app):
    return app.extensions["serymusic"]


@pytest.fixture
def player(services):
    p = FakePlayer(services["hub"], "device-living-room", "Salón")
    yield p
    p.close()


def test_no_device_explains_what_to_do(services):
    with pytest.raises(RemoteError, match="No SeryMusic player is open"):
        services["remote"].play("rosalia")


def test_play_with_radio(services, player):
    result = services["remote"].play("rosalia")
    assert result == {"device": "Salón", "now_playing": "DESPECHÁ - ROSALÍA", "songs_in_queue": 4}
    assert [s["id"] for s in player.queue] == [SONG["id"]] + [s["id"] for s in RADIO]


def test_play_without_radio_and_by_id(services, player):
    services["remote"].play(song_id=OTHER["id"], radio=False)
    assert player.queue == [OTHER]
    with pytest.raises(RemoteError, match="valid song id"):
        services["remote"].play(song_id="nope")
    with pytest.raises(RemoteError, match="Nothing found"):
        services["remote"].play("zzz")


def test_picks_device_by_name(services, player):
    other = FakePlayer(services["hub"], "device-phone-0001", "Móvil Android")
    try:
        assert services["remote"].play("rosalia", device="móvil")["device"] == "Móvil Android"
        assert other.playing and not player.playing
        # Without a name, the device playing is chosen
        assert services["remote"].now_playing()["device"] == "Móvil Android"
        with pytest.raises(RemoteError, match="No device called 'cocina'.*Salón"):
            services["remote"].play("rosalia", device="cocina")
    finally:
        other.close()


def test_blocked_autoplay_is_reported(services):
    blocked = FakePlayer(services["hub"], "device-tablet-01", "Tablet", autoplay_allowed=False)
    try:
        assert "tap the SeryMusic page" in services["remote"].play("rosalia")["warning"]
    finally:
        blocked.close()


def test_controls(services, player):
    remote = services["remote"]
    remote.play("rosalia")
    assert remote.control("next") == {"device": "Salón", "done": "next", "now_playing": "Radio 0 - ROSALÍA", "playing": True}
    assert remote.control("pause")["playing"] is False
    assert remote.control("resume")["playing"] is True
    assert remote.set_volume(150) == {"device": "Salón", "volume": 100}
    remote.set_volume(40)
    remote.enqueue("monaco", play_next=True)
    time.sleep(0.3)
    state = remote.now_playing()
    assert state["song"] == "Radio 0 - ROSALÍA" and state["volume"] == 40
    assert state["up_next"][0] == "MONACO - Bad Bunny"
    with pytest.raises(RemoteError, match="Unknown action"):
        remote.control("explode")


def test_like_current_and_collections(services, player):
    remote = services["remote"]
    with pytest.raises(RemoteError, match="Nothing is playing"):
        remote.like()
    remote.play("rosalia", radio=False)
    assert remote.like() == {"added_to_favorites": "DESPECHÁ - ROSALÍA"}
    time.sleep(0.3)
    assert {"type": "library"} in player.received

    library = services["library"]
    playlist_id = library.create_playlist("Gym Mix")
    library.add_to_playlist(playlist_id, OTHER)
    assert remote.play_collection("gym")["collection"] == "Gym Mix"
    assert player.queue == [OTHER]
    assert remote.play_collection("favoritos")["collection"] == "Favoritos"
    assert remote.playlists() == [{"name": "favorites", "songs": 1}, {"name": "Gym Mix", "songs": 1}]
    with pytest.raises(RemoteError, match="Playlists: Gym Mix"):
        remote.play_collection("jazz")


def test_stream_url_works_without_login(app, services, monkeypatch):
    app.config["PUBLIC_URL"] = "http://192.168.1.250:8090/"
    result = services["remote"].get_stream_url("rosalia")
    assert result["url"].startswith(f"http://192.168.1.250:8090/api/stream/{SONG['id']}?codec=aac&token=")

    monkeypatch.setattr(youtube, "stream", lambda *a, **k: (_ for _ in ()).throw(RuntimeError("stop here")))
    client = app.test_client()
    path = result["url"].removeprefix("http://192.168.1.250:8090")
    assert client.get(path).status_code == 502  # got past the login
    assert client.get(path.replace("token=", "token=x")).status_code == 401
    assert client.get(f"/api/stream/{OTHER['id']}?token={path.split('token=')[1]}").status_code == 401


def test_device_endpoints(app):
    client = app.test_client()
    client.post("/login", data={"password": "pw"})
    response = client.get("/api/devices/device-browser-1/events?name=PC%20Windows")
    assert response.mimetype == "text/event-stream"
    assert next(response.response) == b"retry: 3000\n\n"
    hub = app.extensions["serymusic"]["hub"]
    assert hub.send("device-browser-1", {"type": "pause"})
    assert next(response.response) == f"data: {json.dumps({'type': 'pause'})}\n\n".encode()

    assert client.post("/api/devices/device-browser-1/state",
                       json={"name": "PC Windows", "song": SONG, "playing": True, "volume": 0.5}).status_code == 200
    devices = client.get("/api/devices").json["devices"]
    assert devices[0]["name"] == "PC Windows" and devices[0]["song"]["id"] == SONG["id"]
    assert client.post("/api/devices/x/state", json={}).status_code == 404
    assert client.post("/api/devices/device-browser-1/state", json={"volume": "loud"}).status_code == 400
    response.close()
    assert hub.devices()[0]["connected"] is False


@pytest.mark.anyio
async def test_mcp_tools(services, player):
    server = build_server(services["remote"])
    async with Client(server) as client:
        names = {tool.name for tool in (await client.list_tools()).tools}
        assert names == {"search_music", "play_music", "play_playlist", "add_to_queue", "control_playback",
                         "set_volume", "now_playing", "list_devices", "list_playlists", "like_song", "get_stream_url"}

        result = await client.call_tool("play_music", {"query": "rosalia"})
        assert not result.is_error
        assert result.structured_content["now_playing"] == "DESPECHÁ - ROSALÍA"

        # Errors reach the agent with what to do about them
        failed = await client.call_tool("play_music", {"query": "rosalia", "device": "cocina"})
        assert failed.is_error and "No device called 'cocina'" in failed.content[0].text

        devices = await client.call_tool("list_devices", {})
        assert "Salón" in devices.content[0].text


def test_device_names_ignore_accents_and_server_devices_are_not_nearby(services):
    hub = services["hub"]
    live = FakePlayer(hub, "live-0000000000000001", "Emisión", kind="live")
    tv = FakePlayer(hub, "dlna-0000000000000001", "BRAVIA KDL-48W705C", kind="dlna")
    try:
        for player in (live, tv):
            hub.report(player.id, None, {**hub.find(player.id), "kind": player.kind}, "127.0.0.1")
        assert services["remote"].play("rosalia", device="emision")["device"] == "Emisión"
        # An agent on the server itself isn't next to the TV
        assert not any(d["same_device_as_you"] for d in services["remote"].devices(near="127.0.0.1"))
    finally:
        live.close()
        tv.close()
