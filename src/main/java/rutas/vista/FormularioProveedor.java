package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import rutas.modelo.Colombia;
import rutas.modelo.Proveedor;

/**
 * «Agregar proveedor» (y editar): como la referencia, con la cabeza azul y los datos de la empresa
 * (tipo de documento, NIT con su DV calculado, nombre, regimen, telefono, correo, departamento,
 * municipio, direccion, pagina web) y abajo el vendedor que pasa por la tienda, el dia de visita y
 * la forma de pago. Solo el nombre es obligatorio.
 */
final class FormularioProveedor {

    private FormularioProveedor() {
    }

    /** p null = nuevo. alGuardar recibe el proveedor ya guardado (con su id). */
    static void abrir(JComponent dentroDe, Aplicacion app, Proveedor p, Consumer<Proveedor> alGuardar) {
        Proveedor pr = p == null ? new Proveedor() : p;
        Capa[] capa = new Capa[1];
        JPanel modal = modal(p == null ? "Agregar proveedor" : "Editar proveedor", 760, () -> capa[0].cerrar());
        JPanel t = (JPanel) ((BorderLayout) modal.getLayout()).getLayoutComponent(BorderLayout.CENTER);

        JComboBox<String> tipo = new JComboBox<>(Proveedor.TIPOS_DOCUMENTO);
        Tema.estiloCombo(tipo, String::valueOf);
        tipo.setSelectedItem(pr.tipoDocumento);
        JTextField documento = Tema.campo("Nit / Documento");
        documento.setText(pr.documento);
        JTextField dv = Tema.campo("");
        dv.setText(pr.dv);
        dv.setHorizontalAlignment(JTextField.CENTER);
        JTextField nombre = Tema.campo("Nombre o razón social");
        nombre.setText(pr.nombre);
        SelectorRegimen regimen = new SelectorRegimen(pr.regimenes);
        JTextField telefono = Tema.campo("Teléfono de la empresa");
        telefono.setText(pr.telefono);
        JTextField correo = Tema.campo("ventas@proveedor.com");
        correo.setText(pr.correo);
        JComboBox<String> depto = new JComboBox<>(Colombia.departamentos().toArray(new String[0]));
        Tema.estiloCombo(depto, String::valueOf);
        depto.setMaximumRowCount(14);
        JComboBox<String> muni = new JComboBox<>();
        muni.setEditable(true); // si el municipio no esta en la lista se escribe
        Tema.estiloCombo(muni, String::valueOf);
        Runnable llenarMunicipios = () -> {
            Object antes = muni.getEditor().getItem();
            muni.removeAllItems();
            for (String m : Colombia.municipios((String) depto.getSelectedItem())) {
                muni.addItem(m);
            }
            if (antes != null && !String.valueOf(antes).isBlank()
                    && Colombia.municipios((String) depto.getSelectedItem()).contains(String.valueOf(antes))) {
                muni.setSelectedItem(antes);
            }
        };
        depto.setSelectedItem(pr.departamento.isBlank() ? "GUAJIRA" : pr.departamento);
        llenarMunicipios.run();
        muni.setSelectedItem(pr.municipio);
        depto.addActionListener(e -> llenarMunicipios.run());
        JTextField direccion = Tema.campo("Ej. Calle 15 # 7-20");
        direccion.setText(pr.direccion);
        JTextField web = Tema.campo("www.proveedor.com");
        web.setText(pr.paginaWeb);
        JTextField vendedor = Tema.campo("Ej. Carlos (el que pasa por la tienda)");
        vendedor.setText(pr.vendedor);
        JTextField celVendedor = Tema.campo("Celular con WhatsApp");
        celVendedor.setText(pr.celularVendedor);
        JComboBox<String> dia = new JComboBox<>(Proveedor.DIAS);
        Tema.estiloCombo(dia, s -> s.isEmpty() ? "Sin día fijo" : s);
        dia.setSelectedItem(pr.diaVisita);
        JComboBox<String> pago = new JComboBox<>(Proveedor.FORMAS_PAGO);
        Tema.estiloCombo(pago, String::valueOf);
        pago.setSelectedItem(pr.formaPago);

        // el DV se calcula solo para el NIT (la DIAN tiene una formula fija)
        Runnable calcularDv = () -> {
            boolean nit = String.valueOf(tipo.getSelectedItem()).startsWith("NIT -");
            dv.setEditable(false);
            dv.setFocusable(false);
            dv.setText(nit ? Proveedor.digitoVerificacion(documento.getText()) : "");
            dv.setToolTipText(nit ? "Se calcula solo con el NIT" : "Solo el NIT lleva dígito de verificación");
        };
        documento.getDocument().addDocumentListener(PanelProductos.alCambiar(calcularDv));
        tipo.addActionListener(e -> calcularDv.run());
        calcularDv.run();

        JPanel g = new JPanel(new GridBagLayout());
        g.setOpaque(false);
        g.setAlignmentX(Component.LEFT_ALIGNMENT);
        poner(g, 0, 0, 2, 0.45, "Tipo de documento", tipo);
        poner(g, 2, 0, 1, 0.4, "Nit / Documento", documento);
        poner(g, 3, 0, 1, 0.15, "DV", dv);
        poner(g, 0, 1, 2, 0.5, "Nombre del proveedor *", nombre);
        poner(g, 2, 1, 2, 0.5, "Régimen", regimen);
        poner(g, 0, 2, 2, 0.5, "Teléfono", telefono);
        poner(g, 2, 2, 2, 0.5, "Correo electrónico", correo);
        poner(g, 0, 3, 2, 0.5, "Departamento", depto);
        poner(g, 2, 3, 2, 0.5, "Municipio", muni);
        poner(g, 0, 4, 2, 0.5, "Dirección", direccion);
        poner(g, 2, 4, 2, 0.5, "Página web", web);
        t.add(g);

        JLabel sec = Tema.seccion("Vendedor y pedidos");
        t.add(sec);
        Tema.Parrafo ayudaV = Tema.parrafo("A quien se le mandan las órdenes por WhatsApp y qué día pasa por la tienda.", 680);
        ayudaV.setFont(Tema.texto(12.5f));
        ayudaV.setForeground(Tema.GRIS_TEXTO);
        t.add(ayudaV);
        t.add(Tema.espacio(8));
        JPanel g2 = new JPanel(new GridBagLayout());
        g2.setOpaque(false);
        g2.setAlignmentX(Component.LEFT_ALIGNMENT);
        poner(g2, 0, 0, 2, 0.5, "Vendedor o contacto", vendedor);
        poner(g2, 2, 0, 2, 0.5, "Celular del vendedor", celVendedor);
        poner(g2, 0, 1, 2, 0.5, "Día de visita", dia);
        poner(g2, 2, 1, 2, 0.5, "Forma de pago", pago);
        t.add(g2);

        JLabel error = new JLabel(" ");
        error.setFont(Tema.textoFuerte(13));
        error.setForeground(Tema.ERROR_TEXTO);
        error.setAlignmentX(Component.LEFT_ALIGNMENT);
        t.add(error);
        t.add(Tema.espacio(8));
        Tema.Boton guardar = Tema.botonAccion(p == null ? "Agregar" : "Guardar cambios");
        JPanel pie = new JPanel(new BorderLayout());
        pie.setOpaque(false);
        pie.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel nota = new JLabel("Solo el nombre es obligatorio.");
        nota.setFont(Tema.texto(12.5f));
        nota.setForeground(Tema.GRIS_TEXTO);
        pie.add(nota, BorderLayout.WEST);
        pie.add(guardar, BorderLayout.EAST);
        t.add(pie);

        capa[0] = new Capa(dentroDe, modal, true);
        Runnable hacer = () -> {
            String tel = telefono.getText().trim().replaceAll("[\\s-]", "");
            String cel = celVendedor.getText().trim().replaceAll("[\\s-]", "");
            if (!tel.isEmpty() && !tel.matches("\\+?\\d{7,12}")) {
                error.setText("El teléfono lleva solo números (7 a 10)");
                return;
            }
            if (!cel.isEmpty() && !cel.matches("\\+?\\d{10,12}")) {
                error.setText("El celular del vendedor lleva 10 números");
                return;
            }
            Proveedor n = new Proveedor();
            n.id = pr.id;
            n.activo = pr.activo;
            n.tipoDocumento = (String) tipo.getSelectedItem();
            n.documento = documento.getText();
            n.dv = dv.getText();
            n.nombre = nombre.getText();
            n.regimenes = regimen.valor();
            n.telefono = tel;
            n.correo = correo.getText().trim();
            n.departamento = (String) depto.getSelectedItem();
            Object m = muni.getEditor().getItem();
            n.municipio = m == null ? "" : String.valueOf(m).trim().toUpperCase();
            n.direccion = direccion.getText();
            n.paginaWeb = web.getText();
            n.vendedor = vendedor.getText();
            n.celularVendedor = cel;
            n.diaVisita = (String) dia.getSelectedItem();
            n.formaPago = (String) pago.getSelectedItem();
            try {
                app.getOrdenes().guardarProveedor(n);
            } catch (IllegalArgumentException ex) {
                error.setText(ex.getMessage());
                return;
            } catch (java.sql.SQLException ex) {
                rutas.logica.Ubicaciones.registrarError(ex);
                error.setText("No se pudo guardar: " + ex.getMessage());
                return;
            }
            capa[0].cerrar();
            alGuardar.accept(n);
            Aviso.mostrar(dentroDe, Aviso.Tipo.BIEN, p == null ? "Proveedor agregado" : "Proveedor guardado", n.nombre);
        };
        guardar.addActionListener(e -> hacer.run());
        for (JTextField f : new JTextField[]{documento, nombre, telefono, correo, direccion, web, vendedor, celVendedor}) {
            f.addActionListener(e -> hacer.run());
        }
        capa[0].mostrar(p == null ? tipo : nombre);
    }

