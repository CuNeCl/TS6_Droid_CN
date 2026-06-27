import re
files = [
    'app/build.gradle.kts',
    'README.md',
    'app/src/main/kotlin/dev/tsdroid/bridge/TsClient.kt',
    'app/src/main/kotlin/dev/tsdroid/viewmodel/ConnectionViewModel.kt',
]
for f in files:
    with open(f, 'r', encoding='utf-8') as fh:
        c = fh.read()
    markers = len(re.findall(r'<<<<<<<|=======|>>>>>>>', c))
    print(f'{f}: {markers} conflict markers')
