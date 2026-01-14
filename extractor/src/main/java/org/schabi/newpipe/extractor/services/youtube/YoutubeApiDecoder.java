package org.schabi.newpipe.extractor.services.youtube;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.localization.Localization;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decoder for YouTube signature and throttling parameters using the PipePipe API.
 *
 * <p>
 * This class replaces the local JavaScript-based decoding with API calls to
 * https://api.pipepipe.dev/decoder/decode
 * </p>
 *
 * <p>
 * Updated for 2026 with improved error handling, retry logic, and cache management
 * to prevent "unknown error" issues.
 * </p>
 */
public final class YoutubeApiDecoder {

    private static final String API_BASE_URL = "https://api.pipepipe.dev/decoder/decode";
    private static final String USER_AGENT = "MetrolistExtractor/2026.1";

    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 500;
    private static final int MAX_CACHE_SIZE = 1000;
    private static final long CACHE_EXPIRY_MS = 3600000; // 1 hour

    // Thread-safe cache for decoded parameters with expiry tracking
    @Nonnull
    private static final Map<String, CacheEntry> DECODE_CACHE = new ConcurrentHashMap<>();

    private static class CacheEntry {
        final String value;
        final long timestamp;

        CacheEntry(String value) {
            this.value = value;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_EXPIRY_MS;
        }
    }

    private YoutubeApiDecoder() {
    }

    /**
     * Decode a signature parameter using the PipePipe API.
     *
     * @param playerId  the YouTube player ID (8-character hash)
     * @param signature the obfuscated signature to decode
     * @return the deobfuscated signature
     * @throws ParsingException if the API call fails or returns invalid data
     */
    @Nonnull
    static String decodeSignature(@Nonnull final String playerId,
                                  @Nonnull final String signature) throws ParsingException {
        return decode(playerId, "sig", signature);
    }

    /**
     * Decode a throttling parameter (n parameter) using the PipePipe API.
     *
     * @param playerId          the YouTube player ID (8-character hash)
     * @param nParameter        the obfuscated n parameter to decode
     * @return the deobfuscated n parameter
     * @throws ParsingException if the API call fails or returns invalid data
     */
    @Nonnull
    static String decodeThrottlingParameter(@Nonnull final String playerId,
                                            @Nonnull final String nParameter)
            throws ParsingException {
        return decode(playerId, "n", nParameter);
    }

    /**
     * Generic decode method that calls the PipePipe API with retry logic.
     *
     * @param playerId   the YouTube player ID (8-character hash)
     * @param paramType  the parameter type ("sig" or "n")
     * @param value      the obfuscated value to decode
     * @return the deobfuscated value
     * @throws ParsingException if the API call fails or returns invalid data
     */
    @Nonnull
    private static String decode(@Nonnull final String playerId,
                                 @Nonnull final String paramType,
                                 @Nonnull final String value) throws ParsingException {
        // Check cache first
        final String cacheKey = playerId + ":" + paramType + ":" + value;
        final CacheEntry cachedEntry = DECODE_CACHE.get(cacheKey);
        if (cachedEntry != null && !cachedEntry.isExpired()) {
            return cachedEntry.value;
        }

        // Clean up expired entries if cache is getting large
        cleanupCacheIfNeeded();

        ParsingException lastException = null;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                // Build API URL
                final String encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8.name());
                final String url = API_BASE_URL + "?player=" + playerId + "&" + paramType + "=" + encodedValue;

                // Set headers
                final Map<String, java.util.List<String>> headers = new HashMap<>();
                headers.put("User-Agent", java.util.Collections.singletonList(USER_AGENT));
                headers.put("Accept", java.util.Collections.singletonList("application/json"));
                headers.put("Cache-Control", java.util.Collections.singletonList("no-cache"));

                // Make API call
                final Response response = NewPipe.getDownloader().get(url, headers, Localization.DEFAULT);

                // Check HTTP status
                final int statusCode = response.responseCode();
                if (statusCode >= 500) {
                    throw new IOException("Server error: " + statusCode);
                }
                if (statusCode >= 400) {
                    throw new ParsingException("Client error: " + statusCode);
                }

