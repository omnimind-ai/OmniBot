#!/usr/bin/env python3
"""Verify Vibe runtime files in a standard APK or Gradle's generated asset root."""
import json
import sys
import zipfile
from pathlib import Path

artifact = Path(sys.argv[1])
repo = Path(__file__).resolve().parents[1]
source = repo / 'plugins/vibe-project/runtime-skill/vibe-project-builder'
archive = zipfile.ZipFile(artifact) if artifact.is_file() else None
try:
    def read(name):
        return archive.read('assets/' + name) if archive else (artifact / name).read_bytes()
    catalog = json.loads(read('catalog.v1.json'))
    plugin = next(p for p in catalog['plugins'] if p['id'] == 'com.omnimind.vibe-project-builder')
    expected = next(p for p in json.loads((repo / 'plugins/catalog.v1.json').read_text())['plugins']
                    if p['id'] == plugin['id'])
    assert plugin['version'] == expected['version'], 'Stale Vibe version; regenerate assets/rebuild APK'
    assert 'main' in plugin['profiles'], 'Vibe Builder is absent from the main profile'
    root = plugin['runtimeSkill']['packagedAssetPath']
    files = ['SKILL.md', 'bundle.json', 'PACKAGED_RUNTIME_SKILL',
             'references/product-writing.md', 'references/workflow-validation.md']
    for name in files:
        content = read(root + '/' + name)
        assert content, 'Missing runtime asset: ' + name
        assert content == (source / name).read_bytes(), 'Stale runtime asset: ' + name
    bundle = json.loads(read(root + '/bundle.json'))
    assert {'project_contract', 'project_check', 'project_publish'} <= {t['name'] for t in bundle['tools']}
finally:
    if archive:
        archive.close()
print(json.dumps({'passed': True, 'artifactType': 'apk' if archive else 'generated-assets',
                  'plugin': plugin['id'], 'version': plugin['version'], 'verifiedFiles': files}))
