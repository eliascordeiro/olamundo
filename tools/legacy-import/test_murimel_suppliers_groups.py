import tempfile
import unittest
from pathlib import Path

import murimel_groups as groups
import murimel_suppliers as suppliers

SUPPLIER_COLUMNS = ("codigo", "fornecedor", "cnpj", "ie", "endereco", "bairro", "cep", "cidade", "uf", "fone", "fax",
                    "email", "contato")


def supplier_row(**values):
    defaults = {c: "" for c in SUPPLIER_COLUMNS}
    defaults.update(values)
    return " (" + ",".join("'" + str(defaults[c]).replace("\\", "\\\\").replace("'", "''") + "'" for c in SUPPLIER_COLUMNS) + ")"


SUPPLIERS = "INSERT INTO `easy_fornecedor` (" + ",".join(f"`{c}`" for c in SUPPLIER_COLUMNS) + ") VALUES\n" + ",\n".join([
    supplier_row(codigo=1, fornecedor="MADEIREIRA  SUL", cnpj="11.222.333/0001-81", fone="(41) 3797-8329", uf="pr",
                 contato="JOAO", cep="82770-000", email="Vendas@Sul.com"),
    supplier_row(codigo=2, fornecedor="MADEIREIRA SUL LTDA", cnpj="11222333000181", cidade="CURITIBA", ie="123"),
    supplier_row(codigo=3, fornecedor="DOC RUIM", cnpj="12.345.678/0001-00", fone="telefone", email="invalido"),
    supplier_row(codigo=4, fornecedor="X"),
    supplier_row(codigo=5, fornecedor="SEM DOC"),
]) + ";\n"

GROUP_DUMP = (
    "INSERT INTO `easy_grupo` (`codigo`,`grupo`,`comissao`,`descmontagem`,`mk`,`desconto_pauta`,`desconto_flexivel`,"
    "`comissao_montagem`,`ncm`) VALUES\n"
    " ('1','ELETRO','1.00','0.00','40.00','0.00','20.00','0.00','8418.10.00'),\n"
    " ('2','SOFA','0.00','41.00','130.00','0.00','0.00','6.50',''),\n"
    " ('3','X','0.00','0.00','0.00','0.00','0.00','0.00',''),\n"
    " ('4','FAIXA RUIM','150.00','0.00','5000.00','0.00','0.00','0.00','');\n"
    "INSERT INTO `easy_subgrupo` (`codgrupo`,`codsubgrupo`,`mk`,`subgrupo`,`margem`,`desconto_pauta`,`desconto_flexivel`) VALUES\n"
    " ('1','1','10.00','GELADEIRA','25','0.00','5.00'),\n"
    " ('1','2','10.00','geladeira','25','0.00','5.00'),\n"
    " ('2','1','0.00','RETRATIL','0','0.00','0.00'),\n"
    " ('9','1','0.00','ORFAO','0','0.00','0.00');\n"
    "INSERT INTO `easy_produtos` (`codigo`,`descricao`,`grupo`,`subgrupo`) VALUES\n"
    " ('10010001','GELADEIRA 1','1','1'),\n"
    " ('20010001','SOFA 1','2','1'),\n"
    " ('20990001','SOFA SEM SUB','2','99'),\n"
    " ('90010001','GRUPO INEXISTENTE','9','1');\n"
)


