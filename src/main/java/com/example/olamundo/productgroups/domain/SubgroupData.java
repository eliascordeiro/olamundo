package com.example.olamundo.productgroups.domain;

import java.math.BigDecimal;

/** Campos editaveis de um subgrupo, ja normalizados. */
public record SubgroupData(String name, BigDecimal markup, BigDecimal margin, BigDecimal pautaDiscount,
		BigDecimal flexibleDiscount) {
}
