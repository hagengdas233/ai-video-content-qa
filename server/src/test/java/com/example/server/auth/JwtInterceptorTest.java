package com.example.server.auth;

import com.example.server.entity.User;
import com.example.server.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtInterceptorTest {

    private static final String SECRET = UUID.randomUUID().toString();

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void validJwtSetsAndAfterCompletionClearsUserContext() throws Exception {
        User user = user(7L, "alice");
        JwtUtil jwtUtil = new JwtUtil(SECRET, 60_000);
        UserMapper userMapper = mock(UserMapper.class);
        when(userMapper.selectById(7L)).thenReturn(user);
        JwtInterceptor interceptor = new JwtInterceptor(jwtUtil, userMapper);
        String token = jwtUtil.generateToken(user);
        var claims = jwtUtil.parseAndValidate(token);
        assertEquals(7L, jwtUtil.getUserId(claims));
        assertEquals("alice", claims.get("username"));
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
        HttpServletRequest request = request("Bearer " + token);
        HttpServletResponse response = response(new StringWriter());

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(7L, UserContext.getUserId());

        interceptor.afterCompletion(request, response, new Object(), null);
        assertNull(UserContext.getUserId());
        verify(userMapper).selectById(7L);
    }

    @Test
    void forgedDemoTokenIsRejected() throws Exception {
        assertUnauthorized("Bearer user_1", new JwtUtil(SECRET, 60_000));
    }

    @Test
    void tamperedJwtIsRejected() throws Exception {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 60_000);
        String token = jwtUtil.generateToken(user(1L, "alice"));
        int index = token.length() / 2;
        char replacement = token.charAt(index) == 'a' ? 'b' : 'a';
        String tampered = token.substring(0, index) + replacement + token.substring(index + 1);
        assertUnauthorized("Bearer " + tampered, jwtUtil);
    }

    @Test
    void expiredJwtIsRejected() throws Exception {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 1);
        String token = jwtUtil.generateToken(user(1L, "alice"));
        Thread.sleep(1_100);
        assertUnauthorized("Bearer " + token, jwtUtil);
    }

    @Test
    void validJwtForDeletedUserIsRejected() throws Exception {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 60_000);
        String token = jwtUtil.generateToken(user(404L, "deleted"));
        assertUnauthorized("Bearer " + token, jwtUtil);
    }

    private void assertUnauthorized(String authorization, JwtUtil jwtUtil) throws Exception {
        UserContext.setUserId(999L);
        UserMapper userMapper = mock(UserMapper.class);
        JwtInterceptor interceptor = new JwtInterceptor(jwtUtil, userMapper);
        StringWriter body = new StringWriter();
        HttpServletResponse response = response(body);

        boolean accepted = interceptor.preHandle(request(authorization), response, new Object());

        assertEquals(false, accepted);
        assertNull(UserContext.getUserId());
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private HttpServletRequest request(String authorization) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("Authorization")).thenReturn(authorization);
        return request;
    }

    private HttpServletResponse response(StringWriter body) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        return response;
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }
}
