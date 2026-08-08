"""
Minimal stub imitating the shape of BlueZ's D-Bus surface (ObjectManager +
Adapter1), enough to prove D-Bus IPC mechanics round-trip in this sandbox.
This is NOT real BlueZ and does not validate real adapter/permission/AppArmor
behaviour on an actual Ubuntu Touch device.
"""
import asyncio
from dbus_next.aio import MessageBus
from dbus_next.service import ServiceInterface, method
from dbus_next import Variant

BUS_NAME = "org.bluez.stub"
ADAPTER_PATH = "/org/bluez/hci0"


class AdapterInterface(ServiceInterface):
    def __init__(self):
        super().__init__("org.bluez.Adapter1")

    @method()
    def StartDiscovery(self):
        print("STUB: StartDiscovery called", flush=True)


class ObjectManagerInterface(ServiceInterface):
    def __init__(self):
        super().__init__("org.freedesktop.DBus.ObjectManager")

    @method()
    def GetManagedObjects(self) -> "a{oa{sa{sv}}}":
        return {
            ADAPTER_PATH: {
                "org.bluez.Adapter1": {
                    "Address": Variant("s", "AA:BB:CC:DD:EE:FF"),
                    "Powered": Variant("b", True),
                }
            }
        }


async def main():
    bus = await MessageBus().connect()
    bus.export(ADAPTER_PATH, AdapterInterface())
    bus.export("/", ObjectManagerInterface())
    await bus.request_name(BUS_NAME)
    print("STUB: bluez stub service registered", flush=True)
    await asyncio.Event().wait()


asyncio.run(main())
