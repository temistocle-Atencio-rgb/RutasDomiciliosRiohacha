package rutas.vista;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import rutas.logica.Carrito;
import rutas.logica.Cobro;
import rutas.logica.TiendaEnLinea;
import rutas.modelo.LineaVenta;
import rutas.modelo.Pago;
import rutas.modelo.Parada;
import rutas.modelo.PedidoWeb;
import rutas.modelo.Producto;

/**
 * Lo que el encargado de pedidos (pedidos.html) le pide a la caja. Se hace aquí porque aquí están las rutas,
 * el inventario, la impresora y el correo. Todo corre en el hilo de la pantalla, sin ventanas: el cajero
 * puede seguir vendiendo (solo sale un aviso pequeño).
 */
class TrabajosCaja {

    private final Aplicacion app;
    private final java.util.function.Supplier<PanelPedidos> pedidos;
    private final java.util.function.BiConsumer<String, String> avisar;

    TrabajosCaja(Aplicacion app, java.util.function.Supplier<PanelPedidos> pedidos,
            java.util.function.BiConsumer<String, String> avisar) {
        this.app = app;
        this.pedidos = pedidos;
        this.avisar = avisar;
    }

    /** Hace el trabajo (en el hilo de la pantalla). Devuelve null si salió bien o el problema. */
    String hacer(TiendaEnLinea.Trabajo t, PedidoWeb[] pedidosDelTrabajo, Map<String, rutas.modelo.Domiciliario> porNube) {
        try {
            switch (t.tipo) {
                case "vender":
                    return vender(pedidosDelTrabajo[0]);
                case "imprimir":
                    return imprimir(pedidosDelTrabajo[0]);
                case "asignar":
                    return asignar(pedidosDelTrabajo, porNube.get(String.valueOf(t.datos.get("repartidor"))));
                default:
                    return "Trabajo desconocido: " + t.tipo;
            }
        } catch (RuntimeException e) {
            rutas.logica.Ubicaciones.registrarError(e);
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    /** Las líneas de la venta con el precio del pedido (sin lo que faltó). */
    private Carrito carrito(PedidoWeb p) {
        Carrito c = new Carrito();
        for (PedidoWeb.Item i : p.items) {
            if (p.faltantes.contains(i.id)) {
                continue;
            }
            Producto prod = app.getCatalogo() == null ? null : app.getCatalogo().buscarPorId(i.id);
            if (prod == null) {
                throw new IllegalStateException("«" + i.nombre + "» ya no existe en DomiRuta");
            }
            LineaVenta l = c.agregar(prod, i.cantidad * 1000L);
            int k = c.getLineas().indexOf(l);
            if (l.getPrecio() != i.precio) {
                c.cambiarPrecio(k, i.precio); // se le cobra lo que vio en la página
            }
        }
        if (c.estaVacio()) {
            throw new IllegalStateException("No quedó ningún producto para vender");
        }
        c.setNota("Pedido en línea " + p.codigo + (p.aDomicilio() ? " · domicilio " + SubidaTienda.plata(p.domicilio) : " · lo recoge"));
        return c;
    }

    /** Empacado: la venta queda registrada (baja el inventario y queda en el cuadre con su medio de pago). */
    private String vender(PedidoWeb p) {
        if (p.venta > 0) {
            return null; // ya estaba
        }
        if (app.getTienda() == null || app.getSesion() == null) {
            return "La caja no tiene la tienda abierta";
        }
        Carrito c = carrito(p);
        List<Pago> pagos = List.of(new Pago(p.metodo(), c.getTotal()));
        Cobro.Resultado r = Cobro.calcular(c.getTotal(), pagos);
        long numero;
        try {
            numero = app.getTienda().cobrar(null, app.getSesion().getUsuario().getId(), p.nombre, c, pagos, r);
        } catch (java.sql.SQLException e) {
            return "No se pudo guardar la venta: " + e.getMessage();
        } catch (IllegalStateException | IllegalArgumentException e) {
            return e.getMessage(); // «Ya no hay suficiente Arroz Diana: quedan 0 und» (se vendió en la caja)
        }
        p.venta = numero;
        try {
            app.getCatalogo().recargar();
        } catch (java.sql.SQLException e) {
            rutas.logica.Ubicaciones.registrarError(e);
        }
        app.tiendaCambio(); // lo agotado se ve en la página enseguida
        avisar.accept("Venta del pedido en línea " + p.codigo, "Recibo " + Comprobante.numeroTexto(numero) + " · "
                + SubidaTienda.plata(c.getTotal()) + " · " + p.metodo().getTexto());
        return "venta:" + numero;
    }

    /** El recibo por la impresora de la tienda, sin preguntar y sin abrir el cajón. */
    private String imprimir(PedidoWeb p) {
        rutas.logica.AjustesRecibo a = app.getAjustesRecibo();
        if (a.impresora.isBlank() || Impresion.buscar(a.impresora) == null) {
            return "La caja no tiene una impresora fija: escójala en Configuración › Impresora";
        }
        a.abrirCajon = false; // el cajón solo se abre cuando cobra el cajero
        Carrito c = carrito(p);
        List<Pago> pagos = p.pagaAntes() && !p.pagado ? new ArrayList<>() : List.of(new Pago(p.metodo(), c.getTotal()));
        Comprobante comp = new Comprobante(app.getNegocio(), p.venta, LocalDateTime.now(), "Pedido en línea", p.nombre,
                c.getLineas(), c.getTotal(), pagos, 0, 0);
        comp.nota = "PEDIDO EN LÍNEA #" + p.codigo + " · Cel. " + p.celular
                + (p.aDomicilio() ? " · " + p.direccion : " · Lo recoge en la tienda")
                + (p.pagaAntes() && !p.pagado ? " · PAGO SIN CONFIRMAR" : "");
        comp.ajustes = a;
        final String[] fin = {null};
        Impresion.imprimirAparte(comp, comp.alto(), a, "Pedido en línea " + p.codigo, aviso -> {
            if (aviso != null && !aviso.equals("No se imprimió")) {
                avisar.accept("No se imprimió el pedido " + p.codigo, aviso);
            }
        });
        avisar.accept("Se imprimió el pedido en línea " + p.codigo, p.nombre);
        return fin[0];
    }

    /** Arma la salida con la ruta y la manda al domiciliario escogido en la página. */
    private String asignar(PedidoWeb[] ps, rutas.modelo.Domiciliario d) {
        if (d == null) {
            return "Ese domiciliario no está en DomiRuta (Configuración › Domiciliarios)";
        }
        PanelPedidos panel = pedidos.get();
        if (panel == null) {
            return "La caja no tiene abierta la parte de domicilios";
        }
        List<Parada> paradas = new ArrayList<>();
        for (PedidoWeb p : ps) {
            if (p == null) {
                return "Uno de los pedidos ya no existe";
            }
            try {
                paradas.add(PanelEnLinea.parada(app, p));
            } catch (IllegalArgumentException e) {
                return "No se encontró la dirección de " + p.nombre + " («" + p.direccion + "»): márquela en la caja";
            }
        }
        String r = panel.despacharRemoto(paradas, d);
        if (r == null) {
            avisar.accept("Salió " + d.nombre + " con " + ps.length + (ps.length == 1 ? " pedido en línea" : " pedidos en línea"),
                    "Lo asignó el encargado desde la página");
        }
        return r;
    }
}
