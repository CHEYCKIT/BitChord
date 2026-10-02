package com.music.bitchord.data.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import com.music.bitchord.data.canvas.CanvasLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * The bearer token behind Spotify's own web player, minted from the
 * listener's session cookie ([AppSettings.spotifySpdcToken]) rather than an
 * app credential — there is no public API for a track's Canvas, so this walks
 * the same door the web player itself uses to fetch one.
 *
 * Call [init] once at process start, same as [CanvasCache] and the other
 * app-scoped singletons — the WebView harvest below needs a [Context] and
 * none of the suspend call chain that reaches [accessToken] has one to hand.
 */
internal object SpotifyToken {

    private const val TAG = "SpotifyToken"
    private const val BRIDGE_NAME = "BitChordSpotifyTokenBridge"
    private const val HARVEST_TIMEOUT_MS = 20_000L
    private const val DEFAULT_TOKEN_LIFETIME_MS = 3_600_000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val harvestMutex = Mutex()

    @Volatile private var appContext: Context? = null

    @Volatile private var cachedAccessToken: String? = null
    @Volatile private var accessTokenExpiresAtMs = 0L
    @Volatile private var cachedClientId: String? = null

    @Volatile private var cachedSession: SessionInfo? = null
    @Volatile private var cachedClientToken: String? = null
    @Volatile private var clientTokenExpiresAtMs = 0L

    private data class SessionInfo(val clientVersion: String, val deviceId: String)
    private data class HarvestedToken(
        val token: String,
        val expiresAt: Long,
        val clientId: String?,
        val clientToken: String,
        val clientTokenExpiresAt: Long,
    )

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * The current bearer token, or null when there is no cookie to mint one
     * from, [init] was never called, or the harvest failed. Cached until
     * shortly before it expires so a skip through a queue doesn't pay for
     * this per track.
     *
     * Minted by loading the real web player in an offscreen WebView with the
     * listener's cookie applied and reading the token it mints for itself —
     * see [harvestViaWebView] for why signing the request ourselves isn't
     * enough.
     */
    suspend fun accessToken(): String? {
        val cookie = AppSettings.spotifySpdcToken.value
        if (cookie.isBlank()) return null

        val now = System.currentTimeMillis()
        cachedAccessToken?.let {
            if (now < accessTokenExpiresAtMs - 30_000 &&
                cachedClientToken != null &&
                now < clientTokenExpiresAtMs - 30_000
            ) return it
        }

        return harvestMutex.withLock {
            val stillNow = System.currentTimeMillis()
            cachedAccessToken?.let {
                if (stillNow < accessTokenExpiresAtMs - 30_000 &&
                    cachedClientToken != null &&
                    stillNow < clientTokenExpiresAtMs - 30_000
                ) return@withLock it
            }

            val context = appContext
            if (context == null) {
                Log.w(TAG, "SpotifyToken.init was never called; no context for the harvest")
                return@withLock null
            }

            val harvested = withContext(Dispatchers.Main) { harvestViaWebView(context, cookie) }
            if (harvested == null) {
                Log.w(TAG, "token harvest failed or timed out")
                return@withLock null
            }

            cachedAccessToken = harvested.token
            accessTokenExpiresAtMs = harvested.expiresAt
            harvested.clientId?.let { cachedClientId = it }
            cachedClientToken = harvested.clientToken
            clientTokenExpiresAtMs = harvested.clientTokenExpiresAt
            Log.d(TAG, "harvested access token, good until ${java.util.Date(harvested.expiresAt)}")
            Log.d(TAG, "harvested client token, good until ${java.util.Date(harvested.clientTokenExpiresAt)}")
            harvested.token
        }
    }

    /**
     * Spotify retired the endpoint this used to hit directly with a signed
     * request; its replacement (`/api/token`) checks a TOTP the web player
     * computes from a secret buried in its own JS bundle. That part is
     * reproducible — the secret is short-lived but published — and doing so
     * does get a 200 back with a token. What it doesn't get back is a token
     * anything downstream honours: api.spotify.com and spclient both turn a
     * forged one away with a 429 that reads exactly like rate limiting, right
     * up until it fires on the very first request of a session. So rather
     * than sign the request ourselves, this loads open.spotify.com for real
     * in an offscreen WebView with the cookie applied, and reads the token
     * the page mints for itself by hooking fetch/XHR before its own bundle
     * runs.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun harvestViaWebView(context: Context, cookie: String): HarvestedToken? {
        val deferred = CompletableDeferred<HarvestedToken?>()

        val cookieManager = CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie("https://open.spotify.com/", "sp_dc=$cookie; Domain=.spotify.com; Path=/; Secure")
            setCookie("https://accounts.spotify.com/", "sp_dc=$cookie; Domain=.spotify.com; Path=/; Secure")
            flush()
        }
        // The player caches its token in web storage and skips a fresh
        // /api/token request if a live one is already sitting there, leaving
        // the hook with nothing to see — wipe storage so every harvest forces
        // a real mint.
        runCatching { WebStorage.getInstance().deleteAllData() }

        var webView: WebView? = null
        return try {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = CANVAS_UA
                cookieManager.setAcceptThirdPartyCookies(this, true)
                addJavascriptInterface(TokenBridge(deferred), BRIDGE_NAME)

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        view.evaluateJavascript(HOOK_SCRIPT, null)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        // Re-assert in case the player navigated client-side
                        // after the onPageStarted injection ran.
                        view.evaluateJavascript(HOOK_SCRIPT, null)
                    }
                }
                loadUrl("https://open.spotify.com/")
            }

            withTimeoutOrNull(HARVEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            Log.w(TAG, "token harvest threw: ${e.message}")
            null
        } finally {
            runCatching {
                webView?.removeJavascriptInterface(BRIDGE_NAME)
                webView?.stopLoading()
                webView?.destroy()
            }
        }
    }

    /** Receives the web player's bearer and client-token responses. */
    private class TokenBridge(private val deferred: CompletableDeferred<HarvestedToken?>) {
        private var accessToken: String? = null
        private var accessExpiresAt = 0L
        private var clientId: String? = null
        private var clientToken: String? = null
        private var clientTokenExpiresAt = 0L

