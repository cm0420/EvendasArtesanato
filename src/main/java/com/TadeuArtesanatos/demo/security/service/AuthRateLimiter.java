package com.TadeuArtesanatos.demo.security.service;

import com.TadeuArtesanatos.demo.common.exception.TooManyRequestsException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conta tentativas por chave numa janela de 15 minutos (login, cadastro).
 *
 * shortcut: contagem em memória, por instância; com mais de uma réplica, mover para Redis/bucket4j.
 */
@Service
public class AuthRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(15);

    private record Attempts(int count, Instant windowStart) {
        boolean expired() {
            return windowStart.plus(WINDOW).isBefore(Instant.now());
        }
    }

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public void checkAllowed(String key, int max) {
        Attempts a = attempts.get(key);
        if (a != null && !a.expired() && a.count() >= max) {
            throw new TooManyRequestsException("Muitas tentativas. Tente novamente em alguns minutos.");
        }
    }

    public void record(String key) {
        attempts.merge(key, new Attempts(1, Instant.now()),
                (old, fresh) -> old.expired() ? fresh : new Attempts(old.count() + 1, old.windowStart()));
    }

    public void reset(String key) {
        attempts.remove(key);
    }

    @Scheduled(fixedRate = 15 * 60 * 1000)
    public void purgeExpired() {
        attempts.values().removeIf(Attempts::expired);
    }
}
