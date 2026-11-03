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
    val subtitleUrl: String? = null
)

class CastairJSBridge(
    private val onVideoFound: (url: String, title: String, subtitleUrl: String?) -> Unit
) {
    @JavascriptInterface
    fun onVideoDetected(url: String, title: String) {
        if (!url.isBlank()) {
            onVideoFound(url, title, null)
        }
    }

    @JavascriptInterface
    fun onVideoDetected(url: String, title: String, subtitleUrl: String?) {
        if (!url.isBlank()) {
            onVideoFound(url, title, subtitleUrl)
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
            lower.contains("videoplayback") ||
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

        // Retrieve active session cookies for this URL and pageUrl from WebView CookieManager
        runCatching {
            val cookieMgr = android.webkit.CookieManager.getInstance()
            val cookiesUrl = cookieMgr.getCookie(url)
            val cookiesPage = if (pageUrl.isNotBlank()) cookieMgr.getCookie(pageUrl) else null
            val combined = listOfNotNull(cookiesUrl, cookiesPage)
                .flatMap { it.split(";") }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("; ")
            if (combined.isNotBlank()) {
                headers["Cookie"] = combined
            }
        }

        val origin = headers.entries.firstOrNull { it.key.equals("Origin", ignoreCase = true) }?.value
        val existingReferer = headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value

        if (existingReferer.isNullOrBlank() || existingReferer.contains("google.com/search") || existingReferer.contains("bing.com/search")) {
            if (pageUrl.isNotBlank() && !pageUrl.contains("google.com/search") && !pageUrl.contains("bing.com/search")) {
                headers["Referer"] = pageUrl
            } else if (!origin.isNullOrBlank()) {
                headers["Referer"] = if (origin.endsWith("/")) origin else "$origin/"
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

    fun isSameVideoUrl(url1: String, url2: String): Boolean {
        if (url1 == url2) return true
        if (url1.isBlank() || url2.isBlank()) return false
        val uri1 = runCatching { java.net.URI(url1) }.getOrNull()
        val uri2 = runCatching { java.net.URI(url2) }.getOrNull()
        if (uri1 != null && uri2 != null) {
            val scheme1 = uri1.scheme ?: "https"
            val scheme2 = uri2.scheme ?: "https"
            val host1 = uri1.host ?: ""
            val host2 = uri2.host ?: ""
            val path1 = uri1.path ?: ""
            val path2 = uri2.path ?: ""
            return scheme1.equals(scheme2, ignoreCase = true) &&
                   host1.equals(host2, ignoreCase = true) &&
                   path1.equals(path2, ignoreCase = true) &&
                   path1.isNotBlank()
        }
        return url1.substringBefore("?") == url2.substringBefore("?")
    }

    val JS_ADVANCED_SNIFFER = """
        (function() {
            if (window.__castairInjected) return;
            window.__castairInjected = true;

            function getSubtitle(v) {
                if (!v) return null;
                var tracks = v.getElementsByTagName('track');
                for (var i = 0; i < tracks.length; i++) {
                    var t = tracks[i];
                    if (t.src && (t.kind === 'subtitles' || t.kind === 'captions' || !t.kind)) {
                        return t.src;
                    }
                }
                return null;
            }

            function notify(url, subUrl) {
                if (!url || typeof url !== 'string') return;
                if (url.indexOf('blob:') === 0 || url.indexOf('data:') === 0) return;
                if (url.endsWith('.ts') || url.endsWith('.m4s') || url.indexOf('.ts?') !== -1 || url.indexOf('.m4s?') !== -1) return;

                var lower = url.toLowerCase();
                var isPotentialVideo = lower.indexOf('.m3u8') !== -1 ||
                                       lower.indexOf('.mp4') !== -1 ||
                                       lower.indexOf('.mpd') !== -1 ||
                                       lower.indexOf('.webm') !== -1 ||
                                       lower.indexOf('/hls/') !== -1 ||
                                       lower.indexOf('/stream') !== -1 ||
                                       lower.indexOf('/source') !== -1 ||
                                       lower.indexOf('videoplayback') !== -1 ||
                                       lower.indexOf('mime=video') !== -1 ||
                                       lower.indexOf('type=video') !== -1;

                if (isPotentialVideo) {
                    if (window.CastairBridge && window.CastairBridge.onVideoDetected) {
                        try {
                            window.CastairBridge.onVideoDetected(url, document.title || 'Web Video', subUrl || null);
                        } catch(e) {
                            try {
                                window.CastairBridge.onVideoDetected(url, document.title || 'Web Video');
                            } catch(err) {}
                        }
                    }
                }
            }

            function scanVideos() {
                var videos = document.getElementsByTagName('video');
                for (var i = 0; i < videos.length; i++) {
                    var v = videos[i];
                    var sub = getSubtitle(v);
                    var src = v.currentSrc || v.src;
                    if (src) notify(src, sub);
                    var sources = v.getElementsByTagName('source');
                    for (var j = 0; j < sources.length; j++) {
                        if (sources[j].src) notify(sources[j].src, sub);
                    }
                }
            }

            try {
                var origPlay = HTMLMediaElement.prototype.play;
                HTMLMediaElement.prototype.play = function() {
                    var sub = getSubtitle(this);
                    var src = this.currentSrc || this.src;
                    if (src) notify(src, sub);
                    return origPlay.apply(this, arguments);
                };
            } catch(e) {}

            try {
                var origFetch = window.fetch;
                window.fetch = function() {
                    var url = arguments[0];
                    var urlStr = (typeof url === 'string') ? url : (url && url.url ? url.url : '');
                    if (urlStr) notify(urlStr);
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
