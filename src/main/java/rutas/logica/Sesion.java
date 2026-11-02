package rutas.logica;

import rutas.modelo.Rol;
import rutas.modelo.Usuario;

/** Quien entro al programa y que puede ver. */
public class Sesion {

    private final Usuario usuario;

    public Sesion(Usuario usuario) {
        this.usuario = usuario;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public boolean esAdmin() {
        return usuario.getRol() == Rol.ADMIN;
    }

    /**
     * El administrador puede todo. El cajero vende, hace domicilios y consulta el inventario;
     * lo demás solo si el administrador se lo dio (Configuración › Usuarios).
     */
    public boolean puede(Permiso p) {
        if (esAdmin() || p == Permiso.VER_INVENTARIO) {
            return true;
        }
        if (!p.seDa()) {
            return false; // usuarios y configuración: solo el administrador
        }
        if (p == Permiso.VER_COSTOS && usuario.tienePermiso(Permiso.REGISTRAR_COMPRAS.name())) {
            return true; // quien registra compras ve los costos (los escribe)
        }
        return usuario.tienePermiso(p.name());
    }
}
