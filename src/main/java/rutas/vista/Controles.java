package rutas.vista;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;

/** Controles del formulario de producto que Swing no trae: interruptor, fecha con calendario, etiquetas y area de texto. */
final class Controles {

    private Controles() {
    }

    // ------------------------------------------------------------------
    //  Interruptor (si / no)
    // ------------------------------------------------------------------

    /** Interruptor redondo con su pregunta al lado. Encendido en azul. */
    static class Interruptor extends JToggleButton {
        Interruptor(String texto) {
            super(texto);
            setFont(Tema.textoFuerte(13));
            setForeground(Tema.GRIS_OSCURO);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setHorizontalAlignment(LEFT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(6, 48, 6, 4));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            addItemListener(e -> repaint());
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) {
                    repaint();
                }

                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    repaint();
                }
            });
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int w = 38, h = 22, x = 2, y = (getHeight() - h) / 2;
            if (isFocusOwner()) {
                g.setColor(Tema.AZUL);
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(x - 2, y - 2, w + 3, h + 3, h + 4, h + 4);
            }
            g.setColor(isSelected() ? Tema.AZUL : Tema.BORDE_CONTROL);
            g.fillRoundRect(x, y, w, h, h, h);
            g.setColor(Tema.BLANCO);
            int d = h - 6;
            g.fillOval(isSelected() ? x + w - d - 3 : x + 3, y + 3, d, d);
            g.dispose();
            super.paintComponent(g0);
        }
    }

    // ------------------------------------------------------------------
    //  Fecha con calendario
    // ------------------------------------------------------------------

    static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

    /** Campo dd/mm/aaaa con un boton de calendario al lado. */
    static class CampoFecha extends JPanel {
        private final JTextField campo = Tema.campo("dd/mm/aaaa");
        private final JButton boton = new JButton();
        private Runnable alCambiar;

        CampoFecha(String nombre) {
            super(new BorderLayout());
            setOpaque(false);
            campo.getAccessibleContext().setAccessibleName(nombre + " (dd/mm/aaaa)");
            campo.setToolTipText("Escriba la fecha (dd/mm/aaaa) o escójala en el calendario");
            add(campo, BorderLayout.CENTER);
            boton.setContentAreaFilled(false);
            boton.setBorderPainted(false);
            boton.setFocusPainted(true);
            boton.setOpaque(false);
            boton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            boton.setPreferredSize(new Dimension(30, 18));
            Tema.nombreAccesible(boton, "Abrir el calendario");
            boton.setIcon(Iconos.de("calendario", 16, Tema.GRIS_OSCURO));
            // el boton va dentro del borde del campo, a la derecha
            campo.setLayout(new BorderLayout());
            campo.add(boton, BorderLayout.EAST);
            boton.addActionListener(e -> abrirCalendario());
            campo.getDocument().addDocumentListener(PanelProductos.alCambiar(() -> {
                if (alCambiar != null) {
                    alCambiar.run();
                }
            }));
        }

        void setAlCambiar(Runnable r) {
            alCambiar = r;
        }

        JTextField getCampo() {
            return campo;
        }

        /** La fecha escrita; null si esta vacia. Lanza IllegalArgumentException si esta mal escrita. */
        LocalDate getFecha() {
            String t = campo.getText().trim();
            if (t.isEmpty()) {
                return null;
            }
            try {
                return LocalDate.parse(t, FORMATO);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("La fecha «" + t + "» no existe. Escríbala así: 31/12/2026");
            }
        }

        /** Como getFecha, pero devuelve null si esta mal escrita (para avisos mientras se escribe). */
        LocalDate fechaSiEsValida() {
            try {
                return getFecha();
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        void setFecha(LocalDate f) {
            campo.setText(f == null ? "" : f.format(FORMATO));
        }

        private void abrirCalendario() {
            LocalDate inicial = fechaSiEsValida();
            JPopupMenu menu = new JPopupMenu();
            menu.setBorder(new Tema.BordeRedondo(Tema.BORDE_CONTROL, Tema.RADIO));
            Calendario cal = new Calendario(inicial == null ? LocalDate.now() : inicial, inicial, f -> {
                setFecha(f);
                menu.setVisible(false);
                campo.requestFocusInWindow();
            });
            menu.add(cal);
            menu.show(this, Math.max(0, getWidth() - cal.getPreferredSize().width - 4), getHeight());
        }
    }

    /** Mes con sus dias para escoger una fecha. */
    static class Calendario extends JPanel {
        private static final Locale ES = new Locale("es", "CO");
        private YearMonth mes;
        private final LocalDate elegida;
        private final java.util.function.Consumer<LocalDate> alElegir;
        private final JLabel titulo = new JLabel("", SwingConstants.CENTER);
        private final JPanel dias = new JPanel(new GridLayout(0, 7, 2, 2));

        Calendario(LocalDate mostrar, LocalDate elegida, java.util.function.Consumer<LocalDate> alElegir) {
            super(new BorderLayout(0, 6));
            this.mes = YearMonth.from(mostrar);
            this.elegida = elegida;
            this.alElegir = alElegir;
            setBackground(Tema.BLANCO);
            setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            JPanel arriba = new JPanel(new BorderLayout());
            arriba.setOpaque(false);
            JButton antes = flecha("‹", "Mes anterior");
            JButton despues = flecha("›", "Mes siguiente");
            antes.addActionListener(e -> {
                mes = mes.minusMonths(1);
                llenar();
            });
            despues.addActionListener(e -> {
                mes = mes.plusMonths(1);
                llenar();
            });
            titulo.setFont(Tema.subtitulo(14));
            titulo.setForeground(Tema.AZUL_PROFUNDO);
            arriba.add(antes, BorderLayout.WEST);
            arriba.add(titulo, BorderLayout.CENTER);
            arriba.add(despues, BorderLayout.EAST);
            add(arriba, BorderLayout.NORTH);
            dias.setOpaque(false);
            add(dias, BorderLayout.CENTER);
            JButton hoy = new Tema.Boton("Hoy", Tema.BLANCO, Tema.AZUL, null);
            hoy.setFont(Tema.textoFuerte(12.5f));
            hoy.addActionListener(e -> alElegir.accept(LocalDate.now()));
            JPanel abajo = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
            abajo.setOpaque(false);
            abajo.add(hoy);
            add(abajo, BorderLayout.SOUTH);
            llenar();
        }

        private static JButton flecha(String t, String nombre) {
            JButton b = new Tema.Boton(t, Tema.BLANCO, Tema.AZUL, null);
            b.setFont(Tema.subtitulo(18));
            b.setBorder(BorderFactory.createEmptyBorder(0, 10, 2, 10));
            Tema.nombreAccesible(b, nombre);
            return b;
        }

        private void llenar() {
            String m = mes.getMonth().getDisplayName(java.time.format.TextStyle.FULL, ES);
            titulo.setText(Character.toUpperCase(m.charAt(0)) + m.substring(1) + " " + mes.getYear());
            dias.removeAll();
            for (String d : new String[]{"Lu", "Ma", "Mi", "Ju", "Vi", "Sá", "Do"}) {
                JLabel l = new JLabel(d, SwingConstants.CENTER);
                l.setFont(Tema.textoFuerte(11.5f));
                l.setForeground(Tema.GRIS_TEXTO);
                dias.add(l);
            }
            int vacios = mes.atDay(1).getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
            for (int i = 0; i < vacios; i++) {
                dias.add(new JLabel());
            }
            LocalDate hoy = LocalDate.now();
            for (int d = 1; d <= mes.lengthOfMonth(); d++) {
                LocalDate f = mes.atDay(d);
                boolean esElegida = f.equals(elegida);
                Tema.Boton b = new Tema.Boton(String.valueOf(d), esElegida ? Tema.AZUL : Tema.BLANCO,
                        esElegida ? Tema.BLANCO : f.equals(hoy) ? Tema.AZUL : Tema.GRIS_OSCURO, f.equals(hoy) && !esElegida
                        ? Tema.AZUL : null);
                b.setFont(Tema.texto(12.5f));
                b.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
                b.setPreferredSize(new Dimension(34, 30));
                b.getAccessibleContext().setAccessibleName(f.format(FORMATO));
                b.addActionListener(e -> alElegir.accept(f));
                dias.add(b);
            }
            dias.revalidate();
            dias.repaint();
            java.awt.Window w = javax.swing.SwingUtilities.getWindowAncestor(this);
            JPopupMenu menu = (JPopupMenu) javax.swing.SwingUtilities.getAncestorOfClass(JPopupMenu.class, this);
            if (menu != null) {
                menu.pack();
            } else if (w != null) {
                w.pack();
            }
        }
    }

    // ------------------------------------------------------------------
    //  Varias opciones como etiquetas (impuestos)
    // ------------------------------------------------------------------

    /** Muestra lo escogido como etiquetas azules con su x; al hacer clic se abre la lista para marcar. */
    static class Etiquetas extends JPanel {
        private final String[] opciones;
        private final List<String> elegidas = new ArrayList<>();
        private final JPanel fichas = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        private final JLabel vacio = new JLabel();
        private Runnable alCambiar;

        Etiquetas(String nombre, String vacioTexto, String... opciones) {
            super(new BorderLayout());
            this.opciones = opciones;
            setOpaque(false);
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            getAccessibleContext().setAccessibleName(nombre);
            setBorder(BorderFactory.createCompoundBorder(new Tema.BordeRedondo(Tema.BORDE_CONTROL, Tema.RADIO),
                    BorderFactory.createEmptyBorder(3, 6, 3, 34)));
            fichas.setOpaque(false);
            vacio.setText(vacioTexto);
            vacio.setFont(Tema.texto(13));
            vacio.setForeground(Tema.AYUDA);
            add(fichas, BorderLayout.CENTER);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    abrirLista();
                }
            });
            getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke("SPACE"), "abrir");
            getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke("DOWN"), "abrir");
            getActionMap().put("abrir", new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    abrirLista();
                }
            });
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) {
                    repaint();
                }

                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    repaint();
                }
            });
            pintar();
        }

        void setAlCambiar(Runnable r) {
            alCambiar = r;
        }

        List<String> getElegidas() {
            return new ArrayList<>(elegidas);
        }

        void setElegidas(List<String> lista) {
            elegidas.clear();
            for (String o : opciones) { // en el orden de la lista
                if (lista.contains(o)) {
                    elegidas.add(o);
                }
            }
            for (String o : lista) { // y lo que no este en la lista (de una version vieja) tambien se conserva
                if (!elegidas.contains(o)) {
                    elegidas.add(o);
                }
            }
            pintar();
        }

        private void abrirLista() {
            JPopupMenu menu = new JPopupMenu();
            for (String o : opciones) {
                JCheckBoxMenuItem item = new JCheckBoxMenuItem(o, elegidas.contains(o));
                item.setFont(Tema.texto(14));
                item.addActionListener(e -> {
                    if (item.isSelected()) {
                        elegidas.add(o);
                    } else {
                        elegidas.remove(o);
                    }
                    setElegidas(new ArrayList<>(elegidas));
                    if (alCambiar != null) {
                        alCambiar.run();
                    }
                });
                menu.add(item);
            }
            menu.setPreferredSize(new Dimension(getWidth(), menu.getPreferredSize().height));
            menu.show(this, 0, getHeight());
        }

        private void pintar() {
            fichas.removeAll();
            if (elegidas.isEmpty()) {
                fichas.add(vacio);
            }
            for (String o : new ArrayList<>(elegidas)) {
                fichas.add(new Ficha(o, () -> {
                    elegidas.remove(o);
                    pintar();
                    if (alCambiar != null) {
                        alCambiar.run();
                    }
                }));
            }
            fichas.revalidate();
            fichas.repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(d.width, Math.max(32, d.height));
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            g.setColor(Tema.BLANCO);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tema.ARCO, Tema.ARCO);
            if (isFocusOwner()) {
                g.setColor(Tema.AZUL);
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, Tema.ARCO, Tema.ARCO);
            }
            // flecha hacia abajo
            int cx = getWidth() - 18, cy = getHeight() / 2;
            g.setColor(Tema.GRIS_TEXTO);
            java.awt.geom.Path2D p = new java.awt.geom.Path2D.Double();
            p.moveTo(cx - 6, cy - 3);
            p.lineTo(cx + 6, cy - 3);
            p.lineTo(cx, cy + 4);
            p.closePath();
            g.fill(p);
            g.dispose();
        }

        /** Etiqueta azul con el texto blanco y una x para quitarla. */
        private static class Ficha extends JLabel {
            Ficha(String texto, Runnable quitar) {
                super(texto + "   ×");
                setFont(Tema.textoFuerte(12));
                setForeground(Tema.BLANCO);
                setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
                setToolTipText("Quitar " + texto);
                getAccessibleContext().setAccessibleName(texto);
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent e) {
                        FontMetrics fm = getFontMetrics(getFont());
                        if (e.getX() >= getWidth() - 10 - fm.stringWidth("×") - 6) {
                            quitar.run();
                            e.consume();
                        }
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Tema.suavizar(g);
                g.setColor(Tema.AZUL);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), Tema.ARCO_PEQUENO, Tema.ARCO_PEQUENO);
                g.dispose();
                super.paintComponent(g0);
            }
        }
    }

    // ------------------------------------------------------------------
    //  Area de texto con borde y ayuda
    // ------------------------------------------------------------------

    /** Texto de varias lineas con el mismo borde de los campos y la frase de ayuda cuando esta vacio. */
    static class AreaTexto extends JScrollPane {
        private final JTextArea area;
        private final int filas;

        AreaTexto(String ayuda, int filas) {
            this.filas = filas;
            area = new JTextArea() {
                @Override
                protected void paintComponent(Graphics g0) {
                    super.paintComponent(g0);
                    if (getText().isEmpty() && ayuda != null) {
                        Graphics2D g = (Graphics2D) g0.create();
                        Tema.suavizar(g);
                        g.setColor(Tema.AYUDA);
                        g.setFont(getFont());
                        Insets in = getInsets();
                        g.drawString(ayuda, in.left, in.top + g.getFontMetrics().getAscent());
                        g.dispose();
                    }
                }
            };
            area.setFont(Tema.texto(13));
            area.setForeground(Tema.GRIS_OSCURO);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setBorder(BorderFactory.createEmptyBorder(6, 9, 6, 9));
            area.getAccessibleContext().setAccessibleName(ayuda);
            // Tab pasa al siguiente campo en vez de escribir un tabulador
            area.setFocusTraversalKeys(java.awt.KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
            area.setFocusTraversalKeys(java.awt.KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
            setViewportView(area);
            setBorder(new Tema.BordeRedondo(Tema.BORDE_CONTROL, Tema.RADIO));
            getVerticalScrollBar().setUnitIncrement(14);
            setHorizontalScrollBarPolicy(HORIZONTAL_SCROLLBAR_NEVER);
            area.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) {
                    setBorder(new Tema.BordeRedondo(Tema.AZUL, Tema.RADIO));
                }

                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    setBorder(new Tema.BordeRedondo(Tema.BORDE_CONTROL, Tema.RADIO));
                }
            });
        }

        JTextArea getArea() {
            return area;
        }

        String getText() {
            return area.getText();
        }

        void setText(String t) {
            area.setText(t == null ? "" : t);
            area.setCaretPosition(0);
        }

        /** Alto fijo de sus renglones (si escriben mas, aparece la barra para bajar). */
        @Override
        public Dimension getPreferredSize() {
            Insets a = area.getInsets();
            Insets b = getInsets();
            int alto = filas * area.getFontMetrics(area.getFont()).getHeight() + a.top + a.bottom + b.top + b.bottom + 2;
            return new Dimension(120, alto);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** Campo de numero alineado a la derecha (como en la referencia). */
    static JTextField campoNumero(String ayuda) {
        JTextField t = Tema.campo(ayuda);
        t.setHorizontalAlignment(JTextField.RIGHT);
        return t;
    }

    /** Campo que se ve pero no se escribe (nombre de la bodega). */
    static JTextField campoFijo(String texto) {
        JTextField t = Tema.campo(null);
        t.setText(texto);
        t.setEditable(false);
        t.setFocusable(false);
        t.setBackground(new Color(0xE9ECF0));
        t.setForeground(Tema.GRIS_OSCURO);
        return t;
    }

    /** Boton pequeño con una x para quitar una fila. */
    static JButton botonQuitar(String nombre) {
        Tema.Boton b = new Tema.Boton("×", Tema.BLANCO, Tema.ERROR_TEXTO, null);
        b.setFont(Tema.subtitulo(18));
        b.setBorder(BorderFactory.createEmptyBorder(2, 10, 4, 10));
        Tema.nombreAccesible(b, nombre);
        return b;
    }

    static JComponent ancho(JComponent c) {
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }
}
