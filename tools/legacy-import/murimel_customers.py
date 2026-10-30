#!/usr/bin/env python3
"""Gera o SQL de carga dos CLIENTES do legado Murimel para a empresa "Moveis Murimel".

Le o dump e escreve arquivos; NAO conecta a banco. Idempotente (ON CONFLICT DO NOTHING).

Decisoes do cliente (30/10/2026):
  - clientes pertencem a empresa (proprietario), compartilhados por todas as lojas dela;
  - dados minimos: nome, CPF/CNPJ, RG, IE, nascimento, telefones, e-mail e endereco.
    NAO importa salario, filiacao, conjuge, empresa onde trabalha nem referencias;
  - todos os clientes, inclusive os sem venda;
  - CPF/CNPJ valido repetido: os cadastros sao unidos automaticamente (campos vazios do
    principal sao completados pelos demais) e todos os codigos antigos ficam rastreados.
    Documento invalido/placeholder nunca une ninguem: o cliente entra sem documento.

Uso: python3 murimel_customers.py "dump.sql" --out ./legacy-import-out
"""
import argparse
import collections
import csv
import re
import sys
from datetime import date
from pathlib import Path

import murimel_dryrun as base
import murimel_import as imp

SOURCE = "murimel"
PHONE_ALLOWED = re.compile(r"[^0-9 ()+./-]")
EMAIL = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
CHUNK = 300
FIELDS = ("name", "document", "rg", "state_registration", "birth_date", "phone", "mobile", "email", "zip",
          "street", "number", "complement", "neighborhood", "city", "state", "ibge")


def tax_id_valid(digits):
    if not digits or not digits.isdigit() or len(set(digits)) == 1:
        return False
    if len(digits) == 11:
        for n in (9, 10):
            total = sum(int(digits[i]) * (n + 1 - i) for i in range(n))
            if (total * 10 % 11) % 10 != int(digits[n]):
                return False
        return True
    if len(digits) == 14:
        def check(base_digits, weights):
            rest = sum(int(d) * w for d, w in zip(base_digits, weights)) % 11
            return 0 if rest < 2 else 11 - rest
        return (check(digits[:12], [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]) == int(digits[12])
                and check(digits[:13], [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]) == int(digits[13]))
    return False


def parse_date(value):
    try:
        parsed = date.fromisoformat((value or "").strip())
    except ValueError:
        return None
    return parsed if date(1900, 1, 1) <= parsed <= date.today() else None


def clean_phone(value):
    text = re.sub(r"\s+", " ", PHONE_ALLOWED.sub("", value or "")).strip()
    return text if len(re.sub(r"\D", "", text)) >= 8 and len(text) <= 20 else None


def limited(value, size):
    text = value or ""
    return text if text and len(text) <= size else (text[:size].rstrip() if text else None)


def normalize_row(row, stats, flags):
    """Converte um cadastro legado nos campos do destino. flags e um Counter de problemas."""
    name = stats.clean("easy_cliente", row["nome"])
    data = {"name": name}
    digits = [re.sub(r"\D", "", row.get(col) or "") for col in ("cpf", "cnpj")]
    document = next((d for d in digits if tax_id_valid(d)), None)
    if document is None and any(digits):
        flags["DOCUMENTO_INVALIDO"] += 1
    data["document"] = document
    data["rg"] = limited(stats.clean("easy_cliente", row.get("rg")), 20)
    data["state_registration"] = limited(stats.clean("easy_cliente", row.get("ie")), 20)
    data["birth_date"] = parse_date(row.get("datanasc"))
    for source, target in (("fonecasa", "phone"), ("celular", "mobile")):
        raw = (row.get(source) or "").strip()
        data[target] = clean_phone(raw)
        if raw and data[target] is None:
            flags["TELEFONE_DESCARTADO"] += 1
    email = stats.clean("easy_cliente", row.get("email")).lower()
    data["email"] = email if email and EMAIL.match(email) and len(email) <= 254 else None
    if email and data["email"] is None:
        flags["EMAIL_DESCARTADO"] += 1
    zip_code = re.sub(r"\D", "", row.get("cep") or "")
    data["zip"] = zip_code if len(zip_code) == 8 else None
    data["street"] = limited(stats.clean("easy_cliente", row.get("endereco")), 100)
    data["number"] = limited(stats.clean("easy_cliente", row.get("numero")), 20)
    extras = [stats.clean("easy_cliente", row.get(c)) for c in ("complemento", "bloco", "apto", "condominio")]
    data["complement"] = limited(" ".join(x for x in extras if x), 100)
    data["neighborhood"] = limited(stats.clean("easy_cliente", row.get("bairro")), 60)
    data["city"] = limited(stats.clean("easy_cliente", row.get("cidade")), 60)
    state = stats.clean("easy_cliente", row.get("estado")).upper()
    data["state"] = state if re.fullmatch(r"[A-Z]{2}", state) else None
    data["ibge"] = limited(stats.clean("easy_cliente", row.get("ibge")), 10)
    created = parse_date(row.get("datacad"))
    return data, created


