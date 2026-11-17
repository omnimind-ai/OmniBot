#!/usr/bin/env python3
"""Validate isolated/UI measurements and summarize equivalent synthetic runs."""
import argparse
import json
import math
from pathlib import Path
import re
import statistics
import sys

from common import PACKAGE, output_directory, write_json


class InvalidMeasurement(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise InvalidMeasurement(message)


def read_json(path):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError) as error:
        raise InvalidMeasurement(f"Cannot read complete JSON from {path.name}: {error}")


def label(value):
    require(re.fullmatch(r"[A-Za-z0-9_-]+", value) is not None, "Invalid result label")
    return value


def number(value, name):
    require(isinstance(value, (int, float)) and not isinstance(value, bool)
            and math.isfinite(value) and value >= 0, f"Invalid nonnegative metric: {name}")
    return value


def percentile(values, fraction):
    """Sorted floor((n - 1) * p), with no interpolation."""
    require(bool(values), "Cannot summarize an empty sample set")
    ordered = sorted(values)
    return ordered[math.floor((len(ordered) - 1) * fraction)]


def distribution(values):
    return {"count": len(values), "p95": percentile(values, .95),
            "p99": percentile(values, .99), "max": max(values)}


def same(values, description):
    require(bool(values), f"No samples for {description}")
    require(all(value == values[0] for value in values[1:]), f"Mismatched {description}")
    return values[0]


def digest(value):
    require(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None,
            "Missing/invalid semanticSha256")
    return value


def isolated(workspace, result_label, manifest):
    data = read_json(workspace / f"{label(result_label)}-results.json")
    require(data.get("complete") is True, f"{result_label}: isolated suite is incomplete")
    require(data.get("package") == PACKAGE, f"{result_label}: wrong package")
    require(data.get("probeSha256") == manifest.get("probeSha256"), f"{result_label}: probe SHA mismatch")
    require(data.get("baselineCommit") == manifest.get("baselineCommit"), f"{result_label}: baseline commit mismatch")
    groups = {}
    for sample in data.get("samples", []):
        require(isinstance(sample.get("warmup"), bool), "Missing explicit warmup marker")
        if sample["warmup"]:
            continue
        require(sample.get("measurement") == "isolated-production-reducer", "Wrong isolated measurement kind")
        require(sample.get("package") == PACKAGE, "Wrong isolated sample package")
        require(sample.get("stillResponding") is False, "Isolated runtime did not finish")
        require(sample.get("terminalStatus") == "success", "Isolated tool did not complete")
        scenario = sample.get("scenario")
        require(scenario in ("short", "long", "tools"), "Unknown isolated scenario")
        number(sample.get("reductionWorkUs"), "reductionWorkUs")
        number(sample.get("wallUs"), "wallUs")
        digest(sample.get("semanticSha256"))
        groups.setdefault(scenario, []).append(sample)
    require(set(groups) == {"short", "long", "tools"}, "Isolated suite requires short, long and tools")
    scenarios = {}
    for scenario, samples in sorted(groups.items()):
        require(len({row.get("sample") for row in samples}) == len(samples), f"Duplicate sample in {scenario}")
        shape = same([{key: row.get(key) for key in
                       ("events", "batchSize", "workers", "historyRows")} for row in samples],
                     f"{scenario} fixture parameters")
        scenarios[scenario] = {
            "samples": len(samples), "shape": shape,
            "semanticSha256": same([row["semanticSha256"] for row in samples], f"{scenario} semantic SHA"),
            "retainedEvents": same([row.get("retainedEvents") for row in samples], f"{scenario} retained events"),
            "reductionWorkUsMedian": statistics.median(row["reductionWorkUs"] for row in samples),
            "wallUsMedian": statistics.median(row["wallUs"] for row in samples),
        }
    return {"label": result_label, "mode": data.get("mode"),
            "installedApk": data.get("installedApk"), "scenarios": scenarios}


def validate_visible(owner, description):
    require(isinstance(owner, dict), f"{description}: missing visible owner")
    require(owner.get("chatPageVisible") is True and owner.get("routePath") == "/home/chat",
            f"{description}: ChatPage was not visible")
    require(owner.get("runtimeMode") == "agent" and owner.get("conversationMode") == "agent",
            f"{description}: wrong visible mode")
    require(owner.get("pageUsesOwningRuntime") is True and owner.get("listUsesOwningRuntime") is True,
            f"{description}: visible list did not use the owning runtime")
    require(owner.get("conversationId") == owner.get("runtimeConversationId"),
            f"{description}: conversation identity mismatch")
    require(owner.get("agentId") == owner.get("listAgentId") and bool(owner.get("agentId")),
            f"{description}: agent identity mismatch")
    count = owner.get("runtimeMessageCount")
    require(isinstance(count, int) and count > 0 and count == owner.get("pageMessageCount")
            and count == owner.get("listMessageCount"), f"{description}: visible message counts mismatch")


