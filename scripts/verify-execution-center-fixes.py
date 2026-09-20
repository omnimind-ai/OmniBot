"""Physical PJE110 UI regression for recording provenance, collisions, screenshots,
enhancement and cancellation. Requires unlocked/authorized phone and the current APK.
Use OOB_GUI_SERIAL, OOB_ALLOW_PHYSICAL_DEVICE=1, OOB_GUI_OUTPUT.
Run --phase record --fresh-home from the home chat, then --phase replay.
For restart coverage relaunch/reinstall without clearing data before
--phase enhance --fresh-home. Run --phase stop from an idle test chat.
Use --phase verify-enhancement to recheck an already open native tool-result
sheet. Enhancement may legitimately keep existing semantic metadata unchanged.
Native debug tools only observe or read; all writes/executions start in actual UI.
Only test-owned read-only Settings navigation is performed. No fake completion.
"""

import argparse
import importlib.util
from pathlib import Path
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location(
    "journey", Path(__file__).with_name("verify-execution-center-device.py")
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def record(j, args):
    from pathlib import Path
    import time, json

    j.center(restarted=args.fresh_home)
    before = j.device.tool("list_functions", include_hidden=True)["functions"]
    j.device.persist("before-functions.json", before)
    for i in range(2):
        j.device.adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
        time.sleep(0.5)
        cancel = [
            n
            for n in j.nodes()
            if n.get("package") == "com.android.settings" and j.label(n) == "取消"
        ]
        if cancel:
            j.tap_node(cancel[0])
            time.sleep(0.5)
        j.center()
        j.tap("手动录制")
        status = j.device.wait(
            j.device.recording_status, lambda s: s.get("recording_active")
        )
        run_id = status["run_id"]
        j.tap("开始")
        j.device.adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
        time.sleep(0.5)
        j.tap("搜索设置项")
        time.sleep(0.5)
        started = time.monotonic()
        j.tap("完成")
        j.device.wait(
            j.device.recording_status, lambda s: not s.get("recording_active")
        )
        functions = j.device.tool("list_functions", include_hidden=True)["functions"]
        matched = [f for f in functions if f.get("source_run_id") == run_id]
        assert len(matched) == 1, {
            "run_id": run_id,
            "ids": [(f["function_id"], f.get("source_run_id")) for f in functions],
        }
        original = {f["function_id"]: f for f in before}
        assert all(
            next(f for f in functions if f["function_id"] == key) == value
            for key, value in original.items()
        ), "Existing function overwritten"
        j.device.persist(
            "record-" + str(i + 1) + ".json",
            {
                "run_id": run_id,
                "function": matched[0],
                "save_seconds": round(time.monotonic() - started, 2),
                "previous_functions_preserved": True,
            },
        )
        print(
            {
                "index": i + 1,
                "run_id": run_id,
                "function_id": matched[0]["function_id"],
                "save_seconds": round(time.monotonic() - started, 2),
            },
            flush=True,
        )
        before = functions
        j.launch()
        time.sleep(0.5)
        # Completion may show the registered Function sheet.
        close = j.matching("关闭")
        if close and j.matching("复用指令详情"):
            j.tap("关闭")


def replay(j, args):
    from pathlib import Path
    import json, time

    r = json.load(open(j.device.output / "record-2.json"))
    j.settings_home()
    j.center()
    j.tap("运行记录")
    j.tap(r["run_id"])
    j.tap("查看复用指令")
    base = j.device.tool("list_run_logs", limit=1)["runs"][0]["run_id"]
    t = time.monotonic()
    j.tap("执行")
    run = j.device.wait(
        lambda: j.device.tool("list_run_logs", limit=1)["runs"][0],
        lambda r: r["run_id"] != base
        and r["status"] in ("succeeded", "failed", "cancelled"),
        seconds=90,
    )
    assert run["status"] == "succeeded"
    j.device.wait(lambda: j.matching("搜索历史"), bool)
    full = j.device.run_log(run["run_id"])
    states = []
    for s in full["steps"]:
        sid = s["before_state_id"]
        state = j.device.tool("get_run_log_state", state_id=sid)
        path = state.get("screenshot_path")
        assert path, {"state_id": sid, "error": "missing screenshot"}
        assert (
            j.device.adb("shell", "run-as", j.device.package, "test", "-s", path) == b""
        )
        states.append({"state_id": sid, "screenshot_file_nonempty": True})
    j.device.persist(
        "replay-screenshots.json",
        {
            "run_id": run["run_id"],
            "status": run["status"],
            "seconds": round(time.monotonic() - t, 2),
            "function_id": r["function"]["function_id"],
            "states": states,
        },
    )
    print({"run_id": run["run_id"], "state_count": len(states)}, flush=True)
    j.center()
    j.tap("运行记录")
    j.tap(run["run_id"])
    j.tap("第 1 步")
    j.tap("动作截图")
    time.sleep(0.5)
    assert not j.matching("状态截图不可用")
    j.device.persist(
        "screenshot-ui.json",
        {
            "passed": True,
            "state_id": states[0]["state_id"],
            "missing_screenshot_label": False,
        },
    )


def enhance(j, args):
    from pathlib import Path
    import json, time

    r = json.load(open(j.device.output / "record-2.json"))
    j.center(restarted=args.fresh_home)
    f = j.device.tool("get_function", function_id=r["function"]["function_id"])
    assert f["source_run_id"] == r["run_id"]
    assert f["steps"] == r["function"]["steps"]
    j.device.persist(
        "restart-provenance.json",
        {
            "passed": True,
            "function_id": f["function_id"],
            "source_run_id": f["source_run_id"],
        },
    )
    j.tap("运行记录")
    j.tap(r["run_id"])
    j.tap("查看复用指令")
    j.tap("增强")
    time.sleep(1)
    labels = [
        j.label(n)
        for n in j.nodes()
        if n.get("package") == j.device.package and j.label(n)
    ]
    assert not any("Missing local source_run_id" in s for s in labels)
    j.device.persist(
        "enhance-start.json",
        {
            "function_id": f["function_id"],
            "source_run_id": f["source_run_id"],
            "ui_opened": any("save_function" in s for s in labels),
        },
    )
    print("Enhancement sent through actual UI", flush=True)

    j.device.wait(lambda: j.matching("发送"), bool, seconds=180)
    # Read the real tool result, not the assistant's completion claim. A valid
    # semantic enhancement can leave an already adequate name unchanged.
    import re

    expanded = False
    for _ in range(12):
        nodes = j.nodes()
        cards = [n for n in nodes if j.label(n) == "save_function\n成功"]
        if cards:
            j.tap_node(cards[-1])
            time.sleep(0.5)
            break
        summaries = [n for n in nodes if j.label(n).startswith("已处理")]
        if summaries and not expanded:
            j.tap_node(summaries[-1])
            expanded = True
            time.sleep(0.5)
            continue
        viewports = [n for n in nodes if n.get("scrollable") == "true"]
        assert viewports, "No scrollable tool history"
        bounds = list(map(int, re.findall(r"\d+", viewports[-1].get("bounds", ""))))
        left, top, right, bottom = bounds
        x = str((left + right) // 2)
        j.device.adb(
            "shell", "input", "swipe", x, str(top + 100), x, str(bottom - 100), "400"
        )
        time.sleep(0.4)
    else:
        raise AssertionError(
            "No successful save_function card; model text is not proof"
        )
    verify_enhancement(j, args)


def verify_enhancement(j, args):
    """Read an already open save_function result sheet and durable Store state."""
    import json

    r = json.loads((j.device.output / "record-2.json").read_text())
    f = r["function"]
    labels = [j.label(n) for n in j.nodes()]
    assert "save_function" in labels and "成功" in labels
    detail = next(s for s in labels if "previewJson.function.source_run_id:" in s)
    required = [
        "previewJson.registered: true",
        "previewJson.function_id: " + f["function_id"],
        "previewJson.function.source_run_id: " + r["run_id"],
    ]
    assert all(line in detail.splitlines() for line in required), "Wrong tool result"
    updated = j.device.tool("get_function", function_id=f["function_id"])
    assert updated["source_run_id"] == r["run_id"]
    assert updated["steps"] == f["steps"]
    j.device.persist(
        "enhance-completed.json",
        {
            "passed": True,
            "function_id": updated["function_id"],
            "source_run_id": updated["source_run_id"],
            "name": updated["name"],
            "steps_unchanged": True,
            "tool_result_proof": required,
            "metadata_changed": (updated["name"], updated["description"])
            != (f["name"], f["description"]),
        },
    )


def stop_after_action(j, args):
    from pathlib import Path
    import json, time, shlex

    base = j.device.tool("list_run_logs", limit=1)["runs"][0]["run_id"]
    prompt = "Use run_gui to open Android Settings, read the main page, scroll down once and scroll back to the top. Do not change any setting. This is a cancellation test; report honestly if stopped."
    inputs = [n for n in j.nodes() if n.get("class") == "android.widget.EditText"]
    assert len(inputs) == 1
    j.tap_node(inputs[0])
    j.device.adb("shell", "input", "text", shlex.quote(prompt.replace(" ", "%s")))
    j.tap("发送")
    control = j.device.wait(
        lambda: [
            n
            for n in j.nodes()
            if j.label(n) == "停止" and n.get("class") == "android.widget.TextView"
        ],
        bool,
        seconds=70,
    )
    deadline = time.monotonic() + 80
    while time.monotonic() < deadline:
        names = (
            j.device.adb(
                "shell", "run-as", j.device.package, "ls", "-t", "files/run_logs"
            )
            .decode()
            .splitlines()
        )
        candidates = [n for n in names if n.endswith(".json") and "gui-" in n]
        active = (
            j.device.read("files/run_logs/" + candidates[0]) if candidates else None
        )
        if active and active["run_id"] != base and active.get("steps"):
            controls = [
                n
                for n in j.nodes()
                if j.label(n) == "停止" and n.get("class") == "android.widget.TextView"
            ]
            assert controls, "Run completed before stop; not a mid-run cancellation"
            j.tap_node(controls[0])
            break
        time.sleep(0.3)
    else:
        raise AssertionError("No actual action before cancellation deadline")
    r = j.device.wait(
        lambda: j.device.tool("list_run_logs", limit=1)["runs"][0],
        lambda r: r["run_id"] != base and r["status"] != "running",
        seconds=90,
    )
    full = j.device.tool("get_run_log", run_id=r["run_id"])
    assert r["status"] == "cancelled", r["status"]
    assert full["status"] == "cancelled", full["status"]
    j.launch()
    j.device.wait(lambda: [n for n in j.nodes() if "已取消" in j.label(n)], bool)
    time.sleep(3)
    late = j.device.run_log(r["run_id"])
    assert late["status"] == "cancelled"
    j.device.persist(
        "stop-after-action.json",
        {
            "passed": True,
            "run_id": r["run_id"],
            "list_status": r["status"],
            "detail_status": full["status"],
            "ui_cancelled": True,
            "late_status": late["status"],
            "step_count": len(late["steps"]),
        },
    )
    print("Native stop and both RunLog surfaces cancelled", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--phase",
        choices=["record", "replay", "enhance", "verify-enhancement", "stop"],
        required=True,
    )
    parser.add_argument("--fresh-home", action="store_true")
    args = parser.parse_args()
    j = module.Journey(SimpleNamespace())
    try:
        {
            "record": record,
            "replay": replay,
            "enhance": enhance,
            "verify-enhancement": verify_enhancement,
            "stop": stop_after_action,
        }[args.phase](j, args)
    except Exception as error:
        j.device.persist(
            args.phase + "-failure.json", {"passed": False, "error": str(error)}
        )
        raise
