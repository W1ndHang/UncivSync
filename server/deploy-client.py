"""Build versioned Windows x64 and x86-compatible launchers with the desktop JAR embedded."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from project_layout import BUILD, CLIENT, DEPLOY, RUNTIME_SERVER, SERVER, launch4j, version


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
        shutil.copy2(core, staging / 'UncivSync.jar')
        shutil.copy2(SERVER / 'src/Unciv.ico', staging / 'Unciv.ico')
        for architecture in ('x64', 'x86'):
            tree = ET.parse(SERVER / 'src/launch4j-config.xml')
            config = tree.getroot()
            filename = f'UncivSync-{version()}-windows-{architecture}.exe'
            config.find('outfile').text = filename
            config.find('jre/path').text = '%JAVA_HOME%;%PATH%'
            config.find('jre/requires64Bit').text = 'true' if architecture == 'x64' else 'false'
            config.find('jre/initialHeapSize').text = '512' if architecture == 'x64' else '128'
            config.find('jre/maxHeapSize').text = '4096' if architecture == 'x64' else '1024'
            config.find('supportUrl').text = 'https://github.com/W1ndHang/UncivSync/issues'
            numeric = '.'.join(str(int(part)) for part in version().split('.'))
            numeric += '.0' * (4 - len(numeric.split('.')))
            for name in ('fileVersion', 'productVersion'):
                config.find(f'versionInfo/{name}').text = numeric
            for name in ('txtFileVersion', 'txtProductVersion'):
                config.find(f'versionInfo/{name}').text = version()
            config.find('versionInfo/originalFilename').text = filename
            config.find('versionInfo/internalName').text = f'UncivSync-{architecture}'
            configuration = staging / f'launch4j-{architecture}.xml'
            tree.write(configuration, encoding='utf-8', xml_declaration=True)
            subprocess.run([str(compiler), str(configuration)], cwd=staging, check=True)
            executable = staging / filename
            if not executable.is_file():
                raise FileNotFoundError(f'Launch4j did not produce {filename}')
            target = DEPLOY / 'Windows-Client' / filename
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(executable, target)
            shutil.copy2(configuration, target.with_suffix('.xml'))
            # Preserve the two-client local testing workflow using the x64 launcher.
            if architecture == 'x64':
                for name in ('Client', 'Client2'):
                    legacy = DEPLOY / name / 'UncivSync.exe'
                    legacy.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(executable, legacy)
            print(f'Deployed Windows {architecture}: {target}', flush=True)
    RUNTIME_SERVER.mkdir(parents=True, exist_ok=True)
    shutil.copy2(core, RUNTIME_SERVER / 'UncivSync.jar')


if __name__ == '__main__':
    main()
