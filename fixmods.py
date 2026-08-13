#!/usr/bin/env python3
"""Remove bad patch from root build.gradle.kts and instead add
publishing { singleVariant("debug") } to each library module's build.gradle.kts."""
import os

root = '/home/ubuntu/CleanAgent/build.gradle.kts'
s = open(root).read()
if 'singleVariant' in s:
    # remove the appended patch block
    idx = s.find('// Variant disambiguation')
    s = s[:idx].rstrip() + '\n'
    open(root, 'w').write(s)
print('root restored')

block = """
android {
    publishing {
        singleVariant("debug")
    }
}
"""

modules = ['core/model', 'core/common', 'core/database', 'core/datastore', 'core/network',
           'core/security', 'core/permissions', 'core/ui', 'core/agent', 'core/tools',
           'core/capabilities', 'core/memory', 'core/workspace',
           'feature/chat', 'feature/providers', 'feature/device', 'feature/browser',
           'feature/terminal', 'feature/sandbox', 'feature/files', 'feature/security',
           'feature/settings', 'feature/logs',
           'tool/android', 'tool/filesystem', 'tool/terminal', 'tool/http', 'tool/mcp',
           'tool/clipboard', 'tool/ssh',
           'provider/openai', 'provider/anthropic', 'provider/google',
           'provider/openrouter', 'provider/openai-compatible']

count = 0
for m in modules:
    p = f'/home/ubuntu/CleanAgent/{m}/build.gradle.kts'
    if not os.path.exists(p):
        print('MISSING', m)
        continue
    s = open(p).read()
    if 'singleVariant' in s:
        continue
    if 'android {' in s:
        # append after the last closing brace
        idx = s.rstrip().rfind('}')
        s = s.rstrip()[:idx].rstrip() + '\n' + block + '\n}\n'
    else:
        s = s.rstrip() + '\n\n' + block + '\n'
    open(p, 'w').write(s)
    count += 1
print(f'patched {count} modules')
