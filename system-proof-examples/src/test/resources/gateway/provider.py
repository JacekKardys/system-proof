import http.server
import os
import socketserver
import threading


HTTP_PORT = int(os.environ["HTTP_PORT"])
SESSION_PORT = int(os.environ["SESSION_PORT"])


class ReusableThreadingServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


class HttpHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/route":
            self.send_error(404)
            return
        body = b"http-provider"
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        return


class SessionHandler(socketserver.StreamRequestHandler):
    def handle(self):
        for line in self.rfile:
            self.wfile.write(b"smpp-provider:" + line)
            self.wfile.flush()


session_server = ReusableThreadingServer(("0.0.0.0", SESSION_PORT), SessionHandler)
threading.Thread(target=session_server.serve_forever, daemon=True).start()

http_server = http.server.ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), HttpHandler)
http_server.serve_forever()
