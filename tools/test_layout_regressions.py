"""Regression checks for layout profile types, remote focus and Arabic numerals.

Source-level guards complement (but do not replace) Android device UI tests.
"""
from pathlib import Path
import json

root = Path(__file__).resolve().parents[1]
profile = (root / "app/src/main/java/com/aly/sahltv/LayoutProfiles.kt").read_text()
editor = (root / "app/src/main/java/com/aly/sahltv/DashboardEditor.kt").read_text()
visual = (root / "app/src/main/java/com/aly/sahltv/HomeVisualEditor.kt").read_text()
top = (root / "app/src/main/java/com/aly/sahltv/TopBar.kt").read_text()
rows = (root / "app/src/main/java/com/aly/sahltv/HomeRows.kt").read_text()
home = (root / "app/src/main/java/com/aly/sahltv/HomeLayout.kt").read_text()
flex = (root / "app/src/main/java/com/aly/sahltv/FlexibleDashboard.kt").read_text()
notes = json.loads((root / "app/src/main/assets/whatsnew.json").read_text())
assert 'field.startsWith("dashboardSize_")' in profile.split("else if (field == \"homeTileSize\" || field == \"dashboardOrder\"")[1], "card sizes must restore as strings"
assert "restoreFocus?.requestFocus()" in editor and "focusKey = key" in editor, "dashboard redraw must restore focus"
assert "setOnFocusChangeListener" in visual, "visual editor must show remote focus"
assert "prayerTv.text = digits(" in top, "next-prayer banner must honor Arabic digit setting"
assert '"homeRecentFirst"' in home and '"homeRecentFirst"' in profile, "row ordering must survive profile backup"
assert 'if (homeRecentFirst && !homeCombined)' in rows, "Recent-first ordering must be applied to home rows"
assert 'recentFirst = !recentFirst' in visual and 'favoriteNames' in visual and 'recentNames' in visual, "preview must reflect actual app names and row order"
assert 'layoutProfileName_$name' in profile and 'EditText(this)' in profile, "custom profiles must allow names"
assert 'applyFlexibleDashboardCards()' in (root / "app/src/main/java/com/aly/sahltv/DashboardLayout.kt").read_text(), "flexible panel must be opt-in"
assert 'flexibleDashboardRows(order, widths)' in flex and 'panel.removeAllViews()' in flex, "preview and live layout must share layout planner"
assert 'field.startsWith("dashboardWidth_")' in profile.split("else if (field == \\"homeTileSize\\"")[1], "dashboard card width must restore as string"
assert '"homeRecentLimit"' in profile and '"homeTileGap"' in profile, "row options must survive profile restore"
assert 'homeRecentLimit' in rows and 'homeTileGapDp' in rows, "render must honor row settings"
assert '"dashboardWidth_$it"' in profile and '"dashboardFlexible"' in profile, "profiles must preserve flexible layout settings"
assert 'remove("dashboardFlexible")' in home and 'remove("dashboardWidth_$key")' in home, "layout reset must clear flexible choices"
assert len(notes["en"]) == len(notes["ar"]) and len(notes["en"]) >= 4
print("PASS layout source regression guards and bilingual release notes")