        @Synchronized
        private fun completeIfReady() {
            if (accessToken.isNullOrBlank() || clientToken.isNullOrBlank() || deferred.isCompleted) return
            deferred.complete(
                HarvestedToken(
                    token = accessToken!!,
                    expiresAt = accessExpiresAt,
                    clientId = clientId,
                    clientToken = clientToken!!,
                    clientTokenExpiresAt = clientTokenExpiresAt,
                )
            )
        }

        @JavascriptInterface
        fun onTokenPayload(payload: String?) {
            if (payload.isNullOrBlank() || deferred.isCompleted) return
            runCatching {
                val root = json.parseToJsonElement(payload).jsonObject
                root["granted_token"]?.jsonObject?.let { granted ->
                    val token = granted["token"]?.jsonPrimitive?.contentOrNull
                    if (!token.isNullOrBlank()) {
                        clientToken = token
                        val ttl = granted["expires_after_seconds"]?.jsonPrimitive?.contentOrNull
                            ?.toLongOrNull() ?: 3600L
                        clientTokenExpiresAt = System.currentTimeMillis() + ttl * 1000L
                        Log.d(TAG, "web player minted client token, good for ${ttl}s")
                        completeIfReady()
                    }
                }

                val token = root["accessToken"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                val anonymous = root["isAnonymous"]?.jsonPrimitive?.contentOrNull
                    ?.toBooleanStrictOrNull() ?: false
                if (anonymous) {
                    Log.d(TAG, "page minted an anonymous token; waiting for a logged-in one")
                    return@runCatching
                }
                accessToken = token
                accessExpiresAt = root["accessTokenExpirationTimestampMs"]?.jsonPrimitive?.contentOrNull
                    ?.toLongOrNull()?.takeIf { it > System.currentTimeMillis() }
                    ?: (System.currentTimeMillis() + DEFAULT_TOKEN_LIFETIME_MS)
                clientId = root["clientId"]?.jsonPrimitive?.contentOrNull
                completeIfReady()
            }
        }
    }

    private val HOOK_SCRIPT = """
        (function () {
          if (window.__bitchordTokenHook) return;
          window.__bitchordTokenHook = true;
          var report = function (body) {
            try { $BRIDGE_NAME.onTokenPayload(body); } catch (e) {}
          };
          var isToken = function (u) {
            try { return String(u).indexOf('/api/token') !== -1; } catch (e) { return false; }
          };
          var isClientToken = function (u) {
            try { return String(u).indexOf('clienttoken.spotify.com/v1/clienttoken') !== -1; } catch (e) { return false; }
          };
          var origFetch = window.fetch;
          if (origFetch) {
            window.fetch = function (input, init) {
              var url = (input && input.url) ? input.url : input;
              var result = origFetch.apply(this, arguments);
              if (isToken(url) || isClientToken(url)) {
                try {
                  result.then(function (res) {
                    res.clone().text().then(report).catch(function () {});
                  }).catch(function () {});
                } catch (e) {}
              }
              return result;
            };
          };
          var origOpen = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function (method, url) {
            this.__bitchordUrl = url;
            return origOpen.apply(this, arguments);
          };
          var origSend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.send = function () {
            var xhr = this;
            try {
              xhr.addEventListener('load', function () {
                if (isToken(xhr.__bitchordUrl) || isClientToken(xhr.__bitchordUrl)) {
                  try { report(xhr.responseText); } catch (e) {}
                }
              });
            } catch (e) {}
            return origSend.apply(this, arguments);
          };
        })();
    """.trimIndent()

    /**
     * Returns the client token minted by the same WebPlayer session that
     * minted [accessToken]. Spotify pairs these credentials; independently
     * minting a client token with guessed device metadata can produce a token
     * that is accepted by the minting endpoint but rejected by Pathfinder.
     */
    fun clientToken(): String? {
        val token = cachedClientToken
        return token?.takeIf { System.currentTimeMillis() < clientTokenExpiresAtMs - 30_000 }
    }

    private fun session(): SessionInfo? {
        cachedSession?.let { return it }

        val request = Request.Builder()
            .url("https://open.spotify.com")
            .header("User-Agent", CANVAS_UA)
            .build()
        val (html, deviceId) = runCatching {
            Http.client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                val spT = response.headers("Set-Cookie").firstNotNullOfOrNull { header ->
                    Regex("""sp_t=([^;]+)""").find(header)?.groupValues?.get(1)
                }
                body to spT
            }
        }.getOrNull() ?: (null to null)
        if (html == null) {
            Log.w(TAG, "couldn't load the web player page for session info")
            return null
        }

        val configB64 = Regex("""<script id="appServerConfig" type="text/plain">([^<]+)</script>""")
            .find(html)?.groupValues?.get(1)
        if (configB64 == null) {
            Log.w(TAG, "web player page had no appServerConfig block")
            return null
        }
        val clientVersion = runCatching {
            val configJson = String(Base64.getDecoder().decode(configB64), Charsets.UTF_8)
            json.parseToJsonElement(configJson).jsonObject["clientVersion"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (clientVersion == null) {
            Log.w(TAG, "appServerConfig had no clientVersion")
            return null
        }

        val session = SessionInfo(clientVersion, deviceId ?: java.util.UUID.randomUUID().toString())
        cachedSession = session
        return session
    }
}
