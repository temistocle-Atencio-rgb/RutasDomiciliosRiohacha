package rutas.logica;

/** Puerta para que las pruebas armen un correo sin enviarlo. */
public final class CorreoPrueba {
    private CorreoPrueba() {
    }

    public static String mensaje(String cuenta, String remitente, String para, String asunto, String texto, String html,
            String adjunto, byte[] datos, String tipo) {
        return CorreoSmtp.mensaje(cuenta, remitente, para, asunto, texto, html, adjunto, datos, tipo);
    }

    public static String mensaje(String cuenta, String remitente, String para, String asunto, String texto, String html,
            byte[] logo, String adjunto, byte[] datos, String tipo) {
        return CorreoSmtp.mensaje(cuenta, remitente, para, asunto, texto, html, logo, adjunto, datos, tipo);
    }
}
