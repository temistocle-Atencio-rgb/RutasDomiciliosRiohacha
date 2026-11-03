package rutas.logica;

import java.sql.SQLException;

/**
 * Lo que cada tienda configura una vez: los datos de su negocio (con su logo) y la
 * impresora del recibo. Se guarda en la base de la tienda, asi cada negocio que use
 * DomiRuta tiene los suyos.
 */
public class AjustesRecibo {

    /** Tamaños de letra del recibo. */
    public enum Letra {
        PEQUENA("Pequeña", 0.88f), NORMAL("Normal", 1f), GRANDE("Grande", 1.15f);

        private final String texto;
        private final float escala;

        Letra(String texto, float escala) {
            this.texto = texto;
            this.escala = escala;
        }

        public float getEscala() {
            return escala;
        }

        @Override
        public String toString() {
            return texto;
        }
    }

    public static final String[] REGIMENES = {"No responsable de IVA", "Responsable de IVA", "Régimen simple",
        "No aplica"};

    // ---- mi negocio ----
    public String nombre = "";
    public String razonSocial = "";
    public String nit = "";
    public String direccion = "";
    public String telefono = "";
    public String ciudad = "";
    public String regimen = REGIMENES[0];
    public String gracias = "GRACIAS POR SU COMPRA";
    public String pie = "";
    public boolean mostrarSistema = true;
    public boolean qrTienda = true;  // al final: «¿Lo quieres a domicilio? Escanea y pide» con el QR de la tienda
    /** El enlace de la tienda en línea ("" si no hay conexión configurada): no se guarda, sale de Conexión. */
    public String enlaceTienda = "";
    public String logo;              // ruta del logo copiado en la carpeta del programa (null = sin logo)
    public int umbralLogo = 160;     // 0-255: mas alto, mas negro sale el logo
    public boolean tramado;          // puntitos para degradados (fotos); apagado: logo limpio de dos tonos

    // ---- impresora ----
    public String impresora = "";    // "" = preguntar cada vez
    public double anchoMm = 58;
    public double margenIzqMm = 5;   // 58 mm de papel dejan unos 48 mm para escribir
    public double margenDerMm = 5;
    public Letra letra = Letra.NORMAL;
    public int ppp = 203;
    public boolean cortar = true;
    public boolean abrirCajon;
    public int copias = 1;

    /** Margen comun de cada lado para un ancho de rollo (58 mm -> 48 utiles; 80 mm -> 72 utiles). */
    public static double margenComun(double anchoMm) {
        return anchoMm >= 80 ? 4 : anchoMm >= 58 ? 5 : 3;
    }

    /** Ancho donde se puede escribir, en mm. */
    public double anchoUtilMm() {
        return anchoMm - margenIzqMm - margenDerMm;
    }

    /** Puntos de la impresora en el ancho util (384 en 58 mm a 203 ppp). */
    public int puntosUtiles() {
        // las termicas de "203 ppp" son en realidad de 8 puntos por mm (203,2 ppp)
        double porMm = ppp == 203 ? 8 : ppp / 25.4;
        return (int) Math.round(anchoUtilMm() * porMm);
    }

    /** Revisa que los numeros tengan sentido; lanza IllegalArgumentException con un mensaje claro. */
    public void validar() {
        if (anchoMm < 40 || anchoMm > 230) {
            throw new IllegalArgumentException("El ancho del papel va de 40 a 230 mm (los rollos comunes son de 58 y 80 mm)");
        }
        if (margenIzqMm < 0 || margenDerMm < 0 || margenIzqMm > 15 || margenDerMm > 15) {
            throw new IllegalArgumentException("Cada margen va de 0 a 15 mm");
        }
        if (anchoUtilMm() < 30) {
            throw new IllegalArgumentException("Con esos márgenes queda muy poco espacio para escribir (mínimo 30 mm)");
        }
        if (ppp != 180 && ppp != 203 && ppp != 300) {
            throw new IllegalArgumentException("La resolución debe ser 180, 203 o 300 ppp");
        }
        if (copias < 1 || copias > 3) {
            throw new IllegalArgumentException("Las copias van de 1 a 3");
        }
        if (umbralLogo < 30 || umbralLogo > 230) {
            throw new IllegalArgumentException("El tono del logo va de 30 a 230");
        }
    }

