package rutas.vista;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;

/**
 * Sonidos de caja, generados aqui mismo (sin archivos de audio):
 * - bien: el "bip" agudo y corto de un lector de supermercado (producto agregado);
 * - clic: un toque suave y bajito (producto tocado con el mouse);
 * - error: dos tonos graves (codigo que no existe o producto agotado).
 * Si el computador no tiene sonido, queda el pitido de Windows.
 */
final class Pitido {

    private static final float MUESTRAS = 44_100f;
    private static Clip bien;
    private static Clip error;
    private static Clip clic;
    private static boolean sinSonido;

    private Pitido() {
    }

    /** Producto agregado. */
    static void bien() {
        sonar(true);
    }

    /** Codigo desconocido, agotado o no alcanza. */
    static void error() {
        sonar(false);
    }

    /** Producto tocado con el mouse: un clic suave, para no llenar la tienda de pitidos. */
    static synchronized void clic() {
        if (sinSonido) {
            return; // sin parlantes, el clic no hace falta
        }
        try {
            if (clic == null) {
                clic = clip(tonos(new int[]{1200}, 12, 0, 0.12));
            }
            clic.stop();
            clic.setFramePosition(0);
            clic.start();
        } catch (Exception | LinkageError e) {
            sinSonido = true;
        }
    }

    private static Clip campana;

    /** Aviso de la campana: dos notas suaves. Si el computador no tiene sonido, no suena nada (nunca el de Windows). */
    static synchronized void aviso() {
        if (sinSonido) {
            return;
        }
        try {
            if (campana == null) {
                campana = clip(tonos(new int[]{880, 1320}, 110, 40, 0.35));
            }
            campana.stop();
            campana.setFramePosition(0);
            campana.start();
        } catch (Exception | LinkageError e) {
            sinSonido = true;
        }
    }

    private static synchronized void sonar(boolean ok) {
        if (sinSonido) {
            java.awt.Toolkit.getDefaultToolkit().beep();
            return;
        }
        try {
            if (bien == null) {
                // 2.400 Hz durante 90 ms, como un lector de verdad
                bien = clip(tonos(new int[]{2400}, 90, 0));
                // 440 Hz dos veces, 120 ms cada uno con 60 ms de silencio
                error = clip(tonos(new int[]{440, 440}, 120, 60));
            }
            Clip c = ok ? bien : error;
            c.stop();
            c.setFramePosition(0);
            c.start();
        } catch (Exception | LinkageError e) {
            sinSonido = true; // sin tarjeta de sonido (o en un servidor): el de Windows
            java.awt.Toolkit.getDefaultToolkit().beep();
        }
    }

    /** Tonos seguidos con un silencio entre ellos; cada uno sube y baja suave para que no "truene". */
    static byte[] tonos(int[] frecuencias, int msTono, int msSilencio) {
        return tonos(frecuencias, msTono, msSilencio, 0.35);
    }

    static byte[] tonos(int[] frecuencias, int msTono, int msSilencio, double volumen) {
        int porTono = (int) (MUESTRAS * msTono / 1000);
        int porSilencio = (int) (MUESTRAS * msSilencio / 1000);
        int rampa = Math.max(1, Math.min((int) (MUESTRAS * 0.004), porTono / 3)); // entrada y salida suaves
        byte[] b = new byte[(porTono + porSilencio) * frecuencias.length * 2];
        int i = 0;
        for (int f : frecuencias) {
            for (int n = 0; n < porTono; n++) {
                double env = Math.min(1, Math.min(n, porTono - 1 - n) / (double) rampa);
                short v = (short) (Math.sin(2 * Math.PI * f * n / MUESTRAS) * volumen * env * Short.MAX_VALUE);
                b[i++] = (byte) v;
                b[i++] = (byte) (v >> 8);
            }
            i += porSilencio * 2; // silencio: ceros
        }
        return b;
    }

    private static Clip clip(byte[] datos) throws Exception {
        AudioFormat formato = new AudioFormat(MUESTRAS, 16, 1, true, false);
        Clip c = AudioSystem.getClip();
        c.open(formato, datos, 0, datos.length);
        return c;
    }
}