def completeness(data):
    return sum(1 for field in FIELDS if data.get(field))


def name_tokens(name):
    return {t for t in re.sub(r"[^A-Z0-9 ]", " ", name.upper()).split() if len(t) > 2}


def build_plan(text):
    t = base.load_tables(text, wanted={"easy_cliente"})
    stats = base.TextStats()
    flags = collections.Counter()
    discarded = []
    records = []
    for row in t["easy_cliente"]:
        data, created = normalize_row(row, stats, flags)
        code = row["codigo"]
        if len(data["name"]) < 2:
            discarded.append({"codigo_legado": code, "motivo": "CLIENTE_SEM_NOME"})
            continue
        if len(data["name"]) > 120:
            data["name"] = imp.shorten(data["name"])[0]
            flags["NOME_ENCURTADO"] += 1
        records.append({"code": code, "data": data, "created": created})

    by_document = collections.defaultdict(list)
    for record in records:
        if record["data"]["document"]:
            by_document[record["data"]["document"]].append(record)
    merged_into = {}
    merges = []
    for document, group in by_document.items():
        if len(group) < 2:
            continue
        ranked = sorted(group, key=lambda r: (-completeness(r["data"]), -(r["created"].toordinal() if r["created"] else 0),
                                              -int(r["code"]) if r["code"].isdigit() else 0))
        main, others = ranked[0], ranked[1:]
        for other in others:
            for field in FIELDS:
                if not main["data"].get(field) and other["data"].get(field):
                    main["data"][field] = other["data"][field]
            merged_into[other["code"]] = main["code"]
        tokens = [name_tokens(r["data"]["name"]) for r in group]
        shared = set.intersection(*tokens) if all(tokens) else set()
        merges.append({"codigo_principal": main["code"], "codigos_unidos": " ".join(o["code"] for o in others),
                       "cadastros": len(group),
                       "nomes_iguais": "sim" if len({r["data"]["name"].upper() for r in group}) == 1 else "nao",
                       "nomes_com_palavra_em_comum": "sim" if shared else "nao"})
    customers = [r for r in records if r["code"] not in merged_into]
    legacy = [(r["code"], merged_into.get(r["code"], r["code"])) for r in records]
    return {"customers": customers, "legacy": legacy, "merges": merges, "discarded": discarded,
            "flags": flags, "read": len(t["easy_cliente"]), "text_stats": stats.counts}


def sql_value(value):
    return "NULL" if value is None else imp.sql_text(str(value))


def build_sql(plan):
    owner = imp.OWNER_ID
    out = ["-- Carga de clientes Murimel (gerado por murimel_customers.py). Idempotente.", "BEGIN;", ""]
    rows = []
    for record in plan["customers"]:
        d = record["data"]
        created = f"'{record['created'].isoformat()}'::timestamptz" if record["created"] else "now()"
        rows.append(
            f"  ('{imp.uid('customer', record['code'])}', '{owner}', {sql_value(d['name'])}, {sql_value(d['name'])}, "
            f"{sql_value(d['document'])}, {sql_value(d['rg'])}, {sql_value(d['state_registration'])}, "
            f"{sql_value(d['birth_date'].isoformat() if d['birth_date'] else None)}, {sql_value(d['phone'])}, "
            f"{sql_value(d['mobile'])}, {sql_value(d['email'])}, {sql_value(d['zip'])}, {sql_value(d['street'])}, "
            f"{sql_value(d['number'])}, {sql_value(d['complement'])}, {sql_value(d['neighborhood'])}, "
            f"{sql_value(d['city'])}, {sql_value(d['state'])}, {sql_value(d['ibge'])}, "
            f"{sql_value(imp.ACTOR)}, {created}, {sql_value(imp.ACTOR)}, {created})")
    for start in range(0, len(rows), CHUNK):
        out.append("INSERT INTO customers (id, owner_id, name, registration_name, document, rg, state_registration, "
                   "birth_date, phone, mobile, email, zip, street, number, complement, neighborhood, city, state, ibge, "
                   "created_by, created_at, updated_by, updated_at) VALUES")
        out.append(",\n".join(rows[start:start + CHUNK]))
        out.append("ON CONFLICT DO NOTHING;\n")
    codes = [f"  ('{owner}'::uuid, '{SOURCE}', {sql_value(code)}, '{imp.uid('customer', main)}'::uuid)"
             for code, main in plan["legacy"]]
    for start in range(0, len(codes), CHUNK * 2):
        out.append("INSERT INTO customer_legacy_codes (owner_id, source, legacy_code, customer_id)")
        out.append("SELECT v.owner_id, v.source, v.legacy_code, v.customer_id FROM (VALUES")
        out.append(",\n".join(codes[start:start + CHUNK * 2]))
        out.append(") AS v(owner_id, source, legacy_code, customer_id)")
        out.append("WHERE EXISTS (SELECT 1 FROM customers c WHERE c.id = v.customer_id)")
        out.append("ON CONFLICT DO NOTHING;\n")
    out.append("COMMIT;\n")
    return "\n".join(out)


