-- DomiRuta: el encargado de pedidos en línea (página pedidos.html).
-- Entra con su celular y una clave de 6 números (se la crea la tienda en DomiRuta). Atiende los pedidos
-- de la página, empaca, revisa el comprobante, habla con el cliente por WhatsApp y asigna el domiciliario.
-- Lo pesado (armar la ruta, registrar la venta, imprimir) lo hace DomiRuta en la caja: la página deja el
-- trabajo en «trabajos_caja» y DomiRuta lo recoge cada 15 s.
-- Se corre DESPUÉS de tienda.sql y whatsapp.sql. Se puede correr otra vez sin dañar nada.

create table if not exists public.encargados (
  id uuid primary key default gen_random_uuid(),
  nombre text not null check (length(nombre) between 1 and 80),
  celular text not null unique check (celular ~ '^3[0-9]{9}$'),
  clave_hash text,
  con_clave boolean not null default false,
  activo boolean not null default true,
  creado timestamptz not null default now()
);

create table if not exists public.sesiones_encargado (
  encargado uuid primary key references public.encargados(id) on delete cascade,
  token_hash text not null unique,
  creada timestamptz not null default now(),
  visto_en timestamptz not null default now()
);

-- Lo que la página le pide a la caja (DomiRuta lo hace y anota cómo le fue)
create table if not exists public.trabajos_caja (
  id bigint generated always as identity primary key,
  creado timestamptz not null default now(),
  tipo text not null check (tipo in ('asignar', 'imprimir', 'vender')),
  datos jsonb not null default '{}',
  encargado uuid references public.encargados(id) on delete set null,
  estado text not null default 'pendiente' check (estado in ('pendiente', 'hecho', 'error')),
  resultado text not null default '',
  hecho_en timestamptz
);
create index if not exists trabajos_caja_pendientes_idx on public.trabajos_caja (id) where estado = 'pendiente';

-- La caja avisa que está prendida (DomiRuta lo actualiza cada 15 s)
create table if not exists public.caja_estado (
  id int primary key default 1 check (id = 1),
  visto_en timestamptz
);
insert into public.caja_estado (id) values (1) on conflict do nothing;

-- Cuántos quedan de cada producto en línea (solo lo ve el encargado; el cliente solo ve «Agotado»)
create table if not exists public.existencias_web (
  id bigint primary key,
  stock numeric not null default 0,
  maneja boolean not null default true
);

alter table public.pedidos_web add column if not exists empacado boolean not null default false;
alter table public.pedidos_web add column if not exists faltantes jsonb not null default '[]';  -- ids de productos que no hay
alter table public.pedidos_web add column if not exists atendido_por text not null default '';
alter table public.pedidos_web add column if not exists venta bigint;                          -- número del recibo en DomiRuta

alter table public.encargados enable row level security;
alter table public.sesiones_encargado enable row level security;
alter table public.trabajos_caja enable row level security;
alter table public.caja_estado enable row level security;
alter table public.existencias_web enable row level security;
revoke all on public.encargados, public.sesiones_encargado, public.trabajos_caja, public.caja_estado,
  public.existencias_web from anon, authenticated;
grant select, insert, update, delete on public.encargados, public.sesiones_encargado, public.trabajos_caja,
  public.caja_estado, public.existencias_web to service_role;

-- =====================================================================
-- Sesión
-- =====================================================================

create or replace function public.enc_hash(t text)
returns text language sql immutable set search_path = public, extensions as $$
  select encode(extensions.digest(coalesce(t, ''), 'sha256'), 'hex');
$$;

-- El encargado dueño de la sesión (o error «sesion_cerrada»)
create or replace function public.enc_dueno(p_sesion text)
returns public.encargados language plpgsql security definer set search_path = public as $$
declare
  e encargados%rowtype;
begin
  if p_sesion is null or p_sesion !~ '^[0-9a-f]{64}$' then
    raise exception 'sesion_cerrada';
  end if;
  select x.* into e from sesiones_encargado s join encargados x on x.id = s.encargado
   where s.token_hash = enc_hash(p_sesion) and x.activo;
  if not found then
    raise exception 'sesion_cerrada';
  end if;
  update sesiones_encargado set visto_en = now() where encargado = e.id and visto_en < now() - interval '1 minute';
  return e;
end;
$$;

create or replace function public.enc_entrar(p_celular text, p_clave text)
returns json language plpgsql security definer set search_path = public, extensions as $$
declare
  cel text := regexp_replace(coalesce(p_celular, ''), '[^0-9]', '', 'g');
  e encargados%rowtype;
  token text;
