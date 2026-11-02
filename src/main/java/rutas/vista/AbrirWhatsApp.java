package rutas.vista;

import java.awt.Desktop;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import rutas.logica.Avisos;

/**
 * Abre WhatsApp con un mensaje ya escrito. Si la app de escritorio de WhatsApp es la que abre los
 * enlaces whatsapp:// se abre directo, sin navegador; si no (por ejemplo cuando ese enlace lo tiene
 * Edge o Chrome), se usa el enlace wa.me de siempre.
 */
public final class AbrirWhatsApp {

    private static Boolean hayApp; // se averigua una sola vez

    private AbrirWhatsApp() {
    }

    /** telefono "" = WhatsApp pregunta a qué chat. Lanza Exception si no se pudo abrir nada. */
    static void abrir(String telefono, String mensaje) throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (appInstalada()) {
            String enlace = Avisos.enlaceWhatsAppApp(telefono, mensaje);
            try {
                if (windows) {
                    // Se le pasa a Windows igual que si se escribiera en Ejecutar (Win+R)
                    new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", enlace).start();
                } else {
                    Desktop.getDesktop().browse(new URI(enlace));
                }
                return;
            } catch (Exception e) {
                hayApp = false; // no se pudo: de aqui en adelante por el enlace wa.me
            }
        }
        String web = telefono == null || telefono.isBlank() ? Avisos.enlaceWhatsAppSinNumero(mensaje)
                : Avisos.enlaceWhatsApp(telefono, mensaje);
        Desktop.getDesktop().browse(new URI(web));
    }

    /** Mira en Windows qué programa abre los enlaces whatsapp://. */
    static synchronized boolean appInstalada() {
        if (hayApp == null) {
            hayApp = false;
            String so = System.getProperty("os.name", "").toLowerCase();
            try {
                if (so.contains("win")) {
                    // 1) el programa que el usuario eligió para whatsapp://
                    String eleccion = leerRegistro("HKCU\\Software\\Microsoft\\Windows\\Shell\\Associations\\UrlAssociations\\whatsapp\\UserChoice", "ProgId");
                    if (eleccion != null) {
                        hayApp = esLaApp(eleccion);
                    } else {
                        // 2) si no eligió nada, el que quedó registrado
                        String comando = leerRegistro("HKEY_CLASSES_ROOT\\whatsapp\\shell\\open\\command", null);
                        hayApp = comando != null && esLaApp(comando);
                    }
                } else if (so.contains("mac")) {
                    hayApp = new java.io.File("/Applications/WhatsApp.app").exists();
                }
            } catch (Exception e) {
                hayApp = false;
            }
        }
        return hayApp;
    }

    /**
     * true si el texto del registro es de la app de WhatsApp (la de la Microsoft Store empieza por
     * AppX, la clásica es WhatsApp.exe); false si es de un navegador (Edge, Chrome, Firefox...).
     */
    public static boolean esLaApp(String valor) {
        String v = valor.toLowerCase().trim();
        if (v.startsWith("appx")) {
            return true; // app de la Microsoft Store
        }
        String[] navegadores = { "edge", "chrome", "firefox", "brave", "opera", "browser", "iexplore", "html" };
        for (String n : navegadores) {
            if (v.contains(n)) {
                return false;
            }
        }
        return v.contains("whatsapp");
    }

    /** Lee un valor del registro con reg query; null si no existe. nombre null = valor por defecto. */
    private static String leerRegistro(String clave, String nombre) throws Exception {
        ProcessBuilder pb = nombre == null ? new ProcessBuilder("reg", "query", clave, "/ve")
                : new ProcessBuilder("reg", "query", clave, "/v", nombre);
        Process p = pb.redirectErrorStream(true).start();
        String salida = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(3, TimeUnit.SECONDS) || p.exitValue() != 0) {
            return null;
        }
        for (String linea : salida.split("\\R")) {
            int i = linea.indexOf("REG_");
            if (i >= 0) {
                int fin = linea.indexOf(' ', i);
                return fin < 0 ? "" : linea.substring(fin).trim();
            }
        }
        return null;
    }
}
