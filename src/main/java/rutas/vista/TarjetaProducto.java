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
 * Tarjeta de un producto en la caja: foto, nombre, precio y (si hace falta)
 * cuanto queda. Clic, Enter o espacio lo agregan a la venta.
 */
class TarjetaProducto extends javax.swing.JPanel {

    static final int ANCHO = 168;
    static final int ALTO = 208;
    private static final int FOTO = 112;

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

        // foto (o el paquete dibujado)
        int fx = x + 12, fy = y + 12, fw = cw - 24, fh = FOTO - 12;
        Image img = Fotos.cargar(producto.getFoto(), fw, fh);
        if (img != null) {
            Fotos.dibujar(g, img, fx, fy, fw, fh);
        } else {
            g.setColor(Tema.GRIS_CLARO);
            g.fillRoundRect(fx, fy, fw, fh, Tema.ARCO, Tema.ARCO);
            Fotos.paquete(g, fx + fw / 2, fy + fh / 2, 40);
        }

        // nombre en dos lineas como maximo
        int tx = x + 12, ancho = cw - 24;
        int ty = y + FOTO + 18;
        g.setFont(Tema.textoFuerte(13.5f));
        g.setColor(Tema.GRIS_OSCURO);
        FontMetrics fm = g.getFontMetrics();
        String[] lineas = dosLineas(fm, producto.getNombre(), ancho);
        g.drawString(lineas[0], tx, ty);
        if (lineas[1] != null) {
            g.drawString(lineas[1], tx, ty + fm.getHeight() - 2);
        }

        // precio y estado abajo
        int base = y + ch - 12;
        String est = estado(producto);
        g.setFont(Tema.subtitulo(15.5f));
        g.setColor(Tema.AZUL);
        g.drawString(precio(producto), tx, est == null ? base : base - 17);
        if (est != null) {
            g.setFont(Tema.texto(12.5f));
            g.setColor(producto.getStock() <= 0 ? Tema.ERROR_TEXTO : Tema.AVISO_TEXTO);
            g.drawString(est, tx, base);
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
