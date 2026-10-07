#!/usr/bin/env python3
"""Exercise the real APK UI without requiring a server profile or fake connected state."""
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

OUT = Path('runtime-results')
APP_PACKAGE = 'com.fox.onev8'


def adb(*args):
    return subprocess.check_output(['adb', *args])


def node_center(node):
    bounds = node.get('bounds', '')
    values = [int(v) for v in re.findall(r'\d+', bounds)]
    if len(values) != 4:
        raise AssertionError(f'Invalid node bounds: {bounds!r}')
    x1, y1, x2, y2 = values
    return (x1 + x2) // 2, (y1 + y2) // 2


def dismiss_first_boot_overlay(tree):
    """Dismiss only known Android tutorial overlays, never arbitrary app UI."""
    for node in tree.iter('node'):
        resource_id = node.get('resource-id', '')
        text = node.get('text', '').casefold()
        package = node.get('package', '')
        if package == 'android' and (resource_id == 'android:id/ok' or text == 'got it'):
            x, y = node_center(node)
            adb('shell', 'input', 'tap', str(x), str(y))
            time.sleep(0.7)
            return True
    return False


def capture(name, require_app=True):
    """Capture UI, retrying when Android's one-time immersive tutorial owns the tree."""
    last_xml = None
    for attempt in range(5):
        adb('shell', 'uiautomator', 'dump', '/sdcard/fox-ui.xml')
        xml = adb('shell', 'cat', '/sdcard/fox-ui.xml')
        last_xml = xml
        tree = ET.fromstring(xml)

        if dismiss_first_boot_overlay(tree):
            (OUT / f'{name}-system-overlay-{attempt + 1}.xml').write_bytes(xml)
            continue

        if require_app and not any(n.get('package') == APP_PACKAGE for n in tree.iter('node')):
            (OUT / f'{name}-foreign-ui-{attempt + 1}.xml').write_bytes(xml)
            time.sleep(0.7)
            continue

        (OUT / f'{name}.xml').write_bytes(xml)
        (OUT / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p'))
        return tree

    if last_xml is not None:
        (OUT / f'{name}-last.xml').write_bytes(last_xml)
        (OUT / f'{name}-last.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    raise AssertionError(f'Unable to capture expected UI for {name}')


def tap(tree, label):
    wanted = label.casefold()
    for node in tree.iter('node'):
        if node.get('text', '').casefold() == wanted or node.get('content-desc', '').casefold() == wanted:
            x, y = node_center(node)
            adb('shell', 'input', 'tap', str(x), str(y))
            time.sleep(1)
            return
    raise AssertionError('Missing control: ' + label)


def has(tree, text):
    if not any(text in n.get('text', '') for n in tree.iter('node')):
        raise AssertionError('Missing text: ' + text)


def app_has_fatal(logs):
    java_crash = re.search(
        r'FATAL EXCEPTION[\s\S]{0,1800}?Process:\s*com\.fox\.onev8(?:,|\s)',
        logs,
        re.IGNORECASE,
    )
    native_crash = re.search(
        r'Fatal signal[\s\S]{0,1800}?com\.fox\.onev8',
        logs,
        re.IGNORECASE,
    )
    return bool(java_crash or native_crash)


# Fresh emulators may otherwise display Android's full-screen tutorial over FOX.
subprocess.run(
    ['adb', 'shell', 'settings', 'put', 'secure', 'immersive_mode_confirmations', 'confirmed'],
    stdout=subprocess.DEVNULL,
    stderr=subprocess.DEVNULL,
    check=False,
)

adb('shell', 'am', 'start', '-W', '-n', 'com.fox.onev8/com.ponie.dayov12.LoginActivity')
time.sleep(2)

home = capture('modern-home')
has(home, 'Your session')
has(home, 'AMNEZIAWG')
has(home, 'Import .conf')

tap(home, 'Details')
details = capture('modern-connection')
has(details, 'AmneziaWG connection')
has(details, 'Disconnected')
tap(details, 'Close')

home = capture('modern-home-after-dialog')
tap(home, 'Import .conf')
capture('modern-document-picker', require_app=False)
adb('shell', 'input', 'keyevent', '4')
time.sleep(1)

home = capture('modern-import-cancelled')
has(home, 'Your session')
tap(home, 'Customize')
custom = capture('modern-customize')
has(custom, 'FREEZE')
tap(custom, 'Home')
has(capture('modern-return-home'), 'Your session')

adb('shell', 'input', 'keyevent', '3')
time.sleep(1)
adb('shell', 'am', 'start', '-W', '-n', 'com.fox.onev8/com.ponie.dayov12.MainActivity')
time.sleep(1)
capture('modern-resumed')

logs = adb('logcat', '-d', '-v', 'threadtime').decode(errors='replace')
(OUT / 'modern-logcat.txt').write_text(logs)
if app_has_fatal(logs):
    raise AssertionError('FOX process reported a fatal runtime crash')
if not adb('shell', 'pidof', APP_PACKAGE).strip():
    raise AssertionError('FOX process is not alive after runtime presentation test')

activities = adb('shell', 'dumpsys', 'activity', 'activities').decode(errors='replace')
(OUT / 'modern-activities.txt').write_text(activities)
if 'com.fox.onev8/com.ponie.dayov12.MainActivity' not in activities:
    raise AssertionError('FOX MainActivity is not present after runtime presentation test')

(OUT / 'presentation-runtime.json').write_text(
    json.dumps(
        {
            'startup': True,
            'connection_dialog': True,
            'document_picker_cancel': True,
            'customization_navigation': True,
            'resume': True,
            'server_handshake_tested': False,
        },
        indent=2,
    )
)
print('PASS: dashboard, connection details, picker cancellation, customization, resume')
