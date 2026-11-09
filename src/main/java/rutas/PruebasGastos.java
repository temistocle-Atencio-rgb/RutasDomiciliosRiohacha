package rutas;

import static rutas.Pruebas.revisar;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import rutas.logica.BaseGastos;
import rutas.logica.BaseOrdenes;
import rutas.logica.BaseTienda;
import rutas.modelo.MetodoPago;
import rutas.modelo.Proveedor;
import rutas.modelo.Rol;
import rutas.modelo.Usuario;

/** Pruebas de gastos: categorías, contado y crédito, abonos, caja, anular, fijos e informe. */
public class PruebasGastos {

    static void correr() throws Exception {
        System.out.println("--- Gastos ---");
        probarFechas();
        probarTodo();
    }

    private static boolean lanza(String pedazo, Accion r) {
        try {
            r.hacer();
            return false;
        } catch (Exception e) {
            return e.getMessage() != null && e.getMessage().contains(pedazo);
        }
    }

    interface Accion {
        void hacer() throws Exception;
    }

    private static void probarFechas() {
        revisar("fijo: el 31 en noviembre se paga el 30 y en febrero bisiesto el 29",
                BaseGastos.fechaEnMes(YearMonth.of(2026, 11), 31).equals(LocalDate.of(2026, 11, 30))
                && BaseGastos.fechaEnMes(YearMonth.of(2028, 2), 31).equals(LocalDate.of(2028, 2, 29)));
        revisar("fijo: primer pago este mes si el día no ha pasado, si no el siguiente",
                BaseGastos.primerPago(LocalDate.of(2026, 11, 9), 15).equals(LocalDate.of(2026, 11, 15))
                && BaseGastos.primerPago(LocalDate.of(2026, 11, 9), 5).equals(LocalDate.of(2026, 12, 5))
                && BaseGastos.primerPago(LocalDate.of(2026, 11, 9), 9).equals(LocalDate.of(2026, 11, 9)));
    }

    private static BaseGastos.Gasto gasto(long cat, long valor, boolean credito, MetodoPago m, boolean caja, long u,
                                          LocalDateTime f) {
        BaseGastos.Gasto g = new BaseGastos.Gasto();
        g.fecha = f;
        g.categoriaId = cat;
        g.valor = valor;
        g.credito = credito;
        g.metodo = m;
        g.saleCaja = caja;
        g.usuarioId = u;
        g.descripcion = "prueba " + valor;
        return g;
    }

