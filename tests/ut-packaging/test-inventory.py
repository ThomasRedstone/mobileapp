# SPDX-License-Identifier: MIT
import json,subprocess,tempfile,unittest,zipfile
from pathlib import Path
SCRIPT=Path(__file__).resolve().parents[2]/'ubuntuTouchApp/inventory-package.py'
class Inventory(unittest.TestCase):
 def test_original_order_and_original_licenses(self):
  with tempfile.TemporaryDirectory() as t:
   root=Path(t);source=root/'source';source.mkdir();subprocess.run(['git','init','-q',str(source)],check=True);(source/'file').write_text('owned')
   subprocess.run(['git','-C',str(source),'add','.'],check=True);subprocess.run(['git','-C',str(source),'-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','-qm','fixture'],check=True)
   image=root/'image';app=image/'lib/app';app.mkdir(parents=True)
   for name,terms in [('hash-z-same.jar','first original terms'),('hash-a-same.jar','second original terms')]:
    with zipfile.ZipFile(app/name,'w') as z:z.writestr('META-INF/LICENSE',terms)
   (app/'RUNTIME-CLASSPATH.txt').write_text('hash-z-same.jar\nhash-a-same.jar\n');(app/'coreapp.cfg').write_text('[Application]\napp.classpath=$APPDIR/hash-a-same.jar\napp.mainclass=fixture\napp.classpath=$APPDIR/hash-z-same.jar\n')
   subprocess.run(['python3',str(SCRIPT),'--root',str(image),'--source',str(source),'--packager','fixture'],check=True)
   cfg=(app/'coreapp.cfg').read_text();self.assertLess(cfg.index('hash-z-same.jar'),cfg.index('hash-a-same.jar'))
   inventory=json.loads((image/'PACKAGE-SOURCE-INVENTORY.json').read_text());self.assertEqual(len(inventory['jar_inventory']),2)
   self.assertEqual({p.read_text() for p in (image/'DEPENDENCY-NOTICES').rglob('LICENSE')},{'first original terms','second original terms'})
if __name__=='__main__':unittest.main()
