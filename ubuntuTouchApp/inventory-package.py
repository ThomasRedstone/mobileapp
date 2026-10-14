#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Normalize launcher dependency order and preserve/inventory every JAR notice."""
import argparse,hashlib,json,subprocess,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--root',required=True,type=Path);p.add_argument('--source',required=True,type=Path);p.add_argument('--packager',required=True);a=p.parse_args()
root=a.root.resolve()
# jpackage enumerates input JARs alphabetically. Use Gradle's ordered runtime
# classpath explicitly; duplicate Maven basenames remain separate hash names.
app=root/'lib/app';ordered=(app/'RUNTIME-CLASSPATH.txt').read_text().splitlines()
for name in ordered:
 if Path(name).name!=name or not (app/name).is_file():raise ValueError('invalid staged classpath')
cfg=app/'coreapp.cfg';lines=cfg.read_text().splitlines();first=next(i for i,line in enumerate(lines) if line.startswith('app.classpath='))
lines=[line for line in lines if not line.startswith('app.classpath=')]
lines[first:first]=['app.classpath=$APPDIR/coreapp.jar']+['app.classpath=$APPDIR/'+name for name in ordered]
cfg.write_text('\n'.join(lines)+'\n')
notices=root/'DEPENDENCY-NOTICES';notices.mkdir(exist_ok=False);entries=[]
for jar in sorted(root.rglob('*.jar')):
 h=hashlib.sha256(jar.read_bytes()).hexdigest();names=[]
 with zipfile.ZipFile(jar) as z:
  for n in z.namelist():
   if n.endswith('/'):continue
   if not any(word in Path(n).name.upper() for word in ('LICENSE','COPYING','NOTICE')):continue
   if Path(n).is_absolute() or '..' in Path(n).parts:raise ValueError('unsafe JAR notice')
   out=notices/h/n;out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(z.read(n));names.append(n)
 entries.append({'path':str(jar.relative_to(root)),'sha256':h,'notice_entries':names})
record={'source_revision':subprocess.check_output(['git','rev-parse','HEAD'],cwd=a.source,text=True).strip(),'source_dirty_paths':subprocess.check_output(['git','status','--porcelain','--untracked-files=no'],cwd=a.source,text=True).splitlines(),'packager_image':a.packager,'jar_inventory':entries,'status':'candidate; dependency corresponding-source and hardware acceptance remain required'}
(root/'PACKAGE-SOURCE-INVENTORY.json').write_text(json.dumps(record,indent=2)+'\n')
