package com.gptdrive.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight, dependency-free Markdown renderer for the internal file viewer. */
final class MarkdownRenderer {
    private static final Pattern TABLE_SEPARATOR = Pattern.compile(
            "^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");
    private static final Pattern ORDERED = Pattern.compile("^\\s*\\d+[.)]\\s+(.+)$");
    private static final Pattern UNORDERED = Pattern.compile("^\\s*[-+*]\\s+(.+)$");

    private MarkdownRenderer() {}

    static String toHtml(String markdown) {
        if (markdown == null) markdown = "";
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder out = new StringBuilder(Math.max(1024, normalized.length() + 1024));
        String list = "";
        boolean code = false;
        String codeLanguage = "";

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                if (!list.isEmpty()) {
                    out.append("</").append(list).append(">");
                    list = "";
                }
                if (!code) {
                    code = true;
                    codeLanguage = trimmed.length() > 3 ? trimmed.substring(3).trim() : "";
                    out.append("<pre><code");
                    if (!codeLanguage.isEmpty()) out.append(" data-language=\"").append(escape(codeLanguage)).append("\"");
                    out.append(">");
                } else {
                    out.append("</code></pre>");
                    code = false;
                    codeLanguage = "";
                }
                continue;
            }
            if (code) {
                out.append(escape(line)).append('\n');
                continue;
            }

            if (trimmed.isEmpty()) {
                if (!list.isEmpty()) {
                    out.append("</").append(list).append(">");
                    list = "";
                }
                out.append("<div class=\"gap\"></div>");
                continue;
            }

            // GitHub-style pipe tables.
            if (i + 1 < lines.length && line.contains("|") && TABLE_SEPARATOR.matcher(lines[i + 1]).matches()) {
                if (!list.isEmpty()) {
                    out.append("</").append(list).append(">");
                    list = "";
                }
                List<String> headers = cells(line);
                out.append("<div class=\"table-wrap\"><table><thead><tr>");
                for (String cell : headers) out.append("<th>").append(inline(cell)).append("</th>");
                out.append("</tr></thead><tbody>");
                i += 2;
                while (i < lines.length && lines[i].contains("|") && !lines[i].trim().isEmpty()) {
                    List<String> row = cells(lines[i]);
                    out.append("<tr>");
                    int width = Math.max(headers.size(), row.size());
                    for (int c = 0; c < width; c++) {
                        out.append("<td>");
                        if (c < row.size()) out.append(inline(row.get(c)));
                        out.append("</td>");
                    }
                    out.append("</tr>");
                    i++;
                }
                out.append("</tbody></table></div>");
                i--;
                continue;
            }

            int heading = headingLevel(trimmed);
            if (heading > 0) {
                if (!list.isEmpty()) {
                    out.append("</").append(list).append(">");
                    list = "";
                }
                String text = trimmed.substring(heading).trim();
                out.append("<h").append(heading).append(">").append(inline(text))
                        .append("</h").append(heading).append(">");
                continue;
            }

            if (trimmed.matches("^([-*_])(?:\\s*\\1){2,}$")) {
                if (!list.isEmpty()) {
                    out.append("</").append(list).append(">");
                    list = "";
                }
                out.append("<hr>");
                continue;
            }

            if (trimmed.startsWith(">")) {
                if (!list.isEmpty()) {
                    out.append("</").append(list).append(">");
                    list = "";
                }
                out.append("<blockquote>").append(inline(trimmed.substring(1).trim())).append("</blockquote>");
                continue;
            }

