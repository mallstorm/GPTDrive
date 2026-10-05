from pathlib import Path


def rep(path, old, new, label):
    p = Path(path)
    s = p.read_text(encoding='utf-8')
    if old not in s:
        raise SystemExit(f'{label} not found in {path}')
    p.write_text(s.replace(old, new, 1), encoding='utf-8')

# Native ChatGPT backend: all HTTPS traffic goes through app-scoped Smart DNS proxy when enabled.
p='app/src/main/java/com/gptdrive/app/ChatGptApiClient.java'
s=Path(p).read_text(encoding='utf-8')
s=s.replace('GPTDrive/0.6.2', 'GPTDrive/0.6.3')
old='HttpURLConnection h = (HttpURLConnection) new URL(endpoint.startsWith("http") ? endpoint : BASE + endpoint).openConnection();'
new='URL requestUrl = new URL(endpoint.startsWith("http") ? endpoint : BASE + endpoint);\n        HttpURLConnection h = (HttpURLConnection) requestUrl.openConnection(SmartDnsProxy.proxy(context));'
if old not in s: raise SystemExit('ChatGptApiClient connection marker not found')
s=s.replace(old,new,1)
Path(p).write_text(s,encoding='utf-8')

p='app/src/main/java/com/gptdrive/app/RemoteFileSource.java'
s=Path(p).read_text(encoding='utf-8').replace('GPTDrive/0.6.2','GPTDrive/0.6.3')
old='HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();'
new='HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection(SmartDnsProxy.proxy(context));'
if old not in s: raise SystemExit('RemoteFileSource connection marker not found')
s=s.replace(old,new,1)
Path(p).write_text(s,encoding='utf-8')

# Launcher initializes routing and exposes settings.
p='app/src/main/java/com/gptdrive/app/ExplorerActivityCompact.java'
s=Path(p).read_text(encoding='utf-8')
old='''        db = new DriveDb(this);\n        buildUi();'''
new='''        db = new DriveDb(this);\n        SmartDnsManager.initialize(this);\n        buildUi();'''
if old not in s: raise SystemExit('Explorer init marker not found')
s=s.replace(old,new,1)
old='''        showActionPopup(anchor, new String[]{"Синхронизировать библиотеку", "Android Files", "Аккаунты"}, new Runnable[]{() -> openSync("Library"), this::openSystemFiles, () -> startActivity(new Intent(this, AccountManagerActivity.class))}, dp(214));'''
new='''        showActionPopup(anchor, new String[]{"Синхронизировать библиотеку", "Routing & DNS", "Android Files", "Аккаунты"}, new Runnable[]{() -> openSync("Library"), () -> startActivity(new Intent(this, DnsSettingsActivity.class)), this::openSystemFiles, () -> startActivity(new Intent(this, AccountManagerActivity.class))}, dp(232));'''
if old not in s: raise SystemExit('Explorer menu marker not found')
s=s.replace(old,new,1)
Path(p).write_text(s,encoding='utf-8')

# Same menu for the alternate main screen.
p='app/src/main/java/com/gptdrive/app/MainActivity.java'
s=Path(p).read_text(encoding='utf-8')
old='''        db = new DriveDb(this);\n        buildUi();'''
new='''        db = new DriveDb(this);\n        SmartDnsManager.initialize(this);\n        buildUi();'''
if old in s: s=s.replace(old,new,1)
old='''        showActionPopup(anchor, new String[]{"Синхронизировать Library", "Android Files", "Аккаунты"}, new Runnable[]{() -> openSync("Library"), this::openSystemFiles, () -> startActivity(new Intent(this, AccountManagerActivity.class))}, dp(220));'''
new='''        showActionPopup(anchor, new String[]{"Синхронизировать Library", "Routing & DNS", "Android Files", "Аккаунты"}, new Runnable[]{() -> openSync("Library"), () -> startActivity(new Intent(this, DnsSettingsActivity.class)), this::openSystemFiles, () -> startActivity(new Intent(this, AccountManagerActivity.class))}, dp(238));'''
if old not in s: raise SystemExit('Main menu marker not found')
s=s.replace(old,new,1)
Path(p).write_text(s,encoding='utf-8')

