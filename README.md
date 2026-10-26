# Nexo Business - API

Spring Boot 4, Java 21, Spring Security, PostgreSQL e MongoDB. Autenticacao por sessao opaca
em cookie HttpOnly, sem JWT no navegador. Senhas recebem BCrypt com custo 12.
Usuarios e sessoes sao persistidos no MongoDB; o indice unico de e-mail e o
indice TTL de sessoes sao criados na inicializacao. A expiracao tambem e
verificada em cada leitura, independentemente do monitor TTL.

## Etapa 1 - fundacao multiloja

Monolito modular, sem microservicos. O modulo `stores` separa API, aplicacao,
dominio e infraestrutura JDBC. PostgreSQL guarda lojas e vinculos; Flyway
versiona o esquema. MongoDB continua responsavel por usuarios e sessoes,
sem migrar, apagar ou atribuir dados antigos a uma loja automaticamente.
Essa convivencia e transitoria, nao uma recomendacao de dois bancos para todos
os futuros modulos. Pedidos, estoque e pagamentos devem usar o nucleo relacional.

O mural usado para testar MongoDB foi removido, incluindo GET/POST
`/api/mensagens`, modelo e repositorio. Autenticacao e sessoes ainda dependem
do MongoDB: mantenha o servico e as variaveis existentes no Railway.
Esta remocao nao apaga colecoes ou dados antigos e nao exige migracao SQL.
Retirar MongoDB requer uma migracao separada de contas/sessoes, preservando
IDs dos usuarios referenciados nas lojas, pedidos e auditoria.

Uma conta participa de varias lojas, com papel por vinculo. Criar uma loja
atribui OWNER ao criador; somente OWNER pode listar ou adicionar membros
existentes como MANAGER ou STAFF. As permissoes de catalogo da etapa 2
estao descritas abaixo; papeis nunca valem globalmente para outras lojas.

O backend resolve o ID imutavel do usuario pela sessao, nunca pelo corpo da
requisicao. Listagens retornam apenas lojas com vinculo; acessar uma loja sem
vinculo retorna 404. Permissoes sao consultadas no PostgreSQL a cada operacao,
sem transformar OWNER em uma autoridade global na sessao MongoDB.

| Metodo | Rota | Acesso |
| --- | --- | --- |
| POST | `/api/stores` | Autenticado + CSRF; cria loja e OWNER na mesma transacao |
| GET | `/api/stores?page=0&size=20` | Somente lojas do usuario; size de 1 a 100 |
| GET | `/api/stores/{storeId}` | Membro da loja |
| GET | `/api/stores/{storeId}/members?page=0&size=20` | OWNER |
| POST | `/api/stores/{storeId}/members` | OWNER + CSRF; `{"email":"conta@example.com","role":"STAFF"}` |

Listagens usam `{content, page, size, hasNext}` com ordenacao estavel.
IDs de lojas sao UUID. Ainda nao existem exclusao de lojas, remocao de membros,
convites por e-mail, transferencia de propriedade ou alteracao de papeis.
Vinculos duplicados retornam 409 e nao alteram a permissao existente.
Nao ha FK entre PostgreSQL e MongoDB: eventual exclusao de contas precisara
de um fluxo explicito que trate seus vinculos.

## Etapa 2 - catalogo por loja

O modulo `catalog` mantem API, aplicacao, dominio e infraestrutura JDBC separados.
Usa a autorizacao compartilhada de `stores`, sem acessar o repositorio de usuarios
diretamente nem copiar papeis para a sessao. Todos os membros podem consultar;
somente OWNER e MANAGER podem cadastrar produtos.

| Metodo | Rota | Acesso |
| --- | --- | --- |
| GET | `/api/stores/{storeId}/products?page=0&size=20` | Membro; size de 1 a 100 |
| GET | `/api/stores/{storeId}/products/{productId}` | Membro; produto deve pertencer a loja indicada |
| POST | `/api/stores/{storeId}/products` | OWNER ou MANAGER + CSRF; retorna 201 com Location |

Exemplo de cadastro (preco decimal em texto e aceito para evitar conversao
para ponto flutuante no navegador):

