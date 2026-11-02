package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import rutas.logica.AjustesRecibo;
import rutas.logica.BaseTienda;
import rutas.logica.Tarifa;

/** La compra completa (como quedo guardada): datos, productos, totales y, si se anulo, por que. */
final class DetalleCompraVista {

    private static final String[] COLUMNAS = {"Producto", "Cant", "Vr Base", "% Imp", "Vr Compra", "Vr Venta",
        "% Desc", "SubTotal"};

    private DetalleCompraVista() {
    }

    static void mostrar(JComponent dentroDe, BaseTienda.DetalleCompra d, AjustesRecibo negocio, Runnable anular) {
        if (Capa.hayAbierta(dentroDe)) {
            return;
        }
        BaseTienda.CompraGuardada c = d.compra;
        JPanel modal = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                javax.swing.JRootPane r = SwingUtilities.getRootPane(dentroDe);
                int w = r == null ? 900 : Math.max(720, Math.min(960, r.getWidth() - 80));
                int h = Math.min(super.getPreferredSize().height, r == null ? 640 : r.getHeight() - 80);
                return new Dimension(w, h);
            }
        };
        modal.setOpaque(false);

        // cabeza azul con el numero y el estado
        JPanel cabeza = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Tema.suavizar(g);
                g.setColor(Tema.AZUL);
                g.fillRoundRect(0, 0, getWidth(), getHeight() + Tema.ARCO, Tema.ARCO, Tema.ARCO);
                g.dispose();
            }
        };
        cabeza.setOpaque(false);
        cabeza.setBorder(BorderFactory.createEmptyBorder(12, 20, 12, 20));
        JLabel titulo = new JLabel("Compra No. " + Comprobante.numeroTexto(c.getNumero()));
        titulo.setFont(Tema.subtitulo(20));
        titulo.setForeground(Tema.BLANCO);
        cabeza.add(titulo, BorderLayout.WEST);
        JLabel estado = new JLabel(c.isAnulada() ? "ANULADO" : "PAGADO");
        estado.setFont(Tema.textoFuerte(14));
        estado.setForeground(Tema.BLANCO);
        cabeza.add(estado, BorderLayout.EAST);
        modal.add(cabeza, BorderLayout.NORTH);

        JPanel cuerpo = new JPanel(new BorderLayout(0, 12));
        cuerpo.setOpaque(false);
        cuerpo.setBorder(BorderFactory.createEmptyBorder(14, 20, 10, 20));

        JPanel arriba = new JPanel();
        arriba.setOpaque(false);
        arriba.setLayout(new BoxLayout(arriba, BoxLayout.Y_AXIS));
        JPanel datos = new JPanel(new GridLayout(2, 3, 18, 10));
        datos.setOpaque(false);
        datos.add(dato("Fecha y hora", PanelHistorialCompras.textoFecha(c.getFecha())));
        datos.add(dato("Proveedor", c.getProveedor().isEmpty() ? "Sin proveedor" : c.getProveedor()));
        datos.add(dato("Factura del proveedor", c.getFactura().isEmpty() ? "—" : c.getFactura()));
        datos.add(dato("Bodega", c.getBodega()));
        datos.add(dato("Atendió", c.getAtendio()));
        datos.add(dato("Productos", String.valueOf(d.lineas.size())));
        datos.setAlignmentX(Component.LEFT_ALIGNMENT);
        arriba.add(datos);
        if (c.isAnulada()) {
            arriba.add(Tema.espacio(10));
            String cuando = d.anuladaEn == null ? "" : " el " + PanelHistorialCompras.textoFecha(d.anuladaEn);
            Tema.Parrafo an = Tema.parrafo("Anulada" + cuando + (d.anuladaPor.isEmpty() ? "" : " por " + d.anuladaPor)
                    + ". Motivo: " + d.motivo + ". El stock ya se devolvió.", 800);
            an.setFont(Tema.textoFuerte(13.5f));
            an.setForeground(Tema.ERROR_TEXTO);
            an.setAlignmentX(Component.LEFT_ALIGNMENT);
            arriba.add(an);
        }
        cuerpo.add(arriba, BorderLayout.NORTH);

        JTable tabla = new JTable(new Modelo(d.lineas));
        tabla.setRowHeight(40);
        tabla.setFont(Tema.texto(13));
        tabla.setShowVerticalLines(false);
        tabla.setGridColor(Tema.BORDE);
        tabla.setIntercellSpacing(new Dimension(0, 1));
        tabla.setFillsViewportHeight(true);
        tabla.setBackground(Color.WHITE);
        tabla.setSelectionBackground(Tema.AZUL_SUAVE);
        tabla.setSelectionForeground(Tema.GRIS_OSCURO);
        tabla.getAccessibleContext().setAccessibleName("Productos de la compra");
        tabla.getTableHeader().setReorderingAllowed(false);
        tabla.getTableHeader().setDefaultRenderer(new PanelProductos.Encabezado());
        tabla.setDefaultRenderer(Object.class, new Celda(d.lineas));
        int[] anchos = {240, 70, 90, 60, 90, 90, 60, 100};
        for (int i = 0; i < anchos.length; i++) {
            tabla.getColumnModel().getColumn(i).setPreferredWidth(anchos[i]);
        }
        JScrollPane sc = new JScrollPane(tabla);
        sc.setBorder(BorderFactory.createLineBorder(Tema.BORDE));
        sc.getViewport().setBackground(Color.WHITE);
        sc.setPreferredSize(new Dimension(600, Math.min(260, 46 + 41 * Math.max(2, d.lineas.size()))));
        cuerpo.add(sc, BorderLayout.CENTER);

        // observaciones y totales
        JPanel abajo = new JPanel(new GridBagLayout());
        abajo.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.NORTHWEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;
        g.insets = new Insets(0, 0, 0, 30);
        JPanel obs = new JPanel();
        obs.setOpaque(false);
        obs.setLayout(new BoxLayout(obs, BoxLayout.Y_AXIS));
        JLabel lo = new JLabel("Observaciones");
        lo.setFont(Tema.textoFuerte(13));
        lo.setForeground(Tema.GRIS_OSCURO);
        obs.add(lo);
        obs.add(Tema.espacio(4));
        Tema.Parrafo to = Tema.parrafo(d.observaciones.isEmpty() ? "Sin observaciones" : d.observaciones, 420);
        to.setFont(Tema.texto(13));
        to.setForeground(d.observaciones.isEmpty() ? Tema.GRIS_TEXTO : Tema.GRIS_OSCURO);
        obs.add(to);
        abajo.add(obs, g);
        JPanel tot = new JPanel(new GridLayout(0, 2, 24, 4));
        tot.setOpaque(false);
        filaTotal(tot, "Total Bruto", Tarifa.pesos(d.bruto), false);
        filaTotal(tot, "Descuento", d.descuentos == 0 ? "$0" : "-" + Tarifa.pesos(d.descuentos), false);
        filaTotal(tot, "Impuestos", Tarifa.pesos(d.impuestos), false);
        filaTotal(tot, "Total Compra", Tarifa.pesos(d.total), true);
        g.gridx = 1;
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        g.anchor = GridBagConstraints.NORTHEAST;
        g.insets = new Insets(0, 0, 0, 0);
        abajo.add(tot, g);
        cuerpo.add(abajo, BorderLayout.SOUTH);
        modal.add(cuerpo, BorderLayout.CENTER);

        JPanel pie = new JPanel(new BorderLayout());
        pie.setOpaque(false);
        pie.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Tema.BORDE),
                BorderFactory.createEmptyBorder(10, 20, 10, 20)));
        Tema.Boton cerrar = Tema.botonSecundario("Cerrar");
        Tema.Boton imprimir = Tema.botonPrimario("Imprimir o PDF");
        imprimir.setIcon(Iconos.de("notas", 16, Tema.BLANCO));
        imprimir.setToolTipText("Ver la compra en hoja carta para imprimirla o guardarla como PDF");
        Tema.Boton anularB = new Tema.Boton("Anular compra", Tema.BLANCO, Tema.ERROR_TEXTO, Tema.ERROR);
        anularB.setToolTipText("Devolver el stock y marcar la compra como ANULADO");
        if (!c.isAnulada()) {
            pie.add(Tema.fila(8, anularB), BorderLayout.WEST);
        }
        pie.add(Tema.fila(8, cerrar, imprimir), BorderLayout.EAST);
        modal.add(pie, BorderLayout.SOUTH);

        Capa capa = new Capa(dentroDe, modal, true);
        cerrar.addActionListener(e -> capa.cerrar());
        anularB.addActionListener(e -> {
            capa.cerrar();
            anular.run();
        });
        imprimir.addActionListener(e -> {
            capa.cerrar();
            VistaPrevia.mostrar(dentroDe, documento(d, negocio), "Compra " + Comprobante.numeroTexto(c.getNumero()));
        });
        capa.mostrar(cerrar);
    }

    /** La compra en hoja carta: datos arriba, los productos y al final los totales. */
    static DocumentoLista documento(BaseTienda.DetalleCompra d, AjustesRecibo negocio) {
        BaseTienda.CompraGuardada c = d.compra;
        List<String[]> filas = new ArrayList<>();
        for (BaseTienda.LineaGuardada l : d.lineas) {
            filas.add(new String[]{l.nombre, PanelHistorialCompras.cantidad(l.cantidad, l.unidad), Tarifa.pesos(l.base),
                PanelHistorialCompras.porcentaje(l.impuesto), Tarifa.pesos(l.costo),
                PanelHistorialCompras.porcentaje(l.descuento), Tarifa.pesos(l.subtotal)});
        }
        filas.add(new String[]{"", "", "", "", "Total bruto", "", Tarifa.pesos(d.bruto)});
        filas.add(new String[]{"", "", "", "", "Descuento", "", d.descuentos == 0 ? "$0" : "-" + Tarifa.pesos(d.descuentos)});
        filas.add(new String[]{"", "", "", "", "Impuestos", "", Tarifa.pesos(d.impuestos)});
        filas.add(new String[]{"", "", "", "", "TOTAL", "", Tarifa.pesos(d.total)});
        DocumentoLista doc = new DocumentoLista("Compra No. " + Comprobante.numeroTexto(c.getNumero())
                + (c.isAnulada() ? " · ANULADA" : ""),
                new String[]{"Producto", "Cant", "Vr Base", "% Imp", "Vr Compra", "% Desc", "SubTotal"},
                new float[]{3.1f, 0.9f, 1.1f, 0.8f, 1.2f, 0.9f, 1.2f},
                new boolean[]{false, true, true, true, true, true, true}, filas, negocio);
        doc.setConteo(d.lineas.size() + (d.lineas.size() == 1 ? " producto" : " productos"));
        doc.setSubtitulo(PanelHistorialCompras.textoFecha(c.getFecha())
                + "  ·  Proveedor: " + (c.getProveedor().isEmpty() ? "—" : c.getProveedor())
                + "  ·  Factura: " + (c.getFactura().isEmpty() ? "—" : c.getFactura())
                + "  ·  Bodega: " + c.getBodega());
        return doc;
    }

    private static JComponent dato(String titulo, String valor) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel t = new JLabel(titulo);
        t.setFont(Tema.texto(12.5f));
        t.setForeground(Tema.GRIS_TEXTO);
        p.add(t);
        JLabel v = new JLabel(valor);
        v.setFont(Tema.textoFuerte(14));
        v.setForeground(Tema.GRIS_OSCURO);
        v.setToolTipText(valor);
        p.add(v);
        return p;
    }

    private static void filaTotal(JPanel p, String nombre, String valor, boolean grande) {
        JLabel n = new JLabel(nombre);
        n.setFont(grande ? Tema.subtitulo(15) : Tema.texto(13.5f));
        n.setForeground(grande ? Tema.GRIS_OSCURO : Tema.GRIS_TEXTO);
        p.add(n);
        JLabel v = new JLabel(valor, SwingConstants.RIGHT);
        v.setFont(grande ? Tema.titulo(20) : Tema.textoFuerte(14));
        v.setForeground(Tema.GRIS_OSCURO);
        p.add(v);
    }

    private static class Modelo extends AbstractTableModel {
        private final List<BaseTienda.LineaGuardada> lineas;

        Modelo(List<BaseTienda.LineaGuardada> lineas) {
            this.lineas = lineas;
        }

        @Override
        public int getRowCount() {
            return lineas.size();
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
            BaseTienda.LineaGuardada l = lineas.get(r);
            switch (c) {
                case 0:
                    return l.nombre;
                case 1:
                    return PanelHistorialCompras.cantidad(l.cantidad, l.unidad);
                case 2:
                    return Tarifa.pesos(l.base);
                case 3:
                    return PanelHistorialCompras.porcentaje(l.impuesto);
                case 4:
                    return Tarifa.pesos(l.costo);
                case 5:
                    return l.precioVenta == null ? "Sin cambio" : Tarifa.pesos(l.precioVenta);
                case 6:
                    return PanelHistorialCompras.porcentaje(l.descuento);
                default:
                    return Tarifa.pesos(l.subtotal);
            }
        }
    }

    private static class Celda extends DefaultTableCellRenderer {
        private final List<BaseTienda.LineaGuardada> lineas;

        Celda(List<BaseTienda.LineaGuardada> lineas) {
            this.lineas = lineas;
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
            super.getTableCellRendererComponent(t, v, s, false, r, c);
            setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
            setFont(c == 7 ? Tema.textoFuerte(13.5f) : Tema.texto(13));
            setForeground(Tema.GRIS_OSCURO);
            setHorizontalAlignment(c == 0 ? LEFT : c == 3 || c == 6 ? CENTER : RIGHT);
            if (c == 0) {
                BaseTienda.LineaGuardada l = lineas.get(r);
                setText("<html><b>" + esc(l.nombre) + "</b>" + (l.codigo == null ? ""
                        : "<br><span style='color:#5B6670'>Cod: " + esc(l.codigo) + "</span>") + "</html>");
            } else if (c == 5 && lineas.get(r).precioVenta == null) {
                setForeground(Tema.GRIS_TEXTO);
                setToolTipText("No se cambió el precio de venta en esta compra");
            } else {
                setToolTipText(null);
            }
            return this;
        }

        private static String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
