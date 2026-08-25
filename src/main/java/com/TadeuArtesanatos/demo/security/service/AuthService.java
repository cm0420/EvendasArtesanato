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

    public record AuthResult(TokenResponseDto tokens, String rawRefreshToken) {
    }

    @Transactional
    public AuthResult signup(SignupRequestDto dto) {
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
    public AuthResult login(LoginRequestDto dto) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(dto.email(), dto.password())
        );

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
