"""Bundle pinned production JS dependencies into the APK; no install/download occurs on the phone."""
from pathlib import Path
import hashlib, json, zipfile
root=Path(__file__).resolve().parents[1]
api=root.parent/'android-api'
assets=root/'app/src/main/assets'
assets.mkdir(parents=True,exist_ok=True)
if not (api/'node_modules/axios/package.json').exists():
    raise SystemExit('Run npm ci --omit=dev --ignore-scripts in android-api first.')
archive=assets/'echo-api.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as output:
    files=[api/'package.json',api/'package-lock.json',api/'LICENSE']
    for folder in ['module','util','node_modules']:
        files.extend(path for path in (api/folder).rglob('*') if path.is_file() and '.bin' not in path.relative_to(api).parts)
    for path in sorted(files):
        if path.suffix=='.node':raise SystemExit('Native npm dependency is not supported: '+str(path))
        relative=path.relative_to(api).as_posix()
        entry=zipfile.ZipInfo(relative,date_time=(2026,1,1,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED
        output.writestr(entry,path.read_bytes())
    entry=zipfile.ZipInfo('bootstrap.cjs',date_time=(2026,1,1,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED
    output.writestr(entry,(root/'app/src/main/node/bootstrap.cjs').read_bytes())
manifest={'version':3,'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'files':len(files)+1,'apiVersion':json.loads((api/'package.json').read_text())['version']}
(assets/'embedded-api.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print(json.dumps(manifest),'bundle bytes:',archive.stat().st_size)
