package com.TadeuArtesanatos.demo.customer.repository;

import com.TadeuArtesanatos.demo.customer.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByEmail(String email);

    Optional<Customer> findByCpf(String cpf);

    Optional<Customer> findByUserId(UUID userId);

    boolean existsByEmail(String email);

    boolean existsByCpf(String cpf);

    Page<Customer> findAllByDeletedAtIsNull(Pageable pageable);
}
