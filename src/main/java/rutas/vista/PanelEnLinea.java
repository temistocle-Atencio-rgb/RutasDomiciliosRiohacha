package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import rutas.modelo.Geo;
import rutas.modelo.Parada;
import rutas.modelo.PedidoWeb;

/**
 * Domicilios › En línea: los pedidos que llegan de la tienda en línea. Aceptar, pasarlos a la ruta (o
 * «listo para recoger»), confirmar el pago con el comprobante y cancelar con el motivo. Cada cambio lo ve
 * el cliente en la página y, si hay WhatsApp configurado, le llega el mensaje.
 */
class PanelEnLinea extends JPanel {

    private final Aplicacion app;
    private final Runnable irARuta;
    private final JPanel lista = new JPanel();
    private final Tema.Parrafo estado = Tema.parrafo(" ", 360);

    PanelEnLinea(Aplicacion app, Runnable irARuta) {
        super(new BorderLayout());
        this.app = app;
        this.irARuta = irARuta;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel arriba = new JPanel();
        arriba.setOpaque(false);
        arriba.setLayout(new BoxLayout(arriba, BoxLayout.Y_AXIS));
        JLabel t = Tema.tituloPantalla("Pedidos en línea");
        t.setAlignmentX(LEFT_ALIGNMENT);
        arriba.add(t);
        arriba.add(Tema.espacio(4));
        estado.setFont(Tema.texto(13));
        estado.setForeground(Tema.GRIS_TEXTO);
        estado.setAlignmentX(LEFT_ALIGNMENT);
        arriba.add(estado);
        arriba.add(Tema.espacio(10));
        add(arriba, BorderLayout.NORTH);
        lista.setOpaque(false);
        lista.setLayout(new BoxLayout(lista, BoxLayout.Y_AXIS));
        add(lista, BorderLayout.CENTER);

        addHierarchyListener(e -> {
            if (isShowing() && app.getSubidaTienda() != null) {
                app.getSubidaTienda().revisarPedidos();
            }
        });
        pintar();
    }

    /** Se llama cuando ya existe la subida de la tienda (la crea la ventana al final). */
    void conectar(SubidaTienda s) {
        s.alCambiarPedidos(this::pintar);
        pintar();
    }

    void pintar() {
        lista.removeAll();
        SubidaTienda s = app.getSubidaTienda();
        List<PedidoWeb> pedidos = s == null ? List.of() : s.getPedidos();
        if (s == null) {
            estado.setText("Falta la conexión con Supabase (Configuración › Conexión).");
        } else if (!s.getErrorPedidos().isEmpty()) {
            estado.setText(s.getErrorPedidos().startsWith("No hay internet") ? "Sin internet: los pedidos llegan cuando vuelva."
                    : s.getErrorPedidos());
        } else {
            estado.setText(pedidos.isEmpty() ? "No hay pedidos por atender. Llegan solos y suena el aviso."
                    : pedidos.size() == 1 ? "1 pedido por atender." : pedidos.size() + " pedidos por atender.");
        }
        for (PedidoWeb p : pedidos) {
            JComponent c = tarjeta(p);
            c.setAlignmentX(LEFT_ALIGNMENT);
            lista.add(c);
            lista.add(Tema.espacio(10));
        }
        revalidate();
        repaint();
    }

