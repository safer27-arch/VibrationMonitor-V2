package com.example.vibrationmonitor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

public class RemoteMonitorServer {

    public interface StateProvider {
        String stateJson();
    }

    private static final int PORT = 8765;

    private final StateProvider provider;
    private final SecureRandom random = new SecureRandom();

    private volatile boolean running = false;
    private volatile ServerSocket serverSocket = null;
    private volatile Thread serverThread = null;
    private volatile String token = "";

    public RemoteMonitorServer(StateProvider provider) {
        this.provider = provider;
    }

    public synchronized boolean start() {
        if (running) return true;

        token = String.format(Locale.US, "%06d", random.nextInt(1_000_000));

        try {
            final ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(PORT));
            serverSocket = ss;
            running = true;

            serverThread = new Thread(() -> serveLoop(ss), "RemoteMonitorServer");
            serverThread.start();
            return true;

        } catch (Exception e) {
            running = false;
            serverSocket = null;
            return false;
        }
    }

    public synchronized void stop() {
        running = false;

        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}

        serverSocket = null;
        serverThread = null;
    }

    public boolean isRunning() {
        return running;
    }

    public String getToken() {
        return token;
    }

    public String getPrimaryUrl() {
        List<String> urls = getAccessUrls();
        return urls.isEmpty() ? "" : urls.get(0);
    }

    public List<String> getAccessUrls() {
        ArrayList<String> out = new ArrayList<>();

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return out;

            for (NetworkInterface ni : Collections.list(interfaces)) {
                try {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                } catch (Exception ignored) {
                    continue;
                }

                Enumeration<InetAddress> addresses = ni.getInetAddresses();

                for (InetAddress address : Collections.list(addresses)) {
                    if (!(address instanceof Inet4Address)) continue;
                    if (address.isLoopbackAddress()) continue;

                    String ip = address.getHostAddress();
                    if (ip == null || ip.trim().isEmpty()) continue;

                    String url = "http://" + ip + ":" + PORT + "/?k=" + token;

                    if (address.isSiteLocalAddress()) out.add(0, url);
                    else out.add(url);
                }
            }
        } catch (Exception ignored) {}

        ArrayList<String> unique = new ArrayList<>();
        for (String u : out) {
            if (!unique.contains(u)) unique.add(u);
        }
        return unique;
    }

    private void serveLoop(ServerSocket ss) {
        while (running) {
            try {
                Socket socket = ss.accept();
                socket.setSoTimeout(2500);
                handle(socket);

            } catch (Exception e) {
                if (running) {
                    try {
                        Thread.sleep(80L);
                    } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    private void handle(Socket socket) {
        try (
                Socket s = socket;
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)
                );
                BufferedWriter out = new BufferedWriter(
                        new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)
                )
        ) {
            String request = in.readLine();
            if (request == null || request.trim().isEmpty()) return;

            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // Drain HTTP headers.
            }

            String[] first = request.split(" ");
            if (first.length < 2 || !"GET".equals(first[0])) {
                send(out, 405, "text/plain; charset=utf-8", "GET only");
                return;
            }

            String target = first[1];
            String path = target;
            String query = "";

            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }

            String requestToken = queryParam(query, "k");

            if ("/favicon.ico".equals(path)) {
                send(out, 204, "text/plain; charset=utf-8", "");
                return;
            }

            if (!token.equals(requestToken)) {
                send(
                        out,
                        403,
                        "text/html; charset=utf-8",
                        "<!doctype html><meta charset='utf-8'><h3>REMOTE MONITOR</h3>"
                                + "<p>Access code가 맞지 않습니다.</p>"
                );
                return;
            }

            if ("/state".equals(path)) {
                String body = provider == null ? "{}" : provider.stateJson();
                send(out, 200, "application/json; charset=utf-8", body);
                return;
            }

            if ("/".equals(path) || "/index.html".equals(path)) {
                send(out, 200, "text/html; charset=utf-8", html());
                return;
            }

            send(out, 404, "text/plain; charset=utf-8", "Not found");

        } catch (Exception ignored) {}
    }

    private String queryParam(String query, String key) {
        if (query == null || query.isEmpty()) return "";

        String[] items = query.split("&");

        for (String item : items) {
            int eq = item.indexOf('=');
            String k = eq >= 0 ? item.substring(0, eq) : item;

            if (key.equals(k)) {
                return eq >= 0 ? item.substring(eq + 1) : "";
            }
        }
        return "";
    }

    private void send(
            BufferedWriter out,
            int status,
            String contentType,
            String body
    ) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        String text;
        if (status == 200) text = "OK";
        else if (status == 204) text = "No Content";
        else if (status == 403) text = "Forbidden";
        else if (status == 404) text = "Not Found";
        else text = "Method Not Allowed";

        out.write("HTTP/1.1 " + status + " " + text + "\r\n");
        out.write("Content-Type: " + contentType + "\r\n");
        out.write("Content-Length: " + bytes.length + "\r\n");
        out.write("Cache-Control: no-store, no-cache, must-revalidate\r\n");
        out.write("Connection: close\r\n");
        out.write("\r\n");
        out.write(body);
        out.flush();
    }

    private String html() {
        return "<!doctype html>"
                + "<html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Process Shock Remote</title>"
                + "<style>"
                + "body{margin:0;background:#eef3f7;color:#0f263d;font-family:Arial,sans-serif}"
                + ".wrap{max-width:760px;margin:auto;padding:12px}"
                + ".head{background:#0f263d;color:white;border-radius:16px;padding:16px}"
                + ".sub{font-size:12px;color:#b9cfe1;margin-top:4px}"
                + ".status{font-weight:700;margin-top:10px}"
                + ".row{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-top:8px}"
                + ".card{background:white;border-radius:12px;padding:10px}"
                + ".k{font-size:11px;color:#6b7c8a}.v{font-size:22px;font-weight:700;margin-top:3px}"
                + ".wide{background:white;border-radius:12px;padding:10px;margin-top:8px}"
                + "canvas{width:100%;height:210px;background:white;border-radius:10px}"
                + "#tl{height:92px}.small{font-size:12px;color:#5d7181}"
                + ".live{color:#14b878}.stop{color:#e0a02d}.err{color:#e05c61}"
                + "</style></head><body><div class='wrap'>"
                + "<div class='head'><div style='font-size:24px;font-weight:800'>PROCESS SHOCK REMOTE</div>"
                + "<div class='sub'>READ ONLY · Wi-Fi / Hotspot Live Monitor</div>"
                + "<div id='st' class='status'>CONNECTING...</div></div>"
                + "<div class='wide'><b id='ctx'>-</b><div class='small' id='clock'>-</div></div>"
                + "<div class='row'>"
                + "<div class='card'><div class='k'>TOTAL</div><div class='v' id='total'>0.000</div></div>"
                + "<div class='card'><div class='k'>PEAK</div><div class='v' id='peak'>0.000</div></div>"
                + "<div class='card'><div class='k'>RMS</div><div class='v' id='rms'>0.000</div></div>"
                + "</div>"
                + "<div class='row'>"
                + "<div class='card'><div class='k'>X</div><div id='x'>0.000</div></div>"
                + "<div class='card'><div class='k'>Y</div><div id='y'>0.000</div></div>"
                + "<div class='card'><div class='k'>Z</div><div id='z'>0.000</div></div>"
                + "</div>"
                + "<div class='wide'><b>LIVE VIBRATION</b><div class='small'>X / Y / Z / TOTAL · SPEC red line</div>"
                + "<canvas id='g'></canvas></div>"
                + "<div class='wide'><b>MCSC / IMPACT TIMELINE</b><div class='small' id='mcscTxt'>-</div>"
                + "<canvas id='tl'></canvas></div>"
                + "<div class='wide small'><b>LAST IMPACT</b><div id='last'>-</div>"
                + "<div style='margin-top:6px'>Browser refresh: 0.2 s · Sensor data remains stored on equipment phone.</div>"
                + "</div></div>"
                + "<script>"
                + "const k=new URLSearchParams(location.search).get('k')||'';"
                + "const $=id=>document.getElementById(id);"
                + "function fit(c,h){let d=devicePixelRatio||1;c.width=Math.max(320,c.clientWidth*d);c.height=h*d;return d}"
                + "function graph(s){let c=$('g'),d=fit(c,210),q=c.getContext('2d'),w=c.width,h=c.height;"
                + "q.clearRect(0,0,w,h);let pts=s.points||[];let max=Math.max(3,s.spec*1.5);"
                + "pts.forEach(p=>{max=Math.max(max,p[4]*1.12)});"
                + "q.strokeStyle='#e3e8ec';q.lineWidth=1*d;for(let i=1;i<4;i++){let y=h*i/4;q.beginPath();q.moveTo(0,y);q.lineTo(w,y);q.stroke()}"
                + "let sy=h-s.spec/max*h;q.strokeStyle='#d94149';q.lineWidth=1.5*d;q.beginPath();q.moveTo(0,sy);q.lineTo(w,sy);q.stroke();"
                + "let cols=['#0082dc','#00aa6e','#e69114','#704bbe'];"
                + "for(let m=0;m<4;m++){q.strokeStyle=cols[m];q.lineWidth=(m===3?2.8:1.7)*d;q.beginPath();"
                + "pts.forEach((p,i)=>{let xx=pts.length<2?0:i*w/(pts.length-1);let yy=h-Math.abs(p[m])*h/max;if(i===0)q.moveTo(xx,yy);else q.lineTo(xx,yy)});q.stroke()}}"
                + "function timeline(s){let c=$('tl'),d=fit(c,92),q=c.getContext('2d'),w=c.width,h=c.height;"
                + "q.clearRect(0,0,w,h);let units=s.mcsc||[],total=s.mcscTotal||0,ps=s.processSec||0;"
                + "let display=Math.max(.1,total);if(ps>total)display=Math.max(display,total+Math.max(.5,total*.12,(ps-total)*1.15));"
                + "let acc=0;units.forEach((u,i)=>{let st=acc;acc+=u.sec;let x1=w*st/display,x2=w*acc/display;"
                + "q.fillStyle=(ps>=st&&ps<acc)?'#cae2f1':(i%2?'#e8eff4':'#f3f7fa');q.fillRect(x1,15*d,x2-x1,52*d);"
                + "q.strokeStyle='#91a0ac';q.strokeRect(x1,15*d,Math.max(1,x2-x1),52*d);"
                + "q.fillStyle='#374b5c';q.font=(10*d)+'px Arial';if(x2-x1>18*d)q.fillText(u.name,x1+3*d,45*d)});"
                + "if(ps>total&&total>0){let ex=w*total/display;q.fillStyle='#ffedcd';q.fillRect(ex,15*d,w-ex,52*d);"
                + "q.fillStyle='#aa640a';q.font=(10*d)+'px Arial';q.fillText('OVER +'+(ps-total).toFixed(1)+'s',Math.min(w-75*d,ex+4*d),30*d)}"
                + "let px=w*Math.min(1,ps/Math.max(.1,display));q.strokeStyle=(s.paused||ps>total)?'#e6962d':'#007d6e';q.lineWidth=3*d;"
                + "q.beginPath();q.moveTo(px,8*d);q.lineTo(px,75*d);q.stroke();"
                + "(s.events||[]).forEach(e=>{let xx=w*Math.min(1,e.t/Math.max(.1,display));q.strokeStyle='#cd4448';q.lineWidth=2*d;q.beginPath();q.moveTo(xx,6*d);q.lineTo(xx,72*d);q.stroke()});"
                + "q.fillStyle='#333';q.font=(10*d)+'px Arial';q.fillText('0s',0,90*d);q.fillText(display.toFixed(1)+'s',Math.max(0,w-42*d),90*d)}"
                + "async function poll(){try{let r=await fetch('/state?k='+encodeURIComponent(k),{cache:'no-store'});if(!r.ok)throw 0;let s=await r.json();"
                + "$('st').textContent=s.calibrating?'● CALIBRATING':(s.running?'● LIVE MONITORING':'● READY / STOPPED');"
                + "$('st').className='status '+(s.running?'live':'stop');"
                + "$('ctx').textContent=(s.line||'-')+' / '+(s.equipment||'-')+' / '+(s.process||'-')+' / '+(s.unit||'-');"
                + "$('clock').textContent='PROCESS '+Number(s.processSec).toFixed(1)+'s / MCSC '+Number(s.mcscTotal).toFixed(1)+'s · '+(s.timelineState||'-');"
                + "$('total').textContent=Number(s.total).toFixed(3);$('peak').textContent=Number(s.peak).toFixed(3);$('rms').textContent=Number(s.rms).toFixed(3);"
                + "$('x').textContent=Number(s.x).toFixed(3);$('y').textContent=Number(s.y).toFixed(3);$('z').textContent=Number(s.z).toFixed(3);"
                + "$('mcscTxt').textContent='Unit '+(s.unit||'-')+' · '+Number(s.processSec).toFixed(1)+'s / '+Number(s.mcscTotal).toFixed(1)+'s';"
                + "$('last').textContent=s.lastImpact||'-';graph(s);timeline(s)}catch(e){$('st').textContent='● CONNECTION LOST';$('st').className='status err'}finally{setTimeout(poll,200)}}"
                + "poll();</script></body></html>";
    }
}
