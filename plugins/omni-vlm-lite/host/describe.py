"""Export the package's public tool surface without starting Android or a model."""
import json
import sys
from pathlib import Path
from omniflow.bridge import PROTOCOL_VERSION, _MANAGEMENT_TOOL_NAMES, _management_tool_definition

root = Path(sys.argv[1])
tools = []
for name in sorted({*_MANAGEMENT_TOOL_NAMES, 'run_gui'}):
    definition = _management_tool_definition(name)
    definition['interactive'] = name in {'run_gui', 'run_function'}
    definition['agentVisible'] = not definition['interactive']
    tools.append(definition)
# Source-edit actions use generic host operations; all descriptions belong to this package.
for name, action, fields, required in (
    ('get_omniflow_python_override', 'source.read', {'path': 'string'}, []),
    ('apply_omniflow_python_override', 'source.apply', {'path': 'string', 'content': 'string'}, ['path', 'content']),
    ('clear_omniflow_python_override', 'source.clear', {'confirm': 'boolean'}, ['confirm']),
    ('reload_omniflow_python_override', 'source.reload', {}, []),
):
    tools.append(dict(name=name, description=f'Developer operation {action} for this package. Python paths are relative to its declared source root.',
                      inputSchema=dict(type='object', properties={k: dict(type=v) for k, v in fields.items()}, required=required, additionalProperties=False),
                      interactive=False, agentVisible=True, hostAction=action))
properties = dict(line.split('=', 1) for line in (root / 'scripts/runtime/runtime.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
(root / 'host.json').write_text(json.dumps(dict(interfaceVersion=1, version=json.loads((root / 'component.json').read_text())['version'] + '-' + properties['omniflow.source.sha256'][:12], protocol=PROTOCOL_VERSION,
    entrypoint='host/start.sh', prepareEntrypoint='host/prepare.sh', sourceRoot='scripts/runtime/python', tools=tools), ensure_ascii=False, indent=2) + '\n')
