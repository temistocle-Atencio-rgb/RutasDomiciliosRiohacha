package rutas;

import static rutas.Pruebas.revisar;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import rutas.logica.BaseCartera;
import rutas.logica.BaseGastos;
import rutas.logica.BaseTienda;
import rutas.logica.Carrito;
import rutas.logica.Catalogo;
import rutas.logica.Cobro;
import rutas.modelo.Pago;
import rutas.modelo.MetodoPago;
import rutas.modelo.Producto;
import rutas.modelo.Rol;
import rutas.modelo.Unidad;

/** Pruebas de cuentas por cobrar (fiado), por pagar (compras y gastos a crédito), presupuesto y flujo. */
public class PruebasCartera {

    static void correr() throws Exception {
        System.out.println("--- Cartera, presupuesto y flujo de efectivo ---");
        probarSinBase();
        probarTodo();
    }

    interface Accion {
        void hacer() throws Exception;
    }

    private static boolean lanza(String pedazo, Accion r) {
        try {
            r.hacer();
            return false;
        } catch (Exception e) {
            return e.getMessage() != null && e.getMessage().contains(pedazo);
        }
    }

    private static void probarSinBase() {
        revisar("rangos: 0 días por vencer, 1-30, 31-60, 61-90 y más de 90",
                BaseCartera.Rango.de(0) == BaseCartera.Rango.POR_VENCER && BaseCartera.Rango.de(30) == BaseCartera.Rango.D1_30
                && BaseCartera.Rango.de(31) == BaseCartera.Rango.D31_60 && BaseCartera.Rango.de(90) == BaseCartera.Rango.D61_90
                && BaseCartera.Rango.de(91) == BaseCartera.Rango.MAS_90);
        revisar("plazo del proveedor: «Crédito 15 días» = 15, contado = 0",
                BaseCartera.diasDe("Crédito 15 días") == 15 && BaseCartera.diasDe("Contado") == 0);
        BaseCartera.Fiado f = new BaseCartera.Fiado();
        f.cliente = "Ana";
        f.vence = LocalDate.now().plusDays(30);
        f.cupo = 50_000;
        f.debeAntes = 40_000;
        revisar("fiado: si se pasa del cupo se rechaza diciendo cuánto debe",
                lanza("cupo de fiado", () -> BaseCartera.validarFiado(f, 20_000, LocalDate.now())));
        BaseCartera.Fiado sin = new BaseCartera.Fiado();
        sin.vence = LocalDate.now();
        revisar("fiado: sin cliente se rechaza", lanza("cliente", () -> BaseCartera.validarFiado(sin, 1000, LocalDate.now())));
    }

    private static List<Pago> pago(MetodoPago m, long v) {
        List<Pago> l = new ArrayList<>();
        l.add(new Pago(m, v));
        return l;
    }