def stream_run(workspace, result_label, kind):
    data = read_json(workspace / f"{label(result_label)}-results.json")
    stream = data.get("stream", {})
    result = stream.get("result", {})
    capture = data.get("capture", {})
    require(result.get("valid") is True, f"{result_label}: stream is invalid")
    require(result.get("measurement") == "production-coordinator-and-real-chat-page", "Wrong UI measurement kind")
    require(result.get("kind") == kind and stream.get("kind") == kind, "Wrong stream kind")
    require(stream.get("fixtureRunning") is False and stream.get("fixtureError") is None, "Fixture did not finish cleanly")
    require(stream.get("visibleProjectionVerified") is True, "First visible projection was not verified")
    require(result.get("finalMessageFound") is True and result.get("promptResponseHandled") is True,
            "Final message/official PromptResponse was not verified")
    require(result.get("runtimeResponding") is False and stream.get("runtimeResponding") is False,
            "Owning runtime is still responding")
    require(result.get("cancelled") is False, "Cancelled streams are not completion benchmarks")
    require(result.get("fixturePageVisibleAtEnd") is True, "Foreground UI benchmark ended off-page")
    require(capture.get("package") == PACKAGE, "Wrong UI capture package")
    validate_visible(result.get("firstVisibleOwner"), "first update")
    validate_visible(result.get("finalVisibleOwner"), "final update")
    validate_visible(capture.get("visibleOwner"), "capture")
    emitted = result.get("emittedEvents")
    require(isinstance(emitted, int) and emitted > 0 and emitted == result.get("requestedEvents")
            and emitted == stream.get("emittedEvents"), "Emitted event count mismatch")
    work = result.get("updateWorkUs", [])
    require(len(work) == emitted and len(result.get("updateTimesUs", [])) == emitted,
            "One update timing is required for every emitted event")
    for value in work:
        number(value, "updateWorkUs")
    if kind == "text":
        require(result.get("textMatchesFixture") is True, "Rendered text differs from fixture")
    else:
        require(result.get("retainedEvents") == emitted + 2 * result.get("workers", 0),
                "Tool progress/lifecycle events were lost")
        require(result["finalVisibleOwner"].get("listHidesToolCards") is False,
                "Tool cards were hidden")
    start = number(result.get("startUs"), "startUs")
    end = number(result.get("projectionEndUs"), "projectionEndUs")
    require(end > start, "Stream window is empty or reversed")
    require(capture.get("captureStartUs", math.inf) <= start
            and capture.get("captureEndUs", -1) >= end, "Frame capture does not cover the stream")
    frames = [frame for frame in capture.get("frames", []) if start <= frame.get("startUs", -1) < end]
    require(bool(frames), "No Flutter frames started in the valid stream window")
    for frame in frames:
        for key in ("buildUs", "rasterUs", "totalUs"):
            number(frame.get(key), key)
    gaps = capture.get("timerGapsUs", [])
    require(bool(gaps), "No Timer observations in the whole capture")
    for gap in gaps:
        number(gap, "timerGapsUs")
    metrics = {
        "updateWorkUsSum": sum(work), "wallUs": number(result.get("wallUs"), "wallUs"),
        "projectionSpanUs": end - start, "frameCount": len(frames),
        "buildUs": distribution([frame["buildUs"] for frame in frames]),
        "rasterUs": distribution([frame["rasterUs"] for frame in frames]),
        "timerWholeCaptureUs": distribution(gaps),
    }
    return {
        "label": result_label, "semanticSha256": digest(result.get("semanticSha256")),
        "shape": {key: result.get(key) for key in
                  ("requestedEvents", "intervalMs", "payloadChars", "workers")},
        "retainedEvents": result.get("retainedEvents"), "metrics": metrics,
        "frames": frames, "timerGapsUs": gaps,
    }


def stream_group(workspace, prefix, kind, count):
    runs = [stream_run(workspace, f"{label(prefix)}_{kind}_{index}", kind)
            for index in range(1, count + 1)]
    frame_rows = [frame for run in runs for frame in run["frames"]]
    gaps = [gap for run in runs for gap in run["timerGapsUs"]]
    medians = {key: statistics.median(run["metrics"][key] for run in runs)
               for key in ("updateWorkUsSum", "wallUs", "projectionSpanUs", "frameCount")}
    for key in ("buildUs", "rasterUs", "timerWholeCaptureUs"):
        medians[key] = {quantile: statistics.median(run["metrics"][key][quantile] for run in runs)
                        for quantile in ("p95", "p99", "max")}
    return {
        "samples": count,
        "shape": same([run["shape"] for run in runs], f"{kind} stream parameters"),
        "semanticSha256": same([run["semanticSha256"] for run in runs], f"{kind} stream semantic SHA"),
        "retainedEvents": same([run["retainedEvents"] for run in runs], f"{kind} retained events"),
        "perRunMedians": medians,
        "pooledFrames": {"count": len(frame_rows),
                         "buildUs": distribution([frame["buildUs"] for frame in frame_rows]),
                         "rasterUs": distribution([frame["rasterUs"] for frame in frame_rows])},
        "pooledTimerWholeCaptureUs": distribution(gaps),
        "runs": [{key: run[key] for key in ("label", "metrics")} for run in runs],
    }


