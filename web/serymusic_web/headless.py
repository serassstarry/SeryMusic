"""
Headless SeryMusic player: plays on this machine's speakers, no browser needed.

It shows up as a device, so an AI agent (through the MCP server) or the web app can send it music.
Audio comes over the network from the SeryMusic server and is played by mpv (https://mpv.io).

    python -m serymusic_web.headless http://192.168.1.250:8090 --name "Altavoz salón"
"""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time

import requests

log = logging.getLogger("serymusic.headless")

POLL_SECONDS = 0.5
HEARTBEAT_SECONDS = 10
# A song counts as listened after this long, like in the web app
LISTENED_SECONDS = 10


class MpvError(Exception):
    pass


class Mpv:
    """
    mpv running idle in the background, driven through its JSON IPC.

    Outputs of the headless player share this interface: `load`, `command`, `get`, `ended`,
    `close`, `describe`, plus `media_format` (format of the audio links it wants) and `volume`.
    """

    media_format = "opus"
    # Format retried with when a song fails
    fallback_format = "aac"

    def __init__(self, volume: float):
        self.volume = volume
        executable = shutil.which("mpv")
        if not executable:
            raise MpvError("mpv is not installed (Debian/Raspberry Pi OS: sudo apt install mpv, "
                           "Windows: winget install mpv)")
        name = f"serymusic-mpv-{os.getpid()}"
        self.path = rf"\\.\pipe\{name}" if os.name == "nt" else os.path.join(tempfile.gettempdir(), f"{name}.sock")
        self.process = subprocess.Popen(
            [executable, "--idle=yes", "--no-video", "--no-terminal", "--force-window=no", "--audio-display=no",
             f"--volume={round(volume * 100)}", f"--input-ipc-server={self.path}"],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        self._lock = threading.Lock()
        self._request = 0
        # Reason of the last finished file ("eof", "error"...), collected while reading replies
        self.ended: str | None = None
        self._connect()

    def _connect(self) -> None:
        deadline = time.time() + 10
        while True:
            try:
                if os.name == "nt":
                    self._pipe = open(self.path, "r+b", buffering=0)
                    self._read, self._write = self._pipe.readline, self._pipe.write
                else:
                    self._socket = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                    self._socket.connect(self.path)
                    stream = self._socket.makefile("rwb", buffering=0)
                    self._read, self._write = stream.readline, stream.write
                return
            except OSError:
                if time.time() > deadline or self.process.poll() is not None:
                    raise MpvError("Couldn't start mpv")
                time.sleep(0.1)

    def command(self, *args):
        """Sends a command and waits for its reply. Events read meanwhile are recorded."""
        with self._lock:
            self._request += 1
            request_id = self._request
            self._write((json.dumps({"command": list(args), "request_id": request_id}) + "\n").encode())
            while True:
                line = self._read()
                if not line:
                    raise MpvError("mpv stopped")
                message = json.loads(line)
                if message.get("event") == "end-file":
                    self.ended = message.get("reason")
                if message.get("request_id") == request_id:
                    if message.get("error") not in (None, "success"):
                        raise MpvError(f"{args[0]}: {message['error']}")
                    return message.get("data")

    def get(self, name: str, default=None):
        try:
            return self.command("get_property", name)
        except MpvError:
            return default  # e.g. time-pos while nothing is loaded

    def describe(self) -> dict:
        return {"kind": "speaker"}

    def load(self, url: str, start: float = 0, duration: float = 0, song: dict | None = None) -> None:
        self.ended = None
        # `start` instead of loadfile options, their position changed between mpv versions
        self.command("set_property", "start", str(round(start, 1)) if start else "none")
        self.command("loadfile", url, "replace")
        self.command("set_property", "pause", False)

    def close(self) -> None:
        try:
            self.command("quit")
        except Exception:
            pass
        self.process.terminate()


class HeadlessPlayer:
    """Same behaviour as the web player: queue, radio at the end of the queue, history."""

    def __init__(self, server: str, name: str, password: str | None = None, volume: float = 0.8,
                 output=None, device_id: str | None = None, media_base: str | None = None):
        """
        [output] plays the audio, mpv on this machine by default (see `Mpv` for the interface).
        [media_base] is where the output fetches audio from, when it can't use [server]
        (e.g. a TV can't reach 127.0.0.1).
        """
        self.server = server.rstrip("/")
        self.media_base = (media_base or server).rstrip("/")
        self.name = name[:40]
        self.password = password
        self.id = device_id or "headless-" + hashlib.sha256(f"{socket.gethostname()}/{name}".encode()).hexdigest()[:16]
        self.http = requests.Session()
        self.output = output or Mpv(volume)
        self.volume = self.output.volume
        self.queue: list[dict] = []
        self.index = -1
        self.playing = False
        self.retried = False
        self.listened = False
        self.loading_radio = False
        self._lock = threading.RLock()
        self._stop = threading.Event()

    # Server

    def _login(self) -> None:
        if not self.password:
            return
        response = self.http.post(f"{self.server}/login", data={"password": self.password}, allow_redirects=False, timeout=15)
        if "error" in response.headers.get("Location", ""):
            raise SystemExit("Wrong SeryMusic password")

    def _api(self, method: str, path: str, **kwargs):
        response = self.http.request(method, f"{self.server}{path}", timeout=30, **kwargs)
        if response.status_code == 401:
            self._login()
            response = self.http.request(method, f"{self.server}{path}", timeout=30, **kwargs)
        response.raise_for_status()
        return response.json()

    def report(self) -> None:
        with self._lock:
            state = {
                "name": self.name,
                "song": self.current(),
                "playing": self.playing,
                "position": self.output.get("time-pos", 0) or 0,
                "volume": self.volume,
                "upcoming": self.queue[self.index + 1:self.index + 6],
                **self.output.describe(),
            }
        try:
            self._api("POST", f"/api/devices/{self.id}/state", json=state)
        except requests.RequestException as error:
            log.debug("State report failed: %s", error)

    def _listen(self) -> None:
        """Receives commands, reconnecting whenever the server goes away."""
        delay = 1
        while not self._stop.is_set():
            try:
                self._login()
                with self.http.get(f"{self.server}/api/devices/{self.id}/events", params={"name": self.name},
                                   stream=True, timeout=(10, 60)) as response:
                    response.raise_for_status()
                    log.info("Connected to %s as '%s'", self.server, self.name)
                    delay = 1
                    self.report()
                    for line in response.iter_lines(decode_unicode=True):
                        if self._stop.is_set():
                            return
                        if line and line.startswith("data: "):
                            try:
                                self.handle(json.loads(line[6:]))
                            except Exception:
                                log.exception("Command failed: %s", line)
            except requests.RequestException as error:
                log.warning("Server unreachable (%s), retrying in %ss", error, delay)
            self._stop.wait(delay)
            delay = min(delay * 2, 30)

    # Playback

    def current(self) -> dict | None:
        return self.queue[self.index] if 0 <= self.index < len(self.queue) else None

    def _load(self, song: dict, media_format: str, start: float = 0) -> None:
        path = self._api("GET", f"/api/streamlink/{song['id']}", params={"format": media_format})["path"]
        self.output.load(self.media_base + path, start, song.get("duration") or 0, song)

    def _play_index(self, index: int, start: float = 0) -> None:
        song = self.queue[index]
        self.index = index
        self.retried = False
        self.listened = False
        self._load(song, self.output.media_format, start)
        self.playing = True
        log.info("Playing %s - %s", song["title"], song.get("artist", ""))

    def next(self) -> None:
        with self._lock:
            if self.index + 1 < len(self.queue):
                self._play_index(self.index + 1)
                return
        # End of the queue: keep going with similar songs
        if self.current() and self._append_radio():
            with self._lock:
                self._play_index(self.index + 1)
        else:
            self.playing = False

    def previous(self) -> None:
        with self._lock:
            if (self.output.get("time-pos", 0) or 0) > 3 or self.index <= 0:
                self.output.command("seek", 0, "absolute")
            else:
                self._play_index(self.index - 1)

    def _append_radio(self) -> bool:
        if self.loading_radio:
            return False
        self.loading_radio = True
        try:
            songs = self._api("GET", f"/api/radio/{self.current()['id']}")["songs"]
            with self._lock:
                known = {s["id"] for s in self.queue}
                fresh = [s for s in songs if s["id"] not in known]
                self.queue.extend(fresh)
            return bool(fresh)
        except requests.RequestException as error:
            log.warning("Radio failed: %s", error)
            return False
        finally:
            self.loading_radio = False

    def handle(self, command: dict) -> None:
        kind = command.get("type")
        with self._lock:
            if kind == "play" and command.get("songs"):
                self.queue = list(command["songs"])
                self._play_index(min(int(command.get("index") or 0), len(self.queue) - 1))
            elif kind in ("pause", "stop"):
                self.output.command("set_property", "pause", True)
                self.playing = False
            elif kind == "resume" and self.current():
                if self.output.get("idle-active", False):
                    self._play_index(self.index)
                else:
                    self.output.command("set_property", "pause", False)
                    self.playing = True
            elif kind == "next":
                pass  # outside the lock, may fetch the radio
            elif kind == "previous":
                self.previous()
            elif kind == "enqueue" and command.get("song"):
                if self.current() is None:
                    self.queue = [command["song"]]
                    self._play_index(0)
                elif command.get("next"):
                    self.queue.insert(self.index + 1, command["song"])
                else:
                    self.queue.append(command["song"])
            elif kind == "volume":
                self.volume = max(0.0, min(float(command.get("level", self.volume)), 1.0))
                self.output.command("set_property", "volume", round(self.volume * 100))
        if kind == "next":
            self.next()
        self.report()

    def _watch(self) -> None:
        """Moves to the next song when one ends, records history, sends heartbeats."""
        last_report = 0.0
        while not self._stop.is_set():
            time.sleep(POLL_SECONDS)
            try:
                position = self.output.get("time-pos", 0) or 0
                ended, self.output.ended = self.output.ended, None
                song = self.current()
                if song and self.playing and not self.listened and position >= LISTENED_SECONDS:
                    self.listened = True
                    threading.Thread(target=self._api, args=("POST", "/api/history"), kwargs={"json": song},
                                     daemon=True).start()
                if ended == "eof" and self.playing:
                    self.next()
                    self.report()
                elif ended == "error" and self.playing:
                    with self._lock:
                        if not self.retried:
                            # The server gets a fresh YouTube url on the retry
                            self.retried = True
                            log.warning("Playback failed, retrying %s", song and song["title"])
                            self._load(song, self.output.fallback_format, position)
                            continue
                elif ended == "stopped" and self.playing:
                    # Stopped from the device itself (e.g. TV remote)
                    self.playing = False
                    self.report()
                    log.warning("Can't play %s, skipping", song and song["title"])
                    self.next()
                    self.report()
                elif time.time() - last_report > HEARTBEAT_SECONDS:
                    last_report = time.time()
                    self.report()
            except MpvError:
                log.error("mpv stopped, exiting")
                self._stop.set()
            except Exception:
                log.exception("Player loop error")

    def start(self) -> None:
        threading.Thread(target=self._listen, name="headless-listen", daemon=True).start()
        threading.Thread(target=self._watch, name="headless-watch", daemon=True).start()

    def run_forever(self) -> None:
        self.start()
        try:
            while not self._stop.wait(1):
                pass
        except KeyboardInterrupt:
            pass
        finally:
            self.stop()

    def stop(self) -> None:
        self._stop.set()
        try:
            self.http.post(f"{self.server}/api/devices/{self.id}/bye", timeout=5)
        except requests.RequestException:
            pass
        self.output.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="SeryMusic player without browser, plays on this machine's speakers.")
    parser.add_argument("server", nargs="?", default=os.environ.get("SERYMUSIC_SERVER", "http://localhost:8080"),
                        help="SeryMusic server, e.g. http://192.168.1.250:8090")
    parser.add_argument("--name", default=os.environ.get("SERYMUSIC_SPEAKER") or socket.gethostname(),
                        help="device name the agent and the web app will see")
    parser.add_argument("--password", default=os.environ.get("SERYMUSIC_PASSWORD"),
                        help="only when the server has a password")
    parser.add_argument("--volume", type=int, default=80, help="starting volume, 0-100")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    try:
        player = HeadlessPlayer(args.server, args.name, args.password, args.volume / 100)
    except MpvError as error:
        sys.exit(str(error))
    print(f"\n  '{player.name}' is ready, music sent to it plays on this machine. Ctrl+C to stop.\n")
    player.run_forever()


if __name__ == "__main__":
    main()
