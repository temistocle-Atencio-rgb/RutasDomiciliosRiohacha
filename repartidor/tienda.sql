-- DomiRuta: tienda en línea. El cliente ve el catálogo, arma su pedido y lo envía; DomiRuta lo recibe.
-- El catálogo lo sube DomiRuta (es el dueño): aquí nadie de afuera puede cambiarlo.
-- Se corre DESPUÉS de en-vivo.sql y sesiones.sql. Se puede correr otra vez sin dañar nada.

-- =====================================================================
-- Tablas (cerradas: la página solo usa las funciones de abajo)
-- =====================================================================

-- Los datos de la tienda que ve el cliente (una sola fila)
create table if not exists public.tienda_info (
  id int primary key default 1 check (id = 1),
  nombre text not null default '' check (length(nombre) <= 80),
  logo text check (logo is null or length(logo) <= 500),            -- URL de la foto
  direccion text not null default '' check (length(direccion) <= 200),
  telefono text not null default '' check (length(telefono) <= 20),
  horario text not null default '' check (length(horario) <= 120),
  abierta boolean not null default true,
  domicilio int not null default 0 check (domicilio between 0 and 1000000),
  minimo int not null default 0 check (minimo between 0 and 10000000),
  actualizado timestamptz not null default now()
);
insert into public.tienda_info (id) values (1) on conflict do nothing;
-- Los medios de pago que acepta la tienda (los mismos nombres de DomiRuta, en minúscula: efectivo, nequi, ...)
alter table public.tienda_info add column if not exists pagos text[] not null default '{efectivo}';

-- Los productos que se venden en línea (solo los que la tienda marcó en DomiRuta)
create table if not exists public.catalogo (
  id bigint primary key,                                            -- el id del producto en DomiRuta
  nombre text not null check (length(nombre) between 1 and 120),
  descripcion text not null default '' check (length(descripcion) <= 300),
  marca text not null default '' check (length(marca) <= 60),
  categoria text not null default '' check (length(categoria) <= 60),
  precio int not null check (precio between 0 and 100000000),
  foto text check (foto is null or length(foto) <= 500),
  disponible boolean not null default true,                         -- false = «Agotado» (nunca la cantidad)
  orden int not null default 0,
  actualizado timestamptz not null default now()
);

-- La ficha del producto (como la del D1): código de barras y características de DomiRuta
alter table public.catalogo add column if not exists codigo text not null default '';
alter table public.catalogo add column if not exists caracteristicas jsonb not null default '[]';
alter table public.catalogo drop constraint if exists catalogo_ficha_check;
alter table public.catalogo add constraint catalogo_ficha_check check (
  length(codigo) <= 40 and jsonb_typeof(caracteristicas) = 'array' and jsonb_array_length(caracteristicas) <= 20
  and length(caracteristicas::text) <= 3000);

-- Los pedidos que llegan de la página
create table if not exists public.pedidos_web (
  id bigint generated always as identity primary key,
  codigo text not null unique,
  creado timestamptz not null default now(),
  nombre text not null,
  celular text not null,
  correo text not null default '',
  entrega text not null default 'domicilio' check (entrega in ('domicilio', 'retiro')),
  direccion text not null default '',
  referencia text not null default '',
  lat double precision,
  lon double precision,
  pago text not null check (pago ~ '^[a-z_]{2,20}$'),
  paga_con int,
  nota text not null default '',
  items jsonb not null,                                             -- [{id, nombre, precio, cantidad}] con el precio REAL
  subtotal int not null,
  domicilio int not null,
  total int not null,
  estado text not null default 'nuevo'
    check (estado in ('nuevo', 'aceptado', 'preparando', 'en_camino', 'entregado', 'cancelado')),
  estado_en timestamptz not null default now(),
  motivo text not null default '',                                  -- por qué se canceló
  tomado boolean not null default false,                            -- DomiRuta ya lo bajó
  ip text not null default ''
);
-- Antes solo había efectivo y transferencia: ahora vale cualquier medio que la tienda tenga activo
alter table public.pedidos_web drop constraint if exists pedidos_web_pago_check;
alter table public.pedidos_web add constraint pedidos_web_pago_check check (pago ~ '^[a-z_]{2,20}$');
create index if not exists pedidos_web_tomado_idx on public.pedidos_web (tomado) where not tomado;

alter table public.tienda_info enable row level security;
alter table public.catalogo enable row level security;
alter table public.pedidos_web enable row level security;
revoke all on public.tienda_info, public.catalogo, public.pedidos_web from anon, authenticated;
grant select, insert, update, delete on public.tienda_info, public.catalogo, public.pedidos_web to service_role;
grant usage, select on all sequences in schema public to service_role;

