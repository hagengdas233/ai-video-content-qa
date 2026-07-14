package com.example.server.auth;

import com.example.server.entity.User;
import com.example.server.mapper.UserMapper;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class JwtInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;

    public JwtInterceptor(JwtUtil jwtUtil, UserMapper userMapper) {
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws IOException {
        UserContext.clear();
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null
                || authorization.length() <= BEARER_PREFIX.length()
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return reject(response, "missing or invalid Authorization header");
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        Long userId;
        try {
            userId = jwtUtil.getUserId(jwtUtil.parseAndValidate(token));
        } catch (ExpiredJwtException e) {
            return reject(response, "token expired");
        } catch (JwtException | IllegalArgumentException e) {
            return reject(response, "invalid token");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            return reject(response, "invalid token user");
        }

        UserContext.setUserId(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        UserContext.clear();
    }

    private boolean reject(HttpServletResponse response, String message) throws IOException {
        UserContext.clear();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(message);
        return false;
    }
}
