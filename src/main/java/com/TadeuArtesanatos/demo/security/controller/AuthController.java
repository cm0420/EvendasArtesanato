package com.TadeuArtesanatos.demo.security.controller;
import com.TadeuArtesanatos.demo.security.dto.LoginRequestDto;
import com.TadeuArtesanatos.demo.security.dto.SignupRequestDto;
import com.TadeuArtesanatos.demo.security.dto.TokenResponseDto;
import com.TadeuArtesanatos.demo.security.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping(
        value = "/auth",
        produces = MediaType.APPLICATION_JSON_VALUE
)
@RequiredArgsConstructor
public class AuthController {
    private static final String REFRESH_COOKIE_NAME = "refresh_token";
    private static final long REFRESH_COOKIE_MAX_AGE_SECONDS = 7 * 24 * 60 * 60; // 7 dias
    private final AuthService authService;
    @Value("${app.cookie.secure:true}")
    private boolean cookieSecure;
    @PostMapping(value = "/signup", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TokenResponseDto> signup(@Valid @RequestBody SignupRequestDto dto, HttpServletRequest request) {
        AuthService.AuthResult result = authService.signup(dto, request.getRemoteAddr());
        return respondWithTokens(result, HttpStatus.CREATED);
    }
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TokenResponseDto> login(@Valid @RequestBody LoginRequestDto dto, HttpServletRequest request) {
        AuthService.AuthResult result = authService.login(dto, request.getRemoteAddr());
        return respondWithTokens(result, HttpStatus.OK);
    }
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponseDto> refresh(
            @CookieValue(name = REFRESH_COOKIE_NAME) String refreshToken
    ) {
        AuthService.AuthResult result = authService.refresh(refreshToken);
        return respondWithTokens(result, HttpStatus.OK);
    }
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE_NAME) String refreshToken,
            HttpServletResponse response
    ) {
        authService.logout(refreshToken);
        ResponseCookie expiredCookie = buildRefreshCookie("", 0);
        response.addHeader("Set-Cookie", expiredCookie.toString());
        return ResponseEntity.noContent().build();
    }
    // ---- helper ----
    private ResponseEntity<TokenResponseDto> respondWithTokens(AuthService.AuthResult result, HttpStatus status) {
        ResponseCookie refreshCookie = buildRefreshCookie(result.rawRefreshToken(), REFRESH_COOKIE_MAX_AGE_SECONDS);
        return ResponseEntity.status(status)
                .header("Set-Cookie", refreshCookie.toString())
                .body(result.tokens());
    }
    private ResponseCookie buildRefreshCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/auth")
                .maxAge(maxAgeSeconds)
                .build();
    }
}