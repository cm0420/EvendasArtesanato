package com.TadeuArtesanatos.demo.customer.entity;

import com.TadeuArtesanatos.demo.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "customers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "cpf", nullable = false, unique = true, length = 11)
    private String cpf;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder.Default
    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Address> addresses = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ---- Regras de negócio da entidade ----

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return this.deletedAt == null;
    }

    public void addAddress(Address address) {
        this.addresses.add(address);
        address.setCustomer(this);
    }
}
