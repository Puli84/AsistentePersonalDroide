package com.danipuli.bicho.model;

/**
 * Mensaje que viaja por WebSocket (y por el endpoint REST de prueba) para anunciar
 * un cambio de estado del bicho.
 *
 * Ejemplo de JSON:
 * { "estado": "hablando", "texto": "Hola Daniel, aquí estoy" }
 *
 * El campo "texto" es opcional (solo tiene sentido cuando estado = hablando, como subtítulo).
 * El campo "emocion" es opcional (neutral, contento, triste, sorprendido, enfadado, pensativo):
 * lo pone Claude con la herramienta poner_cara.
 */
public class EstadoMensaje {

    private String estado;
    private String texto;
    private String emocion;

    public EstadoMensaje() {
        // constructor vacío necesario para que Jackson pueda montar el objeto desde JSON
    }

    public EstadoMensaje(String estado, String texto) {
        this.estado = estado;
        this.texto = texto;
    }

    public EstadoMensaje(String estado, String texto, String emocion) {
        this.estado = estado;
        this.texto = texto;
        this.emocion = emocion;
    }

    public static EstadoMensaje de(Estado estado, String texto) {
        return new EstadoMensaje(estado.comoTexto(), texto);
    }

    public static EstadoMensaje de(Estado estado, String texto, String emocion) {
        return new EstadoMensaje(estado.comoTexto(), texto, emocion);
    }

    public String getEmocion() {
        return emocion;
    }

    public void setEmocion(String emocion) {
        this.emocion = emocion;
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
