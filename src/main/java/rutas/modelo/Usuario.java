package rutas.modelo;

/** Persona que entra al programa. La contraseña nunca se guarda aqui. */
public class Usuario {

    private final long id;
    private final String usuario;
    private final String nombre;
    private final Rol rol;
    private final boolean activo;
    private final String permisos; // lo que el administrador le dio a un cajero, separado por comas

    public Usuario(long id, String usuario, String nombre, Rol rol, boolean activo) {
        this(id, usuario, nombre, rol, activo, "");
    }

    public Usuario(long id, String usuario, String nombre, Rol rol, boolean activo, String permisos) {
        this.id = id;
        this.usuario = usuario;
        this.nombre = nombre;
        this.rol = rol;
        this.activo = activo;
        this.permisos = permisos == null ? "" : permisos;
    }

    /** True si el administrador le dio ese permiso (por su nombre, por ejemplo "EDITAR_PRODUCTOS"). */
    public boolean tienePermiso(String nombre) {
        for (String p : permisos.split(",")) {
            if (p.trim().equals(nombre)) {
                return true;
            }
        }
        return false;
    }

    public String getPermisos() {
        return permisos;
    }

    public long getId() {
        return id;
    }

    public String getUsuario() {
        return usuario;
    }

    public String getNombre() {
        return nombre;
    }

    public Rol getRol() {
        return rol;
    }

    public boolean isActivo() {
        return activo;
    }
}
