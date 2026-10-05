from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'{label} not found')
    return text.replace(old, new, 1)


client = Path('app/src/main/java/com/gptdrive/app/ChatGptApiClient.java')
s = client.read_text(encoding='utf-8')

old = (
    '        while (pages < maxPages) {\n'
    '            Response r = request("POST", request.endpoint, request.payload);\n'
    '            ensureLibraryStatus(r);\n'
    '            Page page = decodeLibraryPage(r.body);'
)
new = (
    '        while (pages < maxPages) {\n'
    '            Response r = request("POST", request.endpoint, request.payload);\n'
    '            if (pages > 0 && !merged.isEmpty() && isPaginationRejected(r.code)) break;\n'
    '            ensureLibraryStatus(r);\n'
    '            Page page = decodeLibraryPage(r.body);'
)
s = replace_once(s, old, new, 'fetchLibraryPages request block')

old = '            if (seenCursors.contains(next)) throw new java.io.IOException("Library pagination cursor зациклился");'
new = '            if (seenCursors.contains(next)) break;'
s = replace_once(s, old, new, 'cursor loop block')

old = (
    '                JSONObject o = (JSONObject) root; nextCursor = findString(o, 0, "next_cursor", "nextCursor", "next_page_cursor", "nextPageCursor");\n'
    '                if (nextCursor.isEmpty()) nextCursor = o.optString("cursor", "").trim(); hasMore = findBoolean(o, 0, "has_more", "hasMore", "more") || !nextCursor.isEmpty();'
)
new = (
    '                JSONObject o = (JSONObject) root;\n'
    '                boolean explicitMore = findBoolean(o, 0, "has_more", "hasMore", "more");\n'
    '                nextCursor = findString(o, 0, "next_cursor", "nextCursor", "next_page_cursor", "nextPageCursor");\n'
    '                if (nextCursor.isEmpty() && explicitMore) nextCursor = o.optString("cursor", "").trim();\n'
    '                hasMore = explicitMore || !nextCursor.isEmpty();'
)
s = replace_once(s, old, new, 'decodeLibraryPage pagination block')

marker = '    private static void ensureLibraryStatus(Response r) throws Exception {'
helper = (
    '    private static boolean isPaginationRejected(int code) {\n'
    '        return code == 400 || code == 404 || code == 405 || code == 422;\n'
    '    }\n\n'
)
if marker not in s:
    raise SystemExit('ensureLibraryStatus marker not found')
if 'private static boolean isPaginationRejected' not in s:
    s = s.replace(marker, helper + marker, 1)

s = s.replace('GPTDrive/0.6.0', 'GPTDrive/0.6.1')
client.write_text(s, encoding='utf-8')

browser = Path('app/src/main/java/com/gptdrive/app/BrowserActivity.java')
b = browser.read_text(encoding='utf-8')
if 'GPTDrive/0.6.0' not in b:
    raise SystemExit('BrowserActivity version marker not found')
browser.write_text(b.replace('GPTDrive/0.6.0', 'GPTDrive/0.6.1'), encoding='utf-8')

gradle = Path('app/build.gradle')
g = gradle.read_text(encoding='utf-8')
if 'versionCode 17' not in g or "versionName '0.6.0'" not in g:
    raise SystemExit('Gradle version markers not found')
g = g.replace('versionCode 17', 'versionCode 18', 1)
g = g.replace("versionName '0.6.0'", "versionName '0.6.1'", 1)
gradle.write_text(g, encoding='utf-8')

normal_ci = Path('.github/workflows/android.yml')
y = normal_ci.read_text(encoding='utf-8')
if 'GPTDrive-v0.6.0-debug' not in y:
    raise SystemExit('android.yml artifact marker not found')
normal_ci.write_text(y.replace('GPTDrive-v0.6.0-debug', 'GPTDrive-v0.6.1-debug', 1), encoding='utf-8')

print('GPT Drive v0.6.1 Library pagination hotfix applied')
