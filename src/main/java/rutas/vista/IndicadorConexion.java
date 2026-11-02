package rutas.vista;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.time.LocalDateTime;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;

/**
 * El ícono de arriba que dice si DomiRuta está hablando con internet:
 * en línea (señal), sin internet (señal tachada) o la base de Supabase no responde (base con aviso).
 * Ícono de línea de 2 px y puntas redondas, blanco sobre la barra azul (manual de marca).
 */
final class IndicadorConexion extends JButton {

    /** Lo que se sabe de la conexión. OCULTO = Supabase no está configurado (no hay nada que mostrar). */
    enum Estado { OCULTO, REVISANDO, EN_LINEA, SIN_INTERNET, SIN_BASE }

    private Estado estado = Estado.OCULTO;
    private LocalDateTime desde;
    private String detalle = "";

    IndicadorConexion() {
        setContentAreaFilled(false);
        setFocusPainted(true);
        setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        setFont(Tema.textoFuerte(13.5f));
        setForeground(Color.WHITE);
        setIconTextGap(6);
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        addActionListener(e -> {
            java.awt.Window w = javax.swing.SwingUtilities.getWindowAncestor(this);
            javax.swing.JComponent donde = w instanceof javax.swing.RootPaneContainer
                    ? (javax.swing.JComponent) ((javax.swing.RootPaneContainer) w).getContentPane() : this;
            Aviso.mostrar(donde, estado == Estado.EN_LINEA ? Aviso.Tipo.BIEN : Aviso.Tipo.AVISO, titulo(), explicacion());
        });
        pintar();
    }

    void setEstado(Estado nuevo, String detalle) {
        if (nuevo != estado) {
            desde = LocalDateTime.now();
        }
        estado = nuevo;
        this.detalle = detalle == null ? "" : detalle;
        pintar();
    }

    Estado getEstado() {
        return estado;
    }

    private void pintar() {
        setVisible(estado != Estado.OCULTO);
        setIcon(new Dibujo(estado));
        // en línea basta el ícono; cuando hay un problema también va la palabra (se nota de una)
        setText(estado == Estado.SIN_INTERNET ? "Sin internet" : estado == Estado.SIN_BASE ? "Supabase no responde" : "");
        setToolTipText(titulo() + ". Clic para ver más");
        getAccessibleContext().setAccessibleName(titulo());
    }

    String titulo() {
        switch (estado) {
            case EN_LINEA: return "En línea";
            case SIN_INTERNET: return "Sin internet";
            case SIN_BASE: return "Supabase no responde";
            case REVISANDO: return "Revisando la conexión";
            default: return "";
        }
    }

    private String explicacion() {
        String hora = desde == null ? "" : rutas.modelo.Ruta.FORMATO_HORA.format(desde);
        switch (estado) {
            case EN_LINEA:
                return "Los repartidores y los clientes ven lo último.";
            case SIN_INTERNET:
                return "Este computador no tiene internet desde las " + hora + ". La caja sigue funcionando; lo que"
                        + " marque se manda solo cuando vuelva.";
            case SIN_BASE:
                return "Hay internet, pero Supabase no responde desde las " + hora + ". "
                        + (detalle.isEmpty() ? "" : detalle + ". ") + "Revise Configuración › Conexión.";
            default:
                return "Revisando…";
        }
    }

    /** Los tres dibujos (18 × 18). */
    private static final class Dibujo implements Icon {
        private final Estado estado;

        Dibujo(Estado estado) {
            this.estado = estado;
        }

        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 18;
        }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            g.translate(x, y);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (estado == Estado.SIN_BASE) {
                // base de datos (cilindro) con un signo de admiración
                g.draw(new Ellipse2D.Double(2, 2, 11, 4));
                g.draw(new Line2D.Double(2, 4, 2, 14));
                g.draw(new Line2D.Double(13, 4, 13, 8));
                g.draw(new Arc2D.Double(2, 12, 11, 4, 180, 180, Arc2D.OPEN));
                g.draw(new Arc2D.Double(2, 7, 11, 4, 180, 180, Arc2D.OPEN));
                g.draw(new Line2D.Double(15.5, 9, 15.5, 13));
                g.fill(new Ellipse2D.Double(14.3, 15, 2.4, 2.4));
            } else {
                // señal: tres arcos y un punto
                float alfa = estado == Estado.REVISANDO ? 0.55f : 1f;
                g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alfa));
                g.draw(new Arc2D.Double(1, 3, 16, 16, 45, 90, Arc2D.OPEN));
                g.draw(new Arc2D.Double(4, 6.5, 10, 10, 45, 90, Arc2D.OPEN));
                g.fill(new Ellipse2D.Double(7.6, 12.6, 2.8, 2.8));
                if (estado == Estado.SIN_INTERNET) {
                    g.draw(new Line2D.Double(2.5, 2.5, 15.5, 15.5)); // tachada
                }
            }
            g.dispose();
        }
    }
}
