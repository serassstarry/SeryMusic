import re
import time

import pytest
import requests

import serymusic_web
from serymusic_web import create_app, parse_range, youtube

PASSWORD = "test-password"
SONG = {"id": "dN3y8kVNplA", "title": "DESPECHÁ", "artist": "ROSALÍA", "album": "MOTOMAMI +",
        "duration": 157, "thumbnail": "https://yt3.googleusercontent.com/x=w544-h544"}
OTHER = {**SONG, "id": "J-7knuczw2E", "title": "MONACO", "artist": "Bad Bunny"}


@pytest.fixture
def app(tmp_path, monkeypatch):
    monkeypatch.setattr(time, "sleep", lambda _: None)  # login failure delay
    return create_app(data_dir=tmp_path, password=PASSWORD)


@pytest.fixture
def client(app):
    client = app.test_client()
    assert client.post("/login", data={"password": PASSWORD}).status_code == 302
    return client


# Byte ranges

@pytest.mark.parametrize("header, expected", [
    (None, (0, 999)),
    ("bytes=0-", (0, 999)),
    ("bytes=100-199", (100, 199)),
    ("bytes=900-5000", (900, 999)),
    ("bytes=-100", (900, 999)),
    ("bytes=1000-", None),
    ("bytes=200-100", None),
    ("bytes=0-1,5-6", None),
    ("items=0-1", None),
    ("bytes=abc-", None),
])
def test_parse_range(header, expected):
    assert parse_range(header, 1000) == expected


# Auth

def test_requires_login(app):
    client = app.test_client()
    assert client.get("/api/favorites").status_code == 401
    page = client.get("/")
    assert page.status_code == 302 and page.headers["Location"].endswith("/login")
    # Login page and its assets stay reachable
    assert client.get("/login").status_code == 200
    assert client.get("/static/style.css").status_code == 200


def test_wrong_password_then_lockout(app):
    client = app.test_client()
    for _ in range(serymusic_web.MAX_LOGIN_FAILURES):
        assert client.post("/login", data={"password": "nope"}).headers["Location"].endswith("error=1")
    # Even the right password is refused while locked out
    assert client.post("/login", data={"password": PASSWORD}).headers["Location"].endswith("error=locked")
    assert client.get("/api/favorites").status_code == 401


def test_generated_password(tmp_path, monkeypatch):
    monkeypatch.delenv("SERYMUSIC_PASSWORD", raising=False)
    app = create_app(data_dir=tmp_path)
    password = (tmp_path / "password.txt").read_text().strip()
    assert app.config["PASSWORD_FILE"] == tmp_path / "password.txt"
    assert len(password) >= 12
    client = app.test_client()
    assert client.post("/login", data={"password": password}).headers["Location"] == "/"
    # Same password and session key after a restart
    assert create_app(data_dir=tmp_path).secret_key == app.secret_key


def test_logout(client):
    client.post("/logout")
    assert client.get("/api/favorites").status_code == 401


def test_security_headers(client):
    headers = client.get("/").headers
    assert "script-src 'self'" in headers["Content-Security-Policy"]
    assert headers["X-Content-Type-Options"] == "nosniff"


# Library

def test_favorites(client):
    assert client.put(f"/api/favorites/{SONG['id']}", json=SONG).status_code == 200
    client.put(f"/api/favorites/{OTHER['id']}", json=OTHER)
    songs = client.get("/api/favorites").json["songs"]
    assert [s["id"] for s in songs] == [OTHER["id"], SONG["id"]]  # newest first
    assert songs[1] == SONG
    assert set(client.get("/api/favorites/ids").json["ids"]) == {SONG["id"], OTHER["id"]}

    client.delete(f"/api/favorites/{SONG['id']}")
    assert client.get("/api/favorites/ids").json["ids"] == [OTHER["id"]]


def test_song_is_validated(client):
    assert client.put("/api/favorites/bad", json={"id": "bad"}).status_code == 400
    assert client.put(f"/api/favorites/{OTHER['id']}", json=SONG).status_code == 400
    # Untrusted thumbnail urls are dropped, long text is cut
    client.put(f"/api/favorites/{SONG['id']}", json={**SONG, "thumbnail": "javascript:alert(1)", "title": "x" * 1000})
    saved = client.get("/api/favorites").json["songs"][0]
    assert saved["thumbnail"] == "" and len(saved["title"]) == 300


