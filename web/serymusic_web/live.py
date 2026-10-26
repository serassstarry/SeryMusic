"""
Live stream: a device whose music goes out as one endless MP3 stream at `/live`, like a radio.

Anyone opening the url (VLC, a phone, a TV, Home Assistant...) hears the same thing. Between songs
and while paused it sends silence, so players stay connected.
"""

from __future__ import annotations

import queue
import subprocess
import threading
import time
from typing import Iterator

from . import media

# Chunks a slow listener may fall behind before being dropped (~10 seconds of 192 kbps)
LISTENER_BUFFER = 16


class LiveBroadcast:
    """Output for the headless player (see `headless.Mpv` for the interface)."""

    media_format = "aac"
    fallback_format = "aac"

    def __init__(self, ffmpeg: str, listen_url: str, volume: float = 1.0):
        self.ffmpeg = ffmpeg
        self.listen_url = listen_url
        self.volume = volume
        self.ended: str | None = None
        self._silence = media.mp3_silence(ffmpeg)
        self._listeners: list[queue.Queue] = []
        self._lock = threading.Lock()
        self._process: subprocess.Popen | None = None
        self._url: str | None = None
        self._paused = False
        # Playback position: where the current ffmpeg started, and when
        self._offset = 0.0
        self._started = 0.0
        self._closed = False
        threading.Thread(target=self._silence_loop, name="live-silence", daemon=True).start()

    # Listeners

    def listen(self) -> Iterator[bytes]:
        listener = queue.Queue(maxsize=LISTENER_BUFFER)
        with self._lock:
            self._listeners.append(listener)
        try:
            while not self._closed:
                try:
                    yield listener.get(timeout=5)
                except queue.Empty:
                    continue
        finally:
            with self._lock:
                if listener in self._listeners:
                    self._listeners.remove(listener)

    @property
    def listeners(self) -> int:
        with self._lock:
            return len(self._listeners)

    def _broadcast(self, chunk: bytes) -> None:
        with self._lock:
            listeners = list(self._listeners)
        for listener in listeners:
            try:
                listener.put_nowait(chunk)
            except queue.Full:
                # Too slow to keep up with a live stream, it reconnects on its own
                with self._lock:
                    if listener in self._listeners:
                        self._listeners.remove(listener)

    def _silence_loop(self) -> None:
        """Keeps listeners fed while nothing plays."""
        while not self._closed:
            if self._process is None:
                self._broadcast(self._silence)
            time.sleep(1)

    # Playback

    def _run(self, start: float) -> None:
        """Starts converting the current song from [start] seconds, at playback speed."""
        self._stop_process()
        process = media.mp3_process(self.ffmpeg, self._url, start, realtime=True, volume=self.volume)
        self._process = process
        self._offset, self._started = start, time.monotonic()
        threading.Thread(target=self._pump, args=(process,), name="live-pump", daemon=True).start()

    def _pump(self, process: subprocess.Popen) -> None:
        while chunk := process.stdout.read(media.CHUNK):
            if process is not self._process:
                return
            self._broadcast(chunk)
        code = process.wait()
        # Stopped on purpose (pause, next song) isn't the end of the song
        if process is self._process:
            self._process = None
            self.ended = "eof" if code == 0 else "error"

    def _stop_process(self) -> None:
        process, self._process = self._process, None
        if process:
            process.kill()
            process.wait()

    def _position(self) -> float:
        if self._process is None:
            return self._offset
        return self._offset + time.monotonic() - self._started

    def load(self, url: str, start: float = 0, duration: float = 0, song: dict | None = None) -> None:
        self.ended = None
        self._url = url
        self._paused = False
        self._run(start)

    def command(self, *args):
        if args[:2] == ("set_property", "pause"):
            if args[2] and not self._paused:
                self._offset = self._position()
                self._paused = True
                self._stop_process()
            elif not args[2] and self._paused and self._url:
                self._paused = False
                self._run(self._offset)
        elif args[:2] == ("set_property", "volume"):
            self.volume = max(0, min(int(args[2]), 100)) / 100
            # Volume is applied while converting, restart from where it is
            if self._process and self._url:
                self._run(self._position())
        elif args[0] == "seek" and self._url:
            self._offset = float(args[1])
            if not self._paused:
                self._run(self._offset)
        elif args[0] == "quit":
            self.close()

    def get(self, name: str, default=None):
        if name == "time-pos":
            return round(self._position(), 1) if self._url else default
        if name == "idle-active":
            return self._url is None
        return default

    def describe(self) -> dict:
        return {"kind": "live", "listen_url": self.listen_url}

    def close(self) -> None:
        self._closed = True
        self._stop_process()