            Matcher ordered = ORDERED.matcher(line);
            Matcher unordered = UNORDERED.matcher(line);
            if (ordered.matches() || unordered.matches()) {
                String wanted = ordered.matches() ? "ol" : "ul";
                if (!wanted.equals(list)) {
                    if (!list.isEmpty()) out.append("</").append(list).append(">");
                    list = wanted;
                    out.append("<").append(list).append(">");
                }
                String text = ordered.matches() ? ordered.group(1) : unordered.group(1);
                String low = text.toLowerCase(Locale.ROOT);
                if (low.startsWith("[ ] ") || low.startsWith("[x] ")) {
                    boolean checked = low.startsWith("[x]");
                    text = text.substring(4);
                    out.append("<li class=\"task\"><span class=\"box\">")
                            .append(checked ? "✓" : "")
                            .append("</span>").append(inline(text)).append("</li>");
                } else {
                    out.append("<li>").append(inline(text)).append("</li>");
                }
                continue;
            }

            if (!list.isEmpty()) {
                out.append("</").append(list).append(">");
                list = "";
            }
            out.append("<p>").append(inline(line)).append("</p>");
        }

        if (!list.isEmpty()) out.append("</").append(list).append(">");
        if (code) out.append("</code></pre>");

        return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>"
                + "html,body{margin:0;padding:0;background:#f7f9fa;color:#182230;font-family:sans-serif;}"
                + "body{padding:18px 18px 36px;font-size:15px;line-height:1.52;}"
                + "h1,h2,h3,h4,h5,h6{color:#101828;margin:1.15em 0 .45em;font-weight:750;line-height:1.25;}"
                + "h1{font-size:1.75em}h2{font-size:1.45em}h3{font-size:1.25em}h4{font-size:1.1em}"
                + "p{margin:.55em 0}.gap{height:.25em}a{color:#087f5b;text-decoration:none;}"
                + "pre{overflow:auto;background:#111827;color:#e5e7eb;padding:14px;border-radius:12px;font-size:13px;}"
                + "code{font-family:monospace;background:#eef2f5;padding:2px 5px;border-radius:5px;}pre code{background:transparent;padding:0;}"
                + "blockquote{margin:.7em 0;padding:8px 12px;border-left:4px solid #08a66d;background:#edf8f4;border-radius:5px;}"
                + "ul,ol{padding-left:24px}li{margin:5px 0}.task{list-style:none;margin-left:-20px}.box{display:inline-block;width:17px;height:17px;line-height:17px;text-align:center;border:1px solid #08a66d;border-radius:4px;margin-right:7px;color:#08a66d;font-weight:bold;}"
                + ".table-wrap{overflow:auto;margin:10px 0}table{border-collapse:collapse;min-width:100%;background:#fff}th,td{border:1px solid #d0d5dd;padding:8px 10px;text-align:left;vertical-align:top}th{background:#eef7f3}"
                + "img{max-width:100%;height:auto;border-radius:10px}hr{border:0;border-top:1px solid #d0d5dd;margin:18px 0}"
                + "</style></head><body>" + out + "</body></html>";
    }

    private static int headingLevel(String s) {
        int n = 0;
        while (n < s.length() && n < 6 && s.charAt(n) == '#') n++;
        return n > 0 && n < s.length() && Character.isWhitespace(s.charAt(n)) ? n : 0;
    }

    private static List<String> cells(String line) {
        String s = line.trim();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        String[] parts = s.split("\\|", -1);
        ArrayList<String> result = new ArrayList<>(parts.length);
        for (String p : parts) result.add(p.trim());
        return result;
    }

    private static String inline(String raw) {
        String s = escape(raw);
        s = s.replaceAll("!\\[([^]]*)\\]\\((https?://[^\\s)]+)\\)", "<img alt=\"$1\" src=\"$2\">");
        s = s.replaceAll("\\[([^]]+)\\]\\((https?://[^\\s)]+)\\)", "<a href=\"$2\">$1</a>");
        s = s.replaceAll("`([^`]+)`", "<code>$1</code>");
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        s = s.replaceAll("__([^_]+)__", "<strong>$1</strong>");
        s = s.replaceAll("~~([^~]+)~~", "<del>$1</del>");
        s = s.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "<em>$1</em>");
        return s;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