-- Fotos del catálogo: carpeta pública de solo lectura (las sube DomiRuta con la clave secreta)
do $$
begin
  if exists (select 1 from information_schema.tables where table_schema = 'storage' and table_name = 'buckets') then
    insert into storage.buckets (id, name, public) values ('catalogo', 'catalogo', true) on conflict (id) do nothing;
  end if;
end $$;

-- =====================================================================
-- Funciones de la página (lo único que puede hacer alguien de afuera)
-- =====================================================================

-- El catálogo y los datos de la tienda (sin costos, sin cantidades, sin proveedores)
create or replace function public.ver_catalogo()
returns json
language sql stable security definer set search_path = public as $$
  select json_build_object(
    'tienda', (select json_build_object('nombre', nombre, 'logo', logo, 'direccion', direccion, 'telefono', telefono,
                                        'horario', horario, 'abierta', abierta, 'domicilio', domicilio, 'minimo', minimo,
                                        'pagos', to_json(pagos))
               from tienda_info where id = 1),
    'productos', coalesce((select json_agg(json_build_object('id', id, 'nombre', nombre, 'descripcion', descripcion,
                                        'marca', marca, 'categoria', categoria, 'precio', precio, 'foto', foto,
                                        'disponible', disponible, 'codigo', codigo,
                                        'caracteristicas', caracteristicas) order by categoria, orden, nombre)
                           from catalogo), '[]'::json));
$$;

-- Hacer el pedido. Los precios y lo agotado se revisan AQUÍ (lo que mande la página no se cree).
-- Devuelve {ok:true, codigo, total} o {ok:false, error, detalle}.
create or replace function public.hacer_pedido(p json)
returns json
language plpgsql security definer set search_path = public as $$
declare
  t tienda_info%rowtype;
  v_nombre text := trim(coalesce(p->>'nombre', ''));
  v_cel text := dr_celular(p->>'celular');
  v_correo text := lower(trim(coalesce(p->>'correo', '')));
  v_entrega text := coalesce(nullif(p->>'entrega', ''), 'domicilio');
  v_dir text := trim(coalesce(p->>'direccion', ''));
  v_ref text := trim(coalesce(p->>'referencia', ''));
  v_lat double precision;
  v_lon double precision;
  v_pago text := coalesce(p->>'pago', '');
  v_paga int;
  v_nota text := trim(coalesce(p->>'nota', ''));
  v_items jsonb := '[]'::jsonb;
  v_sub int := 0;
  v_dom int;
  v_total int;
  agotados text := '';
  x json;
  c catalogo%rowtype;
  n int;
  v_codigo text;
