#!/usr/bin/env python3
"""Apply V5.0 once to a clean Korean V4.4 source tree. No global translation replacements."""
from pathlib import Path
import re, json, hashlib, sys
from unified_blocks import METHODS
PKG=Path('app/src/main/java/com/example/vibrationmonitor')
VERSION='5.0-UNIFIED-KO-EN-PL'

# Quoted strings, character literals and comments are never constructor/method patch targets.
TOKEN=re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
def masked(s):return TOKEN.sub(lambda m:''.join('\n' if c=='\n' else ' ' for c in m.group()),s)
def edits(s,changes):
    for start,end,replacement in sorted(changes,reverse=True):s=s[:start]+replacement+s[end:]
    return s

def span_method(s,name):
    msk=masked(s)
    matches=list(re.finditer(r'(?m)^\s*(?:private|protected|public)\s+(?:static\s+)?[\w.<>,?\[\] ]+\s+'+re.escape(name)+r'\s*\([^)]*\)\s*(?:throws\s+[^\{]+)?\{',msk))
    if len(matches)!=1:raise ValueError(f'{name}: expected one method, found {len(matches)}')
    m=matches[0];open_at=msk.rfind('{',m.start(),m.end());level=1;i=open_at+1
    while i<len(s) and level:
        if msk[i]=='{':level+=1
        elif msk[i]=='}':level-=1
        i+=1
    if level:raise ValueError('Unbalanced method '+name)
    return m.start(),i,open_at

def replace_method(s,name,code):
    a,b,o=span_method(s,name);return s[:a]+'\n'+code.strip('\n')+'\n'+s[b:]
def add_start(s,name,code):
    a,b,o=span_method(s,name);return s[:o+1]+'\n'+code+s[o+1:]
def add_end(s,name,code):
    a,b,o=span_method(s,name);return s[:b-1]+code+'\n'+s[b-1:]
def once(s,old,new,label):
    n=s.count(old)
    if n!=1:raise ValueError(f'{label}: expected one source block, found {n}')
    return s.replace(old,new,1)

def first_argument(s,opening):
    msk=masked(s);level=0
    for i in range(opening+1,len(s)):
        c=msk[i]
        if c in '([{':level+=1
        elif c in ')]}':
            if c==')' and level==0:return opening+1,i
            level-=1
        elif c==',' and level==0:return opening+1,i
    raise ValueError('Unterminated argument')

def ui_boundaries(s):
    msk=masked(s);changes=[]
    widgets={'TextView':'LocalizedTextView','Button':'LocalizedButton','CheckBox':'LocalizedCheckBox','EditText':'LocalizedEditText','ArrayAdapter':'LocalizedArrayAdapter'}
    for m in re.finditer(r'\bnew\s+(?:android\.widget\.)?(TextView|Button|CheckBox|EditText|ArrayAdapter)\b(?=\s*(?:<[^;{}()]*>\s*)?\()',msk):
        changes.append((m.start(),m.end(),'new '+widgets[m.group(1)]))
    for m in re.finditer(r'\bnew\s+(?:android\.app\.)?AlertDialog\.Builder\b',msk):changes.append((m.start(),m.end(),'new LocalizedDialog'))
    for m in re.finditer(r'\b(?:android\.widget\.)?Toast\.makeText\b',msk):changes.append((m.start(),m.end(),'LocalizedToast.makeText'))
    for m in re.finditer(r'\b([a-zA-Z_]\w*)\.drawText\s*\(',msk):
        a,b=first_argument(s,m.end()-1);changes.append((a,b,'AppLanguage.text('+s[a:b]+')'))
    # EditText.getText() and its data are untouched. Only hints change language.
    for m in re.finditer(r'\b([a-zA-Z_]\w*)\.setHint\s*\(',msk):
        a,b=first_argument(s,m.end()-1)
        if msk[b]!=')':raise ValueError('Unexpected hint signature')
        changes.append((m.start(),b+1,'UiHints.set('+m.group(1)+', '+s[a:b]+')'))
    return edits(s,changes)

