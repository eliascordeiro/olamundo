#!/usr/bin/env python3
"""Dry-run da carga do legado Murimel (lojas, vendedores, produtos e saldos).

Le o dump MySQL e gera relatorios do que SERIA criado e do que fica pendente.
Nao conecta a nenhum banco e nao grava nada no sistema. Senhas do legado
(easy_vendedor.senha, easy_senha, easy_usuario) nunca vao para os relatorios.

Uso: python3 murimel_dryrun.py "dump.sql" --out ./legacy-import-out
"""
import argparse
import collections
import csv
import hashlib
import json
import re
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path

INT_MAX = 2147483647
PRICE_MAX = Decimal("9999999999.99")
SKU_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
WANTED = {"easy_loja", "easy_vendedor", "easy_produtos", "easy_lojasaldo", "easy_itens", "easy_vendas"}
ESCAPES = {"0": "\0", "n": "\n", "r": "\r", "t": "\t", "Z": "\x1a", "b": "\b"}
MOJIBAKE = re.compile("[\u00c3\u00c2][\u0080-\u00bf\u0152\u0153\u0160\u0161\u0178\u017d\u017e\u0192\u02c6\u02dc"
                      "\u2013-\u203a\u20ac\u2122]")
FLAG_COLUMNS = re.compile(r"^(i\d+|icalc|imaster|ibkpe)$")


# ---------------------------------------------------------------- parsing do dump
def read_string(text, i):
    """Le uma string SQL entre aspas simples a partir de text[i] == "'"."""
    i += 1
    out = []
    while True:
        c = text[i]
        if c == "\\":
            out.append(ESCAPES.get(text[i + 1], text[i + 1]))
            i += 2
        elif c == "'":
            if text[i + 1] == "'":
                out.append("'")
                i += 2
            else:
                return i + 1, "".join(out)
        else:
            out.append(c)
            i += 1


def parse_tuples(text, i):
    rows = []
    while True:
        c = text[i]
        if c == "(":
            row = []
            i += 1
            while True:
                if text[i] == "'":
                    i, value = read_string(text, i)
                    row.append(value)
                else:
                    j = i
                    while text[j] not in ",)":
                        j += 1
                    token = text[i:j]
                    row.append(None if token == "NULL" else token)
                    i = j
                if text[i] == ",":
                    i += 1
                    continue
                i += 1
                break
            rows.append(row)
        elif c == ";":
            return rows, i + 1
        else:
            i += 1


def load_tables(text, wanted=WANTED):
    """Devolve {tabela: [dict(coluna -> valor)]} apenas das tabelas pedidas."""
    pattern = re.compile(r"INSERT INTO `([^`]+)` \(([^)]*)\) VALUES")
    tables = {name: [] for name in wanted}
    pos = 0
    while True:
        match = pattern.search(text, pos)
        if not match:
            return tables
        table = match.group(1)
        if table not in wanted:
            # Pula as linhas da tabela ate o proximo INSERT que comece uma linha.
            end = text.find("\nINSERT INTO ", match.end())
            pos = len(text) if end < 0 else end + 1
            continue
        columns = [c.strip().strip("`") for c in match.group(2).split(",")]
        rows, pos = parse_tuples(text, match.end())
        tables[table].extend(dict(zip(columns, row)) for row in rows)


# ---------------------------------------------------------------- texto
class TextStats:
    def __init__(self):
        self.counts = collections.Counter()

    def clean(self, table, value):
        """Corrige acentuacao dupla codificada (latin1 lida como utf8) e normaliza espacos."""
        if value is None:
            return ""
        text = value
        if MOJIBAKE.search(text):
            try:
                data = bytearray()
                for ch in text:
                    try:
                        data += ch.encode("cp1252")
                    except UnicodeEncodeError:
                        if ord(ch) < 256:
                            data.append(ord(ch))
                        else:
                            raise
                text = data.decode("utf-8")
                self.counts[(table, "corrigido")] += 1
            except (UnicodeEncodeError, UnicodeDecodeError):
                self.counts[(table, "irrecuperavel")] += 1
        return re.sub(r"\s+", " ", text).strip()


def to_decimal(value):
    try:
        return Decimal((value or "").strip())
    except InvalidOperation:
        return None


