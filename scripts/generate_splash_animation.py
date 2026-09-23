#!/usr/bin/env python3
"""Export the splash SVG's paths and timeline to Android animated vectors.

Run from any directory with Python 3 (standard library only). Keep geometry and
keyframes in docs/design/splash-logo.svg so its preview matches both app themes.
"""

from pathlib import Path
import re
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
ANDROID = "http://schemas.android.com/apk/res/android"
AAPT = "http://schemas.android.com/aapt"
SVG = "{http://www.w3.org/2000/svg}"
ET.register_namespace("android", ANDROID)
ET.register_namespace("aapt", AAPT)


def attrs(**values):
    return {f"{{{ANDROID}}}{key}": str(value) for key, value in values.items()}


def write_xml(path, element):
    ET.indent(element, space="    ")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- Generated from docs/design/splash-logo.svg. '
        'Run scripts/generate_splash_animation.py to update. -->\n'
        + ET.tostring(element, encoding="unicode") + "\n"
    )


def export_animation(group):
    timeline = ET.Element("set", attrs(ordering="together"))
    for animation in group.findall(f"{SVG}animateTransform"):
        kind = animation.get("type")
        properties = {"rotate": ["rotation"], "scale": ["scaleX", "scaleY"]}[kind]
        values = [value.split() for value in animation.get("values").split(";")]
        times = animation.get("keyTimes").split(";")
        # Android's fast_out_slow_in matches the SVG's per-segment cubic curve.
        assert animation.get("keySplines").split(";") == ["0.4 0 0.2 1"] * (len(times) - 1)
        assert len(values) == len(times)
        for axis, property_name in enumerate(properties):
            samples = [value[axis] for value in values]
            if len(set(samples)) == 1:
                continue
            animator = ET.SubElement(timeline, "objectAnimator", attrs(
                duration=animation.get("dur").removesuffix("ms"),
                interpolator="@android:interpolator/linear",
            ))
            holder = ET.SubElement(animator, "propertyValuesHolder", attrs(
                propertyName=property_name, valueType="floatType",
            ))
            for time, value in zip(times, samples):
                ET.SubElement(holder, "keyframe", attrs(
                    fraction=time, value=value,
                    interpolator="@android:interpolator/fast_out_slow_in",
                ))
    name = f"splash_{group.get('id')}"
    write_xml(RES / "animator" / f"{name}.xml", timeline)
    return name


def export_group(source, target, palette):
    for child in source:
        if child.tag == f"{SVG}g":
            properties = {}
            if child.get("id"):
                properties["name"] = child.get("id")
            transform = child.get("transform")
            if transform:
                match = re.fullmatch(r"translate\(([-\d.]+) ([-\d.]+)\)", transform)
                if not match:
                    raise ValueError(f"Unsupported transform: {transform}")
                properties.update(translateX=match[1], translateY=match[2])
            group = ET.SubElement(target, "group", attrs(**properties))
            export_group(child, group, palette)
        elif child.tag == f"{SVG}path":
            ET.SubElement(target, "path", attrs(
                fillColor=palette.get(child.get("fill"), child.get("fill")),
                pathData=child.get("d"),
            ))


def main():
    source = ET.parse(ROOT / "docs/design/splash-logo.svg").getroot()
    targets = {
        group.get("id"): export_animation(group)
        for group in source.iter(f"{SVG}g")
        if group.find(f"{SVG}animateTransform") is not None
    }
    min_x, min_y, width, height = source.get("viewBox").split()
    for suffix, palette in [
        ("", {}),
        ("_dark", {"#2C7FEB": "#98AD90", "#F4F7FB": "#151617"}),
    ]:
        drawable = ET.Element("animated-vector")
        inline = ET.SubElement(drawable, f"{{{AAPT}}}attr", {"name": "android:drawable"})
        vector = ET.SubElement(inline, "vector", attrs(
            width=f"{source.get('width')}dp", height=f"{source.get('height')}dp",
            viewportWidth=width, viewportHeight=height,
        ))
        # Android viewports always start at (0, 0); preserve an inset SVG viewBox.
        content = vector
        if float(min_x) != 0 or float(min_y) != 0:
            content = ET.SubElement(vector, "group", attrs(
                translateX=f"{-float(min_x):g}", translateY=f"{-float(min_y):g}",
            ))
        export_group(source, content, palette)
        for name, animation in targets.items():
            ET.SubElement(drawable, "target", attrs(name=name, animation=f"@animator/{animation}"))
        write_xml(RES / "drawable" / f"splash_logo_animated{suffix}.xml", drawable)


if __name__ == "__main__":
    main()
