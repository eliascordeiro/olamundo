#!/usr/bin/env python3
"""Gera o SQL de carga de GRUPOS e SUBGRUPOS de produtos do legado Murimel (empresa "Moveis Murimel").

Le o dump e escreve arquivos; NAO conecta a banco. Idempotente.
- Grupos e subgrupos guardam nome, NCM e os percentuais do legado (comissao, markup, descontos), que ainda
  nao sao usados por vendas. Valores fora das faixas do destino viram 0 e entram em ajustes.
- Os produtos JA importados recebem o grupo/subgrupo (pelo codigo/SKU legado), sem sobrescrever
  classificacao que alguem ja tenha feito (so atualiza produto sem grupo).
Depende da V11 e dos produtos ja carregados (murimel_import.py).

Uso: python3 murimel_groups.py "dump.sql" --out ./legacy-import-out
"""
import argparse
import collections
import csv
import sys
from decimal import Decimal
from pathlib import Path

import murimel_dryrun as base
import murimel_import as imp

SOURCE = "murimel"
CHUNK = 400


def percent(value, maximum, flags, label):
    number = base.to_decimal(value)
    if number is None or number < 0 or number > maximum:
        if number is not None and number != 0:
            flags[f"{label}_FORA_DA_FAIXA"] += 1
        return Decimal("0.00")
    return number.quantize(Decimal("0.01"))


def build_plan(text):
    t = base.load_tables(text, wanted={"easy_grupo", "easy_subgrupo", "easy_produtos"})
    stats = base.TextStats()
    flags = collections.Counter()
    discarded, groups, subgroups = [], {}, []
    for row in t["easy_grupo"]:
        name = stats.clean("easy_grupo", row["grupo"])
        if not 2 <= len(name) <= 60:
            discarded.append({"entidade": "grupo", "codigo_legado": row["codigo"], "motivo": "GRUPO_NOME_INVALIDO"})
            continue
        groups[row["codigo"]] = {
            "id": imp.uid("group", row["codigo"]), "name": name,
            "ncm": stats.clean("easy_grupo", row.get("ncm"))[:20] or None,
            "commission": percent(row["comissao"], 100, flags, "COMISSAO"),
            "assembly_commission": percent(row["comissao_montagem"], 100, flags, "COMISSAO_MONTAGEM"),
            "assembly_discount": percent(row["descmontagem"], 100, flags, "DESCONTO_MONTAGEM"),
            "markup": percent(row["mk"], 1000, flags, "MARKUP"),
            "pauta_discount": percent(row["desconto_pauta"], 100, flags, "DESCONTO_PAUTA"),
            "flexible_discount": percent(row["desconto_flexivel"], 100, flags, "DESCONTO_FLEXIVEL")}
    seen_names = collections.Counter()
    for row in t["easy_subgrupo"]:
        name = stats.clean("easy_subgrupo", row["subgrupo"])
        if row["codgrupo"] not in groups:
            discarded.append({"entidade": "subgrupo", "codigo_legado": f"{row['codgrupo']}/{row['codsubgrupo']}",
                              "motivo": "GRUPO_INEXISTENTE_OU_DESCARTADO"})
        elif not 2 <= len(name) <= 60:
            discarded.append({"entidade": "subgrupo", "codigo_legado": f"{row['codgrupo']}/{row['codsubgrupo']}",
                              "motivo": "SUBGRUPO_NOME_INVALIDO"})
        else:
            seen_names[(row["codgrupo"], name.upper())] += 1
            subgroups.append({
                "id": imp.uid("subgroup", row["codgrupo"], row["codsubgrupo"]), "group": row["codgrupo"],
                "code": f"{row['codgrupo']}/{row['codsubgrupo']}", "sub": row["codsubgrupo"], "name": name,
                "markup": percent(row["mk"], 1000, flags, "SUBGRUPO_MARKUP"),
                "margin": percent(row["margem"], 1000, flags, "SUBGRUPO_MARGEM"),
                "pauta_discount": percent(row["desconto_pauta"], 100, flags, "SUBGRUPO_DESCONTO_PAUTA"),
                "flexible_discount": percent(row["desconto_flexivel"], 100, flags, "SUBGRUPO_DESCONTO_FLEXIVEL")})
    flags["SUBGRUPO_NOME_REPETIDO_NO_GRUPO"] = sum(1 for count in seen_names.values() if count > 1)
    if not flags["SUBGRUPO_NOME_REPETIDO_NO_GRUPO"]:
        del flags["SUBGRUPO_NOME_REPETIDO_NO_GRUPO"]
    sub_codes = {s["code"] for s in subgroups}
    links = []
    for row in t["easy_produtos"]:
        if row["grupo"] in groups and base.SKU_PATTERN.match(row["codigo"]):
            code = f"{row['grupo']}/{row['subgrupo']}"
            links.append((row["codigo"], row["grupo"], row["subgrupo"] if code in sub_codes else None))
    return {"groups": groups, "subgroups": subgroups, "links": links, "discarded": discarded, "flags": flags,
            "text_stats": stats.counts}


def sql_value(value):
    return "NULL" if value is None else imp.sql_text(str(value))


