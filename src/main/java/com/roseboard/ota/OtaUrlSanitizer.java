package com.roseboard.ota;

import java.util.Map;

public final class OtaUrlSanitizer {
    private OtaUrlSanitizer() {
    }

    public static String redact(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        int cut = url.length();
        if (query >= 0) {
            cut = Math.min(cut, query);
        }
        if (fragment >= 0) {
            cut = Math.min(cut, fragment);
        }
        return url.substring(0, cut);
    }

    public static boolean containsSecret(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("token=") || lower.contains("secret=") || lower.contains("signature=");
    }

    public static Map<String, Object> auditDetail(String kind, String title, String version,
                                                  String url, long sizeBytes) {
        return Map.of(
                "kind", kind,
                "title", title,
                "version", version,
                "size", sizeBytes,
                "url", redact(url));
    }
}
