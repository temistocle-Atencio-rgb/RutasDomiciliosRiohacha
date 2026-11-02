package rutas.modelo;

/**
 * Cliente guardado: para la caja (buscarlo por nombre, cédula o celular) y para los domicilios
 * (su dirección ya ubicada en el mapa). La ubicación 0,0 quiere decir "todavía sin ubicar".
 */
public class Cliente {

    /** Tipos de documento de Colombia. */
    public static final String[] TIPOS_DOCUMENTO = {"Cédula de ciudadanía", "NIT", "Cédula de extranjería",
        "Tarjeta de identidad", "Pasaporte", "PPT (Permiso por protección temporal)"};

    private final String nombre;
    private final String telefono;
    private final String direccion;
    private final String nota;
    private final double lat;
    private final double lon;
    private final String tipoDocumento;
    private final String documento;
    private final String correo;
    private final String departamento;
    private final String municipio;
    private final long creditoMaximo; // pesos; 0 = sin limite
    private final boolean activo;     // inactivo: no sale para escoger en la caja ni en Ruta (no se borra)

    public Cliente(String nombre, String telefono, String direccion, String nota, double lat, double lon) {
        this(nombre, telefono, direccion, nota, lat, lon, "", "", "", "", "", 0);
    }

    public Cliente(String nombre, String telefono, String direccion, String nota, double lat, double lon,
            String tipoDocumento, String documento, String correo, String departamento, String municipio,
            long creditoMaximo) {
        this(nombre, telefono, direccion, nota, lat, lon, tipoDocumento, documento, correo, departamento, municipio,
                creditoMaximo, true);
    }

    public Cliente(String nombre, String telefono, String direccion, String nota, double lat, double lon,
            String tipoDocumento, String documento, String correo, String departamento, String municipio,
            long creditoMaximo, boolean activo) {
        this.nombre = nombre;
        this.telefono = vacio(telefono);
        this.direccion = vacio(direccion);
        this.nota = vacio(nota);
        this.lat = lat;
        this.lon = lon;
        this.tipoDocumento = vacio(tipoDocumento);
        this.documento = vacio(documento);
        this.correo = vacio(correo);
        this.departamento = vacio(departamento);
        this.municipio = vacio(municipio);
        this.creditoMaximo = Math.max(0, creditoMaximo);
        this.activo = activo;
    }

    private static String vacio(String s) {
        return s == null ? "" : s.trim();
    }

    /** El mismo cliente con otra direccion (conserva documento, correo y demas). */
    public Cliente conDireccion(String nuevaDireccion, double nuevaLat, double nuevaLon) {
        return new Cliente(nombre, telefono, nuevaDireccion, nota, nuevaLat, nuevaLon, tipoDocumento, documento,
                correo, departamento, municipio, creditoMaximo, activo);
    }

    /** El mismo cliente con otro celular. */
    public Cliente conTelefono(String nuevoTelefono) {
        return new Cliente(nombre, nuevoTelefono, direccion, nota, lat, lon, tipoDocumento, documento, correo,
                departamento, municipio, creditoMaximo, activo);
    }

    /** El mismo cliente con otro correo. */
    public Cliente conCorreo(String nuevoCorreo) {
        return new Cliente(nombre, telefono, direccion, nota, lat, lon, tipoDocumento, documento, nuevoCorreo,
                departamento, municipio, creditoMaximo, activo);
    }

    /** El mismo cliente con otra nota para el domiciliario. */
    public Cliente conNota(String nuevaNota) {
        return new Cliente(nombre, telefono, direccion, nuevaNota, lat, lon, tipoDocumento, documento, correo,
                departamento, municipio, creditoMaximo, activo);
    }

    /** El mismo cliente activo o inactivo. */
    public Cliente conActivo(boolean nuevo) {
        return new Cliente(nombre, telefono, direccion, nota, lat, lon, tipoDocumento, documento, correo,
                departamento, municipio, creditoMaximo, nuevo);
    }

    public String getNombre() { return nombre; }
    public String getTelefono() { return telefono; }
    public String getDireccion() { return direccion; }
    public String getNota() { return nota; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }
    public String getTipoDocumento() { return tipoDocumento; }
    public String getDocumento() { return documento; }
    public String getCorreo() { return correo; }
    public String getDepartamento() { return departamento; }
    public String getMunicipio() { return municipio; }
    public long getCreditoMaximo() { return creditoMaximo; }
    public boolean isActivo() { return activo; }

    /** True si la direccion ya esta ubicada en el mapa (sirve para domicilios). */
    public boolean tieneUbicacion() {
        return lat != 0 || lon != 0;
    }

    /** Sigla del documento para mostrar: "CC 1052…", "NIT 900…". */
    public String documentoCorto() {
        if (documento.isEmpty()) {
            return "";
        }
        String sigla;
        switch (tipoDocumento) {
            case "NIT": sigla = "NIT"; break;
            case "Cédula de extranjería": sigla = "CE"; break;
            case "Tarjeta de identidad": sigla = "TI"; break;
            case "Pasaporte": sigla = "PA"; break;
            case "PPT (Permiso por protección temporal)": sigla = "PPT"; break;
            default: sigla = "CC";
        }
        return sigla + " " + documento;
    }

    @Override
    public String toString() {
        return nombre + " - " + direccion;
    }
}
