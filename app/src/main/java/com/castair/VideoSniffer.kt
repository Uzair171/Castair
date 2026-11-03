package com.castair

import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.util.Locale

data class DetectedVideo(
    val url: String,
    val title: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

class CastairJSBridge(
    private val onVideoFound: (url: String, title: String) -> Unit
) {
    @JavascriptInterface
    fun onVideoDetected(url: String, title: String) {
        if (!url.isBlank()) {
            onVideoFound(url, title)
        }
    }
}

object VideoSniffer {

    private val VIDEO_EXTENSIONS = listOf(".m3u8", ".mpd", ".mp4", ".webm", ".mkv", ".mov", ".flv")
    private val VIDEO_MIME_TYPES = listOf(
        "video/mp4",
        "video/webm",
        "video/x-matroska",
        "application/x-mpegurl",
        "application/vnd.apple.mpegurl",
        "application/dash+xml"
    )

    fun isVideoUrl(url: String, requestHeaders: Map<String, String>? = null): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (lower.startsWith("blob:") || lower.startsWith("data:")) return false

        // Exclude segment/chunk files and sub-playlists
        if (lower.endsWith(".ts") || lower.contains(".ts?") ||
            lower.endsWith(".m4s") || lower.contains(".m4s?") ||
            lower.contains("/segment") || lower.contains("segment_") ||
            lower.contains("/chunk") || lower.contains("chunk_") ||
            lower.contains("/fragment") || lower.contains("fragment_") ||
            lower.contains("/slice") || lower.contains("/part")) {
            return false
        }

        if (VIDEO_EXTENSIONS.any { lower.contains(it) }) return true

        if (lower.contains("/hls/") ||
            lower.contains("/playlist") ||
            lower.contains("/manifest") ||
            lower.contains("/master.") ||
            lower.contains("/source") ||
            lower.contains("mime=video") ||
            lower.contains("type=video") ||
            lower.contains(".m3u8")) {
            return true
        }

        if (requestHeaders != null) {
            val accept = requestHeaders.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value?.lowercase(Locale.ROOT)
            if (accept != null && (accept.contains("mpegurl") || accept.contains("video/") || accept.contains("dash+xml"))) {
                return true
            }
        }

        return false
    }

    fun isVideoMime(mimeType: String?): Boolean {
        if (mimeType.isNullOrBlank()) return false
        val lower = mimeType.lowercase(Locale.ROOT)
        return VIDEO_MIME_TYPES.any { lower.contains(it) }
    }

    fun inspectRequest(
        request: WebResourceRequest,
        pageTitle: String,
        pageUrl: String,
        userAgent: String
    ): DetectedVideo? {
        val url = request.url?.toString() ?: return null
        if (!isVideoUrl(url, request.requestHeaders)) return null

        val headers = mutableMapOf<String, String>()

        request.requestHeaders?.forEach { (k, v) ->
            if (k.equals("Referer", ignoreCase = true) ||
                k.equals("Origin", ignoreCase = true) ||
                k.equals("Cookie", ignoreCase = true) ||
                k.equals("User-Agent", ignoreCase = true)) {
                headers[k] = v
            }
        }

        // Retrieve active session cookies for this URL from WebView CookieManager
        runCatching {
            val cookies = android.webkit.CookieManager.getInstance().getCookie(url)
            if (!cookies.isNullOrBlank()) {
                headers["Cookie"] = cookies
            }
        }

        val origin = headers.entries.firstOrNull { it.key.equals("Origin", ignoreCase = true) }?.value
        val existingReferer = headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value

        if (existingReferer.isNullOrBlank() || existingReferer.contains("google.com/search") || existingReferer.contains("bing.com/search")) {
            if (!origin.isNullOrBlank()) {
                headers["Referer"] = if (origin.endsWith("/")) origin else "$origin/"
            } else if (pageUrl.isNotBlank() && !pageUrl.contains("google.com/search") && !pageUrl.contains("bing.com/search")) {
                headers["Referer"] = pageUrl
            } else {
                runCatching {
                    val uri = android.net.Uri.parse(url)
                    val host = uri.host
                    if (host != null) {
                        headers["Referer"] = "${uri.scheme ?: "https"}://$host/"
                    }
                }
            }
        }

        val hasUa = headers.keys.any { it.equals("User-Agent", ignoreCase = true) }
        if (!hasUa) {
            headers["User-Agent"] = userAgent
        }

        return DetectedVideo(
            url = url,
            title = pageTitle.ifBlank { "Web Video" },
            headers = headers
        )
    }

    fun inspectResponse(
        url: String,
        response: WebResourceResponse,
        pageTitle: String,
        pageUrl: String,
        userAgent: String
    ): DetectedVideo? {
        if (!isVideoUrl(url) && !isVideoMime(response.mimeType)) return null

        val headers = mapOf(
            "Referer" to pageUrl,
            "User-Agent" to userAgent
        )

        return DetectedVideo(
            url = url,
            title = pageTitle.ifBlank { "Web Video" },
            mimeType = response.mimeType,
            headers = headers
        )
    }

    val JS_ADVANCED_SNIFFER = """
        (function() {
            if (window.__castairInjected) return;
            window.__castairInjected = true;

            function notify(url) {
                if (!url || typeof url !== 'string') return;
                if (url.indexOf('blob:') === 0 || url.indexOf('data:') === 0) return;
                if (url.endsWith('.ts') || url.endsWith('.m4s') || url.includes('.ts?') || url.includes('.m4s?')) return;

                var lower = url.toLowerCase();
                if (lower.includes('.m3u8') || lower.includes('.mp4') || lower.includes('.mpd') || lower.includes('.webm') || lower.includes('/hls/') || lower.includes('/stream') || lower.includes('/source')) {
                    if (window.CastairBridge && window.CastairBridge.onVideoDetected) {
                        window.CastairBridge.onVideoDetected(url, document.title || 'Web Video');
                    }
                }
            }

            function scanVideos() {
                var videos = document.getElementsByTagName('video');
                for (var i = 0; i < videos.length; i++) {
                    var v = videos[i];
                    notify(v.src);
                    notify(v.currentSrc);
                    var sources = v.getElementsByTagName('source');
                    for (var j = 0; j < sources.length; j++) {
                        notify(sources[j].src);
                    }
                }
            }

            try {
                var origPlay = HTMLMediaElement.prototype.play;
                HTMLMediaElement.prototype.play = function() {
                    notify(this.src);
                    notify(this.currentSrc);
                    return origPlay.apply(this, arguments);
                };
            } catch(e) {}

            try {
                var origFetch = window.fetch;
                window.fetch = function() {
                    var url = arguments[0];
                    if (typeof url === 'string') notify(url);
                    else if (url && url.url) notify(url.url);
                    return origFetch.apply(this, arguments);
                };
            } catch(e) {}

            try {
                var origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                    if (typeof url === 'string') notify(url);
                    return origOpen.apply(this, arguments);
                };
            } catch(e) {}

            scanVideos();
            setInterval(scanVideos, 1000);
        })();
    """.trimIndent()
}
