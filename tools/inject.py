import sys
import zipfile
import shutil
import os

src_apk, dex_path, meta_dir, out_apk = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]

if os.path.exists(out_apk):
    os.remove(out_apk)

with zipfile.ZipFile(src_apk, "r") as zin:
    entries = zin.infolist()
    with zipfile.ZipFile(out_apk, "w") as zout:
        for item in entries:
            data = zin.read(item.filename)
            keep = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            keep.compress_type = item.compress_type
            keep.external_attr = item.external_attr
            zout.writestr(keep, data)
        zi = zipfile.ZipInfo("classes.dex")
        zi.compress_type = zipfile.ZIP_DEFLATED
        with open(dex_path, "rb") as f:
            zout.writestr(zi, f.read())
        for root, _, files in os.walk(meta_dir):
            for name in files:
                full = os.path.join(root, name)
                rel = os.path.relpath(full, meta_dir).replace("\\", "/")
                arc = "META-INF/" + rel
                with open(full, "rb") as f:
                    zout.writestr(arc, f.read())

print("injected ->", out_apk, os.path.getsize(out_apk), "bytes")
