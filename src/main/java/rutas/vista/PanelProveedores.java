package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import rutas.logica.Ubicaciones;
import rutas.modelo.Geo;
import rutas.modelo.Proveedor;

/**
 * Compras › Proveedores: la lista con buscador, «Nuevo proveedor», editar (lapiz) y desactivar o
 * reactivar (papelera / flecha), como la lista de productos.
 */
public class PanelProveedores extends JPanel {

    private static final String[] COLUMNAS = {"Nombre", "Documento", "Teléfono", "Vendedor", "Celular vendedor",
        "Día de visita", "Forma de pago", "Estado", "Acciones"};
    private static final int COL_ESTADO = 7, COL_ACCIONES = 8;
    private static final String[] MOSTRAR = {"Activos", "Inactivos", "Todos"};

    private final Aplicacion app;
    private final JTextField campoBuscar = Tema.campo("Buscar por nombre, documento o vendedor");
    private final JComboBox<String> comboMostrar = new JComboBox<>(MOSTRAR);
    private final Modelo modelo = new Modelo();
    private final JTable tabla = new JTable(modelo);
    private final JLabel lblTotal = new JLabel(" ");
    private List<Proveedor> filas = new ArrayList<>();

    public PanelProveedores(Aplicacion app) {
        super(new BorderLayout());
        this.app = app;
        setBackground(Tema.GRIS_CLARO);
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        putClientProperty(Capa.FOCO_AL_CERRAR, campoBuscar);

        JPanel cab = new JPanel(new BorderLayout());
        cab.setOpaque(false);
        cab.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        JLabel titulo = new JLabel("Proveedores");
        titulo.setFont(Tema.titulo(24));
        titulo.setForeground(Tema.GRIS_OSCURO);
        cab.add(titulo, BorderLayout.WEST);
        add(cab, BorderLayout.NORTH);

        Tema.Tarjeta t = new Tema.Tarjeta();
        t.setLayout(new BorderLayout(0, 12));
        JPanel barra = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        barra.setOpaque(false);
        campoBuscar.setPreferredSize(new Dimension(320, 40));
        campoBuscar.getAccessibleContext().setAccessibleName("Buscar proveedor");
        campoBuscar.getDocument().addDocumentListener(PanelProductos.alCambiar(this::refrescar));
        Tema.estiloCombo(comboMostrar, String::valueOf);
        comboMostrar.setPreferredSize(new Dimension(150, 40));
        comboMostrar.getAccessibleContext().setAccessibleName("Mostrar");
        comboMostrar.addActionListener(e -> refrescar());
        barra.add(campoBuscar);
        barra.add(comboMostrar);
        JPanel arriba = new JPanel(new BorderLayout());
        arriba.setOpaque(false);
        arriba.add(barra, BorderLayout.WEST);
        Tema.Boton nuevo = Tema.botonAccion("+  Nuevo proveedor");
        nuevo.setToolTipText("Agregar un proveedor (Ctrl+N)");
        nuevo.addActionListener(e -> FormularioProveedor.abrir(this, app, null, p -> refrescar()));
        arriba.add(nuevo, BorderLayout.EAST);
        t.add(arriba, BorderLayout.NORTH);

        tabla.setRowHeight(44);
        tabla.setShowVerticalLines(true);
        tabla.setShowHorizontalLines(true);
        tabla.setGridColor(Tema.BORDE);
        tabla.setIntercellSpacing(new Dimension(1, 1));
        tabla.setFillsViewportHeight(true);
        tabla.setBackground(Color.WHITE);
        tabla.setSelectionBackground(Tema.AZUL_SUAVE);
        tabla.setSelectionForeground(Tema.GRIS_OSCURO);
        tabla.setFont(Tema.texto(14));
        tabla.getAccessibleContext().setAccessibleName("Tabla de proveedores");
        tabla.setDefaultRenderer(Object.class, new Celda());
        tabla.getTableHeader().setReorderingAllowed(false);
        tabla.getTableHeader().setPreferredSize(new Dimension(100, Math.max(60, tabla.getFontMetrics(Tema.textoFuerte(13.5f)).getHeight() * 2 + 24)));
        tabla.getTableHeader().setDefaultRenderer(new PanelProductos.Encabezado());
        int[] anchos = {220, 180, 120, 150, 140, 110, 130, 90, 96};
        for (int i = 0; i < anchos.length; i++) {
            tabla.getColumnModel().getColumn(i).setPreferredWidth(anchos[i]);
        }
        tabla.getColumnModel().getColumn(COL_ACCIONES).setCellRenderer(new Acciones());
        tabla.getColumnModel().getColumn(COL_ACCIONES).setMinWidth(90);
        tabla.getColumnModel().getColumn(COL_ACCIONES).setMaxWidth(104);
        tabla.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int f = tabla.rowAtPoint(e.getPoint());
                int c = tabla.columnAtPoint(e.getPoint());
                if (f < 0 || f >= filas.size()) {
                    return;
                }
                Proveedor p = filas.get(f);
                if (c == COL_ACCIONES) {
                    java.awt.Rectangle r = tabla.getCellRect(f, c, false);
                    if (e.getX() < r.x + r.width / 2) {
                        editar(p);
                    } else {
                        cambiarActivo(p);
                    }
                } else if (e.getClickCount() == 2) {
                    editar(p);
                }
            }
        });
        tabla.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                tabla.setCursor(tabla.columnAtPoint(e.getPoint()) == COL_ACCIONES
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
        });
        tabla.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "editar");
        tabla.getActionMap().put("editar", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int f = tabla.getSelectedRow();
                if (f >= 0 && f < filas.size()) {
                    editar(filas.get(f));
                }
            }
        });
        JScrollPane sc = new JScrollPane(tabla);
        sc.setBorder(BorderFactory.createLineBorder(Tema.BORDE));
        sc.getViewport().setBackground(Color.WHITE);
        t.add(sc, BorderLayout.CENTER);
        lblTotal.setFont(Tema.texto(14));
        lblTotal.setForeground(Tema.GRIS_OSCURO);
        t.add(lblTotal, BorderLayout.SOUTH);
        add(t, BorderLayout.CENTER);

        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control N"), "nuevo");
        getActionMap().put("nuevo", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (isShowing() && !Capa.hayAbierta(PanelProveedores.this)) {
                    FormularioProveedor.abrir(PanelProveedores.this, app, null, p -> refrescar());
                }
            }
        });
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                refrescar();
            }
        });
        refrescar();
    }

    private void refrescar() {
        if (app.getOrdenes() == null) {
            return;
        }
        String b = Geo.normalizar(campoBuscar.getText().trim());
        String m = (String) comboMostrar.getSelectedItem();
        List<Proveedor> todos;
        try {
            todos = app.getOrdenes().listarProveedores(false);
        } catch (SQLException e) {
            Ubicaciones.registrarError(e);
            todos = new ArrayList<>();
        }
        filas = new ArrayList<>();
        int activos = 0;
        for (Proveedor p : todos) {
            if (p.activo) {
                activos++;
            }
            if ("Activos".equals(m) && !p.activo || "Inactivos".equals(m) && p.activo) {
                continue;
            }
            if (!b.isEmpty() && !Geo.normalizar(p.nombre + " " + p.documento + " " + p.vendedor).contains(b)) {
                continue;
            }
            filas.add(p);
        }
        modelo.fireTableDataChanged();
        lblTotal.setText(todos.isEmpty() ? "Aún no hay proveedores. Agregue el primero con «Nuevo proveedor»."
                : "Total: " + filas.size() + "  ·  " + activos + (activos == 1 ? " activo" : " activos")
                + " de " + todos.size());
    }

    private void editar(Proveedor p) {
        FormularioProveedor.abrir(this, app, p, x -> refrescar());
    }

    private void cambiarActivo(Proveedor p) {
        Runnable hacer = () -> {
            p.activo = !p.activo;
            try {
                app.getOrdenes().guardarProveedor(p);
                Aviso.mostrar(this, Aviso.Tipo.BIEN, p.activo ? "Proveedor activado" : "Proveedor desactivado", p.nombre);
            } catch (IllegalArgumentException | SQLException e) {
                p.activo = !p.activo;
                Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo cambiar", e.getMessage(), null);
            }
            refrescar();
        };
        if (p.activo) {
            Alerta.confirmar(this, "¿Desactivar a " + p.nombre + "?",
                    "Ya no sale para escoger en las órdenes y compras nuevas. Lo de antes queda igual.",
                    "Sí, desactivar", "No", hacer);
        } else {
            hacer.run();
        }
    }

    private class Modelo extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return filas.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNAS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLUMNAS[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            Proveedor p = filas.get(r);
            switch (c) {
                case 0:
                    return p.nombre;
                case 1:
                    return p.documentoTexto().isEmpty() ? "—" : p.documentoTexto();
                case 2:
                    return p.telefono.isEmpty() ? "—" : p.telefono;
                case 3:
                    return p.vendedor.isEmpty() ? "—" : p.vendedor;
                case 4:
                    return p.celularVendedor.isEmpty() ? "—" : p.celularVendedor;
                case 5:
                    return p.diaVisita.isEmpty() ? "—" : p.diaVisita;
                case 6:
                    return p.formaPago;
                case COL_ESTADO:
                    return p.activo ? "Activo" : "Inactivo";
                default:
                    return "";
            }
        }
    }

    private class Celda extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, false, r, c);
            Proveedor p = filas.get(r);
            setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
            setFont(c == 0 ? Tema.textoFuerte(13.5f) : Tema.texto(13.5f));
            setHorizontalAlignment(c == 0 ? LEFT : CENTER);
            setBackground(sel ? Tema.AZUL_SUAVE : (r % 2 == 1 ? new Color(0xFAFBFC) : Color.WHITE));
            setForeground(p.activo ? Tema.GRIS_OSCURO : Tema.GRIS_TEXTO);
            if ("—".equals(v)) {
                setForeground(Tema.GRIS_TEXTO);
            }
            if (c == COL_ESTADO) {
                setFont(Tema.textoFuerte(13));
                setForeground(p.activo ? Tema.EXITO_TEXTO : Tema.ERROR_TEXTO);
            }
            setToolTipText(c == 0 ? p.nombre : null);
            return this;
        }
    }

    /** Lapiz para editar y papelera para desactivar (o flecha para reactivar). */
    private class Acciones extends DefaultTableCellRenderer {
        private Proveedor p;
        private boolean sel;
        private int fila;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int r, int c) {
            this.p = filas.get(r);
            this.sel = sel;
            this.fila = r;
            setText("");
            setHorizontalAlignment(SwingConstants.CENTER);
            setToolTipText("Editar  ·  " + (p.activo ? "Desactivar" : "Reactivar"));
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            g.setColor(sel ? Tema.AZUL_SUAVE : (fila % 2 == 1 ? new Color(0xFAFBFC) : Color.WHITE));
            g.fillRect(0, 0, getWidth(), getHeight());
            int cy = getHeight() / 2;
            PanelProductos.Icono.LAPIZ.dibujar(g, getWidth() / 2 - 22, cy - 9, 18, Tema.GRIS_OSCURO);
            if (p.activo) {
                PanelProductos.Icono.PAPELERA.dibujar(g, getWidth() / 2 + 6, cy - 9, 18, Tema.ERROR);
            } else {
                PanelProductos.Icono.REACTIVAR.dibujar(g, getWidth() / 2 + 6, cy - 9, 18, Tema.AZUL);
            }
            g.dispose();
        }
    }
}
