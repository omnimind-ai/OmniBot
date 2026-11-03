#!/usr/bin/env python3
"""Freeze paired sources, retaining unrelated working-tree edits in both variants."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
from urllib.parse import urljoin

ROOT = Path(__file__).resolve().parents[3]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--baseline', default='HEAD')
    parser.add_argument('--common-snapshot', type=Path, help='Reuse an earlier frozen variant for unrelated sources')
    parser.add_argument('--change', action='append', required=True, help='Production file changed only by this optimization')
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        parser.error('Use a new snapshot directory')
    revision = subprocess.check_output(['git', 'rev-parse', args.baseline], cwd=ROOT, text=True).strip()
    config_path = ROOT / 'ui/.dart_tool/package_config.json'
    package_config = json.loads(config_path.read_text())
    manifest = {'baseline_commit': revision, 'changed_files': args.change, 'sources': {}}
    for variant in ('after', 'before'):
        root = output / variant
        root.mkdir(parents=True)
        if variant == 'after':
            for relative in ('ui/lib', 'app/src/main/java', 'baselib/src/main/java'):
                source = args.common_snapshot.resolve() if args.common_snapshot else ROOT
                shutil.copytree(source / relative, root / relative)
            if args.common_snapshot:
                for relative in args.change:
                    shutil.copyfile(ROOT / relative, root / relative)
        else:
            for relative in ('ui/lib', 'app/src/main/java', 'baselib/src/main/java'):
                shutil.copytree(output / 'after' / relative, root / relative)
            for relative in args.change:
                target = root / relative
                if not target.is_file():
                    raise ValueError(f'Expected an existing frozen production source: {relative}')
                target.write_bytes(subprocess.check_output(['git', 'show', f'{revision}:{relative}'], cwd=ROOT))
        ui = root / 'ui'
        for name in ('pubspec.yaml', 'pubspec.lock', 'l10n.yaml', 'analysis_options.yaml', '.flutter-plugins-dependencies'):
            if (ROOT / 'ui' / name).exists():
                shutil.copyfile(ROOT / 'ui' / name, ui / name)
        for name in ('assets', 'shaders'):
            (ui / name).symlink_to(ROOT / 'ui' / name, target_is_directory=True)
        config = json.loads(json.dumps(package_config))
        for package in config['packages']:
            package['rootUri'] = ui.as_uri() + '/' if package['name'] == 'ui' else urljoin(config_path.as_uri(), package['rootUri'])
        (ui / '.dart_tool').mkdir()
        (ui / '.dart_tool/package_config.json').write_text(json.dumps(config, indent=2))
        graph = ROOT / 'ui/.dart_tool/package_graph.json'
        if graph.exists():
            shutil.copyfile(graph, ui / '.dart_tool/package_graph.json')
        (root / '.startup-snapshot').write_text(revision + '\n')
        manifest['sources'][variant] = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
            for prefix in ('ui/lib', 'app/src/main/java', 'baselib/src/main/java')
            for p in sorted((root / prefix).rglob('*')) if p.is_file()}
    before, after = manifest['sources']['before'], manifest['sources']['after']
    changed = sorted(p for p in set(before) | set(after) if before.get(p) != after.get(p))
    if changed != sorted(args.change):
        raise ValueError(f'Pair differs outside requested files or includes unchanged files: {changed}')
    (output / 'sources.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'Frozen {len(before)} source files per variant; exactly {len(changed)} files differ')


if __name__ == '__main__':
    main()
