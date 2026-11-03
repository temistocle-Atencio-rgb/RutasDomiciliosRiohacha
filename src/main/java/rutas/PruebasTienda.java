package rutas;

import static rutas.Pruebas.revisar;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import rutas.logica.BaseTienda;
import rutas.logica.Catalogo;
import rutas.logica.Json;
import rutas.logica.NubeRepartidor;
import rutas.logica.TiendaEnLinea;
import rutas.modelo.MetodoPago;
import rutas.modelo.Producto;
import rutas.modelo.Unidad;

/** Tienda en línea: qué se sube, que solo vaya lo que cambió y que se quite lo que ya no se vende. */
public class PruebasTienda {

    /** Supabase de mentiras: guarda el catálogo y cuenta las llamadas. */
    static class Falsa {
        final Map<Long, Map<?, ?>> catalogo = new LinkedHashMap<>();
        Map<?, ?> tienda;
        final List<String> llamadas = new ArrayList<>();
        final List<String> fotos = new ArrayList<>();
        boolean sinInternet;
        List<?> cuentas;
        List<?> existencias;
        String trabajosJson = "[]";
        String pedidosJson = "[]";
        final List<String> parches = new ArrayList<>();
        int fotoResponde = 200;

        NubeRepartidor.Transporte red() {
            return (metodo, url, cab, cuerpo) -> {
                if (sinInternet) {
                    throw new java.net.ConnectException("sin red");
                }
                String ruta = url.substring(url.indexOf("/rest/v1/") + 9);
                llamadas.add(metodo + " " + ruta);
                if (!"sb_secret_x".equals(cab.get("apikey"))) {
                    return new NubeRepartidor.Respuesta(401, "");
                }
                if (metodo.equals("GET") && ruta.startsWith("catalogo")) {
                    StringBuilder sb = new StringBuilder("[");
                    for (Long id : catalogo.keySet()) {
                        sb.append(sb.length() > 1 ? "," : "").append("{\"id\":").append(id).append('}');
                    }
                    return new NubeRepartidor.Respuesta(200, sb + "]");
                }
                if (metodo.equals("POST") && ruta.startsWith("catalogo")) {
                    for (Object o : (List<?>) Json.leer(cuerpo)) {
                        catalogo.put(Json.entero((Map<?, ?>) o, "id"), (Map<?, ?>) o);
                    }
                    return new NubeRepartidor.Respuesta(201, "");
                }
                if (metodo.equals("POST") && ruta.startsWith("tienda_info")) {
                    tienda = (Map<?, ?>) ((List<?>) Json.leer(cuerpo)).get(0);
                    return new NubeRepartidor.Respuesta(201, "");
                }
                if (ruta.startsWith("existencias_web")) {
                    existencias = metodo.equals("DELETE") ? List.of() : (List<?>) Json.leer(cuerpo);
                    return new NubeRepartidor.Respuesta(metodo.equals("DELETE") ? 204 : 201, "");
                }
                if (ruta.startsWith("caja_estado") || ruta.startsWith("encargados") || (ruta.startsWith("trabajos_caja") && !metodo.equals("GET"))) {
                    parches.add(metodo + " " + ruta + " " + cuerpo);
                    return new NubeRepartidor.Respuesta(metodo.equals("POST") ? 201 : 204, "");
                }
                if (ruta.startsWith("trabajos_caja")) {
                    return new NubeRepartidor.Respuesta(200, trabajosJson);
                }
                if (ruta.startsWith("pagos_tienda")) {
                    if (metodo.equals("DELETE")) {
                        cuentas = List.of();
                    } else {
                        cuentas = (List<?>) Json.leer(cuerpo);
                    }
                    return new NubeRepartidor.Respuesta(metodo.equals("DELETE") ? 204 : 201, "");
                }
                if (metodo.equals("GET") && ruta.startsWith("pedidos_web")) {
                    return new NubeRepartidor.Respuesta(200, pedidosJson);
                }
                if (metodo.equals("PATCH") && ruta.startsWith("pedidos_web")) {
                    parches.add(ruta + " " + cuerpo);
                    return new NubeRepartidor.Respuesta(204, "");
                }
                if (metodo.equals("DELETE") && ruta.startsWith("catalogo?id=in.(")) {
                    String ids = ruta.substring(ruta.indexOf('(') + 1, ruta.indexOf(')'));
                    for (String s : ids.split(",")) {
                        catalogo.remove(Long.parseLong(s));
                    }
                    return new NubeRepartidor.Respuesta(204, "");
                }
                return new NubeRepartidor.Respuesta(400, "{}");
            };
        }

