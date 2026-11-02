package rutas.vista;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Pasa el logo de la tienda a blanco y negro, como lo imprime una impresora termica
 * (solo puntos negros, sin grises). Usa difusion de error (Floyd-Steinberg) para que
 * los degradados salgan como puntitos y no como manchas.
 */
final class LogoTermico {

    private static final Map<String, BufferedImage> cache = new HashMap<>();

    private LogoTermico() {
    }

    /**
     * Logo listo para imprimir con un ancho maximo en puntos de la impresora.
     * Devuelve null si no hay logo o no se pudo leer.
     */
    static synchronized BufferedImage preparar(String ruta, int anchoPuntos, int umbral, boolean tramado) {
        if (ruta == null || ruta.isBlank() || anchoPuntos < 8) {
            return null;
        }
        File f = new File(ruta);
        String clave = ruta + "|" + f.lastModified() + "|" + anchoPuntos + "|" + umbral + "|" + tramado;
        if (cache.containsKey(clave)) {
            return cache.get(clave);
        }
        BufferedImage r = null;
        try {
            BufferedImage original = ImageIO.read(f);
            if (original != null) {
                r = convertir(original, anchoPuntos, umbral, tramado);
            }
        } catch (Exception e) {
            rutas.logica.Ubicaciones.registrarError(e);
        }
        cache.put(clave, r);
        return r;
    }

    /** Escala (sin agrandar) y convierte a puntos negros y blancos. */
    static BufferedImage convertir(BufferedImage original, int anchoPuntos, int umbral, boolean tramado) {
        int w = Math.min(anchoPuntos, original.getWidth());
        int h = Math.max(1, (int) Math.round(original.getHeight() * (w / (double) original.getWidth())));
        int maxAlto = anchoPuntos / 2; // un logo muy alto gasta papel
        if (h > maxAlto) {
            w = Math.max(1, (int) Math.round(w * (maxAlto / (double) h)));
            h = maxAlto;
        }
        BufferedImage gris = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = gris.createGraphics();
        g.setColor(Color.WHITE); // lo transparente sale blanco (papel)
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(original.getScaledInstance(w, h, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
        g.dispose();
        float[] lum = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = gris.getRGB(x, y);
                lum[y * w + x] = 0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255);
            }
        }
        BufferedImage bn = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float viejo = lum[i];
                float nuevo = viejo < umbral ? 0 : 255;
                bn.setRGB(x, y, nuevo == 0 ? 0xFF000000 : 0xFFFFFFFF);
                if (!tramado) {
                    continue; // corte limpio: cada punto es negro o blanco segun el tono
                }
                float err = viejo - nuevo;
                if (x + 1 < w) {
                    lum[i + 1] += err * 7 / 16;
                }
                if (y + 1 < h) {
                    if (x > 0) {
                        lum[i + w - 1] += err * 3 / 16;
                    }
                    lum[i + w] += err * 5 / 16;
                    if (x + 1 < w) {
                        lum[i + w + 1] += err * 1 / 16;
                    }
                }
            }
        }
        return bn;
    }
}
