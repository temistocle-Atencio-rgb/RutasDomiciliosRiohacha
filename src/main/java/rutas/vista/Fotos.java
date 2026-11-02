package rutas.vista;

import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/** Carga las fotos de productos y categorias ya reducidas, y las guarda para no leerlas cada vez. */
final class Fotos {

    private static final Map<String, Image> CACHE = new HashMap<>();
    private static final Image NADA = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    private Fotos() {
    }

    /** Foto que cabe en ancho x alto (sin deformarla), o null si no hay o no se pudo leer. */
    static Image cargar(String ruta, int ancho, int alto) {
        if (ruta == null || ruta.isBlank()) {
            return null;
        }
        String clave = ruta + "@" + ancho + "x" + alto;
        Image img = CACHE.get(clave);
        if (img == null) {
            img = leer(ruta, ancho, alto);
            CACHE.put(clave, img);
        }
        return img == NADA ? null : img;
    }

    /** Olvida una foto (por ejemplo, si el producto cambio de foto). */
    static void olvidar(String ruta) {
        CACHE.keySet().removeIf(k -> k.startsWith(ruta + "@"));
    }

    private static Image leer(String ruta, int ancho, int alto) {
        try {
            File f = new File(ruta);
            if (!f.isFile()) {
                return NADA;
            }
            BufferedImage org = ImageIO.read(f);
            if (org == null) {
                return NADA;
            }
            // las fotos se guardan al doble para que se vean nitidas con la escala de Windows al 125 o 150 %
            double e = Math.min(ancho * 2.0 / org.getWidth(), alto * 2.0 / org.getHeight());
            e = Math.min(e, 1.0);
            int w = Math.max(1, (int) Math.round(org.getWidth() * e));
            int h = Math.max(1, (int) Math.round(org.getHeight() * e));
            BufferedImage r = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = r.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(org, 0, 0, w, h, null);
            g.dispose();
            return r;
        } catch (Exception | OutOfMemoryError e) {
            return NADA;
        }
    }

    /** Dibuja la imagen centrada dentro del cuadro, sin deformarla. */
    static void dibujar(java.awt.Graphics2D g, Image img, int x, int y, int w, int h) {
        int iw = img.getWidth(null), ih = img.getHeight(null);
        double e = Math.min((double) w / iw, (double) h / ih);
        int dw = (int) Math.round(iw * e), dh = (int) Math.round(ih * e);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(img, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, null);
    }

    /** Icono de paquete en linea gris (cuando el producto no tiene foto). */
    /** Producto sin foto: la caja de la carpeta de iconos del diseño, suave para que no compita con las fotos. */
    static void paquete(java.awt.Graphics2D g, int cx, int cy, int t) {
        java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
        g2.setComposite(java.awt.AlphaComposite.SrcOver.derive(0.45f));
        Iconos.dibujarColor(g2, "/imagenes/caja.png", cx - t / 2, cy - t / 2, t);
        g2.dispose();
    }
}