        TiendaEnLinea.Bytes bytes() {
            return (url, cab, cuerpo) -> {
                if (sinInternet) {
                    throw new java.net.ConnectException("sin red");
                }
                fotos.add(url.substring(url.indexOf("/object/") + 8) + " " + cab.get("Content-Type") + " " + cuerpo.length);
                return fotoResponde;
            };
        }
    }

    static void correr() throws Exception {
        System.out.println("--- Tienda en línea (subida del catálogo) ---");
        try (BaseTienda bd = PruebasCaja.baseTemporal()) {
            Catalogo cat = new Catalogo(bd);
            long granos = bd.crearCategoria("Granos", null, 1).getId();
            File dir = Files.createTempDirectory("domiruta_fotos").toFile();
            File foto = new File(dir, "arroz.png");
            BufferedImage img = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_ARGB);
            ImageIO.write(img, "png", foto);
            Producto arroz = cat.crear(new Producto(0, "770", "Arroz Diana 500 g", Unidad.UNIDAD, 3200, 2500, 10, 0,
                    foto.getAbsolutePath(), true, granos, true));
            Producto queso = cat.crear(new Producto(0, "771", "Queso costeño", Unidad.LIBRA, 9000, 7000, 0, 0,
                    null, true, null, true));
            Producto recarga = cat.crear(new Producto(0, "772", "Recarga", Unidad.UNIDAD, 1000, 0, 0, 0,
                    null, true, null, false));
            Producto oculto = cat.crear(new Producto(0, "773", "Cigarrillo", Unidad.UNIDAD, 500, 300, 5, 0,
                    null, true, null, true));
            bd.ponerEnLinea(arroz.getId(), true);
            bd.ponerEnLinea(queso.getId(), true);
            bd.ponerEnLinea(recarga.getId(), true);
            bd.ponerEnLinea(recarga.getId(), true); // dos veces no repite
            revisar("en línea: marcado y no marcado", bd.enLinea(arroz.getId()) && !bd.enLinea(oculto.getId()));
            revisar("en línea: solo los 3 marcados", bd.productosEnLinea().size() == 3);

            rutas.logica.AjustesRecibo recibo = new rutas.logica.AjustesRecibo();
            recibo.nombre = "Tienda La Florida";
            recibo.direccion = "Calle 12 # 8-30";
            recibo.telefono = "3001234567";
            File logo = new File(dir, "logo-negocio.png");
            ImageIO.write(new BufferedImage(900, 900, BufferedImage.TYPE_INT_ARGB), "png", logo);
            recibo.logo = logo.getAbsolutePath();
            TiendaEnLinea.Ajustes aj = new TiendaEnLinea.Ajustes();
            aj.domicilio = 3000;
            aj.minimo = 10000;
            aj.horario = "7 a. m. a 9 p. m.";
            aj.guardar(bd);
            rutas.logica.MediosPago.guardar(bd, List.of(MetodoPago.NEQUI, MetodoPago.MERCADO_PAGO));
            TiendaEnLinea.Ajustes leido = TiendaEnLinea.Ajustes.leer(bd);
            revisar("ajustes de la tienda se guardan", leido.domicilio == 3000 && leido.minimo == 10000 && leido.abierta);
            boolean malo = false;
            try {
                TiendaEnLinea.Ajustes x = new TiendaEnLinea.Ajustes();
                x.domicilio = -1;
                x.validar();
            } catch (IllegalArgumentException e) {
                malo = true;
            }
            revisar("domicilio negativo no se acepta", malo);

            Falsa f = new Falsa();
            TiendaEnLinea t = new TiendaEnLinea("https://x.supabase.co/", "sb_secret_x", f.red(), f.bytes());
            TiendaEnLinea.Datos d = TiendaEnLinea.Datos.leer(bd, recibo, "");
            TiendaEnLinea.Resultado r = t.sincronizar(d, Map.of(), true);
            revisar("primera subida: 3 productos, tienda y 2 fotos", r.subidos == 3 && r.tienda && r.fotos == 2
                    && f.catalogo.size() == 3);
            Map<?, ?> a = f.catalogo.get(arroz.getId());
            revisar("arroz: precio, categoría, disponible y foto en la carpeta",
                    Json.entero(a, "precio") == 3200 && "Granos".equals(a.get("categoria"))
                    && Boolean.TRUE.equals(a.get("disponible"))
                    && String.valueOf(a.get("foto")).startsWith("https://x.supabase.co/storage/v1/object/public/catalogo/p/"));
            revisar("va el código de barras y sin características si no tiene", "770".equals(a.get("codigo"))
                    && String.valueOf(a.get("caracteristicas")).equals("[]"));
            rutas.modelo.FichaProducto fq = new rutas.modelo.FichaProducto();
            fq.descripcion = "Queso fresco de la Guajira";
            fq.medida = "Libra";
            fq.caracterizar = true;
            fq.caracteristicas.add(new String[]{"Origen", "Riohacha"});
            fq.caracteristicas.add(new String[]{"Conservación", "Refrigerado"});
            bd.guardarFicha(queso.getId(), fq);
            r = t.sincronizar(TiendaEnLinea.Datos.leer(bd, recibo, ""), r.enviado, false);
            Map<?, ?> qc = f.catalogo.get(queso.getId());
            revisar("las características de la ficha van a la tienda", r.subidos == 1
                    && String.valueOf(qc.get("caracteristicas")).equals("[{n=Origen, v=Riohacha}, {n=Conservación, v=Refrigerado}]")
                    && "Precio por libra · Queso fresco de la Guajira".equals(qc.get("descripcion")));
            revisar("cuántos quedan va aparte (solo para el encargado), en unidades", f.existencias != null
                    && f.existencias.stream().anyMatch(x -> Json.entero((Map<?, ?>) x, "id") == arroz.getId()
                            && Json.decimal((Map<?, ?>) x, "stock") == 0.01) // 10 milésimas
                    && !String.valueOf(f.catalogo).contains("maneja"));
            revisar("no se sube el costo ni el stock", !a.containsKey("precio_compra") && !a.containsKey("stock")
                    && !a.toString().contains("2500"));
            Map<?, ?> q = f.catalogo.get(queso.getId());
            revisar("queso sin existencias: Agotado y dice por libra", Boolean.FALSE.equals(q.get("disponible"))
                    && String.valueOf(q.get("descripcion")).startsWith("Precio por libra"));
            revisar("recarga (no maneja inventario) siempre disponible",
                    Boolean.TRUE.equals(f.catalogo.get(recarga.getId()).get("disponible")));
            revisar("la tienda: nombre del recibo, logo, domicilio y medios de pago",
                    "Tienda La Florida".equals(f.tienda.get("nombre")) && String.valueOf(f.tienda.get("logo")).contains("/catalogo/logo-")
                    && Json.entero(f.tienda, "domicilio") == 3000
                    && String.valueOf(f.tienda.get("pagos")).equals("[efectivo, nequi, mercado_pago]"));
            revisar("la foto va achicada en JPG y el logo en PNG",
                    f.fotos.stream().anyMatch(s -> s.contains("/p/") && s.contains("image/jpeg"))
                    && f.fotos.stream().anyMatch(s -> s.contains("logo-") && s.contains("image/png")));
            try {
                BufferedImage chica = ImageIO.read(new java.io.ByteArrayInputStream(
                        rutasPreparar(foto)));
                revisar("la foto queda de 600 px de ancho", chica.getWidth() == 600 && chica.getHeight() == 400);
            } catch (Exception e) {
                revisar("la foto queda de 600 px de ancho", false);
            }

            // 2. sin cambios: no se llama nada (fuera de la revisión)
            Map<String, String> env = TiendaEnLinea.leerEnviado(TiendaEnLinea.escribirEnviado(r.enviado));
            f.llamadas.clear();
            f.fotos.clear();
            r = t.sincronizar(TiendaEnLinea.Datos.leer(bd, recibo, ""), env, false);
            revisar("sin cambios no se sube nada", r.nada() && f.llamadas.isEmpty() && f.fotos.isEmpty());

            // 3. se vende el último arroz y cambia el precio del queso: solo esos dos
            cat.editar(new Producto(arroz.getId(), "770", "Arroz Diana 500 g", Unidad.UNIDAD, 3200, 2500, 0, 0,
                    foto.getAbsolutePath(), true, granos, true));
            cat.editar(new Producto(queso.getId(), "771", "Queso costeño", Unidad.LIBRA, 9500, 7000, 0, 0,
                    null, true, null, true));
            r = t.sincronizar(TiendaEnLinea.Datos.leer(bd, recibo, ""), r.enviado, false);
            revisar("cambian 2: arroz agotado y queso a 9.500", r.subidos == 2 && r.fotos == 0 && !r.tienda
                    && Boolean.FALSE.equals(f.catalogo.get(arroz.getId()).get("disponible"))
                    && Json.entero(f.catalogo.get(queso.getId()), "precio") == 9500);

            // 4. se quita de en línea y se desactiva otro: se borran de Supabase
            bd.ponerEnLinea(recarga.getId(), false);
            cat.desactivar(queso.getId());
            r = t.sincronizar(TiendaEnLinea.Datos.leer(bd, recibo, ""), r.enviado, false);
            revisar("lo que ya no se vende se quita", r.quitados == 2 && f.catalogo.keySet().equals(java.util.Set.of(arroz.getId())));

            // 5. alguien borró en Supabase: al revisar se vuelve a subir; y lo que sobra allá se quita
            f.catalogo.clear();
            f.catalogo.put(999L, Map.of("id", 999L));
            r = t.sincronizar(TiendaEnLinea.Datos.leer(bd, recibo, ""), r.enviado, true);
            revisar("al revisar: vuelve el arroz y se quita el que sobraba", f.catalogo.keySet().equals(java.util.Set.of(arroz.getId())));

            // 6. cambia la foto: sube la nueva con otro nombre
            ImageIO.write(new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB), "png", foto);
            foto.setLastModified(System.currentTimeMillis() + 5000);
            String antes = String.valueOf(f.catalogo.get(arroz.getId()).get("foto"));
            f.fotos.clear();
            r = t.sincronizar(TiendaEnLinea.Datos.leer(bd, recibo, ""), r.enviado, false);
            revisar("foto nueva: se sube y cambia el enlace", r.fotos == 1 && f.fotos.size() == 1
                    && !antes.equals(f.catalogo.get(arroz.getId()).get("foto")));

