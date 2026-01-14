package org.schabi.newpipe.extractor.services.youtube;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.utils.Parser;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The extractor of YouTube's base JavaScript player file.
 *
 * <p>
 * This class handles fetching of this base JavaScript player file in order to allow other classes
 * to extract the needed data.
 * </p>
 *
 * <p>
 * It will try to get the player URL from YouTube's IFrame resource first, and from a YouTube embed
 * watch page as a fallback.
 * </p>
 *
 * <p>
 * Updated for 2026 with improved caching, retry logic, and error handling to prevent
 * "unknown error" issues.
 * </p>
 */
final class YoutubeJavaScriptExtractor {

    private static final String HTTPS = "https:";
    private static final String BASE_JS_PLAYER_URL_FORMAT =
            "https://www.youtube.com/s/player/%s/player_ias.vflset/en_GB/base.js";
    private static final Pattern IFRAME_RES_JS_BASE_PLAYER_HASH_PATTERN = Pattern.compile(
            "player\\\\/([a-z0-9]{8})\\\\/");
    private static final Pattern EMBEDDED_WATCH_PAGE_JS_BASE_PLAYER_URL_PATTERN = Pattern.compile(
            "\"jsUrl\":\"(/s/player/[A-Za-z0-9]+/player_ias\\.vflset/[A-Za-z_-]+/base\\.js)\"");

    // Additional patterns for 2026 player URL extraction
    private static final Pattern PLAYER_JS_URL_PATTERN = Pattern.compile(
            "/s/player/([a-z0-9]{8})/");
    private static final Pattern ALT_PLAYER_HASH_PATTERN = Pattern.compile(
            "\"PLAYER_JS_URL\"\\s*:\\s*\"/s/player/([a-z0-9]{8})/");

    // Cache for player ID to avoid repeated network calls
    @Nullable
    private static String cachedPlayerId = null;
    private static long cacheTimestamp = 0;
    private static final long CACHE_DURATION_MS = 3600000; // 1 hour

    // Retry configuration
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 500;

    private YoutubeJavaScriptExtractor() {
    }

