package com.TadeuArtesanatos.demo.security.service;

import com.TadeuArtesanatos.demo.common.exception.TooManyRequestsException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bloqueia login após MAX_FAILURES senhas erradas na janela, por IP + email.
 * A chave inclui o IP para que um atacante não consiga travar a conta de outra pessoa
 * de qualquer lugar só errando a senha dela.
 *
 * shortcut: contagem em memória, por instância; com mais de uma réplica, mover para Redis/bucket4j.
 * shortcut: usa o IP da conexão; atrás de proxy/load balancer, configurar
 * server.forward-headers-strategy=native para o IP real do cliente.
 */
@Service
public class LoginAttemptService {

    static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private record Failures(int count, Instant windowStart) {
        boolean expired() {
            return windowStart.plus(WINDOW).isBefore(Instant.now());
        }
    }

    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    public void checkAllowed(String key) {
        Failures f = failures.get(key);
        if (f != null && !f.expired() && f.count() >= MAX_FAILURES) {
            throw new TooManyRequestsException("Muitas tentativas de login. Tente novamente em alguns minutos.");
        }
    }

    public void recordFailure(String key) {
        failures.merge(key, new Failures(1, Instant.now()),
                (old, fresh) -> old.expired() ? fresh : new Failures(old.count() + 1, old.windowStart()));
    }

    public void recordSuccess(String key) {
        failures.remove(key);
    }

    @Scheduled(fixedRate = 15 * 60 * 1000)
    public void purgeExpired() {
        failures.values().removeIf(Failures::expired);
    }
}
