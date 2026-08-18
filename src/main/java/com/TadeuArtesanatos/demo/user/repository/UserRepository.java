package com.TadeuArtesanatos.demo.user.repository;

import com.TadeuArtesanatos.demo.user.entity.Role;
import com.TadeuArtesanatos.demo.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    Page<User> findAllByDeletedAtIsNull(Pageable pageable);
    Page<User> findAllByRoleAndDeletedAtIsNull(Role role, Pageable pageable);


}