# Fast session login: first reuse stored WebView cookies/session; fresh login only when needed.
p='app/src/main/java/com/gptdrive/app/ChatGptAuthActivity.java'
s=Path(p).read_text(encoding='utf-8').replace('GPTDrive/0.6.0','GPTDrive/0.6.3').replace('GPTDrive/0.6.2','GPTDrive/0.6.3')
s=s.replace('''    private boolean authorized;\n    private boolean closing;''','''    private boolean authorized;\n    private boolean closing;\n    private boolean freshStarted;\n    private boolean decisionOpen;''',1)
s=s.replace('''        buildWebView();\n        prepareFreshLogin();''','''        buildWebView();\n        prepareQuickLogin();''',1)
start=s.find('    private void prepareFreshLogin() {')
end=s.find('    private void checkSessionSoon',start)
if start<0 or end<0: raise SystemExit('auth prepare block not found')
quick='''    private void prepareQuickLogin() {\n        if (previousActiveId > 0) AccountSession.snapshot(this, previousActiveId);\n        long quickId = newAccount ? previousActiveId : targetId;\n        if (quickId > 0 && db.accountById(quickId) != null) {\n            AccountSession.switchTo(this, quickId, () -> {\n                if (web == null || isFinishing()) return;\n                web.loadUrl("https://chatgpt.com/");\n                checkSessionSoon(150);\n                checkSessionSoon(650);\n                checkSessionSoon(1500);\n                handler.postDelayed(() -> {\n                    if (!authorized && !decisionOpen && !freshStarted) startFreshLogin();\n                }, 3200);\n            });\n        } else {\n            startFreshLogin();\n        }\n    }\n\n    private void startFreshLogin() {\n        if (freshStarted || authorized || closing) return;\n        freshStarted = true;\n        decisionOpen = false;\n        AccountSession.startFresh(this, targetId, () -> {\n            if (!isFinishing() && web != null) web.loadUrl("https://chatgpt.com/auth/login");\n        });\n    }\n\n'''
s=s[:start]+quick+s[end:]
old='''                if (email.isEmpty() && accessToken.isEmpty()) return;\n                authorized = true;\n                runOnUiThread(() -> completeAuthorization(json, name, email));'''
new='''                if (email.isEmpty() && accessToken.isEmpty()) return;\n                runOnUiThread(() -> {\n                    if (newAccount && !freshStarted) showQuickAccountChoice(json, name, email);\n                    else { authorized = true; completeAuthorization(json, name, email); }\n                });'''
if old not in s: raise SystemExit('auth bridge marker not found')
s=s.replace(old,new,1)
insert=s.find('    private void completeAuthorization(')
if insert<0: raise SystemExit('completeAuthorization marker not found')
choice='''    private void showQuickAccountChoice(String json, String name, String email) {\n        if (authorized || closing || decisionOpen || freshStarted) return;\n        decisionOpen = true;\n        String who = email == null || email.trim().isEmpty() ? "текущий ChatGPT аккаунт" : email.trim();\n        new android.app.AlertDialog.Builder(this)\n                .setTitle("Быстрый вход")\n                .setMessage("Продолжить как " + who + "?")\n                .setPositiveButton("Продолжить", (d, w) -> {\n                    decisionOpen = false; authorized = true; completeAuthorization(json, name, email);\n                })\n                .setNegativeButton("Другой аккаунт", (d, w) -> {\n                    decisionOpen = false; startFreshLogin();\n                })\n                .setOnCancelListener(d -> { decisionOpen = false; startFreshLogin(); })\n                .show();\n    }\n\n'''
s=s[:insert]+choice+s[insert:]
Path(p).write_text(s,encoding='utf-8')

# UI/provider user agents.
for p in ['app/src/main/java/com/gptdrive/app/DriveActions.java','app/src/main/java/com/gptdrive/app/BrowserActivity.java']:
    q=Path(p); t=q.read_text(encoding='utf-8').replace('GPTDrive/0.6.0','GPTDrive/0.6.3').replace('GPTDrive/0.6.2','GPTDrive/0.6.3'); q.write_text(t,encoding='utf-8')

# Manifest settings activity.
p='app/src/main/AndroidManifest.xml'; s=Path(p).read_text(encoding='utf-8')
marker='        <activity android:name=".AccountManagerActivity" android:exported="false"/>\n'
if '.DnsSettingsActivity' not in s:
    if marker not in s: raise SystemExit('manifest marker not found')
    s=s.replace(marker,marker+'        <activity android:name=".DnsSettingsActivity" android:exported="false"/>\n',1)
Path(p).write_text(s,encoding='utf-8')

# Version and artifact.
p='app/build.gradle'; s=Path(p).read_text(encoding='utf-8')
if 'versionCode 19' not in s or "versionName '0.6.2'" not in s: raise SystemExit('version markers not found')
s=s.replace('versionCode 19','versionCode 20',1).replace("versionName '0.6.2'","versionName '0.6.3'",1); Path(p).write_text(s,encoding='utf-8')
p='.github/workflows/android.yml'; s=Path(p).read_text(encoding='utf-8').replace('GPTDrive-v0.6.2-debug','GPTDrive-v0.6.3-debug'); Path(p).write_text(s,encoding='utf-8')

print('GPT Drive v0.6.3 integration applied')
