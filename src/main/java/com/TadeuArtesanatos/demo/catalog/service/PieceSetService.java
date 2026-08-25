package com.TadeuArtesanatos.demo.catalog.service;

import com.TadeuArtesanatos.demo.catalog.dto.PieceSetRequestDto;
import com.TadeuArtesanatos.demo.catalog.dto.PieceSetResponseDto;
import com.TadeuArtesanatos.demo.catalog.entity.Piece;
import com.TadeuArtesanatos.demo.catalog.entity.PieceSet;
import com.TadeuArtesanatos.demo.catalog.mapper.PieceSetMapper;
import com.TadeuArtesanatos.demo.catalog.repository.PieceRepository;
import com.TadeuArtesanatos.demo.catalog.repository.PieceSetRepository;
import com.TadeuArtesanatos.demo.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PieceSetService {

    private final PieceSetRepository pieceSetRepository;
    private final PieceRepository pieceRepository;
    private final PieceSetMapper pieceSetMapper;

    @Transactional
    public PieceSetResponseDto create(PieceSetRequestDto dto) {
        PieceSet pieceSet = PieceSet.builder()
                .name(dto.name())
                .description(dto.description())
                .price(dto.price())
                .pieces(resolvePieces(dto.pieceIds()))
                .build();

        return pieceSetMapper.toResponseDto(pieceSetRepository.save(pieceSet));
    }

    @Transactional(readOnly = true)
    public Page<PieceSetResponseDto> list(Pageable pageable) {
        return pieceSetRepository.findAllByDeletedAtIsNull(pageable)
                .map(pieceSetMapper::toResponseDto);
    }

    @Transactional(readOnly = true)
    public PieceSetResponseDto findById(UUID id) {
        return pieceSetMapper.toResponseDto(getActiveOrThrow(id));
    }

    @Transactional
    public PieceSetResponseDto update(UUID id, PieceSetRequestDto dto) {
        PieceSet pieceSet = getActiveOrThrow(id);

        pieceSet.setName(dto.name());
        pieceSet.setDescription(dto.description());
        pieceSet.setPrice(dto.price());
        pieceSet.setPieces(resolvePieces(dto.pieceIds()));

        return pieceSetMapper.toResponseDto(pieceSet);
    }

    @Transactional
    public void softDelete(UUID id) {
        PieceSet pieceSet = getActiveOrThrow(id);
        pieceSet.softDelete();
    }

    // ---- helpers internos ----

    private PieceSet getActiveOrThrow(UUID id) {
        return pieceSetRepository.findById(id)
                .filter(PieceSet::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Conjunto não encontrado"));
    }

    private Set<Piece> resolvePieces(Set<UUID> pieceIds) {
        Set<Piece> pieces = new HashSet<>(pieceRepository.findAllById(pieceIds));

        if (pieces.size() != pieceIds.size()) {
            throw new IllegalArgumentException("Uma ou mais peças informadas não existem");
        }

        boolean temPecaInativa = pieces.stream().anyMatch(piece -> !piece.isActive());
        if (temPecaInativa) {
            throw new IllegalArgumentException("Uma ou mais peças informadas estão inativas (removidas da loja)");
        }

        return pieces;
    }
}
