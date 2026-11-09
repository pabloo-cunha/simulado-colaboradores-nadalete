package br.edu.checkout;

public class FalhaPagamentoException extends RuntimeException {
    public FalhaPagamentoException(String message) {
        super(message);
    }
}
