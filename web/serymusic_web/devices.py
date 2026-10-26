"""
Players (open browser tabs) that can be controlled remotely, like Spotify Connect.

Each tab keeps an event stream open to receive commands, and reports what it's playing.
"""

from __future__ import annotations

import queue
import threading
import time
import unicodedata
from dataclasses import dataclass, field

# A device that stopped reporting for this long is considered gone
OFFLINE_AFTER = 90
# Players the server runs itself (DLNA TVs, live stream) aren't on the machine of whoever asks
LOCAL_KINDS = ("browser", "speaker")


def _plain(text: str) -> str:
    """Lowercase without accents, "Emisión" matches "emision"."""
    return "".join(c for c in unicodedata.normalize("NFKD", text.lower().strip()) if not unicodedata.combining(c))


# Players report every 10 seconds, a connection silent for longer belongs to a closed tab
STALE_AFTER = 25


@dataclass
class Device:
    id: str
    name: str
    # IP the player connects from, to find the player next to whoever asks for music
    address: str = ""
    last_seen: float = 0.0
    # Last state reported by the player: song, playing, position, volume...
    state: dict = field(default_factory=dict)
    state_at: float = 0.0
    connections: list[queue.Queue] = field(default_factory=list)

    @property
    def online(self) -> bool:
        return bool(self.connections) or time.time() - self.last_seen < OFFLINE_AFTER

    @property
    def connected(self) -> bool:
        return bool(self.connections) and time.time() - self.last_seen < STALE_AFTER

    def describe(self) -> dict:
        state = dict(self.state)
        # Position keeps moving while playing, extrapolate from the last report
        if state.get("playing") and isinstance(state.get("position"), (int, float)):
            state["position"] = round(state["position"] + time.time() - self.state_at, 1)
        # Only connected devices can receive commands
        return {"id": self.id, "name": self.name, "address": self.address, "connected": self.connected, **state}


class DeviceHub:

    def __init__(self):
        self._devices: dict[str, Device] = {}
        self._lock = threading.Lock()

    def _get(self, device_id: str, name: str | None = None, address: str | None = None) -> Device:
        device = self._devices.get(device_id)
        if device is None:
            device = self._devices[device_id] = Device(device_id, name or "Dispositivo")
        elif name:
            device.name = name
        if address:
            device.address = address
        device.last_seen = time.time()
        return device

    def connect(self, device_id: str, name: str | None, address: str | None = None) -> queue.Queue:
        """Command stream of a player, must be given back to [disconnect]."""
        commands = queue.Queue()
        with self._lock:
            self._get(device_id, name, address).connections.append(commands)
        return commands

    def disconnect(self, device_id: str, commands: queue.Queue) -> None:
        with self._lock:
            device = self._devices.get(device_id)
            if device and commands in device.connections:
                device.connections.remove(commands)
                device.last_seen = time.time()

    def report(self, device_id: str, name: str | None, state: dict, address: str | None = None) -> None:
        with self._lock:
            device = self._get(device_id, name, address)
            device.state = state
            device.state_at = time.time()

    def leave(self, device_id: str) -> None:
        """A player closing (tab closed), so it stops being picked right away."""
        with self._lock:
            device = self._devices.get(device_id)
            if device:
                device.last_seen = 0
                device.state = {**device.state, "playing": False}

    def send(self, device_id: str, command: dict) -> bool:
        """@return `False` when the device has no open connection"""
        with self._lock:
            device = self._devices.get(device_id)
            if device and not device.connected:
                return False
            # Only the newest tab of a browser obeys, otherwise every open tab would play
            connections = device.connections[-1:] if device else []
        for commands in connections:
            commands.put(command)
        return bool(connections)

    @staticmethod
    def is_near(device: dict, near: str | None) -> bool:
        """Whether [device] runs on the machine at address [near]."""
        return bool(near) and device["address"] == near and device.get("kind", "browser") in LOCAL_KINDS

    def devices(self) -> list[dict]:
        """Online devices, most recently active first."""
        with self._lock:
            found = [d for d in self._devices.values() if d.online]
            found.sort(key=lambda d: (d.connected, d.state_at, d.last_seen), reverse=True)
            return [d.describe() for d in found]

    def find(self, wanted: str | None = None, near: str | None = None) -> dict | None:
        """
        Device matching [wanted] (id, or part of its name, case insensitive).
        Without [wanted]: the device at address [near] (where the request comes from),
        or else the device playing music, or else the most recently active one.
        """
        connected = [d for d in self.devices() if d["connected"]]
        if not connected:
            return None
        if wanted:
            text = _plain(wanted)
            for device in connected:
                if device["id"] == wanted or _plain(device["name"]) == text:
                    return device
            matches = [d for d in connected if text in _plain(d["name"])]
            return matches[0] if matches else None
        nearby = [d for d in connected if self.is_near(d, near)]
        if nearby:
            connected = nearby
        playing = [d for d in connected if d.get("playing")]
        return (playing or connected)[0]
