package rutas.vista;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import rutas.modelo.Ruta;

/**
 * La campana de avisos de la barra de arriba: guarda lo que pasó hoy con las salidas
 * (aceptó, no puede, mensaje, terminó, se cerró) para que no se pierda si el aviso de
 * abajo se fue mientras se atendía a un cliente. El número naranja son los que no se han visto.
 * Al abrirla se dan por vistos; al tocar uno se va a esa salida. Cada día empieza vacía.
 */
public class Notificaciones {

    /** Un aviso guardado. */
    public static class Nota {
        public LocalDateTime hora = LocalDateTime.now();
        public int veces = 1; // el mismo aviso varias veces seguidas
        public final Aviso.Tipo tipo;
        public final String titulo;
        public final String texto;
        public final long salida; // id de la salida (0 = ninguna)
        public boolean vista;

        Nota(Aviso.Tipo tipo, String titulo, String texto, long salida) {
            this.tipo = tipo;
            this.titulo = titulo == null ? "" : titulo;
            this.texto = texto == null ? "" : texto;
            this.salida = salida;
        }
    }

    static final int MAXIMO = 60;

    private final List<Nota> notas = new ArrayList<>(); // la más nueva al final
    private final Campana campana = new Campana();
    private LongConsumer alTocar = id -> { };

    public Notificaciones() {
    }

    /** Qué hacer al tocar un aviso (ir a esa salida). */
    public void alTocar(LongConsumer accion) {
        alTocar = accion;
    }

    public JComponent getCampana() {
        return campana;
    }

    /**
     * Agrega el aviso. Si es igual al de hace menos de 10 minutos, no se repite: se suma a ese («×3») y devuelve
     * false (así no vuelve a salir en pantalla ni a sonar).
     */
    public boolean agregar(Aviso.Tipo tipo, String titulo, String texto, long salida) {
        limpiarViejas();
        for (int i = notas.size() - 1; i >= 0; i--) {
            Nota x = notas.get(i);
            if (x.titulo.equals(titulo == null ? "" : titulo) && x.salida == salida
                    && x.hora.isAfter(LocalDateTime.now().minusMinutes(10))) {
                x.veces++;
                x.hora = LocalDateTime.now();
                notas.remove(i);
                notas.add(x); // queda arriba, con la hora nueva
                campana.repaint();
                campana.actualizarTexto();
                return false;
            }
        }
        notas.add(new Nota(tipo, titulo, texto, salida));
        while (notas.size() > MAXIMO) {
            notas.remove(0);
        }
        campana.repaint();
        campana.actualizarTexto();
        return true;
    }

    public List<Nota> getNotas() {
        limpiarViejas();
        return notas;
    }

    public int sinVer() {
        limpiarViejas();
        int n = 0;
        for (Nota x : notas) {
            if (!x.vista) {
                n++;
            }
        }
        return n;
    }

    /** Solo las de hoy (lo de antes queda en el Historial de salidas). */
    private void limpiarViejas() {
        LocalDate hoy = LocalDate.now();
        notas.removeIf(x -> !x.hora.toLocalDate().equals(hoy));
    }

    static Color color(Aviso.Tipo t) {
        switch (t) {
            case ERROR:
                return Tema.ERROR_TEXTO;
            case AVISO:
                return Tema.NARANJA;
            case MENSAJE:
                return Tema.AZUL;
            default:
                return Tema.EXITO_TEXTO;
        }
    }

    // ------------------------------------------------------------------
    //  La lista que se abre al tocar la campana
    // ------------------------------------------------------------------

