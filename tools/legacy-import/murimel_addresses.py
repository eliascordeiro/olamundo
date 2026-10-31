#!/usr/bin/env python3
"""Gera o SQL de carga dos ENDERECOS DE ENTREGA do legado Murimel (tabela `endereco`).

Le o dump e escreve arquivos; NAO conecta a banco. Idempotente (ON CONFLICT DO NOTHING).
Depende da V12 e da carga de clientes (usa `customer_legacy_codes` para achar o cliente final).

Decisoes do cliente (31/10/2026):
  - enderecos identicos ao endereco principal do cliente nao sao importados (o principal ja esta no cadastro);
  - sem rua: descartado; CEP invalido: importa o endereco sem CEP;
  - cliente inexistente ou nao importado: descartado.

Uso: python3 murimel_addresses.py "dump.sql" --out ./legacy-import-out
"""
import argparse
import collections
import csv
import re
import sys
from pathlib import Path

import murimel_customers as cust
import murimel_dryrun as base
import murimel_import as imp

CHUNK = 300


def limited(value, size):
    return cust.limited(value, size)


def normalize_row(row, stats):
    digits = re.sub(r"\D", "", row.get("cep") or "")
    state = stats.clean("endereco", row.get("estado")).upper()
    parts = [("Cond.", row.get("condominio")), ("Bl.", row.get("bloco")), ("Ap.", row.get("apto"))]
    complement = ", ".join(f"{label} {stats.clean('endereco', value)}" for label, value in parts
                           if stats.clean("endereco", value))
    return {
        "zip": digits if len(digits) == 8 else None,
        "zip_invalid": len(digits) != 8,
        "street": limited(stats.clean("endereco", row.get("endereco")), 100),
        "number": limited(stats.clean("endereco", row.get("numero")), 20),
        "complement": limited(complement, 100),
        "neighborhood": limited(stats.clean("endereco", row.get("bairro")), 60),
        "city": limited(stats.clean("endereco", row.get("cidade")), 60),
        "state": state if re.fullmatch(r"[A-Z]{2}", state) else None,
    }


def signature(data):
    return (data["zip"] or "", (data["street"] or "").upper(), (data["number"] or "").upper())


def build_plan(text):
    t = base.load_tables(text, wanted={"endereco", "easy_cliente"})
    plan = cust.build_plan(text)
    final = {r["code"]: r["data"] for r in plan["customers"]}
    main_of = dict(plan["legacy"])
    stats = base.TextStats()
    flags = collections.Counter()
    legacy_rows = {str(r["codigo"]): r for r in t["easy_cliente"]}
    seen = set()
    addresses, skipped = [], []
    for row in t["endereco"]:
        code = str(row["codigo"])
        data = normalize_row(row, stats)

        def skip(reason):
            skipped.append({"codigo_cliente_legado": code, "cep": row["cep"], "motivo": reason})

        if code not in legacy_rows:
            skip("CLIENTE_INEXISTENTE")
            continue
        if code not in main_of:
            skip("CLIENTE_NAO_IMPORTADO")
            continue
        if not data["street"] or len(data["street"]) < 2:
            skip("SEM_RUA")
            continue
        own, _ = cust.normalize_row(legacy_rows[code], base.TextStats(), collections.Counter())
        if signature(data) in (signature(own), signature(final[main_of[code]])):
            skip("IDENTICO_AO_PRINCIPAL")
            continue
        key = (main_of[code], signature(data))
        if key in seen:
            skip("DUPLICADO")
            continue
        seen.add(key)
        if data["zip_invalid"]:
            flags["CEP_INVALIDO_REMOVIDO"] += 1
        addresses.append({"code": code, "cep_raw": row["cep"], "data": data})
    return {"addresses": addresses, "skipped": skipped, "flags": flags, "read": len(t["endereco"]),
            "text_stats": stats.counts}


def sql_value(value):
    return "NULL" if value is None else imp.sql_text(str(value))


def build_sql(plan):
    owner = imp.OWNER_ID
    out = ["-- Carga de enderecos de entrega Murimel (gerado por murimel_addresses.py). Idempotente.", "BEGIN;", ""]
    rows = []
    for record in plan["addresses"]:
        d = record["data"]
        rows.append(f"  ('{imp.uid('delivery', record['code'], record['cep_raw'])}'::uuid, {sql_value(record['code'])}, "
                    f"{sql_value(d['zip'])}, {sql_value(d['street'])}, {sql_value(d['number'])}, "
                    f"{sql_value(d['complement'])}, {sql_value(d['neighborhood'])}, {sql_value(d['city'])}, "
                    f"{sql_value(d['state'])})")
    actor = sql_value(imp.ACTOR)
    for start in range(0, len(rows), CHUNK):
        out.append("INSERT INTO customer_delivery_addresses (id, owner_id, customer_id, registration_name, zip, street, "
                   "number, complement, neighborhood, city, state, created_by, created_at, updated_by, updated_at)")
        out.append(f"SELECT v.id, '{owner}'::uuid, l.customer_id, v.street, v.zip, v.street, v.number, v.complement, "
                   f"v.neighborhood, v.city, v.state, {actor}, now(), {actor}, now()")
        out.append("FROM (VALUES")
        out.append(",\n".join(rows[start:start + CHUNK]))
        out.append(") AS v(id, code, zip, street, number, complement, neighborhood, city, state)")
        out.append(f"JOIN customer_legacy_codes l ON l.owner_id = '{owner}'::uuid AND l.source = 'murimel' "
                   "AND l.legacy_code = v.code")
        out.append("ON CONFLICT DO NOTHING;\n")
    out.append("COMMIT;\n")
    return "\n".join(out)


def summarize(plan):
    reasons = collections.Counter(s["motivo"] for s in plan["skipped"])
    return {"lidos": plan["read"], "a_importar": len(plan["addresses"]), "nao_importados": dict(sorted(reasons.items())),
            "ajustes": dict(sorted(plan["flags"].items())),
            "texto_corrigido": {k[0]: v for k, v in plan["text_stats"].items() if k[1] == "corrigido"}}


def render_report(s):
    return "\n".join([
        "# Carga de enderecos de entrega Murimel: o que o SQL grava", "",
        "Sem dados pessoais neste relatorio.", "",
        f"- Enderecos lidos no legado: {s['lidos']}.",
        f"- A importar: {s['a_importar']}.",
        f"- Nao importados: {s['nao_importados'] or 'nenhum'}.",
        f"- Ajustes (campo invalido virou vazio, nunca inventado): {s['ajustes'] or 'nenhum'}.",
        f"- Acentuacao corrigida: {s['texto_corrigido'] or 'nenhum'}.", ""])


def write_outputs(plan, out):
    out.mkdir(parents=True, exist_ok=True)
    (out / "carga_enderecos.sql").write_text(build_sql(plan), encoding="utf-8")
    with open(out / "enderecos_nao_importados.csv", "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=["codigo_cliente_legado", "cep", "motivo"])
        writer.writeheader()
        writer.writerows(plan["skipped"])
    summary = summarize(plan)
    (out / "relatorio_enderecos.md").write_text(render_report(summary), encoding="utf-8")
    return summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("dump", type=Path)
    parser.add_argument("--out", type=Path, default=Path("legacy-import-out"))
    args = parser.parse_args(argv)
    summary = write_outputs(build_plan(args.dump.read_bytes().decode("utf-8")), args.out)
    print(summary)
    print(f"Arquivos em: {args.out.resolve()}", file=sys.stderr)


if __name__ == "__main__":
    main()
