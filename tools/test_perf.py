import os
import pathlib
import subprocess
import tempfile

SCRIPT = pathlib.Path(__file__).with_name('perf.sh').resolve()
MOCK = r'''#!/usr/bin/env python3
import os, sys, time
a = sys.argv[1:]
mode = os.environ['CASE']
if mode == 'timeout' and a[:4] == ['shell','am','start','-W']:
    time.sleep(20)
if a[:2] == ['shell','getprop']:
    print('0' if mode == 'boot-failure' else '1')
elif a[:1] == ['install'] and mode == 'install-failure':
    sys.exit(1)
elif a[:3] == ['shell','am','start']:
    print('TotalTime: ' + ('20000' if mode == 'slow' else '123'))
elif a[:3] == ['shell','cat','/proc/meminfo']:
    if mode == 'disconnect': sys.exit(1)
    if mode != 'missing-memory': print('MemAvailable: 200000 kB\nMemTotal: 1000000 kB')
elif a[:3] == ['shell','dumpsys','meminfo']:
    print('TOTAL PSS: ' + ('400000' if mode == 'high-memory' else '45000'))
elif a[:3] == ['shell','dumpsys','gfxinfo']:
    print('Total frames rendered: ' + ('0' if mode == 'no-frames' else '100') + '\nJanky frames: 5 (5%)')
elif a[:2] == ['shell','pidof'] and mode != 'dead':
    print('777')
elif a[:4] == ['logcat','-d','-b','crash']:
    if mode == 'log-failure': sys.exit(1)
    with open('crash-reads', 'a') as f: f.write('read\n')
    names = {
        'google': ['com.google.android.tvrecommendations'],
        'sahl': ['com.aly.sahltv'],
        'subprocess': ['com.aly.sahltv:remote'],
        'prefix': ['com.aly.sahltvother'],
        'mixed': ['com.google.android.tvrecommendations','com.aly.sahltv','com.aly.sahltv:remote'],
    }.get(mode, [])
    for i,p in enumerate(names):
        print(f'E/AndroidRuntime( {100+i}): FATAL EXCEPTION: main')
        print(f'E/AndroidRuntime( {100+i}): Process: {p}, PID: {100+i}')
        print(f'E/AndroidRuntime( {100+i}): java.lang.NullPointerException: fixture')
        print(f'E/AndroidRuntime( {100+i}):     at example.fixture(Source.kt:1)')
elif a[:3] == ['logcat','-d','-s']:
    print('SahlPerf: ready=90')
'''

for case, expected, count in [
    ('empty',0,0), ('google',0,0), ('sahl',1,1), ('subprocess',1,1),
    ('prefix',0,0), ('mixed',1,2), ('dead',1,0), ('install-failure',1,None),
    ('boot-failure',1,None), ('log-failure',1,None), ('timeout',124,None),
    ('disconnect',1,None), ('missing-memory',1,None),
    ('slow',1,0), ('high-memory',1,0), ('no-frames',1,0),
]:
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)
        for name, source in {
            'adb': MOCK,
            'sleep': '#!/bin/sh\nexit 0\n',
            # Exercise a real GNU timeout without waiting 90 seconds.
            'timeout': '#!/bin/bash\nshift 2\nexec /usr/bin/timeout --kill-after=1s 2s "$@"\n',
        }.items():
            p=root/name; p.write_text(source); p.chmod(0o755)
        env = dict(os.environ, PATH=d+':'+os.environ['PATH'], CASE=case, BUILD='31')
        result = subprocess.run(['bash',str(SCRIPT),'apks','1024-31'],cwd=d,env=env,capture_output=True,text=True,timeout=30)
        assert result.returncode == expected, (case,result.returncode,result.stdout,result.stderr)
        report=(root/'perf-1024-31.md').read_text()
        assert (root/'logcat-1024-31.txt').is_file(), case
        if count is not None:
            alive = 'NO' if case == 'dead' else 'yes'
            assert f'| {alive} / {count} |' in report, (case,report)
            assert 'build 31' in report and '60 key presses' in report
            assert (root/'crash-reads').read_text() == 'read\n'
            assert 'com.google.android.tvrecommendations' not in report
            if case in ('google','mixed'):
                assert 'com.google.android.tvrecommendations' in (root/'logcat-1024-31.txt').read_text()
        else:
            assert 'incomplete' in report, (case,report)
        if case == 'timeout':
            assert 'boot and install' in report and 'timed out' in result.stderr
        print(f'PASS {case}')
