# Serviço de ativação de produtos

Projeto do desafio técnico Java Pleno/Sênior. A aplicação registra solicitações de ativação, consulta o status e processa a ativação por meio de um provedor HTTP simulado.

## Requisitos

- Docker com Docker Compose para executar aplicação, PostgreSQL e provedor simulado juntos; ou
- Java 21, Maven e Docker para executar a aplicação localmente com o banco em contêiner.

## Executar aplicação e banco

```sh
docker compose up --build
```

O Compose expõe a aplicação na porta `8080`, o PostgreSQL na porta `5433` e o WireMock na porta `8081`. Depois que a aplicação iniciar, verifique `http://localhost:8080/actuator/health`. O banco usa um volume Docker para preservar os dados entre reinicializações.

Para encerrar:

```sh
docker compose down
```

## Executar com Maven

Inicie apenas o banco:

```sh
docker compose up -d db provider
mvn spring-boot:run
```

O endereço padrão do banco é `jdbc:postgresql://localhost:5433/activation`; o provedor simulado usa `http://localhost:8081`. É possível alterá-los com `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` e `PROVIDER_BASE_URL`.

## Build e testes

```sh
mvn verify
```

Os testes da API usam H2 em memória e simulam as respostas do cliente HTTP. A aplicação usa PostgreSQL e WireMock; o build não precisa desses serviços ativos.

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

O esquema do banco é criado pela migração Flyway `V1__create_activation_requests.sql`. A restrição `UNIQUE` em `external_id` garante que apenas um registro seja criado, mesmo se duas chamadas chegarem simultaneamente. A aplicação faz a inserção em uma transação do repositório; se a restrição rejeitar a inserção, essa transação termina e a aplicação consulta o registro vencedor em uma operação independente. Uma consulta prévia seguida de inserção, sem essa restrição, não impediria a corrida.

## Integração e novas tentativas

O serviço consulta solicitações `PENDING` a cada segundo. Uma atualização condicional no banco reserva cada solicitação como `PROCESSING`, evitando que duas instâncias a processem ao mesmo tempo. A chamada HTTP ocorre fora da transação do banco. O cliente usa timeout de conexão de 1 segundo e timeout de leitura de 2 segundos, configuráveis por `PROVIDER_CONNECT_TIMEOUT` e `PROVIDER_READ_TIMEOUT`.

| Retorno do provedor | Comportamento |
| --- | --- |
| 2xx | `COMPLETED`, sem nova tentativa. |
| 400 e demais 4xx, exceto 429 | `FAILED`, sem nova tentativa; a requisição foi rejeitada. |
| 500, 429 e demais 5xx | Volta a `PENDING` para nova tentativa; após três tentativas, `FAILED`. |
| Timeout ou falha de rede | Mesma política de novas tentativas. O resultado remoto pode ser desconhecido. |

As novas tentativas esperam 2 e 4 segundos. Se a aplicação cair enquanto uma solicitação está em `PROCESSING`, ela volta a `PENDING` após 30 segundos para ser reenviada. Todas as chamadas ao provedor levam `Idempotency-Key` com o `externalId`, para que um provedor real possa reconhecer repetição após timeout ou queda. O WireMock é apenas um stub e não mantém essa chave; em produção, o provedor deve honrar a idempotência ou oferecer consulta/reconciliação antes de repetir a ativação. Um `FAILED` após timeout significa que não foi possível confirmar a ativação, não uma prova de que o produto ficou inativo.

Para testar pelo Insomnia, use um `externalId` novo em cada POST. O mock devolve sucesso para produtos comuns, `400` para `SIMULATE-400`, `500` para `SIMULATE-500` e demora 5 segundos para `SIMULATE-TIMEOUT`. Consulte a solicitação pelo `id` para acompanhar o status.

Os testes automatizados cobrem criação, validação, consulta, repetição idêntica, conflito, seis chamadas simultâneas com o mesmo `externalId`, sucesso do provedor, rejeição e falha temporária. Eles usam H2 em memória. O comportamento com concorrência também foi verificado manualmente com a aplicação e o PostgreSQL iniciados pelo Docker Compose.

## Próximas etapas

1. Padronizar os demais erros, analisar o cenário de produção e concluir a documentação.