def test_playlists(client):
    playlist_id = client.post("/api/playlists", json={"name": " Gym "}).json["id"]
    assert client.post("/api/playlists", json={"name": ""}).status_code == 400

    for song in (SONG, OTHER, SONG):  # adding twice keeps one copy
        assert client.post(f"/api/playlists/{playlist_id}/songs", json=song).status_code == 200
    playlist = client.get(f"/api/playlists/{playlist_id}").json
    assert playlist["name"] == "Gym"
    assert [s["id"] for s in playlist["songs"]] == [SONG["id"], OTHER["id"]]

    client.put(f"/api/playlists/{playlist_id}/order", json={"ids": [OTHER["id"]]})
    assert [s["id"] for s in client.get(f"/api/playlists/{playlist_id}").json["songs"]] == [OTHER["id"], SONG["id"]]

    listed = client.get("/api/playlists").json["playlists"]
    assert listed == [{"id": playlist_id, "name": "Gym", "count": 2, "thumbnail": OTHER["thumbnail"]}]

    client.patch(f"/api/playlists/{playlist_id}", json={"name": "Running"})
    client.delete(f"/api/playlists/{playlist_id}/songs/{OTHER['id']}")
    playlist = client.get(f"/api/playlists/{playlist_id}").json
    assert playlist["name"] == "Running" and [s["id"] for s in playlist["songs"]] == [SONG["id"]]

    assert client.delete(f"/api/playlists/{playlist_id}").status_code == 200
    assert client.get(f"/api/playlists/{playlist_id}").status_code == 404
    assert client.post(f"/api/playlists/{playlist_id}/songs", json=SONG).status_code == 404


def test_history(client):
    for song in (SONG, OTHER, SONG):
        client.post("/api/history", json=song)
        time.sleep(0.01)
    # Each song once, most recent first
    assert [s["id"] for s in client.get("/api/history").json["songs"]] == [SONG["id"], OTHER["id"]]
    client.delete("/api/history")
    assert client.get("/api/history").json["songs"] == []


def test_state(client):
    assert client.get("/api/state").json == {}
    state = {"queue": [SONG, OTHER], "index": 1, "position": 42.5}
    assert client.put("/api/state", json=state).status_code == 200
    # Songs come back with (empty) links to their artists and album
    links = {"artists": [], "album_id": None}
    assert client.get("/api/state").json == {**state, "queue": [{**SONG, **links}, {**OTHER, **links}]}
    assert client.put("/api/state", json={"queue": "nope"}).status_code == 400
    assert client.put("/api/state", json={"queue": [], "index": "x"}).status_code == 400


# YouTube

def test_search(client, monkeypatch):
    results = {"songs": [SONG], "artists": [{"id": "UCeYz6rzUGhVwqxRM37FUo8w", "name": "ROSALÍA"}], "albums": []}
    monkeypatch.setattr(youtube, "search_all", lambda q: results if q == "rosalia" else {})
    assert client.get("/api/search?q=rosalia").json == results
    assert client.get("/api/search?q=").json == {"songs": [], "artists": [], "albums": []}


def test_artist_and_album_pages(client, monkeypatch):
    artist_id, album_id = "UCeYz6rzUGhVwqxRM37FUo8w", "MPREb_VdtEnJZ2vqp"
    monkeypatch.setattr(youtube, "artist", lambda i: {"id": i, "name": "ROSALÍA", "songs": [SONG]})
    monkeypatch.setattr(youtube, "artist_songs", lambda i: [SONG, OTHER])
    monkeypatch.setattr(youtube, "album", lambda i: {"id": i, "title": "MOTOMAMI +", "songs": [SONG]})
    monkeypatch.setattr(youtube, "find_artist", lambda n: artist_id if n == "ROSALÍA" else None)
    assert client.get(f"/api/artist/{artist_id}").json["name"] == "ROSALÍA"
    assert len(client.get(f"/api/artist/{artist_id}/songs").json["songs"]) == 2
    assert client.get(f"/api/album/{album_id}").json["title"] == "MOTOMAMI +"
    assert client.get("/api/artist-by-name?name=ROSALÍA").json == {"id": artist_id}
    # Ids are checked before reaching YouTube
    assert client.get("/api/artist/not-an-id").status_code == 404
    assert client.get("/api/album/nope").status_code == 404
    assert client.get("/api/artist-by-name?name=Nobody").status_code == 404


def test_song_keeps_artist_and_album_links(client):
    song = {**SONG, "artists": [{"name": "ROSALÍA", "id": "UCeYz6rzUGhVwqxRM37FUo8w"}, {"name": "X", "id": "bad"}],
            "album_id": "MPREb_VdtEnJZ2vqp"}
    client.put("/api/state", json={"queue": [song], "index": 0, "position": 0})
    saved = client.get("/api/state").json["queue"][0]
    assert saved["artists"] == [{"name": "ROSALÍA", "id": "UCeYz6rzUGhVwqxRM37FUo8w"}, {"name": "X", "id": None}]
    assert saved["album_id"] == "MPREb_VdtEnJZ2vqp"


def test_search_error(client, monkeypatch):
    def fail(_):
        raise RuntimeError("ERROR: YouTube said no")
    monkeypatch.setattr(youtube, "search_all", fail)
    response = client.get("/api/search?q=x")
    assert response.status_code == 502 and response.json["error"] == "YouTube said no"


AUDIO = bytes(range(256)) * 40  # 10240 bytes


class FakeUpstream:
    def __init__(self, status, data=b""):
        self.status_code = status
        self.data = data

    def iter_content(self, size):
        for i in range(0, len(self.data), size):
            yield self.data[i:i + size]

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(f"{self.status_code}")

    def close(self):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *_):
        pass


