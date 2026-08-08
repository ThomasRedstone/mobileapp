"""
Persistent companion process hosting the real BlueZ GATT server for
GattServer.jvm.kt, driven over stdin/stdout with line-delimited JSON.

dbus-java's SASL EXTERNAL auth sends the wrong UID against this platform's
BlueZ/dbus-daemon (traced at the syscall level -- a genuine upstream bug),
and busctl (used elsewhere for one-shot calls) can only invoke methods on
other services, not export/host object paths of its own. Exporting a real
GATT server therefore needs a real D-Bus binding; dbus-python + a GLib
mainloop is the one proven reliable on this platform during the Ubuntu
Touch PoC (docs/ubuntu-touch-poc-plan.md).

Service/characteristic layout is ported byte-for-byte from libpebble3's
GattServer.android.kt / LEConstants.kt / PebbleBle.android.kt, and this
file's dbus object structure mirrors BlueZ's own
test/example-gatt-server (including the easy-to-miss empty "Descriptors"
key -- omitting it fails RegisterApplication with
"No valid service object found").
"""
import json
import sys
import threading

import dbus
import dbus.exceptions
import dbus.mainloop.glib
import dbus.service
from gi.repository import GLib

BLUEZ_SERVICE_NAME = "org.bluez"
GATT_MANAGER_IFACE = "org.bluez.GattManager1"
DBUS_OM_IFACE = "org.freedesktop.DBus.ObjectManager"
DBUS_PROP_IFACE = "org.freedesktop.DBus.Properties"
GATT_SERVICE_IFACE = "org.bluez.GattService1"
GATT_CHRC_IFACE = "org.bluez.GattCharacteristic1"

PPOGATT_DEVICE_SERVICE_UUID_SERVER = "10000000-328e-0fbb-c642-1aa6699bdada"
PPOGATT_DEVICE_CHARACTERISTIC_SERVER = "10000001-328e-0fbb-c642-1aa6699bdada"
META_CHARACTERISTIC_SERVER = "10000002-328e-0fbb-c642-1aa6699bdada"
FAKE_SERVICE_UUID = "badbadba-dbad-badb-adba-badbadbadbad"

# PebbleBle.android.kt's actual val SERVER_META_RESPONSE, byte-for-byte.
SERVER_META_RESPONSE = [0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1]


def emit(event):
    sys.stdout.write(json.dumps(event) + "\n")
    sys.stdout.flush()


def device_address(options):
    # BlueZ passes the device as an object path
    # (/org/bluez/hci0/dev_XX_XX_XX_XX_XX_XX) under the "device" key, not a
    # MAC string -- convert to the colon-separated address the rest of the
    # app (PebbleBleIdentifier) expects.
    path = str(options.get("device", ""))
    marker = "dev_"
    idx = path.find(marker)
    if idx == -1:
        return ""
    return path[idx + len(marker):].replace("_", ":")


class Application(dbus.service.Object):
    def __init__(self, bus):
        self.path = "/"
        self.services = []
        dbus.service.Object.__init__(self, bus, self.path)
        self.add_service(PpogService(bus, 0))
        self.add_service(FakeService(bus, 1))

    def get_path(self):
        return dbus.ObjectPath(self.path)

    def add_service(self, service):
        self.services.append(service)

    def find_characteristic(self, uuid):
        for service in self.services:
            for chrc in service.characteristics:
                if chrc.uuid == uuid:
                    return chrc
        return None

    @dbus.service.method(DBUS_OM_IFACE, out_signature="a{oa{sa{sv}}}")
    def GetManagedObjects(self):
        response = {}
        for service in self.services:
            response[service.get_path()] = service.get_properties()
            for chrc in service.get_characteristics():
                response[chrc.get_path()] = chrc.get_properties()
        return response


class Service(dbus.service.Object):
    PATH_BASE = "/io/rebble/pebble/ppog/service"

    def __init__(self, bus, index, uuid, primary):
        self.path = self.PATH_BASE + str(index)
        self.bus = bus
        self.uuid = uuid
        self.primary = primary
        self.characteristics = []
        dbus.service.Object.__init__(self, bus, self.path)

    def get_properties(self):
        return {
            GATT_SERVICE_IFACE: {
                "UUID": self.uuid,
                "Primary": self.primary,
                "Characteristics": dbus.Array(
                    [c.get_path() for c in self.characteristics], signature="o"
                ),
            }
        }

    def get_path(self):
        return dbus.ObjectPath(self.path)

    def add_characteristic(self, characteristic):
        self.characteristics.append(characteristic)

    def get_characteristics(self):
        return self.characteristics

    @dbus.service.method(DBUS_PROP_IFACE, in_signature="s", out_signature="a{sv}")
    def GetAll(self, interface):
        return self.get_properties()[GATT_SERVICE_IFACE]


