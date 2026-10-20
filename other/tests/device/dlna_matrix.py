#!/usr/bin/env python3
"""Push two local clips to a DLNA renderer and check seek, invalid seek, next, and headers."""
import json
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

SOAP = "http://schemas.xmlsoap.org/soap/envelope/"
AVT = "urn:schemas-upnp-org:service:AVTransport:1"


ADB = None
ADB_HOST = None
ADB_PORT = None


def envelope(action, body):
    return (
        '<?xml version="1.0"?>'
        f'<s:Envelope xmlns:s="{SOAP}" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
        f"<s:Body><u:{action} xmlns:u=\"{AVT}\">{body}</u:{action}></s:Body></s:Envelope>"
    )


def soap(control, action, body):
    xml = envelope(action, body)
    if ADB:
        return soap_adb(control, action, xml)
    req = urllib.request.Request(
        control,
        data=xml.encode(),
        headers={
            "Content-Type": 'text/xml; charset="utf-8"',
            "SOAPAction": f'"{AVT}#{action}"',
        },
    )
    with urllib.request.urlopen(req, timeout=8) as resp:
        return resp.read().decode("utf-8", "replace")


def soap_adb(control, action, xml):
    path = urllib.parse.urlparse(control).path
    local = Path(".pi-apks/soap-body.xml")
    local.write_bytes(xml.encode())
    subprocess.check_call(["adb", "-s", ADB, "push", str(local), "/data/local/tmp/soap-body.xml"], stdout=subprocess.DEVNULL)
    script = f"""
len=$(toybox wc -c < /data/local/tmp/soap-body.xml)
{{
  printf 'POST {path} HTTP/1.1\\r\\n'
  printf 'Host: {ADB_HOST}:{ADB_PORT}\\r\\n'
  printf 'Content-Type: text/xml; charset=utf-8\\r\\n'
  printf 'SOAPAction: "{AVT}#{action}"\\r\\n'
  printf 'Content-Length: %s\\r\\n' "$len"
  printf 'Connection: close\\r\\n\\r\\n'
  cat /data/local/tmp/soap-body.xml
}} | toybox nc -w 5 {ADB_HOST} {ADB_PORT}
"""
    out = subprocess.check_output(["adb", "-s", ADB, "shell", script], text=True, encoding="utf-8", errors="replace")
    start = out.find("<?xml")
    if start < 0:
        raise RuntimeError(out[:400])
    text = out[start:]
    end = text.find("</s:Envelope>")
    if end >= 0:
        text = text[:end + len("</s:Envelope>")]
    return text


def tag(xml, name):
    root = ET.fromstring(xml)
    for node in root.iter():
        if node.tag.endswith(name):
            return (node.text or "").strip()
    return ""


def didl(uri, title, headers=None):
    import html
    desc = ""
    if headers:
        desc = f"<dc:description>{html.escape(json.dumps(headers))}</dc:description>"
    raw = (
        '<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
        'xmlns:dc="http://purl.org/dc/elements/1.1/" '
        'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
        '<item id="0" parentID="-1" restricted="1">'
        f"<dc:title>{html.escape(title)}</dc:title>{desc}"
        "<upnp:class>object.item.videoItem</upnp:class>"
        f'<res protocolInfo="http-get:*:video/mp4:*">{html.escape(uri)}</res>'
        "</item></DIDL-Lite>"
    )
    return html.escape(raw)


def position_ms(control):
    xml = soap(control, "GetPositionInfo", "<InstanceID>0</InstanceID>")
    text = tag(xml, "RelTime") or "00:00:00"
    parts = text.split(":")
    if len(parts) != 3:
        return -1
    return (int(parts[0]) * 3600 + int(parts[1]) * 60 + int(float(parts[2]))) * 1000


def main():
    global ADB, ADB_HOST, ADB_PORT
    args = sys.argv[1:]
    if args and args[0] == "--adb":
        ADB, ADB_HOST, ADB_PORT = args[1], args[2], args[3]
        args = args[4:]
    control = args[0]
    first = args[1]
    second = args[2]
    headers = {"X-Cast-Test": "matrix", "User-Agent": "dlna-matrix"}
    soap(control, "SetAVTransportURI",
         f"<InstanceID>0</InstanceID><CurrentURI>{first}</CurrentURI>"
         f"<CurrentURIMetaData>{didl(first, 'clip-a', headers)}</CurrentURIMetaData>")
    soap(control, "SetNextAVTransportURI",
         f"<InstanceID>0</InstanceID><NextURI>{second}</NextURI>"
         f"<NextURIMetaData>{didl(second, 'clip-b')}</NextURIMetaData>")
    soap(control, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    deadline = time.time() + 12
    pos = 0
    while time.time() < deadline:
        time.sleep(1)
        pos = position_ms(control)
        if pos >= 1000:
            break
    print("advanced", pos)
    assert pos >= 1000, pos
    soap(control, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>00:00:04</Target>")
    time.sleep(1.5)
    seeked = position_ms(control)
    print("seeked", seeked)
    assert seeked >= 3000, seeked
    soap(control, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>nope</Target>")
    time.sleep(1)
    after = position_ms(control)
    print("invalid_seek", after)
    assert after >= 3000, after
    soap(control, "Next", "<InstanceID>0</InstanceID>")
    time.sleep(2)
    info = soap(control, "GetMediaInfo", "<InstanceID>0</InstanceID>")
    uri = tag(info, "CurrentURI")
    print("next_uri", uri)
    assert second in uri, uri
    print("MATRIX_OK")


if __name__ == "__main__":
    main()
