"""Wrap the desktop JAR as a Windows executable; deploy two clients and the server game core."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

from project_layout import BUILD, CLIENT, DEPLOY, RUNTIME_SERVER, SERVER, launch4j


def main():
    argparse.ArgumentParser(description=__doc__).parse_args()
    core = CLIENT / 'desktop/build/libs/UncivSync.jar'
    if not core.is_file():
        raise FileNotFoundError('Build client/ first: gradlew.bat :desktop:dist')
    compiler = launch4j()
    BUILD.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='client-package-', dir=BUILD) as temporary:
        staging = Path(temporary).resolve()
        if not staging.is_relative_to(BUILD.resolve()):
            raise ValueError('Client staging must stay inside server/build/')
        for source, name in [(core, 'UncivSync.jar'),
                             (SERVER / 'src/launch4j-config.xml', 'launch4j-config.xml'),
                             (SERVER / 'src/Unciv.ico', 'Unciv.ico')]:
            shutil.copy2(source, staging / name)
        subprocess.run([str(compiler), 'launch4j-config.xml'], cwd=staging, check=True)
        executable = staging / 'UncivSync.exe'
        if not executable.is_file():
            raise FileNotFoundError('Launch4j did not produce UncivSync.exe')
        for name in ('Client', 'Client2'):
            target = DEPLOY / name / 'UncivSync.exe'
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(executable, target)
            print(f'Deployed {name}: {target}', flush=True)
    RUNTIME_SERVER.mkdir(parents=True, exist_ok=True)
    shutil.copy2(core, RUNTIME_SERVER / 'UncivSync.jar')
    print(f'Deployed game core: {RUNTIME_SERVER / "UncivSync.jar"}', flush=True)


if __name__ == '__main__':
    main()
