import tempfile
import unittest
from datetime import date
from pathlib import Path

import murimel_customers as cust

COLUMNS = ("codigo", "nome", "datanasc", "datacad", "rg", "cpf", "filiacao", "conjuge", "endereco", "bairro", "cidade",
           "estado", "cep", "fonecasa", "empresa", "salario", "email", "numero", "cnpj", "ie", "celular", "bloco",
           "apto", "condominio", "ibge", "complemento")


def row(**values):
    defaults = {c: "" for c in COLUMNS}
    defaults.update({"datanasc": "0001-01-01", "datacad": "0001-01-01", "salario": "0.00"})
    defaults.update(values)
    cells = ",".join("'" + str(defaults[c]).replace("\\", "\\\\").replace("'", "''") + "'" for c in COLUMNS)
    return f" ({cells})"


DUMP = "INSERT INTO `easy_cliente` (" + ",".join(f"`{c}`" for c in COLUMNS) + ") VALUES\n" + ",\n".join([
    row(codigo=1, nome="MARIA   SOUZA", cpf="529.982.247-25", datacad="2020-05-01", email="Maria@Example.com",
        cep="82770-000", estado="pr", celular="(41) 99999-1234", bloco="B", apto="12", salario="5000.00",
        filiacao="MAE DA MARIA", empresa="ACME", conjuge="JOAO"),
    row(codigo=2, nome="MARIA SOUZA LIMA", cpf="52998224725", datacad="2022-01-01", rg="1234567", cidade="CURITIBA"),
    row(codigo=3, nome="OUTRA PESSOA", cpf="111.111.111-11"),
    row(codigo=4, nome="EMPRESA LTDA", cnpj="11.222.333/0001-81", datanasc="2999-01-01"),
    row(codigo=5, nome="X"),
    row(codigo=6, nome="SOFÃ¡ CLIENTE", fonecasa="telefone", email="invalido", cep="123", estado="parana"),
    row(codigo=7, nome="SEM DOC", cpf="", cnpj=""),
]) + ";\n"


class HelperTests(unittest.TestCase):
    def test_tax_id_validation(self):
        self.assertTrue(cust.tax_id_valid("52998224725"))
        self.assertTrue(cust.tax_id_valid("11222333000181"))
        self.assertFalse(cust.tax_id_valid("11111111111"))
        self.assertFalse(cust.tax_id_valid("12345678900"))
        self.assertFalse(cust.tax_id_valid(""))

    def test_dates_and_phones(self):
        self.assertIsNone(cust.parse_date("0001-01-01"))
        self.assertIsNone(cust.parse_date("0000-00-00"))
        self.assertEqual(cust.parse_date("1980-05-17"), date(1980, 5, 17))
        self.assertIsNone(cust.parse_date("2999-01-01"))
        self.assertEqual(cust.clean_phone("(41) 3797-8329"), "(41) 3797-8329")
        self.assertIsNone(cust.clean_phone("telefone"))


class PlanTests(unittest.TestCase):
    def setUp(self):
        self.plan = cust.build_plan(DUMP)
        self.by_code = {r["code"]: r["data"] for r in self.plan["customers"]}

    def test_duplicates_with_valid_document_are_merged_and_completed(self):
        self.assertEqual(sorted(self.by_code), ["1", "3", "4", "6", "7"])
        merged = self.plan["merges"][0]
        self.assertEqual(merged["cadastros"], 2)
        self.assertEqual(merged["nomes_iguais"], "nao")
        self.assertEqual(merged["nomes_com_palavra_em_comum"], "sim")
        main = self.by_code[merged["codigo_principal"]]
        self.assertEqual(main["document"], "52998224725")
        self.assertEqual(main["email"], "maria@example.com")
        self.assertEqual(main["zip"], "82770000")
        self.assertEqual(main["mobile"], "(41) 99999-1234")
        self.assertEqual(main["rg"], "1234567")
        self.assertEqual({code: main for code, main in self.plan["legacy"]}["1"], merged["codigo_principal"])

    def test_placeholder_document_never_merges_and_is_dropped(self):
        self.assertIsNone(self.by_code["3"]["document"])
        self.assertEqual(self.plan["flags"]["DOCUMENTO_INVALIDO"], 1)

    def test_cnpj_future_birth_and_invalid_fields(self):
        self.assertEqual(self.by_code["4"]["document"], "11222333000181")
        self.assertIsNone(self.by_code["4"]["birth_date"])
        bad = self.by_code["6"]
        self.assertEqual(bad["name"], "SOFá CLIENTE")
        self.assertIsNone(bad["phone"])
        self.assertIsNone(bad["email"])
        self.assertIsNone(bad["zip"])
        self.assertIsNone(bad["state"])

    def test_short_names_are_discarded_and_sensitive_fields_never_loaded(self):
        self.assertEqual(self.plan["discarded"], [{"codigo_legado": "5", "motivo": "CLIENTE_SEM_NOME"}])
        sql = cust.build_sql(self.plan)
        for secret in ("5000.00", "MAE DA MARIA", "ACME", "JOAO"):
            self.assertNotIn(secret, sql)

    def test_sql_is_transactional_idempotent_and_traces_every_code(self):
        sql = cust.build_sql(self.plan)
        self.assertTrue(sql.rstrip().endswith("COMMIT;"))
        self.assertEqual(sql.count("ON CONFLICT DO NOTHING"), 2)
        self.assertEqual(len(self.plan["legacy"]), 6)
        self.assertIn("WHERE EXISTS (SELECT 1 FROM customers c WHERE c.id = v.customer_id)", sql)

    def test_outputs_written_without_personal_data(self):
        with tempfile.TemporaryDirectory() as folder:
            out = Path(folder)
            summary = cust.write_outputs(self.plan, out)
            for name in ("carga_clientes.sql", "clientes_descartados.csv", "clientes_unidos.csv", "relatorio_clientes.md"):
                self.assertTrue((out / name).exists(), name)
            self.assertEqual(summary["clientes_a_criar"], 5)
            self.assertEqual(summary["cadastros_unidos"], 1)
            for name in ("clientes_descartados.csv", "clientes_unidos.csv", "relatorio_clientes.md"):
                text = (out / name).read_text(encoding="utf-8")
                self.assertNotIn("MARIA", text)
                self.assertNotIn("529", text)


if __name__ == "__main__":
    unittest.main()
