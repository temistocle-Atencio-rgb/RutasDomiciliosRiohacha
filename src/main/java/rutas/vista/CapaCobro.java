package rutas.vista;

import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import rutas.logica.Carrito;
import rutas.logica.Cobro;
import rutas.logica.ImportadorProductos;
import rutas.logica.Tarifa;
import rutas.modelo.Cliente;
import rutas.modelo.Geo;
import rutas.modelo.LineaVenta;
import rutas.modelo.MetodoPago;
import rutas.modelo.Pago;

/**
 * Cobro de la venta, encima de la pantalla: total grande, medio de pago en botones,
 * billetes que suman, vueltas en grande y, al terminar, la pantalla de fin con el
 * numero de la venta, las vueltas y los botones para seguir.
 */
final class CapaCobro {

    private static final String COMBINADO = "Combinado";
    private static final long[] BILLETES = {1000, 2000, 5000, 10_000, 20_000, 50_000, 100_000};

    private final JComponent dentroDe;
    private final Aplicacion app;
    private final Carrito carrito;
    private final Long idEspera;
    private final LongConsumer alCobrar;
    private final long total;

    private final JPanel tarjeta = Capa.tarjeta(680, 24, 30);
    private final CardLayout cuerpos = new CardLayout();
    private final JPanel cuerpo = new JPanel(cuerpos);
    private final List<Tema.Boton> botonesMetodo = new ArrayList<>();
    private String modo = MetodoPago.EFECTIVO.name();

    private final JTextField recibido = campoPlata("Recibido en efectivo");
    private final JTextField valorOtro = campoPlata("Valor pagado");
    private final JComboBox<MetodoPago> metodo1 = new JComboBox<>(MetodoPago.values());
    private final JComboBox<MetodoPago> metodo2 = new JComboBox<>(MetodoPago.values());
    private final JTextField valor1 = campoPlata("Primer pago");
    private final JTextField valor2 = campoPlata("Segundo pago");
    private boolean valor2Escrito;
    private boolean llenando;

    private final JLabel resultado = new JLabel(" ");
    private final JTextField cliente = Tema.campo("Cliente (opcional): nombre o celular");
    private final DefaultListModel<Cliente> sugerencias = new DefaultListModel<>();
    private final JList<Cliente> listaSugerencias = new JList<>(sugerencias);
    private final Tema.Boton cobrar;
    private final Tema.Boton volver = Tema.botonSecundario("Volver a la venta");
    private Capa capa;
    private Cobro.Resultado cuenta;

    private CapaCobro(JComponent dentroDe, Aplicacion app, Carrito carrito, Long idEspera, String clienteInicial,
            LongConsumer alCobrar) {
        this.dentroDe = dentroDe;
        this.app = app;
        this.carrito = carrito;
        this.idEspera = idEspera;
        this.alCobrar = alCobrar;
        this.total = carrito.getTotal();
        this.cobrar = Tema.botonAccion("Cobrar " + Tarifa.pesos(total) + "   (Enter)");
        if (clienteInicial != null) {
            cliente.setText(clienteInicial);
        }
    }

    static void mostrar(JComponent dentroDe, Aplicacion app, Carrito carrito, Long idEspera, String clienteInicial,
            LongConsumer alCobrar) {
        new CapaCobro(dentroDe, app, carrito, idEspera, clienteInicial, alCobrar).abrir();
    }

    private static JTextField campoPlata(String nombre) {
        JTextField f = Tema.campo(null);
        f.setFont(Tema.subtitulo(22));
        f.setHorizontalAlignment(JTextField.RIGHT);
        f.getAccessibleContext().setAccessibleName(nombre);
        f.setToolTipText(nombre);
        return f;
    }