                // Parse response
                final String responseBody = response.responseBody();
                if (responseBody == null || responseBody.isEmpty()) {
                    throw new ParsingException("Empty response from API");
                }

                final JsonObject jsonResponse = JsonParser.object().from(responseBody);

                // Validate response structure
                final String responseType = jsonResponse.getString("type");
                if (!"result".equals(responseType)) {
                    if ("error".equals(responseType)) {
                        final String errorMsg = jsonResponse.getString("message", "Unknown API error");
                        throw new ParsingException("API error: " + errorMsg);
                    }
                    throw new ParsingException("API returned unexpected type: " + responseType);
                }

                // Extract decoded value
                final JsonArray responses = jsonResponse.getArray("responses");
                if (responses == null || responses.isEmpty()) {
                    throw new ParsingException("API returned empty responses array");
                }

                final JsonObject firstResponse = responses.getObject(0);
                if (!"result".equals(firstResponse.getString("type"))) {
                    throw new ParsingException("API response item has unexpected type: " + firstResponse.getString("type"));
                }

                final JsonObject data = firstResponse.getObject("data");
                if (data == null) {
                    throw new ParsingException("API response missing data object");
                }

                final String decodedValue = data.getString(value);

                if (decodedValue == null || decodedValue.isEmpty()) {
                    throw new ParsingException("API returned empty decoded value for: " + value);
                }

                // Cache the result
                DECODE_CACHE.put(cacheKey, new CacheEntry(decodedValue));

