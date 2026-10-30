# Importador do legado Murimel (dry-run)

Le o dump MySQL do legado e gera relatorios do que **seria** carregado
(lojas, vendedores, produtos e saldos de abertura) e das pendencias. **Nao grava
em nenhum banco e nao altera a aplicacao.** Python 3.10+, sem dependencias.

```bash
python3 tools/legacy-import/murimel_dryrun.py "/caminho/dump.sql" --out ./legacy-import-out
python3 -m unittest discover -s tools/legacy-import      # testes
```

Saidas (em `--out`, pasta ignorada pelo Git; **nunca versione o dump nem as saidas**):

| Arquivo | Conteudo |
| --- | --- |
| `relatorio.md` | Resumo, pendencias por tipo, historico medido e decisoes abertas |
| `pendencias.csv` | Uma linha por pendencia (`BLOQUEIA`, `REVISAR`, `INFO`), com codigo legado |
| `vendedores.csv` | Vendedores para o proprietario preencher o e-mail (coluna `email`) |
| `plano.json` | Contagens para comparar execucoes |

Garantias: senhas (`easy_vendedor.senha`, `easy_senha`, `easy_usuario`) nao entram
em nenhuma saida; clientes e historico de vendas nao sao importados (so medidos);
flag master nunca vira proprietario; nada e truncado ou corrigido em silencio —
problemas viram pendencia. Textos com acentuacao dupla codificada sao corrigidos
quando a reversao e exata; o dump atual so tem esse problema em clientes/enderecos.

Regras de saldo do destino: inteiro, nao negativo, ate 2147483647. Saldos zerados
sao ignorados; de produto inexistente, negativos ou de loja sem cadastro viram pendencia.
