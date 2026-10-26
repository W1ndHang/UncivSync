"""Compile the Java server and deploy it under server/build/deploy/Server/."""
import argparse
import os
import shutil
import subprocess
import tempfile

from project_layout import BUILD, CLASSES, RUNTIME_SERVER, SERVER, game_jar, java_tool, kotlin_stdlib, version


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    core = game_jar()
    stdlib = kotlin_stdlib()
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
    with tempfile.TemporaryDirectory(prefix='server-package-', dir=BUILD) as temporary:
        from pathlib import Path
        staging = Path(temporary).resolve()
        if not staging.is_relative_to(BUILD.resolve()):
            raise ValueError('Package staging must stay inside server/build/')
        for source in CLASSES.rglob('*.class'):
            if any(token in source.name for token in ('Test', 'GenFixture', 'Repro', 'OpsCoverage')):
                continue
            target = staging / source.relative_to(CLASSES)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        license_file = SERVER / 'LICENSE'
        if not license_file.is_file():
            raise FileNotFoundError('The server license must accompany every build.')
        metadata = staging / 'META-INF'
        metadata.mkdir(exist_ok=True)
        shutil.copy2(license_file, metadata / 'LICENSE')
        manifest = BUILD / 'MANIFEST.MF'
        manifest.write_text(
            'Manifest-Version: 1.0\n'
            'Main-Class: unciv.fs.local.Main\n'
            f'Class-Path: UncivSync.jar {stdlib.name}\n'
            'Implementation-Title: UncivSync Server\n'
            'Implementation-Vendor: W1ndHang\n'
            f'Implementation-Version: {version()}\n'
            'Bundle-License: https://polyformproject.org/licenses/noncommercial/1.0.0\n\n', encoding='utf-8')
        subprocess.run([
            str(java_tool('jar')), '--create', f'--file={output}', f'--manifest={manifest}',
            '-C', str(staging), '.',
        ], check=True)

    RUNTIME_SERVER.mkdir(parents=True, exist_ok=True)
    for source in [output, core, stdlib]:
        target = RUNTIME_SERVER / source.name
        if source.resolve() != target.resolve():
            shutil.copy2(source, target)
    print(f'Deployed server build {version()} to {RUNTIME_SERVER}', flush=True)


if __name__ == '__main__':
    main()
