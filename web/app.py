"""
Starts SeryMusic web, and its MCP server for AI agents.

    python app.py

Settings come from environment variables, see README.md.
"""

import logging
import os
import socket
import threading

from serymusic_web import create_app


def lan_address() -> str:
    """Address other devices of the network reach this machine at."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            # Nothing is sent, connecting a UDP socket only picks the outgoing interface
            probe.connect(("192.0.2.1", 9))
            return probe.getsockname()[0]
    except OSError:
        return "localhost"


def start_mcp(app, host: str, port: int) -> None:
    import uvicorn
    from serymusic_web.mcp_server import build_app

    allowed_hosts = [h.strip() for h in os.environ.get("SERYMUSIC_MCP_ALLOWED_HOSTS", "").split(",") if h.strip()]
    mcp_app = build_app(app.extensions["serymusic"]["remote"], app.config["MCP_PASSWORD"], allowed_hosts)
    server = uvicorn.Server(uvicorn.Config(mcp_app, host=host, port=port, log_level="warning"))
    threading.Thread(target=server.run, name="mcp", daemon=True).start()


def start_players(app, address: str) -> None:
    """Devices living inside the server: its own speakers, the live stream, DLNA renderers."""
    import hashlib

    from serymusic_web import media
    from serymusic_web.headless import HeadlessPlayer, MpvError

    server, password = app.config["SELF_URL"], app.config["MCP_PASSWORD"]
    public = app.config["PUBLIC_URL"].rstrip("/")

    speaker = os.environ.get("SERYMUSIC_SPEAKER")
    if speaker:
        try:
            HeadlessPlayer(server, speaker, password).start()
            print(f"  Speaker '{speaker}' plays on this machine")
        except MpvError as error:
            print(f"  Speaker '{speaker}' disabled: {error}")

    ffmpeg = media.ffmpeg_path()
    live_name = os.environ.get("SERYMUSIC_LIVE", "Emisión")
    if live_name != "0":
        if ffmpeg:
            from serymusic_web.live import LiveBroadcast

            listen_url = f"{public}/live" + (f"?token={app.config['LIVE_TOKEN']}" if password else "")
            broadcast = LiveBroadcast(ffmpeg, listen_url)
            app.extensions["serymusic"]["live"] = broadcast
            device_id = "live-" + hashlib.sha256(live_name.encode()).hexdigest()[:16]
            HeadlessPlayer(server, live_name, password, output=broadcast, device_id=device_id).start()
            print(f"  Live stream '{live_name}': {listen_url}")
        else:
            print("  Live stream disabled: ffmpeg is not installed")

    if os.environ.get("SERYMUSIC_DLNA", "1") != "0":
        if ffmpeg:
            from serymusic_web.dlna import DlnaManager

            port = int(app.config["SELF_URL"].rsplit(":", 1)[1])
            ssdp_port = int(os.environ.get("SERYMUSIC_SSDP_PORT", str(port + 2)))
            bind = address if address != "localhost" else ""
            DlnaManager(server, public, password, bind_address=bind, port=ssdp_port).start()
            print(f"  DLNA: looking for TVs and speakers (replies on UDP {ssdp_port})")
        else:
            print("  DLNA disabled: ffmpeg is not installed")
    print()


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    app = create_app()

    host = os.environ.get("SERYMUSIC_HOST", "0.0.0.0")
    port = int(os.environ.get("SERYMUSIC_PORT", "8080"))
    mcp_port = int(os.environ.get("SERYMUSIC_MCP_PORT", str(port + 1)))
    address = lan_address() if host == "0.0.0.0" else host
    # Base of the audio links given to agents
    if not app.config["PUBLIC_URL"]:
        app.config["PUBLIC_URL"] = f"http://{address}:{port}"

    password_file = app.config.get("PASSWORD_FILE")
    if app.config["NO_PASSWORD"]:
        print("\n  No password: anyone who can reach this server can use it.")
    elif password_file:
        print(f"\n  Password: {password_file.read_text(encoding='utf-8').strip()}")
        print(f"  (saved in {password_file}, set SERYMUSIC_PASSWORD to choose your own)")
    print(f"\n  SeryMusic web:   http://{address}:{port}")
    if mcp_port:
        start_mcp(app, host, mcp_port)
        print(f"  MCP for agents:  http://{address}:{mcp_port}/mcp\n")

    # How the server reaches itself, for the players running inside it
    app.config["SELF_URL"] = f"http://{'127.0.0.1' if host == '0.0.0.0' else host}:{port}"
    start_players(app, address)

    from waitress import serve
    # Every song being played, and every open player, keeps a thread busy
    serve(app, host=host, port=port, threads=int(os.environ.get("SERYMUSIC_THREADS", "32")))


if __name__ == "__main__":
    main()
