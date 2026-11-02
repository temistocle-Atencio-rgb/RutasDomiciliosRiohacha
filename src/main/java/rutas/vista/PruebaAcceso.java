package rutas.vista;

/** Puerta para que las pruebas usen ayudas de la vista que no son publicas. */
public final class PruebaAcceso {
    private PruebaAcceso() {
    }

    public static boolean coincide(rutas.modelo.Cliente c, String texto) {
        return FormularioCliente.coincide(c, texto);
    }

    /** Revisa y arma un cliente como el formulario «Agregar cliente». */
    public static rutas.modelo.Cliente armarCliente(java.util.List<rutas.modelo.Cliente> todos, rutas.modelo.Cliente viejo,
            String tipo, String documento, String nombre, String telefono, String correo, String direccion,
            String credito) {
        return FormularioCliente.armar(todos, viejo, tipo, documento, nombre, telefono, correo, direccion, "GUAJIRA",
                "riohacha", credito, true, "");
    }

    /** Como armarCliente, con direccion y nota. */
    public static rutas.modelo.Cliente armarClienteCon(java.util.List<rutas.modelo.Cliente> todos, rutas.modelo.Cliente viejo,
            String nombre, String direccion, String nota) {
        return FormularioCliente.armar(todos, viejo, "", "", nombre, "", "", direccion, "GUAJIRA", "riohacha", "", true, nota);
    }

    public static String departamentoDeLista(String valor) {
        return FormularioCliente.departamentoDeLista(valor);
    }

    /** Cuerpo HTML del correo con el recibo de ejemplo. */
    public static String htmlRecibo(String mensaje) {
        return EnvioRecibo.html(mensaje, Comprobante.ejemplo(new rutas.logica.AjustesRecibo()));
    }

    /** El recibo carta de un ejemplo con n renglones: {hojas, bytes del PDF}. */
    public static Object[] reciboCarta(int n) throws java.io.IOException {
        java.util.List<rutas.modelo.LineaVenta> l = new java.util.ArrayList<>();
        long total = 0;
        for (int i = 0; i < n; i++) {
            rutas.modelo.Producto p = new rutas.modelo.Producto(i + 1, null, "Producto " + i, rutas.modelo.Unidad.UNIDAD,
                    1000, 800, 0, 0, null, true);
            rutas.modelo.LineaVenta x = new rutas.modelo.LineaVenta(p, 1000, 1000, 0, null);
            l.add(x);
            total += x.getSubtotal();
        }
        Comprobante c = new Comprobante("Tienda", 7, java.time.LocalDateTime.now(), "Ana", "", l, total,
                java.util.List.of(new rutas.modelo.Pago(rutas.modelo.MetodoPago.EFECTIVO, total)), 0, 0);
        c.ajustes = new rutas.logica.AjustesRecibo();
        return new Object[]{ReciboCarta.imagenes(c).size(), ReciboCarta.pdf(c)};
    }

    /** Alto del recibo de ejemplo, con o sin los datos del domicilio para el repartidor. */
    public static int altoRecibo(java.util.List<String> domicilio) {
        Comprobante c = Comprobante.ejemplo(new rutas.logica.AjustesRecibo());
        c.domicilio = domicilio;
        return c.alto();
    }
}
