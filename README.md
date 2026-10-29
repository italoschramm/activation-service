# Serviço de ativação de produtos

Projeto do desafio técnico Java Pleno/Sênior. Esta primeira etapa contém a base da aplicação; os endpoints de solicitações e a integração com o provedor serão adicionados nas próximas etapas.

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

Os testes dos fluxos de negócio serão adicionados junto com os respectivos endpoints. O build inicial não precisa de um banco ativo.

## Próximas etapas

1. Modelo de solicitação e endpoints de criação e consulta.
2. Regra de unicidade de `externalId`, inclusive sob concorrência.
3. Integração simulada com o provedor e transições de status.
4. Tratamento de erros, testes, análise de produção e documentação final.