    private void abrir() {
        JLabel lt = new JLabel("Total a cobrar");
        lt.setFont(Tema.texto(14));
        lt.setForeground(Tema.GRIS_TEXTO);
        lt.setAlignmentX(Component.LEFT_ALIGNMENT);
        tarjeta.add(Tema.anchoLibre(lt));
        JLabel lTotal = new JLabel(Tarifa.pesos(total));
        lTotal.setFont(Tema.titulo(40));
        lTotal.setForeground(Tema.GRIS_OSCURO);
        lTotal.setAlignmentX(Component.LEFT_ALIGNMENT);
        tarjeta.add(Tema.anchoLibre(lTotal));
        tarjeta.add(Tema.espacio(16));

        tarjeta.add(Tema.etiqueta("¿Cómo paga?"));
        JPanel metodos = new JPanel(new GridLayout(1, 0, 8, 0));
        metodos.setOpaque(false);
        metodos.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (MetodoPago m : MetodoPago.values()) {
            metodos.add(botonMetodo(m.getTexto(), m.name()));
        }
        metodos.add(botonMetodo(COMBINADO, COMBINADO));
        metodos.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        tarjeta.add(metodos);
        tarjeta.add(Tema.espacio(16));

        cuerpo.setOpaque(false);
        cuerpo.setAlignmentX(Component.LEFT_ALIGNMENT);
        cuerpo.add(cuerpoEfectivo(), MetodoPago.EFECTIVO.name());
        cuerpo.add(cuerpoOtro(), "OTRO");
        cuerpo.add(cuerpoCombinado(), COMBINADO);
        tarjeta.add(cuerpo);
        tarjeta.add(Tema.espacio(12));

        resultado.setFont(Tema.titulo(26));
        resultado.setAlignmentX(Component.LEFT_ALIGNMENT);
        resultado.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        tarjeta.add(resultado);
        tarjeta.add(Tema.espacio(14));

        Tema.anchoCompleto(cliente);
        tarjeta.add(cliente);
        listaSugerencias.setFont(Tema.texto(14));
        listaSugerencias.setVisibleRowCount(4);
        listaSugerencias.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                Cliente c = (Cliente) v;
                return super.getListCellRendererComponent(l, c.getNombre()
                        + (c.getTelefono().isEmpty() ? "" : "  ·  " + c.getTelefono()), i, s, f);
            }
        });
        listaSugerencias.getAccessibleContext().setAccessibleName("Clientes encontrados");
        listaSugerencias.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                elegirCliente();
            }
        });
        listaSugerencias.setVisible(false);
        listaSugerencias.setAlignmentX(Component.LEFT_ALIGNMENT);
        listaSugerencias.setBorder(Tema.bordeLista());
        tarjeta.add(listaSugerencias);
        cliente.getDocument().addDocumentListener(escucha(this::buscarClientes));
        cliente.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "bajarLista");
        cliente.getActionMap().put("bajarLista", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (listaSugerencias.isVisible() && sugerencias.size() > 0) {
                    listaSugerencias.setSelectedIndex(0);
                    listaSugerencias.requestFocusInWindow();
                }
            }
        });
        listaSugerencias.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "elegir");
        listaSugerencias.getActionMap().put("elegir", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                elegirCliente();
            }
        });
        tarjeta.add(Tema.espacio(18));

        cobrar.setPreferredSize(new Dimension(320, 44));
        tarjeta.add(Tema.fila(10, cobrar, volver));

        capa = new Capa(dentroDe, tarjeta, true);
        cobrar.addActionListener(e -> cobrar());
        volver.addActionListener(e -> capa.cerrar());
        capa.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "cobrar");
        capa.getActionMap().put("cobrar", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (cobrar.isShowing() && cobrar.isEnabled() && !listaSugerencias.isFocusOwner()) {
                    cobrar();
                }
            }
        });
        for (JTextField f : new JTextField[]{recibido, valorOtro, valor1, valor2}) {
            f.getDocument().addDocumentListener(escucha(this::calcular));
            f.addActionListener(e -> {
                if (cobrar.isEnabled()) {
                    cobrar();
                }
            });
        }
        valor1.getDocument().addDocumentListener(escucha(this::llenarSegundo));
        valor2.getDocument().addDocumentListener(escucha(() -> {
            if (!llenando) {
                valor2Escrito = true;
            }
        }));
        metodo1.addActionListener(e -> calcular());
        metodo2.addActionListener(e -> calcular());
        elegirModo(MetodoPago.EFECTIVO.name());
        capa.mostrar(recibido);
    }

    private Tema.Boton botonMetodo(String texto, String clave) {
        Tema.Boton b = Tema.botonSecundario(texto);
        b.setFont(Tema.textoFuerte(13.5f));
        b.setBorder(BorderFactory.createEmptyBorder(9, 6, 9, 6)); // "Transferencia" cabe completo
        b.setActionCommand(clave);
        b.addActionListener(e -> elegirModo(clave));
        botonesMetodo.add(b);
        return b;
    }

    private void elegirModo(String clave) {
        modo = clave;
        for (Tema.Boton b : botonesMetodo) {
            b.setSelected(b.getActionCommand().equals(clave));
            b.getAccessibleContext().setAccessibleDescription(b.isSelected() ? "elegido" : null);
        }
        if (clave.equals(MetodoPago.EFECTIVO.name())) {
            cuerpos.show(cuerpo, clave);
            recibido.requestFocusInWindow();
        } else if (clave.equals(COMBINADO)) {
            cuerpos.show(cuerpo, COMBINADO);
            valor1.requestFocusInWindow();
        } else {
            cuerpos.show(cuerpo, "OTRO");
            valorOtro.setText(Tarifa.pesos(total).replace("$", ""));
            valorOtro.requestFocusInWindow();
        }
        calcular();
    }

    private JComponent cuerpoEfectivo() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(Tema.etiqueta("¿Con cuánto paga?"));
        Tema.anchoCompleto(recibido);
        p.add(recibido);
        p.add(Tema.espacio(8));
        JPanel billetes = new JPanel(new GridLayout(2, 0, 6, 6));
        billetes.setOpaque(false);
        billetes.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (long b : BILLETES) {
            Tema.Boton bt = Tema.botonPequeno(Tarifa.pesos(b));
            bt.setFont(Tema.textoFuerte(13.5f));
            bt.setToolTipText("Suma " + Tarifa.pesos(b));
            bt.addActionListener(e -> {
                recibido.setText(Tarifa.pesos(leer(recibido) + b).replace("$", ""));
                recibido.requestFocusInWindow();
            });
            billetes.add(bt);
        }
        Tema.Boton exacto = Tema.botonPequeno("Exacto");
        exacto.setFont(Tema.textoFuerte(13.5f));
        exacto.addActionListener(e -> {
            recibido.setText(Tarifa.pesos(total).replace("$", ""));
            recibido.requestFocusInWindow();
        });
        billetes.add(exacto);
        Tema.Boton borrar = Tema.botonPequeno("Borrar");
        borrar.addActionListener(e -> {
            recibido.setText("");
            recibido.requestFocusInWindow();
        });
        JPanel envolver = new JPanel(new java.awt.BorderLayout(6, 0));
        envolver.setOpaque(false);
        envolver.setAlignmentX(Component.LEFT_ALIGNMENT);
        envolver.add(billetes, java.awt.BorderLayout.CENTER);
        envolver.add(borrar, java.awt.BorderLayout.EAST);
        envolver.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
        p.add(envolver);
        return p;
    }

    private JComponent cuerpoOtro() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(Tema.etiqueta("Valor pagado"));
        Tema.anchoCompleto(valorOtro);
        p.add(valorOtro);
        p.add(Tema.espacio(6));
        Tema.Parrafo nota = Tema.parrafo("Revise en el celular que el pago sí llegó antes de cobrar.", 560);
        p.add(nota);
        return p;
    }

    private JComponent cuerpoCombinado() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(Tema.etiqueta("Primer pago"));
        p.add(filaCombinado(metodo1, valor1));
        p.add(Tema.espacio(8));
        p.add(Tema.etiqueta("Segundo pago (se llena con lo que falta)"));
        metodo1.setSelectedItem(MetodoPago.NEQUI);
        metodo2.setSelectedItem(MetodoPago.EFECTIVO);
        p.add(filaCombinado(metodo2, valor2));
        return p;
    }

    private JComponent filaCombinado(JComboBox<MetodoPago> m, JTextField v) {
        Tema.estiloCombo(m, MetodoPago::getTexto);
        m.getAccessibleContext().setAccessibleName("Medio de pago");
        m.setPreferredSize(new Dimension(190, 44));
        JPanel f = new JPanel(new java.awt.BorderLayout(8, 0));
        f.setOpaque(false);
        f.setAlignmentX(Component.LEFT_ALIGNMENT);
        f.add(m, java.awt.BorderLayout.WEST);
        f.add(v, java.awt.BorderLayout.CENTER);
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        return f;
    }

    private void llenarSegundo() {
        if (valor2Escrito) {
            return;
        }
        llenando = true;
        long falta = Math.max(0, total - leer(valor1));
        javax.swing.SwingUtilities.invokeLater(() -> {
            llenando = true;
            valor2.setText(falta == 0 ? "" : Tarifa.pesos(falta).replace("$", ""));
            llenando = false;
        });
    }

    /** Lee pesos del campo; vacio o con error cuenta como 0 (el error se muestra aparte). */
    private static long leer(JTextField f) {
        try {
            String t = f.getText().trim();
            return t.isEmpty() ? 0 : ImportadorProductos.leerPesos(t);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    private static String errorDe(JTextField f) {
        try {
            String t = f.getText().trim();
            if (!t.isEmpty()) {
                ImportadorProductos.leerPesos(t);
            }
            return null;
        } catch (IllegalArgumentException e) {
            return "Escriba solo el valor en pesos (ej. 20.000)";
        }
    }

    private List<Pago> pagos() {
        List<Pago> l = new ArrayList<>();
        if (modo.equals(MetodoPago.EFECTIVO.name())) {
            l.add(new Pago(MetodoPago.EFECTIVO, leer(recibido)));
        } else if (modo.equals(COMBINADO)) {
            l.add(new Pago((MetodoPago) metodo1.getSelectedItem(), leer(valor1)));
            l.add(new Pago((MetodoPago) metodo2.getSelectedItem(), leer(valor2)));
        } else {
            l.add(new Pago(MetodoPago.valueOf(modo), leer(valorOtro)));
        }
        return l;
    }

    private void calcular() {
        String error = null;
        for (JTextField f : campoActivos()) {
            if (error == null) {
                error = errorDe(f);
            }
        }
        cuenta = Cobro.calcular(total, pagos());
        if (error != null || cuenta.getError() != null) {
            resultado.setForeground(Tema.ERROR_TEXTO);
            resultado.setFont(Tema.textoFuerte(15));
            resultado.setText(error != null ? error : cuenta.getError());
        } else if (!cuenta.isAlcanza()) {
            boolean vacio = pagos().stream().allMatch(p -> p.getValor() == 0);
            resultado.setFont(Tema.titulo(26));
            resultado.setForeground(vacio ? Tema.GRIS_TEXTO : Tema.ERROR_TEXTO);
            resultado.setText(vacio ? "Toque los billetes o escriba el valor" : "Faltan " + Tarifa.pesos(cuenta.getFaltan()));
            if (vacio) {
                resultado.setFont(Tema.texto(15));
            }
        } else {
            resultado.setFont(Tema.titulo(26));
            resultado.setForeground(Tema.EXITO_TEXTO);
            resultado.setText(cuenta.getVueltas() > 0 ? "Vueltas: " + Tarifa.pesos(cuenta.getVueltas()) : "Pago exacto");
        }
        cobrar.setEnabled(error == null && cuenta.isAlcanza());
    }

    private JTextField[] campoActivos() {
        if (modo.equals(MetodoPago.EFECTIVO.name())) {
            return new JTextField[]{recibido};
        }
        if (modo.equals(COMBINADO)) {
            return new JTextField[]{valor1, valor2};
        }
        return new JTextField[]{valorOtro};
    }

    private void buscarClientes() {
        sugerencias.clear();
        String t = cliente.getText().trim();
        if (t.length() >= 2) {
            String clave = Geo.normalizar(t);
            boolean numero = t.replaceAll("[\\s-]", "").matches("\\d+");
            List<Cliente> todos = numero ? app.getClientes().enOrden() : app.getClientes().buscarPorPrefijo(clave, 5);
            for (Cliente c : todos) {
                if (sugerencias.size() >= 5) {
                    break;
                }
                if (!numero || c.getTelefono().replaceAll("[\\s-]", "").startsWith(t.replaceAll("[\\s-]", ""))) {
                    if (!c.getNombre().equalsIgnoreCase(t)) {
                        sugerencias.addElement(c);
                    }
                }
            }
        }
        listaSugerencias.setVisible(!sugerencias.isEmpty());
        capa.reacomodar();
    }

    private void elegirCliente() {
        Cliente c = listaSugerencias.getSelectedValue();
        if (c != null) {
            cliente.setText(c.getNombre());
            sugerencias.clear();
            listaSugerencias.setVisible(false);
            capa.reacomodar();
            cobrar.requestFocusInWindow();
        }
    }

    // ------------------------------------------------------------------
    //  Cobrar y pantalla de fin
    // ------------------------------------------------------------------

    private void cobrar() {
        calcular();
        if (!cuenta.isAlcanza()) {
            return;
        }
        cobrar.setEnabled(false);
        List<Pago> pagos = pagos();
        List<LineaVenta> lineas = new ArrayList<>(carrito.getLineas());
        String nombreCliente = cliente.getText().trim();
        long numero;
        try {
            numero = app.getTienda().cobrar(idEspera, app.getSesion().getUsuario().getId(), nombreCliente,
                    carrito, pagos, cuenta);
        } catch (Exception e) {
            rutas.logica.Ubicaciones.registrarError(e);
            calcular();
            Alerta.mostrar(dentroDe, Alerta.Tipo.AVISO, "No se pudo guardar la venta",
                    "No se cobró nada. Intente de nuevo. Detalle: " + e.getMessage(), null);
            return;
        }
        // de aqui en adelante la venta YA quedo guardada: un error no puede volver a habilitar Cobrar
        Comprobante comp = null;
        try {
            comp = new Comprobante(app.getNegocio(), numero, LocalDateTime.now(),
                    app.getSesion().getUsuario().getNombre(), nombreCliente, lineas, total,
                    Comprobante.pagosNetos(pagos, cuenta.getVueltas()), cuenta.getRecibidoEfectivo(), cuenta.getVueltas());
            comp.nota = carrito.getNota();
        } catch (RuntimeException e) {
            rutas.logica.Ubicaciones.registrarError(e);
        }
        try {
            alCobrar.accept(numero);
        } catch (RuntimeException e) {
            rutas.logica.Ubicaciones.registrarError(e);
        }
        if (comp != null) {
            mostrarFin(comp);
        } else {
            capa.cerrar();
            Alerta.mostrar(dentroDe, Alerta.Tipo.EXITO, "Venta No. " + Comprobante.numeroTexto(numero) + " cobrada",
                    "La venta quedó guardada. No se pudo armar el comprobante.", null);
        }
    }

    private void mostrarFin(Comprobante comp) {
        tarjeta.removeAll();
        Alerta.Icono ok = new Alerta.Icono(Alerta.Tipo.EXITO);
        ok.setAlignmentX(Component.CENTER_ALIGNMENT);
        tarjeta.add(ok);
        tarjeta.add(Tema.espacio(14));
        tarjeta.add(centrado("Venta No. " + Comprobante.numeroTexto(comp.numero) + " cobrada", Tema.titulo(22),
                Tema.GRIS_OSCURO));
        tarjeta.add(Tema.espacio(16));
        if (comp.recibido > 0) {
            tarjeta.add(centrado("Vueltas", Tema.texto(15), Tema.GRIS_TEXTO));
            tarjeta.add(centrado(Tarifa.pesos(comp.vueltas), Tema.titulo(46), Tema.EXITO_TEXTO));
            tarjeta.add(centrado("Recibió " + Tarifa.pesos(comp.recibido) + " en efectivo", Tema.texto(14),
                    Tema.GRIS_TEXTO));
        } else {
            tarjeta.add(centrado("Total " + Tarifa.pesos(comp.total), Tema.titulo(30), Tema.GRIS_OSCURO));
        }
        tarjeta.add(Tema.espacio(22));

        Tema.Boton nueva = Tema.botonAccion("Nueva venta   (Enter)");
        Tema.Boton imprimir = Tema.botonSecundario("Imprimir");
        imprimir.setToolTipText("Imprimir el comprobante o guardarlo como PDF");
        Tema.Boton ver = Tema.botonSecundario("Ver la venta");
        JPanel fila = Tema.fila(10, nueva, imprimir, ver);
        ((java.awt.FlowLayout) fila.getLayout()).setAlignment(java.awt.FlowLayout.CENTER);
        fila.setAlignmentX(Component.CENTER_ALIGNMENT);
        tarjeta.add(fila);
        Vista vista = new Vista(comp);
        vista.setVisible(false);
        vista.setAlignmentX(Component.CENTER_ALIGNMENT);
        tarjeta.add(Tema.espacio(10));
        tarjeta.add(vista);

        nueva.addActionListener(e -> capa.cerrar());
        imprimir.addActionListener(e -> imprimir(comp));
        ver.addActionListener(e -> {
            vista.setVisible(!vista.isVisible());
            ver.setText(vista.isVisible() ? "Ocultar la venta" : "Ver la venta");
            capa.reacomodar();
        });
        capa.getActionMap().put("cobrar", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                capa.cerrar();
            }
        });
        capa.reacomodar();
        nueva.requestFocusInWindow();
    }

    private static JLabel centrado(String texto, java.awt.Font f, java.awt.Color c) {
        JLabel l = new JLabel(texto, JLabel.CENTER);
        l.setFont(f);
        l.setForeground(c);
        l.setAlignmentX(Component.CENTER_ALIGNMENT);
        return l;
    }

    private void imprimir(Comprobante comp) {
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName("Comprobante " + Comprobante.numeroTexto(comp.numero));
        job.setPrintable(comp);
        try {
            if (job.printDialog()) {
                job.print();
            }
        } catch (PrinterException | RuntimeException e) {
            Alerta.mostrar(dentroDe, Alerta.Tipo.AVISO, "No se pudo imprimir",
                    "Revise que haya una impresora instalada. En Windows puede escoger «Microsoft Print to PDF»"
                    + " para guardarlo como PDF.", null);
        }
    }

    /** Vista previa del comprobante dentro de la pantalla de fin. */
    static class Vista extends JPanel {
        private final Comprobante comp;

        Vista(Comprobante comp) {
            this.comp = comp;
            setOpaque(false);
            getAccessibleContext().setAccessibleName("Comprobante de la venta");
        }

        @Override
        public Dimension getPreferredSize() {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(1, 1,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            int alto = comp.dibujar(g, 300);
            g.dispose();
            return new Dimension(340, alto + 24);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            g.setColor(Tema.GRIS_CLARO);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), Tema.ARCO, Tema.ARCO);
            g.translate(20, 4);
            comp.dibujar(g, 300);
            g.dispose();
        }
    }

    private static DocumentListener escucha(Runnable r) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { r.run(); }
            public void removeUpdate(DocumentEvent e) { r.run(); }
            public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }
}