def transform_proc(s):
    s=once(s,'extends Activity implements SensorEventListener','extends UnifiedActivity implements SensorEventListener','Process superclass')
    s=once(s,'    private volatile String remoteEventsJson = "[]";', '''    private volatile String remoteEventsJson = "[]";
    // UNIFIED_V5_PRESENTATION_AND_DIAGNOSTICS
    private volatile String remoteRunId = java.util.UUID.randomUUID().toString();
    private volatile boolean remoteAutoMode = false;
    private long remoteMetadataMs = 0;
    private double[] remoteBucket = null;
    @Override protected boolean measurementActive() { return running; }
''','unified fields')
    for name,code in METHODS.items():
        if name=='syncRemoteControlState':
            a,b,o=span_method(s,'updateRemoteSnapshot');s=s[:a]+'\n'+code+'\n'+s[a:]
        else:s=replace_method(s,name,code)
    # Explicitly reset per-run pause/correction state: previous patch scripts had skipped these resets.
    reset='''        if (running) return;
'''
    s=add_start(s,'startMon',reset)
    s=once(s,'        running = true;\n        calibrating = true;', '''        sensorHealth.reset();
        remoteRunId = java.util.UUID.randomUUID().toString();
        processPaused = false; processPauseStartMs = 0L; totalProcessPausedMs = 0L;
        autoPauseActive = false; autoPausedTotalMs = 0L; autoCorrectionCount = 0;
        autoStopCandidateStartMs = 0L; autoResumeCandidateStartMs = 0L;
        autoCorrectionSuppressUntilMs = 0L;
        calibrationSampleCount = 0L; calibrationSumSq = 0.0;
        stoppedDurationSec = 0L; stoppedProcessDurationSec = 0L;
        runStartWallMs = System.currentTimeMillis();
        currentRunSummaryFile = null; currentUnitSummaryFile = null; currentImpactSummaryFile = null;
        synchronized (remotePoints) { remotePoints.clear(); remoteBucket = null; }
        remoteLastPointMs = 0L; remoteProcessSec = 0.0; remoteLastImpact = "-"; remoteEventsJson = "[]";
        running = true;
        calibrating = true;''','per-run reset')
    s=add_end(s,'startMon','        syncRemoteControlState();')
    s=once(s,'        if (!running) return;\n\n        long now = SystemClock.elapsedRealtime();','        if (!running) return;\n        unifiedSensorSample(e.timestamp);\n\n        long now = SystemClock.elapsedRealtime();','sensor diagnostics')
    s=once(s,'        updateRemoteSnapshot(now, x, y, z, t);\n\n        if (calibrating) {','        if (calibrating) {\n            updateRemoteSnapshot(now, x, y, z, t);','snapshot calibration')
    s=once(s,'                calibrating = false;\n                startMs = now;', '''                calibrating = false;
                sensorHealth.reset();
                runStartWallMs = System.currentTimeMillis();
                synchronized (remotePoints) { remotePoints.clear(); remoteBucket = null; }
                remoteLastPointMs = 0L;
                startMs = now;''','actual measurement start')
    s=once(s,'        graph.add(x, y, z, t, spec);','        updateRemoteSnapshot(now, x, y, z, t);\n        graph.add(x, y, z, t, spec);','snapshot after statistics')
    s=once(s,'        long stopNow = SystemClock.elapsedRealtime();','''        long stopNow = SystemClock.elapsedRealtime();
        double exactRemoteEnd = startMs > 0L
                ? (isAutoMode() ? getProcessElapsedMs(stopNow) / 1000.0 : (stopNow - startMs) / 1000.0)
                : 0.0;''','stop precision')
    s=once(s,'        remoteProcessSec = stoppedProcessDurationSec;','''        remoteProcessSec = exactRemoteEnd;
        remotePeak = sessionPeak; remoteRms = n > 0L ? Math.sqrt(sumSq / n) : 0.0;
        synchronized (remotePoints) { if (remoteBucket != null) { remotePoints.addLast(remoteBucket); remoteBucket = null; } }''','frozen remote state')
    s=once(s,'        saveSessionCsv();\n        saveUnitSummaryCsv();','''        exportHealth(eventDir(), remoteRunId);
        saveSessionCsv();
        saveUnitSummaryCsv();''','save diagnostic CSV')
    s=once(s,'                zipAddFile(out, currentImpactSummaryFile, "SUMMARY/IMPACT_SUMMARY.csv");','''                zipAddFile(out, currentImpactSummaryFile, "SUMMARY/IMPACT_SUMMARY.csv");
                zipAddFile(out, new java.io.File(eventDir(), "SENSOR_HEALTH_" + remoteRunId + ".csv"), "SUMMARY/SENSOR_HEALTH.csv");''','diagnostic export')
    s=once(s, '"REMOTE_COMMAND_LOG.csv"', '"REMOTE_COMMAND_LOG_" + remoteRunId + ".csv"', 'per-run command audit')
    s=once(s, '                zipAddFile(out, new java.io.File(eventDir(), "SENSOR_HEALTH_" + remoteRunId + ".csv"), "SUMMARY/SENSOR_HEALTH.csv");', '''                zipAddFile(out, new java.io.File(eventDir(), "SENSOR_HEALTH_" + remoteRunId + ".csv"), "SUMMARY/SENSOR_HEALTH.csv");
                zipAddFile(out, new java.io.File(eventDir(), "REMOTE_COMMAND_LOG_" + remoteRunId + ".csv"), "SUMMARY/REMOTE_COMMAND_LOG.csv");''', 'export current command audit')
    s=add_end(s,'toggleProcessPause','        syncRemoteControlState();')
    return ui_boundaries(s)

