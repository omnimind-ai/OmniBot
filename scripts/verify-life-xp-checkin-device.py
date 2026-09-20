#!/usr/bin/env python3
"""Real generated Life XP check-in; UI mutation, read-only SQLite verification.
Open the published app first. Arguments: emulator-N output-dir project-slug habit-name.
Uses the autonomous project's habit_id/xp_reward schema, not the older demo schema.
"""
import json, re, sqlite3, subprocess, sys, time
import xml.etree.ElementTree as E
from pathlib import Path
from agent_test_database import agent_database_snapshot

serial, output, slug, habit = sys.argv[1:]
assert re.fullmatch(r"emulator-\d+", serial)
assert re.fullmatch(r"life-xp-autonomous-[0-9]+", slug)
out = Path(output); out.mkdir(parents=True, exist_ok=True)
report = {"serial": serial, "project": slug, "habit": habit, "passed": False,
          "scope": "actual published check-in, saved local day, reward and duplicate prevention"}
def adb(*args):
    return subprocess.check_output(["adb", "-s", serial, *args], timeout=30)
def snapshot(name):
    assert b"dumped to:" in adb("shell", "uiautomator", "dump", "/data/local/tmp/life-checkin.xml")
    raw = adb("exec-out", "cat", "/data/local/tmp/life-checkin.xml")
    (out / (name + ".xml")).write_bytes(raw)
    (out / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))
    return list(E.fromstring(raw).iter("node"))
def label(n): return n.get("text") or n.get("content-desc") or ""
def records():
    with agent_database_snapshot(serial, "files/plugin-data/local.project." + slug + "/project.db") as db:
        db.row_factory = sqlite3.Row
        return {t: [dict(r) for r in db.execute("SELECT * FROM " + t)]
                for t in ("habits", "check_ins")}
def button(nodes):
    start = next(i for i, n in enumerate(nodes) if label(n) == habit)
    # Inspect only this rendered habit row, before another habit label.
    names = {r["name"] for r in before["habits"]} - {habit}
    for n in nodes[start + 1:]:
        if label(n) in names: break
        if n.get("class") == "android.widget.Button" and any(s in label(n) for s in ("完成", "打卡")):
            return n
    raise AssertionError("No visible completion button for selected habit")
def tap(n):
    x, y, xx, yy = map(int, re.findall(r"\d+", n.get("bounds", "")))
    assert xx > x >= 0 and yy > y >= 0, "Button has no visible bounds"
    adb("shell", "input", "tap", str((x + xx) // 2), str((y + yy) // 2))
try:
    report["version"] = adb("shell", "dumpsys", "package", "cn.com.omnimind.bot").decode()
    report["version"] = [x.strip() for x in report["version"].splitlines() if "versionName=" in x or "versionCode=" in x]
    before = records(); report["before"] = before
    row, = [r for r in before["habits"] if r["name"] == habit]
    day = adb("shell", "date", "+%Y-%m-%d").decode().strip()
    assert not any(r["habit_id"] == row["habit_id"] and r["check_in_date"] == day for r in before["check_ins"]), "Already completed; do not erase data"
    target = button(snapshot("before"))
    assert target.get("enabled") == "true", "Uncompleted habit is disabled: " + habit
    tap(target)
    nodes = snapshot("after")
    report["labels"] = [label(n) for n in nodes if label(n)]
    deadline = time.monotonic() + 10
    after = records()
    while len(after["check_ins"]) == len(before["check_ins"]) and time.monotonic() < deadline:
        if any("失败" in x for x in report["labels"]): break
        time.sleep(.3); after = records()
    report["after"] = after
    old = {r["check_in_id"]: r for r in before["check_ins"]}
    added = [r for r in after["check_ins"] if r["check_in_id"] not in old]
    assert len(added) == 1, "Actual UI did not save exactly one check-in"
    assert added[0]["habit_id"] == row["habit_id"] and added[0]["check_in_date"] == day
    assert all(r == old[r["check_in_id"]] for r in after["check_ins"] if r["check_in_id"] in old), "Prior records changed"
    assert after["habits"] == before["habits"], "Check-in changed habit definitions"
    rewards = {r["habit_id"]: r["xp_reward"] for r in after["habits"]}
    total = sum(rewards[r["habit_id"]] for r in after["check_ins"])
    assert str(total) in report["labels"], "Visible total does not match saved rewards"
    target = button(nodes); assert target.get("enabled") == "false", "Completed action remains enabled"
    tap(target)
    assert records() == after, "Duplicate tap changed saved records"
    report.update(passed=True, totalXP=total, localDate=day)
except Exception as error:
    report["error"] = str(error)
    raise
finally:
    (out / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({k: v for k,v in report.items() if k not in ("before", "after", "labels")}, ensure_ascii=False))
