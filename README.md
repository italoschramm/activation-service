# Serviço de ativação de produtos

Projeto do desafio técnico Java Pleno/Sênior. A aplicação já registra e consulta solicitações de ativação. A integração com o provedor será adicionada em uma etapa futura.

## Requisitos

- Docker com Docker Compose para executar aplicação e PostgreSQL juntos; ou
- Java 21, Maven e Docker para executar a aplicação localmente com o banco em contêiner.

## Executar aplicação e banco

```sh
docker compose up --build
```

O Compose expõe a aplicação na porta `8080` e o PostgreSQL na porta `5433`. Depois que a aplicação iniciar, verifique `http://localhost:8080/actuator/health`. O banco usa um volume Docker para preservar os dados entre reinicializações.

Para encerrar:

```sh
docker compose down
```

## Executar com Maven

Inicie apenas o banco:

```sh
docker compose up -d db
mvn spring-boot:run
```

O endereço padrão do banco é `jdbc:postgresql://localhost:5433/activation`. É possível alterá-lo com `DB_URL`, `DB_USERNAME` e `DB_PASSWORD`.

## Build e testes

```sh
mvn verify
```

Os testes da API usam H2 em memória. A aplicação usa PostgreSQL; o build não precisa de um banco externo ativo.

## API disponível

Criar solicitação:

```sh
curl -i -X POST http://localhost:8080/api/v1/requests -H "Content-Type: application/json" -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'
```

A resposta é `201 Created`, com o endereço de consulta no header `Location`. O estado inicial é `PENDING`.

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

O esquema do banco é criado pela migração Flyway `V1__create_activation_requests.sql`. O banco impede a repetição de `externalId` por uma restrição única; a resposta da API para esse caso será definida na próxima etapa.

## Próximas etapas

1. Definir a resposta para repetição de `externalId` e testar concorrência.
2. Integrar o provedor simulado e controlar transições de status.
3. Padronizar erros, analisar o cenário de produção e concluir a documentação.