    /**
     * Extracts the player ID (hash) from YouTube.
     *
     * <p>
     * The player ID is an 8-character hash that identifies the JavaScript player version.
     * It is used for API-based decoding of signatures and throttling parameters.
     * </p>
     *
     * <p>
     * Updated for 2026 with caching and improved retry logic.
     * </p>
     *
     * @param videoId the video ID (can be empty, but not recommended)
     * @return the 8-character player ID/hash
     * @throws ParsingException if the extraction of the player ID failed
     */
    @Nonnull
    static String extractPlayerId(@Nonnull final String videoId) throws ParsingException {
        // Check cache first
        if (cachedPlayerId != null && !isCacheExpired()) {
            return cachedPlayerId;
        }

        ParsingException lastException = null;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                final String playerId = extractPlayerIdInternal(videoId);
                // Update cache
                cachedPlayerId = playerId;
                cacheTimestamp = System.currentTimeMillis();
                return playerId;
            } catch (final ParsingException e) {
                lastException = e;
                // Wait before retry
                if (attempt < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ParsingException("Interrupted during player ID extraction retry", ie);
                    }
                }
            }
        }

        throw new ParsingException("Could not extract player ID after " + MAX_RETRIES + " attempts", lastException);
    }

    /**
     * Internal method to extract player ID with multiple fallback strategies.
     */
    @Nonnull
    private static String extractPlayerIdInternal(@Nonnull final String videoId) throws ParsingException {
        // Strategy 1: Try IFrame resource
        try {
            final String iframeUrl = "https://www.youtube.com/iframe_api";
            final String iframeContent = NewPipe.getDownloader()
                    .get(iframeUrl, Localization.DEFAULT)
                    .responseBody();

            if (iframeContent != null && !iframeContent.isEmpty()) {
                final String hash = Parser.matchGroup1(IFRAME_RES_JS_BASE_PLAYER_HASH_PATTERN, iframeContent);
                if (hash != null && hash.length() == 8) {
                    return hash;
                }
            }
        } catch (final Exception ignored) {
            // Try next strategy
        }

        // Strategy 2: Try embed page
        try {
            final String embedUrl = "https://www.youtube.com/embed/" + videoId;
            final String embedPageContent = NewPipe.getDownloader()
                    .get(embedUrl, Localization.DEFAULT)
                    .responseBody();

            if (embedPageContent != null && !embedPageContent.isEmpty()) {
                // Try jsUrl pattern first
                try {
                    final String jsUrl = Parser.matchGroup1(
                            EMBEDDED_WATCH_PAGE_JS_BASE_PLAYER_URL_PATTERN, embedPageContent);
                    final String hash = Parser.matchGroup1(PLAYER_JS_URL_PATTERN, jsUrl);
                    if (hash != null && hash.length() == 8) {
                        return hash;
                    }
                } catch (final Exception ignored) {
                    // Try alternative pattern
                }

                // Try alternative pattern
                try {
                    final String hash = Parser.matchGroup1(ALT_PLAYER_HASH_PATTERN, embedPageContent);
                    if (hash != null && hash.length() == 8) {
                        return hash;
                    }
                } catch (final Exception ignored) {
                    // Continue to next strategy
                }
            }
        } catch (final Exception ignored) {
            // Try next strategy
        }

        // Strategy 3: Try watch page
        try {
            final String watchUrl = "https://www.youtube.com/watch?v=" + videoId;
            final String watchPageContent = NewPipe.getDownloader()
                    .get(watchUrl, Localization.DEFAULT)
                    .responseBody();

            if (watchPageContent != null && !watchPageContent.isEmpty()) {
                try {
                    final String hash = Parser.matchGroup1(ALT_PLAYER_HASH_PATTERN, watchPageContent);
                    if (hash != null && hash.length() == 8) {
                        return hash;
                    }
                } catch (final Exception ignored) {
                    // Continue
                }

                try {
                    final String hash = Parser.matchGroup1(PLAYER_JS_URL_PATTERN, watchPageContent);
                    if (hash != null && hash.length() == 8) {
                        return hash;
                    }
                } catch (final Exception ignored) {
                    // Continue
                }
            }
        } catch (final Exception ignored) {
            // All strategies failed
        }

        throw new ParsingException("Could not extract player ID using any strategy");
    }

    /**
     * Check if the cached player ID has expired.
     */
    private static boolean isCacheExpired() {
        return System.currentTimeMillis() - cacheTimestamp > CACHE_DURATION_MS;
    }

    /**
     * Clear the cached player ID.
     */
    static void clearCache() {
        cachedPlayerId = null;
        cacheTimestamp = 0;
    }

    /**
     * Extracts the JavaScript base player file.
     *
     * <p>
     * Updated for 2026 with improved retry logic and multiple fallback strategies.
     * </p>
     *
     * @param videoId the video ID used to get the JavaScript base player file (an empty one can be
     *                passed, even it is not recommend in order to spoof better official YouTube
     *                clients)
     * @return the whole JavaScript base player file as a string
     * @throws ParsingException if the extraction of the file failed
     */
    @Nonnull
    static String extractJavaScriptPlayerCode(@Nonnull final String videoId)
            throws ParsingException {
        ParsingException lastException = null;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return extractJavaScriptPlayerCodeInternal(videoId);
            } catch (final ParsingException e) {
                lastException = e;
                // Wait before retry
                if (attempt < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ParsingException("Interrupted during JavaScript extraction retry", ie);
                    }
                }
            }
        }

        throw new ParsingException("Could not extract JavaScript player code after " + MAX_RETRIES + " attempts", lastException);
    }

    /**
     * Internal method to extract JavaScript player code with multiple strategies.
     */
    @Nonnull
    private static String extractJavaScriptPlayerCodeInternal(@Nonnull final String videoId)
            throws ParsingException {
        // Strategy 1: Try IFrame resource
        try {
            final String url = extractJavaScriptUrlWithIframeResource();
            final String playerJsUrl = cleanJavaScriptUrl(url);
            new URL(playerJsUrl); // Validate URL
            return downloadJavaScriptCode(playerJsUrl);
        } catch (final Exception ignored) {
            // Try next strategy
        }

        // Strategy 2: Try embed watch page
        try {
            final String url = extractJavaScriptUrlWithEmbedWatchPage(videoId);
            final String playerJsUrl = cleanJavaScriptUrl(url);
            new URL(playerJsUrl); // Validate URL
            return downloadJavaScriptCode(playerJsUrl);
        } catch (final Exception ignored) {
            // Try next strategy
        }

        // Strategy 3: Use player ID to construct URL
        try {
            final String playerId = extractPlayerId(videoId);
            final String playerJsUrl = String.format(BASE_JS_PLAYER_URL_FORMAT, playerId);
            new URL(playerJsUrl); // Validate URL
            return downloadJavaScriptCode(playerJsUrl);
        } catch (final Exception e) {
            throw new ParsingException("Could not extract JavaScript player code using any strategy", e);
        }
    }

    @Nonnull
    static String extractJavaScriptUrlWithIframeResource() throws ParsingException {
        final String iframeUrl;
        final String iframeContent;
        try {
            iframeUrl = "https://www.youtube.com/iframe_api";
            iframeContent = NewPipe.getDownloader()
                    .get(iframeUrl, Localization.DEFAULT)
                    .responseBody();
        } catch (final Exception e) {
            throw new ParsingException("Could not fetch IFrame resource", e);
        }

        try {
            final String hash = Parser.matchGroup1(
                    IFRAME_RES_JS_BASE_PLAYER_HASH_PATTERN, iframeContent);
            return String.format(BASE_JS_PLAYER_URL_FORMAT, hash);
        } catch (final Parser.RegexException e) {
            throw new ParsingException(
                    "IFrame resource didn't provide JavaScript base player's hash", e);
        }
    }

    @Nonnull
    static String extractJavaScriptUrlWithEmbedWatchPage(@Nonnull final String videoId)
            throws ParsingException {
        final String embedUrl;
        final String embedPageContent;
        try {
            embedUrl = "https://www.youtube.com/embed/" + videoId;
            embedPageContent = NewPipe.getDownloader()
                    .get(embedUrl, Localization.DEFAULT)
                    .responseBody();
        } catch (final Exception e) {
            throw new ParsingException("Could not fetch embedded watch page", e);
        }

        // Parse HTML response with jsoup and look at script elements first
        final Document doc = Jsoup.parse(embedPageContent);
        final Elements elems = doc.select("script")
                .attr("name", "player/base");
        for (final Element elem : elems) {
            // Script URLs should be relative and not absolute
            final String playerUrl = elem.attr("src");
            if (playerUrl.contains("base.js")) {
                return playerUrl;
            }
        }

        // Use regexes to match the URL in an embedded script of the HTML page
        try {
            return Parser.matchGroup1(
                    EMBEDDED_WATCH_PAGE_JS_BASE_PLAYER_URL_PATTERN, embedPageContent);
        } catch (final Parser.RegexException e) {
            throw new ParsingException(
                    "Embedded watch page didn't provide JavaScript base player's URL", e);
        }
    }

    @Nonnull
    private static String cleanJavaScriptUrl(@Nonnull final String javaScriptPlayerUrl) {
        if (javaScriptPlayerUrl.startsWith("//")) {
            // https part has to be added manually if the URL is protocol-relative
            return HTTPS + javaScriptPlayerUrl;
        } else if (javaScriptPlayerUrl.startsWith("/")) {
            // https://www.youtube.com part has to be added manually if the URL is relative to
            // YouTube's domain
            return HTTPS + "//www.youtube.com" + javaScriptPlayerUrl;
        } else {
            return javaScriptPlayerUrl;
        }
    }

    @Nonnull
    private static String downloadJavaScriptCode(@Nonnull final String javaScriptPlayerUrl)
            throws ParsingException {
        try {
            return NewPipe.getDownloader()
                    .get(javaScriptPlayerUrl, Localization.DEFAULT)
                    .responseBody();
        } catch (final Exception e) {
            throw new ParsingException("Could not get JavaScript base player's code", e);
        }
    }
}