def summarize_workspace(workspace, before_label="baseline_clean", after_label="after_clean",
                        before_stream_prefix="baseline_stream", after_stream_prefix="after_stream",
                        stream_samples=3, before_only=False):
    manifest = read_json(workspace / "runtime-perf.json")
    require(manifest.get("package") == PACKAGE, "Workspace is not the independent runtime benchmark")
    before = isolated(workspace, before_label, manifest)
    after = None if before_only else isolated(workspace, after_label, manifest)
    if after is not None:
        require(before["mode"] == after["mode"], "Cannot compare Profile and Debug measurements")
        for scenario in before["scenarios"]:
            left, right = before["scenarios"][scenario], after["scenarios"][scenario]
            for key in ("shape", "semanticSha256", "retainedEvents", "samples"):
                require(left[key] == right[key], f"Before/after {scenario} {key} mismatch")
    streams = {}
    for kind in ("tools", "text"):
        left = stream_group(workspace, before_stream_prefix, kind, stream_samples)
        right = None if before_only else stream_group(workspace, after_stream_prefix, kind, stream_samples)
        if right is not None:
            for key in ("shape", "semanticSha256", "retainedEvents"):
                require(left[key] == right[key], f"Before/after stream {kind} {key} mismatch")
        streams[kind] = {"before": left, "after": right,
                         "equivalent": None if before_only else True}
    return {
        "package": PACKAGE, "comparisonComplete": not before_only,
        "baselineCommit": manifest.get("baselineCommit"), "probeSha256": manifest.get("probeSha256"),
        "isolated": {"before": before, "after": after}, "streams": streams,
        "metricNotes": [
            "Percentiles use sorted floor((n-1)*p), without interpolation.",
            "Per-run medians weight runs equally; pooled frame percentiles weight frames equally.",
            "FrameTiming is filtered by build start in [stream.startUs, stream.projectionEndUs).",
            "Timer gaps cover the complete capture, including the tail; they are not input latency or frame duration.",
            "Isolated reducer timings exclude platform channels, persistence and UI; stream timings exclude native EventChannel/network.",
            "Stream raw results rely on guarded workspace labels; they do not embed a separate installed-APK hash.",
        ],
    }


def markdown(summary):
    rows = ["| 测量（中位数，ms） | Before | After |", "| --- | ---: | ---: |"]
    before, after = summary["isolated"]["before"], summary["isolated"]["after"]
    def cell(value):
        return "—" if value is None else f"{value / 1000:.2f}"
    for scenario, left in before["scenarios"].items():
        right = None if after is None else after["scenarios"][scenario]
        rows.append(f"| reducer {scenario} 同步工作 | {cell(left['reductionWorkUsMedian'])} | {cell(None if right is None else right['reductionWorkUsMedian'])} |")
        rows.append(f"| reducer {scenario} wall | {cell(left['wallUsMedian'])} | {cell(None if right is None else right['wallUsMedian'])} |")
    for kind, group in summary["streams"].items():
        left = group["before"]["perRunMedians"]
        right = None if group["after"] is None else group["after"]["perRunMedians"]
        for name, key in (("投影同步工作合计", "updateWorkUsSum"), ("wall（含间隔/保存）", "wallUs")):
            rows.append(f"| UI {kind} {name} | {cell(left[key])} | {cell(None if right is None else right[key])} |")
        for phase in ("buildUs", "rasterUs"):
            rows.append(f"| UI {kind} 每轮 {phase[:-2]} P95 | {cell(left[phase]['p95'])} | {cell(None if right is None else right[phase]['p95'])} |")
    return "\n".join(rows) + "\n\n帧 P99/最大值、合并帧分布和完整 capture Timer 间隔见 JSON；Timer 间隔不是输入延迟。"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--before-label", default="baseline_clean")
    parser.add_argument("--after-label", default="after_clean")
    parser.add_argument("--before-stream-prefix", default="baseline_stream")
    parser.add_argument("--after-stream-prefix", default="after_stream")
    parser.add_argument("--stream-samples", type=int, default=3)
    parser.add_argument("--before-only", action="store_true")
    parser.add_argument("--summary-file", default="summary.json")
    args = parser.parse_args()
    try:
        require(1 <= args.stream_samples <= 30, "Stream samples must be 1..30")
        require(Path(args.summary_file).name == args.summary_file and args.summary_file.endswith(".json"),
                "--summary-file must be a JSON filename inside --output")
        workspace = output_directory(args.output)
        destination = workspace / args.summary_file
        require(not destination.exists(), "Summary exists; choose another --summary-file")
        summary = summarize_workspace(workspace, args.before_label, args.after_label,
                                      args.before_stream_prefix, args.after_stream_prefix,
                                      args.stream_samples, args.before_only)
        write_json(destination, summary)
        print(markdown(summary))
        print(f"\nSaved {destination}")
    except InvalidMeasurement as error:
        print(f"Measurement validation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