begin
  if cel ~ '^573[0-9]{9}$' then
    cel := substr(cel, 3);
  end if;
  if not dr_limite('enc-entrar-ip:' || dr_ip(), 20, interval '10 minutes')
     or not dr_limite('enc-entrar-cel:' || cel, 8, interval '10 minutes') then
    return json_build_object('ok', false, 'error', 'Muchos intentos seguidos. Espera unos minutos.');
  end if;
  select * into e from encargados where celular = cel and activo and con_clave;
  if not found or e.clave_hash is null or extensions.crypt(coalesce(p_clave, ''), e.clave_hash) <> e.clave_hash then
    return json_build_object('ok', false, 'error', 'El celular o la clave no coinciden.');
  end if;
  token := encode(extensions.gen_random_bytes(32), 'hex');
  delete from sesiones_encargado where encargado = e.id;   -- una sola sesión: la de antes se cierra
  insert into sesiones_encargado (encargado, token_hash) values (e.id, enc_hash(token));
  return json_build_object('ok', true, 'sesion', token, 'nombre', e.nombre);
end;
$$;

create or replace function public.enc_salir(p_sesion text)
returns void language sql security definer set search_path = public as $$
  delete from sesiones_encargado where token_hash = enc_hash(p_sesion);
$$;

-- DomiRuta le pone la clave (con la clave secreta); la sesión que tuviera se cierra
create or replace function public.dr_clave_encargado(p_id uuid, p_clave text)
returns text language plpgsql security definer set search_path = public, extensions as $$
begin
  if p_clave !~ '^[0-9]{6}$' then
    return 'La clave son 6 números';
  end if;
  update encargados set clave_hash = extensions.crypt(p_clave, extensions.gen_salt('bf', 10)), con_clave = true
   where id = p_id;
  if not found then
    return 'No existe ese encargado';
  end if;
  delete from sesiones_encargado where encargado = p_id;
  return 'ok';
end;
$$;

-- Si se desactiva o le cambian el celular, se le cierra la sesión
create or replace function public.enc_tr_cambio()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if not new.activo or new.celular is distinct from old.celular then
    delete from sesiones_encargado where encargado = new.id;
  end if;
  return new;
end;
$$;
drop trigger if exists enc_cambio on public.encargados;
create trigger enc_cambio after update on public.encargados for each row execute function public.enc_tr_cambio();

-- =====================================================================
-- El tablero
-- =====================================================================

create or replace function public.enc_pedido_json(r public.pedidos_web)
returns json language sql stable security definer set search_path = public as $$
  select json_build_object(
    'id', r.id, 'codigo', r.codigo, 'creado', r.creado, 'nombre', r.nombre, 'celular', r.celular, 'correo', r.correo,
    'entrega', r.entrega, 'direccion', r.direccion, 'referencia', r.referencia, 'lat', r.lat, 'lon', r.lon,
    'pago', r.pago, 'pagaCon', r.paga_con, 'nota', r.nota, 'subtotal', r.subtotal, 'domicilio', r.domicilio,
    'total', r.total, 'estado', r.estado, 'motivo', r.motivo, 'pagado', r.pagado,
    'comprobante', r.comprobante is not null, 'empacado', r.empacado, 'faltantes', r.faltantes,
    'atendidoPor', r.atendido_por, 'venta', r.venta,
    'items', coalesce((select json_agg(json_build_object('id', (i->>'id')::bigint, 'nombre', i->>'nombre',
                         'precio', (i->>'precio')::int, 'cantidad', (i->>'cantidad')::int,
                         'foto', c.foto, 'stock', x.stock, 'maneja', coalesce(x.maneja, true)))
                       from jsonb_array_elements(r.items) i
                       left join catalogo c on c.id = (i->>'id')::bigint
                       left join existencias_web x on x.id = (i->>'id')::bigint), '[]'::json),
    'anteriores', (select count(*) from pedidos_web o where o.celular = r.celular and o.id < r.id and o.estado = 'entregado'));
$$;

create or replace function public.enc_tablero(p_sesion text)
returns json language plpgsql security definer set search_path = public as $$
declare
  e encargados%rowtype := enc_dueno(p_sesion);
  visto timestamptz := (select visto_en from caja_estado where id = 1);
begin
  return json_build_object(
    'nombre', e.nombre,
    'tienda', (select nombre from tienda_info where id = 1),
    'caja', visto is not null and visto > now() - interval '1 minute',
    'cajaVista', visto,
    'pedidos', coalesce((select json_agg(enc_pedido_json(p) order by p.id)
                         from pedidos_web p
                        where (p.estado not in ('entregado', 'cancelado') and p.creado > now() - interval '3 days')
                           or (p.estado_en > now() - interval '3 hours')), '[]'::json),
    'repartidores', coalesce((select json_agg(json_build_object('id', r.id, 'nombre', r.nombre,
                         'disponible', r.disponible, 'visto', r.visto_en,
                         'enCamino', exists (select 1 from salidas s where s.repartidor_id = r.id and not s.cerrada
                                             and s.aceptada is not null and s.regresando is null))
                         order by r.nombre)
                         from repartidores r where r.activo), '[]'::json),
    'trabajos', coalesce((select json_agg(json_build_object('id', t.id, 'tipo', t.tipo, 'estado', t.estado,
                         'resultado', t.resultado, 'datos', t.datos) order by t.id desc)
                         from (select * from trabajos_caja where creado > now() - interval '2 hours'
                               order by id desc limit 20) t), '[]'::json));
