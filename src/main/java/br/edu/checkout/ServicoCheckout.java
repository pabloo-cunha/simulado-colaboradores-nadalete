package br.edu.checkout;

import java.math.BigDecimal;
import java.util.Objects;

/** Coordena os sistemas externos envolvidos no checkout de um único item. */
public class ServicoCheckout {
    private final EstoqueGateway estoque;
    private final PagamentoGateway pagamento;
    private final PedidoRepository pedidos;
    private final Notificador notificador;

    public ServicoCheckout(
            EstoqueGateway estoque,
            PagamentoGateway pagamento,
            PedidoRepository pedidos,
            Notificador notificador
    ) {
        this.estoque = Objects.requireNonNull(estoque);
        this.pagamento = Objects.requireNonNull(pagamento);
        this.pedidos = Objects.requireNonNull(pedidos);
        this.notificador = Objects.requireNonNull(notificador);
    }

    public ResultadoCheckout finalizar(SolicitacaoCheckout solicitacao) {
        validar(solicitacao);

        BigDecimal total = solicitacao.precoUnitario()
                .multiply(BigDecimal.valueOf(solicitacao.quantidade()));

        if (!estoque.reservar(solicitacao.sku(), solicitacao.quantidade())) {
            throw new EstoqueInsuficienteException();
        }

        Pagamento cobranca;
        try {
            cobranca = pagamento.cobrar(solicitacao.clienteId(), total);
        } catch (FalhaPagamentoException exception) {
            estoque.liberar(solicitacao.sku(), solicitacao.quantidade());
            throw exception;
        }

        if (!cobranca.aprovado()) {
            estoque.liberar(solicitacao.sku(), solicitacao.quantidade());
            throw new PagamentoRecusadoException();
        }

        PedidoConfirmado pedido = new PedidoConfirmado(
                solicitacao.clienteId(),
                solicitacao.email(),
                solicitacao.sku(),
                solicitacao.quantidade(),
                total,
                cobranca.transacaoId()
        );
        String numeroPedido = pedidos.salvar(pedido);

        try {
            notificador.enviarConfirmacao(solicitacao.email(), numeroPedido, total);
            return new ResultadoCheckout(numeroPedido, total, true);
        } catch (FalhaNotificacaoException exception) {
            return new ResultadoCheckout(numeroPedido, total, false);
        }
    }

    private void validar(SolicitacaoCheckout solicitacao) {
        if (solicitacao == null
                || emBranco(solicitacao.clienteId())
                || emBranco(solicitacao.email())
                || emBranco(solicitacao.sku())
                || solicitacao.quantidade() <= 0
                || solicitacao.precoUnitario() == null
                || solicitacao.precoUnitario().signum() <= 0) {
            throw new IllegalArgumentException("Solicitação de checkout inválida");
        }
    }

    private boolean emBranco(String texto) {
        return texto == null || texto.isBlank();
    }
}