    private JComponent tarjeta(PedidoWeb p) {
        Tema.Tarjeta t = new Tema.Tarjeta();
        t.setLayout(new BoxLayout(t, BoxLayout.Y_AXIS));
        t.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        JPanel cab = new JPanel(new BorderLayout(8, 0));
        cab.setOpaque(false);
        JLabel nombre = new JLabel(p.nombre + " · " + p.codigo);
        nombre.setFont(Tema.textoFuerte(14.5f));
        nombre.setForeground(Tema.AZUL_PROFUNDO);
        cab.add(nombre, BorderLayout.CENTER);
        JLabel est = new JLabel(p.estadoTexto());
        est.setFont(Tema.textoFuerte(13));
        est.setForeground(p.estado.equals("nuevo") ? Tema.AVISO_TEXTO : Tema.EXITO_TEXTO);
        cab.add(est, BorderLayout.EAST);
        agregar(t, cab);

        linea(t, hace(p.creado) + " · Cel. " + p.celular, Tema.GRIS_TEXTO);
        linea(t, p.aDomicilio() ? "A domicilio: " + p.direccion + (p.referencia.isBlank() ? "" : " (" + p.referencia + ")")
                : "Lo recoge en la tienda", Tema.GRIS_OSCURO);
        for (PedidoWeb.Item i : p.items) {
            linea(t, i.cantidad + " × " + i.nombre + " — " + SubidaTienda.plata(i.precio * i.cantidad), Tema.GRIS_OSCURO);
        }
        if (!p.nota.isBlank()) {
            linea(t, "Nota: " + p.nota, Tema.AVISO_TEXTO);
        }
        JLabel total = new JLabel("Total " + SubidaTienda.plata(p.total)
                + (p.domicilio > 0 ? " (domicilio " + SubidaTienda.plata(p.domicilio) + ")" : ""));
        total.setFont(Tema.textoFuerte(14));
        agregar(t, total);

        String pago;
        java.awt.Color color = Tema.GRIS_OSCURO;
        if (!p.pagaAntes()) {
            pago = "Paga en " + p.metodo().getTexto().toLowerCase() + " al recibir"
                    + (p.pagaCon > p.total ? " · con " + SubidaTienda.plata(p.pagaCon) + " (cambio " + SubidaTienda.plata(p.pagaCon - p.total) + ")" : "");
        } else if (p.pagado) {
            pago = p.metodo().getTexto() + " · pago confirmado";
            color = Tema.EXITO_TEXTO;
        } else if (p.comprobante != null) {
            pago = p.metodo().getTexto() + " · llegó el comprobante: revíselo";
            color = Tema.AVISO_TEXTO;
        } else {
            pago = p.metodo().getTexto() + " · esperando el comprobante por WhatsApp";
            color = Tema.AVISO_TEXTO;
        }
        linea(t, pago, color);
        t.add(Tema.espacio(8));

        JPanel botones = new JPanel(new java.awt.GridLayout(0, 2, 6, 6)); // de a dos: siempre caben en la columna
        botones.setOpaque(false);
        botones.setAlignmentX(LEFT_ALIGNMENT);
        if (p.estado.equals("nuevo")) {
            botones.add(boton("Aceptar", true, () -> estado(p, "aceptado", null)));
        } else if (p.aDomicilio() && (p.estado.equals("aceptado"))) {
            botones.add(boton("Pasar a la ruta", true, () -> aLaRuta(p)));
        } else if (!p.aDomicilio() && (p.estado.equals("aceptado") || p.estado.equals("preparando"))) {
            botones.add(boton("Listo para recoger", true, () -> estado(p, "en_camino", null)));
        } else if (!p.aDomicilio() && p.estado.equals("en_camino")) {
            botones.add(boton("Ya lo recogió", true, () -> estado(p, "entregado", null)));
        }
        if (p.comprobante != null) {
            botones.add(boton("Ver comprobante", false, () -> verComprobante(p)));
        }
        if (p.pagaAntes() && !p.pagado) {
            botones.add(boton("Pago recibido", false, () -> pagado(p)));
        }
        if (!p.estado.equals("en_camino")) {
            botones.add(boton("Cancelar", false, () -> cancelar(p)));
        }
        botones.setMaximumSize(new Dimension(360, botones.getPreferredSize().height));
        t.add(botones);
        t.setMaximumSize(new Dimension(Integer.MAX_VALUE, t.getPreferredSize().height));
        return t;
    }

    private static void agregar(JPanel t, JComponent c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        t.add(c);
        t.add(Tema.espacio(3));
    }

    private static void linea(JPanel t, String texto, java.awt.Color color) {
        Tema.Parrafo l = Tema.parrafo(texto, 340);
        l.setFont(Tema.texto(13));
        l.setForeground(color);
        agregar(t, l);
    }

    private static Component boton(String texto, boolean principal, Runnable r) {
        Tema.Boton b = principal ? Tema.botonPrimario(texto) : Tema.botonPequeno(texto);
        if (principal) {
            b.setFont(Tema.textoFuerte(13));
            b.setBorder(BorderFactory.createEmptyBorder(7, 14, 7, 14));
        }
        b.addActionListener(e -> r.run());
        return b;
    }

    static String hace(LocalDateTime t) {
        if (t == null) {
            return "";
        }
        long m = Duration.between(t, LocalDateTime.now()).toMinutes();
        return m < 1 ? "Hace un momento" : m < 60 ? "Hace " + m + " min" : "Hace " + (m / 60) + " h";
    }

