package com.TadeuArtesanatos.demo.customer.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "addresses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "label", nullable = false, length = 50)
    private String label;

    @Column(name = "recipient_name", nullable = false, length = 100)
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false, length = 20)
    private String recipientPhone;

    @Column(name = "zip_code", nullable = false, length = 8)
    private String zipCode;

    @Column(name = "street", nullable = false)
    private String street;

    @Column(name = "number", nullable = false)
    private String number;

    @Column(name = "complement")
    private String complement;

    @Column(name = "neighborhood", nullable = false, length = 100)
    private String neighborhood;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    @Column(name = "state", nullable = false, length = 2)
    private String state;

    @Builder.Default
    @Column(name = "country", nullable = false, length = 50)
    private String country = "BR";

    @Builder.Default
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    // ---- Regra de negócio da entidade ----

    public void markAsDefault() {
        this.isDefault = true;
    }
}