```json
{
  "name": "Camiseta",
  "sku": "CAM-001",
  "description": "Camiseta de algodao",
  "price": "19.90"
}
```

- Nome de 2 a 120 caracteres; descricao opcional de ate 2000.
- SKU de ate 64 caracteres: letras ASCII, numeros, ponto, hifen e sublinhado,
  comecando por letra ou numero. Espacos externos sao removidos e o SKU e
  convertido para maiusculas. A unicidade e por loja, inclusive sob concorrencia;
  duplicacao retorna 409. O mesmo SKU pode existir em lojas diferentes.
- Preco entre 0 e 9999999999.99, com ate duas casas decimais, sem arredondamento.
  Java usa BigDecimal e PostgreSQL NUMERIC(12,2); moeda fixa BRL.
- A resposta inclui `id`, `storeId`, `name`, `sku`, `description`, `price`,
  `currency` e `createdAt`. Listagens usam `{content, page, size, hasNext}`,
  ordenadas por criacao e ID, sempre filtradas por loja.
- Sem vinculo retorna 404; STAFF tentando cadastrar recebe 403. As permissoes
  sao consultadas novamente a cada operacao.

A migracao V2 adiciona apenas `catalog_products` e seus indices, sem alterar
lojas, vinculos ou dados MongoDB. Ainda nao ha edicao/exclusao de produtos,
imagens, vitrine publica ou checkout.

## Etapa 3 - estoque por produto e loja

O modulo `inventory` separa API, aplicacao, dominio e infraestrutura JDBC.
O catalogo valida a existencia do produto na loja; `StoreAuthorization` consulta
o papel atual. OWNER e MANAGER registram entradas/saidas; STAFF so consulta.
Saldo inicial zero, inclusive para produtos existentes: quantidade fisica
deve ser registrada por uma entrada explicita, nunca presumida pela migracao.

Prefixo das rotas: `/api/stores/{storeId}/products/{productId}/stock`.

| Metodo | Sufixo | Contrato |
| --- | --- | --- |
| GET | nenhum | `{storeId, productId, quantity, version}` |
| GET | `/movements?page=0&size=20` | Historico `{content, page, size, hasNext}`, size de 1 a 100 |
| POST | `/movements` | OWNER/MANAGER + CSRF; operacao idempotente, retorna 200 |

Exemplo de entrada (gere um novo UUID por operacao intencional):

```json
{
  "requestId": "563422af-7713-448d-b21e-90694c984d12",
  "type": "IN",
  "quantity": "10",
  "reason": "Recebimento inicial conferido"
}
```

`type` aceita IN ou OUT. Quantidade inteira de 1 a 2147483647; saldo entre
0 e 2147483647. Motivo obrigatorio, de 2 a 500 caracteres apos trim.
Fracoes sao rejeitadas sem truncamento. Saida sem saldo e entrada acima do
limite retornam 409; nada e gravado parcialmente.

A resposta de movimentacao inclui `id` (requestId), `storeId`, `productId`,
`sequence`, `type`, `quantity`, `balanceAfter`, `reason`, `userId` resolvido pela
sessao e `createdAt` UTC. O historico e ordenado por sequencia decrescente,
sem depender da precisao do relogio; guarda responsavel e motivo imutaveis.
Paginacao por offset nao e um snapshot: novas movimentacoes podem deslocar paginas.

- Repetir requestId + mesmos dados + mesmo usuario, no mesmo produto/loja,
  devolve a movimentacao original sem alterar saldo, mesmo apos outras operacoes.
  Reutilizar o identificador com dados ou responsavel diferentes retorna 409.
  Permissao e sessao continuam sendo verificadas em reenvios.
- Uma transacao PostgreSQL bloqueia a linha de saldo por produto, verifica
  idempotencia, altera saldo e insere historico. Entradas iniciais concorrentes
  tambem sao serializadas. Falha no historico desfaz a alteracao do saldo.
- Chaves estrangeiras compostas impedem associar saldo/movimentacao a produto
  de outra loja. Leituras e escritas sempre usam storeId + productId.
