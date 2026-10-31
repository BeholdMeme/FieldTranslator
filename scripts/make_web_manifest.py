"""Writes models/manifest.json for the web app: one 'pack' per model with every file's
path and size. File paths include a short content hash, so an updated model gets a new URL
(and only changed files are downloaded again).
Usage: python make_web_manifest.py SITE_MODELS_DIR"""
import hashlib
import json
import os
import sys

PACKS = {
    "learn": "learn",
    "tts-ru": "tts/ru",
    "asr-ru": "asr/ru-zipformer",
    "asr-en": "asr/en-zipformer",
    "mt-ru-en": "mt/ru-en",
    "mt-en-ru": "mt/en-ru",
    "ocr": "ocr",
}
# these packs keep the real file name at the end of the URL (the hash becomes a folder instead),
# because the camera reader's engine decides how to load a file from its extension
HASH_AS_FOLDER = {"ocr"}


def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()[:10]


def main():
    root = sys.argv[1]
    out = {"version": 1, "packs": {}}
    for name, rel in PACKS.items():
        base = os.path.join(root, rel)
        if not os.path.isdir(base):
            sys.exit("missing model folder " + base)
        files = []
        for dirpath, _, names in list(os.walk(base)):
            for n in sorted(names):
                full = os.path.join(dirpath, n)
                inner = os.path.relpath(full, base).replace(os.sep, "/")
                if inner in ("MODEL_CARD", "README.md", "stress_meta.txt"):
                    continue
                h = digest(full)
                if name in HASH_AS_FOLDER:
                    hashed = os.path.join(base, h, inner)
                    os.makedirs(os.path.dirname(hashed), exist_ok=True)
                    os.rename(full, hashed)
                    url = rel + "/" + h + "/" + inner
                else:
                    hashed = full + "~" + h
                    os.rename(full, hashed)
                    url = rel + "/" + inner + "~" + h
                files.append({"name": inner, "path": url, "size": os.path.getsize(hashed)})
        files.sort(key=lambda f: f["name"])
        out["packs"][name] = {"files": files}
        print(name, len(files), "files", sum(f["size"] for f in files) // 1048576, "MB")
    with open(os.path.join(root, "manifest.json"), "w") as f:
        json.dump(out, f, separators=(",", ":"))


if __name__ == "__main__":
    main()
