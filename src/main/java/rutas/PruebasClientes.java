package rutas;

import static rutas.Pruebas.revisar;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import rutas.estructuras.ArbolAVL;
import rutas.logica.BaseDatos;
import rutas.logica.BaseTienda;
import rutas.modelo.Cliente;
import rutas.modelo.Parada;
import rutas.modelo.Rol;
import rutas.modelo.Salida;
import rutas.modelo.Usuario;
import rutas.vista.ImportadorClientes;
import rutas.vista.PruebaAcceso;

/** Pruebas de la pantalla de clientes: formulario, importar, estado, frecuencia y la nota. */
public class PruebasClientes {

    static void correr() throws Exception {
        System.out.println("--- Clientes ---");
        probarFormulario();
        probarImportar();
        probarCsv();
        probarBaseDatos();
        probarFrecuencia();
        probarNota();
    }

    private static boolean lanza(String pedazo, Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return e.getMessage() != null && e.getMessage().contains(pedazo);
        }
    }

    private static void probarFormulario() {
        List<Cliente> hay = new ArrayList<>();
        Cliente solo = PruebaAcceso.armarCliente(hay, null, "", "", "  Ana   Ruiz ", "", "", "", "");
        revisar("cliente: solo el nombre es obligatorio (y se limpian los espacios)", solo.getNombre().equals("Ana Ruiz")
                && solo.getDocumento().isEmpty() && solo.getTipoDocumento().isEmpty() && solo.isActivo());
        revisar("cliente: sin nombre no se guarda",
                lanza("nombre", () -> PruebaAcceso.armarCliente(hay, null, "", "", "  ", "", "", "", "")));
        Cliente nit = PruebaAcceso.armarCliente(hay, null, "NIT", "800.197.268", "Distribuidora Sol", "", "", "", "");
        revisar("cliente: al NIT se le pone el dígito de verificación de la DIAN", nit.getDocumento().equals("800197268-4"));
        revisar("cliente: NIT con el dígito equivocado se rechaza diciendo el bueno",
                lanza("es 4", () -> PruebaAcceso.armarCliente(hay, null, "NIT", "800197268-7", "Otra", "", "", "", "")));
        Cliente cc = PruebaAcceso.armarCliente(hay, null, "", "1052090292", "Marcela Luna", "300 123 4567",
                "marce@gmail.com", "Calle 15 # 7-20", "50.000");
        revisar("cliente: documento sin tipo queda como cédula; fiado en pesos", cc.getTipoDocumento()
                .equals(Cliente.TIPOS_DOCUMENTO[0]) && cc.getTelefono().equals("3001234567") && cc.getCreditoMaximo() == 50000);
        revisar("cliente: correo y teléfono mal escritos se rechazan",
                lanza("correo", () -> PruebaAcceso.armarCliente(hay, null, "", "", "Pepe", "", "pepe@", "", ""))
                && lanza("teléfono", () -> PruebaAcceso.armarCliente(hay, null, "", "", "Pepe", "12ab", "", "", "")));
        hay.add(cc);
        revisar("cliente: no se repite el nombre (sin tildes ni mayúsculas) ni el documento",
                lanza("Ya hay", () -> PruebaAcceso.armarCliente(hay, null, "", "", "MARCELA LUNA", "", "", "", ""))
                && lanza("documento ya es", () -> PruebaAcceso.armarCliente(hay, null, "", "1052090292", "Otra Luna",
                        "", "", "", "")));
        Cliente editado = PruebaAcceso.armarCliente(hay, cc, "", "1052090292", "Marcela Luna Pérez", "", "", "", "");
        revisar("cliente: al editar, su propio nombre y documento no cuentan como repetidos",
                editado.getNombre().equals("Marcela Luna Pérez"));
        revisar("cliente: «La Guajira» queda como GUAJIRA de la lista",
                PruebaAcceso.departamentoDeLista("La Guajira").equals("GUAJIRA")
                && PruebaAcceso.departamentoDeLista("").equals("GUAJIRA"));
    }

    private static void probarImportar() {
        Cliente viejo = new Cliente("Marcela Luna", "3001234567", "Calle 15 # 7-20", "Portón verde", 11.54, -72.9,
                Cliente.TIPOS_DOCUMENTO[0], "1052090292", "", "GUAJIRA", "RIOHACHA", 0);
        List<Cliente> hay = List.of(viejo);
        // titulos como los de la lista de la referencia, en otro orden
        List<String[]> filas = new ArrayList<>();
        filas.add(new String[]{"Nombre del Cliente", "Tipo de Doc", "Número Nit/Doc", "Teléfono", "Correo Electrónico",
            "Dirección", "Municipio", "Estado"});
        filas.add(new String[]{"MARCELA LUNA", "", "", "3109998877", "marce@gmail.com", "", "", ""});
        filas.add(new String[]{"Restaurante El Faro", "nit", "900123456", "", "", "Carrera 7 # 12-30", "riohacha",
            "INACTIVO"});
        filas.add(new String[]{"", "", "", "", "", "", "", ""});
        ImportadorClientes.Resultado r = ImportadorClientes.revisar(filas, hay);
        revisar("importar: reconoce los títulos de la lista (Nombre del Cliente, Número Nit/Doc…)",
                r.errores.isEmpty() && r.nuevos.size() == 1 && r.actualizados.size() == 1);
        Cliente m = r.actualizados.isEmpty() ? null : r.actualizados.get(0);
        revisar("importar: el que ya existe se actualiza sin perder su nota ni su punto del mapa", m != null
                && m.getTelefono().equals("3109998877") && m.getCorreo().equals("marce@gmail.com")
                && m.getNota().equals("Portón verde") && m.getLat() == 11.54 && m.getDocumento().equals("1052090292"));
        Cliente f = r.nuevos.isEmpty() ? null : r.nuevos.get(0);
        revisar("importar: NIT con su DV, estado INACTIVO y municipio en mayúsculas", f != null
                && f.getTipoDocumento().equals("NIT") && f.getDocumento().startsWith("900123456-") && !f.isActivo()
                && f.getMunicipio().equals("RIOHACHA"));

        List<String[]> malas = new ArrayList<>();
        malas.add(new String[]{"nombre", "estado", "tipo_documento"});
        malas.add(new String[]{"Pedro Pérez", "talvez", ""});
        malas.add(new String[]{"Luis Gómez", "", "XX"});
        malas.add(new String[]{"pedro perez", "", ""});
        ImportadorClientes.Resultado e = ImportadorClientes.revisar(malas, hay);
        revisar("importar: con errores no guarda nada y dice la fila (" + e.errores + ")", e.errores.size() == 3
                && e.errores.get(0).startsWith("Fila 2") && e.errores.get(1).startsWith("Fila 3")
                && e.errores.get(2).contains("ya está en la fila 2"));
        List<String[]> sinNombre = new ArrayList<>();
        sinNombre.add(new String[]{"telefono", "correo"});
        revisar("importar: sin la columna nombre se avisa", ImportadorClientes.revisar(sinNombre, hay).errores.get(0)
                .contains("nombre"));
    }

    private static void probarCsv() throws Exception {
        File csv = new File(Files.createTempDirectory("domiruta_csvcli").toFile(), "clientes.csv");
        Files.write(csv.toPath(), ("nombre,direccion,municipio,nota\n"
                + "Ana Ruiz,\"Calle 15 # 7-20, barrio Centro\",RIOHACHA,\n"
                + "Beto Paz,Calle 1,RIOHACHA,timbrar; dos veces\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ImportadorClientes.Resultado r = ImportadorClientes.revisar(rutas.logica.ImportadorProductos.leerCsv(csv), List.of());
        revisar("importar CSV: la coma entre comillas y el ; de la nota no parten los campos", r.errores.isEmpty()
                && r.nuevos.size() == 2 && r.nuevos.get(0).getDireccion().equals("Calle 15 # 7-20, barrio Centro")
                && r.nuevos.get(1).getNota().equals("timbrar; dos veces") && r.nuevos.get(1).getNombre().equals("Beto Paz"));
        List<Cliente> hay = List.of(new Cliente("Carlos Díaz", "", "", "", 0, 0).conActivo(false));
        revisar("crear uno que existe inactivo dice que lo active",
                lanza("inactivo", () -> PruebaAcceso.armarCliente(hay, null, "", "", "carlos diaz", "", "", "", "")));
    }

    private static void probarBaseDatos() throws Exception {
        File carpeta = Files.createTempDirectory("domiruta_clientes2").toFile();
        try (BaseDatos bd = new BaseDatos(new File(carpeta, "rutas.db"))) {
            Cliente a = new Cliente("Ana Ruiz", "3001112233", "Calle 1", "Casa azul", 11.5, -72.9);
            bd.guardarCliente(a.conActivo(false));
            ArbolAVL<String, Cliente> arbol = bd.cargarClientes();
            revisar("cliente inactivo se guarda y se lee inactivo, con su nota",
                    !arbol.buscar("ana ruiz").isActivo() && arbol.buscar("ana ruiz").getNota().equals("Casa azul"));
            bd.renombrarCliente("Ana Ruiz", new Cliente("Ana Ruiz Pérez", "3001112233", "Calle 1", "Casa azul", 11.5,
                    -72.9));
            arbol = bd.cargarClientes();
            revisar("renombrar: queda el nombre nuevo y se va el viejo", arbol.buscar("ana ruiz") == null
                    && arbol.buscar("ana ruiz perez") != null);
            List<Cliente> lote = List.of(new Cliente("Uno Uno", "", "", "", 0, 0), new Cliente("Dos Dos", "", "", "", 0, 0));
            bd.guardarClientes(lote);
            revisar("importar guarda todos juntos", bd.cargarClientes().enOrden().size() == 3);
        }
    }

    private static void probarFrecuencia() throws Exception {
        File carpeta = Files.createTempDirectory("domiruta_frecuencia").toFile();
        File archivo = new File(carpeta, "tienda.db");
        try (BaseTienda bd = new BaseTienda(archivo, 1000)) {
            Usuario u = bd.crearUsuario("ana", "Ana", Rol.ADMIN, "secreto1".toCharArray());
            try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + archivo.getAbsolutePath());
                 PreparedStatement ps = con.prepareStatement("INSERT INTO ventas(numero, estado, usuario_id, cliente,"
                         + " creada, pagada, total) VALUES(?, ?, ?, ?, ?, ?, ?)")) {
                Object[][] v = {
                    {1, "PAGADA", "Marcela Luna", "2026-10-01T10:00:00", 10000},
                    {2, "PAGADA", "marcela luna", "2026-10-20T09:00:00", 5000},
                    {3, "PAGADA", "Pedro", "2026-10-25T12:00:00", 3000},
                    {null, "ESPERA", "Pedro", "2026-10-26T12:00:00", 9000}};
                for (Object[] f : v) {
                    if (f[0] == null) {
                        ps.setNull(1, java.sql.Types.INTEGER);
                    } else {
                        ps.setInt(1, (Integer) f[0]);
                    }
                    ps.setString(2, (String) f[1]);
                    ps.setLong(3, u.getId());
                    ps.setString(4, (String) f[2]);
                    ps.setString(5, (String) f[3]);
                    ps.setString(6, "PAGADA".equals(f[1]) ? (String) f[3] : null);
                    ps.setLong(7, ((Integer) f[4]).longValue());
                    ps.executeUpdate();
                }
            }
            Map<String, BaseTienda.Frecuencia> m = bd.comprasPorCliente();
            BaseTienda.Frecuencia ml = m.get("marcela luna");
            revisar("frecuencia: cuenta las ventas pagadas sin importar mayúsculas (2 de Marcela, $15.000)",
                    ml != null && ml.compras == 2 && ml.total == 15000 && ml.ultima.getDayOfMonth() == 20);
            revisar("frecuencia: la venta en espera no cuenta", m.get("pedro").compras == 1);
            List<BaseTienda.VentaCliente> vs = bd.ventasDeCliente("MARCELA LUNA", 10);
            revisar("ver sus compras: la más nueva primero", vs.size() == 2 && vs.get(0).numero == 2);
            bd.renombrarClienteEnVentas("Marcela Luna", "Marcela Luna Pérez");
            m = bd.comprasPorCliente();
            revisar("al cambiarle el nombre no pierde sus compras", m.get("marcela luna") == null
                    && m.get("marcela luna perez").compras == 2);
        }
    }

    private static void probarNota() {
        Salida s = new Salida();
        Parada a = new Parada("Elianis", "Calle 15 # 7-20", 11.5442, -72.907);
        a.setNota("  portón   verde ");
        s.entregas.add(new Salida.Entrega(a, 0));
        Parada b = new Parada("Pedro", "Carrera 7", 11.5, -72.9);
        Parada c = new Parada("Pedro", "Carrera 7", 11.5, -72.9);
        c.copiarDatosDe(a);
        String m = rutas.logica.MensajesWhatsApp.salida("Tienda", java.time.LocalTime.of(10, 0), "José", s.entregas, "", "");
        revisar("nota: le sale al domiciliario en el mensaje", m.contains("Nota: _portón verde_"));
        revisar("nota: sin nota no sale la línea, y se copia con los datos del pedido",
                b.getNota().isEmpty() && c.getNota().equals("portón verde"));
    }
}
