from pathlib import Path


def replace_between(text: str, start_marker: str, end_marker: str, replacement: str) -> str:
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit(f'start marker not found: {start_marker}')
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit(f'end marker not found: {end_marker}')
    return text[:start] + replacement + text[end:]


client = Path('app/src/main/java/com/gptdrive/app/ChatGptApiClient.java')
s = client.read_text(encoding='utf-8')
s = s.replace('GPTDrive/0.6.1', 'GPTDrive/0.6.2')

resolve_block = '''    static String resolveDownloadUrl(Context context, long localAccountId, String fileId, String conversationId) throws Exception {
        // Validate that we still have a backend session, then return the authenticated
        // backend download route. The route itself redirects to a fresh signed asset URL.
        new ChatGptApiClient(context, new DriveDb(context.getApplicationContext()), localAccountId);
        String fid = extractFileId(fileId);
        if (fid.isEmpty()) throw new java.io.IOException("У файла нет file_id");
        StringBuilder u = new StringBuilder(BASE)
                .append("/backend-api/files/download/")
                .append(Uri.encode(fid));
        if (conversationId != null && !conversationId.trim().isEmpty()) {
            u.append("?conversation_id=").append(Uri.encode(conversationId.trim()));
        }
        return u.toString();
    }

'''
s = replace_between(
    s,
    '    static String resolveDownloadUrl(Context context, long localAccountId, String fileId, String conversationId) throws Exception {',
    '    private LibraryResult fetchLibraryPages',
    resolve_block,
)

library_block = '''    private LibraryResult fetchLibraryPages(int maxPages, Progress progress) throws Exception {
        // Current ChatGPT Library uses GET /files/library/nodes with cursor=<cursor>.
        // POST /files/library is useful for other surfaces but its cursor is not the
        // continuation token for enumerating the complete Library and caused HTTP 400.
        LinkedHashMap<String, RemoteEntry> merged = new LinkedHashMap<>();
        ArrayList<String> seenCursors = new ArrayList<>();
        String cursor = "";
        int pages = 0;

        while (pages < maxPages) {
            String endpoint = "/backend-api/files/library/nodes" +
                    (cursor.isEmpty() ? "" : "?cursor=" + Uri.encode(cursor));

            Response r = null;
            for (int attempt = 0; attempt < 4; attempt++) {
                r = request("GET", endpoint, null);
                if (r.code == 200 || r.code == 401 || r.code == 403) break;
                if (r.code != 429 && r.code < 500) break;
                try { Thread.sleep(Math.min(8000L, 700L * (1L << attempt))); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
            if (r == null) throw new java.io.IOException("ChatGPT Library: пустой ответ");
            if (r.code == 401 || r.code == 403)
                throw new AuthException("ChatGPT session истекла (HTTP " + r.code + ")");
            if (r.code != 200)
                throw new java.io.IOException("ChatGPT Library nodes HTTP " + r.code);

            JSONObject root = new JSONObject(r.body);
            JSONArray items = root.optJSONArray("items");
            if (items == null)
                throw new java.io.IOException("ChatGPT Library вернула неизвестный формат");

            int before = merged.size();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                if (item.optBoolean("is_root", false)) continue;
                if ("directory".equalsIgnoreCase(item.optString("kind", ""))) continue;
                RemoteEntry e = mapToRemote(item);
                if (e != null) merge(merged, e);
            }
            pages++;
            if (progress != null) progress.onProgress("Файлы", 1, 4, merged.size());

            String next = root.optString("cursor", "").trim();
            if (next.isEmpty()) break;
            if (next.equals(cursor) || seenCursors.contains(next)) break;
            seenCursors.add(next);
            cursor = next;

            // If the backend returned only duplicates but still repeated a cursor chain,
            // continue one page; the cursor guards above prevent an infinite loop.
            if (merged.size() == before && items.length() == 0) break;
        }
        return new LibraryResult(new ArrayList<>(merged.values()), pages);
    }

'''
s = replace_between(
    s,
    '    private LibraryResult fetchLibraryPages',
    '    private List<RemoteEntry> fetchImages',
    library_block,
)
client.write_text(s, encoding='utf-8')

