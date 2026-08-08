"""
Client-side proxy: calls the stub bluez service the way a real libpebble3
BlueZ actual would (ObjectManager.GetManagedObjects to discover the
adapter, Adapter1.StartDiscovery to begin scanning). Proves D-Bus IPC
round-trips end-to-end in this sandbox — nothing more.
"""
import asyncio
from dbus_next.aio import MessageBus

BUS_NAME = "org.bluez.stub"
ADAPTER_PATH = "/org/bluez/hci0"


async def main():
    bus = await MessageBus().connect()

    introspection = await bus.introspect(BUS_NAME, "/")
    obj = bus.get_proxy_object(BUS_NAME, "/", introspection)
    om = obj.get_interface("org.freedesktop.DBus.ObjectManager")
    objects = await om.call_get_managed_objects()
    print(f"CLIENT: GetManagedObjects -> {objects}")
    assert ADAPTER_PATH in objects, "adapter not found in managed objects"

    adapter_introspection = await bus.introspect(BUS_NAME, ADAPTER_PATH)
    adapter_obj = bus.get_proxy_object(BUS_NAME, ADAPTER_PATH, adapter_introspection)
    adapter = adapter_obj.get_interface("org.bluez.Adapter1")
    await adapter.call_start_discovery()
    print("CLIENT: StartDiscovery call completed without error")
    print("CLIENT: D-Bus round trip OK")


asyncio.run(main())
