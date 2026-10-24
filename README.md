# Nexo Business - API

Spring Boot 4, Java 21, Spring Security e MongoDB. Autenticacao por sessao opaca
em cookie HttpOnly, sem JWT no navegador. Senhas recebem BCrypt com custo 12.
Usuarios e sessoes sao persistidos no MongoDB; o indice unico de e-mail e o
indice TTL de sessoes sao criados na inicializacao. A expiracao tambem e
verificada em cada leitura, independentemente do monitor TTL.

## Executar localmente

Requisitos: JDK 21, MongoDB e Gradle Wrapper (incluido).

```bash
export JAVA_HOME=/caminho/do/jdk-21
export MONGODB_URI=mongodb://localhost:27017/business_portal
./gradlew bootRun --args='--spring.profiles.active=local'
```

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

Nunca versione credenciais. Use o gerenciador de segredos da hospedagem.
O usuario MongoDB precisa criar indices nas colecoes `users` e `sessions`.
Um banco indisponivel ou indice invalido impede a inicializacao.

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
Recuperacao de senha, verificacao de e-mail, MFA, regras multiempresa e
provisionamento de infraestrutura nao estao incluidos.

## Testes

```bash
./gradlew test
```

Os testes inicializam MongoDB 7 real com Testcontainers: Docker funcional e
acesso para baixar a imagem sao obrigatorios. Nenhuma conexao de producao e
usada. Os testes cobrem cadastro, hash de senha, login, cookies, fixacao de
sessao, CSRF, CORS, expiracao, logout, mural e limite de tentativas.
