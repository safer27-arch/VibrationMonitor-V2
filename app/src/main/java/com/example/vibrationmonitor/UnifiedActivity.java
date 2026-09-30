package com.example.vibrationmonitor;

import android.app.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.content.*;
import android.graphics.Color;
import java.util.*;
import java.io.*;
import org.json.JSONObject;

/** Stable app chrome. Language changes do not recreate this Activity or restart measurement. */
public class UnifiedActivity extends Activity implements AppLanguage.Listener {
    protected final SensorHealth sensorHealth=new SensorHealth();
    private Button languageButton;
    private TextView versionLabel;
    private View content;
    protected boolean measurementActive(){return false;}
    @Override protected void onCreate(Bundle b){AppLanguage.init(this);super.onCreate(b);AppLanguage.register(this);}
    protected int unifiedDp(float v){return Math.round(getResources().getDisplayMetrics().density*v);}
    @Override public void setContentView(View v){setContentView(v,new ViewGroup.LayoutParams(-1,-1));}
    @Override public void setContentView(int id){setContentView(getLayoutInflater().inflate(id,null));}
    @Override public void setContentView(View v,ViewGroup.LayoutParams params){
        content=v;
        LinearLayout outer=new LinearLayout(this);outer.setOrientation(LinearLayout.VERTICAL);outer.setBackgroundColor(Color.rgb(241,245,248));
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(unifiedDp(10),0,unifiedDp(8),0);
        versionLabel=new TextView(this);versionLabel.setText("Vibration Monitor · 5.0");versionLabel.setTextSize(11);versionLabel.setTextColor(Color.rgb(67,88,108));
        bar.addView(versionLabel,new LinearLayout.LayoutParams(0,-2,1f));
        Button health=new LocalizedButton(this);health.setText("측정 진단");health.setAllCaps(false);health.setTextSize(11);health.setMinWidth(0);
        bar.addView(health,new LinearLayout.LayoutParams(unifiedDp(95),unifiedDp(44)));health.setOnClickListener(w->showDiagnostics());
        languageButton=new Button(this);languageButton.setAllCaps(false);languageButton.setTextSize(16);languageButton.setMinWidth(0);languageButton.setContentDescription("Language / 언어 / Język");
        bar.addView(languageButton,new LinearLayout.LayoutParams(unifiedDp(90),unifiedDp(44)));languageButton.setOnClickListener(w->chooseLanguage());
        outer.addView(bar,new LinearLayout.LayoutParams(-1,unifiedDp(48)));outer.addView(v,new LinearLayout.LayoutParams(-1,0,1f));
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        outer.setOnApplyWindowInsetsListener((view,insets)->{
            int top=insets.getSystemWindowInsetTop(),bottom=insets.getSystemWindowInsetBottom();
            if(Build.VERSION.SDK_INT>=28&&insets.getDisplayCutout()!=null)top=Math.max(top,insets.getDisplayCutout().getSafeInsetTop());
            view.setPadding(insets.getSystemWindowInsetLeft(),top,insets.getSystemWindowInsetRight(),bottom);
            return insets.consumeSystemWindowInsets();
        });
        super.setContentView(outer,params);outer.requestApplyInsets();languageChanged();
    }
    private void chooseLanguage(){
        String[] codes={"ko","en","pl"};String[] labels={"🇰🇷 한국어","🇬🇧 English","🇵🇱 Polski"};int at=Arrays.asList(codes).indexOf(AppLanguage.code());
        new AlertDialog.Builder(this).setTitle("언어 / Language / Język").setSingleChoiceItems(labels,at,(d,n)->{AppLanguage.select(codes[n]);d.dismiss();}).setNegativeButton(AppLanguage.text("취소"),null).show();
    }
    @Override public void languageChanged(){if(languageButton!=null)languageButton.setText(AppLanguage.flag());if(content!=null)invalidateTree(content);}
    private void invalidateTree(View v) {
        v.invalidate();
        if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++)invalidateTree(group.getChildAt(i));}
    }
    protected void unifiedSensorSample(long timestamp){sensorHealth.observe(timestamp,SystemClock.elapsedRealtimeNanos());}
    private String diagnosticsText(){
        SensorHealth.Snapshot s=sensorHealth.snapshot(SystemClock.elapsedRealtimeNanos());
        long bytes=getFilesDir().getUsableSpace();int battery=-1;
        Intent b=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(b!=null){int scale=b.getIntExtra(BatteryManager.EXTRA_SCALE,-1),level=b.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);if(scale>0&&level>=0)battery=Math.round(level*100f/scale);}
        String state=measurementActive()?(s.ageMs>=0&&s.ageMs<2000?"수신 중":"새 센서 데이터 없음"):(s.count>0?"측정 종료":"측정 대기");
        return AppLanguage.text(state)+"\n\n"+AppLanguage.text(String.format(Locale.US,"실측 샘플링: %.1f Hz\n샘플 수: %d\n센서 기록 길이: %.3f s\n최근 샘플: %s\n최소 간격: %.3f ms\n최대 간격: %.3f ms\n전달 지연: %.3f ms\n배터리: %d%%\n여유 공간: %.2f GB",s.hz,s.count,s.seconds,s.ageMs<0?"-":s.ageMs+" ms",s.minGapMs,s.maxGapMs,s.deliveryDelayMs,battery,bytes/1073741824.0))
          +"\n\n"+AppLanguage.text("설정된 주기가 아니라 실제 센서 타임스탬프 기준입니다. 큰 간격은 수신 공백의 신호이며 실제 누락 개수를 확정하지는 않습니다.");
    }
    protected void showDiagnostics(){new LocalizedDialog(this).setTitle("측정 진단").setMessage(diagnosticsText()).setPositiveButton("확인",null).setNeutralButton("새로고침",(d,w)->showDiagnostics()).show();}
    protected String unifiedHealthJson(){
        try{SensorHealth.Snapshot s=sensorHealth.snapshot(SystemClock.elapsedRealtimeNanos());JSONObject j=new JSONObject();j.put("language",AppLanguage.code());j.put("samples",s.count);j.put("hz",s.hz);j.put("sampleAgeMs",s.ageMs);j.put("maxGapMs",s.maxGapMs);j.put("deliveryDelayMs",s.deliveryDelayMs);return j.toString();}catch(Exception e){return "{}";}
    }
    protected void exportHealth(java.io.File directory,String id){
        if(directory==null||id==null)return;
        try{SensorHealth.Snapshot s=sensorHealth.snapshot(SystemClock.elapsedRealtimeNanos());File f=new File(directory,"SENSOR_HEALTH_"+id.replaceAll("[^A-Za-z0-9_.-]","_")+".csv");
            try(PrintWriter p=new PrintWriter(new OutputStreamWriter(new FileOutputStream(f),"UTF-8"))){p.println("Samples,RecentHz,SensorDurationSec,MinGapMs,MaxGapMs,DeliveryDelayMs,NonMonotonicSamples");p.printf(Locale.US,"%d,%.6f,%.6f,%.6f,%.6f,%.6f,%d%n",s.count,s.hz,s.seconds,s.minGapMs,s.maxGapMs,s.deliveryDelayMs,s.nonMonotonic);}
        }catch(Exception e){android.util.Log.e("SensorHealth","Cannot export diagnostics",e);}
    }
}