# ---------------------------------------------------------------- analise
def analyze(text):
    t = load_tables(text)
    stats = TextStats()
    pending = []

    def flag(kind, entity, code, store, detail, level="BLOQUEIA"):
        pending.append({"nivel": level, "tipo": kind, "entidade": entity, "codigo_legado": code,
                        "loja_legada": store, "detalhe": detail})

    # lojas
    stores = {}
    for row in t["easy_loja"]:
        name = stats.clean("easy_loja", row["loja"])
        stores[row["codigo"]] = {"codigo": row["codigo"], "nome": name,
                                 "deposito": (row.get("deposito") or "").strip() == "X"}
        if not 2 <= len(name) <= 100:
            flag("LOJA_NOME_INVALIDO", "loja", row["codigo"], row["codigo"], "nome fora de 2..100 caracteres")
    referenced = collections.Counter()
    for table, column in (("easy_vendedor", "loja"), ("easy_vendas", "loja"), ("easy_itens", "loja")):
        for row in t[table]:
            referenced[row[column]] += 1
    nonzero_stock_stores = collections.Counter()
    for row in t["easy_lojasaldo"]:
        saldo = to_decimal(row["saldo"])
        if saldo is not None and saldo != 0:
            referenced[row["codloja"]] += 1
            nonzero_stock_stores[row["codloja"]] += 1
    unmapped = sorted(code for code in referenced if code not in stores and code != "0")
    for code in unmapped:
        flag("LOJA_SEM_CADASTRO", "loja", code, code,
             f"referenciada {referenced[code]}x (vendedores, vendas, itens ou saldos) mas ausente de easy_loja")

    # produtos
    products = {}
    names = collections.defaultdict(list)
    for row in t["easy_produtos"]:
        code = row["codigo"]
        name = stats.clean("easy_produtos", row["descricao"])
        price = to_decimal(row["venda"])
        products[code] = {"codigo": code, "nome": name, "preco": price}
        names[name.upper()].append(code)
        if len(name) < 2:
            flag("PRODUTO_NOME_VAZIO", "produto", code, "", "descricao vazia ou curta demais")
        elif len(name) > 120:
            flag("PRODUTO_NOME_LONGO", "produto", code, "",
                 f"{len(name)} caracteres; destino aceita 120. Nao truncar sem decisao")
        if not SKU_PATTERN.match(code):
            flag("PRODUTO_SKU_INVALIDO", "produto", code, "", "codigo nao serve como SKU")
        if price is None or price < 0 or price > PRICE_MAX:
            flag("PRODUTO_PRECO_INVALIDO", "produto", code, "", "preco fora de 0..9999999999.99")
        elif price == 0:
            flag("PRODUTO_PRECO_ZERO", "produto", code, "", "preco de venda zero", "REVISAR")
        if not (row.get("codfab") or "").strip():
            flag("PRODUTO_SEM_CODIGO_FABRICA", "produto", code, "", "sem codigo de fabrica", "INFO")
    for name, codes in names.items():
        if len(codes) > 1:
            for code in codes:
                flag("PRODUTO_NOME_DUPLICADO", "produto", code, "",
                     f"mesmo nome de outros {len(codes) - 1} produto(s); SKUs distintos", "REVISAR")

    # saldos
    skipped_zero = 0
    planned = []
    stock_issues = collections.Counter()
    for row in t["easy_lojasaldo"]:
        saldo = to_decimal(row["saldo"])
        code, store = row["codpro"], row["codloja"]
        if saldo is None:
            stock_issues["SALDO_INVALIDO"] += 1
            flag("SALDO_INVALIDO", "saldo", code, store, "valor nao numerico")
        elif saldo == 0:
            skipped_zero += 1
        elif code not in products:
            stock_issues["SALDO_PRODUTO_INEXISTENTE"] += 1
            flag("SALDO_PRODUTO_INEXISTENTE", "saldo", code, store,
                 f"saldo {saldo} de produto ausente de easy_produtos")
        elif store not in stores:
            stock_issues["SALDO_LOJA_SEM_CADASTRO"] += 1
            flag("SALDO_LOJA_SEM_CADASTRO", "saldo", code, store, f"saldo {saldo} em loja sem cadastro")
        elif saldo < 0:
            stock_issues["SALDO_NEGATIVO"] += 1
            flag("SALDO_NEGATIVO", "saldo", code, store, f"saldo {saldo}; destino nao aceita negativo")
        elif saldo != saldo.to_integral_value():
            stock_issues["SALDO_FRACIONADO"] += 1
            flag("SALDO_FRACIONADO", "saldo", code, store, f"saldo {saldo}; destino aceita so inteiros")
        elif saldo > INT_MAX:
            stock_issues["SALDO_EXCEDE_LIMITE"] += 1
            flag("SALDO_EXCEDE_LIMITE", "saldo", code, store, "acima de 2147483647")
        else:
            planned.append((code, store, int(saldo)))

    # vendedores (a coluna de senha nunca e usada)
    seller_codes = set()
    name_count = collections.Counter()
    sellers = []
    for row in t["easy_vendedor"]:
        name_count[stats.clean("easy_vendedor", row["vendedor"]).upper()] += 1
    for row in t["easy_vendedor"]:
        code, store = row["codigo"], row["loja"]
        name = stats.clean("easy_vendedor", row["vendedor"])
        seller_codes.add(code)
        flags = [column for column, value in row.items() if FLAG_COLUMNS.match(column) and value == "1"]
        problems = []
        if store not in stores:
            problems.append("LOJA_SEM_CADASTRO")
            flag("VENDEDOR_LOJA_SEM_CADASTRO", "vendedor", code, store, "loja do vendedor ausente de easy_loja")
        if name_count[name.upper()] > 1:
            problems.append("NOME_DUPLICADO")
            flag("VENDEDOR_NOME_DUPLICADO", "vendedor", code, store,
                 "outro vendedor com o mesmo nome; confirmar se e a mesma pessoa", "REVISAR")
        if len(name) < 2:
            problems.append("NOME_INVALIDO")
            flag("VENDEDOR_NOME_INVALIDO", "vendedor", code, store, "nome vazio ou curto")
        flag("VENDEDOR_SEM_EMAIL", "vendedor", code, store, "legado so tem codigo; proprietario informa o e-mail", "INFO")
        sellers.append({"codigo_legado": code, "loja_legada": store,
                        "loja_destino": stores.get(store, {}).get("nome", ""), "nome": name,
                        "modulos_ativos": len([f for f in flags if f != "imaster"]),
                        "imaster": "sim" if "imaster" in flags else "nao",
                        "pendencias": ";".join(problems), "email": ""})

    # historico (somente leitura; nao sera importado nesta etapa)
    sales = t["easy_vendas"]
    missing_sellers = collections.Counter(row["codven"] for row in sales if row["codven"] not in seller_codes)
    dates = sorted(row["data"] for row in sales if row["data"] and not row["data"].startswith("0000"))
    items_missing_product = sum(1 for row in t["easy_itens"] if row["codpro"] not in products)

    # escopo de produtos por loja
    stores_with_stock = collections.defaultdict(set)
    for code, store, _ in planned:
        stores_with_stock[code].add(store)
    sold_in = collections.defaultdict(set)
    for row in t["easy_itens"]:
        if row["codpro"] in products and row["loja"] in stores:
            sold_in[row["codpro"]].add(row["loja"])
    active_pairs = sum(len(stores_with_stock[c] | sold_in[c]) for c in products)
    per_store_units = collections.Counter()
    for _, store, qty in planned:
        per_store_units[store] += qty

    level_count = collections.Counter(item["nivel"] for item in pending)
    kind_count = collections.Counter(item["tipo"] for item in pending)
    return {
        "stores": stores, "sellers": sellers, "pending": pending,
        "summary": {
            "lojas_cadastradas": len(stores), "lojas_sem_cadastro": unmapped,
            "vendedores": len(sellers),
            "vendedores_imaster": sum(1 for s in sellers if s["imaster"] == "sim"),
            "produtos": len(products),
            "produtos_loja_ativos": active_pairs,
            "produtos_todas_as_lojas": len(products) * len(stores),
            "saldos_lidos": len(t["easy_lojasaldo"]), "saldos_zero_ignorados": skipped_zero,
            "movimentos_de_abertura_planejados": len(planned),
            "unidades_planejadas": sum(q for _, _, q in planned),
            "unidades_por_loja": dict(sorted(per_store_units.items())),
            "saldos_nao_zero_por_loja": dict(sorted(nonzero_stock_stores.items())),
            "problemas_de_saldo": dict(stock_issues),
            "vendas_no_historico": len(sales),
            "vendas_periodo": [dates[0], dates[-1]] if dates else [],
            "vendas_com_vendedor_inexistente": sum(missing_sellers.values()),
            "vendedores_inexistentes_distintos": len(missing_sellers),
            "itens_com_produto_inexistente": items_missing_product,
            "texto_corrigido": {k[0]: v for k, v in stats.counts.items() if k[1] == "corrigido"},
            "texto_irrecuperavel": {k[0]: v for k, v in stats.counts.items() if k[1] == "irrecuperavel"},
            "pendencias_por_nivel": dict(level_count), "pendencias_por_tipo": dict(sorted(kind_count.items())),
        },
    }


