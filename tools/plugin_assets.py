"""Collect safe package assets. Paths mirror the SDK package validator."""
import re
from pathlib import Path

MAX_PACKAGE_BYTES = 4 * 1024 * 1024
MAX_PACKAGE_FILES = 512

def asset_files(plugin: Path):
    assets = plugin / 'assets'
    if assets.is_symlink():
        raise ValueError('Asset directories cannot be symbolic links')
    if not assets.exists():
        return []
    if not assets.is_dir():
        raise ValueError('assets must be a directory')
    result = []
    for file in sorted(assets.rglob('*')):
        if file.is_symlink():
            raise ValueError('Assets cannot be symbolic links')
        if not file.is_file():
            continue
        path = file.relative_to(assets).as_posix()
        if len(path) > 240 or any(part in ('.', '..') or not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}', part) for part in path.split('/')):
            raise ValueError(f'Invalid asset path: {path}')
        result.append(('assets/' + path, file))
    if len(result) + 3 > MAX_PACKAGE_FILES:
        raise ValueError('At most 512 package files are supported')
    return result
