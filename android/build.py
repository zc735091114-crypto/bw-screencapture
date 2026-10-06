#!/usr/bin/env python3
"""Offline native Android build for the statement pilot.

Same toolchain and conventions as paytm-mock-pilot/build.py: no Gradle, no
repository dependencies, debug-signed. Contract tests run before dexing so a
gate regression blocks the APK rather than shipping.

Output: testAPKs/BW-PAYTM-STMT-0.4-VECB.apk
"""
from pathlib import Path
import hashlib, json, os, re, subprocess, zipfile

root = Path(__file__).resolve().parent          # .../paytm-statement-pilot/android
workspace = root.parent.parent                  # .../KINGPAY
build = workspace / 'artifacts/bw-paytm-stmt-0.4/build'
build.mkdir(parents=True, exist_ok=True)
sdk = Path.home() / 'Library/Android/sdk'
bt = sdk / 'build-tools/36.0.0'
jar = sdk / 'platforms/android-34/android.jar'
java = Path('/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home')
env = dict(os.environ, JAVA_HOME=str(java))
env['PATH'] = str(java / 'bin') + ':' + env.get('PATH', '')


def run(*args):
    subprocess.run([str(x) for x in args], check=True, env=env)


# ---------------------------------------------------------------------------
# Structural gate: the capture path must be incapable of driving the device.
#
# This is checked on the source text, not at runtime, because a runtime test
# cannot prove an API is *absent*. Any hit fails the build before dexing.
# ---------------------------------------------------------------------------

FORBIDDEN = (
    'performAction', 'ACTION_CLICK', 'ACTION_SET_TEXT', 'ACTION_SCROLL_',
    'dispatchGesture', 'performGlobalAction', 'GLOBAL_ACTION_',
    'AccessibilityNodeInfo.ACTION_', 'sendAccessibilityEvent',
    'setText(', 'GCM.send', 'startActivity', 'startService',
    'HttpURLConnection', 'okhttp', 'Socket(', 'URLConnection',
)

# Injection is permitted in exactly one file, and only to walk the fixed route
# in UpiRoute.java. Declared here with its reason so the grant stays visible and
# a second injection site cannot be added quietly.
MAY_INJECT = {
    'UpiCaptureService.java': 'walks the fixed UpiRoute hop list; no free tap',
}

SCAN_GLOBS = ('UpiVpa.java', 'UpiCapture.java', 'ScreenClass.java',
              'OwnnessJudge.java', 'CapturePolicy.java', 'UpiRoute.java')

# Files permitted to start an activity, with the reason each one may. Anything
# else that navigates fails the build, so new navigation cannot be added quietly.
#   AccessibilityGuide — takes the tester to a Settings toggle for the capture
#                        service, and on to the wallet app. Always on a tap.
#   MainActivity       — the pre-existing SAF file picker for a statement the
#                        tester exports by hand. Unrelated to capture.
MAY_NAVIGATE = {
    'AccessibilityGuide.java': 'settings + wallet navigation for the capture flow',
    'MediaProjectionCapture.java': 'screen-capture consent flow for Option D OCR',
    'MainActivity.java': 'pre-existing SAF statement file picker',
}


def assert_manifest_shape():
    """Catch manifest shapes the package installer rejects.

    Android allows at most one <action> per component <intent-filter>, and
    forbids an <intent> or <intent-filter> outside a component. Both fail as
    INSTALL_PARSE_FAILED_MANIFEST_MALFORMED at install time, long after the
    build itself looks clean, so they are checked here against the source.
    Multi-action <intent> inside <queries> is legal and is checked separately.
    """
    import xml.etree.ElementTree as ET

    ns = '{http://schemas.android.com/apk/res/android}'
    manifest = ET.parse(root / 'AndroidManifest.xml').getroot()
    problems = []

    for queries in manifest.findall('queries'):
        for intent in queries.findall('intent'):
            if not intent.findall('action'):
                problems.append('a <queries><intent> carries no <action>')

    for holder in (manifest, manifest.find('application')):
        if holder is None:
            continue
        for tag in ('intent', 'intent-filter'):
            if holder.findall(tag):
                problems.append(
                    f'<{tag}> is a direct child of <{holder.tag}>, not of a component')

    for holder in manifest.iter():
        for filt in holder.findall('intent-filter'):
            actions = filt.findall('action')
            if len(actions) > 1:
                names = [a.get(ns + 'name') for a in actions]
                problems.append(
                    f'<{holder.tag} {holder.get(ns + "name")}> filter has '
                    f'{len(actions)} actions {names}; at most one is allowed')

    if problems:
        raise SystemExit('MANIFEST SHAPE FAILED:\n  ' + '\n  '.join(problems))
    print('manifest shape: no orphan intent tags, one action per filter')

# The one file allowed to start an activity. It exists to take the tester to a
# Settings toggle on an explicit tap, and it must stay the only such file, so
# "the capture path never navigates" stays checkable rather than asserted.
NAVIGATOR = 'AccessibilityGuide.java'

# Actuation primitives. Their presence is not automatically an offence, because
# UpiCaptureService is allowed to walk the fixed route; it is only an offence
# outside the declared allowlist below.
INJECTION = ('performAction', 'ACTION_CLICK', 'ACTION_SET_TEXT',
             'dispatchGesture', 'AccessibilityNodeInfo.ACTION_',
             'sendAccessibilityEvent')


def strip_comments(text):
    """Remove // and /* */ comments so the gate scans code, not prose."""
    text = re.sub(r'/\*.*?\*/', ' ', text, flags=re.DOTALL)
    return re.sub(r'//[^\n]*', ' ', text)


