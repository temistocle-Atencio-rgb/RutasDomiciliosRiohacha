package rutas.modelo;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Todo lo que el formulario de producto guarda ademas de lo basico (nombre, codigo, precio, stock):
 * descripcion, garantia, impuestos, vencimiento, margen, precio por mayor, tipo, bodegas,
 * caracteristicas, IMEI y lotes. Va en tablas aparte para no tocar los arboles del catalogo.
 */
public class FichaProducto {

    /** Unidades de medida que se pueden escoger. Libra y kilo se venden por peso; las demas por unidad. */
    public static final String[] MEDIDAS = {"Unidad", "Libra", "Kilo", "Gramo", "Litro", "Mililitro", "Galón",
        "Metro", "Caja", "Paquete", "Docena", "Bulto", "Botella", "Bolsa"};

    /** Impuestos que se pueden poner (Colombia). */
    public static final String[] IMPUESTOS = {"IVA 19%", "IVA 5%", "IVA 0%", "Exento", "INC 8%", "INC 4%"};

    public String descripcion = "";
    public String garantia = "";
    public String medida = "Unidad";
    public List<String> impuestos = new ArrayList<>();
    public LocalDate vencimiento;
    public boolean porMargen;
    public long margenCentesimas;   // 25,5 % = 2550
    public long precioMayor;
    public boolean esProducto = true;
    public boolean esInsumo;
    public boolean esReceta;
    public String urlImagen = "";
    public boolean caracterizar;
    public List<String[]> caracteristicas = new ArrayList<>(); // {nombre, valor}
    public boolean usaImei;
    public List<String> imeis = new ArrayList<>();
    public boolean usaLotes;
    public List<Lote> lotes = new ArrayList<>();
    /** Stock y minimo (milesimas) en las bodegas que NO son el punto de venta. */
    public Map<Long, long[]> bodegas = new LinkedHashMap<>();

    /** Unidad de venta que corresponde a la medida escogida. */
    public static Unidad unidadDe(String medida) {
        if ("Libra".equalsIgnoreCase(medida)) {
            return Unidad.LIBRA;
        }
        if ("Kilo".equalsIgnoreCase(medida)) {
            return Unidad.KILO;
        }
        return Unidad.UNIDAD;
    }

    /** Medida por defecto para un producto que todavia no tiene ficha. */
    public static String medidaDe(Unidad u) {
        return u == Unidad.LIBRA ? "Libra" : u == Unidad.KILO ? "Kilo" : "Unidad";
    }

    /** Precio de venta que sale de sumarle el margen al costo (redondeado al peso). */
    public static long precioConMargen(long costo, long margenCentesimas) {
        return Math.round(costo * (10000.0 + margenCentesimas) / 10000.0);
    }

    /** Revisa que todo tenga sentido; lanza IllegalArgumentException con un mensaje para el usuario. */
    public void validar(long precioVenta) {
        validar(precioVenta, null);
    }

