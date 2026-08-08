import tarfile
tarfile.TarFile.extraction_filter = staticmethod(tarfile.fully_trusted_filter)

# maliit-inputcontext-gtk2 no longer exists in resolute/26.04 repos (GTK2 IM
# support has been dropped upstream). Libertine hardcodes it in its default
# package list; strip it rather than patch the installed package.
from libertine.Libertine import BaseContainer
_orig_init = BaseContainer.__init__
def _patched_init(self, *a, **kw):
    _orig_init(self, *a, **kw)
    self.default_packages = [p for p in self.default_packages if p != 'maliit-inputcontext-gtk2']
BaseContainer.__init__ = _patched_init

import runpy
import sys
sys.argv = ['libertine-container-manager', '-v', 'create', '-i', 'x11poc', '-n', 'X11 PoC', '-t', 'chroot', '--force']
runpy.run_path('/usr/bin/libertine-container-manager', run_name='__main__')
