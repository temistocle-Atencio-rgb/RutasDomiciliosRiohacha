package rutas.vista;

import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import rutas.logica.Tarifa;
import rutas.modelo.Producto;
import rutas.modelo.Unidad;

/**
 * Tarjeta de un producto en la caja, como las de PuveSoft: precio arriba, la foto
 * grande en el centro y el nombre abajo en una franja gris. Si hace falta, dice
 * cuanto queda debajo del precio. Clic, Enter o espacio lo agregan a la venta.
 */
class TarjetaProducto extends javax.swing.JPanel {

    static final int ANCHO = 148;   // minimo; la rejilla las estira para llenar la fila
    static final int ALTO = 218;
    private static final int CABEZA = 48; // precio y estado
    private static final int FRANJA = 46; // nombre en dos renglones

    private final Producto producto;
    private boolean encima;

    TarjetaProducto(Producto producto, Consumer<Producto> alElegir) {
        this.producto = producto;
        setOpaque(false);
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText(producto.getNombre() + " · " + precio(producto));
        getAccessibleContext().setAccessibleName(producto.getNombre() + ", " + precio(producto)
                + (estado(producto) == null ? "" : ", " + estado(producto)));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                requestFocusInWindow();
                alElegir.accept(producto);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                encima = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                encima = false;
                repaint();
            }
        });
        for (String tecla : new String[]{"ENTER", "SPACE"}) {
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(tecla), "elegir");
        }
        getActionMap().put("elegir", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                alElegir.accept(producto);
            }
        });
        addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                repaint();
                scrollRectToVisible(new java.awt.Rectangle(0, 0, getWidth(), getHeight()));
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                repaint();
            }
        });
    }

    Producto getProducto() {
        return producto;
    }

    static String precio(Producto p) {
        return Tarifa.pesos(p.getPrecioVenta()) + (p.getUnidad() == Unidad.UNIDAD ? "" : " / " + p.getUnidad().getCorto());
    }

    /** Texto del estado del inventario, o null si no hay nada que decir. */
    static String estado(Producto p) {
        if (!p.isManejaInventario()) {
            return null;
        }
        if (p.getStock() <= 0) {
            return "Sin existencias";
        }
        if (p.estaBajoMinimo()) {
            return "Quedan " + PanelProductos.cantidad(p.getStock()) + " " + p.getUnidad().getCorto();
        }
        return null;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(ANCHO, ALTO);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Tema.suavizar(g);
        int w = getWidth(), h = getHeight();
        int x = 4, y = 3, cw = w - 8, ch = h - 10;
        Tema.sombra(g, x, y, cw, ch);
        g.setColor(Tema.BLANCO);
        g.fillRoundRect(x, y, cw, ch, Tema.ARCO, Tema.ARCO);
        if (isFocusOwner()) {
            g.setColor(Tema.AZUL);
            g.setStroke(new BasicStroke(2.4f));
            g.drawRoundRect(x + 1, y + 1, cw - 2, ch - 2, Tema.ARCO, Tema.ARCO);
        } else if (encima) {
            g.setColor(Tema.AZUL);
            g.setStroke(new BasicStroke(1.3f));
            g.drawRoundRect(x, y, cw, ch, Tema.ARCO, Tema.ARCO);
        }

        int tx = x + 12, ancho = cw - 24;

        // arriba: precio en azul y, debajo, el estado como texto de color (sin pildoras)
        String est = estado(producto);
        g.setFont(Tema.subtitulo(15));
        g.setColor(Tema.AZUL);
        g.drawString(recortar(g.getFontMetrics(), precio(producto), ancho), tx, y + 25);
        if (est != null) {
            g.setFont(Tema.texto(12));
            g.setColor(producto.getStock() <= 0 ? Tema.ERROR_TEXTO : Tema.AVISO_TEXTO);
            g.drawString(est, tx, y + 42);
        }

        // centro: la foto grande (o el paquete dibujado)
        int fy = y + CABEZA, fh = ch - CABEZA - FRANJA - 8;
        int fx = x + 14, fw = cw - 28;
        Image img = Fotos.cargar(producto.getFoto(), fw, fh);
        if (img != null) {
            Fotos.dibujar(g, img, fx, fy, fw, fh);
        } else {
            Fotos.paquete(g, fx + fw / 2, fy + fh / 2, 44);
        }

        // abajo: franja gris con el nombre centrado, dos renglones como maximo
        int by = y + ch - FRANJA;
        java.awt.Shape antes = g.getClip();
        g.clip(new java.awt.geom.RoundRectangle2D.Float(x, y, cw, ch, Tema.ARCO, Tema.ARCO));
        g.setColor(Tema.GRIS_CLARO);
        g.fillRect(x, by, cw, FRANJA);
        g.setClip(antes);
        g.setFont(Tema.textoFuerte(12.5f));
        g.setColor(Tema.GRIS_OSCURO);
        FontMetrics fm = g.getFontMetrics();
        String[] lineas = dosLineas(fm, producto.getNombre(), ancho);
        int alto = fm.getHeight() - 2;
        int y1 = lineas[1] == null ? by + (FRANJA + fm.getAscent() - fm.getDescent()) / 2
                : by + (FRANJA - 2 * alto) / 2 + fm.getAscent();
        g.drawString(lineas[0], x + (cw - fm.stringWidth(lineas[0])) / 2, y1);
        if (lineas[1] != null) {
            g.drawString(lineas[1], x + (cw - fm.stringWidth(lineas[1])) / 2, y1 + alto);
        }
        g.dispose();
    }

    private static String[] dosLineas(FontMetrics fm, String texto, int ancho) {
        String[] r = new String[2];
        StringBuilder linea = new StringBuilder();
        String[] palabras = texto.split(" ");
        int i = 0;
        for (; i < palabras.length; i++) {
            String prueba = linea.length() == 0 ? palabras[i] : linea + " " + palabras[i];
            if (fm.stringWidth(prueba) > ancho && linea.length() > 0) {
                break;
            }
            linea = new StringBuilder(prueba);
        }
        r[0] = recortar(fm, linea.toString(), ancho);
        if (i < palabras.length) {
            StringBuilder resto = new StringBuilder();
            for (; i < palabras.length; i++) {
                resto.append(resto.length() == 0 ? "" : " ").append(palabras[i]);
            }
            r[1] = recortar(fm, resto.toString(), ancho);
        }
        return r;
    }

    private static String recortar(FontMetrics fm, String s, int ancho) {
        if (fm.stringWidth(s) <= ancho) {
            return s;
        }
        String r = s;
        while (r.length() > 1 && fm.stringWidth(r + "…") > ancho) {
            r = r.substring(0, r.length() - 1);
        }
        return r + "…";
    }
}
