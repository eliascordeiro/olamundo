import tempfile
import unittest
from pathlib import Path

import murimel_dryrun as dry

DUMP = """
LOCK TABLES `easy_loja` WRITE;
INSERT INTO `easy_loja` (`codigo`,`loja`,`deposito`) VALUES
 (3,'LOJA TRES',''),
 (2,'DEPOSITO','X');
UNLOCK TABLES;
LOCK TABLES `easy_vendedor` WRITE;
INSERT INTO `easy_vendedor` (`codigo`,`loja`,`vendedor`,`senha`,`i100`,`imaster`) VALUES
 (1,3,'ANA',1234,1,1),
 (2,3,'Ana',4321,0,0),
 (3,9,'BIA',1111,1,0);
UNLOCK TABLES;
LOCK TABLES `easy_produtos` WRITE;
INSERT INTO `easy_produtos` (`codigo`,`descricao`,`venda`,`codfab`) VALUES
 (10,'SOFÃ¡ CAMA   TRES','199.90',''),
 (11,'MESA; ''REDONDA''\\nGRANDE','0.00','X1'),
 (12,'{long}','10.00','Y');
UNLOCK TABLES;
LOCK TABLES `easy_lojasaldo` WRITE;
INSERT INTO `easy_lojasaldo` (`codpro`,`codloja`,`saldo`) VALUES
 (10,3,5.00),(10,2,0.00),(11,3,-2.00),(99,3,4.00),(10,9,1.00),(11,2,1.50);
UNLOCK TABLES;
LOCK TABLES `easy_itens` WRITE;
INSERT INTO `easy_itens` (`codpro`,`loja`) VALUES (10,3),(77,3);
UNLOCK TABLES;
LOCK TABLES `easy_vendas` WRITE;
INSERT INTO `easy_vendas` (`data`,`loja`,`codven`) VALUES ('2025-01-02',3,1),('2025-03-04',9,50),('0000-00-00',3,1);
UNLOCK TABLES;
""".replace("{long}", "X" * 130)


class ParserTests(unittest.TestCase):
    def test_strings_keep_escapes_semicolons_and_quotes(self):
        rows = dry.load_tables(DUMP)["easy_produtos"]
        self.assertEqual(rows[1]["descricao"], "MESA; 'REDONDA'\nGRANDE")
        self.assertEqual(len(rows), 3)

    def test_unwanted_tables_are_skipped(self):
        text = "INSERT INTO `easy_cliente` (`a`) VALUES (1,'x;y');\nUNLOCK TABLES;\n" + DUMP
        self.assertEqual(len(dry.load_tables(text)["easy_loja"]), 2)

    def test_mojibake_is_fixed_and_legit_text_is_kept(self):
        stats = dry.TextStats()
        self.assertEqual(stats.clean("t", "SOFÃ¡  CAMA"), "SOFá CAMA")
        self.assertEqual(stats.clean("t", "SÃO PAULO"), "SÃO PAULO")
        self.assertEqual(stats.counts[("t", "corrigido")], 1)
        self.assertEqual(stats.counts[("t", "irrecuperavel")], 0)


class AnalysisTests(unittest.TestCase):
    def setUp(self):
        self.result = dry.analyze(DUMP)
        self.kinds = {item["tipo"] for item in self.result["pending"]}
        self.summary = self.result["summary"]

    def test_plan_counts_and_stock_rules(self):
        self.assertEqual(self.summary["movimentos_de_abertura_planejados"], 1)
        self.assertEqual(self.summary["unidades_planejadas"], 5)
        self.assertEqual(self.summary["saldos_zero_ignorados"], 1)
        self.assertEqual(self.summary["problemas_de_saldo"], {
            "SALDO_NEGATIVO": 1, "SALDO_PRODUTO_INEXISTENTE": 1,
            "SALDO_LOJA_SEM_CADASTRO": 1, "SALDO_FRACIONADO": 1})

    def test_pending_items_are_reported_not_silently_fixed(self):
        for kind in ("PRODUTO_NOME_LONGO", "PRODUTO_PRECO_ZERO", "LOJA_SEM_CADASTRO", "VENDEDOR_NOME_DUPLICADO",
                     "VENDEDOR_LOJA_SEM_CADASTRO", "VENDEDOR_SEM_EMAIL"):
            self.assertIn(kind, self.kinds)
        self.assertEqual(self.summary["lojas_sem_cadastro"], ["9"])

    def test_sellers_never_carry_passwords_and_master_is_not_owner(self):
        sellers = self.result["sellers"]
        self.assertEqual(len(sellers), 3)
        self.assertTrue(all("senha" not in seller for seller in sellers))
        self.assertEqual(self.summary["vendedores_imaster"], 1)
        self.assertEqual(sellers[0]["email"], "")

    def test_history_is_only_measured(self):
        self.assertEqual(self.summary["vendas_no_historico"], 3)
        self.assertEqual(self.summary["vendas_periodo"], ["2025-01-02", "2025-03-04"])
        self.assertEqual(self.summary["vendas_com_vendedor_inexistente"], 1)
        self.assertEqual(self.summary["itens_com_produto_inexistente"], 1)

    def test_outputs_contain_no_passwords(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "dump.sql"
            source.write_text(DUMP, encoding="utf-8")
            out = Path(folder) / "out"
            dry.write_outputs(self.result, out, source)
            for name in ("relatorio.md", "pendencias.csv", "vendedores.csv", "plano.json"):
                content = (out / name).read_text(encoding="utf-8")
                for secret in ("1234", "4321", "1111"):
                    self.assertNotIn(f",{secret}", content)
            self.assertTrue((out / "relatorio.md").read_text(encoding="utf-8").startswith("# Dry-run"))


if __name__ == "__main__":
    unittest.main()