remote = Path('app/src/main/java/com/gptdrive/app/RemoteFileSource.java')
r = remote.read_text(encoding='utf-8')
r = r.replace('GPTDrive/0.6.0', 'GPTDrive/0.6.2').replace('GPTDrive/0.6.1', 'GPTDrive/0.6.2')
open_block = '''    private static Connection openOne(Context context, long accountId, String url, boolean addSessionCookie) throws Exception {
        if (url == null || url.trim().isEmpty()) throw new java.io.IOException("Empty URL");
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setInstanceFollowRedirects(true);
        h.setConnectTimeout(12000);
        h.setReadTimeout(45000);
        h.setRequestProperty("User-Agent", UA);
        h.setRequestProperty("Accept", "*/*");
        h.setRequestProperty("Referer", CHATGPT + "/");

        boolean chatGptBackend = url.startsWith(CHATGPT + "/") || url.startsWith("https://www.chatgpt.com/");
        if (chatGptBackend) {
            ChatGptSessionStore.Session session = ChatGptSessionStore.load(context, accountId);
            if (session != null && session.accessToken != null && !session.accessToken.isEmpty()) {
                h.setRequestProperty("Authorization", "Bearer " + session.accessToken);
                if (session.accountId != null && !session.accountId.isEmpty())
                    h.setRequestProperty("chatgpt-account-id", session.accountId);
            }
            h.setRequestProperty("Origin", CHATGPT);
            h.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
        }
        if (addSessionCookie || chatGptBackend) {
            String cookie = AccountSession.cookieHeader(context, accountId, url);
            if (cookie != null && !cookie.trim().isEmpty()) h.setRequestProperty("Cookie", cookie);
        }
        h.connect();
        return new Connection(h, h.getResponseCode());
    }

'''
r = replace_between(
    r,
    '    private static Connection openOne(Context context, long accountId, String url, boolean addSessionCookie) throws Exception {',
    '    static final class Connection',
    open_block,
)
remote.write_text(r, encoding='utf-8')

actions = Path('app/src/main/java/com/gptdrive/app/DriveActions.java')
a = actions.read_text(encoding='utf-8')
a = a.replace('Mozilla/5.0 Android GPTDrive/0.6.0', 'Mozilla/5.0 Android GPTDrive/0.6.2')
old = '''                DownloadManager.Request r=new DownloadManager.Request(Uri.parse(resolved));
                r.addRequestHeader("Referer","https://chatgpt.com/");
                r.addRequestHeader("User-Agent","Mozilla/5.0 Android GPTDrive/0.6.2");'''
new = '''                DownloadManager.Request r=new DownloadManager.Request(Uri.parse(resolved));
                r.addRequestHeader("Referer","https://chatgpt.com/");
                r.addRequestHeader("User-Agent","Mozilla/5.0 Android GPTDrive/0.6.2");
                if(resolved.startsWith("https://chatgpt.com/")){
                    ChatGptSessionStore.Session s=ChatGptSessionStore.load(app,item.accountId);
                    if(s!=null&&s.accessToken!=null&&!s.accessToken.isEmpty()){
                        r.addRequestHeader("Authorization","Bearer "+s.accessToken);
                        if(s.accountId!=null&&!s.accountId.isEmpty())r.addRequestHeader("chatgpt-account-id",s.accountId);
                    }
                    String cookie=AccountSession.cookieHeader(app,item.accountId,resolved);
                    if(cookie!=null&&!cookie.trim().isEmpty())r.addRequestHeader("Cookie",cookie);
                }'''
if old not in a:
    raise SystemExit('DriveActions download block not found')
a = a.replace(old, new, 1)
actions.write_text(a, encoding='utf-8')

gradle = Path('app/build.gradle')
g = gradle.read_text(encoding='utf-8')
if 'versionCode 18' not in g or "versionName '0.6.1'" not in g:
    raise SystemExit('build.gradle version markers not found')
g = g.replace('versionCode 18', 'versionCode 19', 1)
g = g.replace("versionName '0.6.1'", "versionName '0.6.2'", 1)
gradle.write_text(g, encoding='utf-8')

print('GPT Drive v0.6.2 hotfix applied')
