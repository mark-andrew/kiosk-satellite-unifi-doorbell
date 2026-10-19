"""Prepare identical package and release manifests without editing source files."""
import json
import re


def build_manifest(path, version=None):
    source = path.read_bytes()
    manifest = json.loads(source)
    chosen = manifest.get('version') if version is None else version
    if not isinstance(chosen, str) or len(chosen) > 40 or not re.fullmatch(
        r'[0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9.-]+)?', chosen
    ):
        raise ValueError('Plugin version must be major.minor.patch with an optional prerelease suffix.')
    if version is None:
        return source
    manifest['version'] = chosen
    return (json.dumps(manifest, indent=2, ensure_ascii=False) + '\n').encode('utf-8')
