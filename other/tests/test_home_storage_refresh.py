#!/usr/bin/env python3
"""Home and settings must reread a pinned network storage after leaving that page."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def between(text, start, end):
    return text.split(start, 1)[1].split(end, 1)[0]


def main():
    phone_setting = (ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/fragment/SettingFragment.java").read_text(encoding="utf-8")
    tv_setting = (ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingActivity.java").read_text(encoding="utf-8")
    phone_resume = between(phone_setting, "public void onResume()", "public void onDestroyView()")
    tv_resume = between(tv_setting, "protected void onResume()", "protected void initEvent()")
    assert "setStorageText()" in phone_resume
    assert "setStorageText()" in tv_resume
    phone_event = between(phone_setting, "public void onConfigEvent", "public void onResume()")
    tv_event = between(tv_setting, "public void onConfigEvent", "\n}")
    assert "setStorageText()" in phone_event
    assert "setStorageText()" in tv_event

    phone_home = (ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/fragment/VodFragment.java").read_text(encoding="utf-8")
    tv_home = (ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java").read_text(encoding="utf-8")
    assert "setFunc();" in between(phone_home, "public void onResume()", "public void onItemClick")
    assert "setFunc();" in between(tv_home, "protected void onResume()", "protected void onPause()")
    assert "HomeFuncs.same(funcItems, items)" in tv_home

    for flavor in ("mobile", "leanback"):
        edit = (ROOT / f"app/src/{flavor}/java/com/fongmi/android/tv/ui/activity/NetworkStorageEditActivity.java").read_text(encoding="utf-8")
        save = between(edit, "private void onSave", "public void onNetworkInput")
        assert "NetworkStorageStore.save(mStorage);" in save
        assert "ConfigEvent.common();" in save

    print("test_home_storage_refresh: ok")


if __name__ == "__main__":
    main()
