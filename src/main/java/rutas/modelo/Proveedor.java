package rutas.modelo;

/**
 * A quien se le compra: la empresa (con su NIT o cedula) y el vendedor que pasa por la tienda.
 * Solo el nombre es obligatorio; lo demas sirve para mandarle las ordenes y para la factura.
 */
public class Proveedor {

    /** Tipos de documento de la DIAN (los mismos de la referencia). */
    public static final String[] TIPOS_DOCUMENTO = {"NIT - Número de Identificación Tributaria",
        "CC - Cédula de Ciudadanía", "CE - Cédula de Extranjería", "TI - Tarjeta de Identidad",
        "NUIP - Número único de identificación personal", "RC - Registro civil", "TE - Tarjeta de extranjería",
        "PS - Pasaporte", "DIE - Documento de identificación extranjero", "NIT-O - NIT de otro país",
        "PEP - Permiso especial de permanencia"};

    /** Responsabilidades fiscales (se puede marcar varias). */
    public static final String[] REGIMENES = {"Gran contribuyente", "Autorretenedor",
        "Agente de retención en el impuesto sobre las ventas", "Régimen Simple de Tributación", "No aplica - Otros"};

    public static final String[] DIAS = {"", "Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo"};
    public static final String[] FORMAS_PAGO = {"Contado", "Crédito 8 días", "Crédito 15 días", "Crédito 30 días",
        "Crédito 60 días"};

    public long id;
    public String tipoDocumento = TIPOS_DOCUMENTO[0];
    public String documento = "";
    public String dv = "";
    public String nombre = "";
    public String regimenes = "";      // separados por ";"
    public String telefono = "";
    public String correo = "";
    public String departamento = "GUAJIRA";
    public String municipio = "RIOHACHA";
    public String direccion = "";
    public String paginaWeb = "";
    public String vendedor = "";
    public String celularVendedor = "";
    public String diaVisita = "";
    public String formaPago = FORMAS_PAGO[0];
    public boolean activo = true;

    /** El numero al que se le manda la orden por WhatsApp: el del vendedor y, si no hay, el de la empresa. */
    public String celularParaPedidos() {
        return celularVendedor.isBlank() ? telefono : celularVendedor;
    }

    /** "NIT 900123456-7" o "CC 1052…" ("" si no tiene). */
    public String documentoTexto() {
        if (documento.isBlank()) {
            return "";
        }
        String tipo = tipoDocumento.contains(" - ") ? tipoDocumento.substring(0, tipoDocumento.indexOf(" - ")) : tipoDocumento;
        return tipo + " " + documento + (dv.isBlank() ? "" : "-" + dv);
    }

    @Override
    public String toString() {
        return nombre;
    }

    /**
     * Digito de verificacion del NIT como lo calcula la DIAN: cada cifra (de derecha a izquierda) por
     * 3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71; la suma se divide por 11 y con el
     * residuo r el DV es r si r es 0 o 1, y 11 - r si no. "" si el numero no sirve.
     */
    public static String digitoVerificacion(String nit) {
        String n = nit == null ? "" : nit.replaceAll("[^0-9]", "");
        if (n.isEmpty() || n.length() > 15) {
            return "";
        }
        int[] pesos = {3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71};
        int suma = 0;
        for (int i = 0; i < n.length(); i++) {
            suma += (n.charAt(n.length() - 1 - i) - '0') * pesos[i];
        }
        int r = suma % 11;
        return String.valueOf(r > 1 ? 11 - r : r);
    }
}