                return decodedValue;

            } catch (final IOException e) {
                lastException = new ParsingException("Failed to call decode API (attempt " + (attempt + 1) + ")", e);
                // Retry on IO errors
                if (attempt < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ParsingException("Interrupted during retry delay", ie);
                    }
                }
            } catch (final JsonParserException e) {
                lastException = new ParsingException("Failed to parse API response", e);
                // Don't retry on parse errors - likely a permanent issue
                break;
            } catch (final ParsingException e) {
                lastException = e;
                // Retry on certain parsing errors
                if (e.getMessage() != null && e.getMessage().contains("Server error")) {
                    if (attempt < MAX_RETRIES - 1) {
                        try {
                            Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new ParsingException("Interrupted during retry delay", ie);
                        }
                    }
                } else {
                    break;
                }
            } catch (final Exception e) {
                lastException = new ParsingException("Unexpected error during decoding", e);
                break;
            }
        }

        throw lastException != null ? lastException : new ParsingException("Failed to decode after " + MAX_RETRIES + " attempts");
    }

    /**
     * Clean up expired cache entries if cache size exceeds limit.
     */
    private static void cleanupCacheIfNeeded() {
        if (DECODE_CACHE.size() > MAX_CACHE_SIZE) {
            DECODE_CACHE.entrySet().removeIf(entry -> entry.getValue().isExpired());
            // If still too large, remove oldest entries
            if (DECODE_CACHE.size() > MAX_CACHE_SIZE) {
                int toRemove = DECODE_CACHE.size() - MAX_CACHE_SIZE / 2;
                DECODE_CACHE.entrySet().stream()
                        .sorted((a, b) -> Long.compare(a.getValue().timestamp, b.getValue().timestamp))
                        .limit(toRemove)
                        .forEach(entry -> DECODE_CACHE.remove(entry.getKey()));
            }
        }
    }

    /**
     * Clear the decode cache.
     */
    static void clearCache() {
        DECODE_CACHE.clear();
    }

    /**
     * Get the current cache size.
     *
     * @return the number of cached decode results
     */
    static int getCacheSize() {
        return DECODE_CACHE.size();
    }

    /**
     * Batch decode multiple signatures and throttling parameters in a single API call with retry logic.
     *
     * @param playerId        the YouTube player ID (8-character hash)
     * @param signatureParams list of obfuscated signatures to decode (can be null or empty)
     * @param nParams         list of obfuscated n parameters to decode (can be null or empty)
     * @return a BatchDecodeResult containing the decoded values
     * @throws ParsingException if the API call fails or returns invalid data
     */
    @Nonnull
    static BatchDecodeResult decodeBatch(@Nonnull final String playerId,
                                         @Nullable final List<String> signatureParams,
                                         @Nullable final List<String> nParams)
            throws ParsingException {
        // Validate input
        final boolean hasSigs = signatureParams != null && !signatureParams.isEmpty();
        final boolean hasNs = nParams != null && !nParams.isEmpty();

        if (!hasSigs && !hasNs) {
            return new BatchDecodeResult(new HashMap<>(), new HashMap<>());
        }

        // Check cache first and collect uncached values
        final Map<String, String> sigResults = new HashMap<>();
        final Map<String, String> nResults = new HashMap<>();
        final List<String> uncachedSigs = new ArrayList<>();
        final List<String> uncachedNs = new ArrayList<>();

        if (hasSigs) {
            for (final String sig : signatureParams) {
                final String cacheKey = playerId + ":sig:" + sig;
                final CacheEntry cachedEntry = DECODE_CACHE.get(cacheKey);
                if (cachedEntry != null && !cachedEntry.isExpired()) {
                    sigResults.put(sig, cachedEntry.value);
                } else {
                    uncachedSigs.add(sig);
                }
            }
        }

        if (hasNs) {
            for (final String n : nParams) {
                final String cacheKey = playerId + ":n:" + n;
                final CacheEntry cachedEntry = DECODE_CACHE.get(cacheKey);
                if (cachedEntry != null && !cachedEntry.isExpired()) {
                    nResults.put(n, cachedEntry.value);
                } else {
                    uncachedNs.add(n);
                }
            }
        }

        // If all values are cached, return immediately
        if (uncachedSigs.isEmpty() && uncachedNs.isEmpty()) {
            return new BatchDecodeResult(sigResults, nResults);
        }

        // Clean up expired entries if cache is getting large
        cleanupCacheIfNeeded();

        ParsingException lastException = null;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                // Build API URL with batch parameters
                final StringBuilder urlBuilder = new StringBuilder(API_BASE_URL);
                urlBuilder.append("?player=").append(playerId);

                if (!uncachedNs.isEmpty()) {
                    urlBuilder.append("&n=");
                    for (int i = 0; i < uncachedNs.size(); i++) {
                        if (i > 0) {
                            urlBuilder.append(',');
                        }
                        urlBuilder.append(URLEncoder.encode(uncachedNs.get(i), StandardCharsets.UTF_8.name()));
                    }
                }

                if (!uncachedSigs.isEmpty()) {
                    urlBuilder.append("&sig=");
                    for (int i = 0; i < uncachedSigs.size(); i++) {
                        if (i > 0) {
                            urlBuilder.append(',');
                        }
                        urlBuilder.append(URLEncoder.encode(uncachedSigs.get(i), StandardCharsets.UTF_8.name()));
                    }
                }

                // Set headers
                final Map<String, java.util.List<String>> headers = new HashMap<>();
                headers.put("User-Agent", java.util.Collections.singletonList(USER_AGENT));
                headers.put("Accept", java.util.Collections.singletonList("application/json"));
                headers.put("Cache-Control", java.util.Collections.singletonList("no-cache"));

                // Make API call
                final Response response = NewPipe.getDownloader().get(urlBuilder.toString(), headers, Localization.DEFAULT);

                // Check HTTP status
                final int statusCode = response.responseCode();
                if (statusCode >= 500) {
                    throw new IOException("Server error: " + statusCode);
                }
                if (statusCode >= 400) {
                    throw new ParsingException("Client error: " + statusCode);
                }

                // Parse response
                final String responseBody = response.responseBody();
                if (responseBody == null || responseBody.isEmpty()) {
                    throw new ParsingException("Empty response from batch API");
                }

                final JsonObject jsonResponse = JsonParser.object().from(responseBody);

                // Validate response structure
                final String responseType = jsonResponse.getString("type");
                if (!"result".equals(responseType)) {
                    if ("error".equals(responseType)) {
                        final String errorMsg = jsonResponse.getString("message", "Unknown API error");
                        throw new ParsingException("Batch API error: " + errorMsg);
                    }
                    throw new ParsingException("API returned unexpected type: " + responseType);
                }

                final JsonArray responses = jsonResponse.getArray("responses");
                if (responses == null) {
                    throw new ParsingException("API returned null responses array");
                }

                // Process n parameters first (if present)
                int responseIndex = 0;
                if (!uncachedNs.isEmpty()) {
                    if (responseIndex >= responses.size()) {
                        throw new ParsingException("Missing n parameter response in batch result");
                    }
                    final JsonObject nResponse = responses.getObject(responseIndex++);
                    if (!"result".equals(nResponse.getString("type"))) {
                        throw new ParsingException("N parameter response has unexpected type: " + nResponse.getString("type"));
                    }

                    final JsonObject nData = nResponse.getObject("data");
                    if (nData == null) {
                        throw new ParsingException("N parameter response missing data object");
                    }

                    for (final String nParam : uncachedNs) {
                        final String decodedValue = nData.getString(nParam);
                        if (decodedValue == null || decodedValue.isEmpty()) {
                            throw new ParsingException("API returned empty decoded value for n parameter: " + nParam);
                        }
                        nResults.put(nParam, decodedValue);
                        // Cache the result
                        DECODE_CACHE.put(playerId + ":n:" + nParam, new CacheEntry(decodedValue));
                    }
                }

                // Process signature parameters (if present)
                if (!uncachedSigs.isEmpty()) {
                    if (responseIndex >= responses.size()) {
                        throw new ParsingException("Missing signature response in batch result");
                    }
                    final JsonObject sigResponse = responses.getObject(responseIndex);
                    if (!"result".equals(sigResponse.getString("type"))) {
                        throw new ParsingException("Signature response has unexpected type: " + sigResponse.getString("type"));
                    }

                    final JsonObject sigData = sigResponse.getObject("data");
                    if (sigData == null) {
                        throw new ParsingException("Signature response missing data object");
                    }

                    for (final String sig : uncachedSigs) {
                        final String decodedValue = sigData.getString(sig);
                        if (decodedValue == null || decodedValue.isEmpty()) {
                            throw new ParsingException("API returned empty decoded value for signature: " + sig);
                        }
                        sigResults.put(sig, decodedValue);
                        // Cache the result
                        DECODE_CACHE.put(playerId + ":sig:" + sig, new CacheEntry(decodedValue));
                    }
                }

                return new BatchDecodeResult(sigResults, nResults);

            } catch (final IOException e) {
                lastException = new ParsingException("Failed to call batch decode API (attempt " + (attempt + 1) + ")", e);
                if (attempt < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ParsingException("Interrupted during retry delay", ie);
                    }
                }
            } catch (final JsonParserException e) {
                lastException = new ParsingException("Failed to parse batch API response", e);
                break;
            } catch (final ParsingException e) {
                lastException = e;
                if (e.getMessage() != null && e.getMessage().contains("Server error")) {
                    if (attempt < MAX_RETRIES - 1) {
                        try {
                            Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new ParsingException("Interrupted during retry delay", ie);
                        }
                    }
                } else {
                    break;
                }
            } catch (final Exception e) {
                lastException = new ParsingException("Unexpected error during batch decoding", e);
                break;
            }
        }

        throw lastException != null ? lastException : new ParsingException("Failed to batch decode after " + MAX_RETRIES + " attempts");
    }

    /**
     * Result class for batch decode operations.
     */
    public static class BatchDecodeResult {
        private final Map<String, String> signatures;
        private final Map<String, String> nParameters;

        BatchDecodeResult(@Nonnull final Map<String, String> signatures,
                          @Nonnull final Map<String, String> nParameters) {
            this.signatures = signatures;
            this.nParameters = nParameters;
        }

        @Nonnull
        public Map<String, String> getSignatures() {
            return signatures;
        }

        @Nonnull
        public Map<String, String> getNParameters() {
            return nParameters;
        }
    }
}
