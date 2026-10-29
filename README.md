# Serviço de ativação de produtos

Projeto do desafio técnico Java Pleno/Sênior. A aplicação registra solicitações de ativação, consulta o status e processa a ativação por meio de um provedor HTTP simulado.

## Requisitos

- Docker com Docker Compose para executar aplicação, PostgreSQL e provedor simulado juntos; ou
- Java 21, Maven e Docker para executar a aplicação localmente com o banco em contêiner.

## Executar com Docker

A partir de um clone limpo:

```sh
git clone https://github.com/italoschramm/activation-service.git
cd activation-service
docker compose up -d --build
```

O Compose expõe a aplicação na porta `8080`, o PostgreSQL na porta `5433` e o WireMock na porta `8081`. Depois que a aplicação iniciar, verifique `http://localhost:8080/actuator/health`. O banco usa um volume Docker para preservar os dados entre reinicializações.

Para encerrar:

```sh
docker compose down
```

## Executar com Maven

Inicie o banco e o provedor simulado:

```sh
docker compose up -d db provider
mvn spring-boot:run
```

O endereço padrão do banco é `jdbc:postgresql://localhost:5433/activation`; o provedor simulado usa `http://localhost:8081`. É possível alterá-los com `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` e `PROVIDER_BASE_URL`.

## Build e testes

```sh
mvn verify
```

Esse comando roda os testes da API com H2 em memória e respostas simuladas do cliente HTTP. Para incluir os testes de concorrência com PostgreSQL real, tenha o Docker Desktop ativo e execute:

```sh
mvn -Ppostgres-it verify
```

O perfil `postgres-it` inicia um PostgreSQL temporário com Testcontainers, executa os testes e encerra o contêiner. O pipeline em `.github/workflows/ci.yml` executa esse perfil automaticamente no GitHub em pushes e pull requests. O build normal e o Dockerfile não precisam iniciar um contêiner dentro de outro.

## API disponível

Criar solicitação:

```sh
curl -i -X POST http://localhost:8080/api/v1/requests -H "Content-Type: application/json" -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'
```

A primeira criação retorna `201 Created`, com o endereço de consulta no header `Location`. O estado inicial é `PENDING`. O processamento em segundo plano passa por `PROCESSING` e, conforme o retorno do provedor, chega a `COMPLETED` ou `FAILED`. Consulte o mesmo `id` novamente para ver o estado atual.

O `externalId` identifica a solicitação no sistema de origem. Se o mesmo `externalId` for enviado novamente com `customerId` e `productCode` idênticos, a API retorna `200 OK` com o registro original, sem criar outro. Se qualquer um desses campos mudar, retorna `409 Conflict` com uma mensagem explicando o conflito. Isso permite repetir uma chamada após perder a resposta HTTP sem criar uma segunda solicitação.

Consultar solicitação:

```sh
curl -i http://localhost:8080/api/v1/requests/ID_DA_SOLICITACAO
```

Um ID inexistente retorna `404 Not Found`. Os três campos de entrada são obrigatórios, não podem conter apenas espaços e têm limite de 100 caracteres; dados inválidos retornam `400 Bad Request`.

No PowerShell, use este exemplo para criar e consultar uma solicitação sem repetir o `externalId`:

```powershell
$body = @{
    externalId = "EXT-" + [guid]::NewGuid().ToString("N")
    customerId = "CUST-10001"
    productCode = "INTERNET-500"
} | ConvertTo-Json

$created = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/requests" -ContentType "application/json" -Body $body
$created
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/requests/$($created.id)"
```

## Decisões técnicas

O projeto usa Java 21, Spring Boot 3, Maven, PostgreSQL e Flyway. As classes são agrupadas por funcionalidade nos packages `request` e `provider`, sem camadas adicionais para um serviço pequeno. Os DTOs de entrada e saída são separados da entidade JPA. O processamento é assíncrono em relação ao POST: o cliente recebe `PENDING` sem esperar pelo provedor, cuja lentidão pode ser acompanhada pelo GET.

O esquema do banco é criado pela migração Flyway `V1__create_activation_requests.sql`. A restrição `UNIQUE` em `external_id` garante que apenas um registro seja criado, mesmo se duas chamadas chegarem simultaneamente. A aplicação faz a inserção em uma transação do repositório; se a restrição rejeitar a inserção, essa transação termina e a aplicação consulta o registro vencedor em uma operação independente. Uma consulta prévia seguida de inserção, sem essa restrição, não impediria a corrida.

