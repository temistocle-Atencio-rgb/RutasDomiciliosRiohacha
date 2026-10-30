package rutas.modelo;

/** Persona que entra al programa. La contraseña nunca se guarda aqui. */
public class Usuario {

    private final long id;
    private final String usuario;
    private final String nombre;
    private final Rol rol;
    private final boolean activo;

    public Usuario(long id, String usuario, String nombre, Rol rol, boolean activo) {
        this.id = id;
        this.usuario = usuario;
        this.nombre = nombre;
        this.rol = rol;
        this.activo = activo;
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
