# Nexo Business - API

Spring Boot 4, Java 21, Spring Security, PostgreSQL e MongoDB. Autenticacao por sessao opaca
em cookie HttpOnly, sem JWT no navegador. Senhas recebem BCrypt com custo 12.
Usuarios e sessoes sao persistidos no MongoDB; o indice unico de e-mail e o
indice TTL de sessoes sao criados na inicializacao. A expiracao tambem e
verificada em cada leitura, independentemente do monitor TTL.

## Etapa 1 - fundacao multiloja

Monolito modular, sem microservicos. O modulo `stores` separa API, aplicacao,
dominio e infraestrutura JDBC. PostgreSQL guarda lojas e vinculos; Flyway
versiona o esquema. MongoDB continua responsavel por usuarios, sessoes e mural,
sem migrar, apagar ou atribuir dados antigos a uma loja automaticamente.
Essa convivencia e transitoria, nao uma recomendacao de dois bancos para todos
os futuros modulos. Pedidos, estoque e pagamentos devem usar o nucleo relacional.

Uma conta participa de varias lojas, com papel por vinculo. Criar uma loja
atribui OWNER ao criador; somente OWNER pode listar ou adicionar membros
existentes como MANAGER ou STAFF. Estes dois papeis ainda nao tem permissoes
comerciais, pois catalogo e vendas nao existem nesta entrega.

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

## Implantar esta etapa no Railway

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
6. Confirme que login, logout e mural antigos continuam funcionando.
   O mural continua global: nao publique nele dados privados de uma loja.


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
| GET/POST | `/api/mensagens` | Mural compartilhado entre todos os usuarios autenticados |

Todo POST, inclusive cadastro, login e logout, exige `X-CSRF-TOKEN` obtido
com GET `/api/auth/csrf`. O frontend busca um token antes de cada escrita.
O identificador de sessao e o token CSRF sao renovados no login.
Sessoes expiram apos 30 minutos de inatividade. O cookie e de sessao do
navegador, HttpOnly e SameSite=Lax. A senha deve ter no minimo 12 caracteres
e no maximo 72 bytes UTF-8; o e-mail e normalizado para minusculas.

As rotas `/` e `/api/ola` continuam publicas. O mural antigo agora exige
autenticacao e protecao CSRF; seus dados continuam compartilhados, sem
introduzir isolamento por organizacao que o modelo existente nao oferece.

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

1. **Esta entrega:** PostgreSQL, migracao V1, lojas, vinculos, isolamento e Swagger.
2. **Proxima:** seletor de loja no React, hooks por loja e catalogo paginado.
   As chaves de React Query devem incluir `storeId`, filtros e pagina.
3. **Depois:** estoque com operacoes atomicas e testes de concorrencia.
4. **Depois:** pedidos com valores em BigDecimal, moeda, preco historico e idempotencia.
5. **Depois:** gateway de pagamentos, webhooks idempotentes, reconciliacao e auditoria.

Cada etapa deve ser testada e aprovada no Railway antes de iniciar a seguinte.
Ainda nao e uma plataforma de vendas completa nem liberacao para operacao financeira.

## Testes

```bash
./gradlew test
```

Os testes inicializam MongoDB 7 e PostgreSQL 17 reais com Testcontainers: Docker funcional e
acesso para baixar a imagem sao obrigatorios. Nenhuma conexao de producao e
usada. Os testes cobrem cadastro, hash de senha, login, cookies, fixacao de
sessao, CSRF, CORS, expiracao, logout, mural e limite de tentativas.
O modulo de lojas adiciona testes de isolamento entre lojas, papeis por
vinculo, paginacao, rollback de criacao loja/OWNER, Flyway, Swagger autenticado
e healthcheck. Os containers usam portas aleatorias, sem dados de producao.
