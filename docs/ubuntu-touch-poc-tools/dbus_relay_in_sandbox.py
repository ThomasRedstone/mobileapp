#!/usr/bin/env python3
"""Runs INSIDE the Libertine sandbox. Creates /run/dbus/system_bus_socket
(the well-known path native D-Bus clients hardcode, ignoring
DBUS_SYSTEM_BUS_ADDRESS) and forwards connections to the outer proxy at
/run/user/<uid>/dbus-system-proxy.sock, which IS visible inside the sandbox
(bind-mounted) and which dbus_proxy.py (running outside, on the real host)
forwards on to the real /run/dbus/system_bus_socket.

Must run inside the same `libertine-launch` invocation as the app it's
serving: each invocation gets its own private /run tmpfs, so a relay
started in one invocation is invisible to an app started in another.
"""
import asyncio
import os
import re
import sys
import time

LISTEN_PATH = "/run/dbus/system_bus_socket"
TARGET_PATH = sys.argv[1] if len(sys.argv) > 1 else "/run/user/32011/dbus-system-proxy.sock"

# D-Bus interface/member/path/error names are ASCII and self-delimited by a
# preceding little-endian length prefix in the wire format, but for a quick
# eyeball trace it's enough to just pull out anything that looks like one.
_STRINGY = re.compile(rb"[A-Za-z0-9_./]{6,}")


_INTERESTING_BARE = {"Connect", "Disconnect", "Pair", "CancelPairing", "StartNotify",
                     "StopNotify", "AcquireWrite", "AcquireNotify", "WriteValue",
                     "ReadValue", "RemoveDevice", "StartDiscovery", "StopDiscovery"}


def preview(data):
    found = set()
    for m in _STRINGY.finditer(data):
        s = m.group().decode("ascii", "ignore")
        if "." in s or "/" in s or s in _INTERESTING_BARE:
            found.add(s)
    return ", ".join(sorted(found)[:10])


async def pipe(reader, writer, other_writer, tag, start):
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            print(f"[{time.monotonic() - start:.3f}s] {tag}: {len(data)} bytes | {preview(data)}", flush=True)
            writer.write(data)
            await writer.drain()
    except (ConnectionResetError, BrokenPipeError, OSError):
        pass
    finally:
        # See dbus_proxy.py - closing only our own side leaks the peer's fd/task
        # forever if it's blocked waiting on data that will never come.
        writer.close()
        other_writer.close()


async def handle(client_reader, client_writer):
    start = time.monotonic()
    try:
        target_reader, target_writer = await asyncio.open_unix_connection(TARGET_PATH)
    except Exception as e:
        print(f"failed to connect to {TARGET_PATH}: {e}", flush=True)
        client_writer.close()
        return
    print(f"[0.000s] new client connection, connected to target", flush=True)
    await asyncio.gather(
        pipe(client_reader, target_writer, client_writer, "client->target", start),
        pipe(target_reader, client_writer, target_writer, "target->client", start),
        return_exceptions=True,
    )
    print(f"[{time.monotonic() - start:.3f}s] connection closed", flush=True)


async def main():
    os.makedirs(os.path.dirname(LISTEN_PATH), exist_ok=True)
    if os.path.exists(LISTEN_PATH):
        os.remove(LISTEN_PATH)
    server = await asyncio.start_unix_server(handle, path=LISTEN_PATH, backlog=256)
    os.chmod(LISTEN_PATH, 0o666)
    print(f"relaying {LISTEN_PATH} -> {TARGET_PATH}", flush=True)
    async with server:
        await server.serve_forever()


asyncio.run(main())