class Characteristic(dbus.service.Object):
    def __init__(self, bus, index, uuid, flags, service):
        self.path = service.path + "/char" + str(index)
        self.bus = bus
        self.uuid = uuid
        self.service = service
        self.flags = flags
        self.value = []
        dbus.service.Object.__init__(self, bus, self.path)

    def get_properties(self):
        return {
            GATT_CHRC_IFACE: {
                "Service": self.service.get_path(),
                "UUID": self.uuid,
                "Flags": self.flags,
                "Descriptors": dbus.Array([], signature="o"),
            }
        }

    def get_path(self):
        return dbus.ObjectPath(self.path)

    @dbus.service.method(DBUS_PROP_IFACE, in_signature="s", out_signature="a{sv}")
    def GetAll(self, interface):
        return self.get_properties()[GATT_CHRC_IFACE]

    @dbus.service.method(GATT_CHRC_IFACE, in_signature="a{sv}", out_signature="ay")
    def ReadValue(self, options):
        # Synchronous, like the proven prototype: our characteristics
        # (meta response, fake service) hold a static value set at
        # construction/via set_value, so there's no need for an async
        # round-trip to Kotlin before answering BlueZ.
        device = device_address(options)
        emit({"event": "read_request", "device": device, "uuid": self.uuid})
        return dbus.Array(self.value, signature="y")

    def set_value(self, data):
        self.value = data

    @dbus.service.method(GATT_CHRC_IFACE, in_signature="aya{sv}")
    def WriteValue(self, value, options):
        self.value = value
        device = device_address(options)
        emit({
            "event": "write",
            "device": device,
            "uuid": self.uuid,
            "data_hex": bytes(value).hex(),
        })

    @dbus.service.method(GATT_CHRC_IFACE)
    def StartNotify(self):
        # No device/options arg here -- matches BlueZ's real StartNotify()
        # signature (confirmed against the proven prototype); notifications
        # are addressed by writing directly to whichever device the app
        # registered via registerDevice(), not by tracking subscribers here.
        emit({"event": "notify_subscribed", "uuid": self.uuid})

    @dbus.service.method(GATT_CHRC_IFACE)
    def StopNotify(self):
        emit({"event": "notify_unsubscribed", "uuid": self.uuid})

    @dbus.service.signal(DBUS_PROP_IFACE, signature="sa{sv}as")
    def PropertiesChanged(self, interface, changed, invalidated):
        pass

    def notify(self, data):
        self.value = data
        self.PropertiesChanged(
            GATT_CHRC_IFACE, {"Value": dbus.Array(data, signature="y")}, []
        )


class PpogService(Service):
    def __init__(self, bus, index):
        super().__init__(bus, index, PPOGATT_DEVICE_SERVICE_UUID_SERVER, True)
        self.add_characteristic(MetaCharacteristic(bus, 0, self))
        self.add_characteristic(PpogCharacteristic(bus, 1, self))


class FakeService(Service):
    def __init__(self, bus, index):
        super().__init__(bus, index, FAKE_SERVICE_UUID, True)
        self.add_characteristic(FakeCharacteristic(bus, 0, self))


class MetaCharacteristic(Characteristic):
    def __init__(self, bus, index, service):
        super().__init__(bus, index, META_CHARACTERISTIC_SERVER, ["encrypt-read"], service)
        self.value = SERVER_META_RESPONSE


class PpogCharacteristic(Characteristic):
    def __init__(self, bus, index, service):
        # BlueZ has no "encrypt-write-without-response" flag (confirmed via
        # bluetoothd's own journal: "Invalid characteristic flag"). The link
        # is already encrypted from pairing, so plain write-without-response
        # + notify is the correct real flag set.
        super().__init__(
            bus, index, PPOGATT_DEVICE_CHARACTERISTIC_SERVER,
            ["write-without-response", "notify"], service,
        )


class FakeCharacteristic(Characteristic):
    def __init__(self, bus, index, service):
        super().__init__(bus, index, FAKE_SERVICE_UUID, ["encrypt-read"], service)


def stdin_reader(app, mainloop):
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            command = json.loads(line)
        except ValueError:
            emit({"event": "error", "message": f"bad command: {line}"})
            continue

        def handle(command=command):
            cmd = command.get("cmd")
            if cmd == "set_value":
                chrc = app.find_characteristic(command["uuid"])
                if chrc is not None:
                    data = list(bytes.fromhex(command.get("data_hex", "")))
                    chrc.set_value(data)
            elif cmd == "notify":
                chrc = app.find_characteristic(command["uuid"])
                if chrc is not None:
                    data = list(bytes.fromhex(command.get("data_hex", "")))
                    chrc.notify(data)
            elif cmd == "close":
                mainloop.quit()
            else:
                emit({"event": "error", "message": f"unknown cmd: {cmd}"})

        # Run on the GLib mainloop thread; dbus-python objects aren't safe
        # to touch from this stdin-reading thread directly.
        GLib.idle_add(handle)
    GLib.idle_add(mainloop.quit)


def main():
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    service_manager = dbus.Interface(
        bus.get_object(BLUEZ_SERVICE_NAME, "/org/bluez/hci0"), GATT_MANAGER_IFACE
    )

    app = Application(bus)
    mainloop = GLib.MainLoop()

    def register_app_cb():
        emit({"event": "services_added"})

    def register_app_error_cb(error):
        emit({"event": "error", "message": f"RegisterApplication failed: {error}"})

    service_manager.RegisterApplication(
        app.get_path(), {}, reply_handler=register_app_cb, error_handler=register_app_error_cb
    )

    reader_thread = threading.Thread(target=stdin_reader, args=(app, mainloop), daemon=True)
    reader_thread.start()

    emit({"event": "ready"})
    mainloop.run()

    try:
        service_manager.UnregisterApplication(app.get_path())
    except dbus.exceptions.DBusException:
        pass


if __name__ == "__main__":
    main()
