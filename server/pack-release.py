"""Export compiled programs directly into Android/, Windows-Client/ and Server/."""
import argparse
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import tempfile
import zipfile

from project_layout import BUILD, CLIENT, DEPLOY, RELEASES, RUNTIME_SERVER, version


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def validate_public_artifact(path):
    """Reject local runtime data accidentally embedded by a client/server build."""
    private_directories = {
        'savefiles', 'saves', 'saves_backup', 'multiplayerfiles', 'multiplayergames',
        'maps', 'mods', 'fonts', 'music', 'scenarios',
    }
    private_names = {
        'gamesettings.json', 'fasteruidevsettings.json', 'modlistcache.json',
        'admin-settings.json', 'lasterror.txt', 'local.properties', '.env',
    }
    private_suffixes = ('.auth', '.log', '.jks', '.keystore', '.pem', '.key', '.p12', '.pfx',
                        '.civs.json', '_preview.json', '.players.json')
    with zipfile.ZipFile(path) as archive:
        for member in archive.infolist():
            if member.is_dir():
                continue
            name = member.filename.replace('\\', '/').lower()
            if name.startswith('assets/'):
                name = name[len('assets/'):]
            first, leaf = name.split('/')[0], name.rsplit('/', 1)[-1]
            # This public certificate template is bundled by a third-party library.
            if name == 'defaulttemplate.pfx':
                continue
            if (first in private_directories or leaf in private_names
                    or leaf.startswith('.env.') or leaf.endswith(private_suffixes)):
                raise ValueError(f'{path.name}: embedded local data detected; rebuild without runtime files.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('release_version', nargs='?', default=version())
    args = parser.parse_args()
    release_version = args.release_version
    if not re.fullmatch(r'\d+(?:\.\d+)+', release_version):
        parser.error('Version must contain only numbers separated by dots.')
    if release_version != version():
        parser.error('Version must match FsProtocol.SERVER_BUILD; update and rebuild first.')
    config = (CLIENT / 'buildSrc/src/main/kotlin/BuildConfig.kt').read_text(encoding='utf-8')
    client_version = re.search(r'releaseVersion\s*=\s*"([^"]+)"', config).group(1)
    if client_version != release_version:
        raise ValueError('Client and server source release versions differ.')

    executable = DEPLOY / f'Windows-Client/UncivSync-{release_version}-windows-x64.exe'
    x86 = DEPLOY / f'Windows-Client/UncivSync-{release_version}-windows-x86.exe'
    second = DEPLOY / 'Client2/UncivSync.exe'
    core = RUNTIME_SERVER / 'UncivSync.jar'
    server = RUNTIME_SERVER / 'server.jar'
    apks = [CLIENT / 'android/build/outputs/apk/release/UncivSync-release.apk',
            BUILD / 'android/UncivSync-release.apk']
    apk = next((candidate for candidate in apks if candidate.is_file()), None)
    if apk is None:
        raise FileNotFoundError('Build :android:assembleRelease before exporting all three platforms.')

    exports = {
        Path(f'Android/UncivSync-{release_version}-android.apk'): apk,
        Path(f'Windows-Client/UncivSync-{release_version}-windows-x64.exe'): executable,
        Path(f'Windows-Client/UncivSync-{release_version}-windows-x86.exe'): x86,
        Path(f'Server/UncivSync-Server-{release_version}.jar'): server,
    }
    for source in [*exports.values(), second]:
        if not source.is_file():
            raise FileNotFoundError(source)
        validate_public_artifact(source)
    if sha256(executable) != sha256(second):
        raise ValueError('Client and Client2 differ; deploy both clients before exporting.')
    with zipfile.ZipFile(server) as archive:
        manifest = archive.read('META-INF/MANIFEST.MF').decode('utf-8')
    if 'Class-Path:' in manifest:
        raise ValueError('Server still references external libraries; rebuild the standalone JAR.')
    with zipfile.ZipFile(server) as archive:
        required = ['com/unciv/logic/GameInfo.class', 'kotlin/sequences/DistinctSequence.class',
                    'jsons/Civ V - Vanilla/Nations.json', 'META-INF/LICENSES/UncivSync-Server.txt',
                    'META-INF/LICENSES/Unciv-client-MPL-2.0.txt', 'META-INF/UncivSync-CREDITS.md']
        if any(name not in archive.namelist() for name in required):
            raise ValueError('Standalone server is missing required classes, resources or notices.')
    if f'Implementation-Version: {release_version}' not in manifest:
        raise ValueError('Rebuild the server before exporting this source version.')

    # Both wrappers include identical game code and the complete x86/x64 native sets.
    # x64 requires 64-bit Java; x86 also permits 32-bit Java and uses a smaller heap.
    with zipfile.ZipFile(core) as plain:
        def entries(archive):
            return {item.filename: (item.CRC, item.file_size) for item in archive.infolist()}
        expected = entries(plain)
        for launcher in (executable, x86):
            with zipfile.ZipFile(launcher) as wrapped:
                if entries(wrapped) != expected:
                    raise ValueError('Windows executable and game core differ; rebuild the launchers.')
                for asset in ['Skin.json', 'Atlases.json', 'default.fnt', 'default.png',
                              'jsons/Civ V - Vanilla/Nations.json', 'gdx.dll', 'gdx64.dll',
                              'windows/x86/org/lwjgl/glfw/glfw.dll',
                              'windows/x64/org/lwjgl/glfw/glfw.dll']:
                    if asset not in wrapped.namelist():
                        raise ValueError(f'Required client asset is missing: {asset}')
    # Prevent an APK from another source version being exported under this version's name.
    metadata = apk.parent / 'output-metadata.json'
    if not metadata.is_file():
        raise FileNotFoundError('Android output-metadata.json is required to verify the APK version.')
    expected_app_version = re.search(r'appVersion\s*=\s*"([^"\n]+)"', config).group(1)
    built = json.loads(metadata.read_text(encoding='utf-8'))
    if not any(item.get('versionName') == expected_app_version
               and item.get('outputFile') == apk.name for item in built.get('elements', [])):
        raise ValueError('Android APK version does not match the current source.')

    RELEASES.mkdir(parents=True, exist_ok=True)
    expected_folders = {path.parts[0] for path in exports}
    if any(not path.is_dir() or path.name not in expected_folders for path in RELEASES.iterdir()):
        raise ValueError('Move the old release layout outside releases/ before exporting direct files.')
    allowed_files = set(exports)
    previous_files = []
    previous_names = {
        'Android': r'UncivSync-\d+(?:\.\d+)+-android\.apk',
        'Windows-Client': r'UncivSync-\d+(?:\.\d+)+-windows-(?:x64|x86)\.exe',
        'Server': r'UncivSync-Server-\d+(?:\.\d+)+\.jar',
    }
    for folder in RELEASES.iterdir():
        for existing in folder.rglob('*'):
            if existing.is_symlink() or existing.is_dir():
                raise ValueError(f'{folder.name}: release folder contains extra runtime items; move them out first.')
            if existing.relative_to(RELEASES) in allowed_files:
                continue
            if not re.fullmatch(previous_names[folder.name], existing.name):
                raise ValueError(f'{folder.name}: unrecognized release file; move it out first.')
            previous_files.append(existing)

    BUILD.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='release-export-', dir=BUILD) as temporary:
        staging = Path(temporary).resolve()
        if not staging.is_relative_to(BUILD.resolve()):
            raise ValueError('Release staging must stay inside server/build/')
        hashes = {}
        for relative, source in exports.items():
            target = staging / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            actual = sha256(target)
            if actual != sha256(source):
                raise ValueError(f'Export hash mismatch: {relative}')
            hashes[relative.as_posix()] = actual
        if previous_files:
            stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
            history = BUILD / 'release-history' / stamp
            for existing in previous_files:
                target = history / existing.relative_to(RELEASES)
                if (not existing.resolve().is_relative_to(RELEASES.resolve())
                        or not target.resolve().is_relative_to(BUILD.resolve())):
                    raise ValueError('Release archive paths must remain inside releases/ and server/build/.')
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(existing), str(target))
        for relative in exports:
            target = RELEASES / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            os.replace(staging / relative, target)
            print(f'Exported: {relative.as_posix()}', flush=True)
        (BUILD / 'release-export.json').write_text(json.dumps(
            {'version': release_version, 'sha256': hashes}, indent=2), encoding='utf-8')
    print(f'Release {release_version}: 3 component folders, 4 program files; hashes verified.', flush=True)


if __name__ == '__main__':
    main()