    private static void probarTodo() throws Exception {
        try (BaseTienda bd = PruebasCaja.baseTemporal()) {
            long u = bd.crearUsuario("jefe", "Jefe", Rol.ADMIN, "clave123".toCharArray()).getId();
            Catalogo cat = new Catalogo(bd);
            Producto arroz = cat.crear(new Producto(0, null, "Arroz", Unidad.UNIDAD, 10_000, 8000, 100_000, 0, null, true));
            BaseCartera bc = new BaseCartera(bd);
            LocalDate hoy = LocalDate.now();

            // ---------- fiado ----------
            Carrito c = new Carrito();
            c.agregar(arroz);
            c.agregar(arroz);
            BaseCartera.Fiado f = new BaseCartera.Fiado();
            f.cliente = "Erika Galindo";
            f.documento = "1938948934";
            f.celular = "3001234567";
            f.vence = hoy.plusDays(30);
            long n = bd.cobrarFiado(null, u, c, f);
            List<BaseCartera.Documento> l = bc.cobrar(hoy, "");
            revisar("fiado: la venta queda con número, baja el stock y aparece por cobrar (por vencer)",
                    n == 1 && bd.buscarProductoPorId(arroz.getId()).getStock() == 98_000 && l.size() == 1
                    && l.get(0).saldo() == 20_000 && l.get(0).rango() == BaseCartera.Rango.POR_VENCER
                    && l.get(0).identificacion.equals("1938948934"));
            revisar("fiado: no entra como plata en las ventas cobradas (sin pagos)", bd.pagosDeVenta(n).isEmpty());
            revisar("fiado: lo que debe el cliente", bc.debe("ERIKA GALINDO") == 20_000);
            revisar("fiado: el buscador encuentra por nombre y por cédula",
                    bc.cobrar(hoy, "erika").size() == 1 && bc.cobrar(hoy, "193894").size() == 1 && bc.cobrar(hoy, "pedro").isEmpty());
            revisar("fiado: a 45 días de corte ya va vencido 15 días (1 a 30)",
                    bc.cobrar(hoy.plusDays(45), "").get(0).rango() == BaseCartera.Rango.D1_30
                    && bc.cobrar(hoy.plusDays(45), "").get(0).diasVencido == 15);
            long id = l.get(0).id;
            revisar("abono: más que el saldo se rechaza", lanza("más que el saldo",
                    () -> bc.abonar(BaseCartera.Tipo.FIADO, id, hoy, 20_001, MetodoPago.EFECTIVO, true, u)));
            revisar("abono: sin método se rechaza", lanza("método", () -> bc.abonar(BaseCartera.Tipo.FIADO, id, hoy, 1000, null, true, u)));
            revisar("abono: con fecha futura se rechaza", lanza("no han llegado",
                    () -> bc.abonar(BaseCartera.Tipo.FIADO, id, hoy.plusDays(1), 1000, MetodoPago.EFECTIVO, true, u)));
            BaseCartera.Abono a1 = bc.abonar(BaseCartera.Tipo.FIADO, id, hoy, 5000, MetodoPago.EFECTIVO, true, u);
            bc.abonar(BaseCartera.Tipo.FIADO, id, hoy, 3000, MetodoPago.NEQUI, false, u);
            revisar("abono: baja el saldo", bc.cobrar(hoy, "").get(0).saldo() == 12_000
                    && bc.abonos(BaseCartera.Tipo.FIADO, id).size() == 2);
            revisar("anular abono: sin motivo no", lanza("por qué", () -> bc.anularAbono(BaseCartera.Tipo.FIADO, a1.id, u, "")));
            bc.anularAbono(BaseCartera.Tipo.FIADO, a1.id, u, "se marcó dos veces");
            revisar("anular abono: el saldo vuelve a subir y el abono queda en la lista marcado",
                    bc.cobrar(hoy, "").get(0).saldo() == 17_000 && bc.abonos(BaseCartera.Tipo.FIADO, id).get(0).anulado);
            revisar("anular abono: dos veces no", lanza("ya estaba", () -> bc.anularAbono(BaseCartera.Tipo.FIADO, a1.id, u, "otra vez")));
            bc.abonar(BaseCartera.Tipo.FIADO, id, hoy, 17_000, MetodoPago.EFECTIVO, false, u);
            revisar("fiado pagado completo: ya no sale por cobrar", bc.cobrar(hoy, "").isEmpty());

            // ---------- compra a crédito ----------
            rutas.logica.Compra k = new rutas.logica.Compra();
            rutas.logica.Compra.Linea lk = k.agregar(arroz, 0);
            lk.setCantidad(10_000);
            lk.setBase(8000);
            k.setProveedor("Morral SAS");
            long nc = bd.registrarCompra(null, u, k);
            bc.formaDePagoCompra(nc, true, null, hoy.plusDays(15));
            List<BaseCartera.Documento> p = bc.pagar(hoy, "");
            revisar("compra a crédito: sale por pagar con su total", p.size() == 1 && p.get(0).tipo == BaseCartera.Tipo.COMPRA
                    && p.get(0).saldo() == k.getTotal() && p.get(0).nombre.equals("Morral SAS"));
            bc.abonar(BaseCartera.Tipo.COMPRA, p.get(0).id, hoy, 30_000, MetodoPago.EFECTIVO, true, u);

            // ---------- gasto a crédito (vence) ----------
            BaseGastos bg = new BaseGastos(bd);
            BaseGastos.Gasto g = new BaseGastos.Gasto();
            g.fecha = hoy.atTime(9, 0);
            g.categoriaId = bg.categorias(true).get(0).id;
            g.valor = 500_000;
            g.credito = true;
            g.usuarioId = u;
            g.descripcion = "Arriendo";
            bg.registrar(g);
            revisar("gasto a crédito: sin fecha queda a 30 días", g.vence.equals(hoy.plusDays(30)));
            BaseGastos.Abono ga = bg.abonar(g.id, 200_000, MetodoPago.TRANSFERENCIA, false, u);
            p = bc.pagar(hoy, "");
            revisar("por pagar: compras y gastos juntos", p.size() == 2 && BaseCartera.edades(p).total
                    == k.getTotal() - 30_000 + 300_000);
            bc.anularAbono(BaseCartera.Tipo.GASTO, ga.id, u, "era de otro gasto");
            revisar("anular abono de gasto: vuelve el saldo (también en Gastos)", bg.gasto(g.id).saldo() == 500_000);
            revisar("orden con el montículo: primero el que lleva más días vencido",
                    bc.pagar(hoy.plusDays(40), "").get(0).tipo == BaseCartera.Tipo.COMPRA);

            // ---------- presupuesto ----------
            YearMonth mes = YearMonth.now();
            bc.asignar(g.categoriaId, mes, 400_000, true);
            BaseCartera.Presupuesto pr = bc.deCategoria(g.categoriaId, mes);
            revisar("presupuesto: todos los meses; el gasto de 500.000 lo pasa (125 %)",
                    pr != null && pr.todosLosMeses && pr.gastado == 500_000 && Math.round(pr.usado()) == 125 && pr.queda() == -100_000);
            bc.asignar(g.categoriaId, mes.plusMonths(1), 600_000, false);
            revisar("presupuesto: un mes distinto no cambia los demás",
                    bc.deCategoria(g.categoriaId, mes.plusMonths(1)).valor == 600_000
                    && bc.deCategoria(g.categoriaId, mes.plusMonths(2)).valor == 400_000);
            long otra = bg.categorias(true).get(1).id;
            bc.asignar(otra, mes, 50_000, false);
            revisar("copiar del mes anterior", bc.copiarMesAnterior(mes.plusMonths(1)) == 1
                    && bc.deCategoria(otra, mes.plusMonths(1)).valor == 50_000);
            revisar("presupuesto: va primero la categoría más pasada", bc.presupuesto(mes).get(0).categoriaId == g.categoriaId);

            // ---------- flujo de efectivo ----------
            Carrito c2 = new Carrito();
            c2.agregar(arroz);
            bd.cobrar(null, u, null, c2, pago(MetodoPago.NEQUI, 10_000), Cobro.calcular(10_000, pago(MetodoPago.NEQUI, 10_000)));
            List<BaseCartera.Movimiento> fl = bc.flujo(hoy.atStartOfDay(), hoy.atTime(23, 59));
            long entra = 0, sale = 0;
            for (BaseCartera.Movimiento m : fl) {
                entra += m.entradas;
                sale += m.salidas;
            }
            // entra: venta Nequi 10.000 + abonos de fiado 3.000 + 17.000 (el anulado no) = 30.000
            // sale: abono a la compra 30.000 (la compra a crédito y el gasto a crédito no salen hasta que se abonan)
            revisar("flujo: solo la plata que se movió (lo fiado y lo de crédito no, lo anulado tampoco)",
                    entra == 30_000 && sale == 30_000);
            long efectivo = 0;
            for (BaseCartera.Movimiento m : BaseCartera.porMedio(fl)) {
                if (m.concepto.equals(MetodoPago.EFECTIVO.toString())) {
                    efectivo = m.neto();
                }
            }
            revisar("flujo por medio: el efectivo neto (17.000 que entró - 30.000 del abono a la compra)", efectivo == -13_000);
            revisar("flujo: fechas al revés se rechazan", lanza("antes de la inicial",
                    () -> bc.flujo(LocalDateTime.now(), LocalDateTime.now().minusDays(1))));
        }
    }
}
