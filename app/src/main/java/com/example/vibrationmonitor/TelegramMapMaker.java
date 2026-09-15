package com.example.vibrationmonitor;
import android.graphics.*;
import java.io.*;
public class TelegramMapMaker {
    public static File create(File folder,String baseName,double lat,double lon){return create(folder,baseName,lat,lon,"WA5");}
    public static File create(File folder,String baseName,double lat,double lon,String area){
        try{
            int w=1000,h=650; Bitmap bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(bmp);c.drawColor(Color.rgb(246,248,250));
            Paint title=p(Color.rgb(35,55,75),38,Paint.Style.FILL,1); Paint fill=p(Color.rgb(230,235,240),24,Paint.Style.FILL,1); Paint border=p(Color.rgb(90,105,120),24,Paint.Style.STROKE,4); Paint red=p(Color.RED,27,Paint.Style.FILL,1);
            c.drawText("LGES Vibration Event Location",55,55,title); String a=area==null?"WA5":area;
            if(a.equals("WA5")){float L=70,T=115,R=930,B=540;c.drawRect(L,T,R,B,fill);c.drawRect(L,T,R,B,border);c.drawText("WA5",L+18,T+42,title);
                double lat1=51.0245604,lon1=16.8860644,lat2=51.0239081,lon2=16.8896636,lat4=51.0236584,lon4=16.8856524,ref=(lat1+lat2+lat4)/3.0,ml=111320.0,mn=111320.0*Math.cos(Math.toRadians(ref));
                double bx=(lon-lon1)*mn,by=(lat-lat1)*ml,ax=(lon2-lon1)*mn,ay=(lat2-lat1)*ml,cx=(lon4-lon1)*mn,cy=(lat4-lat1)*ml,det=ax*cy-ay*cx,u=.5,v=.5;if(Math.abs(det)>1e-6){u=(bx*cy-by*cx)/det;v=(ax*by-ay*bx)/det;}boolean inside=u>=0&&u<=1&&v>=0&&v<=1;u=Math.max(0,Math.min(1,u));v=Math.max(0,Math.min(1,v));float x=(float)(L+u*(R-L)),y=(float)(T+v*(B-T));c.drawCircle(x,y,18,red);c.drawText("Event",x+24,y-10,red);if(!inside)c.drawText("GPS outside selected boundary",70,600,p(Color.rgb(190,100,0),25,Paint.Style.FILL,1));
            } else {drawArea(c,a,title,fill,border,red,w,h);}
            File f=new File(folder,baseName+"_location.png");try(FileOutputStream out=new FileOutputStream(f)){bmp.compress(Bitmap.CompressFormat.PNG,100,out);}bmp.recycle();return f;
        }catch(Exception e){return null;}
    }
    private static Paint p(int color,float size,Paint.Style style,float stroke){Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(color);p.setTextSize(size);p.setStyle(style);p.setStrokeWidth(stroke);return p;}
    private static void drawArea(Canvas c,String area,Paint title,Paint fill,Paint border,Paint red,int w,int h){
        String[] labels=area.equals("WA5+6+7")?new String[]{"WA5","WA6","WA7"}:area.equals("WA8+9")?new String[]{"WA8","WA9"}:new String[]{area};
        float gap=18,L=70,R=930,T=150,B=510,box=(R-L-gap*(labels.length-1))/labels.length;
        for(int i=0;i<labels.length;i++){float x=L+i*(box+gap);c.drawRect(x,T,x+box,B,fill);c.drawRect(x,T,x+box,B,border);c.drawText(labels[i],x+18,T+48,title);}
        c.drawText("GPS boundary coordinates not configured yet",70,590,p(Color.rgb(95,105,115),25,Paint.Style.FILL,1));
        c.drawCircle(890,80,14,red);c.drawText("Selected area: "+area,70,105,p(Color.rgb(70,85,100),27,Paint.Style.FILL,1));
    }
}
