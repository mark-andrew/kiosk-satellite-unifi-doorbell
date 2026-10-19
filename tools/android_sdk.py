"""Select an installed Android platform without assuming integer directory names."""
import re
from pathlib import Path


def android_platform(sdk_root, requested=None):
    platforms = Path(sdk_root) / 'platforms'
    if requested is not None:
        if not re.fullmatch(r'[0-9]+(?:\.[0-9]+)*', requested):
            raise ValueError('Android platform must be a numeric version such as 35 or 37.0.')
        jar = platforms / f'android-{requested}' / 'android.jar'
        if not jar.is_file():
            raise ValueError(f'Install Android platform android-{requested} before building.')
        return jar

    candidates = []
    for jar in platforms.glob('android-*/android.jar'):
        version = jar.parent.name.removeprefix('android-')
        if jar.is_file() and re.fullmatch(r'[0-9]+(?:\.[0-9]+)*', version):
            candidates.append((tuple(int(part) for part in version.split('.')), jar))
    if not candidates:
        raise ValueError('Install a numbered Android SDK platform before building.')
    return max(candidates, key=lambda candidate: candidate[0])[1]