            // 7. sin internet: falla sin dañar lo enviado
            f.sinInternet = true;
            cat.editar(new Producto(arroz.getId(), "770", "Arroz Diana 500 g", Unidad.UNIDAD, 3300, 2500, 0, 0,
                    foto.getAbsolutePath(), true, granos, true));
            boolean fallo = false;
            try {
                t.sincronizar(TiendaEnLinea.Datos.leer(bd, recibo, ""), r.enviado, false);
            } catch (java.io.IOException e) {
                fallo = e.getMessage().startsWith("No hay internet");
            }
            revisar("sin internet: avisa «No hay internet»", fallo);
            f.sinInternet = false;
            r = t.sincronizar(TiendaEnLinea.Datos.leer(bd, recibo, ""), r.enviado, false);
            revisar("vuelve el internet: sube el precio pendiente", Json.entero(f.catalogo.get(arroz.getId()), "precio") == 3300);

            // 8. una foto que Supabase no acepta: el producto va sin foto y se avisa
            Producto leche = cat.crear(new Producto(0, "774", "Leche", Unidad.UNIDAD, 4300, 3500, 4, 0,
                    logo.getAbsolutePath(), true, null, true));
            bd.ponerEnLinea(leche.getId(), true);
            ImageIO.write(new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB), "png", logo);
            logo.setLastModified(System.currentTimeMillis() + 9000);
            f.fotoResponde = 400;
            r = t.sincronizar(TiendaEnLinea.Datos.leer(bd, recibo, ""), r.enviado, false);
            revisar("foto rechazada: va sin foto y queda el aviso", f.catalogo.get(leche.getId()).get("foto") == null
                    && !r.problemas.isEmpty() && f.tienda.get("logo") == null);
            f.fotoResponde = 200;
            r = t.sincronizar(TiendaEnLinea.Datos.leer(bd, recibo, ""), r.enviado, false);
            revisar("se reintenta la foto la vez siguiente", f.catalogo.get(leche.getId()).get("foto") != null
                    && r.problemas.isEmpty());

            // 9. clave mala
            TiendaEnLinea mala = new TiendaEnLinea("https://x.supabase.co", "sb_secret_otra", f.red(), f.bytes());
            String m = "";
            try {
                mala.sincronizar(TiendaEnLinea.Datos.leer(bd, recibo, ""), Map.of(), false);
            } catch (java.io.IOException e) {
                m = e.getMessage();
            }
            revisar("clave mala: mensaje claro", m.contains("clave secreta"));

            // 10. los datos para pagar: solo los medios activos que se pagan antes, y solo si tienen número
            TiendaEnLinea.guardarCuentas(bd, Map.of(MetodoPago.NEQUI, new String[]{" 320 271  2871 ", "Temistocle Atencio"},
                    MetodoPago.DAVIPLATA, new String[]{"3001112233", "X"}));
            revisar("las cuentas se guardan limpias", TiendaEnLinea.leerCuentas(bd).get(MetodoPago.NEQUI)[0].equals("320 271 2871"));
            r = t.sincronizar(TiendaEnLinea.Datos.leer(bd, recibo, ""), r.enviado, false);
            revisar("sube solo Nequi (Daviplata no está activo, Mercado Pago no tiene número)", f.cuentas != null
                    && f.cuentas.size() == 1 && String.valueOf(f.cuentas).contains("nequi") && String.valueOf(f.cuentas).contains("Temistocle"));
            f.cuentas = null;
            t.sincronizar(TiendaEnLinea.Datos.leer(bd, recibo, ""), r.enviado, false);
            revisar("si no cambian, no se vuelven a subir", f.cuentas == null);
            revisar("efectivo y tarjeta no piden datos", !TiendaEnLinea.pagaAntes(MetodoPago.EFECTIVO)
                    && !TiendaEnLinea.pagaAntes(MetodoPago.TARJETA_DEBITO) && TiendaEnLinea.pagaAntes(MetodoPago.BREB));
            boolean largo = false;
            try {
                TiendaEnLinea.guardarCuentas(bd, Map.of(MetodoPago.NEQUI, new String[]{"1".repeat(61), ""}));
            } catch (IllegalArgumentException e) {
                largo = true;
            }
            revisar("número de cuenta muy largo se rechaza", largo);

            // 11. los pedidos que llegan
            f.pedidosJson = "[{\"id\":7,\"codigo\":\"K7M2PQ9X\",\"creado\":\"2026-11-03T10:00:00-05:00\",\"nombre\":\"Ana Pérez\","
                    + "\"celular\":\"3202712871\",\"entrega\":\"domicilio\",\"direccion\":\"Calle 15 # 10-20\",\"referencia\":\"casa azul\","
                    + "\"lat\":11.54,\"lon\":-72.9,\"pago\":\"nequi\",\"paga_con\":null,\"nota\":\"\",\"items\":[{\"id\":1,\"nombre\":\"Arroz\","
                    + "\"precio\":3200,\"cantidad\":2}],\"subtotal\":6400,\"domicilio\":3000,\"total\":9400,\"estado\":\"nuevo\","
                    + "\"motivo\":\"\",\"tomado\":false,\"comprobante\":\"7-1700000000.jpg\",\"pagado\":false}]";
            List<rutas.modelo.PedidoWeb> ps = t.pedidosActivos();
            rutas.modelo.PedidoWeb pw = ps.get(0);
            revisar("pedido en línea: se leen los datos", ps.size() == 1 && pw.total == 9400 && pw.items.get(0).cantidad == 2
                    && pw.lat == 11.54 && pw.pagaAntes() && pw.metodo() == MetodoPago.NEQUI && pw.unidades() == 2
                    && !pw.tomado && pw.comprobante.equals("7-1700000000.jpg") && pw.estadoTexto().equals("Nuevo"));
            t.marcarTomados(List.of(7L, 9L));
            t.cambiarEstado(7, "cancelado", "Se agotó un producto");
            t.marcarPagado(7);
            revisar("pedido en línea: tomado, cancelado con motivo y pagado", f.parches.size() == 3
                    && f.parches.get(0).startsWith("pedidos_web?id=in.(7,9) {\"tomado\":true}")
                    && f.parches.get(1).contains("\"estado\":\"cancelado\"") && f.parches.get(1).contains("Se agotó")
                    && f.parches.get(2).contains("\"pagado\":true"));
            boolean raro = false;
            try {
                t.cambiarEstado(7, "borrado", null);
            } catch (IllegalArgumentException e) {
                raro = true;
            }
            revisar("un estado inventado no se manda", raro);
            boolean malo2 = false;
            try {
                t.enlaceComprobante("../otra/cosa.jpg");
            } catch (IllegalArgumentException e) {
                malo2 = true;
            }
            revisar("comprobante: solo rutas de la carpeta (nada de ../)", malo2);

            // 12. el encargado de pedidos
            f.parches.clear();
            t.latido();
            revisar("latido de la caja", f.parches.get(0).startsWith("PATCH caja_estado?id=eq.1 {\"visto_en\":"));
            f.trabajosJson = "[{\"id\":3,\"tipo\":\"asignar\",\"datos\":{\"pedidos\":[7,8],\"repartidor\":\"1b4e28ba-2fa1-41d2-883f-0016d3cca427\"}}]";
            List<TiendaEnLinea.Trabajo> tr = t.trabajosPendientes();
            revisar("trabajos de la caja: se leen", tr.size() == 1 && tr.get(0).tipo.equals("asignar")
                    && String.valueOf(tr.get(0).datos.get("pedidos")).equals("[7.0, 8.0]"));
            t.terminarTrabajo(3, false, "No se encontró la dirección");
            revisar("trabajo terminado con el problema", f.parches.get(1).contains("\"estado\":\"error\"")
                    && f.parches.get(1).contains("No se encontró"));
            String malCel = "";
            try {
                t.crearEncargado("Laura", "12345");
            } catch (IllegalArgumentException e) {
                malCel = e.getMessage();
            }
            t.crearEncargado("  Laura Gómez ", "+57 300 123 4567");
            revisar("encargado: celular malo se rechaza y el bueno se limpia", malCel.contains("10 números")
                    && f.parches.get(2).contains("\"celular\":\"3001234567\"") && f.parches.get(2).contains("\"nombre\":\"Laura Gómez\""));
            revisar("enlace del encargado junto a la tienda", TiendaEnLinea.enlaceEncargado("https://a.github.io/r/")
                    .equals("https://a.github.io/r/pedidos.html"));
            rutas.modelo.PedidoWeb conFalta = TiendaEnLinea.pedido((Map<?, ?>) ((List<?>) Json.leer(f.pedidosJson)).get(0));
            conFalta.faltantes.add(1L);
            revisar("lo que falta no se cobra (queda el domicilio)", conFalta.totalEmpacado() == 3000);
        }
        revisar("enlace de la tienda desde la página del repartidor",
                TiendaEnLinea.enlace("https://a.github.io/domiruta-repartidor/").equals("https://a.github.io/domiruta-repartidor/tienda.html")
                && TiendaEnLinea.enlace("https://a.github.io/domiruta-repartidor").equals("https://a.github.io/domiruta-repartidor/tienda.html")
                && TiendaEnLinea.enlace("https://a.github.io/r/index.html?x=1").equals("https://a.github.io/r/tienda.html")
                && TiendaEnLinea.enlace("").isEmpty());
        revisar("lo enviado dañado se lee vacío", TiendaEnLinea.leerEnviado("{dañado").isEmpty());

        // ---- códigos QR ----
        rutas.logica.CodigoQR q1 = rutas.logica.CodigoQR.de("1234567");
        rutas.logica.CodigoQR q2 = rutas.logica.CodigoQR.de("12345678");
        revisar("QR nivel H: 7 letras caben en la versión 1 (21 x 21), 8 ya no", q1.version == 1 && q1.lado == 21 && q2.version == 2);
        String largo = "x".repeat(1273);
        boolean cabe = rutas.logica.CodigoQR.de(largo).version == 40;
        boolean muyLargo = false;
        try {
            rutas.logica.CodigoQR.de(largo + "x");
        } catch (IllegalArgumentException e) {
            muyLargo = true;
        }
        revisar("QR: caben 1273 letras (versión 40) y una más se rechaza", cabe && muyLargo);
        rutas.logica.CodigoQR q7 = rutas.logica.CodigoQR.de("x".repeat(60));
        revisar("QR: versión 7 con los cuadros de alineación de la norma (6, 22, 38)",
                q7.version == 7 && java.util.Arrays.equals(q7.alineacion(), new int[]{6, 22, 38}));
        rutas.logica.CodigoQR qt = rutas.logica.CodigoQR.de("https://a.github.io/r/tienda.html");
        boolean esquinas = true, tiempo = true;
        for (int i = 0; i < 7; i++) {
            esquinas &= qt.negro(i, 0) && qt.negro(0, i) && qt.negro(qt.lado - 1 - i, 0) && qt.negro(0, qt.lado - 1 - i);
        }
        for (int i = 8; i < qt.lado - 8; i++) {
            tiempo &= qt.negro(i, 6) == (i % 2 == 0) && qt.negro(6, i) == (i % 2 == 0);
        }
        revisar("QR: buscadores en 3 esquinas, líneas de tiempo y el cuadrito negro fijo",
                esquinas && tiempo && qt.negro(8, qt.lado - 8));
        revisar("QR: el mismo texto da el mismo código", rutas.logica.CodigoQR.de("hola").mascara == rutas.logica.CodigoQR.de("hola").mascara);

        String pag = "https://a.github.io/domiruta-repartidor/";
        revisar("enlaces QR desde la dirección de la página",
                rutas.logica.EnlacesQR.enlace(rutas.logica.EnlacesQR.Tipo.TIENDA, pag, "").equals(pag + "tienda.html")
                && rutas.logica.EnlacesQR.enlace(rutas.logica.EnlacesQR.Tipo.PEDIDOS, pag + "index.html?r=abc", "").equals(pag + "pedidos.html")
                && rutas.logica.EnlacesQR.enlace(rutas.logica.EnlacesQR.Tipo.REPARTIDOR, "https://domiruta.co", "").equals("https://domiruta.co/")
                && rutas.logica.EnlacesQR.enlace(rutas.logica.EnlacesQR.Tipo.TIENDA, "", "").isEmpty()
                && rutas.logica.EnlacesQR.enlace(rutas.logica.EnlacesQR.Tipo.TIENDA, "no es enlace", "").isEmpty());
        revisar("QR de WhatsApp con el celular de la tienda (y sin celular no hay)",
                rutas.logica.EnlacesQR.enlace(rutas.logica.EnlacesQR.Tipo.WHATSAPP, pag, "+57 300 123 4567")
                        .equals("https://wa.me/573001234567?text=Hola%2C%20quiero%20hacer%20un%20pedido")
                && rutas.logica.EnlacesQR.enlace(rutas.logica.EnlacesQR.Tipo.WHATSAPP, pag, "605 727 1234").isEmpty());
        revisar("QR: si cambia el dominio, cambia la huella (los impresos quedan viejos)",
                !rutas.logica.EnlacesQR.huella(pag, "3001234567").equals(rutas.logica.EnlacesQR.huella("https://domiruta.co/", "3001234567"))
                && rutas.logica.EnlacesQR.huella(pag, "3001234567").equals(rutas.logica.EnlacesQR.huella(pag + "index.html", "+573001234567")));
    }

    private static byte[] rutasPreparar(File f) throws Exception {
        java.lang.reflect.Method m = TiendaEnLinea.class.getDeclaredMethod("preparar", File.class, boolean.class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, f, false);
    }
}
