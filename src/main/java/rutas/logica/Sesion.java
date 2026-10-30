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

    /** El administrador puede todo; el cajero solo vender y hacer domicilios. */
    public boolean puede(Permiso p) {
        return esAdmin();
    }
}
