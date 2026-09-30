#!/usr/bin/env python3
"""Fast, offline release checks; Gradle still performs the full Android compilation."""
from pathlib import Path
import json,re,sys
ROOT=Path(__file__).resolve().parents[1]
J=ROOT/'app/src/main/java/com/example/vibrationmonitor'
count=0

def check(condition, message):
    global count
    count+=1
    if not condition:raise RuntimeError(message)

FORMAT=re.compile(r'%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?(?:[tT][a-zA-Z]|[a-zA-Z%])')
def specifiers(s):
    return [m.group() for m in FORMAT.finditer(s) if m.group() not in ('%%','%n')]

def main():
    from check_ui_constructor_boundary import run_checks
    run_checks(check)
    catalog=json.loads((ROOT/'app/src/main/assets/unified_i18n.json').read_text(encoding='utf-8'))
    check(len(catalog)>=400,'Incomplete translation catalogue')
    for key,values in catalog.items():
        check(isinstance(values,list) and len(values)==3,'Exactly 3 languages required: '+key)
        for lang,value in zip(('ko','en','pl'),values):
            check(isinstance(value,str),'Non-text translation')
            check(specifiers(key)==specifiers(value),'Format placeholders changed: '+key+' / '+lang)
            if lang!='ko':check(not re.search('[가-힣]',value),'Korean text left in '+lang+' / '+key)
    for filename in ('MainActivity.java','ProcessShockActivity.java','HistoryActivity.java','StatisticsActivity.java'):
        text=(J/filename).read_text(encoding='utf-8')
        check('extends UnifiedActivity' in text,'Missing unified toolbar: '+filename)
        for bad in ('LiniaarLayout','TYPE_TEXT_FLAG_MULTI_LINIA','setSingleLinia','drawLinia'):
            check(bad not in text,'Translated a Java identifier: '+bad)
    widgets=(J/'LocalizedViews.java').read_text(encoding='utf-8')
    check('String.valueOf(getItem(pos))' in widgets,'Adapter must retain canonical data values')
    check('extends EditText' in widgets and 'rememberHint' in widgets,'EditText must preserve user input')
    language=(J/'AppLanguage.java').read_text(encoding='utf-8')
    check('"ko","en","pl"' in language,'Language codes not configured')
    check('recreate(' not in language and 'startMon(' not in language,'Language changes must not restart recording')
    source=(J/'ProcessShockActivity.java').read_text(encoding='utf-8')
    check('new LocalizedTextView[]{peak, rms, impact}' not in source,
          'PEAK/RMS/IMPACT array must retain TextView element type')
    check('new TextView[]{peak, rms, impact}' in source,
          'Expected type-safe PEAK/RMS/IMPACT card array missing')
    for invariant in ('DateTime,RealElapsedMs,ProcessElapsedMs,ProcessPaused,TimelineState,',
                      'Line,Equipment,Process,UnitAction,X,Y,Z,Total,Spec',
                      '"ProcessElapsedMs"','"TimelineState"','"Total"',
                      'SensorManager.SENSOR_DELAY_GAME','getSharedPreferences("ShockContext", MODE_PRIVATE)',
                      'getSharedPreferences("TelegramSettings", MODE_PRIVATE)'):
        check(invariant in source,'Stored schema or sampling setting changed: '+invariant)
    check('totalProcessPausedMs = 0L' in source and 'calibrationSampleCount = 0L' in source,'Per-run reset absent')
    check('SUMMARY/SENSOR_HEALTH.csv' in source,'Diagnostics absent from export ZIP')
    check('SUMMARY/REMOTE_COMMAND_LOG.csv' in source,'Command audit absent from export ZIP')
    check('done.await(5,java.util.concurrent.TimeUnit.SECONDS)' in source,'Remote response must wait for app execution')
    server=(J/'RemoteMonitorServer.java').read_text(encoding='utf-8')
    for invariant in ('"POST".equals(method)','x-control-pin','Same-origin request required','allowControl=false','completed.get(fingerprint)','ArrayBlockingQueue<>(16)'):
        check(invariant in server,'Remote command guard missing: '+invariant)
    html=(ROOT/'app/src/main/assets/remote_unified.html').read_text(encoding='utf-8')
    for invariant in ('id="screenlang"','localStorage','AbortController','JSON.stringify({cmd,id,run:lastState.runId})','X-Control-Pin'):
        check(invariant in html,'Remote UI check failed: '+invariant)
    wf=(ROOT/'.github/workflows/build-apk.yml').read_text(encoding='utf-8')
    check("applicationId 'com.example.vibrationmonitor.v2'" in wf,'Do not change original app identity')
    check('versionCode 50' in wf,'Wrong unified versionCode')
    check('VibrationMonitor-Unified-KO-EN-PL' in wf,'Ambiguous artifact name')
    check('make_polish.py' not in wf,'Do not apply the old source-wide translation')
    print('UNIFIED_STATIC_CHECKS_PASSED',count)
    print('Full Android compilation still runs in the next Gradle build step.')

if __name__=='__main__':
    try:main()
    except Exception as exc:print('UNIFIED_CHECK_FAILED:',exc,file=sys.stderr);sys.exit(1)
