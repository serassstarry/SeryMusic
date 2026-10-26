"""Remote control of the players, used by the MCP server so an AI agent can play music."""

from __future__ import annotations

import random
import time
from typing import Callable

from . import youtube
from .db import Library
from .devices import DeviceHub

# How long to wait for a player to confirm a command
CONFIRM_TIMEOUT = 6
RADIO_SIZE = 25
ACTIONS = ("pause", "resume", "next", "previous", "stop")
KINDS = {
    "browser": "SeryMusic open in a browser",
    "speaker": "speakers of a computer (headless player)",
    "dlna": "network TV or speaker (DLNA)",
    "live": "live stream, heard by opening listen_url in any player",
}


class RemoteError(Exception):
    """Message is meant for the agent, it explains what went wrong and how to fix it."""


class Remote:

    def __init__(self, hub: DeviceHub, library: Library, stream_url: Callable[[str], str]):
        self.hub = hub
        self.library = library
        self.stream_url = stream_url

    # Helpers

    def _device(self, wanted: str | None, near: str | None = None) -> dict:
        device = self.hub.find(wanted, near)
        if device:
            return device
        connected = [d["name"] for d in self.hub.devices() if d["connected"]]
        if not connected:
            raise RemoteError(
                "No SeryMusic player is open. Open SeryMusic in a browser on the device that should play "
                "(phone, PC, tablet...) and try again, or use get_stream_url to play the audio yourself."
            )
        raise RemoteError(f"No device called '{wanted}'. Open devices: {', '.join(connected)}.")

    def _send(self, device: dict, command: dict) -> None:
        if not self.hub.send(device["id"], command):
            raise RemoteError(f"'{device['name']}' just disconnected, ask the user to reopen SeryMusic there.")

    def _wait(self, device: dict, done: Callable[[dict], bool]) -> dict | None:
        """Waits for the player to report a state where [done] is true, `None` on timeout."""
        deadline = time.time() + CONFIRM_TIMEOUT
        while time.time() < deadline:
            current = self.hub.find(device["id"])
            if current and done(current):
                return current
            time.sleep(0.2)
        return None

    def _song(self, query: str | None, song_id: str | None) -> dict:
        if song_id:
            if not youtube.is_video_id(song_id):
                raise RemoteError(f"'{song_id}' isn't a valid song id, use the id given by search_music.")
            found = youtube.song(song_id)
            if not found:
                raise RemoteError(f"Song {song_id} wasn't found on YouTube Music.")
            return found
        if query and query.strip():
            results = youtube.search(query)
            if not results:
                raise RemoteError(f"Nothing found for '{query}'. Try other words, e.g. song title and artist.")
            return results[0]
        raise RemoteError("Give either a query or a song_id.")

    @staticmethod
    def _summary(song: dict) -> str:
        return f"{song['title']} - {song['artist']}" if song.get("artist") else song["title"]

    def _start(self, device: dict, songs: list[dict]) -> dict:
        """Sends a new queue to [device] and confirms it started."""
        first = songs[0]
        self._send(device, {"type": "play", "songs": songs, "index": 0})
        state = self._wait(device, lambda d: (d.get("song") or {}).get("id") == first["id"]
                           and (d.get("playing") or d.get("blocked")))
        result = {"device": device["name"], "now_playing": self._summary(first), "songs_in_queue": len(songs)}
        if device.get("listen_url"):
            result["listen_url"] = device["listen_url"]
            result["note"] = "This is a live stream: the user hears it by opening listen_url in any player (VLC, phone, TV)."
        if state is None:
            result["warning"] = "The player didn't confirm playback, it may be slow or offline."
        elif state.get("blocked"):
            result["warning"] = ("The browser blocked autoplay. Ask the user to tap the SeryMusic page once on "
                                 f"'{device['name']}'; music starts right away.")
        return result

    # Actions

    def search(self, query: str, limit: int = 10) -> list[dict]:
        if not query.strip():
            raise RemoteError("Query is empty.")
        return youtube.search(query)[:max(1, min(limit, 40))]

    def play(self, query: str | None = None, song_id: str | None = None,
             device: str | None = None, radio: bool = True, near: str | None = None) -> dict:
        target = self._device(device, near)
        song = self._song(query, song_id)
        songs = [song]
        if radio:
            try:
                songs += youtube.radio(song["id"])[:RADIO_SIZE]
            except Exception:
                pass  # The song still plays, only the follow-up is missing
        return self._start(target, songs)

    def play_collection(self, name: str, shuffle: bool = False, device: str | None = None,
                        near: str | None = None) -> dict:
        target = self._device(device, near)
        wanted = name.strip().lower()
        if wanted in ("favorites", "favourites", "favoritos", "favorits", "liked", "me gusta"):
            title, songs = "Favoritos", self.library.favorites()
        else:
            playlists = self.library.playlists()
            match = next((p for p in playlists if p["name"].lower() == wanted), None) or \
                next((p for p in playlists if wanted in p["name"].lower()), None)
            if match is None:
                names = ", ".join(p["name"] for p in playlists) or "none yet"
                raise RemoteError(f"No playlist called '{name}'. Playlists: {names}. 'favorites' plays the liked songs.")
            title, songs = match["name"], self.library.playlist(match["id"])["songs"]
        if not songs:
            raise RemoteError(f"'{title}' is empty.")
        if shuffle:
            songs = random.sample(songs, len(songs))
        return {"collection": title, **self._start(target, songs)}

    def enqueue(self, query: str | None = None, song_id: str | None = None,
                play_next: bool = False, device: str | None = None, near: str | None = None) -> dict:
        target = self._device(device, near)
        song = self._song(query, song_id)
        self._send(target, {"type": "enqueue", "song": song, "next": play_next})
        return {"device": target["name"], "added": self._summary(song),
                "position": "next" if play_next else "end of queue"}

    def control(self, action: str, device: str | None = None, near: str | None = None) -> dict:
        if action not in ACTIONS:
            raise RemoteError(f"Unknown action '{action}', use one of: {', '.join(ACTIONS)}.")
        target = self._device(device, near)
        before = (target.get("song") or {}).get("id")
        self._send(target, {"type": action})
        expected = {
            "pause": lambda d: not d.get("playing"),
            "stop": lambda d: not d.get("playing"),
            "resume": lambda d: d.get("playing") or d.get("blocked"),
            "next": lambda d: (d.get("song") or {}).get("id") != before,
            "previous": lambda d: True,
        }[action]
        state = self._wait(target, expected) or self.hub.find(target["id"]) or target
        result = {"device": target["name"], "done": action}
        if state.get("song"):
            result["now_playing"] = self._summary(state["song"])
            result["playing"] = bool(state.get("playing"))
        if state.get("blocked"):
            result["warning"] = f"The browser blocked autoplay, ask the user to tap the SeryMusic page on '{target['name']}'."
        return result

    def set_volume(self, level: int, device: str | None = None, near: str | None = None) -> dict:
        target = self._device(device, near)
        level = max(0, min(int(level), 100))
        self._send(target, {"type": "volume", "level": level / 100})
        return {"device": target["name"], "volume": level}

    def now_playing(self, device: str | None = None, near: str | None = None) -> dict:
        target = self._device(device, near)
        result = {"device": target["name"], "playing": bool(target.get("playing"))}
        if target.get("song"):
            song = target["song"]
            result.update(song=self._summary(song), song_id=song["id"], album=song.get("album", ""),
                          position_seconds=target.get("position", 0), duration_seconds=song.get("duration", 0))
        else:
            result["song"] = None
        result["volume"] = round((target.get("volume") or 0) * 100)
        result["up_next"] = [self._summary(s) for s in target.get("upcoming") or []]
        return result

    def devices(self, near: str | None = None) -> list[dict]:
        return [
            {"name": d["name"], "type": KINDS.get(d.get("kind"), KINDS["browser"]),
             "can_be_controlled": d["connected"], "playing": bool(d.get("playing")),
             "song": self._summary(d["song"]) if d.get("song") else None,
             **({"listen_url": d["listen_url"]} if d.get("listen_url") else {}),
             # The device the agent itself runs on, where "play here" should go
             "same_device_as_you": self.hub.is_near(d, near)}
            for d in self.hub.devices()
        ]

    def playlists(self) -> list[dict]:
        favorites = len(self.library.favorite_ids())
        return [{"name": "favorites", "songs": favorites}] + \
            [{"name": p["name"], "songs": p["count"]} for p in self.library.playlists()]

    def like(self, song_id: str | None = None, device: str | None = None, near: str | None = None) -> dict:
        if song_id:
            song = self._song(None, song_id)
        else:
            target = self._device(device, near)
            song = target.get("song")
            if not song:
                raise RemoteError(f"Nothing is playing on '{target['name']}'.")
        self.library.add_favorite(song)
        for d in self.hub.devices():
            if d["connected"]:
                self.hub.send(d["id"], {"type": "library"})
        return {"added_to_favorites": self._summary(song)}

    def get_stream_url(self, query: str | None = None, song_id: str | None = None) -> dict:
        song = self._song(query, song_id)
        return {"song": self._summary(song), "duration_seconds": song["duration"], "url": self.stream_url(song["id"])}
