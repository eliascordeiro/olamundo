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

### 3) Clientes (`murimel_customers.py`)
Gera `carga_clientes.sql` (+ `clientes_descartados.csv`, `clientes_unidos.csv`, `relatorio_clientes.md`) para a
empresa "Móveis Murimel". **Depende da V10** (tabelas `customers` e `customer_legacy_codes`) e do proprietario
da empresa existir (semeado pela V7). Decisoes: todos os clientes; so dados minimos (sem salario, filiacao,
conjuge, empresa onde trabalha nem referencias); CPF/CNPJ valido repetido e unido automaticamente (campos vazios
do principal sao completados pelos demais e **todos os codigos antigos ficam rastreados**); documento
invalido/placeholder nunca une ninguem e entra vazio; campos invalidos (e-mail, telefone, CEP, UF, datas
`0001-01-01`) viram vazios, nunca inventados; acentuacao dupla codificada e corrigida. Os arquivos de
apoio contem so codigos, sem dados pessoais. Aplicar: `psql ... -f carga_clientes.sql` (idempotente, ~30 s).

### 4) Fornecedores (`murimel_suppliers.py`) e Grupos/Subgrupos (`murimel_groups.py`)
Dependem da **V11**. Geram `carga_fornecedores.sql` e `carga_grupos.sql` (+ relatorios e CSVs so com codigos).
- Fornecedores: mesmas regras dos clientes (CNPJ/CPF valido repetido e unido automaticamente, codigos antigos
  rastreados em `legacy_codes`, documento invalido nunca une ninguem, campos invalidos viram vazios).
- Grupos/subgrupos: nome, NCM e percentuais do legado (valores fora da faixa viram 0 e sao reportados). **Classifica os
  produtos ja importados** (por SKU = codigo legado), mas so os que ainda estao sem grupo, sem sobrescrever
  classificacao feita depois. Execute depois da carga de produtos.
- Ordem completa: `carga_murimel.sql` -> `anexar_proprietario.sql` (dono ativo) -> V10/V11 pelo backend ->
  `carga_clientes.sql`, `carga_fornecedores.sql`, `carga_grupos.sql`. Todas idempotentes.

### 5) Enderecos de entrega (`murimel_addresses.py`)
Gera `carga_enderecos.sql` (+ `enderecos_nao_importados.csv`, `relatorio_enderecos.md`). **Depende da V12** e da
carga de clientes (acha o cliente final por `customer_legacy_codes`, entao unioes de clientes sao respeitadas).
Decisoes: enderecos identicos ao endereco principal do cliente nao sao importados; sem rua e descartado; CEP
invalido (`---  -  -`) importa o endereco sem CEP; cliente inexistente/nao importado e descartado; condominio,
bloco e apto viram o complemento (`Cond. X, Bl. Y, Ap. Z`). Ids deterministicos; idempotente.

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