@pytest.fixture
def fake_youtube(monkeypatch):
    """YouTube with a 10240 bytes song, `rejected` urls answer 403."""
    calls = {"resolve": [], "get": [], "rejected": set()}

    def stream(video_id, codec="aac", refresh=False):
        calls["resolve"].append(refresh)
        url = f"https://cdn.test/{video_id}?v={len(calls['resolve'])}"
        return youtube.Stream(url, {"User-Agent": "test"}, "audio/webm", len(AUDIO), time.time() + 3600)

    def get(self, url, headers=None, **_):
        calls["get"].append((url, headers["Range"]))
        if url in calls["rejected"]:
            return FakeUpstream(403)
        start, end = map(int, re.match(r"bytes=(\d+)-(\d+)", headers["Range"]).groups())
        return FakeUpstream(206, AUDIO[start:end + 1])

    monkeypatch.setattr(youtube, "stream", stream)
    monkeypatch.setattr(requests.Session, "get", get)
    monkeypatch.setattr(serymusic_web, "UPSTREAM_CHUNK", 4096)
    return calls


def test_stream_range(client, fake_youtube):
    response = client.get(f"/api/stream/{SONG['id']}", headers={"Range": "bytes=100-9999"})
    assert response.status_code == 206
    assert response.headers["Content-Range"] == f"bytes 100-9999/{len(AUDIO)}"
    assert response.headers["Content-Type"] == "audio/webm"
    assert response.data == AUDIO[100:10000]
    # Fetched from YouTube in small pieces
    assert [r for _, r in fake_youtube["get"]] == ["bytes=100-4195", "bytes=4196-8291", "bytes=8292-9999"]


def test_stream_whole_file(client, fake_youtube):
    response = client.get(f"/api/stream/{SONG['id']}")
    assert response.status_code == 200 and response.data == AUDIO


def test_stream_refreshes_rejected_url(client, fake_youtube):
    fake_youtube["rejected"].add(f"https://cdn.test/{SONG['id']}?v=1")
    response = client.get(f"/api/stream/{SONG['id']}", headers={"Range": "bytes=0-99"})
    assert response.status_code == 206 and response.data == AUDIO[:100]
    assert fake_youtube["resolve"] == [False, True]


def test_stream_invalid(client, fake_youtube):
    assert client.get("/api/stream/not-an-id").status_code == 404
    response = client.get(f"/api/stream/{SONG['id']}", headers={"Range": f"bytes={len(AUDIO)}-"})
    assert response.status_code == 416


# Metadata parsing

def test_song_from_search_result():
    item = {"videoId": SONG["id"], "title": "DESPECHÁ",
            "artists": [{"name": "ROSALÍA", "id": "UCeYz6rzUGhVwqxRM37FUo8w"}, {"name": None}],
            "album": {"name": "MOTOMAMI +", "id": "MPREb_VdtEnJZ2vqp"}, "duration": "2:37",
            "thumbnails": [{"url": "https://yt3.googleusercontent.com/a=w60-h60-l90-rj"}]}
    assert youtube._song(item) == {
        "id": SONG["id"], "title": "DESPECHÁ", "artist": "ROSALÍA",
        "artists": [{"name": "ROSALÍA", "id": "UCeYz6rzUGhVwqxRM37FUo8w"}],
        "album": "MOTOMAMI +", "album_id": "MPREb_VdtEnJZ2vqp", "duration": 157,
        "thumbnail": "https://yt3.googleusercontent.com/a=w544-h544-l90-rj",
    }
    # Album tracks take name, artists and cover from their album
    album = {"id": "MPREb_VdtEnJZ2vqp", "title": "MOTOMAMI +", "thumbnail": "https://cover",
             "artists": [{"name": "ROSALÍA", "id": "UCeYz6rzUGhVwqxRM37FUo8w"}]}
    track = youtube._song({"videoId": SONG["id"], "title": "SAOKO", "duration_seconds": 137}, album)
    assert (track["album"], track["album_id"], track["thumbnail"], track["artist"]) ==         ("MOTOMAMI +", "MPREb_VdtEnJZ2vqp", "https://cover", "ROSALÍA")
    assert youtube._song({"videoId": SONG["id"], "isAvailable": False}) is None
    assert youtube._song({"videoId": None}) is None


@pytest.mark.parametrize("text, seconds", [("2:37", 157), ("1:02:03", 3723), ("", 0), (None, 0), ("x:10", 0)])
def test_parse_duration(text, seconds):
    assert youtube._parse_duration(text) == seconds


def test_no_password(tmp_path, monkeypatch):
    monkeypatch.delenv("SERYMUSIC_PASSWORD", raising=False)
    app = create_app(data_dir=tmp_path, no_password=True)
    client = app.test_client()
    assert client.get("/api/favorites").status_code == 200
    assert client.get("/").status_code == 200
    assert client.get("/login").headers["Location"] == "/"
    assert client.get("/api/config").json == {"password": False}
    assert not (tmp_path / "password.txt").exists()


def test_config_with_password(client):
    assert client.get("/api/config").json == {"password": True}
