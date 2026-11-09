package com.TadeuArtesanatos.demo.user.controller;

import com.TadeuArtesanatos.demo.user.dto.UserPasswordUpdateDto;
import com.TadeuArtesanatos.demo.user.dto.UserRequestDto;
import com.TadeuArtesanatos.demo.user.dto.UserResponseDto;
import com.TadeuArtesanatos.demo.user.dto.UserRoleUpdateDto;
import com.TadeuArtesanatos.demo.user.dto.UserUpdateRequestDto;
import com.TadeuArtesanatos.demo.user.entity.Role;
import com.TadeuArtesanatos.demo.user.entity.User;
import com.TadeuArtesanatos.demo.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
// sem consumes na classe: GET não tem corpo nem Content-Type e respondia 415
@RequestMapping(value = "/api/users", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public ResponseEntity<Page<UserResponseDto>> listUsers(
            Pageable pageable,
            @RequestParam(required = false) Role role
    ) {
        return ResponseEntity.ok(userService.listUsers(pageable, role));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponseDto> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.findById(id));
    }

    @PostMapping
    public ResponseEntity<UserResponseDto> createUser(@Valid @RequestBody UserRequestDto dto) {
        UserResponseDto created = userService.createUser(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserResponseDto> updateUser(
            @PathVariable UUID id,
            @Valid @RequestBody UserUpdateRequestDto dto
    ) {
        return ResponseEntity.ok(userService.updateUser(id, dto));
    }

    @PatchMapping("/{id}/role")
    public ResponseEntity<UserResponseDto> updateRole(
            @AuthenticationPrincipal User currentUser,
            @PathVariable UUID id,
            @Valid @RequestBody UserRoleUpdateDto dto
    ) {
        return ResponseEntity.ok(userService.updateRole(currentUser.getId(), id, dto));
    }

    @PatchMapping("/{id}/senha")
    @PreAuthorize("hasRole('ADMIN') or #id == authentication.principal.id")
    public ResponseEntity<Void> changePassword(
            @PathVariable UUID id,
            @Valid @RequestBody UserPasswordUpdateDto dto
    ) {
        userService.changePassword(id, dto);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> softDeleteUser(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        userService.softDeleteUser(currentUser.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
