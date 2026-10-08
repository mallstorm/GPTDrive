#!/usr/bin/env python3
"""Unauthenticated ChatGPT-only live DNS/TLS/HTTP checks; no user sessions."""
import concurrent.futures, http.client, json, socket, ssl, struct, time, urllib.request
from pathlib import Path
REGISTRY = 'https://raw.githubusercontent.com/mallstorm/OmniChat-DNS-Registry/main/dns-registry.json'

def query(host, kind):
    return struct.pack('!6H', 0x4750, 0x100, 1, 0, 0, 0) + b''.join(bytes([len(x)]) + x.encode('ascii') for x in host.split('.')) + b'\0' + struct.pack('!HH', kind, 1)

def readn(stream, n):
    result = b''
    while len(result) < n:
        chunk = stream.recv(n-len(result))
        if not chunk: raise OSError('Truncated DNS response')
        result += chunk
    return result

def answers(data, kind):
    if len(data)<12 or data[:2]!=b'GP' or data[3]&15: raise OSError('DNS error or invalid response')
    def skip(at):
        for _ in range(128):
            n=data[at];at+=1
            if n==0:return at
            if n&0xc0==0xc0:return at+1
            at+=n
        raise OSError('Invalid DNS name')
    qd, an=struct.unpack('!HH',data[4:8]);at=12;result=[]
    for _ in range(qd):at=skip(at)+4
    for _ in range(an):
        at=skip(at);typ,cl,ttl,n=struct.unpack('!HHIH',data[at:at+10]);at+=10
        if cl==1 and typ==kind and ((typ==1 and n==4) or (typ==28 and n==16)):
            result.append(socket.inet_ntop(socket.AF_INET if typ==1 else socket.AF_INET6,data[at:at+n]))
        at+=n
    return result

def resolve(mode,endpoint,host):
    from urllib.parse import urlparse
    ips=[];errors=[]
    for kind in (1,28):
        try:
            body=query(host,kind)
            if mode=='DoH':
                request=urllib.request.Request(endpoint,data=body,headers={'Content-Type':'application/dns-message','Accept':'application/dns-message'})
                with urllib.request.urlopen(request,timeout=5) as response: data=response.read(65536)
            else:
                u=urlparse(endpoint)
                with socket.create_connection((u.hostname,u.port or 853),timeout=5) as raw:
                    with ssl.create_default_context().wrap_socket(raw,server_hostname=u.hostname) as tls:
                        tls.sendall(struct.pack('!H',len(body))+body)
                        data=readn(tls,struct.unpack('!H',readn(tls,2))[0])
            ips+=answers(data,kind)
        except Exception as e:errors.append(str(e))
    if not ips: raise OSError('; '.join(errors) or 'DNS returned no addresses')
    return list(dict.fromkeys(ips))

def tlsconnect(host,ips):
    errors=[]
    for ip in ips[:3]:
        raw=None
        try:
            raw=socket.create_connection((ip,443),timeout=5)
            tls=ssl.create_default_context().wrap_socket(raw,server_hostname=host)
            tls.settimeout(6)
            return tls
        except Exception as e:
            errors.append(str(e))
            if raw:raw.close()
    raise OSError('; '.join(errors))

def probe(row):
    start=time.monotonic();result=dict(row,ok=False)
    try:
        ips=resolve(row['mode'],row['endpoint'],'chatgpt.com');result['addresses']=ips
        with tlsconnect('chatgpt.com',ips) as tls:
            tls.sendall(b'GET /api/auth/session HTTP/1.1\r\nHost: chatgpt.com\r\nConnection: close\r\nAccept: application/json\r\nAccept-Encoding: identity\r\nUser-Agent: Mozilla/5.0\r\n\r\n')
            response=http.client.HTTPResponse(tls);response.begin();result['http']=response.status
            if response.status!=200:raise OSError('ChatGPT HTTP '+str(response.status))
            body=response.read(49153)
            if len(body)>49152:raise OSError('Response too large')
            lower=body.decode('utf-8').lower()
            if any(x in lower for x in ('unsupported_country','country is not supported','country, region, or territory not supported','unable to load site','not available in your country')):raise OSError('Regional block')
            doc=json.loads(body)
            if not isinstance(doc,dict) or 'error' in doc or 'errors' in doc or (doc and not any(x in doc for x in ('user','accessToken','expires','WARNING_BANNER'))):raise OSError('Not a ChatGPT session response')
        with tlsconnect('auth.openai.com',resolve(row['mode'],row['endpoint'],'auth.openai.com')):pass
        result['ok']=True;result['detail']='ChatGPT session JSON + OpenAI Auth TLS verified'
    except Exception as e:result['detail']=str(e)
    result['elapsed_ms']=round((time.monotonic()-start)*1000)
    return result

def main():
    out=Path('route-diagnostics');out.mkdir(exist_ok=True)
    try:
        with urllib.request.urlopen(REGISTRY,timeout=15) as response:registry=json.load(response)
        rows=[]
        for p in registry['providers']:
            if not p.get('enabled',True) or 'openai' not in p.get('capabilities',[]):continue
            for proto in ('doh','dot'):
                ep=p.get('protocols',{}).get(proto)
                if ep:rows.append({'name':p['name'],'mode':'DoH' if proto=='doh' else 'DoT','endpoint':ep})
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:results=list(pool.map(probe,rows))
        results.sort(key=lambda x:(not x['ok'],x['elapsed_ms']))
        (out/'chatgpt-routes.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
        report='# ChatGPT Smart DNS live checks\n\nRunner network only; repeat on the phone without VPN. No credentials were sent.\n\n| Provider | Protocol | Result | ms | Detail |\n|---|---|---|---:|---|\n'
        for r in results:
            report+='| '+r['name']+' | '+r['mode']+' | '+('PASS' if r['ok'] else 'FAIL')+' | '+str(r['elapsed_ms'])+' | '+r['detail'].replace('|','/').replace('\n',' ')+' |\n'
        (out/'chatgpt-routes.md').write_text(report);print(report)
    except Exception as e:
        message='Live route checks unavailable: '+str(e)
        (out/'chatgpt-routes.md').write_text(message);print(message)

if __name__=='__main__':main()
