#!/usr/bin/env python3
"""PC stand-in for the ESP32 wifi_sync module (docs/wifi_sync_protocol.md v1).

Serves ride CSVs from a directory over the same /rides + /data API the
firmware exposes, so the Android sync client is testable end-to-end with no
device hardware. --die-after-bytes simulates the AP dropping mid-transfer
(resume testing) -- once total served bytes cross the threshold, EVERY
subsequent /data request dies for the rest of THIS process's life, so
restart the server without the flag to test the completed-resume path.
--generate synthesizes a plausible M271 ride CSV first.

Usage:
  python kl_sync_dev_server.py --dir ./rides --port 8765
  python kl_sync_dev_server.py --dir ./rides --generate 2 --port 8765
  python kl_sync_dev_server.py --dir ./rides --die-after-bytes 6000
"""
import argparse
import json
import random
import zlib
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

MAX_CHUNK = 4096  # firmware kSyncMaxChunk


def generate_ride(path: Path, seq: int, minutes: int = 4) -> None:
    """Cold-start city ride: warms 20->88 degC, idles + a few pulls."""
    rng = random.Random(seq)
    lines = ["#fw_version=devsim", "#init_mode=fast-init", "t_ms,signal,value",
             "#time_sync=0:1752700000000"]
    t = 0
    while t < minutes * 60_000:
        ect = min(88.0, 20.0 + t / 4000.0)          # ~15 degC/min warm-up
        pull = (t // 30_000) % 3 == 2 and (t % 30_000) < 4_000
        rpm = 3400 + rng.uniform(-100, 100) if pull else 820 + rng.uniform(-25, 25)
        throttle = 85.0 if pull else 12.0
        ltft = 6.0 if not pull else 1.5             # mild idle-vs-load trim shift
        maf = 95.0 if pull else 4.2
        o2 = 0.45 if t < 25_000 else (0.75 if (t // 500) % 2 else 0.15)
        for offset, (name, value) in enumerate([
            ("RPM", rpm), ("SPEED", 42.0 if pull else 0.0), ("THROTTLE", throttle),
            ("MAF_GS", maf), ("LTFT1", ltft), ("ECT", ect), ("O2_B1S1_V", o2),
        ]):
            lines.append(f"{t + offset * 60},{name},{value:.3f}")
        t += 500
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


class Handler(BaseHTTPRequestHandler):
    rides_dir: Path
    die_after: int = -1
    served: int = 0

    def do_GET(self):  # noqa: N802 (stdlib naming)
        url = urlparse(self.path)
        if url.path == "/rides":
            rides = []
            for f in sorted(self.rides_dir.glob("ride_*.csv")):
                data = f.read_bytes()
                rides.append({"name": f.name, "size": len(data),
                              "crc32": f"{zlib.crc32(data) & 0xFFFFFFFF:08X}"})
            body = json.dumps({"rides": rides}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if url.path == "/data":
            q = parse_qs(url.query)
            try:
                name, offset, length = q["name"][0], int(q["offset"][0]), int(q["length"][0])
            except (KeyError, ValueError):
                self.send_error(400, "name/offset/length required")
                return
            if offset < 0 or length <= 0:
                self.send_error(400, "offset/length must be positive")
                return
            f = self.rides_dir / name
            if not f.is_file() or "/" in name or "\\" in name:
                self.send_error(404, "no such ride")
                return
            if 0 <= Handler.die_after <= Handler.served:
                self.connection.close()  # simulate the AP vanishing
                return
            data = f.read_bytes()[offset:offset + min(length, MAX_CHUNK)]
            Handler.served += len(data)
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        self.send_error(404)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dir", default="./rides")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--generate", type=int, default=0, metavar="N")
    ap.add_argument("--die-after-bytes", type=int, default=-1)
    args = ap.parse_args()
    rides = Path(args.dir)
    rides.mkdir(parents=True, exist_ok=True)
    for i in range(args.generate):
        generate_ride(rides / f"ride_{i + 1:05d}.csv", seq=i + 1)
    Handler.rides_dir = rides
    Handler.die_after = args.die_after_bytes
    print(f"kl_sync_dev_server on 0.0.0.0:{args.port}, dir={rides.resolve()}")
    HTTPServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
