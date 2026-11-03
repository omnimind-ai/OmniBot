#!/usr/bin/env python3
"""Summarize raw warm samples; frames use a fixed 800 ms post-pointer window."""
import argparse
import json
import math
from pathlib import Path
import statistics


def stats(values):
    ordered = sorted(values)
    return {"n": len(values), "median": statistics.median(ordered),
            "p95": ordered[max(0, math.ceil(len(ordered) * .95) - 1)],
            "min": min(ordered), "max": max(ordered)} if ordered else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", nargs="+")
    args = parser.parse_args()
    results = []
    for name in args.results:
        raw = json.loads(Path(name).read_text())
        if raw.get("package") != "cn.com.omnimind.bot.keyboardperf" or not raw.get("complete"):
            raise SystemExit(f"Incomplete/unrecognized measurement: {name}")
        installed = raw.get("installedApk")
        companion = Path(name).parent / f"{raw['label']}-installed-apk.json"
        if installed is None and companion.exists():
            installed = json.loads(companion.read_text())
        for scenario in sorted(set(s["scenario"] for s in raw["samples"])):
            samples = [s for s in raw["samples"] if s["scenario"] == scenario and not s["warmup"]]
            if not all(s["valid"] and s["focused"] and s["viewInsetsBottom"] > 0 for s in samples):
                raise SystemExit("Invalid measurement is not reportable as a timing")
            sample_frames = [[f for f in s["frames"]
                              if 0 <= f["startUs"] - s["pointerDownReceivedUs"] < 800_000]
                             for s in samples]
            frames = [f for rows in sample_frames for f in rows]
            result = {"label": raw["label"], "scenario": scenario, "samples": len(samples),
                      "mode": raw.get("mode", "profile"),
                      "installedApk": installed,
                      "loadedMessages": sorted(set(s["loadedMessages"] for s in samples)),
                      "refreshRateHz": sorted(set(s["refreshRateHz"] for s in samples)),
                      "latencyMs": {key: stats([s[key] for s in samples]) for key in
                                    ("pointerUpMs", "focusMs", "firstInsetMs", "firstFinalInsetMs", "lastInsetMs", "stableConfirmedMs")},
                      "framesWindowMs": 800,
                      "framesWindowPotentiallyTruncated": any(
                          s["firstFinalInsetMs"] >= 800 or s["lastInsetMs"] >= 800 for s in samples),
                      "pooledFrameMs": {key: stats([f[key] / 1000 for f in frames])
                                        for key in ("buildUs", "rasterUs", "totalUs")},
                      "perSampleFrameP95Ms": {
                          key: stats([stats([f[key] / 1000 for f in rows])["p95"]
                                      for rows in sample_frames if rows])
                          for key in ("buildUs", "rasterUs", "totalUs")},
                      "perSampleFrameCount": stats([len(rows) for rows in sample_frames]),
                      "perSampleFrameSumMs": {
                          key.removesuffix("Us"): stats([sum(f[key] for f in rows) / 1000
                                                        for rows in sample_frames])
                          for key in ("buildUs", "rasterUs", "totalUs")},
                      "frameSamples": [
                          {"sample": sample["sample"], "frameCount": len(rows),
                           "sumMs": {key.removesuffix("Us"): sum(f[key] for f in rows) / 1000
                                     for key in ("buildUs", "rasterUs", "totalUs")}}
                          for sample, rows in zip(samples, sample_frames)],
                      "frameOverThresholdFraction": {
                          key: {str(budget): sum(f[key] > budget * 1000 for f in frames) / len(frames)
                                if frames else None for budget in (8.333, 16.667)}
                          for key in ("buildUs", "rasterUs")}}
            result["firstFrameBuildStartMs"] = stats([
                min((f["startUs"] - s["pointerDownReceivedUs"]) / 1000
                    for f in s["frames"] if f["startUs"] >= s["pointerDownReceivedUs"])
                for s in samples if any(f["startUs"] >= s["pointerDownReceivedUs"] for f in s["frames"])
            ])
            results.append(result)
    print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