## Integração e novas tentativas

O serviço consulta solicitações `PENDING` a cada segundo. Uma atualização condicional no banco reserva cada solicitação como `PROCESSING`, evitando que duas instâncias a processem ao mesmo tempo. A chamada HTTP ocorre fora da transação do banco. O cliente usa timeout de conexão de 1 segundo e timeout de leitura de 2 segundos, configuráveis por `PROVIDER_CONNECT_TIMEOUT` e `PROVIDER_READ_TIMEOUT`.

| Retorno do provedor | Comportamento |
| --- | --- |
| 2xx | `COMPLETED`, sem nova tentativa. |
| 400 e demais 4xx, exceto 429 | `FAILED`, sem nova tentativa; a requisição foi rejeitada. |
| 500, 429 e demais 5xx | Volta a `PENDING` para nova tentativa; após três tentativas, `FAILED`. |
| Timeout ou falha de rede | Mesma política de novas tentativas. O resultado remoto pode ser desconhecido. |

As novas tentativas esperam 2 e 4 segundos. Se a aplicação cair enquanto uma solicitação está em `PROCESSING`, ela volta a `PENDING` após 30 segundos para ser reenviada, exceto se a terceira tentativa já tiver começado; nesse caso, fica `FAILED` sem uma quarta chamada. Todas as chamadas ao provedor levam `Idempotency-Key` com o `externalId`, para que um provedor real possa reconhecer repetição após timeout ou queda. O WireMock é apenas um stub e não mantém essa chave; em produção, o provedor deve honrar a idempotência ou oferecer consulta/reconciliação antes de repetir a ativação. Um `FAILED` após timeout significa que não foi possível confirmar a ativação, não uma prova de que o produto ficou inativo.

Para testar pelo Insomnia, use um `externalId` novo em cada POST. O mock devolve sucesso para produtos comuns, `400` para `SIMULATE-400`, `500` para `SIMULATE-500` e demora 5 segundos para `SIMULATE-TIMEOUT`. Consulte a solicitação pelo `id` para acompanhar o status.

## Tratamento de erros

Erros da API usam `application/problem+json` com `title`, `status`, `detail`, `instance` e `traceId`. O mesmo `traceId` aparece no log. Falhas inesperadas são registradas no servidor, mas a resposta não inclui exceções, stack traces nem detalhes internos.

| Situação | Resposta |
| --- | --- |
| Campo obrigatório ausente, JSON inválido ou ID malformado | `400 Bad Request`; validação inclui `errors` por campo. |
| Solicitação inexistente | `404 Not Found`. |
| Mesmo `externalId` com cliente ou produto diferente | `409 Conflict`. |
| Erro inesperado no processamento da chamada HTTP | `500 Internal Server Error` com mensagem genérica e `traceId`. |

A integração é processada depois da resposta do POST. Por isso, erros do provedor não alteram retroativamente o HTTP `201`: a solicitação fica `PENDING` para nova tentativa ou `FAILED`, conforme a política acima, e o consumidor consulta o resultado por GET. O `200` do GET significa que o registro foi encontrado; o resultado da ativação está no campo `status`.

## Segurança

**Proposta para produção; autenticação ainda não está implementada neste desafio.** A API local aceita chamadas sem token para permitir o teste pelo Insomnia. Em produção, usaria Spring Security como OAuth 2.0 Resource Server. Para comunicação entre sistemas, o consumidor obteria um JWT pelo fluxo client credentials e enviaria `Authorization: Bearer ...`. O serviço validaria assinatura via JWKS do emissor confiável, `issuer`, `audience`, expiração e início de validade.

O POST exigiria o escopo `activation:write` e o GET, `activation:read`. Também seria necessário vincular a solicitação ao sistema de origem e filtrar consultas por esse sistema: um token de outro consumidor não poderia acessar o registro só por conhecer o UUID. O tráfego externo usaria TLS. A credencial usada para chamar o provedor ficaria em um gerenciador de segredos, com rotação e permissões mínimas, sem aparecer em código, imagem Docker ou logs.

## Testes

