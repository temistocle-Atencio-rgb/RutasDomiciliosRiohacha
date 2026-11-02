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
 * Los iconos de la carpeta imagenes/iconos (sacados de la carpeta ICONO del diseño:
 * editar, basura, categorias, subir, lupa, carrito, codigo, notas, calendario, bodega, lote, foto).
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
                dibujarImagen((Graphics2D) g0, img, x, y, tam);
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

    /** Dibuja el icono "nombre" de un color en (x, y) con lado "tam" (para iconos pintados a mano). */
    public static void dibujar(Graphics2D g, String nombre, int x, int y, int tam, Color color) {
        dibujarImagen(g, cargar(nombre, color), x, y, tam);
    }

    private static final Map<String, java.awt.Image> reducidas = new HashMap<>();
    private static final Map<String, BufferedImage> aColor = new HashMap<>();

    /** Dibuja una imagen a color (sin pintarla de otro color), por ejemplo la caja de "sin foto". */
    public static void dibujarColor(Graphics2D g, String recurso, int x, int y, int tam) {
        BufferedImage img;
        synchronized (Iconos.class) {
            img = aColor.get(recurso);
            if (img == null && !aColor.containsKey(recurso)) {
                try (InputStream in = Iconos.class.getResourceAsStream(recurso)) {
                    img = in == null ? null : ImageIO.read(in);
                } catch (Exception e) {
                    rutas.logica.Ubicaciones.registrarError(e);
                }
                aColor.put(recurso, img);
            }
        }
        dibujarImagen(g, img, x, y, tam);
    }

    /**
     * La imagen original es de 128 px: se reduce una sola vez al tamaño real en pantalla
     * (con el zoom de Windows) promediando pixeles, asi las lineas finas no se ven serruchadas.
     */
    private static void dibujarImagen(Graphics2D g0, BufferedImage img, int x, int y, int tam) {
        if (img == null) {
            return;
        }
        double esc = g0.getTransform().getScaleX();
        int px = Math.max(1, (int) Math.round(tam * esc));
        java.awt.Image chica;
        synchronized (Iconos.class) {
            String clave = System.identityHashCode(img) + "@" + px;
            chica = reducidas.get(clave);
            if (chica == null) {
                BufferedImage r = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
                Graphics2D gr = r.createGraphics();
                gr.drawImage(img.getScaledInstance(px, px, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
                gr.dispose();
                chica = r;
                reducidas.put(clave, chica);
            }
        }
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(chica, x, y, tam, tam, null);
        g.dispose();
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