    /**
     * antes: la ficha como estaba guardada (null si es nueva). Los textos de antes que no se tocaron
     * no se vuelven a revisar contra los largos nuevos (asi un dato viejo no impide editar lo demas).
     */
    public void validar(long precioVenta, FichaProducto antes) {
        if (antes == null || !descripcion.equals(rutas.logica.Textos.limpiarLineas(antes.descripcion))) {
            rutas.logica.Textos.revisar(descripcion, "La descripción", rutas.logica.Textos.DESCRIPCION, true);
        }
        if (antes == null || !garantia.equals(rutas.logica.Textos.limpiarLineas(antes.garantia))) {
            rutas.logica.Textos.revisar(garantia, "La nota de garantía", rutas.logica.Textos.GARANTIA, true);
        }
        if (caracterizar) {
            for (String[] c : caracteristicas) {
                boolean igual = antes != null && antes.caracteristicas.stream()
                        .anyMatch(a -> rutas.logica.Textos.limpiar(a[0]).equals(c[0])
                                && rutas.logica.Textos.limpiar(a[1]).equals(c[1]));
                if (!igual && c[0] != null && c[1] != null) {
                    rutas.logica.Textos.revisar(c[0], "El nombre de la característica «" + c[0] + "»",
                            rutas.logica.Textos.CARACTERISTICA_NOMBRE, false);
                    rutas.logica.Textos.revisar(c[1], "El valor de la característica «" + c[0] + "»",
                            rutas.logica.Textos.CARACTERISTICA_VALOR, false);
                }
            }
        }
        if (!esProducto && !esInsumo && !esReceta) {
            throw new IllegalArgumentException("Marque si es Producto, Insumo o Receta / Servicio / Combo");
        }
        if (porMargen && (margenCentesimas < 0 || margenCentesimas > 100000)) {
            throw new IllegalArgumentException("El margen de utilidad va de 0 % a 1000 %");
        }
        if (precioMayor < 0) {
            throw new IllegalArgumentException("El precio al por mayor no puede ser negativo");
        }
        if (precioMayor > precioVenta) {
            throw new IllegalArgumentException("El precio al por mayor no puede ser más alto que el de venta");
        }
        if (urlImagen != null && !urlImagen.isBlank()
                && !(urlImagen.trim().startsWith("http://") || urlImagen.trim().startsWith("https://"))) {
            throw new IllegalArgumentException("La dirección de imagen debe empezar por http:// o https://");
        }
        if (caracterizar) {
            if (caracteristicas.isEmpty()) {
                throw new IllegalArgumentException("Agregue al menos una característica (por ejemplo Color: rojo)");
            }
            for (String[] c : caracteristicas) {
                if (c[0] == null || c[0].isBlank() || c[1] == null || c[1].isBlank()) {
                    throw new IllegalArgumentException("Cada característica necesita nombre y valor");
                }
            }
        }
        if (usaImei) {
            if (imeis.isEmpty()) {
                throw new IllegalArgumentException("Escriba al menos un IMEI");
            }
            Set<String> vistos = new HashSet<>();
            for (String i : imeis) {
                if (!imeiValido(i)) {
                    throw new IllegalArgumentException("El IMEI " + i + " no es válido (15 números con su dígito de control)");
                }
                if (!vistos.add(i)) {
                    throw new IllegalArgumentException("El IMEI " + i + " está repetido");
                }
            }
        }
        if (usaLotes) {
            if (lotes.isEmpty()) {
                throw new IllegalArgumentException("Agregue al menos un lote");
            }
            Set<String> vistos = new HashSet<>();
            for (Lote l : lotes) {
                if (l.getNumero().isEmpty()) {
                    throw new IllegalArgumentException("Cada lote necesita su número");
                }
                if (!vistos.add(l.getNumero().toUpperCase())) {
                    throw new IllegalArgumentException("El lote " + l.getNumero() + " está repetido");
                }
                if (l.getCantidad() < 0) {
                    throw new IllegalArgumentException("La cantidad del lote " + l.getNumero() + " no puede ser negativa");
                }
            }
        }
        for (long[] sm : bodegas.values()) {
            if (sm[0] < 0 || sm[1] < 0) {
                throw new IllegalArgumentException("El stock de las bodegas no puede ser negativo");
            }
        }
    }

    /** IMEI: 15 numeros y el ultimo cuadra con el algoritmo de Luhn. */
    public static boolean imeiValido(String imei) {
        if (imei == null || !imei.matches("\\d{15}")) {
            return false;
        }
        int suma = 0;
        for (int i = 0; i < 15; i++) {
            int d = imei.charAt(14 - i) - '0';
            if (i % 2 == 1) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            suma += d;
        }
        return suma % 10 == 0;
    }

    /**
     * La fecha de vencimiento que importa: la mas cercana entre la del producto
     * y la de los lotes que todavia tienen cantidad.
     */
    public LocalDate vencimientoMasCercano() {
        LocalDate v = vencimiento;
        if (usaLotes) {
            for (Lote l : lotes) {
                if (l.getVence() != null && l.getCantidad() > 0 && (v == null || l.getVence().isBefore(v))) {
                    v = l.getVence();
                }
            }
        }
        return v;
    }
}
