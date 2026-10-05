package com.gptdrive.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** App-scoped Smart DNS routing ported from OmniChat. */
final class SmartDnsManager {
    static final String REGISTRY_URL = "https://raw.githubusercontent.com/mallstorm/OmniChat-DNS-Registry/main/dns-registry.json";
    private static final String PREFS = "gptdrive_smart_dns_v1";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_CACHE = "registry_cache";
    private static final String KEY_UPDATED = "registry_updated";
    private static final long REFRESH_MS = 6L * 60L * 60L * 1000L;
    private static final long CACHE_MS = 60_000L;
    private static final long LEASE_MS = 5L * 60L * 1000L;
    private static final long COOLDOWN_MS = 3L * 60L * 1000L;
    private static final int MAX_REGISTRY = 512 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ExecutorService WORK = Executors.newCachedThreadPool();
    private static final ExecutorService PROBES = Executors.newFixedThreadPool(3);
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor();
    private static final ConcurrentHashMap<String, CacheValue> DNS_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Stats> STATS = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();

    private static volatile Context app;
    private static volatile boolean initialized;
    private static volatile List<Resolver> resolvers = Collections.emptyList();
    private static volatile String selectedId = "";
    private static volatile long selectedUntil;
    private static volatile String lastRegistryError = "";

    private SmartDnsManager() {}

    static final class RouteView {
        final String id, name, mode, endpoint, error;
        final boolean selected, ok;
        final long dnsMs, tlsMs, httpMs;
        final double score;
        RouteView(String id, String name, String mode, String endpoint, String error,
                  boolean selected, boolean ok, long dnsMs, long tlsMs, long httpMs, double score) {
            this.id=id; this.name=name; this.mode=mode; this.endpoint=endpoint; this.error=error;
            this.selected=selected; this.ok=ok; this.dnsMs=dnsMs; this.tlsMs=tlsMs; this.httpMs=httpMs; this.score=score;
        }
    }

    private static final class Resolver {
        final String id, name, mode, endpoint;
        final int priority;
        final List<String> domains;
        Resolver(String id, String name, String mode, String endpoint, int priority, List<String> domains) {
            this.id=id; this.name=name; this.mode=mode; this.endpoint=endpoint; this.priority=priority; this.domains=domains;
        }
        boolean matches(String host) {
            if (domains.isEmpty()) return true;
            String h=host.toLowerCase(Locale.ROOT);
            for(String r:domains){
                String x=r.toLowerCase(Locale.ROOT).trim();
                if(x.startsWith("*.")) x=x.substring(2);
                if(x.startsWith(".")) x=x.substring(1);
                if(!x.isEmpty() && (h.equals(x)||h.endsWith("."+x))) return true;
            }
            return false;
        }
    }

    private static final class Stats {
        long successes, failures, consecutiveFailures, cooldownUntil;
        double dns=-1, tls=-1, http=-1, jitter=-1;
        long lastDns=-1;
        String error="";
        synchronized void record(boolean ok, long dnsMs, long tlsMs, long httpMs, String err) {
            if(ok){successes++; consecutiveFailures=0; cooldownUntil=0; error="";}
            else {failures++; consecutiveFailures++; error=err==null?"":err; if(consecutiveFailures>=2) cooldownUntil=System.currentTimeMillis()+COOLDOWN_MS;}
            if(dnsMs>=0){ if(lastDns>=0) jitter=ewma(jitter,Math.abs(dnsMs-lastDns)); lastDns=dnsMs; dns=ewma(dns,dnsMs); }
            if(tlsMs>=0) tls=ewma(tls,tlsMs);
            if(httpMs>=0) http=ewma(http,httpMs);
        }
        private double ewma(double old, double v){return old<0?v:(old*0.72+v*0.28);}
    }

    private static final class CacheValue {
        final long at; final List<InetAddress> addresses;
        CacheValue(long at,List<InetAddress> addresses){this.at=at;this.addresses=addresses;}
    }