begin
  select * into t from tienda_info where id = 1;
  if not t.abierta then
    return json_build_object('ok', false, 'error', 'cerrada', 'detalle', 'La tienda está cerrada ahora');
  end if;
  if length(v_nombre) not between 2 and 80 then
    return json_build_object('ok', false, 'error', 'datos', 'detalle', 'Escribe tu nombre');
  end if;
  if v_cel !~ '^3[0-9]{9}$' then
    return json_build_object('ok', false, 'error', 'datos', 'detalle', 'El celular son 10 números y empieza por 3');
  end if;
  if v_correo <> '' and (length(v_correo) > 120 or v_correo !~ '^[^@\s]+@[^@\s]+\.[^@\s]+$') then
    return json_build_object('ok', false, 'error', 'datos', 'detalle', 'Revisa el correo (o déjalo vacío)');
  end if;
  if v_entrega not in ('domicilio', 'retiro') then
    v_entrega := 'domicilio';
  end if;
  if v_entrega = 'domicilio' and length(v_dir) not between 5 and 200 then
    return json_build_object('ok', false, 'error', 'datos', 'detalle', 'Escribe la dirección');
  end if;
  if length(v_ref) > 200 or length(v_nota) > 300 then
    return json_build_object('ok', false, 'error', 'datos', 'detalle', 'La referencia o la nota son muy largas');
  end if;
  begin
    v_lat := (p->>'lat')::double precision;
    v_lon := (p->>'lon')::double precision;
  exception when others then
    v_lat := null;
    v_lon := null;
  end;
  if v_lat is not null and (v_lat not between -90 and 90 or v_lon is null or v_lon not between -180 and 180) then
    v_lat := null;
    v_lon := null;
  end if;
  if not (v_pago = any(t.pagos)) then
    return json_build_object('ok', false, 'error', 'datos', 'detalle', 'Escoge cómo vas a pagar');
  end if;
  if json_typeof(p->'items') <> 'array' or json_array_length(p->'items') not between 1 and 60 then
    return json_build_object('ok', false, 'error', 'datos', 'detalle', 'El pedido está vacío');
  end if;
  for x in select * from json_array_elements(p->'items') loop
    begin
      n := (x->>'cantidad')::int;
    exception when others then
      n := 0;
    end;
    if n not between 1 and 99 then
      return json_build_object('ok', false, 'error', 'datos', 'detalle', 'Revisa las cantidades');
    end if;
    select * into c from catalogo where id = (x->>'id')::bigint;
    if not found or not c.disponible then
      agotados := agotados || case when agotados = '' then '' else ', ' end || coalesce(c.nombre, 'un producto');
      continue;
    end if;
    v_items := v_items || jsonb_build_object('id', c.id, 'nombre', c.nombre, 'precio', c.precio, 'cantidad', n);
    v_sub := v_sub + c.precio * n;
  end loop;
  if agotados <> '' then
    return json_build_object('ok', false, 'error', 'agotado', 'detalle', 'Ya no hay: ' || agotados);
  end if;
  if v_sub < t.minimo then
    return json_build_object('ok', false, 'error', 'minimo', 'detalle', 'El pedido mínimo es de $ ' || t.minimo);
  end if;
  v_dom := case when v_entrega = 'domicilio' then t.domicilio else 0 end;
  v_total := v_sub + v_dom;
  begin
    v_paga := (p->>'pagaCon')::int;
  exception when others then
    v_paga := null;
  end;
  if v_pago <> 'efectivo' or v_paga is null or v_paga <= 0 then
    v_paga := null;
  elsif v_paga < v_total then
    return json_build_object('ok', false, 'error', 'datos', 'detalle', 'Con lo que pagas no alcanza para el total');
  end if;
  -- contra abusos: solo cuentan los pedidos válidos (equivocarse llenando no bloquea a nadie). Por conexión es alto
  -- porque en Colombia muchos celulares de la misma empresa salen por la misma.
  if not dr_limite('pedido-ip:' || dr_ip(), 20, interval '10 minutes')
     or not dr_limite('pedido-cel:' || v_cel, 6, interval '1 hour') then
    return json_build_object('ok', false, 'error', 'muchos', 'detalle', 'Muchos pedidos seguidos: espera unos minutos');
  end if;
  loop
    v_codigo := dr_azar(8, 'ABCDEFGHJKMNPQRSTUVWXYZ23456789');
    exit when not exists (select 1 from pedidos_web where codigo = v_codigo);
  end loop;
  insert into pedidos_web (codigo, nombre, celular, correo, entrega, direccion, referencia, lat, lon, pago, paga_con,
                           nota, items, subtotal, domicilio, total, ip)
  values (v_codigo, v_nombre, v_cel, v_correo, v_entrega, v_dir, v_ref, v_lat, v_lon, v_pago, v_paga,
          v_nota, v_items, v_sub, v_dom, v_total, dr_ip());
  return json_build_object('ok', true, 'codigo', v_codigo, 'total', v_total);
end;
$$;

-- Cómo va el pedido (el cliente solo ve el suyo, con su código)
create or replace function public.ver_pedido_web(p_codigo text)
returns json
language plpgsql security definer set search_path = public as $$
declare
  r pedidos_web%rowtype;
begin
  if p_codigo is null or length(p_codigo) <> 8 then
    return null;
  end if;
  if not dr_limite('verpedido-ip:' || dr_ip(), 120, interval '10 minutes') then
    return null;
  end if;
  select * into r from pedidos_web where codigo = upper(p_codigo) and creado > now() - interval '7 days';
  if not found then
    return null;
  end if;
  return json_build_object('codigo', r.codigo, 'estado', r.estado, 'total', r.total, 'creado', r.creado,
                           'tienda', (select nombre from tienda_info where id = 1), 'motivo', r.motivo,
                           'entrega', r.entrega);
end;
$$;

-- =====================================================================
-- Permisos
-- =====================================================================
revoke all on function public.ver_catalogo() from public;
revoke all on function public.hacer_pedido(json) from public;
revoke all on function public.ver_pedido_web(text) from public;
grant execute on function public.ver_catalogo() to anon, authenticated;
grant execute on function public.hacer_pedido(json) to anon, authenticated;
grant execute on function public.ver_pedido_web(text) to anon, authenticated;
