from pathlib import Path

p = Path('app/src/main/java/com/gptdrive/app/ChatGptApiClient.java')
s = p.read_text(encoding='utf-8')
old = '''            if (r.code != 200)\n                throw new java.io.IOException("ChatGPT Library nodes HTTP " + r.code);'''
new = '''            if (r.code != 200) {\n                // The Library nodes backend can return a terminal 400/404/422 for the\n                // continuation cursor after all usable pages were already returned.\n                // Do not discard thousands of successfully enumerated items in that case.\n                if (pages > 0 && !merged.isEmpty() && isPaginationRejected(r.code)) break;\n                throw new java.io.IOException("ChatGPT Library nodes HTTP " + r.code);\n            }'''
if old not in s:
    raise SystemExit('library HTTP marker not found')
s = s.replace(old, new, 1)
s = s.replace('GPTDrive/0.6.3', 'GPTDrive/0.6.4')
p.write_text(s, encoding='utf-8')

p = Path('app/build.gradle')
s = p.read_text(encoding='utf-8')
if 'versionCode 20' not in s or "versionName '0.6.3'" not in s:
    raise SystemExit('version markers not found')
s = s.replace('versionCode 20', 'versionCode 21', 1)
s = s.replace("versionName '0.6.3'", "versionName '0.6.4'", 1)
p.write_text(s, encoding='utf-8')

for name in ['DriveActions.java', 'BrowserActivity.java', 'ChatGptAuthActivity.java']:
    q = Path('app/src/main/java/com/gptdrive/app') / name
    if q.exists():
        t = q.read_text(encoding='utf-8').replace('GPTDrive/0.6.3', 'GPTDrive/0.6.4')
        q.write_text(t, encoding='utf-8')

print('GPT Drive v0.6.4 Library terminal-cursor fix applied')