    static void initialize(Context context) {
        if(initialized) return;
        synchronized(LOCK){
            if(initialized) return;
            app=context.getApplicationContext();
            String cached=prefs().getString(KEY_CACHE,"");
            if(cached!=null&&!cached.isEmpty()) parseAndInstall(cached);
            initialized=true;
            TIMER.scheduleAtFixedRate(() -> {
                try { if(isEnabled()) { refreshIfStale(); sweep(); } } catch(Exception ignored) {}
            }, 2, 15, TimeUnit.MINUTES);
        }
        if(isEnabled()) WORK.execute(() -> { refreshIfStale(); sweep(); });
    }

    static boolean isEnabled(){ return app!=null && prefs().getBoolean(KEY_ENABLED,false); }

    static void setEnabled(Context context, boolean enabled){
        initialize(context);
        prefs().edit().putBoolean(KEY_ENABLED,enabled).apply();
        DNS_CACHE.clear(); selectedId=""; selectedUntil=0;
        if(enabled) refreshAndSweep(context,null);
    }

    static long lastUpdated(){ return app==null?0:prefs().getLong(KEY_UPDATED,0); }
    static String lastRegistryError(){ return lastRegistryError; }

    static void refreshAndSweep(Context context, Runnable done){
        initialize(context);
        WORK.execute(() -> { try { refreshRemote(); sweep(); } finally { if(done!=null) done.run(); } });
    }

    static void refreshIfStale(){
        if(app==null) return;
        long at=prefs().getLong(KEY_UPDATED,0);
        if(System.currentTimeMillis()-at>REFRESH_MS || resolvers.isEmpty()) refreshRemote();
    }

    private static void refreshRemote(){
        if(app==null) return;
        HttpURLConnection h=null;
        try{
            h=(HttpURLConnection)new URL(REGISTRY_URL).openConnection(Proxy.NO_PROXY);
            h.setConnectTimeout(8000); h.setReadTimeout(10000); h.setInstanceFollowRedirects(true);
            h.setRequestProperty("Accept","application/json"); h.setRequestProperty("User-Agent","GPTDrive-DNS-Registry/1");
            int code=h.getResponseCode(); if(code!=200) throw new java.io.IOException("Registry HTTP "+code);
            byte[] body=readLimited(h.getInputStream(),MAX_REGISTRY);
            String text=new String(body,StandardCharsets.UTF_8);
            List<Resolver> parsed=parse(text);
            if(parsed.isEmpty()) throw new java.io.IOException("В реестре нет OpenAI DNS");
            resolvers=parsed;
            prefs().edit().putString(KEY_CACHE,text).putLong(KEY_UPDATED,System.currentTimeMillis()).apply();
            lastRegistryError=""; DNS_CACHE.clear();
        }catch(Exception e){ lastRegistryError=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage(); }
        finally{if(h!=null)h.disconnect();}
    }

    private static void parseAndInstall(String text){ try{List<Resolver> p=parse(text); if(!p.isEmpty())resolvers=p;}catch(Exception ignored){} }

    private static List<Resolver> parse(String text) throws Exception{
        JSONObject root=new JSONObject(text); if(root.optInt("version",1)!=1) throw new IllegalArgumentException("DNS Registry version");
        JSONArray providers=root.optJSONArray("providers"); ArrayList<Resolver> out=new ArrayList<>();
        if(providers==null)return out;
        for(int i=0;i<providers.length();i++){
            JSONObject p=providers.optJSONObject(i); if(p==null||!p.optBoolean("enabled",true))continue;
            JSONArray caps=p.optJSONArray("capabilities"); boolean openai=caps==null||caps.length()==0;
            if(caps!=null)for(int j=0;j<caps.length();j++)if("openai".equalsIgnoreCase(caps.optString(j)))openai=true;
            if(!openai)continue;
            String base=p.optString("id","provider-"+i); String name=p.optString("name",base); int priority=p.optInt("priority",100);
            ArrayList<String> domains=new ArrayList<>(); JSONArray d=p.optJSONArray("domains"); if(d!=null)for(int j=0;j<d.length();j++){String x=d.optString(j).trim();if(!x.isEmpty())domains.add(x);}
            JSONObject protocols=p.optJSONObject("protocols"); if(protocols==null)continue;
            String doh=protocols.optString("doh","").trim(); String dot=protocols.optString("dot","").trim();
            if(!doh.isEmpty()&&doh.startsWith("https://"))out.add(new Resolver("remote:"+base+":doh",name,"DoH",doh,priority,domains));
            if(!dot.isEmpty()&&dot.startsWith("tls://"))out.add(new Resolver("remote:"+base+":dot",name,"DoT",dot,priority+1,domains));
        }
        out.sort(Comparator.comparingInt(a->a.priority)); return out;
    }

