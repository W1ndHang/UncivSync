"""Shared paths and tool discovery for the client/server/release layout."""
from pathlib import Path
import os
import re
import shutil

SERVER = Path(__file__).resolve().parent
ROOT = SERVER.parent
CLIENT = ROOT / 'client'
BUILD = SERVER / 'build'
CLASSES = BUILD / 'classes'
DEPLOY = BUILD / 'deploy'
RUNTIME_SERVER = DEPLOY / 'Server'
RELEASES = ROOT / 'releases'
FIXTURES = SERVER / 'fixtures'


def version():
    source = (SERVER / 'src/FsProtocol.java').read_text(encoding='utf-8')
    return re.search(r'SERVER_BUILD\s*=\s*"([0-9.]+)"', source).group(1)


def java_tool(name):
    override = os.environ.get(name.upper())
    suffix = '.exe' if os.name == 'nt' else ''
    candidates = []
    if override:
        candidates.append(Path(override))
    if os.environ.get('JAVA_HOME'):
        candidates.append(Path(os.environ['JAVA_HOME']) / 'bin' / (name + suffix))
    found = shutil.which(name)
    if found:
        candidates.append(Path(found))
    for candidate in candidates:
        if candidate.is_file():
            return candidate.resolve()
    raise FileNotFoundError(f'{name} was not found; install JDK 21+ or set JAVA_HOME.')


def game_jar():
    candidates = [RUNTIME_SERVER / 'UncivSync.jar', CLIENT / 'desktop/build/libs/UncivSync.jar']
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise FileNotFoundError('Build client/ with :desktop:dist, then run server/deploy-client.py first.')


def kotlin_stdlib():
    config = (CLIENT / 'gradle/libs.versions.toml').read_text(encoding='utf-8')
    kotlin_version = re.search(r'^kotlin\s*=\s*"([^"]+)"', config, re.M).group(1)
    name = f'kotlin-stdlib-{kotlin_version}.jar'
    override = os.environ.get('KOTLIN_STDLIB')
    if override:
        candidate = Path(override).expanduser().resolve()
        if not candidate.is_file():
            raise FileNotFoundError(f'KOTLIN_STDLIB does not exist: {candidate}')
        return candidate
    deployed = RUNTIME_SERVER / name
    if deployed.is_file():
        return deployed
    gradle_home = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
    cache = gradle_home / 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib' / kotlin_version
    matches = sorted(cache.glob(f'*/{name}'))
    if matches:
        return matches[0]
    raise FileNotFoundError('Kotlin standard library not found; build the client or set KOTLIN_STDLIB.')


def launch4j():
    configured = os.environ.get('LAUNCH4JC')
    found = shutil.which('launch4jc')
    candidate = Path(configured or found or str(Path(os.environ.get('LOCALAPPDATA', '')) / 'Launch4j/launch4jc.exe'))
    if not candidate.is_file():
        raise FileNotFoundError('Launch4j not found; install Launch4j or set LAUNCH4JC.')
    return candidate.resolve()
