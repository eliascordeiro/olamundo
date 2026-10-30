import tempfile
import unittest
from pathlib import Path

import murimel_import as imp

DUMP = """
INSERT INTO `easy_loja` (`codigo`,`loja`,`deposito`) VALUES
 (2,'DEPOSITO','X'),
 (3,'LOJA TRES','');
INSERT INTO `easy_vendedor` (`codigo`,`loja`,`vendedor`,`senha`,`imaster`) VALUES
 (1,3,'ANA',1234,1),
 (2,2,'ana',4321,0),
 (3,1,'BIA',1111,0);
INSERT INTO `easy_produtos` (`codigo`,`descricao`,`venda`,`codfab`) VALUES
 (10,'SOFA   CAMA','199.90','FAB-1'),
 (11,'{long}','10.00',''),
 (12,'','5.00',''),
 (13,'SEM SALDO NEM VENDA','5.00',''),
 (14,'O''BRIEN','0.00','');
INSERT INTO `easy_lojasaldo` (`codpro`,`codloja`,`saldo`) VALUES
 (10,3,5.00),(10,2,0.00),(11,3,-2.00),(99,3,4.00),(10,1,3.00),(14,2,2.00);
INSERT INTO `easy_itens` (`codpro`,`loja`) VALUES (11,3),(77,3),(10,1);
""".replace("{long}", ("PALAVRA " * 20).strip())


class HelperTests(unittest.TestCase):
    def test_shorten_does_not_split_words_and_is_exact_when_short(self):
        self.assertEqual(imp.shorten("CURTO"), ("CURTO", False))
        name = "PALAVRA " * 20
        short, was_short = imp.shorten(name.strip())
        self.assertTrue(was_short)
        self.assertLessEqual(len(short), 120)
        self.assertTrue(short.endswith("PALAVRA"))
        hard, _ = imp.shorten("X" * 200)
        self.assertEqual(len(hard), 120)

    def test_sql_text_escapes_quotes(self):
        self.assertEqual(imp.sql_text("O'BRIEN"), "'O''BRIEN'")
        with self.assertRaises(ValueError):
            imp.sql_text("a\0b")

    def test_ids_are_deterministic(self):
        self.assertEqual(imp.uid("store", "3"), imp.uid("store", "3"))
        self.assertNotEqual(imp.uid("store", "3"), imp.uid("store", "2"))


class PlanTests(unittest.TestCase):
    def setUp(self):
        self.plan = imp.build_plan(DUMP)
        self.kinds = [d["tipo"] for d in self.plan["discarded"]]

    def test_discarded_store_and_everything_depending_on_it(self):
        self.assertEqual(set(self.plan["stores"]), {"2", "3"})
        self.assertIn("LOJA_DESCARTADA", self.kinds)
        self.assertIn("SALDO_LOJA_DESCARTADA", self.kinds)
        self.assertIn("VENDEDOR_LOJA_DESCARTADA", self.kinds)
        self.assertEqual([s["code"] for s in self.plan["sellers"]], ["1", "2"])
        self.assertNotIn(("1", "10"), self.plan["pairs"])

    def test_invalid_stock_is_listed_not_imported(self):
        self.assertEqual(self.plan["opening"], {("3", "10"): 5, ("2", "14"): 2})
        self.assertIn("SALDO_NEGATIVO", self.kinds)
        self.assertIn("SALDO_PRODUTO_INEXISTENTE", self.kinds)
        self.assertIn("PRODUTO_SEM_NOME", self.kinds)

    def test_products_only_where_there_is_stock_or_sale(self):
        self.assertEqual(self.plan["pairs"], [("2", "14"), ("3", "10"), ("3", "11")])
        self.assertNotIn("13", {code for _, code in self.plan["pairs"]})

    def test_long_name_is_shortened_and_full_name_kept_in_description(self):
        product = self.plan["products"]["11"]
        self.assertTrue(product["encurtado"])
        self.assertLessEqual(len(product["nome"]), 120)
        self.assertTrue(product["descricao"].startswith("Nome completo no legado: PALAVRA"))
        self.assertIn("Codigo de fabrica: FAB-1", self.plan["products"]["10"]["descricao"])

    def test_same_name_in_several_stores_is_one_person(self):
        people = {s["pessoa"] for s in self.plan["sellers"]}
        self.assertEqual(people, {"ANA"})


class SqlTests(unittest.TestCase):
    def setUp(self):
        self.plan = imp.build_plan(DUMP)
        self.sql = imp.build_sql(self.plan)

    def test_sql_is_transactional_idempotent_and_has_no_secrets(self):
        self.assertTrue(self.sql.rstrip().endswith("COMMIT;"))
        self.assertIn("BEGIN;", self.sql)
        self.assertEqual(self.sql.count("ON CONFLICT"), 3)
        self.assertIn("WHERE EXISTS (SELECT 1 FROM catalog_products", self.sql)
        for secret in ("1234", "4321", "1111"):
            self.assertNotIn(f"'{secret}'", self.sql)
        self.assertIn("'O''BRIEN'", self.sql)
        self.assertIn("0.00", self.sql)

    def test_opening_movement_only_for_new_balances(self):
        self.assertIn("WITH created AS (", self.sql)
        self.assertIn("NOT EXISTS (SELECT 1 FROM inventory_balances", self.sql)

    def test_attach_owner_requires_active_owner(self):
        sql = imp.attach_owner_sql()
        self.assertIn("o.status = 'ACTIVE'", sql)
        self.assertIn("ON CONFLICT (store_id, user_id) DO NOTHING", sql)

    def test_outputs_written(self):
        with tempfile.TemporaryDirectory() as folder:
            out = Path(folder)
            summary = imp.write_outputs(self.plan, out)
            for name in ("carga_murimel.sql", "anexar_proprietario.sql", "descartados.csv",
                         "vendedores.csv", "relatorio_carga.md"):
                self.assertTrue((out / name).exists(), name)
            self.assertEqual(summary["saldos_abertura"], 2)
            self.assertNotIn("senha", (out / "vendedores.csv").read_text(encoding="utf-8").lower())


if __name__ == "__main__":
    unittest.main()