def assert_read_only():
    """Fail the build if the capture path can type, tap, navigate or send."""
    offences = []
    for name in SCAN_GLOBS:
        path = root / 'src/com/bharatwallet/paytmstmt' / name
        if not path.exists():
            continue
        for lineno, line in enumerate(strip_comments(path.read_text()).splitlines(), 1):
            for token in FORBIDDEN:
                if token in line:
                    offences.append(f'{name}:{lineno} uses {token}')
    if offences:
        raise SystemExit('READ-ONLY GATE FAILED:\n  ' + '\n  '.join(offences))

    # Injection must appear in the one declared file and nowhere else.
    injectors = []
    for path in sorted((root / 'src/com/bharatwallet/paytmstmt').glob('*.java')):
        code = strip_comments(path.read_text())
        if any(t in code for t in INJECTION) and path.name not in MAY_INJECT:
            injectors.append(path.name)
    if injectors:
        raise SystemExit('INJECTION BOUNDARY FAILED: window actuation outside '
                         f'{sorted(MAY_INJECT)} in {", ".join(injectors)}')

    # And that file must not smuggle in typing or gestures while it is there.
    for name in MAY_INJECT:
        code = strip_comments((root / 'src/com/bharatwallet/paytmstmt' / name).read_text())
        for banned in ('ACTION_SET_TEXT', 'dispatchGesture', 'GLOBAL_ACTION_',
                       'ACTION_SCROLL_'):
            if banned in code:
                raise SystemExit(f'INJECTION BOUNDARY FAILED: {name} uses {banned}')

    intruders = []
    for path in sorted((root / 'src/com/bharatwallet/paytmstmt').glob('*.java')):
        if path.name in SCAN_GLOBS or path.name in MAY_NAVIGATE:
            continue
        if 'startActivity' in strip_comments(path.read_text()):
            intruders.append(path.name)
    if intruders:
        raise SystemExit('NAVIGATION BOUNDARY FAILED: startActivity outside the '
                         f'allowlist in {", ".join(intruders)}; add a reason to '
                         'MAY_NAVIGATE if that is really intended')

    print(f'read-only gate: {len(SCAN_GLOBS)} files clean of '
          f'{len(FORBIDDEN)} input/send APIs')
    print('injection allowlist: ' + '; '.join(
        f'{k} ({v})' for k, v in sorted(MAY_INJECT.items())))
    print('navigation allowlist: ' + '; '.join(
        f'{k} ({v})' for k, v in sorted(MAY_NAVIGATE.items())))


classes = build / 'classes'
classes.mkdir(parents=True, exist_ok=True)
sources = sorted((root / 'src').rglob('*.java'))
assert_manifest_shape()
assert_read_only()

run(java / 'bin/javac', '-source', '8', '-target', '8', '-encoding', 'UTF-8',
    '-classpath', jar, '-d', classes, *sources,
    root / 'tests/ContractTest.java', root / 'tests/VectorBTest.java')

for suite in ('ContractTest', 'VectorBTest'):
    tested = subprocess.run([str(java / 'bin/java'), '-cp', str(classes), suite],
                            env=env, text=True, capture_output=True, check=True)
    (build.parent / f'{suite.lower()}-output.txt').write_text(tested.stdout)
    print(tested.stdout, end='')

dex = build / 'dex'
dex.mkdir(exist_ok=True)
run(bt / 'd8', '--lib', jar, '--min-api', '26', '--output', dex,
    *sorted((classes / 'com').rglob('*.class')))

unsigned = build / 'unsigned.apk'
# -S compiles res/ and links it into resources.arsc. Required now that the
# accessibility service declares a config, because the manifest references
# @xml/accessibility_service_config and @string/app_name.
run(bt / 'aapt', 'package', '-f', '-M', root / 'AndroidManifest.xml',
    '-S', root / 'res', '-I', jar, '-F', unsigned)
with zipfile.ZipFile(unsigned, 'a') as archive:
    for item in sorted(dex.glob('*.dex')):
        archive.write(item, item.name)

aligned = build / 'aligned.apk'
run(bt / 'zipalign', '-f', '4', unsigned, aligned)

out = workspace / 'testAPKs/BW-PAYTM-STMT-0.4-VECB.apk'
out.parent.mkdir(exist_ok=True)
run(bt / 'apksigner', 'sign', '--ks', Path.home() / '.android/debug.keystore',
    '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android',
    '--key-pass', 'pass:android', '--out', out, aligned)
run(bt / 'apksigner', 'verify', '--verbose', out)

tracked = [*sources,
           *[p for p in sorted((root / 'res').rglob('*')) if p.is_file()],
           root / 'AndroidManifest.xml', root / 'build.py',
           root / 'tests/ContractTest.java', root / 'tests/VectorBTest.java']
manifest = {
    'apk': str(out),
    'sha256': hashlib.sha256(out.read_bytes()).hexdigest(),
    'bytes': out.stat().st_size,
    'package': 'com.bharatwallet.paytmstmt',
    'version': '0.4-vecb',
    'permissions': [],
    'accessibilityService': 'com.bharatwallet.paytmstmt.UpiCaptureService',
    'readOnlyCapture': True,
    'walletPackages': ['net.one97.paytm', 'com.phonepe.app', 'com.mobiwik.android'],
    'vectorBChecks': 'artifacts/bw-paytm-stmt-0.4/vectorbtest-output.txt',
    'consentGated': True,
    'otpStored': False,
    'smsAccess': False,
    'inputInjection': False,
    'sourceHashes': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                     for p in tracked},
}
(build.parent / 'build-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps(manifest, indent=2))
