package com.example.vibrationmonitor;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.lang.ref.WeakReference;
import java.util.*;
import org.json.*;

public final class AppLanguage {
    public interface Listener { void languageChanged(); }
    private static Context app;
    private static volatile String code="ko";
    private static volatile TextCatalog catalog;
    private static final List<WeakReference<Listener>> listeners=new ArrayList<>();
    public static synchronized void init(Context context) {
        if(app!=null) return;
        app=context.getApplicationContext();
        code=app.getSharedPreferences("UnifiedLanguage",Context.MODE_PRIVATE).getString("language","ko");
        if(!Arrays.asList("ko","en","pl").contains(code)) code="ko";
        Map<String,String[]> entries=new LinkedHashMap<>();
        try(InputStream in=app.getAssets().open("unified_i18n.json")) {
            ByteArrayOutputStream b=new ByteArrayOutputStream(); byte[] block=new byte[8192]; int n;
            while((n=in.read(block))!=-1) b.write(block,0,n);
            JSONObject j=new JSONObject(b.toString("UTF-8"));
            Iterator<String> keys=j.keys();
            while(keys.hasNext()) {String k=keys.next(); JSONArray a=j.getJSONArray(k); entries.put(k,new String[]{a.getString(0),a.getString(1),a.getString(2)});}
        } catch(Exception e) { android.util.Log.e("UnifiedLanguage","Language catalog could not be loaded",e); }
        catalog=new TextCatalog(entries);
    }
    public static String code(){return code;}
    public static String flag(){return "pl".equals(code)?"🇵🇱 PL":("en".equals(code)?"🇬🇧 EN":"🇰🇷 KR");}
    public static String text(CharSequence raw){TextCatalog c=catalog;return c==null?(raw==null?"":raw.toString()):c.translate(raw,"en".equals(code)?1:("pl".equals(code)?2:0));}
    public static synchronized void register(Listener listener){listeners.add(new WeakReference<>(listener));}
    public static void select(String language) {
        if(!Arrays.asList("ko","en","pl").contains(language)||app==null)return;
        code=language;
        app.getSharedPreferences("UnifiedLanguage",Context.MODE_PRIVATE).edit().putString("language",language).apply();
        new Handler(Looper.getMainLooper()).post(() -> {
            List<Listener> active=new ArrayList<>();
            synchronized(AppLanguage.class){Iterator<WeakReference<Listener>> i=listeners.iterator();while(i.hasNext()){Listener l=i.next().get();if(l==null)i.remove();else active.add(l);}}
            for(Listener l:active)l.languageChanged();
        });
    }
    private AppLanguage(){}
}
