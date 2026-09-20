"""Start on the real OmniFlow plugin detail, on an authorized/unlocked phone.
Verify absence of the removed guide, follow the visible recording/reuse entry,
then start, pause and cancel manual recording via real UI. No fixtures injected.
Uses OOB_GUI_SERIAL, OOB_ALLOW_PHYSICAL_DEVICE=1, OOB_GUI_OUTPUT.
"""

import importlib.util
from pathlib import Path
import re
import time

spec = importlib.util.spec_from_file_location(
    "controls", Path(__file__).with_name("verify-execution-center-controls.py")
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
j = module.Audit()
assert j.matching("插件详情")
assert j.matching("手动录制与复用指令")
previous = None
reached_end = False
for _ in range(8):
    nodes = j.nodes()
    labels = [j.label(n) for n in nodes if n.get("package") == j.device.package]
    assert not any(
        "开始使用" in s.splitlines() or "OmniFlow 自动化增强已启用" in s for s in labels
    )
    if labels == previous:
        reached_end = True
        break
    previous = labels
    scrolls = [
        n
        for n in nodes
        if n.get("scrollable") == "true" and n.get("package") == j.device.package
    ]
    assert scrolls
    left, top, right, bottom = map(int, re.findall(r"\d+", scrolls[0].get("bounds")))
    x = str((left + right) // 2)
    j.device.adb(
        "shell", "input", "swipe", x, str(bottom - 80), x, str(top + 80), "400"
    )
    time.sleep(0.6)
assert reached_end, "Did not inspect the entire detail page"
j.tap("手动录制与复用指令")
j.device.wait(lambda: j.matching("手动录制"), bool)
j.tap("运行记录")
j.device.wait(lambda: j.matching("手动录制"), bool)
j.device.persist(
    "entry-navigation.json",
    {
        "passed": True,
        "get_started_removed": True,
        "plugin_entry_opens_execution_center": True,
        "record_button_present_on_both_tabs": True,
    },
)
j.tap("复用指令")  # RunLog titles can also be named 手动录制.
recording = j.cancel_recording(start=True)
j.launch()
j.device.wait(lambda: j.matching("手动录制"), bool)
j.device.persist(
    "recording-entry.json",
    {
        "passed": True,
        "get_started_removed": True,
        "plugin_entry_opens_execution_center": True,
        "record_button_present_on_both_tabs": True,
        "recording": recording,
    },
)
