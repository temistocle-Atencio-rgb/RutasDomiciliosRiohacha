package rutas.logica;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import javax.net.ssl.SSLSocketFactory;

/**
 * Envia un correo con un archivo adjunto por SMTP (Gmail: smtp.gmail.com, puerto 465 con SSL),
 * sin librerias: se habla el protocolo directo. La clave es la "contraseña de aplicación" de Google.
 */
public class CorreoSmtp {

    /** Error con un mensaje para mostrar tal cual en pantalla. */
    public static class ErrorCorreo extends Exception {
        public ErrorCorreo(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    private final String servidor;
    private final int puerto;
    private final boolean ssl;
    private final String cuenta;
    private final String clave;

    public CorreoSmtp(String servidor, int puerto, boolean ssl, String cuenta, String clave) {
        this.servidor = servidor;
        this.puerto = puerto;
        this.ssl = ssl;
        this.cuenta = cuenta;
        this.clave = clave == null ? "" : clave.replace(" ", ""); // Google la muestra en grupos de 4
    }

    /** Gmail con la contraseña de aplicación. */
    public static CorreoSmtp gmail(String cuenta, String claveAplicacion) {
        return new CorreoSmtp("smtp.gmail.com", 465, true, cuenta, claveAplicacion);
    }

    /**
     * Envia el correo. adjunto puede ser null. Lanza ErrorCorreo con una explicacion sencilla.
     */
    public void enviar(String nombreRemitente, String para, String asunto, String texto, String nombreAdjunto,
            byte[] adjunto, String tipoAdjunto) throws ErrorCorreo {
        enviar(nombreRemitente, para, asunto, texto, null, nombreAdjunto, adjunto, tipoAdjunto);
    }

    /**
     * html: la misma carta con formato (null = solo texto). Un correo con texto y HTML, el detalle de la
     * compra en el cuerpo y su Message-ID se parece a un recibo de verdad y no a un "PDF suelto" (eso lo
     * manda Gmail a spam).
     */
    public void enviar(String nombreRemitente, String para, String asunto, String texto, String html,
            String nombreAdjunto, byte[] adjunto, String tipoAdjunto) throws ErrorCorreo {
        enviar(nombreRemitente, para, asunto, texto, html, null, nombreAdjunto, adjunto, tipoAdjunto);
    }

    /** Content-ID con el que el HTML muestra la imagen que va dentro del correo: src="cid:logo@domiruta". */
    public static final String CID_LOGO = "logo@domiruta";

    /** logoPng: imagen que el HTML muestra con cid:logo@domiruta (va dentro del correo, no como adjunto). */
    public void enviar(String nombreRemitente, String para, String asunto, String texto, String html, byte[] logoPng,
            String nombreAdjunto, byte[] adjunto, String tipoAdjunto) throws ErrorCorreo {
        if (!esCorreo(para)) {
            throw new ErrorCorreo("Revise el correo del cliente (ej. nombre@gmail.com)", null);
        }
        if (!esCorreo(cuenta) || clave.isEmpty()) {
            throw new ErrorCorreo("Falta configurar el correo de la tienda en Configuración › Correo", null);
        }
        // si la conexion se corta antes de que Gmail reciba el correo completo, se intenta una vez mas
        for (int intento = 1; ; intento++) {
            try {
                sesion(nombreRemitente, para, asunto, texto, html, logoPng, nombreAdjunto, adjunto, tipoAdjunto);
                return;
            } catch (ErrorCorreo e) {
                boolean cortada = e.getCause() instanceof java.net.SocketException
                        && !(e.getCause() instanceof java.net.ConnectException);
                if (!cortada || intento >= 2) {
                    throw e;
                }
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Una conexion con el servidor: saludo, clave, remitente, destinatario y el correo completo. */
    private void sesion(String nombreRemitente, String para, String asunto, String texto, String html, byte[] logoPng,
            String nombreAdjunto, byte[] adjunto, String tipoAdjunto) throws ErrorCorreo {
        Socket s = null;
        try {
            if (ssl) {
                s = SSLSocketFactory.getDefault().createSocket();
            } else {
                s = new Socket();
            }
            s.connect(new InetSocketAddress(servidor, puerto), 15_000);
            s.setSoTimeout(60_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = new java.io.BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            esperar(in, 220);
            decir(out, "EHLO domiruta");
            esperar(in, 250);
            decir(out, "AUTH LOGIN");
            esperar(in, 334);
            decir(out, b64(cuenta));
            esperar(in, 334);
            decir(out, b64(clave));
            int r = leerCodigo(in);
            if (r != 235) {
                throw new ErrorCorreo("Google no aceptó el correo o la clave. Revise en Configuración › Correo que"
                        + " sea la contraseña de aplicación de 16 letras (no la clave normal).", null);
            }
            decir(out, "MAIL FROM:<" + cuenta + ">");
            esperar(in, 250);
            decir(out, "RCPT TO:<" + para.trim() + ">");
            esperar(in, 250, 251);
            decir(out, "DATA");
            esperar(in, 354);
            out.write(mensaje(cuenta, nombreRemitente, para.trim(), asunto, texto, html, logoPng, nombreAdjunto, adjunto,
                    tipoAdjunto)
                    .getBytes(StandardCharsets.UTF_8));
            decir(out, ".");
            esperar(in, 250);
            decir(out, "QUIT");
        } catch (ErrorCorreo e) {
            throw e;
        } catch (java.net.UnknownHostException | java.net.ConnectException | java.net.SocketTimeoutException e) {
            throw new ErrorCorreo("No hay conexión a internet (o el servidor de correo no responde). Intente de nuevo.", e);
        } catch (java.net.SocketException e) {
            throw new ErrorCorreo("Se cortó la conexión con Gmail mientras se enviaba (puede ser el internet o un"
                    + " antivirus que revisa el correo). Espere un momento e intente de nuevo.", e);
        } catch (IOException e) {
            throw new ErrorCorreo("No se pudo enviar el correo: " + e.getMessage(), e);
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException e) {
                    // nada
                }
            }
        }
    }

    public static boolean esCorreo(String c) {
        return c != null && c.trim().matches("[^@\\s<>]+@[^@\\s<>]+\\.[^@\\s<>]+");
    }

    /** El texto completo del correo (cabeceras + cuerpo + adjunto en base64), listo para DATA. */
    static String mensaje(String cuenta, String nombreRemitente, String para, String asunto, String texto, String nombreAdjunto,
            byte[] adjunto, String tipoAdjunto) {
        return mensaje(cuenta, nombreRemitente, para, asunto, texto, null, nombreAdjunto, adjunto, tipoAdjunto);
    }

    static String mensaje(String cuenta, String nombreRemitente, String para, String asunto, String texto, String html,
            String nombreAdjunto, byte[] adjunto, String tipoAdjunto) {
        return mensaje(cuenta, nombreRemitente, para, asunto, texto, html, null, nombreAdjunto, adjunto, tipoAdjunto);
    }

    static String mensaje(String cuenta, String nombreRemitente, String para, String asunto, String texto, String html,
            byte[] logoPng, String nombreAdjunto, byte[] adjunto, String tipoAdjunto) {
        String limiteRel = "----=_DomiRutaRel_" + UUID.randomUUID().toString().replace("-", "");
        boolean conLogo = html != null && logoPng != null;
        String limite = "----=_DomiRuta_" + UUID.randomUUID().toString().replace("-", "");
        String limiteAlt = "----=_DomiRutaAlt_" + UUID.randomUUID().toString().replace("-", "");
        String dominio = cuenta.contains("@") ? cuenta.substring(cuenta.indexOf('@') + 1) : "domiruta.local";
        StringBuilder m = new StringBuilder();
        m.append("From: ").append(palabra(nombreRemitente)).append(" <").append(cuenta).append(">\r\n");
        m.append("To: <").append(para).append(">\r\n");
        m.append("Subject: ").append(palabra(asunto)).append("\r\n");
        m.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US).format(ZonedDateTime.now()))
                .append("\r\n");
        m.append("Message-ID: <").append(UUID.randomUUID()).append('@').append(dominio).append(">\r\n");
        m.append("Reply-To: ").append(palabra(nombreRemitente)).append(" <").append(cuenta).append(">\r\n");
        m.append("MIME-Version: 1.0\r\n");
        m.append("Content-Type: multipart/mixed; boundary=\"").append(limite).append("\"\r\n\r\n");
        m.append("--").append(limite).append("\r\n");
        if (conLogo) { // el HTML y su logo van juntos (multipart/related); el PDF va aparte como adjunto
            m.append("Content-Type: multipart/related; type=\"multipart/alternative\"; boundary=\"").append(limiteRel)
                    .append("\"\r\n\r\n");
            m.append("--").append(limiteRel).append("\r\n");
        }
        if (html != null) { // texto y HTML juntos: cada programa de correo muestra el que sepa
            m.append("Content-Type: multipart/alternative; boundary=\"").append(limiteAlt).append("\"\r\n\r\n");
            m.append("--").append(limiteAlt).append("\r\n");
        }
        m.append("Content-Type: text/plain; charset=UTF-8\r\n");
        m.append("Content-Transfer-Encoding: base64\r\n\r\n");
        m.append(lineas(Base64.getEncoder().encodeToString(texto.replace("\r\n", "\n").replace("\n", "\r\n")
                .getBytes(StandardCharsets.UTF_8))));
        if (html != null) {
            m.append("--").append(limiteAlt).append("\r\n");
            m.append("Content-Type: text/html; charset=UTF-8\r\n");
            m.append("Content-Transfer-Encoding: base64\r\n\r\n");
            m.append(lineas(Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8))));
            m.append("--").append(limiteAlt).append("--\r\n");
        }
        if (conLogo) {
            m.append("--").append(limiteRel).append("\r\n");
            m.append("Content-Type: image/png; name=\"logo.png\"\r\n");
            m.append("Content-Transfer-Encoding: base64\r\n");
            m.append("Content-ID: <").append(CID_LOGO).append(">\r\n");
            m.append("X-Attachment-Id: logo\r\n"); // asi marca Gmail sus propias imagenes dentro del correo
            m.append("Content-Disposition: inline; filename=\"logo.png\"\r\n\r\n");
            m.append(lineas(Base64.getEncoder().encodeToString(logoPng)));
            m.append("--").append(limiteRel).append("--\r\n");
        }
        if (adjunto != null) {
            m.append("--").append(limite).append("\r\n");
            m.append("Content-Type: ").append(tipoAdjunto).append("; name=\"").append(nombreAdjunto).append("\"\r\n");
            m.append("Content-Transfer-Encoding: base64\r\n");
            m.append("Content-Disposition: attachment; filename=\"").append(nombreAdjunto).append("\"\r\n\r\n");
            m.append(lineas(Base64.getEncoder().encodeToString(adjunto)));
        }
        m.append("--").append(limite).append("--\r\n");
        return m.toString();
    }

