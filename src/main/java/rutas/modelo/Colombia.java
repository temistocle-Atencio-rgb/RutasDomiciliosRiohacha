package rutas.modelo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Departamentos de Colombia y sus municipios principales (La Guajira completo, de los demas la capital). */
public final class Colombia {

    private Colombia() {
    }

    private static final Map<String, List<String>> MUNICIPIOS = new LinkedHashMap<>();

    static {
        poner("AMAZONAS", "LETICIA", "PUERTO NARIÑO");
        poner("ANTIOQUIA", "MEDELLÍN", "BELLO", "ENVIGADO", "ITAGÜÍ", "RIONEGRO", "APARTADÓ", "TURBO");
        poner("ARAUCA", "ARAUCA", "SARAVENA", "TAME");
        poner("ARCHIPIELAGO DE SAN ANDRES Y PROVIDENCIA", "SAN ANDRÉS", "PROVIDENCIA");
        poner("ATLANTICO", "BARRANQUILLA", "SOLEDAD", "MALAMBO", "PUERTO COLOMBIA", "SABANALARGA");
        poner("BOGOTA", "BOGOTÁ D.C.");
        poner("BOLIVAR", "CARTAGENA", "MAGANGUÉ", "TURBACO", "EL CARMEN DE BOLÍVAR");
        poner("BOYACA", "TUNJA", "DUITAMA", "SOGAMOSO", "CHIQUINQUIRÁ");
        poner("CALDAS", "MANIZALES", "LA DORADA", "CHINCHINÁ");
        poner("CAQUETA", "FLORENCIA", "SAN VICENTE DEL CAGUÁN");
        poner("CASANARE", "YOPAL", "AGUAZUL", "VILLANUEVA");
        poner("CAUCA", "POPAYÁN", "SANTANDER DE QUILICHAO", "PUERTO TEJADA");
        poner("CESAR", "VALLEDUPAR", "AGUACHICA", "CODAZZI", "LA PAZ", "SAN DIEGO", "BOSCONIA", "CURUMANÍ");
        poner("CHOCO", "QUIBDÓ", "ISTMINA");
        poner("CORDOBA", "MONTERÍA", "CERETÉ", "LORICA", "SAHAGÚN");
        poner("CUNDINAMARCA", "SOACHA", "FUSAGASUGÁ", "GIRARDOT", "ZIPAQUIRÁ", "FACATATIVÁ", "CHÍA", "MOSQUERA");
        poner("GUAINIA", "INÍRIDA");
        poner("GUAJIRA", "RIOHACHA", "ALBANIA", "BARRANCAS", "DIBULLA", "DISTRACCIÓN", "EL MOLINO", "FONSECA",
                "HATONUEVO", "LA JAGUA DEL PILAR", "MAICAO", "MANAURE", "SAN JUAN DEL CESAR", "URIBIA", "URUMITA",
                "VILLANUEVA");
        poner("GUAVIARE", "SAN JOSÉ DEL GUAVIARE");
        poner("HUILA", "NEIVA", "PITALITO", "GARZÓN", "LA PLATA");
        poner("MAGDALENA", "SANTA MARTA", "CIÉNAGA", "FUNDACIÓN", "EL BANCO", "PLATO", "ARACATACA");
        poner("META", "VILLAVICENCIO", "ACACÍAS", "GRANADA", "PUERTO LÓPEZ");
        poner("NARIÑO", "PASTO", "IPIALES", "TUMACO");
        poner("NORTE DE SANTANDER", "CÚCUTA", "OCAÑA", "PAMPLONA", "VILLA DEL ROSARIO", "LOS PATIOS");
        poner("PUTUMAYO", "MOCOA", "PUERTO ASÍS", "ORITO");
        poner("QUINDIO", "ARMENIA", "CALARCÁ", "MONTENEGRO");
        poner("RISARALDA", "PEREIRA", "DOSQUEBRADAS", "SANTA ROSA DE CABAL");
        poner("SANTANDER", "BUCARAMANGA", "FLORIDABLANCA", "GIRÓN", "PIEDECUESTA", "BARRANCABERMEJA", "SAN GIL");
        poner("SUCRE", "SINCELEJO", "COROZAL", "SAMPUÉS");
        poner("TOLIMA", "IBAGUÉ", "ESPINAL", "MELGAR", "HONDA");
        poner("VALLE DEL CAUCA", "CALI", "PALMIRA", "BUENAVENTURA", "TULUÁ", "BUGA", "CARTAGO", "JAMUNDÍ", "YUMBO");
        poner("VAUPES", "MITÚ");
        poner("VICHADA", "PUERTO CARREÑO");
    }

    private static void poner(String depto, String... municipios) {
        MUNICIPIOS.put(depto, List.of(municipios));
    }

    public static List<String> departamentos() {
        return List.copyOf(MUNICIPIOS.keySet());
    }

    /** Municipios de ese departamento (vacio si no se conoce: se escribe a mano). */
    public static List<String> municipios(String departamento) {
        return MUNICIPIOS.getOrDefault(departamento == null ? "" : departamento, List.of());
    }
}