def build_sql(plan):
    owner = imp.OWNER_ID
    out = ["-- Carga de grupos/subgrupos Murimel (gerado por murimel_groups.py). Idempotente.", "BEGIN;", ""]
    rows = [f"  ('{g['id']}', '{owner}', {sql_value(g['name'])}, {sql_value(g['name'])}, {sql_value(g['ncm'])}, "
            f"{g['commission']}, {g['assembly_commission']}, {g['assembly_discount']}, {g['markup']}, "
            f"{g['pauta_discount']}, {g['flexible_discount']}, {sql_value(imp.ACTOR)}, now(), {sql_value(imp.ACTOR)}, now())"
            for g in plan["groups"].values()]
    out.append("INSERT INTO product_groups (id, owner_id, name, registration_name, ncm, commission, assembly_commission, "
               "assembly_discount, markup, pauta_discount, flexible_discount, created_by, created_at, updated_by, updated_at) VALUES")
    out.append(",\n".join(rows))
    out.append("ON CONFLICT DO NOTHING;\n")
    rows = [f"  ('{s['id']}', '{owner}', '{plan['groups'][s['group']]['id']}', {sql_value(s['name'])}, {sql_value(s['name'])}, "
            f"{s['markup']}, {s['margin']}, {s['pauta_discount']}, {s['flexible_discount']}, "
            f"{sql_value(imp.ACTOR)}, now(), {sql_value(imp.ACTOR)}, now())" for s in plan["subgroups"]]
    for start in range(0, len(rows), CHUNK):
        out.append("INSERT INTO product_subgroups (id, owner_id, group_id, name, registration_name, markup, margin, "
                   "pauta_discount, flexible_discount, created_by, created_at, updated_by, updated_at) VALUES")
        out.append(",\n".join(rows[start:start + CHUNK]))
        out.append("ON CONFLICT DO NOTHING;\n")
    codes = [f"  ('{owner}'::uuid, '{SOURCE}', 'group', {sql_value(code)}, '{g['id']}'::uuid)" for code, g in plan["groups"].items()]
    codes += [f"  ('{owner}'::uuid, '{SOURCE}', 'subgroup', {sql_value(s['code'])}, '{s['id']}'::uuid)" for s in plan["subgroups"]]
    out.append("INSERT INTO legacy_codes (owner_id, source, entity, legacy_code, entity_id)")
    out.append("SELECT v.owner_id, v.source, v.entity, v.legacy_code, v.entity_id FROM (VALUES")
    out.append(",\n".join(codes))
    out.append(") AS v(owner_id, source, entity, legacy_code, entity_id)")
    out.append("WHERE EXISTS (SELECT 1 FROM product_groups g WHERE g.id = v.entity_id)")
    out.append("   OR EXISTS (SELECT 1 FROM product_subgroups s WHERE s.id = v.entity_id)")
    out.append("ON CONFLICT DO NOTHING;\n")
    # Classifica os produtos ja importados; so mexe em produto sem grupo.
    link_rows = [f"  ({sql_value(sku)}, {sql_value(group)}, {sql_value(sub)})" for sku, group, sub in plan["links"]]
    for start in range(0, len(link_rows), 1000):
        out.append("UPDATE catalog_products p SET group_id = g.id, subgroup_id = sg.id")
        out.append("FROM (VALUES")
        out.append(",\n".join(link_rows[start:start + 1000]))
        out.append(") AS v(sku, grp, sub)")
        out.append(f"JOIN legacy_codes lg ON lg.owner_id = '{owner}' AND lg.source = '{SOURCE}' AND lg.entity = 'group' AND lg.legacy_code = v.grp")
        out.append("JOIN product_groups g ON g.id = lg.entity_id")
        out.append(f"LEFT JOIN legacy_codes ls ON ls.owner_id = '{owner}' AND ls.source = '{SOURCE}' AND ls.entity = 'subgroup' "
                   "AND v.sub IS NOT NULL AND ls.legacy_code = v.grp || '/' || v.sub")
        out.append("LEFT JOIN product_subgroups sg ON sg.id = ls.entity_id")
        out.append(f"WHERE p.sku = v.sku AND p.group_id IS NULL AND p.store_id IN (SELECT id FROM stores WHERE owner_id = '{owner}');\n")
    out.append("COMMIT;\n")
    return "\n".join(out)


def summarize(plan):
    return {"grupos": len(plan["groups"]), "subgrupos": len(plan["subgroups"]), "produtos_a_classificar": len(plan["links"]),
            "descartados": len(plan["discarded"]), "ajustes": dict(sorted(plan["flags"].items()))}


def write_outputs(plan, out):
    out.mkdir(parents=True, exist_ok=True)
    (out / "carga_grupos.sql").write_text(build_sql(plan), encoding="utf-8")
    with open(out / "grupos_descartados.csv", "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=["entidade", "codigo_legado", "motivo"])
        writer.writeheader()
        writer.writerows(plan["discarded"])
    s = summarize(plan)
    (out / "relatorio_grupos.md").write_text("\n".join([
        "# Carga de grupos e subgrupos Murimel: o que o SQL grava", "",
        f"- Grupos: {s['grupos']}. Subgrupos: {s['subgrupos']}. Descartados: {s['descartados']}.",
        f"- Produtos legados a classificar (so os ja importados e sem grupo): {s['produtos_a_classificar']}.",
        f"- Ajustes: {s['ajustes'] or 'nenhum'}.", ""]), encoding="utf-8")
    return s


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("dump", type=Path)
    parser.add_argument("--out", type=Path, default=Path("legacy-import-out"))
    args = parser.parse_args(argv)
    print(write_outputs(build_plan(args.dump.read_bytes().decode("utf-8")), args.out))
    print(f"Arquivos em: {args.out.resolve()}", file=sys.stderr)


if __name__ == "__main__":
    main()