def write_outputs(plan, out):
    out.mkdir(parents=True, exist_ok=True)
    (out / "carga_clientes.sql").write_text(build_sql(plan), encoding="utf-8")
    with open(out / "clientes_descartados.csv", "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=["codigo_legado", "motivo"])
        writer.writeheader()
        writer.writerows(plan["discarded"])
    with open(out / "clientes_unidos.csv", "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=["codigo_principal", "codigos_unidos", "cadastros", "nomes_iguais",
                                                "nomes_com_palavra_em_comum"])
        writer.writeheader()
        writer.writerows(plan["merges"])
    summary = summarize(plan)
    (out / "relatorio_clientes.md").write_text(render_report(summary), encoding="utf-8")
    return summary


def summarize(plan):
    different = [m for m in plan["merges"] if m["nomes_iguais"] == "nao"]
    return {
        "lidos": plan["read"], "clientes_a_criar": len(plan["customers"]),
        "descartados_sem_nome": len(plan["discarded"]),
        "grupos_unidos": len(plan["merges"]), "cadastros_unidos": sum(m["cadastros"] - 1 for m in plan["merges"]),
        "unioes_com_nomes_diferentes": len(different),
        "unioes_sem_palavra_em_comum": sum(1 for m in plan["merges"] if m["nomes_com_palavra_em_comum"] == "nao"),
        "codigos_legados_rastreados": len(plan["legacy"]),
        "com_documento": sum(1 for r in plan["customers"] if r["data"]["document"]),
        "sem_documento": sum(1 for r in plan["customers"] if not r["data"]["document"]),
        "ajustes": dict(sorted(plan["flags"].items())),
        "texto_corrigido": {k[0]: v for k, v in plan["text_stats"].items() if k[1] == "corrigido"},
        "texto_irrecuperavel": {k[0]: v for k, v in plan["text_stats"].items() if k[1] == "irrecuperavel"},
    }


def render_report(s):
    return "\n".join([
        "# Carga de clientes Murimel: o que o SQL grava", "",
        "Sem dados pessoais neste relatorio. Importados so os campos minimos; salario, filiacao, conjuge, empresa onde",
        "trabalha e referencias **nao** sao importados.", "",
        f"- Cadastros lidos no legado: {s['lidos']}.",
        f"- Clientes a criar: {s['clientes_a_criar']} ({s['com_documento']} com CPF/CNPJ valido, {s['sem_documento']} sem documento).",
        f"- Descartados por falta de nome: {s['descartados_sem_nome']}.",
        f"- Unidos por CPF/CNPJ repetido: {s['grupos_unidos']} grupos, {s['cadastros_unidos']} cadastros excedentes; "
        f"{s['unioes_com_nomes_diferentes']} grupos com nomes diferentes e {s['unioes_sem_palavra_em_comum']} sem nenhuma palavra em comum "
        "(revisar em `clientes_unidos.csv`; os codigos antigos continuam rastreados).",
        f"- Codigos legados rastreados: {s['codigos_legados_rastreados']} (todos apontam para o cliente final).",
        f"- Ajustes (campo invalido virou vazio, nunca inventado): {s['ajustes'] or 'nenhum'}.",
        f"- Acentuacao corrigida: {s['texto_corrigido'] or 'nenhum'}; nao recuperavel: {s['texto_irrecuperavel'] or 'nenhum'}.", ""])


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
