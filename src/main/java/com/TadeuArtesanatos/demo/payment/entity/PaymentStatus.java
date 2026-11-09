package com.TadeuArtesanatos.demo.payment.entity;

public enum PaymentStatus {
    PENDING,
    APPROVED,
    DECLINED;

    /** Pagamento que ainda vale ou pode valer — bloqueia novo pagamento e troca de frete. */
    public static final java.util.Set<PaymentStatus> LIVE = java.util.EnumSet.of(PENDING, APPROVED);
}
