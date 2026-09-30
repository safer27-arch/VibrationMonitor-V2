package com.example.vibrationmonitor;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Private-network app telemetry/control. HTTP is deliberately disclosed as unencrypted. */
public final class RemoteMonitorServer {
    public interface StateProvider { String stateJson(); }
    public interface CommandHandler { String handleCommand(String command,String runId,String requestId); }
    private static final int PORT=8765;
    private final StateProvider provider;
    private final CommandHandler commands;
    private final String html,catalog;
    private final SecureRandom random=new SecureRandom();
    private volatile ServerSocket server;
    private volatile ThreadPoolExecutor workers;
    private volatile String token="",controlPin="";
    private volatile boolean allowControl=false;
    private int failedPins=0;
    private long lockUntil=0;
    private final Object commandLock=new Object();
    private final LinkedHashMap<String,String> completed=new LinkedHashMap<>();

    public RemoteMonitorServer(Context context,StateProvider provider,CommandHandler commands) {
        this.provider=provider;this.commands=commands;
        html=asset(context,"remote_unified.html");catalog=asset(context,"unified_i18n.json");
    }
    private static String asset(Context c,String name){
        try(InputStream in=c.getAssets().open(name)){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);return b.toString("UTF-8");}
        catch(Exception e){throw new IllegalStateException("Missing remote asset: "+name,e);}
    }
    public synchronized boolean start(){
        if(server!=null&&!server.isClosed())return true;
        ServerSocket ss=null;
        try{
            byte[] bytes=new byte[16];random.nextBytes(bytes);StringBuilder t=new StringBuilder();for(byte b:bytes)t.append(String.format(Locale.US,"%02x",b&255));token=t.toString();
            controlPin=String.format(Locale.US,"%06d",random.nextInt(1000000));allowControl=false;
            ss=new ServerSocket();ss.setReuseAddress(true);ss.bind(new InetSocketAddress(PORT));server=ss;
            ThreadPoolExecutor pool=new ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread w=new Thread(r,"VM-Remote-Client");w.setDaemon(true);return w;});workers=pool;
            ServerSocket active=ss;Thread listener=new Thread(()->serve(active,pool),"VM-Remote-Server");listener.setDaemon(true);listener.start();return true;
        }catch(Exception e){if(ss!=null)try{ss.close();}catch(Exception ignored){}server=null;return false;}
    }
    public synchronized void stop(){ServerSocket s=server;server=null;allowControl=false;if(s!=null)try{s.close();}catch(Exception ignored){}ThreadPoolExecutor p=workers;workers=null;if(p!=null)p.shutdownNow();}
    public boolean isRunning(){return server!=null&&!server.isClosed();}
    public void setControlAllowed(boolean enabled){allowControl=enabled;}
    public boolean isControlAllowed(){return allowControl;}
    public String getToken(){return token;}
    public String getControlPin(){return controlPin;}
    public String getPrimaryUrl(){List<String> a=getAccessUrls();return a.isEmpty()?"":a.get(0);}
    public List<String> getAccessUrls(){
        List<String> wifi=new ArrayList<>(),other=new ArrayList<>();
        try{for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces())){
            if(!ni.isUp()||ni.isLoopback())continue;
            String name=ni.getName().toLowerCase(Locale.US);
            if(name.startsWith("rmnet")||name.startsWith("ccmni")||name.startsWith("tun")||name.startsWith("pdp"))continue;
            for(InetAddress a:Collections.list(ni.getInetAddresses()))if(a instanceof Inet4Address&&a.isSiteLocalAddress()){
                String u="http://"+a.getHostAddress()+":"+PORT+"/?k="+token;
                (name.contains("wlan")||name.contains("wifi")||name.startsWith("ap")||name.startsWith("swlan")?wifi:other).add(u);
            }
        }}catch(Exception ignored){}
        wifi.addAll(other);return new ArrayList<>(new LinkedHashSet<>(wifi));
    }
    private void serve(ServerSocket ss,ThreadPoolExecutor pool){
        while(server==ss&&!ss.isClosed())try{
            Socket s=ss.accept();s.setSoTimeout(3500);
            try{pool.execute(()->handle(s));}catch(RejectedExecutionException ex){s.close();}
        }catch(Exception e){if(server==ss&&!ss.isClosed())try{Thread.sleep(40);}catch(InterruptedException x){Thread.currentThread().interrupt();break;}}
    }
    private static String readLine(InputStream in,int max)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1){if(c=='\n')break;if(c!='\r')b.write(c);if(b.size()>max)throw new IOException("Header too long");}
        if(c==-1&&b.size()==0)return null;return b.toString("US-ASCII");
    }
    private void handle(Socket socket){
        try(Socket s=socket){
            InputStream in=new BufferedInputStream(s.getInputStream());OutputStream out=s.getOutputStream();
            String line=readLine(in,2048);if(line==null)return;String[] request=line.split(" ");if(request.length<3){send(out,400,"text/plain","Bad request");return;}
            String method=request[0],target=request[1];Map<String,String> headers=new HashMap<>();int bytes=0;
            while((line=readLine(in,4096))!=null&&!line.isEmpty()){bytes+=line.length();if(bytes>8192)throw new IOException("Too many headers");int colon=line.indexOf(':');if(colon>0)headers.put(line.substring(0,colon).trim().toLowerCase(Locale.US),line.substring(colon+1).trim());}
            int split=target.indexOf('?');String path=split<0?target:target.substring(0,split),query=split<0?"":target.substring(split+1);
            if("/favicon.ico".equals(path)){send(out,204,"text/plain","");return;}
            if(!constantEquals(token,param(query,"k"))){send(out,403,"application/json",failure("Access denied"));return;}
            if("GET".equals(method)){
                if("/state".equals(path))send(out,200,"application/json",provider.stateJson());
                else if("/i18n".equals(path))send(out,200,"application/json",catalog);
                else if("/".equals(path)||"/index.html".equals(path))send(out,200,"text/html",html);
                else send(out,"/control".equals(path)?405:404,"application/json",failure("POST required"));
                return;
            }
            if(!"POST".equals(method)||!"/control".equals(path)){send(out,405,"application/json",failure("Method not allowed"));return;}
            String origin=headers.get("origin"),host=headers.get("host");
            if(origin==null||host==null||!origin.equals("http://"+host)){send(out,403,"application/json",failure("Same-origin request required"));return;}
            if(!headers.getOrDefault("content-type","").toLowerCase(Locale.US).startsWith("application/json")){send(out,415,"application/json",failure("JSON required"));return;}
            if(!allowControl){send(out,403,"application/json",failure("CONTROL DISABLED"));return;}
            synchronized(commandLock){
                long now=System.nanoTime()/1000000L;
                if(now<lockUntil){send(out,429,"application/json",failure("Too many PIN attempts - wait 60 seconds"));return;}
                if(!constantEquals(controlPin,headers.getOrDefault("x-control-pin",""))){
                    if(++failedPins>=5){lockUntil=now+60000;failedPins=0;}
                    send(out,403,"application/json",failure("Wrong control PIN"));return;
                }
                failedPins=0;
                int size=Integer.parseInt(headers.getOrDefault("content-length","0"));if(size<=0||size>2048){send(out,413,"application/json",failure("Invalid request size"));return;}
                byte[] buf=new byte[size];int used=0,n;while(used<size&&(n=in.read(buf,used,size-used))!=-1)used+=n;
                if(used!=size){send(out,400,"application/json",failure("Incomplete request"));return;}
                String body=new String(buf,StandardCharsets.UTF_8),cmd=field(body,"cmd"),id=field(body,"id"),run=field(body,"run");
                if(!Arrays.asList("start","stop","pause","resume").contains(cmd)||!id.matches("[A-Za-z0-9_-]{8,100}")||!run.matches("[A-Za-z0-9_-]{1,100}")){send(out,400,"application/json",failure("Invalid command"));return;}
                String fingerprint=run+":"+id;String result=completed.get(fingerprint);
                if(result==null){
                    if(!allowControl){send(out,403,"application/json",failure("CONTROL DISABLED"));return;}
                    result=commands.handleCommand(cmd,run,id);
                    completed.put(fingerprint,result);
                    while(completed.size()>256)completed.remove(completed.keySet().iterator().next());
                }
                send(out,200,"application/json",result);
            }
        }catch(Exception e){/* Client disconnects must not stop sensor recording. */}
    }
    private static String field(String body,String key){Matcher m=Pattern.compile("\\\""+key+"\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]*)\\\"").matcher(body);return m.find()?m.group(1):"";}
    private static String param(String q,String key){for(String item:q.split("&")){int at=item.indexOf('=');if(at>0&&key.equals(item.substring(0,at)))try{return URLDecoder.decode(item.substring(at+1),"UTF-8");}catch(Exception ignored){}}return "";}
    private static boolean constantEquals(String a,String b){if(a==null||b==null)return false;return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    private static String failure(String message){return "{\"ok\":false,\"message\":\""+message+"\"}";}
    private static void send(OutputStream out,int code,String type,String body)throws IOException{
        byte[] b=body.getBytes(StandardCharsets.UTF_8);
        String h="HTTP/1.1 "+code+" "+(code==200?"OK":"Response")+"\r\nContent-Type: "+type+"; charset=utf-8\r\nContent-Length: "+b.length+"\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nX-Frame-Options: DENY\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));out.write(b);out.flush();
    }
}