    private static void probarTodo() throws Exception {
        try (BaseTienda bd = PruebasCaja.baseTemporal()) {
            Usuario ana = bd.crearUsuario("ana", "Ana", Rol.ADMIN, "secreto1".toCharArray());
            BaseGastos bg = new BaseGastos(bd);
            List<BaseGastos.Categoria> cats = bg.categorias(true);
            revisar("categorías: una tienda nueva trae 9 (arriendo, servicios, nómina...)",
                    cats.size() == 9 && cats.stream().anyMatch(c -> c.nombre.equals("ARRIENDO") && c.concepto.equals("5005")));
            BaseGastos.Categoria aseo = new BaseGastos.Categoria();
            aseo.nombre = "  aseo   y cafetería ";
            bg.guardarCategoria(aseo);
            revisar("categoría: se guarda en mayúsculas y sin espacios de más; sin DIAN dice «Sin clasificar»",
                    aseo.nombre.equals("ASEO Y CAFETERÍA") && aseo.textoConcepto().equals("Sin clasificar"));
            BaseGastos.Categoria otra = new BaseGastos.Categoria();
            otra.nombre = "Aseo y cafeteria";
            revisar("categoría: el mismo nombre (sin tildes) no se repite", lanza("Ya hay", () -> bg.guardarCategoria(otra)));
            revisar("categoría sin usar: se borra", bg.quitarCategoria(aseo.id) && bg.categorias(false).size() == 9);
            long arriendo = cats.stream().filter(c -> c.nombre.equals("ARRIENDO")).findFirst().get().id;
            long servicios = cats.stream().filter(c -> c.nombre.equals("SERVICIOS PÚBLICOS")).findFirst().get().id;

            LocalDateTime hoy = LocalDate.now().atTime(10, 0);
            revisar("gasto: de contado sin método de pago se rechaza", lanza("método de pago",
                    () -> bg.registrar(gasto(arriendo, 1000, false, null, false, ana.getId(), hoy))));
            revisar("gasto: sin valor se rechaza", lanza("valor",
                    () -> bg.registrar(gasto(arriendo, 0, false, MetodoPago.EFECTIVO, false, ana.getId(), hoy))));
            revisar("gasto: con fecha de días que no han llegado se rechaza", lanza("no han llegado",
                    () -> bg.registrar(gasto(arriendo, 10, false, MetodoPago.EFECTIVO, false, ana.getId(), hoy.plusDays(5)))));

            BaseGastos.Gasto a = gasto(arriendo, 800_000, false, MetodoPago.EFECTIVO, true, ana.getId(), hoy);
            bg.registrar(a);
            BaseGastos.Gasto b = gasto(servicios, 120_000, false, MetodoPago.NEQUI, true, ana.getId(), hoy.plusMinutes(5));
            bg.registrar(b);
            BaseGastos.Gasto c = gasto(servicios, 300_000, true, null, false, ana.getId(), hoy.plusMinutes(10));
            bg.registrar(c);
            revisar("gasto: lleva número seguido; Nequi no sale de la caja aunque se marque",
                    a.numero == 1 && b.numero == 2 && c.numero == 3 && a.saleCaja && !b.saleCaja);

            BaseGastos.Filtro f = new BaseGastos.Filtro();
            f.desde = LocalDate.now().atStartOfDay();
            f.hasta = LocalDate.now().atTime(23, 59);
            List<BaseGastos.Gasto> l = bg.gastos(f);
            revisar("historial: del más nuevo al más viejo, con estado PAGADO / POR PAGAR",
                    l.size() == 3 && l.get(0).numero == 3 && l.get(0).estado().equals("POR PAGAR")
                    && l.get(2).estado().equals("PAGADO") && l.get(0).saldo() == 300_000);
            f.credito = Boolean.TRUE;
            revisar("historial: filtro crédito", bg.gastos(f).size() == 1);
            f.credito = null;
            f.metodo = MetodoPago.NEQUI;
            revisar("historial: filtro método de pago", bg.gastos(f).size() == 1);
            f.metodo = null;

            revisar("abono: no puede pasar del saldo", lanza("más que el saldo",
                    () -> bg.abonar(c.id, 300_001, MetodoPago.EFECTIVO, true, ana.getId())));
            revisar("abono: a un gasto de contado se rechaza", lanza("contado",
                    () -> bg.abonar(a.id, 1000, MetodoPago.EFECTIVO, true, ana.getId())));
            bg.abonar(c.id, 100_000, MetodoPago.EFECTIVO, true, ana.getId());
            BaseGastos.Gasto c1 = bg.gasto(c.id);
            revisar("abono: baja el saldo y lo de efectivo cuenta como egreso de caja",
                    c1.abonado == 100_000 && c1.saldo() == 200_000 && c1.egresoCaja() == 100_000 && c1.estado().equals("POR PAGAR"));
            bg.abonar(c.id, 200_000, MetodoPago.TRANSFERENCIA, true, ana.getId());
            BaseGastos.Gasto c2 = bg.gasto(c.id);
            revisar("abono: con el saldo en cero queda PAGADO (la transferencia no sale de la caja)",
                    c2.saldo() == 0 && c2.estado().equals("PAGADO") && c2.egresoCaja() == 100_000);
            revisar("abonos: el historial los trae y busca por proveedor o número",
                    bg.abonos(f.desde, f.hasta, 0, null, "").size() == 2
                    && bg.abonos(f.desde, f.hasta, 0, MetodoPago.EFECTIVO, "").size() == 1
                    && bg.abonos(f.desde, f.hasta, 0, null, "3").size() == 2
                    && bg.abonos(f.desde, f.hasta, 0, null, "xyz").isEmpty());
            revisar("caja: salió el arriendo en efectivo y el abono en efectivo",
                    bg.salioDeCaja(f.desde, f.hasta) == 900_000);

            revisar("anular: sin motivo se rechaza", lanza("por qué", () -> bg.anular(b.id, ana.getId(), "")));
            bg.anular(b.id, ana.getId(), "se registró dos veces");
            BaseGastos.Gasto b1 = bg.gasto(b.id);
            revisar("anular: queda ANULADO con su motivo, no se borra",
                    b1.anulado && b1.estado().equals("ANULADO") && b1.motivo.equals("se registró dos veces")
                    && bg.gastos(f).size() == 3);
            revisar("anular: dos veces no", lanza("ya estaba", () -> bg.anular(b.id, ana.getId(), "otra vez")));
            revisar("categoría con gastos: no se borra, queda inactiva",
                    !bg.quitarCategoria(servicios) && bg.categorias(true).size() == 8 && bg.categorias(false).size() == 9);

            List<BaseGastos.Total> t = bg.porCategoria(f.desde, f.hasta, 0, 0, null);
            revisar("informe: suma por categoría sin los anulados, del que más gastó al que menos",
                    t.size() == 2 && t.get(0).categoria.equals("ARRIENDO") && t.get(0).total == 800_000
                    && t.get(1).total == 300_000 && t.get(1).cantidad == 1);
            BaseGastos.Gasto viejo = gasto(arriendo, 700_000, false, MetodoPago.EFECTIVO, false, ana.getId(),
                    hoy.minusDays(1));
            bg.registrar(viejo);
            t = bg.porCategoria(f.desde, f.hasta, 0, 0, null);
            revisar("informe: compara con el mismo largo de tiempo justo antes",
                    t.get(0).anterior == 700_000 && t.get(1).anterior == 0);
            revisar("informe: ventas en cero en una tienda nueva", bg.ventas(f.desde, f.hasta) == 0);

            // fijos
            BaseGastos.Fijo x = new BaseGastos.Fijo();
            x.categoriaId = arriendo;
            x.valor = 800_000;
            x.dia = 31;
            x.proximo = LocalDate.of(2026, 10, 31);
            x.descripcion = "Arriendo del local";
            bg.guardarFijo(x);
            BaseGastos.Fijo y = new BaseGastos.Fijo();
            y.categoriaId = servicios;
            y.valor = 90_000;
            y.dia = 5;
            y.proximo = LocalDate.of(2026, 10, 5);
            y.metodo = MetodoPago.NEQUI;
            bg.guardarFijo(y);
            BaseGastos.Fijo z = new BaseGastos.Fijo();
            z.categoriaId = servicios;
            z.valor = 60_000;
            z.dia = 20;
            z.proximo = LocalDate.of(2026, 10, 20);
            bg.guardarFijo(z);
            List<BaseGastos.Fijo> fs = bg.fijos();
            revisar("fijos: salen del montículo del que se vence primero al último",
                    fs.size() == 3 && fs.get(0).dia == 5 && fs.get(1).dia == 20 && fs.get(2).dia == 31);
            BaseGastos.Gasto pago = bg.pagarFijo(fs.get(2), true, ana.getId());
            BaseGastos.Fijo x2 = bg.fijos().get(2);
            revisar("fijo: pagar crea el gasto (contado, sale de la caja) y pasa al 30 de noviembre",
                    pago.numero == 5 && pago.saleCaja && pago.valor == 800_000 && pago.fijoId == x.id
                    && x2.proximo.equals(LocalDate.of(2026, 11, 30)) && pago.descripcion.startsWith("Arriendo del local"));
            bg.pagarFijo(x2, true, ana.getId());
            revisar("fijo: del 30 de noviembre vuelve al 31 de diciembre (guarda el día pedido)",
                    bg.fijos().get(2).proximo.equals(LocalDate.of(2026, 12, 31)));
            revisar("fijos por vencer: los vencidos y los de los próximos 3 días",
                    bg.fijosPorVencer(LocalDate.of(2026, 10, 18), 3).size() == 2);
            bg.quitarFijo(y.id);
            revisar("fijo quitado: ya no sale", bg.fijos().size() == 2);
            revisar("fijo: sin día válido se rechaza", lanza("del 1 al 31", () -> {
                BaseGastos.Fijo m = new BaseGastos.Fijo();
                m.categoriaId = arriendo;
                m.valor = 1;
                m.dia = 32;
                bg.guardarFijo(m);
            }));

            // proveedor
            Proveedor p = new Proveedor();
            p.nombre = "Air-e";
            new BaseOrdenes(bd).guardarProveedor(p);
            BaseGastos.Gasto conProv = gasto(servicios, 50_000, false, MetodoPago.EFECTIVO, false, ana.getId(), hoy);
            conProv.proveedorId = p.id;
            bg.registrar(conProv);
            f.proveedorId = p.id;
            List<BaseGastos.Gasto> lp = bg.gastos(f);
            revisar("historial: filtro por proveedor con su nombre", lp.size() == 1 && lp.get(0).proveedor.equals("Air-e"));

            // el orden con el montículo aguanta muchos y empates
            List<BaseGastos.Fijo> muchos = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                BaseGastos.Fijo m = new BaseGastos.Fijo();
                m.id = i;
                m.proximo = LocalDate.of(2026, 1, 1).plusDays((i * 37) % 60);
                muchos.add(m);
            }
            boolean ordenado = true;
            List<BaseGastos.Fijo> o = ordenar(muchos);
            for (int i = 1; i < o.size(); i++) {
                ordenado &= !o.get(i).proximo.isBefore(o.get(i - 1).proximo);
            }
            revisar("montículo: 200 fijos quedan en orden de fecha", ordenado && o.size() == 200);
        }
    }

    private static List<BaseGastos.Fijo> ordenar(List<BaseGastos.Fijo> l) throws Exception {
        java.lang.reflect.Method m = BaseGastos.class.getDeclaredMethod("ordenarPorFecha", List.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<BaseGastos.Fijo> r = (List<BaseGastos.Fijo>) m.invoke(null, l);
        return r;
    }
}
