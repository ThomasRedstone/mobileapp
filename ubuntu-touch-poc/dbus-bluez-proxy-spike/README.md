# Phase 0 spike 2 proxy: D-Bus IPC mechanics

`stub_bluez.py` registers a private-session-bus service that imitates the shape of
BlueZ's D-Bus surface (`org.freedesktop.DBus.ObjectManager.GetManagedObjects`,
`org.bluez.Adapter1.StartDiscovery`). `client_test.py` calls it the way a real
`libpebble3` BlueZ actual would: discover the adapter via `GetManagedObjects`, then
invoke `StartDiscovery` on it.

Run (Python, `dbus-next`):

```
dbus-daemon --session --fork --print-address=1 > /tmp/addr.txt
export DBUS_SESSION_BUS_ADDRESS=$(cat /tmp/addr.txt)
python3 stub_bluez.py &
python3 client_test.py
```

Result: full round trip succeeds — object discovery, interface introspection, and
method invocation (confirmed server-side: `StartDiscovery` fires on the service).

## What this proves

D-Bus IPC transport, service registration, and method dispatch all work
mechanically in this environment (this was not a given — the first two attempts, via
`dbus-python`+glib and `dbus-python`+native mainloop, failed for missing
dependencies before landing on `dbus-next`, a pure-Python client).

## What this does NOT prove

- This is a **session bus** stub. Real BlueZ lives on the **system bus** with its own
  permission model; this doesn't touch that.
- It's a hand-rolled imitation of BlueZ's shape, not real BlueZ. It says nothing about
  real adapter behaviour, pairing, GATT characteristics, or AppArmor confinement on an
  actual Ubuntu Touch device.
- The eventual implementation is Kotlin/Native, not Python — this only de-risks the
  D-Bus *protocol/transport* question, not the K/N D-Bus binding itself (still to be
  written, likely via `libdbus` cinterop).

Real confirmation of spike 2 still requires a physical Ubuntu Touch device with BlueZ
running on the system bus.
