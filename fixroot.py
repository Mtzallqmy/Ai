#!/usr/bin/env python3
"""Add publishing { singleVariant("debug") } to all Android library modules via
root build.gradle.kts afterEvaluate, eliminating AGP variant ambiguity."""
import glob

p = '/home/ubuntu/CleanAgent/root_patch.txt'
patch = """
// Variant disambiguation for AGP 8.5.2 + Kotlin 1.9.24: publish only the debug variant
// so the compile classpath resolves to a single unambiguous set of elements.
subprojects {
    plugins.withId("com.android.library") {
        afterEvaluate {
            extensions.findByType(com.android.build.gradle.LibraryExtension::class.java)
                ?.publishing?.singleVariant("debug")
        }
    }
}
"""

root = '/home/ubuntu/CleanAgent/build.gradle.kts'
s = open(root).read()
if 'singleVariant' in s:
    print('already patched')
else:
    # append before last closing brace of root file
    idx = s.rstrip().rfind('}')
    open(root, 'w').write(s.rstrip()[:idx].rstrip() + '\n' + patch + '\n}\n')
    print('root patched')
