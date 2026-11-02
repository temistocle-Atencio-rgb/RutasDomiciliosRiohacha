package rutas;

import static rutas.Pruebas.revisar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import rutas.logica.BaseOrdenes;
import rutas.logica.BaseTienda;
import rutas.logica.Catalogo;
import rutas.logica.OrdenCompra;
import rutas.modelo.Producto;
import rutas.modelo.Proveedor;
import rutas.modelo.Rol;
import rutas.modelo.Unidad;
import rutas.modelo.Usuario;

/** Pruebas de proveedores y ordenes de compra. */
public class PruebasOrdenes {

    static void correr() throws Exception {
        System.out.println("--- Proveedores y ordenes de compra ---");
        probarDigito();
        probarProveedores();
        probarOrdenes();
    }

    private static boolean lanza(String pedazo, Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return e.getMessage() != null && e.getMessage().contains(pedazo);
        }
    }

    private static void probarDigito() {
        revisar("DV: el NIT de la DIAN 800197268 da 4", Proveedor.digitoVerificacion("800.197.268").equals("4"));
        revisar("DV: residuo 0 y 1 quedan igual, vacío si no hay número",
                Proveedor.digitoVerificacion("").isEmpty() && Proveedor.digitoVerificacion("890903938").equals("8"));
        Proveedor p = new Proveedor();
        p.vendedor = "Carlos";
        p.telefono = "6057271000";
        revisar("proveedor: la orden va al celular del vendedor y si no hay, al de la empresa",
                p.celularParaPedidos().equals("6057271000"));
        p.celularVendedor = "3001234567";
        revisar("proveedor: con celular del vendedor, a ese", p.celularParaPedidos().equals("3001234567"));
    }

    private static void probarProveedores() throws Exception {
        try (BaseTienda bd = PruebasCaja.baseTemporal()) {
            BaseOrdenes bo = new BaseOrdenes(bd);
            Proveedor p = new Proveedor();
            p.nombre = "  Postobón   S.A. ";
            p.documento = "890.903.939";
            p.vendedor = "Carlos";
            bo.guardarProveedor(p);
            List<Proveedor> l = bo.listarProveedores(true);
            revisar("proveedor: se guarda con el nombre limpio, el NIT sin puntos y su DV",
                    l.size() == 1 && l.get(0).nombre.equals("Postobón S.A.") && l.get(0).documento.equals("890903939")
                    && l.get(0).dv.equals(Proveedor.digitoVerificacion("890903939"))
                    && l.get(0).departamento.equals("GUAJIRA") && l.get(0).municipio.equals("RIOHACHA"));
            Proveedor otro = new Proveedor();
            otro.nombre = "POSTOBON S.A.";
            revisar("proveedor: el mismo nombre (sin tildes ni mayúsculas) no se repite",
                    lanza("Ya existe", () -> guardar(bo, otro)));
            Proveedor otro2 = new Proveedor();
            otro2.nombre = "Otra";
            otro2.documento = "890903939";
            revisar("proveedor: el mismo documento no se repite", lanza("Ya existe", () -> guardar(bo, otro2)));
            Proveedor malo = new Proveedor();
            malo.nombre = "Coca-Cola";
            malo.correo = "ventas@";
            revisar("proveedor: correo mal escrito se rechaza", lanza("Revise el correo", () -> guardar(bo, malo)));
            Proveedor sin = new Proveedor();
            revisar("proveedor: sin nombre se rechaza", lanza("nombre del proveedor", () -> guardar(bo, sin)));
            p.activo = false;
            p.correo = "pedidos@postobon.com";
            bo.guardarProveedor(p);
            revisar("proveedor: se edita y se desactiva (sale de la lista de activos)",
                    bo.listarProveedores(true).isEmpty() && bo.listarProveedores(false).get(0).correo.equals("pedidos@postobon.com"));
        }
    }

    private static void guardar(BaseOrdenes bo, Proveedor p) {
        try {
            bo.guardarProveedor(p);
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void probarOrdenes() throws Exception {
        try (BaseTienda bd = PruebasCaja.baseTemporal()) {
            Usuario u = bd.crearUsuario("ana", "Ana", Rol.ADMIN, "secreto1".toCharArray());
            Catalogo cat = new Catalogo(bd);
            Producto gas = cat.crear(new Producto(0, "7702090000016", "Gaseosa", Unidad.UNIDAD, 1500, 1000, 5000, 10000,
                    null, true));
            Producto arroz = cat.crear(new Producto(0, "7702090000023", "Arroz", Unidad.UNIDAD, 3500, 2900, 40000, 10000,
                    null, true));
            BaseOrdenes bo = new BaseOrdenes(bd);
            revisar("ordenes: «lo que se está acabando» trae los que están en su mínimo",
                    BaseOrdenes.porAcabarse(cat).size() == 1 && BaseOrdenes.porAcabarse(cat).get(0).getId() == gas.getId());

            Proveedor pv = new Proveedor();
            pv.nombre = "Postobón";
            bo.guardarProveedor(pv);
            OrdenCompra o = new OrdenCompra();
            revisar("orden: sin proveedor no se procesa", lanza("Escoja el proveedor", o::validarOrden));
            o.setProveedor(pv.nombre);
            o.setProveedorId(pv.id);
            revisar("orden: sin productos no se procesa", lanza("Agregue al menos un producto a la orden", o::validarOrden));
            o.agregar(gas, 0).setCantidad(24_000);
            o.agregar(arroz, 1900).setCantidad(10_000);
            o.setFechaEstimada(LocalDate.now().minusDays(2));
            o.setFecha(LocalDate.now());
            revisar("orden: la fecha estimada no puede ser antes de la orden",
                    lanza("fecha estimada", o::validarOrden));
            o.setFecha(LocalDate.now().minusDays(3));
            o.validarOrden();
            long bruto = o.getBruto();
            o.setConRetencion(true);
            revisar("orden: retención del 2,5 % sobre la base y total menos retención",
                    o.getRetencion() == Math.round(bruto * 0.025) && o.getTotalOrden() == o.getTotal() - o.getRetencion());
            long n = bo.guardarOrden(o, u.getId());
            revisar("orden: se guarda en borrador con número 1", n == 1 && o.getId() > 0);
            o.setEstado(OrdenCompra.Estado.PENDIENTE);
            bo.guardarOrden(o, u.getId());
            List<String> avisos = new ArrayList<>();
            OrdenCompra c = bo.cargarOrden(o.getId(), cat, avisos);
            revisar("orden: se carga igual (productos, orden, retención, fechas, responsable)",
                    c.getLineas().size() == 2 && c.getLineas().get(0).getProducto().getId() == arroz.getId()
                    && c.getLineas().get(1).getCantidad() == 24_000 && c.isConRetencion() && c.getTotalOrden() == o.getTotalOrden()
                    && c.getEstado() == OrdenCompra.Estado.PENDIENTE && c.getResponsable().equals("Ana")
                    && c.getFechaEstimada().equals(o.getFechaEstimada()) && avisos.isEmpty());
            List<BaseOrdenes.OrdenResumen> l = bo.listarOrdenes(null, BaseOrdenes.FiltroFecha.CREACION, null, null, "");
            revisar("orden: sale en la lista, marcada como atrasada", l.size() == 1 && l.get(0).atrasada(LocalDate.now())
                    && l.get(0).proveedor.equals("Postobón") && l.get(0).total == o.getTotalOrden());
            revisar("orden: filtros de estado, búsqueda y fecha",
                    bo.listarOrdenes(OrdenCompra.Estado.RECIBIDA, null, null, null, "").isEmpty()
                    && bo.listarOrdenes(null, null, null, null, "posto").size() == 1
                    && bo.listarOrdenes(null, null, null, null, "0001").size() == 1
                    && bo.listarOrdenes(null, BaseOrdenes.FiltroFecha.ESTIMADA, LocalDate.now(), LocalDate.now(), "").isEmpty());
            revisar("orden: fechas al revés se rechazan", lanza("antes de la inicial", () -> {
                try {
                    bo.listarOrdenes(null, null, LocalDate.now(), LocalDate.now().minusDays(1), "");
                } catch (java.sql.SQLException e) {
                    throw new RuntimeException(e);
                }
            }));
            OrdenCompra.Estado e1 = bo.registrarRecepcion(o.getId(), Map.of(gas.getId(), 20_000L), 7);
            OrdenCompra r1 = bo.cargarOrden(o.getId(), cat, null);
            revisar("recibir: llegó una parte → Recibida en parte, con lo recibido por línea",
                    e1 == OrdenCompra.Estado.PARCIAL && r1.getLineas().get(1).getRecibida() == 20_000
                    && r1.getLineas().get(1).getPendiente() == 4_000 && r1.getCompras().equals("7"));
            revisar("recibir: en parte ya no se puede editar", lanza("ya no se puede cambiar", () -> {
                try {
                    r1.setEstado(OrdenCompra.Estado.PENDIENTE);
                    bo.guardarOrden(r1, u.getId());
                } catch (java.sql.SQLException ex) {
                    throw new RuntimeException(ex);
                }
            }));
            OrdenCompra.Estado e2 = bo.registrarRecepcion(o.getId(), Map.of(gas.getId(), 4_000L, arroz.getId(), 10_000L), 8);
            revisar("recibir: llegó lo que faltaba → Recibida, con las dos compras",
                    e2 == OrdenCompra.Estado.RECIBIDA && bo.cargarOrden(o.getId(), cat, null).getCompras().equals("7, 8"));
            revisar("recibida: ya no se cancela ni se recibe otra vez",
                    lanza("ya estaba recibida", () -> cancelar(bo, o.getId()))
                    && lanza("no está pendiente", () -> {
                        try {
                            bo.registrarRecepcion(o.getId(), Map.of(gas.getId(), 1000L), 9);
                        } catch (java.sql.SQLException ex) {
                            throw new RuntimeException(ex);
                        }
                    }));
            OrdenCompra o2 = new OrdenCompra();
            o2.setProveedor("Postobón");
            o2.agregar(gas, 0);
            long n2 = bo.guardarOrden(o2, u.getId());
            cancelar(bo, o2.getId());
            revisar("orden: el número sigue (2) y se puede cancelar",
                    n2 == 2 && bo.cargarOrden(o2.getId(), cat, null).getEstado() == OrdenCompra.Estado.CANCELADA);
            pv.nombre = "Postobón S.A.";
            bo.guardarProveedor(pv);
            revisar("proveedor renombrado: las órdenes cerradas guardan el nombre de entonces",
                    bo.cargarOrden(o.getId(), cat, null).getProveedor().equals("Postobón"));
        }
    }

    private static void cancelar(BaseOrdenes bo, long id) {
        try {
            bo.cancelarOrden(id);
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
