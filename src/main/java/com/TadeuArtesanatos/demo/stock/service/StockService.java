package com.TadeuArtesanatos.demo.stock.service;

import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import com.TadeuArtesanatos.demo.catalog.repository.PieceRepository;
import com.TadeuArtesanatos.demo.common.exception.InvalidStateException;
import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import com.TadeuArtesanatos.demo.stock.dto.ReserveStockRequestDto;
import com.TadeuArtesanatos.demo.stock.dto.StockReservationResponseDto;
import com.TadeuArtesanatos.demo.stock.entity.ReservationStatus;
import com.TadeuArtesanatos.demo.stock.entity.StockAdjustment;
import com.TadeuArtesanatos.demo.stock.entity.StockReservation;
import com.TadeuArtesanatos.demo.stock.mapper.StockReservationMapper;
import com.TadeuArtesanatos.demo.stock.repository.StockAdjustmentRepository;
import com.TadeuArtesanatos.demo.stock.repository.StockReservationRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StockService {

    private final StockReservationRepository reservationRepository;
    private final PieceRepository pieceRepository;
    private final StockReservationMapper reservationMapper;
    private final StockAdjustmentRepository stockAdjustmentRepository;
    private final EntityManager entityManager;

    @Value("${app.stock.reservation-expiration-minutes:20}")
    private long reservationExpirationMinutes;

    /**
     * Reserva estoque de uma peça, com lock pessimista.
     * Decrementa o stock da peça imediatamente — a reserva é o que permite
     * devolver essa quantidade depois, se expirar ou for cancelada.
     */
    @Transactional
    public StockReservationResponseDto reserve(ReserveStockRequestDto dto) {
        Piece piece = lockPiece(dto.pieceId());
        if (!piece.isActive()) {
            throw new ResourceNotFoundException("Peça não encontrada");
        }

        if (piece.getStock() < dto.quantity()) {
            throw new InvalidStateException("Peça esgotada — estoque insuficiente para a quantidade solicitada");
        }

        piece.setStock(piece.getStock() - dto.quantity());

        StockReservation reservation = StockReservation.builder()
                .piece(piece)
                .quantity(dto.quantity())
                .orderId(dto.orderId())
                .status(ReservationStatus.ACTIVE)
                .expiresAt(LocalDateTime.now().plusMinutes(reservationExpirationMinutes))
                .build();

        return reservationMapper.toResponseDto(reservationRepository.save(reservation));
    }

    /**
     * Confirma a reserva (pagamento aprovado) — estoque já decrementado
     * permanece decrementado, só muda o status. Vai ser chamado pelo módulo
     * `order`/`payment` quando existir.
     */
    @Transactional
    public StockReservationResponseDto confirm(UUID reservationId) {
        StockReservation reservation = getActiveOrThrow(reservationId);
        reservation.confirm();
        return reservationMapper.toResponseDto(reservation);
    }

    /**
     * Libera a reserva manualmente (cliente cancelou antes de expirar) —
     * devolve a quantidade ao stock da peça, com lock pessimista.
     */
    @Transactional
    public StockReservationResponseDto release(UUID reservationId) {
        StockReservation reservation = getActiveOrThrow(reservationId);
        returnStockWithLock(reservation);
        reservation.release();
        return reservationMapper.toResponseDto(reservation);
    }

    @Transactional(readOnly = true)
    public StockReservationResponseDto findById(UUID id) {
        StockReservation reservation = reservationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Reserva não encontrada"));
        return reservationMapper.toResponseDto(reservation);
    }

    /**
     * Reserva várias peças de uma vez, tudo ou nada. Trava as peças em ordem de id
     * (evita deadlock entre checkouts com as mesmas peças em ordem diferente) e só
     * altera estoque depois de verificar todas — devolve false sem mudar nada se
     * alguma estiver inativa ou sem estoque suficiente.
     */
    @Transactional
    public boolean tryReserveAll(Map<UUID, Integer> quantityByPiece, UUID orderId) {
        Map<UUID, Piece> locked = new TreeMap<>();
        for (UUID pieceId : new TreeMap<>(quantityByPiece).keySet()) {
            Piece piece = lockPiece(pieceId);
            if (!piece.isActive() || piece.getStock() < quantityByPiece.get(pieceId)) {
                return false;
            }
            locked.put(pieceId, piece);
        }

        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(reservationExpirationMinutes);
        locked.forEach((pieceId, piece) -> {
            int quantity = quantityByPiece.get(pieceId);
            piece.setStock(piece.getStock() - quantity);
            reservationRepository.save(StockReservation.builder()
                    .piece(piece)
                    .quantity(quantity)
                    .orderId(orderId)
                    .status(ReservationStatus.ACTIVE)
                    .expiresAt(expiresAt)
                    .build());
        });
        return true;
    }

    /** Pagamento aprovado: confirma todas as reservas ativas do pedido. */
    @Transactional
    public void confirmAllForOrder(UUID orderId) {
        activeReservationsOf(orderId).forEach(StockReservation::confirm);
    }

    /** Pedido cancelado/expirado: devolve o estoque de todas as reservas ativas, em ordem de peça. */
    @Transactional
    public void releaseAllForOrder(UUID orderId) {
        for (StockReservation reservation : activeReservationsOf(orderId)) {
            returnStockWithLock(reservation);
            reservation.release();
        }
    }

    /**
     * Ajusta o estoque manualmente (ex: venda fora do site, perda, avaria), com lock
     * pessimista, gravando trilha de auditoria (quem, quanto, por quê).
     */
    @Transactional
    public StockAdjustment adjustStock(UUID pieceId, Integer delta, String reason, String adjustedByEmail) {
        Piece piece = lockPiece(pieceId);

        int novoEstoque = piece.getStock() + delta;
        if (novoEstoque < 0) {
            throw new InvalidStateException("Ajuste deixaria o estoque negativo (atual: " + piece.getStock() + ", delta: " + delta + ")");
        }

        piece.setStock(novoEstoque);

        StockAdjustment adjustment = StockAdjustment.builder()
                .piece(piece)
                .delta(delta)
                .reason(reason)
                .adjustedByEmail(adjustedByEmail)
                .build();

        return stockAdjustmentRepository.save(adjustment);
    }

    // ---- helpers internos ----

    private StockReservation getActiveOrThrow(UUID reservationId) {
        StockReservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reserva não encontrada"));

        if (reservation.getStatus() != ReservationStatus.ACTIVE) {
            throw new InvalidStateException("Reserva não está mais ativa (status atual: " + reservation.getStatus() + ")");
        }

        return reservation;
    }

    private void returnStockWithLock(StockReservation reservation) {
        Piece piece = lockPiece(reservation.getPiece().getId());
        piece.setStock(piece.getStock() + reservation.getQuantity());
    }

    private List<StockReservation> activeReservationsOf(UUID orderId) {
        return reservationRepository.findAllByOrderId(orderId).stream()
                .filter(r -> r.getStatus() == ReservationStatus.ACTIVE)
                .sorted(Comparator.comparing(r -> r.getPiece().getId()))
                .toList();
    }

    /**
     * Trava a linha primeiro e só depois relê o estado. Se a peça já estava na sessão
     * (ex: veio junto com o carrinho), @Lock/refresh com lock do Hibernate devolvem o estoque
     * lido antes do lock — e duas compras passam pela checagem.
     */
    private Piece lockPiece(UUID pieceId) {
        pieceRepository.lockById(pieceId)
                .orElseThrow(() -> new ResourceNotFoundException("Peça não encontrada"));
        Piece piece = pieceRepository.findById(pieceId).orElseThrow();
        entityManager.refresh(piece);
        return piece;
    }
}
