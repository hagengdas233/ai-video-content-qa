package com.example.server.utils;

public final class AnalysisRedisKeys {

    private static final String CONTENT_HASH_PREFIX = "media:md5:";
    private static final String ACTIVE_PREFIX = "analysis:active:";
    private static final String ANALYSIS_LOCK_PREFIX = "lock:analysis:";
    private static final String CONTENT_HASH_LOCK_PREFIX = "lock:content-hash:";

    private AnalysisRedisKeys() {
    }

    public static String contentHash(Long mediaId) {
        return CONTENT_HASH_PREFIX + requireId(mediaId, "mediaId");
    }

    public static String active(Long userId, String contentHash) {
        return ACTIVE_PREFIX + userScope(userId) + ":" + requireContentHash(contentHash);
    }

    public static String analysisLock(Long userId, String contentHash) {
        return ANALYSIS_LOCK_PREFIX + userScope(userId) + ":" + requireContentHash(contentHash);
    }

    public static String contentHashLock(Long mediaId) {
        return CONTENT_HASH_LOCK_PREFIX + requireId(mediaId, "mediaId");
    }

    public static String legacyContentHash(Long mediaId) {
        return "legacy-media:" + requireId(mediaId, "mediaId");
    }

    public static boolean isMd5(String value) {
        return value != null && value.matches("[a-f0-9]{32}");
    }

    public static boolean isSupportedContentHash(String value) {
        return isMd5(value) || (value != null && value.matches("legacy-media:\\d+"));
    }

    public static boolean isSupportedContentHash(Long mediaId, String value) {
        return isMd5(value)
                || (mediaId != null && mediaId > 0 && legacyContentHash(mediaId).equals(value));
    }

    private static String userScope(Long userId) {
        return String.valueOf(requireId(userId, "userId"));
    }

    private static long requireId(Long id, String name) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return id;
    }

    private static String requireContentHash(String contentHash) {
        if (!isSupportedContentHash(contentHash)) {
            throw new IllegalArgumentException("unsupported contentHash");
        }
        return contentHash;
    }
}
