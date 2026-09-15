# OmniFlow — Orquestração Financeira & Reconciliação na Nuvem

> Um motor de liquidação para marketplaces e splits multi-partes em Java 17 e Spring Boot 3, projetado para resolver o problema de escrita dupla na AWS e reconciliar milhões de transações sem estourar a memória.

[![Java](https://img.shields.io/badge/Java-17%20LTS-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![MongoDB](https://img.shields.io/badge/MongoDB-7.0-green.svg)](https://www.mongodb.com/)
[![AWS LocalStack](https://img.shields.io/badge/AWS-LocalStack%203.7-yellow.svg)](https://localstack.cloud/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

---

## Por que este projeto existe?

Em plataformas de pagamento e marketplaces (estilo Uber, iFood ou plataformas de e-commerce), o dinheiro quase nunca vai de uma conta A para uma conta B de forma direta:
- O cliente passa o cartão em R$ 1.000,00.
- Desse valor, **R$ 930,00** vão para o lojista/prestador de serviço.
- **R$ 50,00** ficam com a plataforma como comissão (*take-rate*).
- **R$ 20,00** ficam retidos em uma conta de caução/escrow para cobrir eventuais disputas ou chargebacks.

Se qualquer uma dessas 4 pernas contábeis falhar no meio do caminho, o livro-razão financeiro fica desbalanceado.

E pior: para avisar os outros serviços da nuvem (antifraude, emissão de nota, repasse bancário), a sua aplicação precisa falar com o banco de dados relacional (PostgreSQL) e publicar eventos no broker de mensagens (**AWS SNS / SQS**). 

Se o seu pod da aplicação for reiniciado pelo Kubernetes no milissegundo exato entre o commit do PostgreSQL e a chamada da AWS, você acabou de criar o pesadelo do **Dual-Write**: o banco debitou o cliente, mas o evento nunca chegou na fila de liquidação.

O **OmniFlow** foi desenhado com Arquitetura Hexagonal para resolver esse fluxo ponta a ponta: do split multi-partes à publicação garantida e reconciliação em lote.

---

## O Fluxo de Liquidação

```mermaid
flowchart TD
    Client([Cliente / API Gateway]) -->|POST /transactions com Idempotency-Key| WebAdapter[TransactionController]

    subgraph Core ["OmniFlow Core (Hexagonal & ACID)"]
        WebAdapter --> InPort[SubmitTransactionUseCase]
        InPort --> Orchestrator[FinancialOrchestratorService: @Transactional]

        subgraph Domain ["Domínio Contábil Puro"]
            LedgerAccount[LedgerAccount: Protegido por @Version]
            JournalEntry[JournalEntry: Split de 4 Pernas Balanceadas]
            Money[Money: Precisão Decimal Estrita]
        end

        Orchestrator --> Domain
        Orchestrator --> OutboxPort[OutboxRepositoryPort: Tabela outbox_events]
        Orchestrator --> LedgerPort[LedgerRepositoryPort: PostgreSQL ACID]
        Orchestrator --> IdempPort[IdempotencyStoragePort: Tabela idempotency_keys]
    end

    subgraph OutboxWorker ["Worker de Despacho Concorrente"]
        OutboxPort -->|SELECT ... FOR UPDATE SKIP LOCKED| OutboxRelay[OutboxRelayScheduledWorker]
        OutboxRelay -->|Fan-Out sem Dual-Write| SNS[AWS SNS Topic]
    end

    subgraph AWS ["AWS Messaging & Storage"]
        SNS --> SQS[AWS SQS Queue]
        SQS --> DLQ[AWS SQS Dead-Letter Queue]
    end

    subgraph Consumer ["Consumidor com Desduplicação"]
        SQS --> SqsListener[SqsSettlementConsumer: Reserva Atômica de ID]
        SqsListener --> MongoStore[MongoAuditEventStoreAdapter: Log Imutável de Auditoria]
    end

    subgraph BatchEngine ["Reconciliação Noturna (Spring Batch 5)"]
        BatchLauncher[ReconciliationBatchLauncher] -->|Cursor Keyset O(1)| LedgerPort
        BatchLauncher --> S3[AWS S3: Relatórios Periciais JSON]
    end
```

---

## Decisões Técnicas que Fazem a Diferença

### 1. Splits de 4 Pernas em Transação Única
Cada pagamento é registrado como uma única entrada de diário contábil (`JournalEntry`) com 4 lançamentos atômicos:
```text
R$ 1.000,00 Valor Bruto da Transação
├── Conta de Depósito do Comprador (Passivo)     -1000.00
├── Conta de Repasse do Lojista (Passivo)         +930.00
├── Conta de Receita da Plataforma (Receita)       +50.00
└── Reserva de Caução para Disputas (Passivo)      +20.00
─────────────────────────────────────────────────────────
Saldo Líquido da Movimentação                        0.00
```
Não existe meio-termo: ou as 4 pernas entram no banco de dados juntas, ou nenhuma entra.

### 2. Transactional Outbox com `SKIP LOCKED` na Nuvem
Para eliminar o problema de escrita dupla (*dual-write*):
- O evento de domínio é salvo na tabela `outbox_events` na mesma transação relacional do saldo.
- O `OutboxRelayScheduledWorker` busca os eventos pendentes usando:
  ```sql
  SELECT * FROM outbox_events 
  WHERE status IN ('PENDING', 'FAILED') AND retry_count < 3 
  ORDER BY created_at ASC
  FOR UPDATE SKIP LOCKED 
  LIMIT 50;
  ```
- O segredo do `SKIP LOCKED`: se você tiver 5 instâncias da aplicação rodando juntas em um cluster Kubernetes, elas disputam a tabela de eventos sem travar as linhas umas das outras. Cada instância pega um lote livre e pula os registros já bloqueados.
- Se a AWS rejeitar temporariamente (ex: throttling 429), o worker aplica retentativa com backoff exponencial e jitter aleatório para não sobrecarregar o broker.

### 3. Reconciliação Keyset no Spring Batch 5 (Zero Out Of Memory)
Como você audita o saldo de milhões de contas toda madrugada sem derrubar a aplicação por falta de memória?

A maioria dos sistemas comete o erro de usar paginação tradicional por offset (`LIMIT 100 OFFSET 1000000`). No PostgreSQL, o banco precisa escanear 1 milhão de linhas para jogar fora e entregar só as 100 seguintes. Conforme a tabela cresce, a query passa de 10ms para 30 segundos.

No OmniFlow, usamos **paginação Keyset baseada em cursor temporal**:
```sql
SELECT * FROM ledger_entries 
WHERE entry_id > :lastId AND created_at <= :cutoff 
ORDER BY entry_id ASC 
LIMIT 100;
```
O banco usa diretamente o índice da chave primária (`entry_id`). A query leva sempre menos de 5ms, independente de estar na página 1 ou na página 500.000. O consumo de memória heap da JVM permanece estritamente constante e previsível ($O(1)$).

### 4. Isolamento Determinístico de Mensagens Venenosas (Dead-Letter Queue)
Mensagens corrompidas ou malformadas que chegam no SQS não ficam em loop infinito travando os consumidores:
- Após 3 tentativas falhas com backoff, a mensagem é isolada na **Dead-Letter Queue (DLQ)**.
- Os metadados de diagnóstico, stack trace e payload original são registrados no MongoDB para investigação forense.

---

## Melhorias de Arquitetura para Alta Escala

### A. Compensação Contínua (Multilateral Netting)
Em vez de disparar uma TED/Pix individual para cada venda do marketplace (o que gera centenas de milhares de reais em taxas de liquidação e amarra liquidez no Banco Central):
- Implementamos janelas de compensação contínua (ex: ciclos de 5 minutos).
- O motor consolida todos os débitos e créditos mútuos entre lojistas e plataforma, liquidando apenas a obrigação financeira líquida apurada.

### B. Trilha Contábil Criptográfica (Merkle Ledger Audit)
Para garantir que nenhum administrador mal-intencionado altere um saldo direto no PostgreSQL (`UPDATE accounts SET balance = ...`):
- Cada lançamento contábil carrega um hash criptográfico encadeado (`HMAC-SHA256`) do registro anterior.
- A reconciliação do Spring Batch valida não só os números, mas o encadeamento das assinaturas. Qualquer adulteração direta quebra a cadeia de auditoria no primeiro centavo alterado.

---

## Stack Tecnológica

* **Java 17 LTS:** Records, Pattern Matching, Sealed Types, Hexagonal Architecture pura.
* **Spring Boot 3.3.4:** Core framework, Spring Data JPA, Actuator.
* **PostgreSQL 16:** Banco transacional ACID com versionamento otimista (`@Version`).
* **MongoDB 7.0:** Armazenamento append-only de logs de auditoria e triagem de incidentes.
* **AWS Cloud / LocalStack 3.7:** Emulação local completa de AWS SNS, SQS e S3.
* **Batch:** Spring Batch 5 para processamento em lotes com cursores keyset.
* **Segurança:** Suporte duplo a OAuth2 JWT e chaves de máquina M2M (`X-API-KEY`).

---

## Como Rodar Localmente

### 1. Subir a Infraestrutura (PostgreSQL, MongoDB e LocalStack AWS)
```bash
docker compose up -d
```

### 2. Executar a Suíte de Testes
```bash
# Windows
.\mvnw.cmd clean test

# Linux / macOS
./mvnw clean test
```

### 3. Subir a Aplicação
```bash
# Windows
.\mvnw.cmd spring-boot:run

# Linux / macOS
./mvnw spring-boot:run
```

---

## Licença

Distribuído sob a licença [Apache 2.0](LICENSE).
