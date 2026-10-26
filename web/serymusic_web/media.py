"""Audio conversion with ffmpeg, for devices that can't play what YouTube serves (DLNA, live stream)."""

from __future__ import annotations

import os
import shutil
import subprocess
from typing import Iterator

# Every DLNA renderer plays MP3. Same encoding everywhere so the live stream can join songs and silence.
MP3_ARGS = ["-vn", "-c:a", "libmp3lame", "-b:a", "192k", "-ar", "44100", "-ac", "2", "-f", "mp3"]
# "Streaming, no seeking": what a DLNA renderer needs to know about a converted stream
DLNA_MP3_FEATURES = "DLNA.ORG_PN=MP3;DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=01700000000000000000000000000000"
CHUNK = 16 * 1024


def ffmpeg_path() -> str | None:
    return os.environ.get("SERYMUSIC_FFMPEG") or shutil.which("ffmpeg")


def mp3_process(ffmpeg: str, source: str, start: float = 0, realtime: bool = False,
                volume: float = 1.0) -> subprocess.Popen:
    """
    ffmpeg converting [source] (a url) to MP3 on its stdout.
    [realtime] paces the output at playback speed, for live streams.
    """
    command = [ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin"]
    if realtime:
        command.append("-re")
    if start > 0:
        command += ["-ss", f"{start:.1f}"]
    command += ["-i", source]
    if volume != 1.0:
        command += ["-af", f"volume={max(volume, 0):.2f}"]
    command += MP3_ARGS + ["pipe:1"]
    return subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)


def mp3_stream(ffmpeg: str, source: str, start: float = 0) -> Iterator[bytes]:
    """MP3 of [source] as fast as the client reads it, ffmpeg stops when the client goes away."""
    process = mp3_process(ffmpeg, source, start)
    try:
        while chunk := process.stdout.read(CHUNK):
            yield chunk
    finally:
        process.kill()
        process.wait()


def mp3_silence(ffmpeg: str, seconds: float = 1.0) -> bytes:
    """[seconds] of silent MP3, with the same encoding as the songs."""
    return subprocess.run(
        [ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi",
         "-i", "anullsrc=r=44100:cl=stereo", "-t", str(seconds)] + MP3_ARGS + ["pipe:1"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True,
    ).stdout
