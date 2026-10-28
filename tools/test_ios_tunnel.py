#!/usr/bin/env python3
"""Unit tests for the iproxy tunnel supervisor."""

import http.client
import importlib.util
import socket
import threading
import unittest
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

spec = importlib.util.spec_from_file_location("tunnel", Path(__file__).with_name("ios_tunnel.py"))
tunnel = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tunnel)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(405)
        self.end_headers()

    def log_message(self, *args):
        pass


class TunnelTests(unittest.TestCase):
    def test_parse_lsof(self):
        self.assertEqual((2582, "iproxy"), tunnel.parse_lsof("p2582\nciproxy\nf4\n"))
        self.assertIsNone(tunnel.parse_lsof(""))

    def test_classify(self):
        self.assertEqual(tunnel.STATUS_OK, tunnel.classify(None))
        self.assertEqual(tunnel.STATUS_TIMEOUT, tunnel.classify(urllib.error.URLError(TimeoutError("timed out"))))
        self.assertEqual(tunnel.STATUS_TIMEOUT, tunnel.classify(TimeoutError("timed out")))
        self.assertEqual(tunnel.STATUS_NOT_LISTENING,
                         tunnel.classify(urllib.error.URLError(ConnectionRefusedError())))
        self.assertEqual(tunnel.STATUS_NO_SERVER, tunnel.classify(ConnectionResetError()))
        self.assertEqual(tunnel.STATUS_NO_SERVER, tunnel.classify(http.client.RemoteDisconnected()))

    def test_probe_live_server_and_refused_port(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            self.assertEqual(tunnel.STATUS_OK, tunnel.probe(server.server_address[1], 2))
        finally:
            server.shutdown()
            server.server_close()
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            free = sock.getsockname()[1]
        self.assertEqual(tunnel.STATUS_NOT_LISTENING, tunnel.probe(free, 2))

    def test_probe_timeout_when_never_answered(self):
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(5)
        try:
            self.assertEqual(tunnel.STATUS_TIMEOUT, tunnel.probe(listener.getsockname()[1], 0.5))
        finally:
            listener.close()


if __name__ == "__main__":
    unittest.main()