class SupplierTests(unittest.TestCase):
    def setUp(self):
        self.plan = suppliers.build_plan(SUPPLIERS)
        self.by_code = {r["code"]: r["data"] for r in self.plan["suppliers"]}

    def test_duplicate_documents_are_merged_and_completed(self):
        self.assertEqual(sorted(self.by_code), ["1", "3", "5"])
        main = self.by_code["1"]
        self.assertEqual(main["document"], "11222333000181")
        self.assertEqual(main["city"], "CURITIBA")
        self.assertEqual(main["state_registration"], "123")
        self.assertEqual(main["email"], "vendas@sul.com")
        self.assertEqual(main["state"], "PR")
        self.assertEqual(dict(self.plan["legacy"])["2"], "1")
        self.assertEqual(self.plan["merges"][0]["nomes_com_palavra_em_comum"], "sim")

    def test_invalid_document_and_fields_become_empty_and_short_names_are_dropped(self):
        bad = self.by_code["3"]
        self.assertIsNone(bad["document"])
        self.assertIsNone(bad["phone"])
        self.assertIsNone(bad["email"])
        self.assertEqual(self.plan["flags"]["DOCUMENTO_INVALIDO"], 1)
        self.assertEqual(self.plan["discarded"], [{"codigo_legado": "4", "motivo": "FORNECEDOR_SEM_NOME"}])

    def test_sql_and_outputs(self):
        sql = suppliers.build_sql(self.plan)
        self.assertTrue(sql.rstrip().endswith("COMMIT;"))
        self.assertEqual(sql.count("ON CONFLICT DO NOTHING"), 2)
        self.assertIn("'supplier'", sql)
        with tempfile.TemporaryDirectory() as folder:
            summary = suppliers.write_outputs(self.plan, Path(folder))
            self.assertEqual(summary["fornecedores_a_criar"], 3)
            self.assertTrue((Path(folder) / "carga_fornecedores.sql").exists())
            self.assertNotIn("MADEIREIRA", (Path(folder) / "fornecedores_unidos.csv").read_text(encoding="utf-8"))


class GroupTests(unittest.TestCase):
    def setUp(self):
        self.plan = groups.build_plan(GROUP_DUMP)

    def test_groups_subgroups_and_ranges(self):
        self.assertEqual(sorted(self.plan["groups"]), ["1", "2", "4"])
        eletro = self.plan["groups"]["1"]
        self.assertEqual(str(eletro["markup"]), "40.00")
        self.assertEqual(eletro["ncm"], "8418.10.00")
        self.assertIsNone(self.plan["groups"]["2"]["ncm"])
        bad = self.plan["groups"]["4"]
        self.assertEqual(str(bad["commission"]), "0.00")
        self.assertEqual(str(bad["markup"]), "0.00")
        self.assertEqual(self.plan["flags"]["COMISSAO_FORA_DA_FAIXA"], 1)
        self.assertEqual(self.plan["flags"]["MARKUP_FORA_DA_FAIXA"], 1)

    def test_orphan_subgroup_and_short_group_are_discarded_duplicate_names_kept(self):
        kinds = {(d["entidade"], d["motivo"]) for d in self.plan["discarded"]}
        self.assertIn(("grupo", "GRUPO_NOME_INVALIDO"), kinds)
        self.assertIn(("subgrupo", "GRUPO_INEXISTENTE_OU_DESCARTADO"), kinds)
        self.assertEqual(len(self.plan["subgroups"]), 3)
        self.assertEqual(self.plan["flags"]["SUBGRUPO_NOME_REPETIDO_NO_GRUPO"], 1)

    def test_product_links_only_for_existing_groups_and_subgroups(self):
        self.assertEqual(self.plan["links"], [("10010001", "1", "1"), ("20010001", "2", "1"), ("20990001", "2", None)])

    def test_sql_is_idempotent_and_never_overwrites_existing_classification(self):
        sql = groups.build_sql(self.plan)
        self.assertTrue(sql.rstrip().endswith("COMMIT;"))
        self.assertIn("p.group_id IS NULL", sql)
        self.assertIn("ON CONFLICT DO NOTHING", sql)
        self.assertIn("'10010001'", sql)
        with tempfile.TemporaryDirectory() as folder:
            summary = groups.write_outputs(self.plan, Path(folder))
            self.assertEqual(summary["grupos"], 3)
            self.assertEqual(summary["subgrupos"], 3)


if __name__ == "__main__":
    unittest.main()