- Nao existem edicao/exclusao de movimentacoes, ajuste direto de saldo,
  reservas ou transferencias entre lojas. A etapa 4 baixa estoque ao confirmar
  um pedido interno, nao ao receber pagamento.
  Correcao manual exige outra entrada/saida com motivo explicito.

## Etapa 4 - pedidos internos

O modulo `orders` organiza API, aplicacao, dominio e infraestrutura JDBC.
Ele usa os servicos de catalogo e estoque, sem acessar suas tabelas diretamente.
OWNER/MANAGER criam, confirmam e cancelam; todos os membros consultam. Nao e checkout:
sem pagamento, frete ou dados de cliente. Cancelamento integral esta na etapa 5.

| Metodo | Rota | Contrato |
| --- | --- | --- |
| POST | `/api/stores/{storeId}/orders` | Cria rascunho idempotente, 200, requer CSRF |
| GET | `/api/stores/{storeId}/orders?page=0&size=20` | Resumos paginados, size de 1 a 100, mais recentes primeiro |
| GET | `/api/stores/{storeId}/orders/{orderId}` | Resumo + itens, somente dentro da loja indicada |
| POST | `/api/stores/{storeId}/orders/{orderId}/confirm` | Confirma e baixa estoque atomicamente, 200, requer CSRF |
| POST | `/api/stores/{storeId}/orders/{orderId}/cancel` | Cancela integralmente com motivo, 200, requer CSRF; detalhes na etapa 5 |

Exemplo de criacao (requestId UUID gerado para este pedido):

```json
{
  "requestId": "fb26b679-8bab-479b-8718-8acb4f6f0500",
  "items": [
    {
      "productId": "a9e90980-c821-43ed-9432-a78b7804b12c",
      "quantity": "2"
    }
  ]
}
```

De 1 a 50 produtos distintos da loja, quantidade inteira de 1 a 2147483647.
Produtos repetidos/fracoes sao rejeitados, nao somados/arredondados.
O cliente nao define precos, total, moeda ou responsavel. Estes valores sao
resolvidos pelo backend; campos extras de preco nao substituem o catalogo.

A resposta de detalhe/criacao/confirmacao/cancelamento e `{summary, items}`. `summary` inclui
`id` (requestId), `storeId`, `status` DRAFT/CONFIRMED/CANCELLED, `currency` BRL, `total`,
`createdBy`, `createdAt`, `confirmedBy` e `confirmedAt` (nulos antes da confirmacao).
Na etapa 5, inclui tambem `cancelledBy`, `cancelledAt` e `cancellationReason`,
nulos antes do cancelamento.
Cada item inclui `productId`, `productName`, `sku`, `quantity`, `unitPrice` e
`lineTotal`. Valores monetarios de pedidos sao **strings decimais com duas casas**,
para preservar exatidao tambem no JSON/navegador; Java usa BigDecimal, PostgreSQL
NUMERIC(12,2) para preco e NUMERIC(24,2) para subtotais/total. O limite de itens
e quantidades cabe nessa precisao. Listagens retornam apenas resumos em
`{content, page, size, hasNext}`.

- Criar rascunho copia nome, SKU e preco atual do catalogo. Esses snapshots nao
  mudam se o catalogo for alterado depois, inclusive na confirmacao. Rascunho
  salvo nao e editavel e nao reserva nem baixa estoque.
- Repetir requestId + mesmos produtos/quantidades (ordem irrelevante) + mesmo
  criador retorna o pedido existente, mesmo ja confirmado/cancelado, sem recalcular preco.
  Dados ou criador diferentes com o mesmo ID na mesma loja retornam 409.
- Confirmar bloqueia o pedido e processa produtos em ordem canonica de UUID,
  na mesma transacao das saidas e historico. Falta de saldo em qualquer item
  retorna 409 e desfaz todas as saidas; o pedido continua DRAFT para nova tentativa.
- Confirmacoes concorrentes/repetidas devolvem o mesmo pedido CONFIRMED sem
  nova baixa. Pedidos concorrentes respeitam o saldo disponivel. Cada saida
  registra o confirmador e motivo `Confirmacao do pedido {orderId}`.
- Autorizacao e consultada em cada chamada, inclusive reenvios. Outro membro
  OWNER/MANAGER pode confirmar um rascunho da mesma loja; createdBy e confirmedBy
  preservam os dois responsaveis. Sem vinculo ou ID de outra loja retorna 404.
