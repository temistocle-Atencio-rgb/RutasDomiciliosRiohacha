package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import rutas.logica.ImportadorProductos;
import rutas.logica.Tarifa;
import rutas.modelo.Cliente;
import rutas.modelo.Colombia;
import rutas.modelo.Geo;
import rutas.modelo.Parada;
import rutas.modelo.Proveedor;

/**
 * «Agregar cliente» / «Editar cliente», como la referencia: cabeza azul y los mismos campos en el
 * mismo orden (tipo de documento, documento, nombre, telefono, correo, direccion, maximo de credito,
 * departamento, municipio y estado). Abajo, «Para domicilios»: la nota que ve el domiciliario.
 * Solo el nombre es obligatorio; si es NIT se le pone el digito de verificacion de la DIAN.
 * Se abre desde Clientes y desde el cobro (crear cliente nuevo).
 */
final class FormularioCliente {

    static final String[] ESTADOS = {"ACTIVO", "INACTIVO"};

    private FormularioCliente() {
    }

    /** Desde el cobro. textoInicial: lo que se habia escrito en el buscador (si son numeros, va al documento). */
    static void abrir(JComponent dentroDe, Aplicacion app, String textoInicial, Consumer<Cliente> alGuardar) {
        abrir(dentroDe, app, null, textoInicial, alGuardar);
    }