end;
$$;

-- Lo que el encargado le hace a un pedido
create or replace function public.enc_accion(p_sesion text, p_pedido bigint, p_accion text, p_dato text default null)
returns json language plpgsql security definer set search_path = public as $$
declare
  e encargados%rowtype := enc_dueno(p_sesion);
  r pedidos_web%rowtype;
  f jsonb;
begin
  select * into r from pedidos_web where id = p_pedido for update;
  if not found then
    return json_build_object('ok', false, 'error', 'No existe ese pedido');
  end if;
  if r.estado in ('entregado', 'cancelado') and p_accion <> 'imprimir' then
    return json_build_object('ok', false, 'error', 'Ese pedido ya terminó');
  end if;
  case p_accion
    when 'aceptar' then
      if r.estado <> 'nuevo' then
        return json_build_object('ok', false, 'error', 'Ya estaba aceptado');
      end if;
      update pedidos_web set estado = 'aceptado', estado_en = now(), atendido_por = e.nombre where id = r.id;
    when 'pagado' then
      update pedidos_web set pagado = true where id = r.id;
    when 'faltante' then   -- marca o desmarca un producto que no hay
      f := case when r.faltantes @> to_jsonb(p_dato::bigint) then
                  (select coalesce(jsonb_agg(x), '[]'::jsonb) from jsonb_array_elements(r.faltantes) x where x <> to_jsonb(p_dato::bigint))
                else r.faltantes || to_jsonb(p_dato::bigint) end;
      update pedidos_web set faltantes = f where id = r.id;
    when 'empacado' then
      if r.estado = 'nuevo' then
        return json_build_object('ok', false, 'error', 'Primero acepta el pedido');
      end if;
      if r.pago not in ('efectivo', 'tarjeta_debito', 'tarjeta_credito') and not r.pagado then
        return json_build_object('ok', false, 'error', 'Falta confirmar el pago');
      end if;
      update pedidos_web set empacado = true, estado = 'preparando', estado_en = now(),
                             atendido_por = coalesce(nullif(atendido_por, ''), e.nombre) where id = r.id;
      -- la venta se registra en la caja (baja el inventario y queda en el cuadre)
      if r.venta is null then
        insert into trabajos_caja (tipo, datos, encargado) values ('vender', json_build_object('pedido', r.id)::jsonb, e.id);
      end if;
    when 'listo_retiro' then
      if r.entrega <> 'retiro' then
        return json_build_object('ok', false, 'error', 'Ese pedido es a domicilio');
      end if;
      update pedidos_web set estado = 'en_camino', estado_en = now() where id = r.id;
    when 'entregado_retiro' then
      if r.entrega <> 'retiro' then
        return json_build_object('ok', false, 'error', 'Ese pedido es a domicilio');
      end if;
      update pedidos_web set estado = 'entregado', estado_en = now() where id = r.id;
    when 'cancelar' then
      update pedidos_web set estado = 'cancelado', estado_en = now(),
                             motivo = left(coalesce(nullif(trim(p_dato), ''), 'La tienda no pudo atenderlo'), 200)
       where id = r.id;
    when 'imprimir' then
      insert into trabajos_caja (tipo, datos, encargado) values ('imprimir', json_build_object('pedido', r.id)::jsonb, e.id);
    else
      return json_build_object('ok', false, 'error', 'Acción no válida');
  end case;
  return json_build_object('ok', true);
end;
$$;

-- Asignar uno o varios pedidos a un domiciliario: DomiRuta arma la salida con la ruta
create or replace function public.enc_asignar(p_sesion text, p_pedidos bigint[], p_repartidor uuid)
returns json language plpgsql security definer set search_path = public as $$
declare
  e encargados%rowtype := enc_dueno(p_sesion);
  n int;
