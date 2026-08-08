# Libertine container creation on current UT (26.04/resolute)

`create_libertine_container.py` is a drop-in replacement invocation for
`libertine-container-manager -v create -i <id> -n <name> -t chroot --force`. Run it with
`python3 create_libertine_container.py` on-device/on-VM (no install needed — it monkeypatches
the already-installed `libertine` package in-process, touching no system files).

Fixes two real compatibility bugs between the `libertine-tools` package and this image's
generation, both discovered creating a container against a live UT 26.04 VM
(`ubuntu-touch-pdk-img-amd64.raw`, see `docs/ubuntu-touch-poc-plan.md`):

1. **`'etc/alternatives/awk' is a link to an absolute path`** — Python 3.14's `tarfile` module
   defaults `extractall()` to the restrictive `'data'` extraction filter (PEP 706), which
   rejects the ubuntu-base tarball's absolute symlinks. Libertine's `ChrootContainer.py` calls
   `extractall()` with no filter argument, so it inherits the new restrictive default. Fixed by
   setting `tarfile.TarFile.extraction_filter = staticmethod(tarfile.fully_trusted_filter)`
   before Libertine's code runs.
2. **`Unable to locate package maliit-inputcontext-gtk2`** — dropped from the resolute/26.04
   repos (GTK2 input-method support is gone upstream). Libertine hardcodes it in
   `Libertine.BaseContainer.default_packages`. Fixed by monkeypatching `BaseContainer.__init__`
   to strip that one entry after construction.

Neither fix modifies an installed package or requires root — both are process-local monkeypatches
applied before calling into Libertine's own `main()` via `runpy`.
