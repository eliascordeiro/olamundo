#!/usr/bin/env python3
"""Gera o SQL de carga do legado Murimel (lojas, vendedores, produtos e saldos de abertura).

Le o dump, aplica as decisoes do cliente e escreve arquivos; NAO conecta a banco.
Aplique o SQL primeiro em um banco de teste (ver README). Idempotente: repetir
nao duplica nada nem altera saldos que ja tenham movimento.

Decisoes aplicadas (cliente, 30/10/2026):
  1. produto so e criado na loja onde ha saldo valido ou venda;
  2. saldos invalidos (produto inexistente, negativo, loja descartada) NAO sao importados;
     ficam listados em descartados.csv;
  3. loja 1 (sem cadastro) e tudo que depende dela sao descartados;
  4. nomes acima de 120 caracteres sao encurtados sem partir palavra; o nome completo
     vai para a descricao;
  5. vendedor com o mesmo nome em varias lojas e a mesma pessoa: um cadastro por loja,
     agrupados em vendedores.csv para o proprietario vincular um unico e-mail.

Uso: python3 murimel_import.py "dump.sql" --out ./legacy-import-out
"""
import argparse
import collections
import csv
import re
import sys
import uuid
from decimal import Decimal
from pathlib import Path

import murimel_dryrun as base

NAMESPACE = uuid.UUID("6f0c1b1e-0f7d-5a6c-9a3e-6d1f4b2a7c10")
OWNER_ID = "00000000-0000-4000-8000-00000000a001"  # "Móveis Murimel", semeado pela V7
DISCARDED_STORES = frozenset({"1"})
SOURCE = "murimel"
ACTOR = "legacy-import:murimel"
REASON = "Saldo de abertura (migracao do legado Murimel)"
NAME_LIMIT = 120
DESCRIPTION_LIMIT = 2000
CHUNK = 400


def uid(*parts):
    return str(uuid.uuid5(NAMESPACE, ":".join(parts)))


def shorten(name, limit=NAME_LIMIT):
    """Encurta sem partir palavra. Devolve (nome, foi_encurtado)."""
    if len(name) <= limit:
        return name, False
    cut = name[:limit]
    if name[limit] != " ":
        space = cut.rfind(" ")
        if space > 0:
            cut = cut[:space]
    return cut.rstrip(" ,;:-/.").strip(), True


def sql_text(value):
    if "\0" in value:
        raise ValueError("NUL em texto")
    return "'" + value.replace("'", "''") + "'"


def build_plan(text, discarded_stores=DISCARDED_STORES):
    t = base.load_tables(text)
    stats = base.TextStats()
    discarded = []

    def drop(kind, entity, code, store, detail):
        discarded.append({"tipo": kind, "entidade": entity, "codigo_legado": code,
                          "loja_legada": store, "detalhe": detail})

    stores = {}
    for row in t["easy_loja"]:
        if row["codigo"] in discarded_stores:
            continue
        name = stats.clean("easy_loja", row["loja"])
        stores[row["codigo"]] = {"id": uid("store", row["codigo"]), "nome": name,
                                 "legacy_code": f"{SOURCE}:{row['codigo']}"}
    for code in sorted(discarded_stores):
        drop("LOJA_DESCARTADA", "loja", code, code, "decisao do cliente: loja sem cadastro no legado")

    # produtos validos
    raw_codes = {row["codigo"] for row in t["easy_produtos"]}
    products = {}
    for row in t["easy_produtos"]:
        code = row["codigo"]
        name = stats.clean("easy_produtos", row["descricao"])
        price = base.to_decimal(row["venda"])
        if len(name) < 2:
            drop("PRODUTO_SEM_NOME", "produto", code, "", "descricao vazia; produto nao importado")
        elif not base.SKU_PATTERN.match(code):
            drop("PRODUTO_SKU_INVALIDO", "produto", code, "", "codigo nao serve como SKU")
        elif price is None or price < 0 or price > base.PRICE_MAX:
            drop("PRODUTO_PRECO_INVALIDO", "produto", code, "", "preco fora de 0..9999999999.99")
        else:
            short, was_short = shorten(name)
            parts = []
            if was_short:
                parts.append(f"Nome completo no legado: {name}")
            if (row.get("codfab") or "").strip():
                parts.append(f"Codigo de fabrica: {stats.clean('easy_produtos', row['codfab'])}")
            products[code] = {"nome": short, "preco": price.quantize(Decimal("0.01")),
                              "descricao": " | ".join(parts)[:DESCRIPTION_LIMIT], "encurtado": was_short}

    # saldos de abertura
    opening = {}
    for row in t["easy_lojasaldo"]:
        saldo = base.to_decimal(row["saldo"])
        code, store = row["codpro"], row["codloja"]
        if saldo is None or saldo == 0:
            continue
        if store in discarded_stores:
            drop("SALDO_LOJA_DESCARTADA", "saldo", code, store, f"saldo {saldo}")
        elif store not in stores:
            drop("SALDO_LOJA_SEM_CADASTRO", "saldo", code, store, f"saldo {saldo}")
        elif code not in products:
            if code in raw_codes:
                drop("SALDO_PRODUTO_NAO_IMPORTADO", "saldo", code, store,
                     f"saldo {saldo}; o produto existe no legado mas foi rejeitado (sem nome, SKU ou preco invalidos)")
            else:
                drop("SALDO_PRODUTO_INEXISTENTE", "saldo", code, store,
                     f"saldo {saldo}; o codigo nao existe mais no catalogo do legado")
        elif saldo < 0:
            drop("SALDO_NEGATIVO", "saldo", code, store, f"saldo {saldo}")
        elif saldo != saldo.to_integral_value() or saldo > base.INT_MAX:
            drop("SALDO_NAO_INTEIRO_OU_ACIMA_DO_LIMITE", "saldo", code, store, f"saldo {saldo}")
        else:
            opening[(store, code)] = int(saldo)

    # escopo: onde ha saldo valido ou venda
    pairs = set(opening)
    for row in t["easy_itens"]:
        if row["loja"] in stores and row["codpro"] in products:
            pairs.add((row["loja"], row["codpro"]))

    # vendedores
    sellers = []
    for row in t["easy_vendedor"]:
        code, store = row["codigo"], row["loja"]
        name = stats.clean("easy_vendedor", row["vendedor"])
        if store in discarded_stores or store not in stores:
            drop("VENDEDOR_LOJA_DESCARTADA", "vendedor", code, store, "loja descartada ou sem cadastro")
        elif len(name) < 2:
            drop("VENDEDOR_SEM_NOME", "vendedor", code, store, "nome vazio")
        else:
            sellers.append({"id": uid("seller", store, code), "store": store, "code": code, "nome": name,
                            "pessoa": re.sub(r"\s+", " ", name).upper()})
    return {"stores": stores, "products": products, "pairs": sorted(pairs), "opening": opening,
            "sellers": sellers, "discarded": discarded, "text_stats": stats.counts}