    /** Base64 en renglones de 76 (asi lo pide el formato de correo). Ningun renglon empieza con punto. */
    private static String lineas(String b) {
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < b.length(); i += 76) {
            r.append(b, i, Math.min(b.length(), i + 76)).append("\r\n");
        }
        return r.toString();
    }

    /** Cabecera con tildes: =?UTF-8?B?...?= */
    private static String palabra(String s) {
        String t = s == null ? "" : s.replaceAll("[\\r\\n]", " ").trim();
        if (t.chars().allMatch(c -> c >= 32 && c < 127) && !t.contains("\"")) {
            return t;
        }
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(t.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void decir(OutputStream out, String linea) throws IOException {
        out.write((linea + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Lee la respuesta del servidor (puede tener varios renglones "250-...") y devuelve el codigo. */
    private static int leerCodigo(BufferedReader in) throws IOException {
        String l;
        do {
            l = in.readLine();
            if (l == null || l.length() < 3) {
                throw new IOException("el servidor cerró la conexión");
            }
        } while (l.length() > 3 && l.charAt(3) == '-');
        return Integer.parseInt(l.substring(0, 3));
    }

    private static void esperar(BufferedReader in, int... codigos) throws IOException {
        int c = leerCodigo(in);
        for (int k : codigos) {
            if (c == k) {
                return;
            }
        }
        throw new IOException("respuesta inesperada del servidor (" + c + ")");
    }
}
