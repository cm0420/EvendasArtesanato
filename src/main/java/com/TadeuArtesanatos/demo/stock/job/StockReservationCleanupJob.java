package com.TadeuArtesanatos.demo.stock.job;

import com.TadeuArtesanatos.demo.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StockReservationCleanupJob {

    private final StockService stockService;

    /**
     * Roda a cada 2 minutos (120_000 ms) — libera reservas ACTIVE cujo
     * prazo já passou, devolvendo a quantidade ao stock da peça.
     */
    @Scheduled(fixedRate = 120_000)
    public void releaseExpiredReservations() {
        stockService.releaseExpiredReservations();
    }
}
