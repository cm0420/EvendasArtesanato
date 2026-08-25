package com.TadeuArtesanatos.demo.security.service;

import com.TadeuArtesanatos.demo.security.entity.RefreshToken;
import com.TadeuArtesanatos.demo.security.repository.RefreshTokenRepository;
import com.TadeuArtesanatos.demo.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final long REFRESH_TOKEN_EXPIRATION_DAYS = 7;
    private static final int RAW_TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public String issue(User user) {
        String rawToken = generateRawToken();

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(passwordEncoder.encode(rawToken))
                .expiresAt(LocalDateTime.now().plusDays(REFRESH_TOKEN_EXPIRATION_DAYS))
                .build();

        refreshTokenRepository.save(refreshToken);
        return rawToken;
    }

    @Transactional(readOnly = true)
    public RefreshToken validate(String rawToken) {
        return refreshTokenRepository.findAll().stream()
                .filter(rt -> passwordEncoder.matches(rawToken, rt.getTokenHash()))
                .filter(RefreshToken::isValid)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Refresh token inválido ou expirado"));
    }

    @Transactional
    public void revoke(RefreshToken refreshToken) {
        refreshToken.revoke();
    }

    private String generateRawToken() {
        byte[] randomBytes = new byte[RAW_TOKEN_BYTES];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