begin
  if p_pedidos is null or array_length(p_pedidos, 1) is null or array_length(p_pedidos, 1) > 15 then
    return json_build_object('ok', false, 'error', 'Escoge de 1 a 15 pedidos');
  end if;
  if not exists (select 1 from repartidores where id = p_repartidor and activo) then
    return json_build_object('ok', false, 'error', 'Ese domiciliario no está activo');
  end if;
  select count(*) into n from pedidos_web
   where id = any(p_pedidos) and entrega = 'domicilio' and empacado and estado = 'preparando';
  if n <> array_length(p_pedidos, 1) then
    return json_build_object('ok', false, 'error', 'Solo se asignan pedidos a domicilio ya empacados');
  end if;
  insert into trabajos_caja (tipo, datos, encargado)
  values ('asignar', json_build_object('pedidos', p_pedidos, 'repartidor', p_repartidor)::jsonb, e.id);
  return json_build_object('ok', true);
end;
$$;

-- El chat de WhatsApp con el cliente del pedido (últimos 7 días)
create or replace function public.enc_chat(p_sesion text, p_pedido bigint)
returns json language plpgsql security definer set search_path = public as $$
declare
  e encargados%rowtype := enc_dueno(p_sesion);
  num text;
  ultimo timestamptz;
begin
  select wa_numero(celular) into num from pedidos_web where id = p_pedido;
  if num is null then
    return json_build_object('mensajes', '[]'::json, 'abierta', false);
  end if;
  select max(creado) into ultimo from wa_entrada where de = num;
  return json_build_object(
    'abierta', ultimo is not null and ultimo > now() - interval '23 hours 50 minutes',
    'mensajes', coalesce((select json_agg(m order by m.hora) from (
        select 'cliente' as de, coalesce(nullif(texto, ''), case when tipo = 'image' then '[foto]' else '[' || tipo || ']' end) as texto,
               creado as hora, '' as estado
          from wa_entrada where de = num and creado > now() - interval '7 days'
        union all
        select 'tienda', coalesce(texto, 'Mensaje automático: ' || replace(plantilla, '_', ' ')), creado, estado
          from wa_salida where para = num and creado > now() - interval '7 days'
      ) m), '[]'::json));
end;
$$;

create or replace function public.enc_escribir(p_sesion text, p_pedido bigint, p_texto text)
returns json language plpgsql security definer set search_path = public as $$
declare
  e encargados%rowtype := enc_dueno(p_sesion);
  num text;
  t text := trim(coalesce(p_texto, ''));
begin
  if length(t) not between 1 and 1000 then
    return json_build_object('ok', false, 'error', 'Escribe el mensaje (máximo 1000 letras)');
  end if;
  select wa_numero(celular) into num from pedidos_web where id = p_pedido;
  if num is null then
    return json_build_object('ok', false, 'error', 'El cliente no tiene un celular válido');
  end if;
  if not exists (select 1 from wa_entrada where de = num and creado > now() - interval '23 hours 50 minutes') then
    return json_build_object('ok', false, 'error',
      'El cliente no ha escrito en las últimas 24 horas: WhatsApp no deja escribirle libre. Llámalo al ' || num);
  end if;
  if not dr_limite('enc-escribir:' || e.id, 60, interval '10 minutes') then
    return json_build_object('ok', false, 'error', 'Muchos mensajes seguidos');
  end if;
  insert into wa_salida (para, texto, llave) values (num, t, 'chat:' || e.id || ':' || extract(epoch from clock_timestamp()));
  perform wa_despertar();
  return json_build_object('ok', true);
end;
$$;

-- La ruta del comprobante (solo la usa la función «whatsapp», que da el enlace de 10 minutos)
create or replace function public.enc_ruta_comprobante(p_sesion text, p_pedido bigint)
returns text language plpgsql security definer set search_path = public as $$
declare
  e encargados%rowtype := enc_dueno(p_sesion);
begin
  return (select comprobante from pedidos_web where id = p_pedido);
end;
$$;

-- =====================================================================
-- Permisos
-- =====================================================================
revoke all on function public.enc_dueno(text) from public, anon, authenticated;
revoke all on function public.enc_pedido_json(public.pedidos_web) from public, anon, authenticated;
revoke all on function public.dr_clave_encargado(uuid, text) from public, anon, authenticated;
revoke all on function public.enc_ruta_comprobante(text, bigint) from public, anon, authenticated;
grant execute on function public.dr_clave_encargado(uuid, text), public.enc_ruta_comprobante(text, bigint) to service_role;
revoke all on function public.enc_entrar(text, text), public.enc_salir(text), public.enc_tablero(text),
  public.enc_accion(text, bigint, text, text), public.enc_asignar(text, bigint[], uuid), public.enc_chat(text, bigint),
  public.enc_escribir(text, bigint, text) from public;
grant execute on function public.enc_entrar(text, text), public.enc_salir(text), public.enc_tablero(text),
  public.enc_accion(text, bigint, text, text), public.enc_asignar(text, bigint[], uuid), public.enc_chat(text, bigint),
  public.enc_escribir(text, bigint, text) to anon, authenticated;