- Nao interprete CONFIRMED como pago, entregue, faturado ou nota fiscal.
  Cancelamento nao representa reembolso financeiro.

## Etapa 5 - cancelamento integral de pedidos

POST `/api/stores/{storeId}/orders/{orderId}/cancel` recebe
`{"reason":"Pedido duplicado"}`. Exige sessao, CSRF e papel OWNER/MANAGER atual
na loja; STAFF consulta a situacao e auditoria. Sem vinculo ou pedido de outra
loja retorna 404. O motivo e obrigatorio, tem de 2 a 500 caracteres apos remover
espacos das extremidades, e fica preservado com ID do responsavel e instante.

- DRAFT -> CANCELLED: nao movimenta estoque, mesmo que nao haja saldo.
- CONFIRMED -> CANCELLED: devolve exatamente as quantidades baixadas, com uma
  entrada por item e motivo `Cancelamento do pedido {orderId}` no historico.
  Confirmador/data anteriores, criador, itens, precos e total nao sao alterados.
- O pedido e bloqueado primeiro, depois os saldos na mesma ordem canonica da
  confirmacao. Estorno, historico e estado sao uma unica transacao PostgreSQL.
  Se qualquer saldo exceder 2147483647, retorna 409 e desfaz **todos** os
  estornos; o pedido continua CONFIRMED. Resolva o saldo com uma movimentacao
  justificada antes de repetir; nao altere saldos diretamente por SQL.
- Repetir cancelamento com o mesmo motivo normalizado retorna 200 e a auditoria
  original, sem nova entrada, mesmo por outro OWNER/MANAGER autorizado.
  Motivo diferente apos cancelamento retorna 409, sem sobrescrever o original.
  Reenvios continuam verificando sessao, CSRF e permissao.
- Cancelamento e definitivo: nao ha reabertura, edicao do motivo ou cancelamento
  parcial. Confirmar CANCELLED retorna 409. Disputa entre confirmar e cancelar
  e serializada: ou cancela o rascunho, ou confirma e depois estorna.
- Nao representa devolucao fisica de mercadoria, reembolso, nota fiscal ou
  operacao de gateway. Estes fluxos ainda precisam de regras proprias.

## Executar localmente

Requisitos: JDK 21, PostgreSQL 17, MongoDB e Gradle Wrapper (incluido).
Para subir bancos locais isolados com Docker Compose:

```bash
cp .env.example .env
# Preencha PGPASSWORD com uma senha local exclusiva antes de continuar.
set -a
. ./.env
set +a
docker compose up -d --wait
export JAVA_HOME=/caminho/do/jdk-21
./gradlew bootRun --args='--spring.profiles.active=local'
```

O Spring nao carrega `.env` automaticamente; exporte as variaveis como acima.
As portas locais dos bancos sao 55432 e 27018, publicadas somente em loopback.
Se ja estiverem ocupadas, ajuste o Compose e as URLs locais. `docker compose stop`
para os servicos sem apagar os volumes. Nao use estes bancos para producao.

API na porta 8081. O perfil `local` permite cookies em HTTP somente para
desenvolvimento. Fora desse perfil, cookies exigem HTTPS.
Nao ha usuario ou senha padrao; crie uma conta pelo frontend.

## Configuracao

| Variavel | Padrao | Finalidade |
| --- | --- | --- |
| `MONGODB_URI` | `mongodb://localhost:27017/business_portal` | MongoDB; producao deve usar autenticacao, TLS e usuario de menor privilegio |
| `PORT` | `8081` | Porta HTTP |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Origens exatas separadas por virgula; nunca curinga |
| `SESSION_COOKIE_SECURE` | `true` | Exigir HTTPS no cookie |
| `JDBC_DATABASE_URL` | obrigatoria | URL JDBC PostgreSQL, por exemplo `jdbc:postgresql://host:5432/database` |
| `PGUSER` / `PGPASSWORD` | obrigatorias | Credenciais PostgreSQL; configuradas na hospedagem |
| `DB_POOL_SIZE` | `5` | Maximo de conexoes por replica; dimensionar com o limite do banco |
| `API_DOCS_ENABLED` | `false` | Habilita OpenAPI e Swagger UI autenticados |

