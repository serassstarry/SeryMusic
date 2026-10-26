# SeryMusic web

A personal web version of SeryMusic: search YouTube Music, play songs with a queue,
favorites, playlists, history and synced lyrics, from any browser.
It runs on any machine with **Python 3.10+**, nothing else is needed (no Node, no database server).
[ffmpeg](https://ffmpeg.org) is optional: with it, music can also go to network TVs and speakers (DLNA) and to a live stream.

It's meant for **one person**: everything is behind a single password, which can be turned off on a trusted home network.

## Run it

```sh
cd web
python -m venv .venv
. .venv/bin/activate            # Windows: .venv\Scripts\activate
pip install -r requirements.txt
SERYMUSIC_PASSWORD='choose-a-password' python app.py
```

Open `http://<server>:8080` and log in. Without `SERYMUSIC_PASSWORD`, a random password is
generated on first start, printed in the console and saved in `data/password.txt`.

On Windows, double-click `startweb.bat`: it installs everything, starts the server **without password** (for the home network) and opens the browser.

On a phone, "Add to home screen" installs it like an app.

## Settings

All optional, through environment variables:

| Variable | Default | |
|---|---|---|
| `SERYMUSIC_PASSWORD` | generated | Login password |
| `SERYMUSIC_NO_PASSWORD` | off | `1` to skip the login, only on a trusted network: anyone who can reach the server can use it |
| `SERYMUSIC_HOST` | `0.0.0.0` | Address to listen on, `127.0.0.1` when behind a reverse proxy |
| `SERYMUSIC_PORT` | `8080` | |
| `SERYMUSIC_DATA` | `web/data` | Folder of the database, session key and generated password |
| `SERYMUSIC_THREADS` | `32` | Every song being played, and every open player, keeps one thread busy |
| `SERYMUSIC_MCP_PORT` | port + 1 | Port of the MCP server for AI agents, `0` turns it off |
| `SERYMUSIC_MCP_ALLOWED_HOSTS` | | Comma separated `host:port` agents use to reach the MCP server; turns on DNS rebinding protection |
| `SERYMUSIC_SPEAKER` | | Name of a [headless player](#headless-player) started with the server, plays on the server's speakers |
| `SERYMUSIC_PUBLIC_URL` | `http://<lan ip>:<port>` | Base of the audio links given to agents, TVs and the live stream |
| `SERYMUSIC_LIVE` | `Emisión` | Name of the [live stream](#network-audio-dlna-and-live-stream) device, `0` turns it off |
| `SERYMUSIC_DLNA` | on | `0` stops looking for DLNA TVs and speakers |
| `SERYMUSIC_SSDP_PORT` | port + 2 | UDP port DLNA devices answer the search on, open it in the firewall |
| `SERYMUSIC_FFMPEG` | from `PATH` | Path of ffmpeg |
| `SERYMUSIC_BEHIND_PROXY` | off | `1` when a reverse proxy sets `X-Forwarded-For` / `X-Forwarded-Proto` |
| `SERYMUSIC_SECURE_COOKIE` | off | `1` when served over HTTPS |

## Control it from an AI agent (MCP)

The server also runs an [MCP](https://modelcontextprotocol.io) server at `http://<server>:<port + 1>/mcp`
(Streamable HTTP), so an AI agent can search and play music for you: "pon Despechá de Rosalía",
"pausa", "sube el volumen", "pon mi playlist del gym en el salón"...

Music plays on a **device**: a browser with SeryMusic open, or a [headless player](#headless-player).
Each device has a name (shown on the home screen of the web app, "Cambiar nombre" to change it).
When the agent doesn't say where, music goes to the player on the **same machine as the agent**, so it plays
where you're talking; otherwise to the device already playing, or the last one used.

Tools: `play_music`, `play_playlist`, `add_to_queue`, `control_playback` (pause, resume, next, previous, stop),
`set_volume`, `now_playing`, `search_music`, `list_devices`, `list_playlists`, `like_song`,
and `get_stream_url` for agents that play audio themselves (a direct link to the audio).

Connecting an agent, e.g. Claude Code:

```sh
claude mcp add --transport http serymusic http://192.168.1.250:8091/mcp
```

Clients that only launch local (stdio) servers can go through [mcp-remote](https://www.npmjs.com/package/mcp-remote):
`npx mcp-remote http://192.168.1.250:8091/mcp --allow-http`.
With a password, agents send it as `Authorization: Bearer <password>`.

Browsers don't let a page start sound before it has been touched once. If a browser blocks it, the agent is told,
and the page shows a button to start the music.

## Network audio: DLNA and live stream

The server sends music over the network without installing anything on the receiver. Both need ffmpeg.

- **DLNA / UPnP**: smart TVs and network speakers are found automatically (every minute) and show up as devices,
  e.g. "BRAVIA KDL-48W705C". The server tells the TV which url to play (the song converted to MP3, which every
  DLNA device plays) and controls it: pause, volume, next song when one ends. It starts at the TV's own volume.
  Discovery replies arrive on UDP `SERYMUSIC_SSDP_PORT`: with a firewall, allow it from the local network
  (e.g. `sudo ufw allow from 192.168.1.0/24 to any port 8092 proto udp`).
- **Live stream**: the "Emisión" device plays its queue as one endless MP3 stream at `http://<server>:<port>/live`,
  like a radio. Open it in any player (VLC, a phone browser, a TV, Home Assistant...); everyone hears the same.
  Between songs and while paused it sends silence, so players stay connected. The agent gives you the url.

## Headless player

Plays on a machine's speakers without browser, e.g. a Raspberry Pi connected to speakers. It needs
[mpv](https://mpv.io) (`sudo apt install mpv`, on Windows `winget install mpv`).

On the server itself, set `SERYMUSIC_SPEAKER=Living room`. On any other machine with Python:

```sh
pip install requests
python -m serymusic_web.headless http://192.168.1.250:8090 --name "Living room"
```

## Putting it on the internet

Serve it over **HTTPS**, otherwise the password travels in clear text. The simplest way is a
reverse proxy such as [Caddy](https://caddyserver.com), which gets the certificate by itself:

```
music.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

and start SeryMusic with `SERYMUSIC_HOST=127.0.0.1 SERYMUSIC_BEHIND_PROXY=1 SERYMUSIC_SECURE_COOKIE=1`.
5 wrong passwords lock logins from that address for 5 minutes.

To keep it running on Linux, a systemd service (`/etc/systemd/system/serymusic.service`):

```ini
[Unit]
Description=SeryMusic web
After=network-online.target

[Service]
WorkingDirectory=/opt/SeryMusic/web
Environment=SERYMUSIC_PASSWORD=choose-a-password
Environment=SERYMUSIC_HOST=127.0.0.1
ExecStart=/opt/SeryMusic/web/.venv/bin/python app.py
Restart=on-failure
User=serymusic

[Install]
WantedBy=multi-user.target
```

## How it works

- Search, radio and song info come from YouTube Music through [ytmusicapi](https://github.com/sigma67/ytmusicapi).
- Audio urls come from [yt-dlp](https://github.com/yt-dlp/yt-dlp). YouTube ties them to the server's IP, so
  the server relays the audio to the browser. Browsers that play opus get opus, Safari gets AAC.
- Lyrics come from [LRCLIB](https://lrclib.net).
- Favorites, playlists, history and the queue are stored in `data/serymusic.db` (SQLite).
  Copy that file to back up the library. The queue is saved on the server, so playback can be resumed from another device.

## When songs stop playing

YouTube changes often and yt-dlp follows. Update it first:

```sh
pip install -U "yt-dlp[default]"
```

Install [Node.js](https://nodejs.org) or [Deno](https://deno.com) on the server, yt-dlp uses either to solve YouTube's
JavaScript challenge. Without one, songs made for kids (and some others) fail with "This video is not available".

## Development

```sh
pip install -r requirements-dev.txt
python -m pytest tests
```
