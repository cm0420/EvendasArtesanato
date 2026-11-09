package com.TadeuArtesanatos.demo.security.service;

import com.TadeuArtesanatos.demo.customer.entity.Customer;
import com.TadeuArtesanatos.demo.customer.repository.CustomerRepository;
import com.TadeuArtesanatos.demo.security.dto.LoginRequestDto;
import com.TadeuArtesanatos.demo.security.dto.SignupRequestDto;
import com.TadeuArtesanatos.demo.security.dto.TokenResponseDto;
import com.TadeuArtesanatos.demo.security.entity.RefreshToken;
import com.TadeuArtesanatos.demo.user.entity.Role;
import com.TadeuArtesanatos.demo.user.entity.User;
import com.TadeuArtesanatos.demo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuthRateLimiter rateLimiter;

    // Por conta: barra força bruta numa senha. Por IP: barra testar uma senha comum em muitas contas
    // (e o custo de CPU do bcrypt). A chave da conta inclui o IP para ninguém travar a conta alheia.
    static final int MAX_LOGIN_FAILURES_PER_ACCOUNT = 5;
    static final int MAX_LOGIN_FAILURES_PER_IP = 30;
    // Cadastro conta toda tentativa: impede varrer CPFs/emails pela mensagem "já cadastrado".
    static final int MAX_SIGNUPS_PER_IP = 10;

    public record AuthResult(TokenResponseDto tokens, String rawRefreshToken) {
    }

    @Transactional
    public AuthResult signup(SignupRequestDto dto, String clientIp) {
        String signupKey = "signup|" + clientIp;
        rateLimiter.checkAllowed(signupKey, MAX_SIGNUPS_PER_IP);
        rateLimiter.record(signupKey);

        if (userRepository.existsByEmail(dto.email())) {
            throw new IllegalArgumentException("Email já cadastrado");
        }

        String cpfNormalizado = dto.cpf().replaceAll("\\D", "");
        if (customerRepository.existsByCpf(cpfNormalizado)) {
            throw new IllegalArgumentException("CPF já cadastrado");
        }

        User user = User.builder()
                .email(dto.email())
                .password(passwordEncoder.encode(dto.password()))
                .role(Role.CLIENTE)
                .build();
        userRepository.save(user);

        Customer customer = Customer.builder()
                .user(user)
                .firstName(dto.firstName())
                .lastName(dto.lastName())
                .email(dto.email())
                .phone(dto.phone())
                .cpf(cpfNormalizado)
                .build();
        customerRepository.save(customer);

        return issueTokens(user);
    }

    @Transactional
    public AuthResult login(LoginRequestDto dto, String clientIp) {
        String ipKey = "login-ip|" + clientIp;
        String accountKey = "login|" + clientIp + "|" + dto.email().toLowerCase();
        rateLimiter.checkAllowed(ipKey, MAX_LOGIN_FAILURES_PER_IP);
        rateLimiter.checkAllowed(accountKey, MAX_LOGIN_FAILURES_PER_ACCOUNT);

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(dto.email(), dto.password())
            );
        } catch (AuthenticationException e) {
            rateLimiter.record(ipKey);
            rateLimiter.record(accountKey);
            throw e;
        }
        // só zera a da conta: um atacante com uma conta válida não pode zerar a contagem do IP
        rateLimiter.reset(accountKey);

        User user = userRepository.findByEmail(dto.email())
                .orElseThrow(() -> new IllegalArgumentException("Credenciais inválidas"));

        return issueTokens(user);
    }

    @Transactional
    public AuthResult refresh(String rawRefreshToken) {
        RefreshToken current = refreshTokenService.validate(rawRefreshToken);
        refreshTokenService.revoke(current); // rotação: o refresh usado não pode ser reusado

        return issueTokens(current.getUser());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        RefreshToken current = refreshTokenService.validate(rawRefreshToken);
        refreshTokenService.revoke(current);
    }

    private AuthResult issueTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String rawRefreshToken = refreshTokenService.issue(user);

        TokenResponseDto tokenResponse = TokenResponseDto.of(
                accessToken, jwtService.getAccessTokenExpirationMs() / 1000
        );

        return new AuthResult(tokenResponse, rawRefreshToken);
    }
}
