#!/usr/bin/env python3
"""Verify Android TV/STB launcher declarations without requiring the Android SDK."""
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"
manifest = ET.parse(ROOT / "AndroidManifest.xml").getroot()

features = {
    feature.get(ANDROID + "name"): feature.get(ANDROID + "required", "true")
    for feature in manifest.findall("uses-feature")
}
assert features.get("android.software.leanback") == "false", features
assert features.get("android.hardware.touchscreen") == "false", features

app = manifest.find("application")
assert app is not None
assert app.get(ANDROID + "banner") == "@drawable/tv_banner"

activities = {
    activity.get(ANDROID + "name"): activity
    for activity in app.findall("activity")
}
for name in (".HomeActivity", ".QuranActivity", ".DzikirActivity", ".AskActivity"):
    activity = activities[name]
    assert activity.get(ANDROID + "banner") == "@drawable/tv_banner", name
    filters = activity.findall("intent-filter")
    assert any(
        "android.intent.action.MAIN" in {
            action.get(ANDROID + "name") for action in intent_filter.findall("action")
        }
        and "android.intent.category.LEANBACK_LAUNCHER" in {
            category.get(ANDROID + "name")
            for category in intent_filter.findall("category")
        }
        for intent_filter in filters
    ), name

banner = ET.parse(ROOT / "res/drawable-nodpi/tv_banner.xml").getroot()
assert banner.tag.endswith("vector")
assert banner.get(ANDROID + "viewportWidth") == "320"
assert banner.get(ANDROID + "viewportHeight") == "180"
print("Android TV/STB manifest and banner declarations are valid.")
