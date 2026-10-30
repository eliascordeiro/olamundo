package com.example.olamundo.productgroups.domain;

import java.math.BigDecimal;

/** Campos editaveis de um grupo, ja normalizados. */
public record GroupData(String name, String ncm, BigDecimal commission, BigDecimal assemblyCommission,
		BigDecimal assemblyDiscount, BigDecimal markup, BigDecimal pautaDiscount, BigDecimal flexibleDiscount) {
}