    private void estado(PedidoWeb p, String nuevo, String motivo) {
        app.getSubidaTienda().accion(t -> t.cambiarEstado(p.id, nuevo, motivo), this::resultado);
    }

    private void resultado(String error) {
        if (error != null) {
            Aviso.mostrar(this, Aviso.Tipo.ERROR, "No se pudo", error);
        }
    }

    private void pagado(PedidoWeb p) {
        int r = JOptionPane.showConfirmDialog(this, "¿Ya le llegaron los " + SubidaTienda.plata(p.total) + " por "
                + p.metodo().getTexto() + "?\nAl cliente le llega «Confirmamos tu pago».", "Pago recibido",
                JOptionPane.YES_NO_OPTION);
        if (r == JOptionPane.YES_OPTION) {
            app.getSubidaTienda().accion(t -> t.marcarPagado(p.id), this::resultado);
        }
    }

    private void cancelar(PedidoWeb p) {
        Object m = JOptionPane.showInputDialog(this, "¿Por qué se cancela? (se lo decimos al cliente por WhatsApp)",
                "Cancelar el pedido " + p.codigo, JOptionPane.QUESTION_MESSAGE, null,
                new String[]{"Se agotó un producto", "No llegamos a esa dirección", "La tienda ya cerró", "El cliente lo pidió"},
                "Se agotó un producto");
        if (m != null) {
            estado(p, "cancelado", m.toString());
        }
    }

    private void verComprobante(PedidoWeb p) {
        app.getSubidaTienda().accion(t -> {
            String u = t.enlaceComprobante(p.comprobante);
            java.awt.Desktop.getDesktop().browse(new java.net.URI(u));
        }, this::resultado);
    }

    /** La parada de la ruta para un pedido en línea (con el pin del cliente o buscando la dirección). */
    static Parada parada(Aplicacion app, PedidoWeb p) {
        Parada parada;
        if (p.lat != null && p.lon != null) {
            parada = new Parada(p.nombre, p.direccion, p.lat, p.lon);
            app.getGeo().asignarNodo(parada, Geo.x(p.lon), Geo.y(p.lat), null);
            parada.setPrecision("Marcado por el cliente en el mapa");
        } else {
            Parada encontrada = app.getGeo().ubicar(p.direccion); // IllegalArgumentException si no la encuentra
            parada = new Parada(p.nombre, encontrada.getDireccion(), encontrada.getLat(), encontrada.getLon());
            parada.setNodo(encontrada.getNodo());
            parada.setPrecision(encontrada.getPrecision());
        }
        long total = p.faltantes.isEmpty() ? p.total : p.totalEmpacado();
        parada.setTelefono(p.celular);
        parada.setValorPedido((int) Math.min(Integer.MAX_VALUE, total));
        parada.setPagado(p.pagaAntes() && p.pagado);
        parada.setPagaCon((int) Math.min(Integer.MAX_VALUE, p.pagaCon));
        parada.setProductos(p.unidades());
        if (p.venta > 0) {
            parada.setVentaNumero(p.venta);
        }
        parada.setNota((p.referencia.isBlank() ? "" : p.referencia + ". ") + (p.nota.isBlank() ? "" : p.nota + ". ")
                + "Pedido en línea " + p.codigo);
        return parada;
    }

    /** El pedido pasa a la lista de la ruta, como si lo hubieran escrito en la caja. */
    private void aLaRuta(PedidoWeb p) {
        Parada parada;
        try {
            parada = parada(app, p);
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, "No se encontró la dirección «" + p.direccion + "».\n"
                    + "Llame al cliente al " + p.celular + " o márquela en el mapa desde Ruta.", "Dirección no encontrada",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (p.pagaAntes() && !p.pagado) {
            int r = JOptionPane.showConfirmDialog(this, "Todavía no se ha confirmado el pago por " + p.metodo().getTexto()
                    + ".\n¿Lo pasa a la ruta igual? (el domiciliario cobraría " + SubidaTienda.plata(p.total) + ")",
                    "Sin pago confirmado", JOptionPane.YES_NO_OPTION);
            if (r != JOptionPane.YES_OPTION) {
                return;
            }
        }
        app.agregarEntrega(parada);
        estado(p, "preparando", null);
        irARuta.run();
    }
}
