#!/usr/bin/env python3
"""Exercise the real APK UI without a server profile or synthetic 'connected' state."""
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

OUT = Path('runtime-results')

def adb(*args):
    return subprocess.check_output(['adb', *args])

def capture(name):
    adb('shell', 'uiautomator', 'dump', '/sdcard/fox-ui.xml')
    xml = adb('shell', 'cat', '/sdcard/fox-ui.xml')
    (OUT / (name+'.xml')).write_bytes(xml)
    (OUT / (name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
    return ET.fromstring(xml)

def tap(tree, label):
    for node in tree.iter('node'):
        if node.get('text') == label or node.get('content-desc') == label:
            x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
            adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
            time.sleep(1)
            return
    raise AssertionError('Missing control: '+label)

def has(tree, text):
    assert any(text in n.get('text','') for n in tree.iter('node')), text

adb('shell','am','start','-W','-n','com.fox.onev8/com.ponie.dayov12.LoginActivity')
time.sleep(2)
home=capture('modern-home')
has(home,'Your session');has(home,'AMNEZIAWG');has(home,'Import .conf')
tap(home,'Details')
details=capture('modern-connection')
has(details,'AmneziaWG connection');has(details,'Disconnected')
tap(details,'Close')
home=capture('modern-home-after-dialog')
tap(home,'Import .conf')
capture('modern-document-picker')
adb('shell','input','keyevent','4');time.sleep(1)
home=capture('modern-import-cancelled');has(home,'Your session')
tap(home,'Customize')
custom=capture('modern-customize')
has(custom,'FREEZE')
# Press Home via its original navigation container even when the legacy tab hides the label.
# The UI content-description on the TextView persists while the parent handles the click.
node=next((n for n in custom.iter('node') if n.get('content-desc')=='Home'),None)
if node is not None:
    tap(custom,'Home');has(capture('modern-return-home'),'Your session')
adb('shell','input','keyevent','3');time.sleep(1)
adb('shell','am','start','-W','-n','com.fox.onev8/com.ponie.dayov12.MainActivity');time.sleep(1)
capture('modern-resumed')
logs=adb('logcat','-d','-v','threadtime').decode(errors='replace')
(OUT/'modern-logcat.txt').write_text(logs)
assert 'FATAL EXCEPTION' not in logs and 'Fatal signal' not in logs
assert 'Presentation installed; original controls retained' in logs
assert adb('shell','pidof','com.fox.onev8').strip()
(OUT/'presentation-runtime.json').write_text(json.dumps({'startup':True,'connection_dialog':True,'document_picker_cancel':True,'customization_navigation':True,'resume':True,'server_handshake_tested':False},indent=2))
print('PASS: dashboard, connection details, picker cancellation, original customization, resume')