Nunca versione credenciais. Use o gerenciador de segredos da hospedagem.
O usuario MongoDB precisa criar indices nas colecoes `users` e `sessions`.
Um banco indisponivel ou indice invalido impede a inicializacao.
Flyway aplica `src/main/resources/db/migration` na inicializacao e valida
checksums. Nao edite uma migracao ja aplicada: crie a proxima versao.
Nao habilite baseline automatico em banco com tabelas preexistentes.

## Implantar a fundacao no Railway

1. Use primeiro um ambiente de homologacao, com copias/contas de teste e sem
   apontar testes automatizados para o MongoDB de producao. Preserve a configuracao
   de MongoDB existente e faca backup antes de alterar o ambiente de producao.
2. Adicione um servico PostgreSQL. No servico Java, referencie as variaveis
   desse servico, substituindo `Postgres` pelo nome real:

   ```text
   JDBC_DATABASE_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
   PGUSER=${{Postgres.PGUSER}}
   PGPASSWORD=${{Postgres.PGPASSWORD}}
   API_DOCS_ENABLED=true
   ```

   Use a rede privada para o banco. `DATABASE_URL=postgresql://...` nao e uma
   URL JDBC e nao substitui `JDBC_DATABASE_URL`. Nunca copie credenciais para Git.
3. Mantenha `MONGODB_URI`, `SESSION_COOKIE_SECURE=true` e
   `CORS_ALLOWED_ORIGINS=https://frontend-production-e620.up.railway.app`
   no backend. Nao ative o perfil `local` no Railway.
4. Implante o backend somente depois de configurar PostgreSQL. Esta versao
   exige os dois bancos; falha de conexao/migracao impede um deploy saudavel.
   Confira no log a migracao `V1__create_stores_and_memberships.sql`.
5. `railway.json` usa `GET /api/health` como healthcheck; deve retornar
   `{"status":"UP"}`. O healthcheck verifica os bancos, sem expor detalhes.
6. Nao e necessario reimplantar o frontend: Swagger e seus recursos usam o
   proxy `/api/` existente. A interface de lojas ainda nao foi adicionada:
   a validacao desta entrega e pela API/Swagger.
7. Execute o roteiro abaixo. Desabilite `API_DOCS_ENABLED` ao encerrar a
   homologacao se a documentacao nao precisar ficar disponivel.

As mudancas SQL sao aditivas e nao tocam o MongoDB. Para rollback, reimplante
as versoes anteriores da aplicacao, preservando as tabelas novas; nao execute
`flyway clean` nem apague volumes/bancos. Planeje separadamente qualquer
rollback de esquema futuro.

### Implantar o catalogo no Railway

Com a etapa 1 validada, nao crie outro banco nem altere as credenciais existentes.
Faca backup e publique primeiro o backend. Confirme a aplicacao da migracao
`V2__create_catalog_products.sql`, a preservacao das lojas/vinculos e
`GET /api/health` com status UP. As rotas antigas continuam compativeis.
Somente depois publique o frontend com o seletor e o catalogo. Nao sao
necessarias novas variaveis de ambiente ou mudancas no proxy `/api/`.

Validacao manual, usando contas e produtos de teste autorizados:

1. Entre pelo frontend, selecione a loja existente e cadastre um produto como
   OWNER. Confira o preco de R$ 0,10 e outro de R$ 19,90, inclusive apos reload.
2. Repita o SKU na mesma loja (409, sem apagar o formulario). Em outra loja,
   o mesmo SKU deve ser permitido, sem compartilhar produtos.
3. Use uma conta MANAGER para cadastrar na loja autorizada; como STAFF, a
   mesma conta so deve visualizar. Tentar POST pelo Swagger deve retornar 403.
4. Troque entre lojas e paginas; nao devem aparecer produtos da loja anterior.
   Uma conta sem vinculo recebe 404 mesmo conhecendo os IDs de loja/produto.
5. Confirme logout/login entre contas e sessao existentes.

