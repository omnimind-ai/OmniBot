#!/usr/bin/env python3
"""Export the splash SVGs' paths and timeline to Android splash vectors.

Run from any directory with Python 3 (standard library only). Keep geometry and
keyframes in docs/design/splash-logo.svg (default variant) and
docs/design/app-icons/<variant>.svg. Logo fills/strokes use the global Android
brand tokens; background cutouts keep the startup background color in each
variant.
"""

from pathlib import Path
import re
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
DESIGN = ROOT / "docs/design"
ANDROID = "http://schemas.android.com/apk/res/android"
AAPT = "http://schemas.android.com/aapt"
SVG = "{http://www.w3.org/2000/svg}"
ET.register_namespace("android", ANDROID)
ET.register_namespace("aapt", AAPT)

# (variant_id, svg_relative_path, resource_infix)
# The official default variant ("sparkle") owns the unsuffixed splash resources.
VARIANTS = [
    ("sparkle", DESIGN / "splash-logo.svg", ""),
    ("dot", DESIGN / "app-icons/dot.svg", "_dot"),
    ("cursor", DESIGN / "app-icons/cursor.svg", "_cursor"),
    ("wave", DESIGN / "app-icons/wave.svg", "_wave"),
    ("open", DESIGN / "app-icons/open.svg", "_open"),
    ("classic", DESIGN / "app-icons/classic.svg", "_classic"),
]


def attrs(**values):
    return {f"{{{ANDROID}}}{key}": str(value) for key, value in values.items()}


def write_xml(path, element, source_note="docs/design/splash-logo.svg"):
    ET.indent(element, space="    ")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<!-- Generated from {source_note}. '
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
            path_attrs = {"pathData": child.get("d")}
            fill = child.get("fill")
            if fill and fill != "none":
                path_attrs["fillColor"] = palette.get(fill, fill)
            stroke = child.get("stroke")
            if stroke and stroke != "none":
                path_attrs["strokeColor"] = palette.get(stroke, stroke)
            if child.get("stroke-width"):
                path_attrs["strokeWidth"] = child.get("stroke-width")
            if child.get("stroke-linecap"):
                path_attrs["strokeLineCap"] = child.get("stroke-linecap")
            if child.get("stroke-linejoin"):
                path_attrs["strokeLineJoin"] = child.get("stroke-linejoin")
            ET.SubElement(target, "path", attrs(**path_attrs))


def export_splash_background(infix, dark, source_note):
    layer = ET.Element("layer-list")
    bg_item = ET.SubElement(layer, "item")
    shape = ET.SubElement(bg_item, "shape", attrs(shape="rectangle"))
    ET.SubElement(shape, "solid", attrs(color="#151617" if dark else "#F4F7FB"))
    suffix = "_dark" if dark else ""
    ET.SubElement(
        layer,
        "item",
        attrs(
            width="@dimen/splash_logo_static_size",
            height="@dimen/splash_logo_static_size",
            gravity="center",
            drawable=f"@drawable/splash_logo{infix}_static{suffix}",
        ),
    )
    write_xml(
        RES / "drawable" / f"splash_background{infix}{suffix}.xml",
        layer,
        source_note=source_note,
    )


def export_variant(svg_path, infix, export_animators=False):
    source = ET.parse(svg_path).getroot()
    source_note = svg_path.relative_to(ROOT).as_posix()
    targets = {}
    for group in source.iter(f"{SVG}g"):
        if group.find(f"{SVG}animateTransform") is not None:
            group_id = group.get("id")
            if export_animators:
                targets[group_id] = export_animation(group)
            else:
                targets[group_id] = f"splash_{group_id}"

    min_x, min_y, width, height = source.get("viewBox").split()
    for suffix, dark, palette in [
        ("", False, {"#2C7FEB": "@color/omni_brand_primary_light"}),
        ("_dark", True, {
            "#2C7FEB": "@color/omni_brand_primary_dark",
            "#F4F7FB": "#151617",
        }),
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
        write_xml(
            RES / "drawable" / f"splash_logo{infix}_animated{suffix}.xml",
            drawable,
            source_note=source_note,
        )

        # The legacy PNGs use the complete 1152px canvas at xxxhdpi (288dp).
        # Undo the animated SVG's inset without changing the static layer's
        # footprint; source paths retain their original canvas coordinates.
        static_width = float(width) + 2 * float(min_x)
        static_height = float(height) + 2 * float(min_y)
        static = ET.Element("vector", attrs(
            width=f"{static_width / 4:g}dp",
            height=f"{static_height / 4:g}dp",
            viewportWidth=f"{static_width:g}",
            viewportHeight=f"{static_height:g}",
        ))
        export_group(source, static, palette)
        write_xml(
            RES / "drawable" / f"splash_logo{infix}_static{suffix}.xml",
            static,
            source_note=source_note,
        )
        export_splash_background(infix, dark, source_note)


def main():
    for index, (_variant_id, svg_path, infix) in enumerate(VARIANTS):
        export_variant(svg_path, infix, export_animators=(index == 0))


if __name__ == "__main__":
    main()
