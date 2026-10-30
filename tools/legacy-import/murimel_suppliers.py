#!/usr/bin/env python3
"""Gera o SQL de carga dos FORNECEDORES do legado Murimel para a empresa "Moveis Murimel".

Le o dump e escreve arquivos; NAO conecta a banco. Idempotente (ON CONFLICT DO NOTHING).
Mesmas regras dos clientes: CNPJ/CPF valido repetido e unido automaticamente (campos vazios do
principal sao completados pelos demais; todos os codigos antigos ficam em legacy_codes);
documento invalido nunca une ninguem; campos invalidos viram vazios, nunca inventados.
Depende da V11 (tabelas suppliers e legacy_codes).

Uso: python3 murimel_suppliers.py "dump.sql" --out ./legacy-import-out
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

SOURCE = "murimel"
FIELDS = ("name", "document", "state_registration", "email", "phone", "fax", "contact", "zip", "street",
          "neighborhood", "city", "state")
CHUNK = 300


def normalize_row(row, stats, flags):
    data = {"name": stats.clean("easy_fornecedor", row["fornecedor"])}
    digits = re.sub(r"\D", "", row.get("cnpj") or "")
    data["document"] = digits if cust.tax_id_valid(digits) else None
    if digits and data["document"] is None:
        flags["DOCUMENTO_INVALIDO"] += 1
    data["state_registration"] = cust.limited(stats.clean("easy_fornecedor", row.get("ie")), 20)
    email = stats.clean("easy_fornecedor", row.get("email")).lower()
    data["email"] = email if email and cust.EMAIL.match(email) and len(email) <= 254 else None
    if email and data["email"] is None:
        flags["EMAIL_DESCARTADO"] += 1
    for source, target in (("fone", "phone"), ("fax", "fax")):
        raw = (row.get(source) or "").strip()
        data[target] = cust.clean_phone(raw)
        if raw and data[target] is None:
            flags["TELEFONE_DESCARTADO"] += 1
    data["contact"] = cust.limited(stats.clean("easy_fornecedor", row.get("contato")), 60)
    zip_code = re.sub(r"\D", "", row.get("cep") or "")
    data["zip"] = zip_code if len(zip_code) == 8 else None
    data["street"] = cust.limited(stats.clean("easy_fornecedor", row.get("endereco")), 100)
    data["neighborhood"] = cust.limited(stats.clean("easy_fornecedor", row.get("bairro")), 60)
    data["city"] = cust.limited(stats.clean("easy_fornecedor", row.get("cidade")), 60)
    state = stats.clean("easy_fornecedor", row.get("uf")).upper()
    data["state"] = state if re.fullmatch(r"[A-Z]{2}", state) else None
    return data


def completeness(data):
    return sum(1 for field in FIELDS if data.get(field))


def build_plan(text):
    t = base.load_tables(text, wanted={"easy_fornecedor"})
    stats = base.TextStats()
    flags = collections.Counter()
    discarded, records = [], []
    for row in t["easy_fornecedor"]:
        data = normalize_row(row, stats, flags)
        if len(data["name"]) < 2:
            discarded.append({"codigo_legado": row["codigo"], "motivo": "FORNECEDOR_SEM_NOME"})
            continue
        if len(data["name"]) > 120:
            data["name"] = imp.shorten(data["name"])[0]
            flags["NOME_ENCURTADO"] += 1
        records.append({"code": row["codigo"], "data": data})
    by_document = collections.defaultdict(list)
    for record in records:
        if record["data"]["document"]:
            by_document[record["data"]["document"]].append(record)
    merged_into, merges = {}, []
    for group in by_document.values():
        if len(group) < 2:
            continue
        ranked = sorted(group, key=lambda r: (-completeness(r["data"]), -(int(r["code"]) if r["code"].isdigit() else 0)))
        main, others = ranked[0], ranked[1:]
        for other in others:
            for field in FIELDS:
                if not main["data"].get(field) and other["data"].get(field):
                    main["data"][field] = other["data"][field]
            merged_into[other["code"]] = main["code"]
        tokens = [cust.name_tokens(r["data"]["name"]) for r in group]
        shared = set.intersection(*tokens) if all(tokens) else set()
        merges.append({"codigo_principal": main["code"], "codigos_unidos": " ".join(o["code"] for o in others),
                       "cadastros": len(group),
                       "nomes_iguais": "sim" if len({r["data"]["name"].upper() for r in group}) == 1 else "nao",
                       "nomes_com_palavra_em_comum": "sim" if shared else "nao"})
    suppliers = [r for r in records if r["code"] not in merged_into]
    legacy = [(r["code"], merged_into.get(r["code"], r["code"])) for r in records]
    return {"suppliers": suppliers, "legacy": legacy, "merges": merges, "discarded": discarded, "flags": flags,
            "read": len(t["easy_fornecedor"]), "text_stats": stats.counts}


def sql_value(value):
    return "NULL" if value is None else imp.sql_text(str(value))


def build_sql(plan):
    owner = imp.OWNER_ID
    out = ["-- Carga de fornecedores Murimel (gerado por murimel_suppliers.py). Idempotente.", "BEGIN;", ""]
    rows = []
    for record in plan["suppliers"]:
        d = record["data"]
        rows.append(
            f"  ('{imp.uid('supplier', record['code'])}', '{owner}', {sql_value(d['name'])}, {sql_value(d['name'])}, "
            f"{sql_value(d['document'])}, {sql_value(d['state_registration'])}, {sql_value(d['email'])}, "
            f"{sql_value(d['phone'])}, {sql_value(d['fax'])}, {sql_value(d['contact'])}, {sql_value(d['zip'])}, "
            f"{sql_value(d['street'])}, {sql_value(d['neighborhood'])}, {sql_value(d['city'])}, {sql_value(d['state'])}, "
            f"true, {sql_value(imp.ACTOR)}, now(), {sql_value(imp.ACTOR)}, now())")
    for start in range(0, len(rows), CHUNK):
        out.append("INSERT INTO suppliers (id, owner_id, name, registration_name, document, state_registration, email, "
                   "phone, fax, contact, zip, street, neighborhood, city, state, active, created_by, created_at, "
                   "updated_by, updated_at) VALUES")
        out.append(",\n".join(rows[start:start + CHUNK]))
        out.append("ON CONFLICT DO NOTHING;\n")
    codes = [f"  ('{owner}'::uuid, '{SOURCE}', 'supplier', {sql_value(code)}, '{imp.uid('supplier', main)}'::uuid)"
             for code, main in plan["legacy"]]
    for start in range(0, len(codes), CHUNK * 2):
        out.append("INSERT INTO legacy_codes (owner_id, source, entity, legacy_code, entity_id)")
        out.append("SELECT v.owner_id, v.source, v.entity, v.legacy_code, v.entity_id FROM (VALUES")
        out.append(",\n".join(codes[start:start + CHUNK * 2]))
        out.append(") AS v(owner_id, source, entity, legacy_code, entity_id)")
        out.append("WHERE EXISTS (SELECT 1 FROM suppliers s WHERE s.id = v.entity_id)")
        out.append("ON CONFLICT DO NOTHING;\n")
    out.append("COMMIT;\n")
    return "\n".join(out)


def summarize(plan):
    different = [m for m in plan["merges"] if m["nomes_iguais"] == "nao"]
    return {
        "lidos": plan["read"], "fornecedores_a_criar": len(plan["suppliers"]),
        "descartados_sem_nome": len(plan["discarded"]), "grupos_unidos": len(plan["merges"]),
        "cadastros_unidos": sum(m["cadastros"] - 1 for m in plan["merges"]),
        "unioes_com_nomes_diferentes": len(different),
        "unioes_sem_palavra_em_comum": sum(1 for m in plan["merges"] if m["nomes_com_palavra_em_comum"] == "nao"),
        "codigos_legados_rastreados": len(plan["legacy"]),
        "com_documento": sum(1 for r in plan["suppliers"] if r["data"]["document"]),
        "sem_documento": sum(1 for r in plan["suppliers"] if not r["data"]["document"]),
        "ajustes": dict(sorted(plan["flags"].items())),
        "texto_corrigido": {k[0]: v for k, v in plan["text_stats"].items() if k[1] == "corrigido"},
        "texto_irrecuperavel": {k[0]: v for k, v in plan["text_stats"].items() if k[1] == "irrecuperavel"},
    }


def write_outputs(plan, out):
    out.mkdir(parents=True, exist_ok=True)
    (out / "carga_fornecedores.sql").write_text(build_sql(plan), encoding="utf-8")
    with open(out / "fornecedores_descartados.csv", "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=["codigo_legado", "motivo"])
        writer.writeheader()
        writer.writerows(plan["discarded"])
    with open(out / "fornecedores_unidos.csv", "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=["codigo_principal", "codigos_unidos", "cadastros", "nomes_iguais",
                                                "nomes_com_palavra_em_comum"])
        writer.writeheader()
        writer.writerows(plan["merges"])
    s = summarize(plan)
    (out / "relatorio_fornecedores.md").write_text("\n".join([
        "# Carga de fornecedores Murimel: o que o SQL grava", "",
        f"- Cadastros lidos no legado: {s['lidos']}.",
        f"- Fornecedores a criar: {s['fornecedores_a_criar']} ({s['com_documento']} com CNPJ/CPF valido, {s['sem_documento']} sem documento).",
        f"- Descartados por falta de nome: {s['descartados_sem_nome']}.",
        f"- Unidos por CNPJ/CPF repetido: {s['grupos_unidos']} grupos, {s['cadastros_unidos']} cadastros excedentes; "
        f"{s['unioes_com_nomes_diferentes']} com nomes diferentes e {s['unioes_sem_palavra_em_comum']} sem palavra em comum "
        "(revisar em `fornecedores_unidos.csv`; os codigos antigos continuam rastreados).",
        f"- Codigos legados rastreados: {s['codigos_legados_rastreados']}.",
        f"- Ajustes (campo invalido virou vazio): {s['ajustes'] or 'nenhum'}.", ""]), encoding="utf-8")
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