Se necessario, reimplante primeiro o frontend anterior e depois o backend
anterior, mantendo as tabelas V2 e o historico Flyway; nao execute limpeza SQL.
Valide esta etapa antes de iniciar estoque.

### Implantar o estoque no Railway

Preserve os bancos e variaveis existentes; faca backup. Publique primeiro o
backend, confirme `V3__create_inventory.sql` e healthcheck UP. A migracao cria
saldos/historico e adiciona uma restricao unica ao catalogo para a FK composta;
nao altera precos, SKUs, produtos, lojas ou dados MongoDB. O indice adicional
exige uma janela planejada se o catalogo crescer muito. Depois publique o frontend.
Nao ha novas dependencias, variaveis ou portas.

Roteiro de aceite com produto de teste autorizado:

1. Abra **Estoque** no produto existente; confira saldo zero e historico vazio.
2. Registre entrada de 10 unidades, depois saida de 3: saldo esperado 7.
   Confira motivo, responsavel, datas e persistencia apos recarregar.
3. Tente saida de 8: deve retornar erro sem mudar saldo/historico.
4. No Swagger, repita exatamente o mesmo requestId e corpo de uma entrada:
   saldo nao deve aumentar novamente. Modifique a quantidade mantendo requestId:
   deve retornar 409. Use novo UUID somente para uma nova operacao.
5. Confira leitura como STAFF, bloqueio de POST (403), isolamento entre lojas
   e preservacao de login e catalogo.

Rollback: primeiro frontend anterior e depois backend anterior, preservando V3
e historico; nunca apague saldos ou execute `flyway clean`. Enquanto o backend
anterior estiver em uso, nenhuma nova movimentacao deve ser registrada.
Pedidos so iniciam depois da validacao desta etapa no Railway.

### Implantar os pedidos no Railway

Com catalogo/estoque validados, faca backup e publique primeiro o backend.
Confirme `V4__create_internal_orders.sql` e healthcheck UP. V4 adiciona apenas
pedidos/itens, suas restricoes e indices, sem atualizar saldos, produtos ou
historico existente. Depois publique o frontend. Sem novas dependencias,
variaveis ou portas.

Aceite usando somente loja/produtos de teste autorizados:

1. Confira saldo e preco do produto; adicione-o ao pedido pelo catalogo.
   Salve o rascunho, confira itens e total calculados pelo servidor.
2. Confirme que salvar nao muda saldo. Confirme o pedido com quantidade 2
   e confira saida de 2 no estoque e historico com o ID do pedido.
3. Repita a confirmacao pelo Swagger: saldo/historico nao podem mudar.
   Confira pedido e valores apos reload e nova sessao.
4. Crie pedido com dois produtos, um sem saldo. A confirmacao deve falhar
   sem baixa parcial; depois de entrada de teste, confirme o mesmo pedido.
5. Confira STAFF somente consulta; POST retorna 403. Outra loja nao acessa
   o pedido. Revalide catalogo, entradas/saidas manuais e login.

Rollback: frontend anterior primeiro, backend anterior depois, preservando V4,
pedidos e historico. Nao desfaça saidas SQL manualmente nem apague dados.
No backend anterior os pedidos nao estarao disponiveis, mas as baixas ja
confirmadas permanecem. Nao opere vendas enquanto estiver nesse rollback.

### Implantar o cancelamento no Railway

Com a etapa 4 validada, faca backup e publique primeiro o backend.
Confirme `V5__cancel_internal_orders.sql`, healthcheck UP e preservacao dos
pedidos antigos antes de publicar o frontend. V5 adiciona campos de auditoria
e expande as restricoes para CANCELLED, sem alterar itens, precos, saldos ou
historico existentes. Nao exige novas variaveis ou dependencias.

Aceite com produtos/pedidos de teste autorizados:

1. Cancele um rascunho com motivo: saldo/historico devem permanecer iguais.
2. Confirme outro pedido com quantidade 2, depois cancele: saldo deve voltar
   ao anterior e o historico deve registrar entrada de 2 com o ID do pedido.
   Confira motivo, cancelador, confirmador anterior e persistencia apos reload.