    /** viejo null = nuevo. alGuardar recibe el cliente ya guardado. */
    static void abrir(JComponent dentroDe, Aplicacion app, Cliente viejo, String textoInicial,
            Consumer<Cliente> alGuardar) {
        Capa[] capa = new Capa[1];
        JPanel modal = FormularioProveedor.modal(viejo == null ? "Agregar cliente" : "Editar cliente", 740,
                () -> capa[0].cerrar());
        JPanel t = (JPanel) ((BorderLayout) modal.getLayout()).getLayoutComponent(BorderLayout.CENTER);

        String[] tipos = new String[Cliente.TIPOS_DOCUMENTO.length + 1];
        tipos[0] = "";
        System.arraycopy(Cliente.TIPOS_DOCUMENTO, 0, tipos, 1, Cliente.TIPOS_DOCUMENTO.length);
        JComboBox<String> tipo = new JComboBox<>(tipos);
        Tema.estiloCombo(tipo, s -> s.isEmpty() ? "Seleccionar…" : s);
        JTextField documento = Tema.campo("Número de documento");
        JTextField nombre = Tema.campo("Nombre completo");
        JTextField telefono = Tema.campo("Número de celular");
        JTextField correo = Tema.campo("correo@dominio.com");
        JTextField direccion = Tema.campo("Ej. Calle 15 # 7-20, barrio Centro");
        JTextField credito = Controles.campoNumero("Sin límite");
        JComboBox<String> depto = new JComboBox<>(Colombia.departamentos().toArray(new String[0]));
        Tema.estiloCombo(depto, String::valueOf);
        depto.setMaximumRowCount(14);
        JComboBox<String> muni = new JComboBox<>();
        muni.setEditable(true); // si el municipio no esta en la lista se escribe
        Tema.estiloCombo(muni, String::valueOf);
        JComboBox<String> estado = new JComboBox<>(ESTADOS);
        Tema.estiloCombo(estado, String::valueOf);
        JTextField nota = Tema.campo("Ej. casa esquinera, portón verde; timbrar dos veces");

        Runnable llenarMunicipios = () -> {
            Object antes = muni.getEditor().getItem();
            muni.removeAllItems();
            for (String m : Colombia.municipios((String) depto.getSelectedItem())) {
                muni.addItem(m);
            }
            if (antes != null && Colombia.municipios((String) depto.getSelectedItem()).contains(String.valueOf(antes))) {
                muni.setSelectedItem(antes);
            }
        };
        if (viejo != null) {
            tipo.setSelectedItem(viejo.getTipoDocumento());
            documento.setText(viejo.getDocumento());
            nombre.setText(viejo.getNombre());
            telefono.setText(viejo.getTelefono());
            correo.setText(viejo.getCorreo());
            direccion.setText(viejo.getDireccion());
            credito.setText(viejo.getCreditoMaximo() > 0 ? String.valueOf(viejo.getCreditoMaximo()) : "");
            estado.setSelectedIndex(viejo.isActivo() ? 0 : 1);
            nota.setText(viejo.getNota());
        }
        String deptoGuardado = viejo == null ? "" : viejo.getDepartamento().trim();
        String deptoLista = buscarDepartamento(deptoGuardado);
        if (deptoLista == null && !deptoGuardado.isEmpty()) {
            deptoLista = deptoGuardado.toUpperCase(); // no esta en la lista: se deja como estaba (no se cambia solo)
            depto.addItem(deptoLista);
        }
        depto.setSelectedItem(deptoLista == null ? "GUAJIRA" : deptoLista);
        llenarMunicipios.run();
        String m0 = viejo == null || viejo.getMunicipio().isBlank()
                ? (app.getCiudad() == null ? "RIOHACHA" : app.getCiudad().getNombre()) : viejo.getMunicipio();
        muni.setSelectedItem(m0.trim().toUpperCase());
        depto.addActionListener(e -> llenarMunicipios.run());
        String ini = textoInicial == null ? "" : textoInicial.trim();
        if (viejo == null && !ini.isEmpty()) {
            if (ini.replaceAll("[\\s.-]", "").matches("\\d{5,}")) {
                (ini.replaceAll("[\\s.-]", "").length() == 10 && ini.startsWith("3") ? telefono : documento)
                        .setText(ini.replaceAll("[\\s.]", ""));
            } else {
                nombre.setText(ini);
            }
        }

        JPanel g = new JPanel(new GridBagLayout());
        g.setOpaque(false);
        g.setAlignmentX(Component.LEFT_ALIGNMENT);
        poner(g, 0, 0, "Tipo de documento", tipo, null);
        poner(g, 1, 0, "Documento", documento, null);
        poner(g, 0, 1, "Nombre del cliente *", nombre, null);
        poner(g, 1, 1, "Teléfono", telefono, null);
        poner(g, 0, 2, "Correo electrónico", correo, null);
        poner(g, 1, 2, "Dirección", direccion, null);
        poner(g, 0, 3, "Máximo de crédito", credito, "Déjelo vacío o en cero para no aplicar límite.");
        poner(g, 1, 3, "Departamento", depto, null);
        poner(g, 0, 4, "Municipio", muni, null);
        poner(g, 1, 4, "Estado", estado, null);
        t.add(g);

        t.add(Tema.seccion("Para domicilios"));
        JPanel g2 = new JPanel(new GridBagLayout());
        g2.setOpaque(false);
        g2.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel celdaNota = poner(g2, 0, 0, "Nota para el domiciliario", nota,
                "Le sale al domiciliario en el mensaje de la salida. El punto del mapa se saca de la dirección.");
        GridBagConstraints kn = ((GridBagLayout) g2.getLayout()).getConstraints(celdaNota);
        kn.gridwidth = 2;
        kn.insets = new Insets(0, 0, 4, 0);
        ((GridBagLayout) g2.getLayout()).setConstraints(celdaNota, kn);
        t.add(g2);

        JLabel error = new JLabel(" ");
        error.setFont(Tema.textoFuerte(13));
        error.setForeground(Tema.ERROR_TEXTO);
        error.setAlignmentX(Component.LEFT_ALIGNMENT);
        t.add(error);
        t.add(Tema.espacio(6));
        Tema.Boton guardar = Tema.botonAccion(viejo == null ? "Agregar" : "Guardar cambios");
        JPanel pie = new JPanel(new BorderLayout());
        pie.setOpaque(false);
        pie.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel obligatorio = new JLabel("Solo el nombre es obligatorio.");
        obligatorio.setFont(Tema.texto(12.5f));
        obligatorio.setForeground(Tema.GRIS_TEXTO);
        pie.add(obligatorio, BorderLayout.WEST);
        pie.add(guardar, BorderLayout.EAST);
        t.add(pie);

        capa[0] = new Capa(dentroDe, modal, true);
        Runnable hacer = () -> {
            Object m = muni.getEditor().getItem();
            Cliente c;
            try {
                c = armar(app.getClientes().enOrden(), viejo, (String) tipo.getSelectedItem(), documento.getText(),
                        nombre.getText(), telefono.getText(), correo.getText(), direccion.getText(),
                        (String) depto.getSelectedItem(), m == null ? "" : String.valueOf(m), credito.getText(),
                        estado.getSelectedIndex() == 0, nota.getText());
            } catch (IllegalArgumentException ex) {
                error.setText(ex.getMessage());
                return;
            }
            String aviso = null;
            boolean otraDireccion = viejo == null
                    || !Geo.normalizar(viejo.getDireccion()).equals(Geo.normalizar(c.getDireccion()));
            if (!c.getDireccion().isEmpty() && (otraDireccion || !viejo.tieneUbicacion())) {
                if (app.getGeo() != null) {
                    try {
                        Parada p = app.getGeo().ubicar(c.getDireccion());
                        c = c.conDireccion(c.getDireccion(), p.getLat(), p.getLon());
                    } catch (IllegalArgumentException ex) {
                        c = c.conDireccion(c.getDireccion(), 0, 0);
                        aviso = "La dirección no se encontró en el mapa; se ubica cuando se haga el domicilio.";
                    }
                } else {
                    c = c.conDireccion(c.getDireccion(), 0, 0);
                }
            } else if (c.getDireccion().isEmpty()) {
                c = c.conDireccion("", 0, 0);
            }
            try {
                if (viejo == null) {
                    app.guardarCliente(c);
                } else {
                    app.reemplazarCliente(viejo.getNombre(), c);
                }
            } catch (IllegalArgumentException ex) {
                error.setText(ex.getMessage());
                return;
            } catch (java.io.IOException ex) {
                error.setText("No se pudo guardar: " + ex.getMessage());
                return;
            }
            capa[0].cerrar();
            alGuardar.accept(c);
            Aviso.mostrar(dentroDe, Aviso.Tipo.BIEN, viejo == null ? "Cliente agregado" : "Cliente guardado",
                    aviso != null ? aviso : c.getNombre());
        };
        guardar.addActionListener(e -> hacer.run());
        for (JTextField f : new JTextField[]{documento, nombre, telefono, correo, direccion, credito, nota}) {
            f.addActionListener(e -> hacer.run());
        }
        capa[0].mostrar(viejo != null || !nombre.getText().isEmpty() ? nombre : tipo);
    }

