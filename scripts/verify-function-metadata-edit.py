"""Real UI metadata editing regression on an unlocked authorized phone.

Uses the existing Journey and OOB_GUI_* environment. Supply a test-owned Function
and its original successful recording; never targets arbitrary user Functions.
Run --phase edit from fresh home after installing the APK, then restart/reinstall
without clearing data and run --phase restart. Public debug tools only read.
"""

import argparse
import importlib.util
import json
from pathlib import Path
import shlex
import time
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location(
    "journey", Path(__file__).with_name("verify-execution-center-device.py")
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def run(args):
    j = module.Journey(SimpleNamespace())
    original_path = j.device.output / "metadata-original.json"
    if args.phase == "edit" and not original_path.exists():
        original = j.device.tool("get_function", function_id=args.function_id)
        j.device.persist("metadata-original.json", original)
    else:
        original = json.loads(original_path.read_text())
    j.center(restarted=True)
    j.tap("运行记录")
    j.tap(args.source_run_id)
    j.tap("查看复用指令")
    expected = {
        **original,
        "name": "Settings search edited",
        "description": "Open Settings search. Manually edited.",
    }
    if args.phase == "edit":
        j.tap("编辑")

        def fields():
            return [n for n in j.nodes() if n.get("class") == "android.widget.EditText"]

        def fill(index, text):
            j.device.wait(fields, lambda nodes: len(nodes) == 2)
            time.sleep(0.5)  # Wait for dialog/keyboard bounds to settle.
            nodes = fields()
            j.tap_node(nodes[index])
            time.sleep(0.5)
            if index == 1:
                # MOVE_END means end of the current visual line on Flutter.
                # Move to the final line before clearing a multiline description.
                j.device.adb(
                    "shell", "input", "keyevent", *(["KEYCODE_DPAD_DOWN"] * 12)
                )
            # Flutter/ColorOS did not honor injected Ctrl+A; clear the observed
            # field length using real end/backspace events instead.
            j.device.adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
            j.device.adb(
                "shell",
                "input",
                "keyevent",
                *(["KEYCODE_DEL"] * (len(j.label(nodes[index])) + 1)),
            )
            if text:
                j.device.adb(
                    "shell", "input", "text", shlex.quote(text.replace(" ", "%s"))
                )

        fill(0, "")
        j.tap("保存")
        assert j.matching("请填写此项"), "Blank name accepted"
        j.tap("取消")
        assert j.device.tool("get_function", function_id=args.function_id) == original
        j.tap("编辑")
        fill(0, expected["name"])
        fill(1, expected["description"])
        # Dismiss keyboard without dismissing the form, then save via real UI.
        j.device.adb("shell", "input", "keyevent", "4")
        j.tap("保存")
        j.device.wait(lambda: not j.matching("编辑指令"), bool)
        j.device.wait(lambda: j.matching(expected["name"]), bool)
    current = j.device.tool("get_function", function_id=args.function_id)
    assert current == expected, (
        "Metadata edit changed other fields or failed to persist"
    )
    assert j.matching(expected["name"]) and j.matching(expected["description"])
    j.tap("关闭")
    j.tap("复用指令")
    j.device.wait(lambda: j.matching(expected["name"]), bool)
    assert j.matching(expected["description"]), "List description did not update"
    j.device.persist(
        "metadata-" + args.phase + ".json",
        {
            "passed": True,
            "function_id": args.function_id,
            "source_run_id": current.get("source_run_id"),
            "name": current["name"],
            "description": current["description"],
            "other_fields_unchanged": True,
            "detail_and_list_updated": True,
            "phase": args.phase,
        },
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--function-id", required=True)
    parser.add_argument("--source-run-id", required=True)
    parser.add_argument("--phase", choices=["edit", "restart"], required=True)
    args = parser.parse_args()
    run(args)
