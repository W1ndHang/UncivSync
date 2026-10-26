"""Compile the server and bundle its game core, resources and Kotlin runtime in one JAR."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

from project_layout import BUILD, CLASSES, CLIENT, ROOT, RUNTIME_SERVER, SERVER, game_jar, java_tool, kotlin_stdlib, version


def manifest_bytes(multi_release):
    fields = [
        ('Manifest-Version', '1.0'),
        ('Main-Class', 'unciv.fs.local.Main'),
        ('Implementation-Title', 'UncivSync Server'),
        ('Implementation-Vendor', 'W1ndHang'),
        ('Implementation-Version', version()),
    ]
    if multi_release:
        fields.append(('Multi-Release', 'true'))
    return (''.join(f'{key}: {value}\r\n' for key, value in fields) + '\r\n').encode('utf-8')


def bundle(output, dependencies):
    entries = {}
    services = {}
    for dependency in dependencies:
        with zipfile.ZipFile(dependency) as archive:
            for item in archive.infolist():
                name = item.filename
                upper = name.upper()
                if (item.is_dir() or upper in {'META-INF/MANIFEST.MF', 'META-INF/INDEX.LIST'}
                        or name.rsplit('/', 1)[-1] == 'module-info.class'
                        or (upper.startswith('META-INF/') and upper.endswith(('.SF', '.RSA', '.DSA', '.EC')))):
                    continue
                data = archive.read(item)
                if name.startswith('META-INF/services/'):
                    providers = services.setdefault(name, [])
                    for line in data.decode('utf-8').splitlines():
                        provider = line.split('#', 1)[0].strip()
                        if provider and provider not in providers:
                            providers.append(provider)
                    continue
                if name in entries:
                    if name.endswith('.class') and entries[name] != data:
                        raise ValueError(f'Conflicting dependency class: {name}')
                    continue
                entries[name] = data
    for name, providers in services.items():
        entries[name] = ('\n'.join(providers) + '\n').encode('utf-8')
    for source in sorted(CLASSES.rglob('*.class')):
        if any(token in source.name for token in ('Test', 'GenFixture', 'Repro', 'OpsCoverage')):
            continue
        name = source.relative_to(CLASSES).as_posix()
        if name in entries:
            raise ValueError(f'Server class collides with dependency: {name}')
        entries[name] = source.read_bytes()
    notices = {
        'META-INF/LICENSES/UncivSync-Server.txt': SERVER / 'LICENSE',
        'META-INF/LICENSES/Unciv-client-MPL-2.0.txt': CLIENT / 'LICENSE',
        'META-INF/UncivSync-LICENSE.txt': ROOT / 'LICENSE',
        'META-INF/UncivSync-CREDITS.md': ROOT / 'CREDITS.md',
    }
    for name, source in notices.items():
        entries[name] = source.read_bytes()
    entries['META-INF/MANIFEST.MF'] = manifest_bytes(any(name.startswith('META-INF/versions/') for name in entries))
    required = ['unciv/fs/local/Main.class', 'com/unciv/logic/GameInfo.class',
                'kotlin/sequences/DistinctSequence.class', 'jsons/Civ V - Vanilla/Nations.json']
    if any(name not in entries for name in required):
        raise ValueError('Standalone server is missing required classes or ruleset resources.')
    temporary = output.with_suffix('.jar.tmp')
    with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name in ['META-INF/MANIFEST.MF', *sorted(set(entries) - {'META-INF/MANIFEST.MF'})]:
            item = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            item.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(item, entries[name])
    os.replace(temporary, output)


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    core, stdlib = game_jar(), kotlin_stdlib()
    BUILD.mkdir(parents=True, exist_ok=True)
    if not CLASSES.resolve().is_relative_to(BUILD.resolve()):
        raise ValueError('Class output must stay inside server/build/')
    if CLASSES.exists():
        shutil.rmtree(CLASSES)
    CLASSES.mkdir()
    sources = sorted((SERVER / 'src').glob('*.java'))
    subprocess.run([
        str(java_tool('javac')), '--release', '21', '-nowarn', '-encoding', 'UTF-8',
        '-cp', os.pathsep.join(map(str, [core, stdlib])), '-d', str(CLASSES),
        *map(str, sources),
    ], check=True)
    print(f'Compiled {len(sources)} Java sources.', flush=True)
    output = BUILD / 'server.jar'
    bundle(output, [core, stdlib])
    RUNTIME_SERVER.mkdir(parents=True, exist_ok=True)
    # Retain the separate libraries for existing local development tools only.
    # The public server.jar is self-contained and does not reference them.
    for source in [output, core, stdlib]:
        target = RUNTIME_SERVER / source.name
        if source.resolve() != target.resolve():
            shutil.copy2(source, target)
    print(f'Deployed standalone server {version()}: {RUNTIME_SERVER / output.name}', flush=True)


if __name__ == '__main__':
    main()
