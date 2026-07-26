import http.client
import http.server
import os
import socket


CONTROL_PORT = int(os.environ["CONTROL_PORT"])
HTTP_HOST = os.environ["ROUTED_HTTP_HOST"]
HTTP_PORT = int(os.environ["ROUTED_HTTP_PORT"])
HTTP_PATH = os.environ["ROUTED_HTTP_PATH"]
SMPP_HOST = os.environ["ROUTED_SMPP_HOST"]
SMPP_PORT = int(os.environ["ROUTED_SMPP_PORT"])


def prove_http_route():
    connection = http.client.HTTPConnection(HTTP_HOST, HTTP_PORT, timeout=10)
    try:
        connection.request("GET", HTTP_PATH)
        response = connection.getresponse()
        body = response.read().decode("utf-8")
        if response.status != 200:
            raise RuntimeError(f"HTTP provider returned status {response.status}")
        return body
    finally:
        connection.close()


def prove_long_lived_session():
    responses = []
    with socket.create_connection((SMPP_HOST, SMPP_PORT), timeout=10) as connection:
        with connection.makefile("rwb", buffering=0) as stream:
            for request in ("bind", "submit-1", "submit-2"):
                stream.write((request + "\n").encode("utf-8"))
                response = stream.readline()
                if not response:
                    raise RuntimeError("SMPP-representative session closed unexpectedly")
                responses.append(response.decode("utf-8").rstrip("\n"))
    return responses


class ControlHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/proof":
            self.send_error(404)
            return
        try:
            results = [prove_http_route(), *prove_long_lived_session()]
            body = ("\n".join(results) + "\n").encode("utf-8")
            self.send_response(200)
        except Exception as failure:
            body = str(failure).encode("utf-8")
            self.send_response(500)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        return


server = http.server.ThreadingHTTPServer(("0.0.0.0", CONTROL_PORT), ControlHandler)
server.serve_forever()
