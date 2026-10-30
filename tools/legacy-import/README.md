# Importador do legado Murimel

Python 3.10+, sem dependencias. **Nenhum script conecta a banco**: eles leem o dump
e escrevem arquivos. Quem aplica o SQL e voce, primeiro em um banco de teste.
Nunca versione o dump nem as saidas (`legacy-import-out/` esta no `.gitignore`).

```bash
python3 -m unittest discover -s tools/legacy-import                              # testes
python3 tools/legacy-import/murimel_dryrun.py  "dump.sql" --out ./legacy-import-out  # 1) simulacao
python3 tools/legacy-import/murimel_import.py  "dump.sql" --out ./legacy-import-out  # 2) gera o SQL
```

## 1) Simulacao (`murimel_dryrun.py`)
Mede o dump e lista pendencias (`relatorio.md`, `pendencias.csv`, `plano.json`).

## 2) Carga (`murimel_import.py`)
Gera, em `--out`:

| Arquivo | Conteudo |
| --- | --- |
| `carga_murimel.sql` | Lojas, produtos, vendedores e saldos de abertura, em uma transacao, idempotente |
| `anexar_proprietario.sql` | Concede OWNER das lojas importadas ao proprietario "Móveis Murimel" **depois que ele ativar a conta** |
| `descartados.csv` | Tudo que nao foi importado, com o motivo |
| `vendedores.csv` | Vendedores por pessoa/loja; coluna `email` para o proprietario preencher |
| `relatorio_carga.md` | Contagens do que o SQL grava |

Decisoes aplicadas: produto so nas lojas com saldo valido ou venda; saldos invalidos
(produto inexistente, negativo, loja descartada) **nao** sao importados; loja 1 e o que
depende dela sao descartados; nomes acima de 120 caracteres sao encurtados sem partir
palavra e o nome completo vai para a descricao; mesmo nome em varias lojas = mesma pessoa.

Garantias: senhas do legado nunca sao lidas para as saidas; flag master nao vira
proprietario; vendedores entram **sem conta** (o proprietario informa o e-mail e envia
o convite); clientes, vendas, pagamentos, recibos, montagens, grupos e custos ficam
fora. Ids sao deterministicos (uuid5) e a V9 grava `legacy_code` em lojas e vendedores.

### Como aplicar (teste local descartavel)
```bash
docker run -d --name murimel-pg-test -e POSTGRES_PASSWORD=test -p 127.0.0.1:55433:5432 postgres:17-alpine
for f in src/main/resources/db/migration/V*.sql; do   # na ordem V1..V9
  docker exec -i murimel-pg-test psql -U postgres -v ON_ERROR_STOP=1 -q < "$f"; done
docker exec -i murimel-pg-test psql -U postgres -v ON_ERROR_STOP=1 -q < legacy-import-out/carga_murimel.sql
docker rm -f murimel-pg-test
```
(Use a ordem numerica das migracoes: `V1`..`V9`, nao a alfabetica se passar de `V9`.)
Em producao, rode as migracoes pelo proprio backend (Flyway) e aplique o
`carga_murimel.sql` com `psql` somente depois de revisar o `descartados.csv`.
