"""Connect a local acceptance driver to one snapshot of the agent port file."""
import json
import re
import socket


def connect_status(path, timeout):
    if path.is_symlink() or not path.is_file():
        raise ValueError("Expected a regular status port file")
    with path.open("rb") as source:
        data = source.read(257)
    match = re.fullmatch(rb"([0-9]{1,5})(?:\r?\ntoken=([0-9a-f]{64}))?(?:\r?\n)?", data)
    if not match or not 0 < int(match[1]) <= 65535:
        raise ValueError("Invalid status port file")
    connection = socket.create_connection(("127.0.0.1", int(match[1])), timeout)
    try:
        if match[2]:
            connection.sendall(b"AUTH " + match[2] + b"\n")
        # Leave subsequent event bytes on the socket for the caller's buffered reader.
        with connection.makefile("rb", buffering=0) as stream:
            greeting = stream.readline(16385)
        if len(greeting) > 16384 or not greeting.endswith(b"\n"):
            raise ValueError("Missing status greeting")
        hello = json.loads(greeting)
        if hello.get("level") != "CONNECTED" or hello.get("version") != (2 if match[2] else 1):
            raise ValueError("Incompatible status protocol")
        return connection
    except BaseException:
        connection.close()
        raise
