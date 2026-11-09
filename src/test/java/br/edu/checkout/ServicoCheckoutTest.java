package br.edu.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServicoCheckoutTest {
    @Mock private EstoqueGateway estoque;
    @Mock private PagamentoGateway pagamento;
    @Mock private PedidoRepository pedidos;
    @Mock private Notificador notificador;

    @ParameterizedTest(name = "entrada inválida: {0}")
    @MethodSource("solicitacoesInvalidas")
    void rejeitaEntradaInvalidaSemChamarColaboradores(String descricao, SolicitacaoCheckout solicitacao) {
        ServicoCheckout servico = servico();

        assertThrows(IllegalArgumentException.class, () -> servico.finalizar(solicitacao));

        verifyNoInteractions(estoque, pagamento, pedidos, notificador);
    }

    private static Stream<Arguments> solicitacoesInvalidas() {
        BigDecimal preco = new BigDecimal("10.00");
        return Stream.of(
                Arguments.of("solicitação nula", null),
                Arguments.of("cliente nulo", new SolicitacaoCheckout(null, "ana@exemplo.com", "SKU-1", 1, preco)),
                Arguments.of("cliente vazio", new SolicitacaoCheckout("", "ana@exemplo.com", "SKU-1", 1, preco)),
                Arguments.of("cliente em branco", new SolicitacaoCheckout("  ", "ana@exemplo.com", "SKU-1", 1, preco)),
                Arguments.of("email nulo", new SolicitacaoCheckout("cli-1", null, "SKU-1", 1, preco)),
                Arguments.of("email vazio", new SolicitacaoCheckout("cli-1", "", "SKU-1", 1, preco)),
                Arguments.of("email em branco", new SolicitacaoCheckout("cli-1", "\t", "SKU-1", 1, preco)),
                Arguments.of("sku nulo", new SolicitacaoCheckout("cli-1", "ana@exemplo.com", null, 1, preco)),
                Arguments.of("sku vazio", new SolicitacaoCheckout("cli-1", "ana@exemplo.com", "", 1, preco)),
                Arguments.of("sku em branco", new SolicitacaoCheckout("cli-1", "ana@exemplo.com", " ", 1, preco)),
                Arguments.of("quantidade zero", new SolicitacaoCheckout("cli-1", "ana@exemplo.com", "SKU-1", 0, preco)),
                Arguments.of("quantidade negativa", new SolicitacaoCheckout("cli-1", "ana@exemplo.com", "SKU-1", -1, preco)),
                Arguments.of("preço nulo", new SolicitacaoCheckout("cli-1", "ana@exemplo.com", "SKU-1", 1, null)),
                Arguments.of("preço zero", new SolicitacaoCheckout("cli-1", "ana@exemplo.com", "SKU-1", 1, BigDecimal.ZERO)),
                Arguments.of("preço negativo", new SolicitacaoCheckout("cli-1", "ana@exemplo.com", "SKU-1", 1, new BigDecimal("-0.01")))
        );
    }

    @Test
    void aceitaQuantidadeMinimaEConservaPrecoComoTotal() {
        SolicitacaoCheckout solicitacao = solicitacao("1", "7.50");
        prepararCompraAprovada(solicitacao, "PED-1", "TX-1");

        ResultadoCheckout resultado = servico().finalizar(solicitacao);

        assertEquals(new BigDecimal("7.50"), resultado.total());
        assertTrue(resultado.notificacaoEnviada());
    }

    @Test
    void calculaTotalUsualEEnviaAoPagamento() {
        SolicitacaoCheckout solicitacao = solicitacao("3", "19.90");
        prepararCompraAprovada(solicitacao, "PED-2", "TX-2");

        ResultadoCheckout resultado = servico().finalizar(solicitacao);

        assertEquals(new BigDecimal("59.70"), resultado.total());
        verify(pagamento).cobrar("cliente-1", new BigDecimal("59.70"));
    }

    @Test
    void preservaPrecisaoSemArredondar() {
        SolicitacaoCheckout solicitacao = solicitacao("3", "0.005");
        prepararCompraAprovada(solicitacao, "PED-3", "TX-3");

        ResultadoCheckout resultado = servico().finalizar(solicitacao);

        assertEquals(new BigDecimal("0.015"), resultado.total());
        verify(pagamento).cobrar("cliente-1", new BigDecimal("0.015"));
    }

    @Test
    void naoCobraNemLiberaQuandoNaoHaEstoque() {
        SolicitacaoCheckout solicitacao = solicitacao("2", "10.00");
        when(estoque.reservar("SKU-1", 2)).thenReturn(false);

        assertThrows(EstoqueInsuficienteException.class, () -> servico().finalizar(solicitacao));

        verify(estoque, times(1)).reservar("SKU-1", 2);
        verify(estoque, never()).liberar("SKU-1", 2);
        verifyNoInteractions(pagamento, pedidos, notificador);
    }

    @Test
    void liberaReservaAposPagamentoRecusado() {
        SolicitacaoCheckout solicitacao = solicitacao("2", "10.00");
        when(estoque.reservar("SKU-1", 2)).thenReturn(true);
        when(pagamento.cobrar("cliente-1", new BigDecimal("20.00")))
                .thenReturn(new Pagamento(false, null));

        assertThrows(PagamentoRecusadoException.class, () -> servico().finalizar(solicitacao));

        InOrder ordem = inOrder(estoque, pagamento);
        ordem.verify(estoque).reservar("SKU-1", 2);
        ordem.verify(pagamento).cobrar("cliente-1", new BigDecimal("20.00"));
        ordem.verify(estoque).liberar("SKU-1", 2);
        verify(estoque, times(1)).liberar("SKU-1", 2);
        verifyNoInteractions(pedidos, notificador);
    }

    @Test
    void liberaReservaEPropagaMesmaFalhaTecnicaDePagamento() {
        SolicitacaoCheckout solicitacao = solicitacao("2", "10.00");
        FalhaPagamentoException falha = new FalhaPagamentoException("gateway indisponível");
        when(estoque.reservar("SKU-1", 2)).thenReturn(true);
        when(pagamento.cobrar("cliente-1", new BigDecimal("20.00"))).thenThrow(falha);

        FalhaPagamentoException lancada = assertThrows(
                FalhaPagamentoException.class,
                () -> servico().finalizar(solicitacao)
        );

        assertSame(falha, lancada);
        verify(estoque).liberar("SKU-1", 2);
        verifyNoInteractions(pedidos, notificador);
    }

    @Test
    void persistePedidoComTodosOsDadosConfirmados() {
        SolicitacaoCheckout solicitacao = solicitacao("3", "19.90");
        prepararCompraAprovada(solicitacao, "PED-4", "TX-4");
        ArgumentCaptor<PedidoConfirmado> captor = ArgumentCaptor.forClass(PedidoConfirmado.class);

        servico().finalizar(solicitacao);

        verify(pedidos).salvar(captor.capture());
        PedidoConfirmado pedido = captor.getValue();
        assertEquals("cliente-1", pedido.clienteId());
        assertEquals("ana@exemplo.com", pedido.email());
        assertEquals("SKU-1", pedido.sku());
        assertEquals(3, pedido.quantidade());
        assertEquals(new BigDecimal("59.70"), pedido.total());
        assertEquals("TX-4", pedido.transacaoId());
    }

    @Test
    void registraENotificaCompraAprovadaNaOrdemDeNegocio() {
        SolicitacaoCheckout solicitacao = solicitacao("2", "10.00");
        prepararCompraAprovada(solicitacao, "PED-5", "TX-5");

        ResultadoCheckout resultado = servico().finalizar(solicitacao);

        assertEquals("PED-5", resultado.numeroPedido());
        assertEquals(new BigDecimal("20.00"), resultado.total());
        assertTrue(resultado.notificacaoEnviada());
        verify(notificador).enviarConfirmacao("ana@exemplo.com", "PED-5", new BigDecimal("20.00"));
        verify(estoque, never()).liberar("SKU-1", 2);
        InOrder ordem = inOrder(estoque, pagamento, pedidos, notificador);
        ordem.verify(estoque).reservar("SKU-1", 2);
        ordem.verify(pagamento).cobrar("cliente-1", new BigDecimal("20.00"));
        ordem.verify(pedidos).salvar(new PedidoConfirmado(
                "cliente-1", "ana@exemplo.com", "SKU-1", 2, new BigDecimal("20.00"), "TX-5"));
        ordem.verify(notificador).enviarConfirmacao("ana@exemplo.com", "PED-5", new BigDecimal("20.00"));
    }

    @Test
    void mantemCompraConfirmadaQuandoNotificacaoFalha() {
        SolicitacaoCheckout solicitacao = solicitacao("2", "10.00");
        prepararCompraAprovada(solicitacao, "PED-6", "TX-6");
        doThrow(new FalhaNotificacaoException("smtp indisponível"))
                .when(notificador)
                .enviarConfirmacao("ana@exemplo.com", "PED-6", new BigDecimal("20.00"));

        ResultadoCheckout resultado = servico().finalizar(solicitacao);

        assertEquals("PED-6", resultado.numeroPedido());
        assertEquals(new BigDecimal("20.00"), resultado.total());
        assertFalse(resultado.notificacaoEnviada());
        verify(pagamento, times(1)).cobrar("cliente-1", new BigDecimal("20.00"));
        verify(pedidos, times(1)).salvar(new PedidoConfirmado(
                "cliente-1", "ana@exemplo.com", "SKU-1", 2, new BigDecimal("20.00"), "TX-6"));
        verify(estoque, never()).liberar("SKU-1", 2);
    }

    private void prepararCompraAprovada(SolicitacaoCheckout solicitacao, String numeroPedido, String transacaoId) {
        BigDecimal total = solicitacao.precoUnitario().multiply(BigDecimal.valueOf(solicitacao.quantidade()));
        when(estoque.reservar(solicitacao.sku(), solicitacao.quantidade())).thenReturn(true);
        when(pagamento.cobrar(solicitacao.clienteId(), total)).thenReturn(new Pagamento(true, transacaoId));
        when(pedidos.salvar(new PedidoConfirmado(
                solicitacao.clienteId(), solicitacao.email(), solicitacao.sku(), solicitacao.quantidade(), total, transacaoId
        ))).thenReturn(numeroPedido);
    }

    private SolicitacaoCheckout solicitacao(String quantidade, String preco) {
        return new SolicitacaoCheckout("cliente-1", "ana@exemplo.com", "SKU-1", Integer.parseInt(quantidade), new BigDecimal(preco));
    }

    private ServicoCheckout servico() {
        return new ServicoCheckout(estoque, pagamento, pedidos, notificador);
    }
}