    /**
     * Revisa lo escrito y arma el cliente (sin ubicar: lat y lon del viejo si lo hay). Lanza
     * IllegalArgumentException con un mensaje claro. todos: los clientes guardados (para no repetir
     * nombre ni documento); viejo: el que se esta editando (null si es nuevo).
     */
    static Cliente armar(List<Cliente> todos, Cliente viejo, String tipo, String documento, String nombre,
            String telefono, String correo, String direccion, String departamento, String municipio,
            String credito, boolean activo, String nota) {
        String n = limpio(nombre).replaceAll("\\s+", " ");
        if (n.length() < 3) {
            throw new IllegalArgumentException("Escriba el nombre del cliente");
        }
        String clave = Geo.normalizar(n);
        String claveVieja = viejo == null ? null : Geo.normalizar(viejo.getNombre());
        String doc = limpio(documento).replaceAll("[\\s.]", "");
        String tip = limpio(tipo);
        if (!doc.isEmpty() && !doc.matches("[0-9A-Za-z-]{4,20}")) {
            throw new IllegalArgumentException("El documento solo lleva números (y guion en el NIT)");
        }
        if (!doc.isEmpty() && tip.isEmpty()) {
            tip = Cliente.TIPOS_DOCUMENTO[0]; // si no escogio, lo normal es la cedula
        }
        if ("NIT".equals(tip) && !doc.isEmpty()) {
            doc = conDigito(doc);
        }
        String tel = limpio(telefono).replaceAll("[\\s-]", "");
        if (!tel.isEmpty() && !tel.matches("\\d{7,10}")) {
            throw new IllegalArgumentException("El teléfono lleva 10 números (o 7 si es fijo)");
        }
        String mail = limpio(correo);
        if (!mail.isEmpty() && !mail.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("Revise el correo (ej. nombre@gmail.com)");
        }
        long tope;
        try {
            tope = limpio(credito).isEmpty() ? 0 : ImportadorProductos.leerPesos(credito);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("El máximo de crédito va en pesos, sin centavos");
        }
        for (Cliente otro : todos) {
            String k = Geo.normalizar(otro.getNombre());
            if (k.equals(claveVieja)) {
                continue; // es el mismo que se esta editando
            }
            if (k.equals(clave)) {
                throw new IllegalArgumentException(otro.isActivo() ? "Ya hay un cliente llamado «" + otro.getNombre()
                        + "». Agregue el apellido para diferenciarlo."
                        : "«" + otro.getNombre() + "» ya existe pero está inactivo: actívelo en Clientes");
            }
            if (!doc.isEmpty() && soloDigitos(otro.getDocumento()).equals(soloDigitos(doc))
                    && !soloDigitos(doc).isEmpty()) {
                throw new IllegalArgumentException("Ese documento ya es de «" + otro.getNombre() + "»");
            }
        }
        double lat = viejo == null ? 0 : viejo.getLat();
        double lon = viejo == null ? 0 : viejo.getLon();
        String dir = rutas.logica.Textos.limpiar(direccion);
        String not = rutas.logica.Textos.limpiar(nota);
        // lo de antes que no se toco no se vuelve a revisar contra los largos nuevos
        if (viejo == null || !dir.equals(rutas.logica.Textos.limpiar(viejo.getDireccion()))) {
            rutas.logica.Textos.revisar(dir, "La dirección", rutas.logica.Textos.DIRECCION, false);
        }
        if (viejo == null || !not.equals(rutas.logica.Textos.limpiar(viejo.getNota()))) {
            rutas.logica.Textos.revisar(not, "La nota", rutas.logica.Textos.NOTA, false);
        }
        return new Cliente(n, tel, dir, not, lat, lon, tip, doc, mail,
                limpio(departamento), limpio(municipio).toUpperCase(), tope, activo);
    }

