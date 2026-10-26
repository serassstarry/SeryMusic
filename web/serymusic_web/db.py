"""Library storage: favorites, playlists, history and player state, in one SQLite file."""

from __future__ import annotations

import json
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS songs (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    artist TEXT NOT NULL DEFAULT '',
    album TEXT NOT NULL DEFAULT '',
    duration INTEGER NOT NULL DEFAULT 0,
    thumbnail TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS favorites (
    song_id TEXT PRIMARY KEY REFERENCES songs(id),
    added_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS playlists (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    created_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS playlist_songs (
    playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
    song_id TEXT NOT NULL REFERENCES songs(id),
    position INTEGER NOT NULL,
    PRIMARY KEY (playlist_id, song_id)
);
CREATE TABLE IF NOT EXISTS history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    song_id TEXT NOT NULL REFERENCES songs(id),
    played_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS history_played_at ON history(played_at);
CREATE TABLE IF NOT EXISTS state (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""

SONG_COLUMNS = "s.id, s.title, s.artist, s.album, s.duration, s.thumbnail"
# Keeps history from growing forever
MAX_HISTORY = 1000


class Library:

    def __init__(self, path: Path):
        self.path = path
        with self._connect() as conn:
            conn.executescript(SCHEMA)

    @contextmanager
    def _connect(self):
        # One connection per call keeps it safe across server threads
        conn = sqlite3.connect(self.path, timeout=10)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys = ON")
        conn.execute("PRAGMA journal_mode = WAL")
        try:
            with conn:
                yield conn
        finally:
            conn.close()

    @staticmethod
    def _songs(rows) -> list[dict]:
        return [dict(row) for row in rows]

    @staticmethod
    def _save_song(conn, song: dict) -> None:
        conn.execute(
            """INSERT INTO songs (id, title, artist, album, duration, thumbnail)
               VALUES (:id, :title, :artist, :album, :duration, :thumbnail)
               ON CONFLICT(id) DO UPDATE SET title = excluded.title, artist = excluded.artist,
                   album = excluded.album, duration = excluded.duration, thumbnail = excluded.thumbnail""",
            song,
        )

    # Favorites

    def favorites(self) -> list[dict]:
        with self._connect() as conn:
            return self._songs(conn.execute(
                f"SELECT {SONG_COLUMNS} FROM favorites f JOIN songs s ON s.id = f.song_id ORDER BY f.added_at DESC"
            ))

    def favorite_ids(self) -> list[str]:
        with self._connect() as conn:
            return [row[0] for row in conn.execute("SELECT song_id FROM favorites")]

    def add_favorite(self, song: dict) -> None:
        with self._connect() as conn:
            self._save_song(conn, song)
            conn.execute("INSERT OR IGNORE INTO favorites (song_id, added_at) VALUES (?, ?)", (song["id"], time.time()))

    def remove_favorite(self, song_id: str) -> None:
        with self._connect() as conn:
            conn.execute("DELETE FROM favorites WHERE song_id = ?", (song_id,))

    # Playlists

    def playlists(self) -> list[dict]:
        with self._connect() as conn:
            rows = conn.execute(
                """SELECT p.id, p.name, COUNT(ps.song_id) AS count,
                          (SELECT s.thumbnail FROM playlist_songs x JOIN songs s ON s.id = x.song_id
                           WHERE x.playlist_id = p.id ORDER BY x.position LIMIT 1) AS thumbnail
                   FROM playlists p LEFT JOIN playlist_songs ps ON ps.playlist_id = p.id
                   GROUP BY p.id ORDER BY p.created_at DESC"""
            )
            return [dict(row) for row in rows]

    def playlist(self, playlist_id: int) -> dict | None:
        with self._connect() as conn:
            row = conn.execute("SELECT id, name FROM playlists WHERE id = ?", (playlist_id,)).fetchone()
            if row is None:
                return None
            songs = self._songs(conn.execute(
                f"""SELECT {SONG_COLUMNS} FROM playlist_songs ps JOIN songs s ON s.id = ps.song_id
                    WHERE ps.playlist_id = ? ORDER BY ps.position""",
                (playlist_id,),
            ))
            return {"id": row["id"], "name": row["name"], "songs": songs}

    def create_playlist(self, name: str) -> int:
        with self._connect() as conn:
            cursor = conn.execute("INSERT INTO playlists (name, created_at) VALUES (?, ?)", (name, time.time()))
            return cursor.lastrowid

    def rename_playlist(self, playlist_id: int, name: str) -> bool:
        with self._connect() as conn:
            return conn.execute("UPDATE playlists SET name = ? WHERE id = ?", (name, playlist_id)).rowcount > 0

    def delete_playlist(self, playlist_id: int) -> bool:
        with self._connect() as conn:
            return conn.execute("DELETE FROM playlists WHERE id = ?", (playlist_id,)).rowcount > 0

    def add_to_playlist(self, playlist_id: int, song: dict) -> bool:
        """@return `False` when playlist doesn't exist"""
        with self._connect() as conn:
            if conn.execute("SELECT 1 FROM playlists WHERE id = ?", (playlist_id,)).fetchone() is None:
                return False
            self._save_song(conn, song)
            conn.execute(
                """INSERT OR IGNORE INTO playlist_songs (playlist_id, song_id, position)
                   VALUES (?, ?, (SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_songs WHERE playlist_id = ?))""",
                (playlist_id, song["id"], playlist_id),
            )
            return True

    def remove_from_playlist(self, playlist_id: int, song_id: str) -> None:
        with self._connect() as conn:
            conn.execute("DELETE FROM playlist_songs WHERE playlist_id = ? AND song_id = ?", (playlist_id, song_id))

    def reorder_playlist(self, playlist_id: int, song_ids: list[str]) -> None:
        """Songs of the playlist missing from [song_ids] keep their relative order, after the given ones."""
        with self._connect() as conn:
            current = [row[0] for row in conn.execute(
                "SELECT song_id FROM playlist_songs WHERE playlist_id = ? ORDER BY position", (playlist_id,)
            )]
            wanted = [sid for sid in dict.fromkeys(song_ids) if sid in current]
            order = wanted + [sid for sid in current if sid not in wanted]
            conn.executemany(
                "UPDATE playlist_songs SET position = ? WHERE playlist_id = ? AND song_id = ?",
                [(i, playlist_id, sid) for i, sid in enumerate(order)],
            )

    # History

    def add_history(self, song: dict) -> None:
        with self._connect() as conn:
            self._save_song(conn, song)
            conn.execute("INSERT INTO history (song_id, played_at) VALUES (?, ?)", (song["id"], time.time()))
            conn.execute(
                "DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY played_at DESC LIMIT ?)",
                (MAX_HISTORY,),
            )

    def history(self, limit: int = 50) -> list[dict]:
        """Most recently played songs, each song once."""
        with self._connect() as conn:
            return self._songs(conn.execute(
                f"""SELECT {SONG_COLUMNS} FROM songs s
                    JOIN (SELECT song_id, MAX(played_at) AS last FROM history GROUP BY song_id) h ON h.song_id = s.id
                    ORDER BY h.last DESC LIMIT ?""",
                (limit,),
            ))

    def clear_history(self) -> None:
        with self._connect() as conn:
            conn.execute("DELETE FROM history")

    # Player state (queue, position...), restored on any device

    def get_state(self, key: str):
        with self._connect() as conn:
            row = conn.execute("SELECT value FROM state WHERE key = ?", (key,)).fetchone()
            return json.loads(row[0]) if row else None

    def set_state(self, key: str, value) -> None:
        with self._connect() as conn:
            conn.execute(
                "INSERT INTO state (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                (key, json.dumps(value)),
            )