    static List<RouteView> routes(){
        long now=System.currentTimeMillis(); ArrayList<RouteView> out=new ArrayList<>();
        List<Resolver> ranked=rankedFor("chatgpt.com",now);
        String chosen=choose(ranked,now)==null?"":choose(ranked,now).id;
        for(Resolver r:ranked){Stats s=STATS.get(r.id); boolean ok=s!=null&&s.successes>0&&s.cooldownUntil<=now;
            out.add(new RouteView(r.id,r.name,r.mode,r.endpoint,s==null?"":s.error,r.id.equals(chosen),ok,
                    s==null||s.dns<0?-1:Math.round(s.dns),s==null||s.tls<0?-1:Math.round(s.tls),s==null||s.http<0?-1:Math.round(s.http),score(r,s,now)));
        }
        return out;
    }

    static String selectedSummary(){
        Resolver r=find(selectedId); if(r==null){Resolver c=choose(rankedFor("chatgpt.com",System.currentTimeMillis()),System.currentTimeMillis());r=c;}
        if(r==null)return "System DNS"; Stats s=STATS.get(r.id); return r.name+" · "+r.mode+(s!=null&&s.dns>=0?" · "+Math.round(s.dns)+" ms":"");
    }

    static Socket connect(Context context,String host,int port) throws Exception{
        initialize(context);
        if(!isEnabled())return systemConnect(host,port);
        refreshIfStale(); long now=System.currentTimeMillis(); List<Resolver> ranked=rankedFor(host,now);
        Resolver first=choose(ranked,now); ArrayList<Resolver> ordered=new ArrayList<>(); if(first!=null)ordered.add(first); for(Resolver r:ranked)if(first==null||!r.id.equals(first.id))ordered.add(r);
        Exception last=null;
        for(Resolver r:ordered){ long start=System.nanoTime(); try{
            List<InetAddress> addrs=resolve(r,host); long dnsMs=ms(start); Socket socket=connectAddresses(addrs,port);
            stat(r).record(true,dnsMs,-1,-1,null); selectedId=r.id; selectedUntil=System.currentTimeMillis()+LEASE_MS; return socket;
        }catch(Exception e){stat(r).record(false,ms(start),-1,-1,e.getMessage()); if(r.id.equals(selectedId)){selectedId="";selectedUntil=0;} last=e;}}
        try{return systemConnect(host,port);}catch(Exception e){if(last!=null)throw last;throw e;}
    }

    private static Socket connectAddresses(List<InetAddress> addrs,int port)throws Exception{
        Exception last=null; for(InetAddress a:addrs){Socket s=new Socket();try{s.connect(new InetSocketAddress(a,port),5000);s.setSoTimeout(45000);return s;}catch(Exception e){last=e;try{s.close();}catch(Exception ignored){}}}throw last==null?new java.io.IOException("No addresses"):last;
    }
    private static Socket systemConnect(String host,int port)throws Exception{return connectAddresses(java.util.Arrays.asList(InetAddress.getAllByName(host)),port);}

    private static void sweep(){
        if(!isEnabled()||resolvers.isEmpty())return; ArrayList<java.util.concurrent.Future<?>> fs=new ArrayList<>();
        for(Resolver r:resolvers) if(r.matches("chatgpt.com")) fs.add(PROBES.submit(()->probe(r)));
        for(java.util.concurrent.Future<?> f:fs)try{f.get(12,TimeUnit.SECONDS);}catch(Exception ignored){}
        choose(rankedFor("chatgpt.com",System.currentTimeMillis()),System.currentTimeMillis());
    }