    /** NIT con su digito de verificacion: "900123456" queda "900123456-8"; si trae uno equivocado, error. */
    static String conDigito(String nit) {
        String s = nit.trim();
        String base = s.contains("-") ? s.substring(0, s.lastIndexOf('-')) : s;
        String puesto = s.contains("-") ? s.substring(s.lastIndexOf('-') + 1) : "";
        base = base.replaceAll("[^0-9]", "");
        if (base.isEmpty()) {
            throw new IllegalArgumentException("El NIT lleva solo números");
        }
        String dv = Proveedor.digitoVerificacion(base);
        if (!puesto.isEmpty() && !puesto.equals(dv)) {
            throw new IllegalArgumentException("El dígito de verificación de ese NIT es " + dv + ", no " + puesto);
        }
        return base + "-" + dv;
    }

    private static String soloDigitos(String s) {
        return s == null ? "" : s.replaceAll("[^0-9A-Za-z]", "").toUpperCase();
    }

    private static String limpio(String s) {
        return s == null ? "" : s.trim();
    }

    /** "La Guajira" o "guajira" -> "GUAJIRA" (como sale en la lista). Vacio o raro: GUAJIRA. */
    static String departamentoDeLista(String valor) {
        String d = buscarDepartamento(valor);
        return d == null ? "GUAJIRA" : d;
    }

    /** El de la lista que corresponde a lo escrito, o null si no esta. */
    static String buscarDepartamento(String valor) {
        String v = Geo.normalizar(valor == null ? "" : valor);
        if (!v.isEmpty()) {
            for (String d : Colombia.departamentos()) {
                String dn = Geo.normalizar(d);
                if (dn.equals(v) || v.endsWith(" " + dn) || dn.endsWith(" " + v)) {
                    return d;
                }
            }
        }
        return null;
    }

    /** Campo con su titulo arriba (y una ayuda gris debajo si la hay), en una de las dos columnas. */
    private static JPanel poner(JPanel g, int x, int y, String etiqueta, JComponent c, String ayuda) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel l = new JLabel(etiqueta);
        l.setFont(Tema.textoFuerte(13));
        l.setForeground(Tema.GRIS_OSCURO);
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setLabelFor(c);
        p.add(l);
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setPreferredSize(new Dimension(200, 40));
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        c.getAccessibleContext().setAccessibleName(etiqueta.replace(" *", ""));
        p.add(c);
        if (ayuda != null) {
            JLabel a = new JLabel(ayuda);
            a.setFont(Tema.texto(12.5f));
            a.setForeground(Tema.GRIS_TEXTO);
            a.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
            a.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(a);
        }
        GridBagConstraints k = new GridBagConstraints();
        k.gridx = x;
        k.gridy = y;
        k.weightx = 1;
        k.fill = GridBagConstraints.HORIZONTAL;
        k.anchor = GridBagConstraints.NORTHWEST;
        k.insets = new Insets(0, 0, 12, x == 0 ? 16 : 0);
        g.add(p, k);
        return p;
    }

    /** Texto corto de un cliente para listas: "CC 1052… · NOMBRE · 300…". */
    static String linea(Cliente c) {
        StringBuilder b = new StringBuilder();
        if (!c.documentoCorto().isEmpty()) {
            b.append(c.documentoCorto()).append("  ·  ");
        }
        b.append(c.getNombre());
        if (!c.getTelefono().isEmpty()) {
            b.append("  ·  ").append(c.getTelefono());
        }
        if (c.getCreditoMaximo() > 0) {
            b.append("  ·  fía hasta ").append(Tarifa.pesos(c.getCreditoMaximo()));
        }
        return b.toString();
    }

    /** True si el texto del buscador coincide con el cliente (nombre, documento o celular). */
    static boolean coincide(Cliente c, String texto) {
        String t = texto.trim();
        String num = t.replaceAll("[\\s.-]", "");
        if (num.matches("\\d+")) {
            return c.getDocumento().replaceAll("[\\s.-]", "").startsWith(num)
                    || c.getTelefono().replaceAll("[\\s-]", "").startsWith(num);
        }
        String clave = Geo.normalizar(t);
        if (Geo.normalizar(c.getNombre()).startsWith(clave)) {
            return true;
        }
        for (String palabra : Geo.normalizar(c.getNombre()).split(" ")) {
            if (palabra.startsWith(clave)) {
                return true; // tambien por el apellido
            }
        }
        return false;
    }
}
