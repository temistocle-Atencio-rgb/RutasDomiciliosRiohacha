package rutas.vista;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import rutas.modelo.Cliente;
import rutas.modelo.Geo;

/**
 * «Importar clientes» desde Excel (.xlsx) o CSV. Las columnas se reconocen por el titulo, en
 * cualquier orden (sirven los de la lista de clientes: «Nombre del cliente», «Número Nit/Doc»…).
 * Solo el nombre es obligatorio. Si el nombre ya existe, ese cliente se actualiza con lo que traiga
 * la fila. Se revisan todas las filas con las mismas reglas del formulario y, si alguna tiene un
 * error, no se guarda nada y se dice cual fila revisar.
 */
public final class ImportadorClientes {

    /** Las columnas del formato que se descarga. */
    public static final String[] COLUMNAS = {"tipo_documento", "documento", "nombre", "telefono", "correo",
        "direccion", "departamento", "municipio", "maximo_credito", "estado", "nota"};

    private static final Map<String, String> ALIAS = new HashMap<>();

    static {
        alias("tipo_documento", "tipodocumento", "tipodedocumento", "tipodedoc", "tipodoc", "tipo");
        alias("documento", "documento", "numeronitdoc", "nitdoc", "numerodocumento", "numerodedocumento", "cedula",
                "nit", "numero", "identificacion");
        alias("nombre", "nombre", "nombredelcliente", "nombrecliente", "cliente", "nombrecompleto");
        alias("telefono", "telefono", "celular", "tel", "movil");
        alias("correo", "correo", "correoelectronico", "email", "mail");
        alias("direccion", "direccion");
        alias("departamento", "departamento", "depto");
        alias("municipio", "municipio", "ciudad");
        alias("maximo_credito", "maximocredito", "maximodecredito", "credito", "cupo", "topefiado", "fiahasta");
        alias("estado", "estado");
        alias("nota", "nota", "notaparaeldomiciliario", "notadomiciliario", "notas");
    }

    private static void alias(String campo, String... nombres) {
        for (String n : nombres) {
            ALIAS.put(n, campo);
        }
    }

    private ImportadorClientes() {
    }

    /** Lo que trae el archivo, ya revisado. */
    public static class Resultado {
        public final List<Cliente> nuevos = new ArrayList<>();
        public final List<Cliente> actualizados = new ArrayList<>();
        public final List<String> errores = new ArrayList<>();

        public List<Cliente> todos() {
            List<Cliente> l = new ArrayList<>(nuevos);
            l.addAll(actualizados);
            return l;
        }
    }

    /** filas: la primera son los titulos. guardados: los clientes que ya hay. */
    public static Resultado revisar(List<String[]> filas, List<Cliente> guardados) {
        Resultado r = new Resultado();
        if (filas.isEmpty()) {
            r.errores.add("El archivo está vacío");
            return r;
        }
        Map<String, Integer> col = new HashMap<>();
        String[] titulos = filas.get(0);
        for (int i = 0; i < titulos.length; i++) {
            String k = Geo.normalizar(titulos[i] == null ? "" : titulos[i]).replaceAll("[^a-z0-9]", "");
            String campo = ALIAS.get(k);
            if (campo != null && !col.containsKey(campo)) {
                col.put(campo, i);
            }
        }
        if (!col.containsKey("nombre")) {
            r.errores.add("Falta la columna «nombre». Descargue el formato para ver cómo va.");
            return r;
        }
        Map<String, Cliente> porClave = new HashMap<>();
        for (Cliente c : guardados) {
            porClave.put(Geo.normalizar(c.getNombre()), c);
        }
        List<Cliente> vistos = new ArrayList<>(guardados); // para no repetir nombre ni documento
        Map<String, Integer> filaDe = new HashMap<>();
        for (int i = 1; i < filas.size(); i++) {
            String[] f = filas.get(i);
            if (vacia(f)) {
                continue;
            }
            int numero = i + 1; // como la ve en Excel
            String nombre = celda(f, col, "nombre");
            String clave = Geo.normalizar(nombre.replaceAll("\\s+", " "));
            if (!clave.isEmpty() && filaDe.containsKey(clave)) {
                r.errores.add("Fila " + numero + ": «" + nombre + "» ya está en la fila " + filaDe.get(clave));
                continue;
            }
            if (!clave.isEmpty()) {
                filaDe.put(clave, numero); // aunque la fila tenga error, el nombre repetido mas abajo se avisa
            }
            Cliente viejo = porClave.get(clave);
            try {
                String tipo = tipoDocumento(celda(f, col, "tipo_documento"));
                Boolean activo = estado(celda(f, col, "estado"));
                Cliente c = FormularioCliente.armar(vistos, viejo,
                        o(tipo, viejo == null ? "" : viejo.getTipoDocumento()),
                        o(celda(f, col, "documento"), viejo == null ? "" : viejo.getDocumento()),
                        nombre,
                        o(celda(f, col, "telefono"), viejo == null ? "" : viejo.getTelefono()),
                        o(celda(f, col, "correo"), viejo == null ? "" : viejo.getCorreo()),
                        o(celda(f, col, "direccion"), viejo == null ? "" : viejo.getDireccion()),
                        departamento(o(celda(f, col, "departamento"), viejo == null ? "" : viejo.getDepartamento())),
                        o(celda(f, col, "municipio"), viejo == null ? "" : viejo.getMunicipio()),
                        o(celda(f, col, "maximo_credito"),
                                viejo == null || viejo.getCreditoMaximo() == 0 ? "" : String.valueOf(viejo.getCreditoMaximo())),
                        activo != null ? activo : viejo == null || viejo.isActivo(),
                        o(celda(f, col, "nota"), viejo == null ? "" : viejo.getNota()));
                if (viejo != null && !Geo.normalizar(viejo.getDireccion()).equals(Geo.normalizar(c.getDireccion()))) {
                    c = c.conDireccion(c.getDireccion(), 0, 0); // otra direccion: se ubica en el proximo domicilio
                }
                if (viejo != null) {
                    vistos.removeIf(x -> Geo.normalizar(x.getNombre()).equals(clave));
                    r.actualizados.add(c);
                } else {
                    r.nuevos.add(c);
                }
                vistos.add(c);
            } catch (IllegalArgumentException e) {
                r.errores.add("Fila " + numero + (nombre.isEmpty() ? "" : " (" + nombre + ")") + ": " + e.getMessage());
            }
        }
        if (r.errores.isEmpty() && r.nuevos.isEmpty() && r.actualizados.isEmpty()) {
            r.errores.add("El archivo no trae clientes (solo los títulos)");
        }
        return r;
    }

