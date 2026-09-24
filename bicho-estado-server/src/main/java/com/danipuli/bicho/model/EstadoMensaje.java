package com.danipuli.bicho.model;

/**
 * Mensaje que viaja por WebSocket (y por el endpoint REST de prueba) para anunciar
 * un cambio de estado del bicho.
 *
 * Ejemplo de JSON:
 * { "estado": "hablando", "texto": "Hola Daniel, aquí estoy" }
 *
 * El campo "texto" es opcional (solo tiene sentido cuando estado = hablando, como subtítulo).
 */
public class EstadoMensaje {

    private String estado;
    private String texto;

    public EstadoMensaje() {
        // constructor vacío necesario para que Jackson pueda montar el objeto desde JSON
    }

    public EstadoMensaje(String estado, String texto) {
        this.estado = estado;
        this.texto = texto;
    }

    public static EstadoMensaje de(Estado estado, String texto) {
        return new EstadoMensaje(estado.comoTexto(), texto);
    }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }

    public String getTexto() {
        return texto;
    }

    public void setTexto(String texto) {
        this.texto = texto;
    }
}
