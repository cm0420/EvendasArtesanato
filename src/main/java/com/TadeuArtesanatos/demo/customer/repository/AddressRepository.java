package com.TadeuArtesanatos.demo.customer.repository;

import com.TadeuArtesanatos.demo.customer.entity.Address;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AddressRepository extends JpaRepository<Address, UUID> {

    List<Address> findAllByCustomerId(UUID customerId);

    Optional<Address> findByCustomerIdAndIsDefaultTrue(UUID customerId);
}