def chunks(items):
    for start in range(0, len(items), CHUNK):
        yield items[start:start + CHUNK]


def build_sql(plan):
    stores, products = plan["stores"], plan["products"]
    out = ["-- Carga Murimel (gerado por murimel_import.py). Idempotente.", "BEGIN;", ""]
    out.append("INSERT INTO stores (id, name, created_at, legacy_code) VALUES")
    out.append(",\n".join(f"  ('{s['id']}', {sql_text(s['nome'])}, now(), {sql_text(s['legacy_code'])})"
                          for s in stores.values()))
    out.append("ON CONFLICT (id) DO NOTHING;\n")

    rows = [f"  ('{uid('product', store, code)}', '{stores[store]['id']}', {sql_text(products[code]['nome'])}, "
            f"{sql_text(code)}, {sql_text(products[code]['descricao'])}, {products[code]['preco']}, 'BRL', now())"
            for store, code in plan["pairs"]]
    for part in chunks(rows):
        out.append("INSERT INTO catalog_products (id, store_id, name, sku, description, price, currency, created_at) VALUES")
        out.append(",\n".join(part))
        out.append("ON CONFLICT (store_id, sku) DO NOTHING;\n")

    rows = [f"  ('{stores[s['store']]['id']}', '{s['id']}', {sql_text(s['nome'])}, {sql_text(s['nome'])}, true, "
            f"{sql_text(ACTOR)}, now(), {sql_text(ACTOR)}, now(), {sql_text(SOURCE + ':' + s['code'])})"
            for s in plan["sellers"]]
    if rows:
        out.append("INSERT INTO salespeople (store_id, id, name, registration_name, active, created_by, created_at, "
                   "updated_by, updated_at, legacy_code) VALUES")
        out.append(",\n".join(rows))
        out.append("ON CONFLICT (store_id, id) DO NOTHING;\n")

    out.append("CREATE TEMP TABLE _opening (store_id uuid, product_id uuid, quantity integer, movement_id uuid) ON COMMIT DROP;")
    rows = [f"  ('{stores[store]['id']}', '{uid('product', store, code)}', {qty}, '{uid('opening', store, code)}')"
            for (store, code), qty in sorted(plan["opening"].items())]
    for part in chunks(rows):
        out.append("INSERT INTO _opening VALUES")
        out.append(",\n".join(part) + ";")
    # So cria movimento para saldos novos: reexecutar nunca sobrescreve estoque ja movimentado.
    out.append(f"""
WITH created AS (
  INSERT INTO inventory_balances (store_id, product_id, quantity, version)
  SELECT o.store_id, o.product_id, o.quantity, 1 FROM _opening o
  WHERE EXISTS (SELECT 1 FROM catalog_products p WHERE p.store_id = o.store_id AND p.id = o.product_id)
    AND NOT EXISTS (SELECT 1 FROM inventory_balances b WHERE b.store_id = o.store_id AND b.product_id = o.product_id)
  RETURNING store_id, product_id, quantity
)
INSERT INTO inventory_movements (store_id, product_id, id, sequence, type, quantity, balance_after, reason, user_id, created_at)
SELECT c.store_id, c.product_id, o.movement_id, 1, 'IN', c.quantity, c.quantity, {sql_text(REASON)}, {sql_text(ACTOR)}, now()
FROM created c JOIN _opening o ON o.store_id = c.store_id AND o.product_id = c.product_id;

COMMIT;
""")
    return "\n".join(out)