    private static void probe(Resolver r){
        long d0=System.nanoTime(); long dns=-1,tls=-1,http=-1; try{
            List<InetAddress> addrs=resolve(r,"chatgpt.com"); dns=ms(d0); if(addrs.isEmpty())throw new java.io.IOException("DNS empty");
            long t0=System.nanoTime(); Socket raw=connectAddresses(addrs,443);
            SSLSocket ssl=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(raw,"chatgpt.com",443,true);
            SSLParameters sp=ssl.getSSLParameters(); sp.setEndpointIdentificationAlgorithm("HTTPS"); sp.setServerNames(Collections.singletonList(new SNIHostName("chatgpt.com"))); ssl.setSSLParameters(sp); ssl.setSoTimeout(5000); ssl.startHandshake(); tls=ms(t0);
            long h0=System.nanoTime(); OutputStream out=ssl.getOutputStream(); out.write(("HEAD / HTTP/1.1\r\nHost: chatgpt.com\r\nConnection: close\r\nUser-Agent: GPTDrive-DNS-Health/1\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
            BufferedReader br=new BufferedReader(new InputStreamReader(ssl.getInputStream(),StandardCharsets.US_ASCII)); String line=br.readLine(); http=ms(h0); ssl.close(); if(line==null||!line.startsWith("HTTP/"))throw new java.io.IOException("HTTP probe");
            stat(r).record(true,dns,tls,http,null);
        }catch(Exception e){stat(r).record(false,dns<0?ms(d0):dns,tls,http,e.getMessage());}
    }

    private static List<Resolver> rankedFor(String host,long now){
        ArrayList<Resolver> list=new ArrayList<>(); for(Resolver r:resolvers)if(r.matches(host))list.add(r);
        list.sort((a,b)->{int c=Double.compare(score(b,STATS.get(b.id),now),score(a,STATS.get(a.id),now));return c!=0?c:Integer.compare(a.priority,b.priority);}); return list;
    }

    private static Resolver choose(List<Resolver> ranked,long now){
        if(ranked.isEmpty())return null; Resolver best=null; for(Resolver r:ranked)if(score(r,STATS.get(r.id),now)>-999){best=r;break;} if(best==null)return null;
        Resolver current=find(selectedId); if(current!=null&&selectedUntil>now&&ranked.contains(current)){
            double delta=score(best,STATS.get(best.id),now)-score(current,STATS.get(current.id),now); if(delta<8.0)return current;
        }
        selectedId=best.id; selectedUntil=now+LEASE_MS; return best;
    }

    private static double score(Resolver r,Stats s,long now){
        if(s!=null&&s.cooldownUntil>now)return -1000;
        double admin=3.0*(1.0-(Math.max(0,Math.min(1000,r.priority))/1000.0));
        if(s==null||s.successes+s.failures==0)return 52.0+admin;
        double attempts=Math.max(1,s.successes+s.failures), availability=s.successes/attempts;
        double v=35*availability+25*q(s.dns,250)+20*q(s.tls,700)+10*q(s.http,1200)+10*q(s.jitter,220)+admin-Math.min(24,s.consecutiveFailures*8);
        return Math.max(0,Math.min(100,v));
    }
    private static double q(double value,double scale){return value<0?0.55:Math.exp(-Math.max(0,value)/scale);}
    private static Stats stat(Resolver r){return STATS.computeIfAbsent(r.id,k->new Stats());}
    private static Resolver find(String id){if(id==null||id.isEmpty())return null;for(Resolver r:resolvers)if(id.equals(r.id))return r;return null;}

    private static List<InetAddress> resolve(Resolver r,String host)throws Exception{
        String key=r.id+"|"+host.toLowerCase(Locale.ROOT); CacheValue cv=DNS_CACHE.get(key); long now=System.currentTimeMillis(); if(cv!=null&&now-cv.at<CACHE_MS)return cv.addresses;
        ArrayList<InetAddress> out=new ArrayList<>(); if("DoH".equals(r.mode)){out.addAll(resolveDoh(r.endpoint,host,1));out.addAll(resolveDoh(r.endpoint,host,28));} else {out.addAll(resolveDot(r.endpoint,host,1));out.addAll(resolveDot(r.endpoint,host,28));}
        LinkedHashMap<String,InetAddress> uniq=new LinkedHashMap<>();for(InetAddress a:out)uniq.put(a.getHostAddress(),a);List<InetAddress> result=new ArrayList<>(uniq.values());if(result.isEmpty())throw new java.io.IOException("DNS не вернул адреса");DNS_CACHE.put(key,new CacheValue(now,result));return result;
    }

    private static List<InetAddress> resolveDoh(String endpoint,String host,int type)throws Exception{
        byte[] query=query(host,type); HttpsURLConnection h=(HttpsURLConnection)new URL(endpoint).openConnection(Proxy.NO_PROXY); h.setRequestMethod("POST");h.setConnectTimeout(5000);h.setReadTimeout(5000);h.setDoOutput(true);h.setFixedLengthStreamingMode(query.length);h.setRequestProperty("Content-Type","application/dns-message");h.setRequestProperty("Accept","application/dns-message");h.setRequestProperty("Accept-Encoding","identity");h.setRequestProperty("User-Agent","GPTDrive-SmartDNS/1");try(OutputStream o=h.getOutputStream()){o.write(query);}int code=h.getResponseCode();if(code!=200)throw new java.io.IOException("DoH HTTP "+code);byte[] body=readLimited(h.getInputStream(),64*1024);h.disconnect();return parseDns(body,type);
    }

    private static List<InetAddress> resolveDot(String endpoint,String host,int type)throws Exception{
        URI u=URI.create(endpoint);String dnsHost=u.getHost();int port=u.getPort()>0?u.getPort():853;Socket raw=new Socket();raw.connect(new InetSocketAddress(dnsHost,port),5000);raw.setSoTimeout(5000);SSLSocket ssl=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(raw,dnsHost,port,true);SSLParameters p=ssl.getSSLParameters();p.setEndpointIdentificationAlgorithm("HTTPS");p.setServerNames(Collections.singletonList(new SNIHostName(dnsHost)));ssl.setSSLParameters(p);ssl.startHandshake();byte[] q=query(host,type);DataOutputStream out=new DataOutputStream(ssl.getOutputStream());out.writeShort(q.length);out.write(q);out.flush();DataInputStream in=new DataInputStream(new BufferedInputStream(ssl.getInputStream()));int len=in.readUnsignedShort();if(len<=0||len>65535)throw new java.io.IOException("DoT length");byte[] body=new byte[len];in.readFully(body);ssl.close();return parseDns(body,type);
    }

    private static byte[] query(String host,int type)throws Exception{
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);d.writeShort(RANDOM.nextInt(65536));d.writeShort(0x0100);d.writeShort(1);d.writeShort(0);d.writeShort(0);d.writeShort(0);for(String label:host.split("\\.")){byte[] x=label.getBytes(StandardCharsets.US_ASCII);if(x.length>63)throw new IllegalArgumentException("DNS label");d.writeByte(x.length);d.write(x);}d.writeByte(0);d.writeShort(type);d.writeShort(1);d.flush();return b.toByteArray();
    }

    private static List<InetAddress> parseDns(byte[] data,int wanted)throws Exception{
        ArrayList<InetAddress> out=new ArrayList<>();if(data.length<12)return out;int qd=u16(data,4),an=u16(data,6);int p=12;for(int i=0;i<qd;i++){p=skipName(data,p);p+=4;if(p>data.length)return out;}for(int i=0;i<an&&p<data.length;i++){p=skipName(data,p);if(p+10>data.length)break;int type=u16(data,p),clazz=u16(data,p+2),len=u16(data,p+8);p+=10;if(p+len>data.length)break;if(clazz==1&&type==wanted&&((type==1&&len==4)||(type==28&&len==16))){byte[] ip=new byte[len];System.arraycopy(data,p,ip,0,len);out.add(InetAddress.getByAddress(ip));}p+=len;}return out;
    }
    private static int skipName(byte[] d,int p)throws Exception{int guard=0;while(p<d.length&&guard++<128){int n=d[p]&255;if(n==0)return p+1;if((n&0xC0)==0xC0){if(p+1>=d.length)throw new java.io.IOException("DNS name");return p+2;}p+=1+n;}throw new java.io.IOException("DNS name");}
    private static int u16(byte[] d,int p){return ((d[p]&255)<<8)|(d[p+1]&255);}
    private static long ms(long start){return Math.max(0,(System.nanoTime()-start)/1_000_000L);}
    private static byte[] readLimited(InputStream in,int max)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int total=0,n;while((n=in.read(buf))!=-1){total+=n;if(total>max)throw new java.io.IOException("Response too large");out.write(buf,0,n);}return out.toByteArray();}
    private static SharedPreferences prefs(){return app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
}