    void abrir() {
        List<Nota> l = new ArrayList<>(getNotas());
        JPopupMenu menu = new JPopupMenu();
        menu.setBorder(BorderFactory.createLineBorder(Tema.BORDE));
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Tema.BLANCO);
        JPanel cab = new JPanel(new BorderLayout());
        cab.setOpaque(false);
        cab.setBorder(BorderFactory.createEmptyBorder(12, 14, 8, 10));
        JLabel tit = new JLabel("Avisos de hoy");
        tit.setFont(Tema.subtitulo(15));
        tit.setForeground(Tema.GRIS_OSCURO);
        cab.add(tit, BorderLayout.WEST);
        if (!l.isEmpty()) {
            Tema.Boton limpiar = Tema.botonPlano("Limpiar");
            limpiar.setToolTipText("Quitar todos los avisos de la lista");
            limpiar.addActionListener(e -> {
                notas.clear();
                campana.repaint();
                campana.actualizarTexto();
                menu.setVisible(false);
            });
            cab.add(limpiar, BorderLayout.EAST);
        }
        p.add(cab, BorderLayout.NORTH);
        JPanel lista = new JPanel();
        lista.setOpaque(false);
        lista.setLayout(new BoxLayout(lista, BoxLayout.Y_AXIS));
        if (l.isEmpty()) {
            JLabel v = new JLabel("No hay avisos hoy.");
            v.setFont(Tema.texto(13));
            v.setForeground(Tema.GRIS_TEXTO);
            v.setBorder(BorderFactory.createEmptyBorder(6, 14, 16, 14));
            lista.add(v);
        }
        for (int i = l.size() - 1; i >= 0; i--) { // la más nueva arriba
            lista.add(fila(l.get(i), menu));
        }
        JScrollPane sc = new JScrollPane(lista);
        sc.setBorder(BorderFactory.createEmptyBorder());
        sc.getViewport().setBackground(Tema.BLANCO);
        sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sc.getVerticalScrollBar().setUnitIncrement(16);
        int alto = Math.min(380, lista.getPreferredSize().height + 2);
        sc.setPreferredSize(new Dimension(360, alto));
        p.add(sc, BorderLayout.CENTER);
        menu.add(p);
        // al abrir se dan por vistos (los que eran nuevos se ven resaltados esta vez)
        for (Nota x : notas) {
            x.vista = true;
        }
        campana.repaint();
        campana.actualizarTexto();
        menu.show(campana, campana.getWidth() - 362, campana.getHeight() + 6);
    }

    private JComponent fila(Nota n, JPopupMenu menu) {
        boolean nueva = !n.vista;
        JPanel f = new JPanel(new BorderLayout(10, 0));
        f.setBackground(nueva ? Tema.AZUL_SUAVE : Tema.BLANCO);
        f.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Tema.BORDE),
                BorderFactory.createEmptyBorder(9, 14, 9, 14)));
        JLabel punto = new JLabel(Tema.punto(color(n.tipo)));
        punto.setVerticalAlignment(JLabel.TOP);
        punto.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
        f.add(punto, BorderLayout.WEST);
        JPanel textos = new JPanel();
        textos.setOpaque(false);
        textos.setLayout(new BoxLayout(textos, BoxLayout.Y_AXIS));
        JPanel arriba = new JPanel(new BorderLayout(8, 0));
        arriba.setOpaque(false);
        arriba.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        TextoEmoji t = new TextoEmoji(n.veces > 1 ? n.titulo + " (×" + n.veces + ")" : n.titulo, Tema.textoFuerte(13.5f), Tema.GRIS_OSCURO, 230);
        arriba.add(t, BorderLayout.CENTER);
        JLabel h = new JLabel(Ruta.FORMATO_HORA.format(n.hora));
        h.setFont(Tema.texto(12));
        h.setForeground(Tema.GRIS_TEXTO);
        h.setVerticalAlignment(JLabel.TOP);
        arriba.add(h, BorderLayout.EAST);
        textos.add(arriba);
        if (!n.texto.isEmpty()) {
            Color c = n.tipo == Aviso.Tipo.ERROR ? Tema.ERROR_TEXTO : n.tipo == Aviso.Tipo.AVISO ? Tema.AVISO_TEXTO
                    : Tema.GRIS_TEXTO;
            textos.add(new TextoEmoji(n.texto, Tema.texto(13), c, 290)); // un mensaje puede traer emojis
        }
        f.add(textos, BorderLayout.CENTER);
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, f.getPreferredSize().height));
        f.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        if (n.salida != 0) {
            f.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            f.setToolTipText("Ir a esta salida");
            f.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    menu.setVisible(false);
                    alTocar.accept(n.salida);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    f.setBackground(Tema.mezclar(Tema.AZUL_SUAVE, Color.WHITE, 0.3));
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    f.setBackground(nueva ? Tema.AZUL_SUAVE : Tema.BLANCO);
                }
            });
        }
        return f;
    }

    // ------------------------------------------------------------------
    //  La campana (blanca sobre el azul de la barra) con el número naranja
    // ------------------------------------------------------------------

    private class Campana extends JPanel { // JPanel: tiene nombre para el lector de pantalla
        private boolean encima;

        Campana() {
            setOpaque(false);
            setPreferredSize(new Dimension(40, 34));
            setMinimumSize(getPreferredSize());
            setMaximumSize(getPreferredSize());
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            actualizarTexto();
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    abrir();
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
            getInputMap().put(javax.swing.KeyStroke.getKeyStroke("SPACE"), "abrir");
            getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "abrir");
            getActionMap().put("abrir", new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    abrir();
                }
            });
        }

        void actualizarTexto() {
            int n = sinVer();
            String t = n == 0 ? "Avisos de hoy" : n == 1 ? "1 aviso sin ver" : n + " avisos sin ver";
            setToolTipText(t);
            getAccessibleContext().setAccessibleName(t);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            if (encima || isFocusOwner()) {
                g.setColor(new Color(255, 255, 255, 40));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), Tema.ARCO, Tema.ARCO);
            }
            // campana: cuerpo, borde de abajo y badajo
            float cx = getWidth() / 2f, top = getHeight() / 2f - 9;
            Path2D.Float b = new Path2D.Float();
            b.moveTo(cx - 7, top + 13);
            b.curveTo(cx - 7, top + 4, cx - 5, top + 1.5f, cx, top + 1.5f);
            b.curveTo(cx + 5, top + 1.5f, cx + 7, top + 4, cx + 7, top + 13);
            b.lineTo(cx + 9, top + 15.5f);
            b.lineTo(cx - 9, top + 15.5f);
            b.closePath();
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(b);
            g.drawLine(Math.round(cx), Math.round(top - 0.5f), Math.round(cx), Math.round(top + 1.5f));
            g.drawArc(Math.round(cx - 2.5f), Math.round(top + 15), 5, 4, 180, 180);
            int n = sinVer();
            if (n > 0) {
                String s = n > 9 ? "9+" : String.valueOf(n);
                g.setFont(Tema.textoFuerte(11));
                int w = Math.max(17, g.getFontMetrics().stringWidth(s) + 8);
                int x = Math.round(cx + 2), y = Math.round(top - 4);
                g.setColor(Tema.NARANJA);
                g.fillRoundRect(x, y, w, 17, Tema.ARCO, Tema.ARCO);
                g.setColor(Tema.AZUL_PROFUNDO);
                g.setStroke(new BasicStroke(1.5f));
                g.drawRoundRect(x, y, w, 17, Tema.ARCO, Tema.ARCO);
                g.setColor(Tema.GRIS_OSCURO);
                g.drawString(s, x + (w - g.getFontMetrics().stringWidth(s)) / 2f, y + 13);
            }
            g.dispose();
        }
    }
}
