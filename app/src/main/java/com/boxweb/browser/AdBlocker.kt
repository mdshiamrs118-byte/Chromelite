package com.boxweb.browser

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Blocks requests to well-known ad and tracking hosts (sub-resources only, never the page itself).
 * Blocking can be switched off per site; the list of exceptions is remembered.
 */
object AdBlocker {

    private const val PREFS = "adblock"
    private const val KEY_ALLOWED = "allowed_sites"

    @Volatile
    private var allowed: Set<String> = emptySet()
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        allowed = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_ALLOWED, emptySet())
            ?.toSet()
            ?: emptySet()
    }

    /** True when blocking is on for pages of [pageHost]. */
    fun isEnabledFor(pageHost: String): Boolean = pageHost.lowercase() !in allowed

    fun setEnabledFor(pageHost: String, enabled: Boolean) {
        val site = pageHost.lowercase()
        if (site.isBlank()) return
        val updated = if (enabled) allowed - site else allowed + site
        allowed = updated
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putStringSet(KEY_ALLOWED, updated)
            ?.apply()
    }

    /** Should a request to [requestHost] made by a page on [pageHost] be blocked? */
    fun shouldBlock(requestHost: String, pageHost: String): Boolean {
        if (!isEnabledFor(pageHost)) return false
        val host = requestHost.lowercase()
        // The page's own domain is never blocked, so a site's own analytics-named paths still work.
        if (pageHost.isNotEmpty() && (host == pageHost || host.endsWith(".$pageHost"))) return false
        var candidate = host
        while (true) {
            if (candidate in BLOCKED) return true
            val dot = candidate.indexOf('.')
            if (dot < 0 || candidate.indexOf('.', dot + 1) < 0) return false // keep at least "name.tld"
            candidate = candidate.substring(dot + 1)
        }
    }

    /** What a blocked request receives: an empty, successful response. */
    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private val BLOCKED: Set<String> = setOf(
        // Google ads and analytics
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "adservice.google.com", "2mdn.net",
        "app-measurement.com", "admob.com", "adsense.google.com",
        // Large ad exchanges and networks
        "adnxs.com", "adsrvr.org", "advertising.com", "criteo.com", "criteo.net", "taboola.com",
        "outbrain.com", "amazon-adsystem.com", "rubiconproject.com", "pubmatic.com", "openx.net",
        "casalemedia.com", "smartadserver.com", "adform.net", "bidswitch.net", "contextweb.com",
        "yieldmo.com", "sharethrough.com", "teads.tv", "media.net", "revcontent.com", "mgid.com",
        "zedo.com", "adroll.com", "3lift.com", "triplelift.com", "gumgum.com", "lijit.com",
        "sovrn.com", "spotxchange.com", "springserve.com", "tremorhub.com", "adition.com",
        "adsymptotic.com", "ads.yahoo.com", "adtech.com", "adtechus.com", "serving-sys.com",
        "flashtalking.com", "adzerk.net", "carbonads.com", "buysellads.com", "adblade.com",
        "inmobi.com", "applovin.com", "unityads.unity3d.com", "ads.mopub.com", "mopub.com",
        "smaato.net", "moatads.com", "doubleverify.com", "adsafeprotected.com", "ad.gt",
        "undertone.com", "conversantmedia.com", "indexww.com", "districtm.io", "emxdgt.com",
        // Pop-up and aggressive ad networks
        "popads.net", "propellerads.com", "popcash.net", "exoclick.com", "juicyads.com",
        "trafficjunky.net", "adcash.com", "clickadu.com", "hilltopads.net", "onclickads.net",
        "adsterra.com", "richpush.co", "mondiad.com", "a-ads.com",
        // Tracking and audience data
        "scorecardresearch.com", "quantserve.com", "quantcount.com", "demdex.net", "omtrdc.net",
        "2o7.net", "krxd.net", "bluekai.com", "exelator.com", "agkn.com", "rlcdn.com", "pippio.com",
        "tapad.com", "liadm.com", "id5-sync.com", "everesttech.net", "mathtag.com", "turn.com",
        "bounceexchange.com", "chartbeat.com", "chartbeat.net", "parsely.com", "parse.ly",
        "newrelic.com", "nr-data.net", "bugsnag.com", "sentry-cdn.com", "branch.io",
        // Behaviour analytics and session recording
        "hotjar.com", "hotjar.io", "mixpanel.com", "fullstory.com", "mouseflow.com", "crazyegg.com",
        "inspectlet.com", "luckyorange.com", "clarity.ms", "heap.io", "heapanalytics.com",
        "kissmetrics.com", "amplitude.com", "optimizely.com", "segment.io", "segment.com",
        "appsflyer.com", "adjust.com", "kochava.com", "singular.net",
        // Social and platform trackers
        "analytics.twitter.com", "ads-twitter.com", "ads.linkedin.com", "px.ads.linkedin.com",
        "snap.licdn.com", "analytics.tiktok.com", "ads.tiktok.com", "bat.bing.com",
        "analytics.yahoo.com", "ads.pinterest.com", "ct.pinterest.com", "sc-static.net",
        "tr.snapchat.com", "an.yandex.ru", "mc.yandex.ru", "ads.reddit.com", "alb.reddit.com"
    )
}
