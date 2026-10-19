#!/usr/bin/env python3
"""Offline protocol/layout/settings tests and mock HTTPS API tests. Never contacts Protect."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import struct
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / 'build' / 'tests'
BUILD.mkdir(parents=True, exist_ok=True)
JAVA = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else None
def tool(name):
    return str(JAVA / name) if JAVA else name
def run(*args):
    subprocess.run(list(args), check=True, cwd=ROOT)

manifest = json.loads((ROOT / 'kiosk-satellite-plugin.json').read_text())
assert manifest['author'] == 'Mark Andrew'
assert manifest['icon'] == 'icon.png', 'Icon paths are relative to assets/'
icon = (ROOT / 'assets' / manifest['icon']).read_bytes()
assert icon[:8] == b'\x89PNG\r\n\x1a\n' and icon[12:16] == b'IHDR'
assert struct.unpack('>II', icon[16:24]) == (256, 256) and len(icon) <= 256 * 1024
keys = set()
for setting in manifest['settings']:
    assert setting['key'] not in keys
    keys.add(setting['key'])
    if setting['type'] == 'number':
        low, high, step, value = (setting[k] for k in ('min', 'max', 'step', 'default'))
        assert low <= value <= high and step > 0
        assert abs((high - low) / step - round((high - low) / step)) < 1e-6
        assert abs((value - low) / step - round((value - low) / step)) < 1e-6
    if setting['type'] == 'select':
        assert setting['default'] in setting['options']
    if setting['type'] == 'secret':
        assert setting['default'] == ''
assert next(s for s in manifest['settings'] if s['key'] == 'talkEnabled')['default'] is False
assert [command['id'] for command in manifest['commands']] == ['show'], 'Exactly one menu action; retained show ID preserves host visibility choices'
assert manifest['id'] == 'protect-camera' and manifest['name'] == 'UniFi Protect Doorbell'
assert manifest['commands'][0]['title'] == 'Open Protect doorbell'
assert manifest['minAndroidSdk'] == 26, 'AudioFocusRequest requires Android 8/API 26'
# Source-level regression guards only: Android rendering is a separate device test.
setup_source = (ROOT / 'src/net/protectsatellite/SetupView.java').read_text()
assert setup_source.index('input.setSingleLine(true)') < setup_source.index('input.setTransformationMethod('), 'Apply password masking after single-line transformation'
assert 'input.setSaveEnabled(false)' in setup_source
assert 'return styleChoice(super.getView(' in setup_source and 'return styleChoice(super.getDropDownView(' in setup_source
assert 'Save and open doorbell' in setup_source and 'dispose(); savedAction.run();' in setup_source
assert 'controller.chooseCamera(selected.id)' in setup_source and 'controller.chooseQuality(quality)' in setup_source
assert 'button("Back")' not in setup_source
viewer_source = (ROOT / 'src/net/protectsatellite/CameraView.java').read_text()
assert 'button("Close")' in viewer_source and 'button("Back")' not in viewer_source
assert 'MICROPHONE LIVE' not in viewer_source and 'private final TextView microphone' not in viewer_source
assert 'ImageView.ScaleType.CENTER_INSIDE' in viewer_source and 'setCompoundDrawables' not in viewer_source
assert 'theme.icon(name' in viewer_source and 'colorButton(talk, highlighted)' in viewer_source
assert 'status.setVisibility(GONE)' in viewer_source and 'if (!disposed && error)' in viewer_source
assert 'doorLock.setVisibility(' in viewer_source and 'lockState' in viewer_source
assert next(s for s in manifest['settings'] if s['key'] == 'controls')['default'] == 'Top'
assert next(s for s in manifest['settings'] if s['key'] == 'sizing')['default'] == 'Stretch'
assert next(s for s in manifest['settings'] if s['key'] == 'lockEntity')['type'] == 'entity'
assert next(s for s in manifest['settings'] if s['key'] == 'lockEntity')['domain'] == 'lock'
assert set(manifest['capabilities']) == {'overlay', 'host.read', 'ha'}
fields = {s['key']: s for s in manifest['settings']}
assert fields['connectionState']['default'] == 'unvalidated'
for key in ('cameraId', 'quality', 'sizing', 'viewport', 'controls', 'autoClose', 'controlSize', 'startMuted', 'muteWhileTalking', 'talkEnabled', 'talkLimit', 'lockEntity'):
    assert fields[key]['visibleWhen'] == {'key': 'connectionState', 'equals': 'validated'}
for key in ('horizontal', 'vertical'):
    assert fields[key]['visibleWhen'] == {'key': 'sizing', 'equals': 'Fill'}
assert fields['cameraAddress']['visibleWhen'] == {'key': 'connectionState', 'equals': 'internal'}
# Conditions must reference an earlier non-secret field; hidden ancestors hide descendants.
earlier = {}
for field in manifest['settings']:
    condition = field.get('visibleWhen')
    if condition:
        assert condition['key'] in earlier and earlier[condition['key']]['type'] not in ('secret', 'list')
    earlier[field['key']] = field
assert 'Talkback address' not in setup_source and 'address.setText' not in setup_source
assert 'controller.validated()' in setup_source and 'updateCrop()' in setup_source
assert next(s for s in manifest['settings'] if s['key'] == 'autoClose')['default'] == '1 minute'
assert 'Off' in next(s for s in manifest['settings'] if s['key'] == 'autoClose')['options']
for key in ('cameraId', 'quality'):
    setting = next(s for s in manifest['settings'] if s['key'] == key)
    assert setting['type'] == 'string' and setting['choices'] == 'plugin'
assert [b['id'] for b in manifest['buttons']] == ['validateConnection']
assert 'removeCallbacks(idleCheck)' in viewer_source and 'talkState.active() || talkState.stopping()' in viewer_source
assert 'IdleTimeout' in viewer_source and 'Auto-close after inactivity' in setup_source
remote_source = (ROOT / 'src/net/protectsatellite/RemoteSetupAdapter.java').read_text()
for event in ('settings.button', 'settings.choices', 'settings.confirm', 'settings.cancel'):
    assert event in remote_source
assert 'host.confirmSetup(' in remote_source and 'host.setupResult(' in remote_source and 'host.setSettingChoices(' in remote_source
assert 'StreamPlayer' not in remote_source and 'Talkback(' not in remote_source and 'haCall(' not in remote_source
plugin_source = (ROOT / 'src/net/protectsatellite/ProtectPlugin.java').read_text()
assert plugin_source.count('public void onThemeChanged(') == 2, 'Both overlays handle host theme changes'
assert all(s not in plugin_source for s in ('DiagnosticRun', 'diagnose(', 'showWindow(', 'publishStatusTile(', 'host.log(')), 'No legacy debug commands or connection-test UI'
assert 'CallbackScope' in viewer_source and 'postPlayback(token,' in viewer_source
assert 'playbackCallbacks.current(token)' in viewer_source and 'playbackCallbacks.next();' in viewer_source
assert 'view == owner[0]' in plugin_source and 'setupView == owner[0]' in plugin_source, 'Old overlay callbacks cannot affect a replacement'
runtime_sources = '\n'.join(p.read_text() for p in (ROOT / 'src').rglob('*.java'))
assert 'DiagnosticText' not in runtime_sources and 'InetAddress destination(Settings' not in runtime_sources
assert not any(s in runtime_sources for s in ('System.out.', 'System.err.', 'printStackTrace(', 'android.util.Log.'))
focus_source = (ROOT / 'src/net/protectsatellite/MusicFocus.java').read_text()
assert 'abandonAudioFocusRequest' in focus_source and 'request == owner[0]' in focus_source
assert 'setStreamVolume' not in focus_source and 'saveSettings' not in focus_source, 'Audio focus never changes saved volume'

# Test-only JSON.org dependency; not bundled into the plugin (Android supplies org.json).
url = 'https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar'
jar = BUILD / 'json-20250517.jar'
checksum = urlopen(url + '.sha1', timeout=30).read().decode().strip().split()[0]
if not jar.exists() or hashlib.sha1(jar.read_bytes()).hexdigest() != checksum:
    content = urlopen(url, timeout=30).read()
    assert hashlib.sha1(content).hexdigest() == checksum
    jar.write_bytes(content)

store = BUILD / 'test-localhost.p12'
if not store.exists():
    run(tool('keytool'), '-genkeypair', '-alias', 'test', '-keyalg', 'RSA', '-keysize', '2048',
        '-storetype', 'PKCS12', '-keystore', str(store), '-storepass', 'test-only', '-keypass', 'test-only',
        '-dname', 'CN=localhost', '-validity', '365', '-ext', 'SAN=dns:localhost,ip:127.0.0.1', '-noprompt')
video_store = BUILD / 'test-video-unifi.p12'
if not video_store.exists():
    run(tool('keytool'), '-genkeypair', '-alias', 'test', '-keyalg', 'RSA', '-keysize', '2048',
        '-storetype', 'PKCS12', '-keystore', str(video_store), '-storepass', 'test-only', '-keypass', 'test-only',
        '-dname', 'CN=unifi.local', '-validity', '365', '-ext', 'SAN=dns:unifi.local', '-noprompt')
classes = BUILD / 'classes'
classes.mkdir(exist_ok=True)
sdk_root = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'android-sdk'))))
platform = sdk_root / 'platforms/android-35/android.jar'
assert platform.exists(), 'Tests require Android platform 35 for compile-time SDK types'
# Android types only satisfy linkage: no Android view/media APIs execute in these JVM tests.
sources = [str(p) for folder in ('sdk/src', 'src', 'tests') for p in sorted((ROOT / folder).rglob('*.java'))]
run(tool('javac'), '--release', '17', '-cp', os.pathsep.join((str(jar), str(platform))), '-d', str(classes), *sources)
run(tool('java'), '-cp', os.pathsep.join((str(classes), str(jar), str(platform))), 'net.protectsatellite.CoreTest', str(store), str(video_store))
print('PASS: manifest defaults, numeric ranges/steps and secret defaults')
