#!/usr/bin/env python3
"""Keep the iproxy USB tunnel to Tester on a physical iPhone/iPad healthy.

Runs `iproxy <local>:<device> -u <udid>`, probes Tester through it, prints clear
status changes and restarts iproxy when the tunnel goes stale (connections are
accepted but never answered). Use --check for a one-shot diagnosis.
"""

import argparse
import datetime
import http.client
import os
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

STATUS_OK = "ok"
STATUS_TIMEOUT = "timeout"
STATUS_NO_SERVER = "no-server"
STATUS_NOT_LISTENING = "not-listening"

ADVICE = {
    STATUS_OK: "Tester answered through the tunnel.",
    STATUS_TIMEOUT: "No reply. The tunnel is stale, or the device is locked / Tester is in the background.",
    STATUS_NO_SERVER: "Tunnel is up but Tester is not serving: unlock the device, open Tester and tap Start server.",
    STATUS_NOT_LISTENING: "Nothing listens on the local port: iproxy is not running.",
}


def log(message):
    stamp = datetime.datetime.now().strftime("%H:%M:%S")
    print(f"[{stamp}] {message}", flush=True)


def parse_lsof(output):
    """Return (pid, command) from `lsof -Fpc` output, or None."""
    pid = command = None
    for line in output.splitlines():
        if line.startswith("p") and pid is None:
            pid = int(line[1:])
        elif line.startswith("c") and command is None:
            command = line[1:]
    return (pid, command) if pid is not None else None


def port_owner(port):
    try:
        result = subprocess.run(
            ["lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN", "-Fpc"],
            capture_output=True, text=True, timeout=10,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    return parse_lsof(result.stdout)


def classify(error):
    if error is None or isinstance(error, urllib.error.HTTPError):
        return STATUS_OK
    reason = getattr(error, "reason", error)
    if isinstance(reason, (TimeoutError,)) or "timed out" in str(reason):
        return STATUS_TIMEOUT
    if isinstance(reason, ConnectionRefusedError):
        return STATUS_NOT_LISTENING
    if isinstance(reason, (ConnectionResetError, http.client.RemoteDisconnected, BrokenPipeError)):
        return STATUS_NO_SERVER
    if isinstance(error, (ConnectionResetError, http.client.RemoteDisconnected)):
        return STATUS_NO_SERVER
    return STATUS_NO_SERVER


def probe(port, timeout):
    try:
        urllib.request.urlopen(f"http://127.0.0.1:{port}/mcp", timeout=timeout).close()
        return classify(None)
    except Exception as error:  # noqa: BLE001 - every failure is classified
        if isinstance(error, urllib.error.HTTPError):
            error.close()
        return classify(error)


class Tunnel:
    def __init__(self, iproxy, udid, local_port, device_port):
        self.command = [iproxy, f"{local_port}:{device_port}", "-u", udid]
        self.process = None

    def start(self):
        self.process = subprocess.Popen(self.command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        log(f"started {' '.join(self.command)} (pid {self.process.pid})")
        time.sleep(1)

    def alive(self):
        return self.process is not None and self.process.poll() is None

    def stop(self):
        if self.alive():
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
        self.process = None

    def restart(self, reason):
        log(f"restarting iproxy: {reason}")
        self.stop()
        self.start()


def free_port(port, replace):
    owner = port_owner(port)
    if owner is None:
        return True
    pid, command = owner
    if replace and command == "iproxy":
        log(f"stopping stale iproxy on port {port} (pid {pid})")
        os.kill(pid, signal.SIGTERM)
        for _ in range(20):
            time.sleep(0.25)
            if port_owner(port) is None:
                return True
    log(f"port {port} is held by {command} (pid {pid}). Stop it with: kill {pid}"
        + ("" if command != "iproxy" else "  (or rerun with --replace)"))
    return False


def free_local_port():
    import socket
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def fresh_tunnel_status(iproxy, udid, device_port, timeout):
    """Probe Tester through a temporary new tunnel to tell a stale tunnel from a device problem."""
    port = free_local_port()
    temp = Tunnel(iproxy, udid, port, device_port)
    temp.process = subprocess.Popen(temp.command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        time.sleep(1.5)
        return probe(port, timeout)
    finally:
        temp.stop()


def check(port, timeout, udid=None, device_port=8080):
    owner = port_owner(port)
    status = probe(port, timeout)
    holder = f"{owner[1]} (pid {owner[0]})" if owner else "nothing"
    print(f"port {port}: held by {holder}; status: {status}")
    print(ADVICE[status])
    iproxy = shutil.which("iproxy")
    if status in (STATUS_TIMEOUT, STATUS_NO_SERVER) and udid and iproxy:
        fresh = fresh_tunnel_status(iproxy, udid, device_port, timeout)
        print(f"fresh tunnel to the device: {fresh}")
        if fresh == STATUS_OK:
            kill = f"kill {owner[0]} and " if owner else ""
            print(f"Verdict: the tunnel on port {port} is stale. Fix: {kill}start it again"
                  f" (or run this script with --udid {udid} --replace).")
        else:
            print("Verdict: the device side is not answering. Unlock it, open Tester and make sure"
                  " the server is Live.")
    elif status == STATUS_TIMEOUT and owner and owner[1] == "iproxy":
        print(f"Add --udid to tell a stale tunnel from a locked device, or restart it: kill {owner[0]}")
    return 0 if status == STATUS_OK else 1


def supervise(args, iproxy):
    if not free_port(args.local_port, args.replace):
        return 2
    tunnel = Tunnel(iproxy, args.udid, args.local_port, args.device_port)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    tunnel.start()
    last_status = None
    timeouts = 0
    last_restart = 0.0
    try:
        while True:
            if not tunnel.alive():
                tunnel.restart("iproxy exited")
                last_restart = time.monotonic()
            status = probe(args.local_port, args.probe_timeout)
            if status != last_status:
                log(f"status: {status} - {ADVICE[status]}")
                last_status = status
            timeouts = timeouts + 1 if status == STATUS_TIMEOUT else 0
            if timeouts >= args.stale_after and time.monotonic() - last_restart >= args.restart_backoff:
                tunnel.restart(f"{timeouts} probes timed out")
                last_restart = time.monotonic()
                timeouts = 0
            time.sleep(args.interval)
    except KeyboardInterrupt:
        pass
    finally:
        tunnel.stop()
        log("tunnel stopped")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--udid", help="Device UDID (required unless --check)")
    parser.add_argument("--local-port", type=int, default=8081)
    parser.add_argument("--device-port", type=int, default=8080)
    parser.add_argument("--interval", type=float, default=10.0, help="Seconds between probes")
    parser.add_argument("--probe-timeout", type=float, default=4.0)
    parser.add_argument("--stale-after", type=int, default=2, help="Timed-out probes before restarting")
    parser.add_argument("--restart-backoff", type=float, default=30.0, help="Minimum seconds between restarts")
    parser.add_argument("--replace", action="store_true", help="Stop an existing iproxy holding the port")
    parser.add_argument("--check", action="store_true", help="Diagnose the port once and exit")
    args = parser.parse_args(argv)
    if args.check:
        return check(args.local_port, args.probe_timeout, args.udid, args.device_port)
    if not args.udid:
        parser.error("--udid is required")
    iproxy = shutil.which("iproxy")
    if not iproxy:
        print("iproxy not found. Install it with: brew install libimobiledevice", file=sys.stderr)
        return 2
    return supervise(args, iproxy)


if __name__ == "__main__":
    sys.exit(main())
