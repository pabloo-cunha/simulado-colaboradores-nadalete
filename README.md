# Checkout com colaboradores simulados

Implementação do exercício de checkout no pacote `br.edu.checkout`. O serviço é real; estoque, pagamento, persistência e notificação são interfaces simuladas nos testes com Mockito.

## Executar

O enunciado fixa Java 21 ou superior. Com um JDK compatível instalado, execute:

```bash
mvn test
```

## O que foi mockado

Foram mockados `EstoqueGateway`, `PagamentoGateway`, `PedidoRepository` e `Notificador`, pois representam fronteiras externas. `ServicoCheckout`, records, exceções e `BigDecimal` são objetos reais.

Um teste de estado é `calculaTotalUsualEEnviaAoPagamento`, que confere o total retornado. Um teste de interação é `registraENotificaCompraAprovadaNaOrdemDeNegocio`, que confere argumentos e sequência dos quatro efeitos. O `ArgumentCaptor` em `persistePedidoComTodosOsDadosConfirmados` detecta, por exemplo, um pedido gravado com total, SKU ou identificador de transação incorreto. A ordem é parte do requisito: cobrar antes de reservar ou notificar antes de salvar pode produzir efeitos externos inconsistentes.

## Registro TDD

O enunciado pede evidência de três ciclos reais de Red–Green–Refactor. Como este projeto foi montado a partir de uma pasta sem projeto-base, não há como afirmar historicamente ciclos que não foram registrados. Use [docs/tdd-ciclos.md](docs/tdd-ciclos.md) durante a realização/defesa para anotar execuções reais.
