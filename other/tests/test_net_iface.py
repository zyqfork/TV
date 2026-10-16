#!/usr/bin/env python3
"""The published LAN address must come from a reachable interface, and be user-selectable.

A host with Docker/VM/tunnel adapters has several up interfaces with site-local addresses, so
"first address found" advertises an address no LAN peer can reach: the server QR code, /device
discovery, device scan and cast callbacks all fail while every log looks healthy.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NET = (ROOT / 'catvod/src/main/java/com/github/catvod/utils/NetIface.java').read_text(encoding='utf-8')
UTIL = (ROOT / 'catvod/src/main/java/com/github/catvod/utils/Util.java').read_text(encoding='utf-8')
DLNA = (ROOT / 'app/src/main/java/com/fongmi/android/tv/dlna/DlnaNetwork.java').read_text(encoding='utf-8')
DIALOG = (ROOT / 'app/src/main/java/com/fongmi/android/tv/ui/dialog/NetIfaceDialog.java').read_text(encoding='utf-8')

# Adapters a LAN peer cannot reach must be excluded before an address is published.
for blocked in ('docker', 'veth', 'tun', 'tap', 'rmnet', 'tailscale', 'wg', 'vpn', 'vmnet', 'br-'):
    assert f'"{blocked}"' in NET, blocked
assert 'isLanReachable' in NET and 'KEY = "net_iface"' in NET
# A user choice must win, and only be honoured while that interface still exists.
assert NET.index('String chosen = get();') < NET.index('String active = getActiveName();')
assert 'if (item.name().equals(chosen)) return chosen;' in NET
# getIp must consult the choice first, then only LAN-reachable interfaces.
# getIp must defer to the shared resolver instead of guessing by name prefix.
assert 'String resolved = NetIface.resolve();' in UTIL
assert UTIL.index('NetIface.resolve()') < UTIL.index('getWifiAddress()')
assert 'getHostAddress("wlan", true)' not in UTIL
assert 'lanOnly && !NetIface.isLanReachable(nif.getName())' in UTIL
# A name-prefix guess cannot work here: the Docker bridge on the test host is literally eth0.
assert 'startsWith("eth")' not in UTIL
# DLNA keeps its own stricter rules but shares the adapter list instead of duplicating it.
assert 'NetIface.isLanReachable(nif.getName())' in DLNA
assert 'name.startsWith("docker")' not in DLNA
# The picker is shared by both flavours and never dead-ends on a single-adapter device.
assert 'NetIface.put(list.get(which).name())' in DIALOG
assert 'list.size() == 1' in DIALOG

ns = '{http://schemas.android.com/apk/res/android}'
import xml.etree.ElementTree as ET
for flavor, name in (('leanback', 'activity_setting.xml'), ('mobile', 'fragment_setting.xml')):
    root = ET.parse(ROOT / f'app/src/{flavor}/res/layout/{name}').getroot()
    ids = {e.get(ns + 'id', '').replace('@+id/', '') for e in root.iter()}
    assert {'netIface', 'netIfaceText'} <= ids, (flavor, sorted(ids))
    texts = {e.get(ns + 'text', '') for e in root.iter()}
    assert '@string/setting_net_iface' in texts, flavor
for flavor, name, kind in (('leanback', 'SettingActivity', 'activity'),
                           ('mobile', 'SettingFragment', 'fragment')):
    page = (ROOT / f'app/src/{flavor}/java/com/fongmi/android/tv/ui/{kind}/{name}.java').read_text(encoding='utf-8')
    assert 'mBinding.netIface.setOnClickListener' in page, flavor
    assert 'NetIfaceDialog.show(' in page, flavor
    assert 'setNetIfaceText();' in page, flavor
for locale in ('values', 'values-zh-rCN', 'values-zh-rTW'):
    strings = (ROOT / f'app/src/main/res/{locale}/strings.xml').read_text(encoding='utf-8')
    assert 'name="setting_net_iface"' in strings, locale
print('PASS published LAN address uses a reachable interface and is selectable on both flavours')
