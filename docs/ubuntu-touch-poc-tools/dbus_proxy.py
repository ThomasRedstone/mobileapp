#!/usr/bin/env python3
"""Forwards a Unix socket to the real system D-Bus socket.

Run on the real host (outside the Libertine bwrap sandbox), listening on a
path under /run/user/<uid>/ - that directory IS bind-mounted into the
sandbox, whereas /run/dbus is not (the sandbox uses --tmpfs /run). Point
DBUS_SYSTEM_BUS_ADDRESS at the listen path from inside the sandbox.
"""
import asyncio
import os
import sys

LISTEN_PATH = sys.argv[1] if len(sys.argv) > 1 else "/run/user/32011/dbus-system-proxy.sock"
TARGET_PATH = sys.argv[2] if len(sys.argv) > 2 else "/run/dbus/system_bus_socket"


async def pipe(reader, writer):
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            writer.write(data)
            await writer.drain()
    except (ConnectionResetError, BrokenPipeError):
        pass
    finally:
        writer.close()


async def handle(client_reader, client_writer):
    try:
        target_reader, target_writer = await asyncio.open_unix_connection(TARGET_PATH)
    except Exception as e:
        print(f"failed to connect to {TARGET_PATH}: {e}", flush=True)
        client_writer.close()
        return
    await asyncio.gather(
        pipe(client_reader, target_writer),
        pipe(target_reader, client_writer),
    )


async def main():
    if os.path.exists(LISTEN_PATH):
        os.remove(LISTEN_PATH)
    server = await asyncio.start_unix_server(handle, path=LISTEN_PATH)
    os.chmod(LISTEN_PATH, 0o666)
    print(f"proxying {LISTEN_PATH} -> {TARGET_PATH}", flush=True)
    async with server:
        await server.serve_forever()


asyncio.run(main())