    /** "CC", "cédula", "NIT", "CE", "TI", "PA", "PPT" o el nombre completo. "" si viene vacio. */
    static String tipoDocumento(String texto) {
        String t = Geo.normalizar(texto).replaceAll("[^a-z ]", "").trim();
        if (t.isEmpty()) {
            return "";
        }
        for (String tipo : Cliente.TIPOS_DOCUMENTO) {
            if (Geo.normalizar(tipo).replaceAll("[^a-z ]", "").trim().equals(t)) {
                return tipo;
            }
        }
        switch (t.replace(" ", "")) {
            case "cc":
            case "cedula":
                return Cliente.TIPOS_DOCUMENTO[0];
            case "nit":
                return "NIT";
            case "ce":
                return Cliente.TIPOS_DOCUMENTO[2];
            case "ti":
                return Cliente.TIPOS_DOCUMENTO[3];
            case "pa":
            case "ps":
            case "pasaporte":
                return Cliente.TIPOS_DOCUMENTO[4];
            case "ppt":
                return Cliente.TIPOS_DOCUMENTO[5];
            default:
                throw new IllegalArgumentException("Tipo de documento no reconocido: " + texto.trim()
                        + " (use CC, NIT, CE, TI, PA o PPT)");
        }
    }

    /** ACTIVO / INACTIVO (tambien si/no). null si viene vacio. */
    static Boolean estado(String texto) {
        switch (Geo.normalizar(texto).trim()) {
            case "":
                return null;
            case "activo":
            case "activa":
            case "si":
            case "1":
                return Boolean.TRUE;
            case "inactivo":
            case "inactiva":
            case "no":
            case "0":
                return Boolean.FALSE;
            default:
                throw new IllegalArgumentException("El estado es ACTIVO o INACTIVO, no «" + texto.trim() + "»");
        }
    }

    /** Si coincide con uno de la lista se escribe como en la lista; si no, como venga. */
    private static String departamento(String texto) {
        if (texto.isBlank()) {
            return "";
        }
        String d = FormularioCliente.buscarDepartamento(texto);
        return d != null ? d : texto.trim().toUpperCase();
    }

    private static String o(String valor, String siVacio) {
        return valor.isBlank() ? siVacio : valor;
    }

    private static String celda(String[] f, Map<String, Integer> col, String campo) {
        Integer i = col.get(campo);
        if (i == null || i >= f.length || f[i] == null) {
            return "";
        }
        String v = f[i].trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1).trim();
        }
        if (v.matches("\\d+\\.0")) {
            v = v.substring(0, v.length() - 2); // numeros que Excel guarda como 3001234567.0
        }
        return v;
    }

    private static boolean vacia(String[] f) {
        for (String c : f) {
            if (c != null && !c.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
