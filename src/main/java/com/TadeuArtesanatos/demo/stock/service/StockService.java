package com.TadeuArtesanatos.demo.stock.service;

import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import com.TadeuArtesanatos.demo.catalog.repository.PieceRepository;
import com.TadeuArtesanatos.demo.common.exception.InvalidStateException;
import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import com.TadeuArtesanatos.demo.stock.dto.ReserveStockRequestDto;
import com.TadeuArtesanatos.demo.stock.dto.StockReservationResponseDto;
import com.TadeuArtesanatos.demo.stock.entity.ReservationStatus;
import com.TadeuArtesanatos.demo.stock.entity.StockReservation;
import com.TadeuArtesanatos.demo.stock.mapper.StockReservationMapper;
import com.TadeuArtesanatos.demo.stock.repository.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StockService {

    private final StockReservationRepository reservationRepository;
    private final PieceRepository pieceRepository;
    private final StockReservationMapper reservationMapper;

    @Value("${app.stock.reservation-expiration-minutes:20}")
    private long reservationExpirationMinutes;

    /**
     * Reserva estoque de uma peça, com lock pessimista.
     * Decrementa o stock da peça imediatamente — a reserva é o que permite
     * devolver essa quantidade depois, se expirar ou for cancelada.
     */
    @Transactional
    public StockReservationResponseDto reserve(ReserveStockRequestDto dto) {
        Piece piece = pieceRepository.findByIdForUpdate(dto.pieceId())
                .filter(Piece::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Peça não encontrada"));

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
     * Chamado pelo job assíncrono (StockReservationCleanupJob) — varre reservas
     * ACTIVE cujo prazo já passou, devolve o estoque e marca como EXPIRED.
     */
    @Transactional
    public void releaseExpiredReservations() {
        List<StockReservation> expired = reservationRepository
                .findAllByStatusAndExpiresAtBefore(ReservationStatus.ACTIVE, LocalDateTime.now());

        for (StockReservation reservation : expired) {
            returnStockWithLock(reservation);
            reservation.expire();
        }
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
        Piece piece = pieceRepository.findByIdForUpdate(reservation.getPiece().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Peça não encontrada"));

        piece.setStock(piece.getStock() + reservation.getQuantity());
    }
}
