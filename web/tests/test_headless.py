import threading
import time

import pytest
from werkzeug.serving import make_server

from serymusic_web import create_app, headless, remote as remote_module, youtube

SONG = {"id": "dN3y8kVNplA", "title": "DESPECHÁ", "artist": "ROSALÍA", "album": "", "duration": 157, "thumbnail": ""}
RADIO = [{**SONG, "id": f"radio{i:06d}", "title": f"Radio {i}"} for i in range(2)]


class FakeMpv:
    """Records what the player asks mpv to do, a song "ends" when the test says so."""

    media_format, fallback_format = "opus", "aac"

    def __init__(self, volume):
        self.volume = volume
        self.loaded, self.properties, self.ended = [], {"volume": round(volume * 100)}, None
        self.position = 0.0

    def describe(self):
        return {"kind": "speaker"}

    def command(self, *args):
        if args[0] == "set_property":
            self.properties[args[1]] = args[2]
        elif args[0] == "seek":
            self.position = args[1]

    def get(self, name, default=None):
        return {"time-pos": self.position, "idle-active": False}.get(name, default)

    def load(self, url, start=0, duration=0, song=None):
        self.loaded.append(url)
        self.position = start

    def close(self):
        pass


def wait_for(condition, timeout=5):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if condition():
            return True
        time.sleep(0.05)
    return False


@pytest.fixture
def server(tmp_path, monkeypatch):
    monkeypatch.setattr(youtube, "search", lambda q: [SONG])
    monkeypatch.setattr(youtube, "radio", lambda i: RADIO if i == SONG["id"] else [])
    monkeypatch.setattr(remote_module, "CONFIRM_TIMEOUT", 3)
    monkeypatch.setattr(headless, "Mpv", FakeMpv)
    monkeypatch.setattr(headless, "POLL_SECONDS", 0.05)
    app = create_app(data_dir=tmp_path, password="pw")
    http = make_server("127.0.0.1", 0, app, threaded=True)
    threading.Thread(target=http.serve_forever, daemon=True).start()
    yield app, f"http://127.0.0.1:{http.server_port}"
    http.shutdown()


def test_headless_player_obeys_the_agent(server):
    app, url = server
    player = headless.HeadlessPlayer(url, "Altavoz salón", password="pw")
    player.start()
    try:
        remote = app.extensions["serymusic"]["remote"]
        assert wait_for(lambda: remote.hub.find("altavoz"))

        result = remote.play("rosalia")
        assert result["device"] == "Altavoz salón" and "warning" not in result
        # mpv plays the song through the server, with a link that needs no session
        assert f"/api/stream/{SONG['id']}?codec=opus&token=" in player.output.loaded[-1]

        remote.set_volume(30)
        assert wait_for(lambda: player.output.properties.get("volume") == 30)

        # A song ending moves to the next one
        player.output.ended = "eof"
        assert wait_for(lambda: player.current()["id"] == RADIO[0]["id"])
        assert remote.now_playing()["song"] == "Radio 0 - ROSALÍA"

        assert remote.control("pause")["playing"] is False
        assert player.output.properties["pause"] is True

        # Counted as listened after 10 seconds
        player.output.position = 11
        remote.control("resume")
        assert wait_for(lambda: app.extensions["serymusic"]["library"].history())
    finally:
        player.stop()
    assert wait_for(lambda: not remote.hub.find("altavoz"))
