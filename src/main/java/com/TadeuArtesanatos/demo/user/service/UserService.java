package com.TadeuArtesanatos.demo.user.service;

import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import com.TadeuArtesanatos.demo.user.dto.UserPasswordUpdateDto;
import com.TadeuArtesanatos.demo.user.dto.UserRequestDto;
import com.TadeuArtesanatos.demo.user.dto.UserResponseDto;
import com.TadeuArtesanatos.demo.user.dto.UserRoleUpdateDto;
import com.TadeuArtesanatos.demo.user.dto.UserUpdateRequestDto;
import com.TadeuArtesanatos.demo.user.entity.Role;
import com.TadeuArtesanatos.demo.user.entity.User;
import com.TadeuArtesanatos.demo.user.mapper.UserMapper;
import com.TadeuArtesanatos.demo.user.repository.UserRepository;
import com.TadeuArtesanatos.demo.security.service.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public UserResponseDto createUser(UserRequestDto dto) {
        if (userRepository.existsByEmail(dto.email())) {
            throw new IllegalArgumentException("Email já cadastrado");
        }

        User user = User.builder()
                .email(dto.email())
                .password(passwordEncoder.encode(dto.password()))
                .role(Role.valueOf(dto.role()))
                .build();

        User saved = userRepository.save(user);
        return userMapper.toResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public Page<UserResponseDto> listUsers(Pageable pageable, Role role) {
        Page<User> page = (role != null)
                ? userRepository.findAllByRoleAndDeletedAtIsNull(role, pageable)
                : userRepository.findAllByDeletedAtIsNull(pageable);

        return page.map(userMapper::toResponseDto);
    }

    @Transactional(readOnly = true)
    public UserResponseDto findById(UUID id) {
        User user = getActiveUserOrThrow(id);
        return userMapper.toResponseDto(user);
    }

    @Transactional
    public UserResponseDto updateUser(UUID id, UserUpdateRequestDto dto) {
        User user = getActiveUserOrThrow(id);

        boolean emailPertenceAOutroUsuario = userRepository.findByEmail(dto.email())
                .map(existing -> !existing.getId().equals(id))
                .orElse(false);

        if (emailPertenceAOutroUsuario) {
            throw new IllegalArgumentException("Email já está em uso por outro usuário");
        }

        user.setEmail(dto.email());

        return userMapper.toResponseDto(user);
    }

    @Transactional
    public UserResponseDto updateRole(UUID currentUserId, UUID id, UserRoleUpdateDto dto) {
        // Sem isso o único admin podia se rebaixar e ninguém mais administrava a loja.
        if (currentUserId.equals(id)) {
            throw new IllegalArgumentException("Você não pode alterar o seu próprio papel");
        }
        User user = getActiveUserOrThrow(id);
        user.setRole(Role.valueOf(dto.role()));
        return userMapper.toResponseDto(user);
    }

    @Transactional
    public void changePassword(UUID id, UserPasswordUpdateDto dto) {
        User user = getActiveUserOrThrow(id);

        if (!passwordEncoder.matches(dto.currentPassword(), user.getPassword())) {
            throw new IllegalArgumentException("Senha atual incorreta");
        }

        if (!dto.newPassword().equals(dto.confirmPassword())) {
            throw new IllegalArgumentException("Confirmação de senha não confere");
        }

        user.setPassword(passwordEncoder.encode(dto.newPassword()));
        // Quem roubou o cookie de refresh continuaria logado para sempre (cada refresh renova 7 dias).
        refreshTokenService.revokeAllForUser(user.getId());
    }

    @Transactional
    public void softDeleteUser(UUID currentUserId, UUID id) {
        if (currentUserId.equals(id)) {
            throw new IllegalArgumentException("Você não pode excluir a sua própria conta por aqui");
        }
        User user = getActiveUserOrThrow(id);
        user.softDelete();
        refreshTokenService.revokeAllForUser(user.getId());
    }

    @Transactional(readOnly = true)
    public User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }

    private User getActiveUserOrThrow(UUID id) {
        return userRepository.findById(id)
                .filter(User::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));
    }
}