# ---------------------------------------------------------------- saidas
def write_outputs(result, out, source):
    out.mkdir(parents=True, exist_ok=True)
    s = result["summary"]
    with open(out / "pendencias.csv", "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=["nivel", "tipo", "entidade", "codigo_legado", "loja_legada", "detalhe"])
        writer.writeheader()
        writer.writerows(result["pending"])
    with open(out / "vendedores.csv", "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=["codigo_legado", "loja_legada", "loja_destino", "nome",
                                                "modulos_ativos", "imaster", "pendencias", "email"])
        writer.writeheader()
        writer.writerows(result["sellers"])
    (out / "plano.json").write_text(json.dumps(s, ensure_ascii=False, indent=2), encoding="utf-8")
    digest = hashlib.sha256(source.read_bytes()).hexdigest()[:16]
    lines = [
        "# Dry-run da carga Murimel", "",
        f"Origem: `{source.name}` (sha256 `{digest}...`).",
        "**Nada foi gravado em nenhum banco.** Este relatorio nao contem senhas nem dados de clientes.", "",
        "## O que seria criado", "",
        f"- Lojas: {s['lojas_cadastradas']} cadastradas em `easy_loja`; sem cadastro mas referenciadas: "
        f"{s['lojas_sem_cadastro'] or 'nenhuma'}.",
        f"- Vendedores: {s['vendedores']} (flag master em {s['vendedores_imaster']}; nenhum vira proprietario). "
        "Senhas nao sao lidas.",
        f"- Produtos: {s['produtos']} no legado (catalogo global). Por loja: {s['produtos_loja_ativos']} pares "
        f"produto/loja se importar so onde ha saldo ou venda, ou {s['produtos_todas_as_lojas']} se importar em todas "
        "as lojas cadastradas.",
        f"- Saldos de abertura: {s['movimentos_de_abertura_planejados']} movimentos, {s['unidades_planejadas']} "
        f"unidades (de {s['saldos_lidos']} linhas; {s['saldos_zero_ignorados']} zeradas ignoradas). "
        f"Unidades por loja: {s['unidades_por_loja']}.", "",
        "## Pendencias por tipo", "",
    ]
    lines += [f"- `{kind}`: {n}" for kind, n in s["pendencias_por_tipo"].items()]
    lines += [
        "", f"Por nivel: {s['pendencias_por_nivel']} (BLOQUEIA impede o item; REVISAR pede decisao; INFO e informativo).", "",
        "## Problemas de saldo", "", f"{s['problemas_de_saldo'] or 'nenhum'}", "",
        "## Historico (nao importado nesta etapa)", "",
        f"- {s['vendas_no_historico']} vendas, periodo {s['vendas_periodo']}.",
        f"- {s['vendas_com_vendedor_inexistente']} vendas apontam para {s['vendedores_inexistentes_distintos']} "
        "codigos de vendedor que nao existem mais no cadastro.",
        f"- {s['itens_com_produto_inexistente']} itens de venda apontam para produtos ausentes do catalogo.", "",
        "## Texto", "",
        f"- Campos com acentuacao corrigida: {s['texto_corrigido'] or 'nenhum'}.",
        f"- Campos com acentuacao quebrada e nao recuperavel automaticamente: {s['texto_irrecuperavel'] or 'nenhum'}.", "",
        "## Decisoes abertas", "",
        "1. Escopo de produtos por loja (so onde ha saldo/venda ou todas as lojas).",
        "2. Destino dos saldos de produtos inexistentes, negativos ou de loja sem cadastro.",
        "3. Loja sem cadastro referenciada pelo legado: criar, mapear para outra ou descartar.",
        "4. Nomes de produto acima de 120 caracteres: encurtar (e como) ou manter a descricao completa.",
        "5. Vendedores duplicados por nome: mesma pessoa em varias lojas ou pessoas distintas.", ""]
    (out / "relatorio.md").write_text("\n".join(lines), encoding="utf-8")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("dump", type=Path)
    parser.add_argument("--out", type=Path, default=Path("legacy-import-out"))
    args = parser.parse_args(argv)
    result = analyze(args.dump.read_bytes().decode("utf-8"))
    write_outputs(result, args.out, args.dump)
    print(json.dumps(result["summary"], ensure_ascii=False, indent=2))
    print(f"\nRelatorios em: {args.out.resolve()}", file=sys.stderr)


if __name__ == "__main__":
    main()