def attach_owner_sql():
    return f"""-- Executar SOMENTE depois que o proprietario "Moveis Murimel" aceitar o convite (status ACTIVE).
-- Concede OWNER em todas as lojas importadas. Idempotente.
INSERT INTO store_memberships (store_id, user_id, role, created_at)
SELECT s.id, o.user_id, 'OWNER', now()
FROM stores s CROSS JOIN platform_owners o
WHERE s.legacy_code LIKE '{SOURCE}:%' AND o.id = '{OWNER_ID}' AND o.status = 'ACTIVE' AND o.user_id IS NOT NULL
ON CONFLICT (store_id, user_id) DO NOTHING;
"""


def write_outputs(plan, out):
    out.mkdir(parents=True, exist_ok=True)
    (out / "carga_murimel.sql").write_text(build_sql(plan), encoding="utf-8")
    (out / "anexar_proprietario.sql").write_text(attach_owner_sql(), encoding="utf-8")
    with open(out / "descartados.csv", "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=["tipo", "entidade", "codigo_legado", "loja_legada", "detalhe"])
        writer.writeheader()
        writer.writerows(plan["discarded"])
    people = collections.Counter(s["pessoa"] for s in plan["sellers"])
    ids = {}
    with open(out / "vendedores.csv", "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=["pessoa", "nome", "codigo_legado", "loja_legada", "loja_destino",
                                                "lojas_da_pessoa", "email"])
        writer.writeheader()
        for s in sorted(plan["sellers"], key=lambda s: (s["pessoa"], s["store"])):
            ids.setdefault(s["pessoa"], len(ids) + 1)
            writer.writerow({"pessoa": ids[s["pessoa"]], "nome": s["nome"], "codigo_legado": s["code"],
                             "loja_legada": s["store"], "loja_destino": plan["stores"][s["store"]]["nome"],
                             "lojas_da_pessoa": people[s["pessoa"]], "email": ""})
    summary = summarize(plan)
    (out / "relatorio_carga.md").write_text(render_report(summary), encoding="utf-8")
    return summary


def summarize(plan):
    kinds = collections.Counter(d["tipo"] for d in plan["discarded"])
    people = {s["pessoa"] for s in plan["sellers"]}
    return {
        "lojas": len(plan["stores"]), "produtos_distintos": len({c for _, c in plan["pairs"]}),
        "pares_produto_loja": len(plan["pairs"]), "nomes_encurtados": sum(
            1 for _, c in plan["pairs"] if plan["products"][c]["encurtado"]),
        "produtos_preco_zero": len({c for _, c in plan["pairs"] if plan["products"][c]["preco"] == 0}),
        "vendedores_cadastros": len(plan["sellers"]), "vendedores_pessoas": len(people),
        "saldos_abertura": len(plan["opening"]), "unidades": sum(plan["opening"].values()),
        "descartados_por_tipo": dict(sorted(kinds.items())),
    }


def render_report(s):
    lines = ["# Carga Murimel: o que o SQL grava", "",
             "Gerado a partir do dump com as decisoes do cliente. **Nao contem senhas nem clientes.**", "",
             f"- Lojas: {s['lojas']} (loja 1 descartada).",
             f"- Produtos: {s['produtos_distintos']} distintos em {s['pares_produto_loja']} pares produto/loja "
             f"(so onde ha saldo valido ou venda); {s['nomes_encurtados']} com nome encurtado e nome completo na descricao.",
             f"- Produtos com preco zero (importados com R$ 0,00; revisar antes de vender): {s['produtos_preco_zero']}.",
             f"- Vendedores: {s['vendedores_cadastros']} cadastros por loja, {s['vendedores_pessoas']} pessoas "
             "(mesmo nome em varias lojas = mesma pessoa). Sem conta e sem e-mail; senhas nunca importadas.",
             f"- Saldos de abertura: {s['saldos_abertura']} movimentos, {s['unidades']} unidades.", "",
             "## Descartados (listados em descartados.csv)", ""]
    lines += [f"- `{kind}`: {n}" for kind, n in s["descartados_por_tipo"].items()]
    lines += ["", "## Fora desta carga", "",
              "Clientes, historico de vendas, pagamentos, recibos, montagens, grupos/subgrupos e custo dos produtos.", ""]
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("dump", type=Path)
    parser.add_argument("--out", type=Path, default=Path("legacy-import-out"))
    args = parser.parse_args(argv)
    plan = build_plan(args.dump.read_bytes().decode("utf-8"))
    summary = write_outputs(plan, args.out)
    print(summary)
    print(f"Arquivos em: {args.out.resolve()}", file=sys.stderr)


if __name__ == "__main__":
    main()
