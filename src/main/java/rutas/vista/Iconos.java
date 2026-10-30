package rutas.vista;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.Icon;

/**
 * Los iconos de la carpeta imagenes/iconos (lupa, codigo, carrito, notas, cajon).
 * Se pintan del color que se pida (solo se usa la forma del dibujo) y al tamaño
 * que se pida, sin verse borrosos en pantallas con zoom de Windows.
 */
public final class Iconos {

    private static final Map<String, BufferedImage> cache = new HashMap<>();

    private Iconos() {
    }

    /** Icono de "tam" x "tam" px pintado de un color. Si el archivo no esta, devuelve un icono vacio. */
    public static Icon de(String nombre, int tam, Color color) {
        BufferedImage img = cargar(nombre, color);
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g0, int x, int y) {
                if (img == null) {
                    return;
                }
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(img, x, y, tam, tam, null);
                g.dispose();
            }

            @Override
            public int getIconWidth() {
                return tam;
            }

            @Override
            public int getIconHeight() {
                return tam;
            }
        };
    }

    private static synchronized BufferedImage cargar(String nombre, Color color) {
        String clave = nombre + "#" + color.getRGB();
        if (cache.containsKey(clave)) {
            return cache.get(clave);
        }
        BufferedImage pintada = null;
        try (InputStream in = Iconos.class.getResourceAsStream("/imagenes/iconos/" + nombre + ".png")) {
            if (in != null) {
                BufferedImage original = ImageIO.read(in);
                pintada = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_ARGB);
                int rgb = color.getRGB() & 0xFFFFFF;
                for (int y = 0; y < original.getHeight(); y++) {
                    for (int x = 0; x < original.getWidth(); x++) {
                        int alfa = original.getRGB(x, y) >>> 24; // se queda con la forma, cambia el color
                        pintada.setRGB(x, y, (alfa << 24) | rgb);
                    }
                }
            }
        } catch (Exception e) {
            rutas.logica.Ubicaciones.registrarError(e);
        }
        cache.put(clave, pintada);
        return pintada;
    }
}