    private static void poner(JPanel g, int x, int y, int ancho, double peso, String etiqueta, JComponent c) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel l = new JLabel(etiqueta);
        l.setFont(Tema.textoFuerte(13));
        l.setForeground(Tema.GRIS_OSCURO);
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setLabelFor(c);
        p.add(l);
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setPreferredSize(new Dimension(80, 40));
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        c.getAccessibleContext().setAccessibleName(etiqueta.replace(" *", ""));
        p.add(c);
        GridBagConstraints k = new GridBagConstraints();
        k.gridx = x;
        k.gridy = y;
        k.gridwidth = ancho;
        k.weightx = peso;
        k.fill = GridBagConstraints.HORIZONTAL;
        k.insets = new Insets(0, 0, 12, x + ancho < 4 ? 16 : 0);
        g.add(p, k);
    }

    // ------------------------------------------------------------------

    /**
     * Ventana de la referencia: cabeza azul con el titulo en blanco y una X para cerrar, y el
     * cuerpo blanco (el CENTER del BorderLayout) donde se ponen los campos de arriba a abajo.
     */
    static JPanel modal(String titulo, int ancho, Runnable cerrar) {
        JPanel modal = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Tema.suavizar(g);
                g.setColor(Tema.BLANCO);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), Tema.ARCO, Tema.ARCO);
                g.dispose();
            }

            @Override
            public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                return new Dimension(ancho, d.height);
            }
        };
        modal.setOpaque(false);
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
        cabeza.setBorder(BorderFactory.createEmptyBorder(14, 22, 14, 14));
        JLabel tit = new JLabel(titulo);
        tit.setFont(Tema.subtitulo(19));
        tit.setForeground(Tema.BLANCO);
        cabeza.add(tit, BorderLayout.WEST);
        Tema.Boton x = new BotonX();
        x.addActionListener(e -> cerrar.run());
        cabeza.add(x, BorderLayout.EAST);
        modal.add(cabeza, BorderLayout.NORTH);
        JPanel cuerpo = new JPanel();
        cuerpo.setOpaque(false);
        cuerpo.setLayout(new BoxLayout(cuerpo, BoxLayout.Y_AXIS));
        cuerpo.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
        modal.add(cuerpo, BorderLayout.CENTER);
        return modal;
    }

    /** La X blanca de la cabeza azul. */
    private static class BotonX extends Tema.Boton {
        BotonX() {
            super("", Tema.AZUL, Tema.BLANCO, Tema.AZUL);
            setToolTipText("Cerrar (Esc)");
            getAccessibleContext().setAccessibleName("Cerrar");
            setPreferredSize(new Dimension(34, 34));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            if (getModel().isRollover() || isFocusOwner()) {
                g.setColor(Tema.AZUL_PROFUNDO);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), Tema.ARCO, Tema.ARCO);
            }
            g.setColor(Tema.BLANCO);
            g.setStroke(new java.awt.BasicStroke(2.4f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            int c = getWidth() / 2, m = getHeight() / 2, r = 7;
            g.drawLine(c - r, m - r, c + r, m + r);
            g.drawLine(c + r, m - r, c - r, m + r);
            g.dispose();
        }
    }

    /** «Seleccione régimen» con casillas (se pueden marcar varias), como en la referencia. */
    static class SelectorRegimen extends JComboBox<String> {
        private final List<JCheckBoxMenuItem> items = new ArrayList<>();
        private final JPopupMenu menu = new JPopupMenu();

        SelectorRegimen(String valor) {
            super(new String[]{""});
            Tema.estiloCombo(this, s -> textoSeleccion());
            JCheckBoxMenuItem todas = new JCheckBoxMenuItem("Seleccionar todas");
            todas.putClientProperty("CheckBoxMenuItem.doNotCloseOnMouseClick", Boolean.TRUE);
            todas.setFont(Tema.texto(13.5f));
            menu.add(todas);
            menu.addSeparator();
            List<String> marcados = List.of(valor == null ? new String[0] : valor.split(";"));
            for (String r : Proveedor.REGIMENES) {
                JCheckBoxMenuItem it = new JCheckBoxMenuItem(r, marcados.contains(r));
                it.putClientProperty("CheckBoxMenuItem.doNotCloseOnMouseClick", Boolean.TRUE);
                it.setFont(Tema.texto(13.5f));
                it.addActionListener(e -> {
                    todas.setSelected(items.stream().allMatch(JCheckBoxMenuItem::isSelected));
                    repaint();
                });
                items.add(it);
                menu.add(it);
            }
            todas.setSelected(items.stream().allMatch(JCheckBoxMenuItem::isSelected));
            todas.addActionListener(e -> {
                for (JCheckBoxMenuItem it : items) {
                    it.setSelected(todas.isSelected());
                }
                repaint();
            });
            // en vez de la lista normal se abre el menu con casillas
            addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                    javax.swing.SwingUtilities.invokeLater(() -> {
                        hidePopup();
                        menu.setPopupSize(new Dimension(Math.max(getWidth(), 300), menu.getPreferredSize().height));
                        menu.show(SelectorRegimen.this, 0, getHeight());
                    });
                }

                public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { }

                public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { }
            });
        }

        private String textoSeleccion() {
            List<String> l = new ArrayList<>();
            for (JCheckBoxMenuItem it : items) {
                if (it.isSelected()) {
                    l.add(it.getText());
                }
            }
            return l.isEmpty() ? "Seleccione régimen" : String.join(", ", l);
        }

        /** "Gran contribuyente;Autorretenedor" (lo que se guarda). */
        String valor() {
            List<String> l = new ArrayList<>();
            for (JCheckBoxMenuItem it : items) {
                if (it.isSelected()) {
                    l.add(it.getText());
                }
            }
            return String.join(";", l);
        }
    }
}