3. Repita o POST de cancelamento pelo Swagger com o mesmo motivo: nenhum
   estorno adicional. Motivo diferente e tentativa de confirmar CANCELLED
   retornam 409, sem alteracao de dados.
4. Confira motivo vazio/espacos (400), sem CSRF (403), STAFF (403), outra loja
   (404), e preservacao das consultas de pedidos, catalogo e login.
5. A devolucao deve ser integral. O cenario de estouro do limite e a disputa
   concorrente sao cobertos com PostgreSQL de teste; nao force limites nos
   saldos de producao para testar esse comportamento.

**Rollback:** preserve V5, pedidos e movimentos. Depois de qualquer pedido
CANCELLED existir, **nao volte ao backend da etapa 4**, cujo enum nao reconhece
esse estado. Prefira correcao progressiva ou uma versao que mantenha suporte
a CANCELLED. O frontend da etapa 4 tambem nao reconhece esse estado; preserve
esse suporte na interface ou interrompa a operacao de pedidos durante a
recuperacao. Nao apague pedidos, nao altere estados por SQL e nao reverta
movimentacoes manualmente para contornar o rollback.

### Swagger e teste manual de isolamento

Swagger agrega valor para esta etapa backend-first. Com `API_DOCS_ENABLED=true`,
entre normalmente pelo frontend e abra **no mesmo dominio**
`https://frontend-production-e620.up.railway.app/api/docs`.
OpenAPI JSON: `/api/openapi`. Sem login, a documentacao retorna 401.
Nao use o dominio publico do backend com o cookie do frontend.

1. Em Swagger, execute GET `/api/auth/csrf` e copie `token`. Em **Authorize**,
   preencha o esquema `csrf` com esse token. O cookie HttpOnly acompanha as
   requisicoes de mesma origem automaticamente; nao copie cookies para campos.
2. Crie uma loja com `{"name":"Loja de teste A"}`. Guarde o `id`, liste as
   lojas e consulte o detalhe. A loja deve ter papel OWNER.
3. Cadastre outra conta pelo frontend em uma janela anonima. Antes de vincular,
   essa conta nao deve listar a loja e deve receber 404 ao consultar seu UUID.
4. Pela conta proprietaria, adicione o e-mail da segunda conta como STAFF.
   Agora a segunda conta deve consultar a loja, mas receber 403 ao listar ou
   adicionar membros. Repita em outra loja com MANAGER para confirmar papeis
   diferentes para a mesma conta.
5. Tente repetir o vinculo (409), enviar role OWNER (400), fazer POST sem
   token CSRF (403) e consultar sem sessao (401). Depois de novo login, obtenha
   outro token CSRF e atualize Authorize.
6. Confirme que login e logout continuam funcionando. O mural de teste nao
   deve aparecer e `/api/mensagens` autenticado retorna 404.


## Contrato de autenticacao

Todas as respostas de erro da aplicacao usam `{ "message": "..." }`.
Cookies devem acompanhar as requisicoes (`credentials: include`).

| Metodo | Rota | Comportamento |
| --- | --- | --- |
| GET | `/api/auth/csrf` | Retorna `{token}`; cria sessao anonima |
| POST | `/api/auth/register` | `{name,email,password}`; 201; depois entre pela rota de login |
| POST | `/api/auth/login` | `{email,password}`; 200 com `{id,name,email}` |
| GET | `/api/auth/me` | Usuario atual ou 401 |
| POST | `/api/auth/logout` | Invalida a sessao no MongoDB; 204 |

Todo POST, inclusive cadastro, login e logout, exige `X-CSRF-TOKEN` obtido
com GET `/api/auth/csrf`. O frontend busca um token antes de cada escrita.
O identificador de sessao e o token CSRF sao renovados no login.
Sessoes expiram apos 30 minutos de inatividade. O cookie e de sessao do
navegador, HttpOnly e SameSite=Lax. A senha deve ter no minimo 12 caracteres
e no maximo 72 bytes UTF-8; o e-mail e normalizado para minusculas.

As rotas `/` e `/api/ola` continuam publicas. A API de mensagens de teste foi
retirada; rotas comerciais continuam exigindo sessao e protecao CSRF em POST.

### Persistencia de sessoes