    public static AjustesRecibo leer(BaseTienda bd) throws SQLException {
        AjustesRecibo a = new AjustesRecibo();
        a.nombre = bd.leerConfig("negocio.nombre", a.nombre);
        a.razonSocial = bd.leerConfig("negocio.razon", a.razonSocial);
        a.nit = bd.leerConfig("negocio.nit", a.nit);
        a.direccion = bd.leerConfig("negocio.direccion", a.direccion);
        a.telefono = bd.leerConfig("negocio.telefono", a.telefono);
        a.ciudad = bd.leerConfig("negocio.ciudad", a.ciudad);
        a.regimen = bd.leerConfig("negocio.regimen", a.regimen);
        a.gracias = bd.leerConfig("negocio.gracias", a.gracias);
        a.pie = bd.leerConfig("negocio.pie", a.pie);
        a.mostrarSistema = Boolean.parseBoolean(bd.leerConfig("negocio.sistema", "true"));
        a.qrTienda = Boolean.parseBoolean(bd.leerConfig("negocio.qr", "true"));
        NubeRepartidor.Ajustes nube = NubeRepartidor.Ajustes.leer(bd);
        a.enlaceTienda = nube.listo() ? EnlacesQR.enlace(EnlacesQR.Tipo.TIENDA, nube.pagina, "") : "";
        String logo = bd.leerConfig("negocio.logo", "");
        a.logo = logo.isEmpty() ? null : logo;
        a.umbralLogo = entero(bd.leerConfig("negocio.umbral", "160"), 160);
        a.tramado = Boolean.parseBoolean(bd.leerConfig("negocio.tramado", "false"));
        a.impresora = bd.leerConfig("impresora.nombre", "");
        a.anchoMm = decimal(bd.leerConfig("impresora.ancho", "58"), 58);
        a.margenIzqMm = decimal(bd.leerConfig("impresora.margen_izq", "5"), 5);
        a.margenDerMm = decimal(bd.leerConfig("impresora.margen_der", "5"), 5);
        try {
            a.letra = Letra.valueOf(bd.leerConfig("impresora.letra", "NORMAL"));
        } catch (IllegalArgumentException e) {
            a.letra = Letra.NORMAL;
        }
        a.ppp = entero(bd.leerConfig("impresora.ppp", "203"), 203);
        a.cortar = Boolean.parseBoolean(bd.leerConfig("impresora.cortar", "true"));
        a.abrirCajon = Boolean.parseBoolean(bd.leerConfig("impresora.cajon", "false"));
        a.copias = entero(bd.leerConfig("impresora.copias", "1"), 1);
        return a;
    }

    public void guardar(BaseTienda bd) throws SQLException {
        validar();
        bd.guardarConfigs(new String[][]{
            {"negocio.nombre", nombre.trim()}, {"negocio.razon", razonSocial.trim()}, {"negocio.nit", nit.trim()},
            {"negocio.direccion", direccion.trim()}, {"negocio.telefono", telefono.trim()},
            {"negocio.ciudad", ciudad.trim()}, {"negocio.regimen", regimen}, {"negocio.gracias", gracias.trim()},
            {"negocio.pie", pie.trim()}, {"negocio.sistema", String.valueOf(mostrarSistema)}, {"negocio.qr", String.valueOf(qrTienda)},
            {"negocio.logo", logo == null ? "" : logo}, {"negocio.umbral", String.valueOf(umbralLogo)}, {"negocio.tramado", String.valueOf(tramado)},
            {"impresora.nombre", impresora}, {"impresora.ancho", String.valueOf(anchoMm)},
            {"impresora.margen_izq", String.valueOf(margenIzqMm)}, {"impresora.margen_der", String.valueOf(margenDerMm)},
            {"impresora.letra", letra.name()}, {"impresora.ppp", String.valueOf(ppp)},
            {"impresora.cortar", String.valueOf(cortar)}, {"impresora.cajon", String.valueOf(abrirCajon)},
            {"impresora.copias", String.valueOf(copias)}});
    }

    private static int entero(String s, int porDefecto) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return porDefecto;
        }
    }

    private static double decimal(String s, double porDefecto) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return porDefecto;
        }
    }
}
