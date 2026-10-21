#!/usr/bin/env python3
"""Fetch pinned, checksum-verified speech AARs; weights are downloaded in the app."""
from pathlib import Path
import hashlib
import shutil
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / '.tools' / 'speech-runtime'
PACKAGES = [
    ('moonshine-voice-0.1.5.aar',
     'https://repo.maven.apache.org/maven2/ai/moonshine/moonshine-voice/0.1.5/moonshine-voice-0.1.5.aar',
     'ee2d95c21150683c743db8f3aef66281fd5408bcefc94be3ca1d2545ada1f571'),
    ('sherpa-onnx-static-1.13.8.aar',
     'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar',
     'b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471'),
]
def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()
def main():
    TARGET.mkdir(parents=True, exist_ok=True)
    for name, url, expected in PACKAGES:
        target = TARGET / name
        if target.is_file() and digest(target) == expected:
            continue
        print('Downloading speech runtime:', name, flush=True)
        partial = target.with_suffix('.partial')
        request = urllib.request.Request(url, headers={'User-Agent': 'Daybook-build/0.6'})
        with urllib.request.urlopen(request, timeout=120) as source, partial.open('wb') as out:
            shutil.copyfileobj(source, out)
        if digest(partial) != expected:
            partial.unlink()
            raise RuntimeError('Speech runtime checksum mismatch: ' + name)
        partial.replace(target)
if __name__ == '__main__':
    main()