O projeto usa `spring-session-core` gerenciado pelo Spring Boot. O repositorio
MongoDB implementa `SessionRepository<MapSession>` e persiste somente o
principal autenticado e o token CSRF, sem serializacao Java e sem hash de senha.
Nao usa a linha pre-release do modulo Spring Session MongoDB 4.
Este repositorio atende o papel unico `USER`; novas permissoes exigem rever o
mapeamento explicito, e nao adicionar atributos de sessao arbitrarios.

### Limites operacionais

O limitador local permite 20 tentativas de login/cadastro por endereco remoto
a cada 15 minutos (429 com Retry-After), com memoria limitada. Em multiplas
replicas, implemente limite distribuido no gateway/Redis. A aplicacao nao confia
em X-Forwarded-For vindo diretamente do cliente: atras de proxy, o limite
pode agrupar clientes sob o IP do proxy. Configure rate limiting por cliente
na borda antes de abrir o servico ao publico.

Use o proxy de mesma origem do frontend em producao; cookies SameSite=Lax
nao foram projetados para front e API em sites independentes.
Recuperacao de senha, verificacao de e-mail, MFA, regras comerciais e
provisionamento de infraestrutura nao estao incluidos.

## Proximas entregas pequenas

1. **Fundacao:** PostgreSQL, migracao V1, lojas, vinculos, isolamento e Swagger.
2. **Catalogo:** migracao V2, seletor de loja, catalogo paginado e cadastro
   autorizado. Chaves de React Query incluem usuario, loja e pagina.
3. **Estoque:** migracao V3, estoque inteiro por loja, historico auditavel,
   operacoes atomicas e idempotencia.
4. **Pedidos:** migracao V4, pedidos internos com preco historico,
   rascunho, confirmacao atomica e idempotencia.
5. **Entrega atual:** migracao V5, cancelamento integral auditado e estorno
   atomico/idempotente de estoque.
6. **Depois de validar a etapa 5:** definir ciclo financeiro, regras de
   pagamento/reembolso e reservas antes de gateway, webhooks e reconciliacao.

Cada etapa deve ser testada e aprovada no Railway antes de iniciar a seguinte.
Ainda nao e uma plataforma de vendas completa nem liberacao para operacao financeira.

## Testes

```bash
./gradlew test
```

Os testes inicializam MongoDB 7 e PostgreSQL 17 reais com Testcontainers: Docker funcional e
acesso para baixar a imagem sao obrigatorios. Nenhuma conexao de producao e
usada. Os testes cobrem cadastro, hash de senha, login, cookies, fixacao de
sessao, CSRF, CORS, expiracao, logout, retirada da API de mensagens e limite de tentativas.
O modulo de lojas adiciona testes de isolamento entre lojas, papeis por
vinculo, paginacao, rollback de criacao loja/OWNER, Flyway, Swagger autenticado
e healthcheck. Os containers usam portas aleatorias, sem dados de producao.
O catalogo cobre persistencia decimal, unicidade concorrente de SKU, isolamento,
permissoes, validacao e paginacao. A atualizacao de um esquema V1 com lojas e
vinculos existentes para V2 e testada separadamente, inclusive sua reaplicacao
idempotente pelo Flyway.
O estoque cobre entradas/saidas, limites inteiros, saldo insuficiente,
autorizacao/CSRF, isolamento por produto/loja, reenvio idempotente, entradas
e saidas concorrentes, rollback se o historico falhar e migracao V2 para V3
sem inventar quantidades para produtos existentes.
Pedidos cobrem snapshots monetarios e totais grandes sem perda de precisao,
criacao/confirmacao idempotentes sob concorrencia, autorizacao/CSRF, isolamento,
validacao/paginacao, rollback de baixa parcial, disputa pelo mesmo estoque
e migracao V3 para V4 sem alterar produtos/saldos existentes.
Cancelamento cobre rascunho sem estoque, estorno integral do confirmado,
auditoria, motivo e reenvios, autorizacao/CSRF, isolamento, rollback por estouro
de saldo, cancelamentos concorrentes, disputa confirmacao/cancelamento e
migracao V4 para V5 com snapshots/saldos/auditoria preservados.
