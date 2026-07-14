package com.example.server.auth;

public final class UserContext {

    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    public static void setUserId(Long userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static Long getUserId() {
        return CURRENT_USER_ID.get();
    }

    public static Long requireUserId() {
        Long userId = getUserId();
        if (userId == null) {
            throw new IllegalStateException("authenticated user is missing");
        }
        return userId;
    }

    public static void clear() {
        CURRENT_USER_ID.remove();
    }
}