def transform_other(s,name):
    if 'extends Activity' in s:
        s=once(s,'extends Activity','extends UnifiedActivity',name+' superclass')
    if name=='MainActivity.java':
        a,b,o=span_method(s,'startMeasurement');s=s[:a]+s[a:b].replace('        measuring = true;', '        if (!measuring) sensorHealth.reset();\n        measuring = true;',1)+s[b:]
        a,b,o=span_method(s,'onSensorChanged');signature=s[a:o];m=re.search(r'SensorEvent\s+(\w+)',signature);assert m
        # Record timestamps only while the main measurement is active.
        s=add_start(s,'onSensorChanged',f'        if (measuring) unifiedSensorSample({m.group(1)}.timestamp);\n')
        at=s.rfind('}');s=s[:at]+'\n    @Override protected boolean measurementActive() { return measuring; }\n'+s[at:]
    return ui_boundaries(s)

def apply(root):
    root=Path(root);plans={}
    for name in ['MainActivity.java','ProcessShockActivity.java','HistoryActivity.java','StatisticsActivity.java']:
        p=root/PKG/name;source=p.read_text(encoding='utf-8')
        if 'extends UnifiedActivity' in source:raise ValueError('Unified release already present; refusing to apply twice.')
        plans[str(PKG/name)]=transform_proc(source) if name=='ProcessShockActivity.java' else transform_other(source,name)
    service_path=PKG/'ProcessShockKeepAliveService.java'
    service=(root/service_path).read_text(encoding='utf-8')
    service=add_start(service,'onCreate','        AppLanguage.init(this);')
    for old in ['"Process Shock Monitoring"', '"Keeps continuous equipment impact monitoring active."', '"연속 충격 측정이 진행 중입니다."']:
        service=service.replace(old,'AppLanguage.text('+old+')')
    plans[str(service_path)]=service
    # SharedPreferences keys, process IDs, filenames, commands and CSV field names are never translated.
    workflow=root/'.github/workflows/build-apk.yml';s=workflow.read_text()
    if "applicationId 'com.example.vibrationmonitor.v2'" not in s:raise ValueError('Unexpected package ID; do not overwrite another app.')
    s=re.sub(r'^name:.*$', 'name: Build Unified Android APK',s,count=1,flags=re.M)
    s,n=re.subn(r'versionCode\s+\d+','versionCode 50',s,count=1);assert n==1
    s,n=re.subn(r"versionName\s+'[^']+'","versionName '"+VERSION+"'",s,count=1);assert n==1
    s=once(s,'      - name: Build APK','      - name: Check localization boundaries\n        run: python tools/verify_unified.py\n\n      - name: Build APK','workflow checks')
    s=once(s,'      - name: Upload APK','''      - name: Name unified APK clearly
        run: cp app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/debug/VibrationMonitor_Unified_5.0.apk

      - name: Upload APK''','unified filename')
    s=once(s,'name: VibrationMonitor-APK','name: VibrationMonitor-Unified-KO-EN-PL','artifact name')
    s=once(s,'path: app/build/outputs/apk/debug/app-debug.apk','path: app/build/outputs/apk/debug/VibrationMonitor_Unified_5.0.apk','artifact path')
    plans['.github/workflows/build-apk.yml']=s
    # Validate the plan before writing anything. Patch failures never leave partly edited Java files.
    for path,s in plans.items():
        if path.endswith('.java'):
            m=masked(s)
            stack=[]
            for c in m:
                if c in '([{':stack.append(c)
                elif c in ')]}':
                    if not stack or '([{'.index(stack.pop())!=')]}'.index(c):raise ValueError('Unbalanced Java: '+path)
            if stack:raise ValueError('Unclosed Java: '+path)
    for path,s in plans.items():
        p=root/path;p.write_text(s,encoding='utf-8')
    return list(plans)
if __name__=='__main__':
    for p in apply(Path.cwd()):print('UPDATED',p)
