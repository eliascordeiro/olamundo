import tempfile
import unittest
from pathlib import Path

import murimel_addresses as addr

CLIENT_COLUMNS = ("codigo", "nome", "datanasc", "datacad", "cpf", "endereco", "bairro", "cidade", "estado", "cep",
                  "numero", "bloco", "apto", "condominio", "complemento")
ADDRESS_COLUMNS = ("codigo", "cep", "endereco", "numero", "bairro", "cidade", "estado", "condominio", "bloco", "apto")


def insert(table, columns, rows):
    cells = [",".join("'" + str(r.get(c, "")).replace("'", "''") + "'" for c in columns) for r in rows]
    return (f"INSERT INTO `{table}` (" + ",".join(f"`{c}`" for c in columns) + ") VALUES\n"
            + ",\n".join(f" ({c})" for c in cells) + ";\n")


DUMP = insert("easy_cliente", CLIENT_COLUMNS, [
    dict(codigo=1, nome="ANA", datanasc="0001-01-01", datacad="0001-01-01", endereco="RUA A", numero="10", cep="83709-000"),
    dict(codigo=2, nome="BETO", datanasc="0001-01-01", datacad="0001-01-01", endereco="RUA B", numero="20", cep="83709-111"),
    dict(codigo=3, nome="X", datanasc="0001-01-01", datacad="0001-01-01"),
]) + insert("endereco", ADDRESS_COLUMNS, [
    dict(codigo=1, cep="83709-000", endereco="rua   a", numero="10", bairro="CENTRO", cidade="ARAUCARIA", estado="PR"),
    dict(codigo=1, cep="83709-200", endereco="RUA DAS FLORES", numero="5", bairro="CENTRO", cidade="ARAUCARIA",
         estado="PR", condominio="SOL", bloco="B", apto="12"),
    dict(codigo=2, cep="---  -  -", endereco="PEGA NA RUA X", numero="SN", estado="PR"),
    dict(codigo=2, cep="83709-300", endereco="", numero="1"),
    dict(codigo=3, cep="83709-400", endereco="RUA DO NOME CURTO", numero="1"),
    dict(codigo=99, cep="83709-500", endereco="RUA SEM CLIENTE", numero="1"),
])


class AddressPlanTests(unittest.TestCase):
    def setUp(self):
        self.plan = addr.build_plan(DUMP)
        self.reasons = {(s["codigo_cliente_legado"], s["motivo"]) for s in self.plan["skipped"]}

    def test_imports_only_what_differs_from_the_main_address_and_is_usable(self):
        streets = sorted(a["data"]["street"] for a in self.plan["addresses"])
        self.assertEqual(streets, ["PEGA NA RUA X", "RUA DAS FLORES"])
        self.assertIn(("1", "IDENTICO_AO_PRINCIPAL"), self.reasons)
        self.assertIn(("2", "SEM_RUA"), self.reasons)
        self.assertIn(("3", "CLIENTE_NAO_IMPORTADO"), self.reasons)
        self.assertIn(("99", "CLIENTE_INEXISTENTE"), self.reasons)

    def test_invalid_zip_is_dropped_but_address_is_kept_and_complement_is_built(self):
        by_street = {a["data"]["street"]: a["data"] for a in self.plan["addresses"]}
        self.assertIsNone(by_street["PEGA NA RUA X"]["zip"])
        self.assertEqual(self.plan["flags"]["CEP_INVALIDO_REMOVIDO"], 1)
        self.assertEqual(by_street["RUA DAS FLORES"]["zip"], "83709200")
        self.assertEqual(by_street["RUA DAS FLORES"]["complement"], "Cond. SOL, Bl. B, Ap. 12")

    def test_sql_is_idempotent_and_resolves_the_final_customer_through_legacy_codes(self):
        sql = addr.build_sql(self.plan)
        self.assertIn("ON CONFLICT DO NOTHING", sql)
        self.assertIn("JOIN customer_legacy_codes l", sql)
        self.assertEqual(sql, addr.build_sql(addr.build_plan(DUMP)))
        with tempfile.TemporaryDirectory() as tmp:
            summary = addr.write_outputs(self.plan, Path(tmp))
            self.assertTrue((Path(tmp) / "carga_enderecos.sql").exists())
        self.assertEqual(summary["a_importar"], 2)


if __name__ == "__main__":
    unittest.main()
