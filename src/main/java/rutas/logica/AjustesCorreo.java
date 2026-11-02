package rutas.logica;

import java.sql.SQLException;

/** El Gmail de la tienda para mandar los recibos (tabla config). La clave queda solo en este computador. */
public class AjustesCorreo {

    public String cuenta = "";
    public String clave = "";
    public String remitente = ""; // nombre que ve el cliente (vacio = el del negocio)

    public static AjustesCorreo leer(BaseTienda bd) throws SQLException {
        AjustesCorreo a = new AjustesCorreo();
        a.cuenta = bd.leerConfig("correo.cuenta", "");
        a.clave = bd.leerConfig("correo.clave", "");
        a.remitente = bd.leerConfig("correo.remitente", "");
        return a;
    }

    public boolean listo() {
        return CorreoSmtp.esCorreo(cuenta) && clave.replace(" ", "").length() >= 16;
    }

    /** Revisa y guarda. La clave se guarda sin espacios. */
    public void guardar(BaseTienda bd) throws SQLException {
        String c = cuenta.trim();
        String k = clave.replace(" ", "").trim();
        if (!c.isEmpty() && !CorreoSmtp.esCorreo(c)) {
            throw new IllegalArgumentException("Revise el correo (ej. mitienda@gmail.com)");
        }
        if (!k.isEmpty() && !k.matches("[A-Za-z]{16}")) {
            throw new IllegalArgumentException("La contraseña de aplicación tiene 16 letras (Google la muestra en"
                    + " grupos de 4)");
        }
        bd.guardarConfigs(new String[][]{{"correo.cuenta", c}, {"correo.clave", k},
            {"correo.remitente", remitente.trim()}});
        cuenta = c;
        clave = k;
    }
}