`mvn verify` executa testes com H2 em memória. Eles cobrem criação, validação, consulta, repetição idêntica, conflito, seis chamadas simultâneas com o mesmo `externalId`, sucesso, rejeição e falha temporária do provedor, recuperação após queda e ocultação de erros internos. `mvn -Ppostgres-it verify` acrescenta dois testes de concorrência com PostgreSQL real: chamadas idênticas e chamadas conflitantes em paralelo. O fluxo HTTP com WireMock também foi verificado localmente, incluindo sucesso, `400`, `500` e timeout.

## Cenário de lentidão em produção

**Dados do desafio:** média da API de 300 ms para 2,5 s, CPU em 45%, memória em 60%, algumas consultas de 50 ms para 1,2 s e provedor de 400 ms para 3 s.

1. Primeiro, separaria a latência por rota e por percentis, comparando volume, taxa de erros e momento da mudança. Verificaria tempo de espera por conexão no pool, consultas lentas, bloqueios no banco, tamanho e idade da fila de `PENDING` e duração das chamadas ao provedor.
2. Compararia traces e tempos por etapa: entrada HTTP, consulta ou gravação no banco e processamento em segundo plano. Como o POST não espera pelo provedor, os 3 s do provedor não explicam sozinhos os 2,5 s da API; a lentidão do banco ou a disputa por recursos compartilhados merece atenção.
3. Usaria logs com identificador de correlação e métricas de latência da API e do provedor, erros por status HTTP, tentativas, quantidade de solicitações por estado, conexões ativas e em espera no pool. No PostgreSQL, examinaria consultas, planos com `EXPLAIN ANALYZE`, bloqueios e `pg_stat_statements`.
4. Aplicaria a correção que as medidas indicarem. Exemplos: ajustar uma consulta ou índice medido, como um índice para `status` e `next_attempt_at` no processamento; corrigir contenção e tamanho do pool; reduzir chamadas desnecessárias; ajustar timeouts e ritmo das tentativas. Não aumentaria recursos sem identificar o gargalo.
5. Uma hipótese de causa comum é degradação da rede entre a aplicação, o banco e o provedor. Compararia latência de rede e conexões a partir do contêiner da aplicação com medições feitas dentro do banco e diretamente no provedor. Se apenas a aplicação sofrer, investigaria sua rede ou seu host; se cada destino estiver lento por conta própria, investigaria as causas separadamente.
6. Após publicar a correção, compararia sob tráfego equivalente os mesmos percentis, tempos de consulta, latência do provedor, fila, tentativas e erros, observando uma janela suficiente para detectar regressão. Manteria a possibilidade de reverter a versão.

## Produção e pontos pendentes

O Dockerfile usa build em dois estágios, Java 21 na imagem final e usuário sem privilégios. Em produção, publicaria a imagem por um pipeline que executa testes, versão a imagem e faz deploy gradual. Usaria PostgreSQL gerenciado, configurações por ambiente, segredos fora da imagem, probes de liveness/readiness e mais de uma instância da aplicação. A restrição única no banco e a reserva condicional de `PROCESSING` mantêm a proteção contra duplicidade entre instâncias. As migrações precisam ser compatíveis com uma implantação gradual.

Antes de uso real, ainda faltam a segurança descrita acima, um contrato de idempotência ou reconciliação com o provedor, métricas e tracing completos, políticas de acesso a dados por sistema de origem e um processo operacional para casos `FAILED` após timeout. A listagem paginada com filtros e o tratamento do header `Retry-After` são diferenciais do enunciado que não foram implementados. Para adicionar a listagem, criaria índices conforme os filtros e a ordenação usados nas consultas, após medir os planos no PostgreSQL. Melhorias futuras incluem limitação de taxa, desligamento gracioso, publicação da imagem pelo CI/CD e publicação confiável de eventos de conclusão para outros serviços, por exemplo com outbox. O mock local não substitui um provedor real nem garante idempotência.

## Uso de IA

Utilizei o Codex como apoio na implementação, na escrita dos testes automatizados e na documentação. Participei das decisões técnicas, incluindo o uso de Java 21, Maven, PostgreSQL, Docker Compose e uma estrutura simples. Também testei manualmente os fluxos da API com Docker Compose e Insomnia. Além desses testes manuais, a suíte automatizada verifica criação, validação, duplicidade, consulta, integração com o provedor e concorrência no PostgreSQL.
