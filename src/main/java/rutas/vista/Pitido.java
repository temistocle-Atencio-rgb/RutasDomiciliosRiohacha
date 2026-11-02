package rutas.vista;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;

/**
 * Sonidos de caja, generados aqui mismo (sin archivos de audio):
 * - bien: el "bip" agudo y corto de un lector de supermercado (producto agregado);
 * - error: dos tonos graves (codigo que no existe o producto agotado).
 * Si el computador no tiene sonido, queda el pitido de Windows.
 */
final class Pitido {

    private static final float MUESTRAS = 44_100f;
    private static Clip bien;
    private static Clip error;
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
        int porTono = (int) (MUESTRAS * msTono / 1000);
        int porSilencio = (int) (MUESTRAS * msSilencio / 1000);
        int rampa = (int) (MUESTRAS * 0.004); // 4 ms de entrada y salida
        byte[] b = new byte[(porTono + porSilencio) * frecuencias.length * 2];
        int i = 0;
        for (int f : frecuencias) {
            for (int n = 0; n < porTono; n++) {
                double env = Math.min(1, Math.min(n, porTono - 1 - n) / (double) rampa);
                short v = (short) (Math.sin(2 * Math.PI * f * n / MUESTRAS) * 0.35 * env * Short.MAX_VALUE);
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
