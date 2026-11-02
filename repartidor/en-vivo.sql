-- DomiRuta: repartidor en vivo y seguimiento del cliente (etapa A del plan 2026-11-02).
-- Se corre DESPUÉS de los SQL que ya están (supabase, mejoras, simplificar, venta, visto, aceptar).
-- Se pega completo en Supabase > SQL Editor > Run. Se puede correr otra vez sin dañar nada.
-- Nada se lee directo: las páginas solo usan las funciones de abajo con la clave pública.

-- =====================================================================
-- Ayudas internas
-- =====================================================================

-- Texto al azar de n letras del alfabeto (32 letras: sin sesgo). Usa gen_random_uuid (azar fuerte).
create or replace function public.dr_azar(n int, alfabeto text)
returns text language plpgsql volatile set search_path = public as $$
declare
  b bytea;
  r text := '';
  i int;
begin
  while length(r) < n loop
    b := uuid_send(gen_random_uuid());
    for i in 0..15 loop
      continue when i in (6, 8); -- bytes con bits fijos de la versión del uuid
      exit when length(r) >= n;
      r := r || substr(alfabeto, (get_byte(b, i) % length(alfabeto)) + 1, 1);
    end loop;
  end loop;
  return r;
end;
$$;

-- Código corto del cliente (sin I, O, 0 ni 1 para que no se confundan) y enlaces largos
create or replace function public.dr_codigo_corto() returns text language sql volatile set search_path = public as
$$ select dr_azar(6, 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789') $$;
create or replace function public.dr_enlace(n int) returns text language sql volatile set search_path = public as
$$ select dr_azar(n, 'abcdefghijkmnpqrstuvwxyz23456789') $$;

-- Distancia en metros entre dos puntos (haversine)
create or replace function public.dr_distancia(lat1 double precision, lon1 double precision,
                                               lat2 double precision, lon2 double precision)
returns double precision language sql immutable as $$
  select 2 * 6371000 * asin(sqrt(
           power(sin(radians(lat2 - lat1) / 2), 2)
           + cos(radians(lat1)) * cos(radians(lat2)) * power(sin(radians(lon2 - lon1) / 2), 2)));
$$;

-- IP de quien llama (la pone Supabase en los encabezados); sirve para limitar intentos
create or replace function public.dr_ip() returns text language plpgsql stable set search_path = public as $$
declare
  h text := current_setting('request.headers', true);
begin
  if h is null or h = '' then
    return 'sin-ip';
  end if;
  return coalesce(nullif(split_part(h::json ->> 'x-forwarded-for', ',', 1), ''), 'sin-ip');
exception when others then
  return 'sin-ip';
end;
$$;

-- =====================================================================
-- Tablas nuevas
-- =====================================================================

-- Repartidores: entran con su enlace personal (sin contraseña), amarrado al primer celular
create table if not exists public.repartidores (
  id uuid primary key default gen_random_uuid(),
  nombre text not null check (length(nombre) between 1 and 80),
  celular text not null default '' check (length(celular) <= 20),
  enlace text not null unique default public.dr_enlace(32) check (length(enlace) >= 32),
  dispositivo text check (dispositivo is null or length(dispositivo) between 16 and 64),
  dispositivo_cambio timestamptz,           -- se abrió en otro celular (la caja recibe el aviso)
  disponible boolean not null default false,
  visto_en timestamptz,
  activo boolean not null default true,
  creado timestamptz not null default now()
);

-- Recorrido del repartidor (para la moto en vivo); se borra a los 30 días
create table if not exists public.ubicaciones (
  id bigint generated always as identity primary key,
  salida uuid not null references public.salidas(id) on delete cascade,
  lat double precision not null check (lat between -90 and 90),
  lon double precision not null check (lon between -180 and 180),
  precision_m real,
  rumbo real,
  velocidad real,
  hora timestamptz not null default now()
);
create index if not exists ubicaciones_salida_idx on public.ubicaciones (salida, hora);

-- Intentos (para limitar consultas con código y abusos)
create table if not exists public.intentos (
  clave text not null,
  hora timestamptz not null default now()
);
create index if not exists intentos_clave_idx on public.intentos (clave, hora);

-- Correcciones de ubicación que proponen los repartidores
create table if not exists public.correcciones (
  id bigint generated always as identity primary key,
  entrega bigint not null references public.entregas_salida(id) on delete cascade,
  repartidor text not null default '',
  lat double precision not null,
  lon double precision not null,
  gps_lat double precision,
  gps_lon double precision,
  distancia_m int not null default 0,          -- del punto que tenía la entrega
  estado text not null default 'pendiente' check (estado in ('pendiente', 'aplicada', 'descartada')),
  procesada boolean not null default false,    -- DomiRuta ya la tomó
  creada timestamptz not null default now()
);
create index if not exists correcciones_estado_idx on public.correcciones (procesada, estado);

-- Celulares a los que se les mandan notificaciones (Web Push)
create table if not exists public.suscripciones_push (
  id bigint generated always as identity primary key,
  repartidor uuid not null references public.repartidores(id) on delete cascade,
  endpoint text not null unique check (endpoint like 'https://%' and length(endpoint) <= 600),
  p256dh text not null check (length(p256dh) <= 200),
  auth text not null check (length(auth) <= 100),
  creada timestamptz not null default now()
);

-- =====================================================================
-- Columnas nuevas
-- =====================================================================

alter table public.salidas add column if not exists repartidor_id uuid references public.repartidores(id) on delete set null;
alter table public.salidas add column if not exists ruta jsonb;          -- [[lat, lon], ...] por las calles
alter table public.salidas add column if not exists calles jsonb;        -- tramos alrededor: [[[lat,lon],...], ...]
alter table public.salidas add column if not exists distancia_m int;
alter table public.salidas add column if not exists tienda_lat double precision;
alter table public.salidas add column if not exists tienda_lon double precision;
-- cuándo vence la oferta (10 min). La pone DomiRuta al despachar; las de antes y vacías no vencen.
alter table public.salidas add column if not exists vence_en timestamptz;
alter table public.salidas add column if not exists ubicacion_pausada boolean not null default false;
alter table public.salidas add column if not exists precision_m real;
alter table public.salidas add column if not exists rumbo real;
create index if not exists salidas_repartidor_idx on public.salidas (repartidor_id, creada);
do $$ begin
  alter table public.salidas add constraint salidas_ruta_tope
    check (ruta is null or (jsonb_typeof(ruta) = 'array' and jsonb_array_length(ruta) <= 5000));
exception when duplicate_object then null; end $$;
do $$ begin
  alter table public.salidas add constraint salidas_calles_tope
    check (calles is null or (jsonb_typeof(calles) = 'array' and jsonb_array_length(calles) <= 2000));
exception when duplicate_object then null; end $$;

alter table public.entregas_salida add column if not exists codigo_cliente text unique;
alter table public.entregas_salida add column if not exists enlace_cliente text unique;
alter table public.entregas_salida add column if not exists ultimos4 text not null default '';
alter table public.entregas_salida add column if not exists entregada_en timestamptz;
alter table public.entregas_salida add column if not exists entrega_lat double precision;
alter table public.entregas_salida add column if not exists entrega_lon double precision;

-- Cada entrega nueva recibe su código corto, su enlace y los últimos 4 dígitos del celular
create or replace function public.dr_codigos_entrega() returns trigger
language plpgsql set search_path = public as $$
begin
  if new.codigo_cliente is null then
    loop
      new.codigo_cliente := dr_codigo_corto();
      exit when not exists (select 1 from entregas_salida where codigo_cliente = new.codigo_cliente);
    end loop;
  end if;
  if new.enlace_cliente is null then
    new.enlace_cliente := dr_enlace(24);
  end if;
  if coalesce(new.ultimos4, '') = '' then
    new.ultimos4 := right(regexp_replace(coalesce(new.telefono, ''), '\D', '', 'g'), 4);
  end if;
  return new;
end;
$$;
drop trigger if exists entregas_codigos on public.entregas_salida;
create trigger entregas_codigos before insert on public.entregas_salida
  for each row execute function public.dr_codigos_entrega();

-- Las entregas de antes también reciben código (para que todo funcione igual)
update public.entregas_salida set codigo_cliente = public.dr_codigo_corto() where codigo_cliente is null;
update public.entregas_salida set enlace_cliente = public.dr_enlace(24) where enlace_cliente is null;
update public.entregas_salida set ultimos4 = right(regexp_replace(telefono, '\D', '', 'g'), 4)
 where ultimos4 = '' and telefono <> '';

-- =====================================================================
-- Seguridad: tablas cerradas (RLS sin permisos públicos); la caja usa la clave secreta
-- =====================================================================

alter table public.repartidores enable row level security;
alter table public.ubicaciones enable row level security;
alter table public.intentos enable row level security;
alter table public.correcciones enable row level security;
alter table public.suscripciones_push enable row level security;
revoke all on public.repartidores, public.ubicaciones, public.intentos, public.correcciones,
              public.suscripciones_push from anon, authenticated;
grant select, insert, update, delete on public.repartidores, public.ubicaciones, public.correcciones,
              public.suscripciones_push to service_role;
grant usage, select on all sequences in schema public to service_role;

-- Limita intentos: true si todavía se puede (y lo cuenta); false si pasó el tope en la ventana
create or replace function public.dr_limite(p_clave text, p_maximo int, p_ventana interval)
returns boolean language plpgsql security definer set search_path = public as $$
begin
  if random() < 0.02 then
    delete from intentos where hora < now() - interval '1 day';
  end if;
  if (select count(*) from intentos where clave = p_clave and hora > now() - p_ventana) >= p_maximo then
    return false;
  end if;
  insert into intentos (clave) values (p_clave);
  return true;
end;
$$;

-- =====================================================================
-- Funciones de la página del repartidor (con el código de la salida)
-- =====================================================================

-- Antes de aceptar: ahora también dice cuándo vence y dónde queda la tienda
drop function if exists public.info_salida(text);
create function public.info_salida(p_codigo text)
returns table (tienda text, repartidor text, creada timestamptz, aceptada boolean, rechazada boolean,
               vence_en timestamptz, vencida boolean, tienda_lat double precision, tienda_lon double precision,
               distancia_m int, ubicacion_pausada boolean)
language sql stable security definer set search_path = public as $$
  select s.tienda, s.repartidor, s.creada, s.aceptada is not null, s.rechazada is not null,
         s.vence_en, (s.aceptada is null and s.rechazada is null and s.vence_en is not null and now() > s.vence_en),
         s.tienda_lat, s.tienda_lon, s.distancia_m, s.ubicacion_pausada
  from salidas s where s.codigo = p_codigo and not s.cerrada;
$$;

-- La ruta por las calles (se puede ver antes de aceptar, para decidir)
create or replace function public.ruta_salida(p_codigo text)
returns table (ruta jsonb, calles jsonb, distancia_m int, tienda_lat double precision, tienda_lon double precision)
language sql stable security definer set search_path = public as $$
  select s.ruta, s.calles, s.distancia_m, s.tienda_lat, s.tienda_lon
  from salidas s where s.codigo = p_codigo and not s.cerrada;
$$;

-- Acepta o no puede: en un solo paso, una sola vez y solo si no ha vencido.
-- Si dos tocan al mismo tiempo, la base deja pasar solo al primero.
create or replace function public.responder_salida(p_codigo text, p_acepta boolean, p_motivo text default '')
returns boolean
language plpgsql security definer set search_path = public as $$
declare
  n int;
begin
  if p_acepta then
    update salidas set aceptada = now(), ubicacion_pausada = false
     where codigo = p_codigo and not cerrada and aceptada is null and rechazada is null
       and (vence_en is null or now() <= vence_en);
  else
    update salidas set rechazada = now(), motivo_rechazo = left(coalesce(p_motivo, ''), 120)
     where codigo = p_codigo and not cerrada and aceptada is null and rechazada is null
       and (vence_en is null or now() <= vence_en);
  end if;
  get diagnostics n = row_count;
  if n = 1 then
    update repartidores r set visto_en = now()
      from salidas s where s.codigo = p_codigo and r.id = s.repartidor_id;
  end if;
  return n = 1;
end;
$$;

-- Ubicación con precisión y rumbo. Máximo una cada 4 s (las demás se ignoran sin error).
-- No se guarda si la salida no está aceptada o la ubicación está pausada.
create or replace function public.enviar_posicion(p_codigo text, p_lat double precision, p_lon double precision,
                                                  p_precision double precision default null,
                                                  p_rumbo double precision default null,
                                                  p_velocidad double precision default null)
returns boolean
language plpgsql security definer set search_path = public as $$
declare
  s salidas%rowtype;
begin
  select * into s from salidas where codigo = p_codigo and not cerrada and aceptada is not null and rechazada is null;
  if not found or s.ubicacion_pausada or p_lat is null or p_lon is null
     or p_lat not between -90 and 90 or p_lon not between -180 and 180
     or (p_precision is not null and (p_precision < 0 or p_precision > 5000)) then
    return false;
  end if;
  if s.ubicacion_en is not null and s.ubicacion_en > now() - interval '4 seconds' then
    return true; -- muy seguido: se ignora
  end if;
  if (select count(*) from ubicaciones where salida = s.id) >= 20000 then
    return false; -- tope para que nadie llene la base
  end if;
  insert into ubicaciones (salida, lat, lon, precision_m, rumbo, velocidad)
  values (s.id, p_lat, p_lon, p_precision,
          case when p_rumbo between 0 and 360 then p_rumbo end,
          case when p_velocidad between 0 and 100 then p_velocidad end);
  -- la moto solo se mueve con lecturas buenas (error de 150 m o menos)
  if p_precision is null or p_precision <= 150 then
    update salidas set lat = p_lat, lon = p_lon, ubicacion_en = now(), precision_m = p_precision,
                       rumbo = case when p_rumbo between 0 and 360 then p_rumbo else rumbo end
     where id = s.id;
  end if;
  update repartidores set visto_en = now() where id = s.repartidor_id;
  return true;
end;
$$;

-- La función de antes sigue sirviendo (la página vieja la usa)
create or replace function public.enviar_ubicacion(p_codigo text, p_lat double precision, p_lon double precision)
returns boolean language sql security definer set search_path = public as $$
  select enviar_posicion(p_codigo, p_lat, p_lon, null, null, null);
$$;

-- Pausar o reanudar la ubicación (la caja y el cliente lo ven)
create or replace function public.pausar_ubicacion(p_codigo text, p_pausar boolean)
returns boolean language plpgsql security definer set search_path = public as $$
declare
  n int;
begin
  update salidas set ubicacion_pausada = coalesce(p_pausar, false)
   where codigo = p_codigo and not cerrada and aceptada is not null and rechazada is null;
  get diagnostics n = row_count;
  return n = 1;
end;
$$;

-- Marcar entrega: igual que antes y además guarda cuándo se entregó
create or replace function public.marcar_entrega(p_codigo text, p_id bigint, p_estado text,
                                                 p_motivo text default '')
returns boolean
language plpgsql security definer set search_path = public as $$
declare
  v_salida uuid := salida_aceptada(p_codigo);
  n int;
begin
  if p_estado not in ('en_camino', 'entregado', 'no_entregado') then
    raise exception 'Estado no válido';
  end if;
  if v_salida is null then
    return false;
  end if;
  update entregas_salida
     set estado = p_estado,
         motivo = case when p_estado = 'no_entregado' then left(coalesce(p_motivo, ''), 120) else '' end,
         entregada_en = case when p_estado = 'entregado' then coalesce(entregada_en, now()) end,
         entrega_lat = case when p_estado = 'entregado' then entrega_lat end,
         entrega_lon = case when p_estado = 'entregado' then entrega_lon end,
         actualizado = now()
   where id = p_id and salida = v_salida;
  get diagnostics n = row_count;
  if n = 1 then
    update salidas
       set regresando = case
             when exists (select 1 from entregas_salida where salida = v_salida and estado = 'en_camino') then null
             else coalesce(regresando, now()) end
     where id = v_salida;
  end if;
  return n = 1;
end;
$$;

-- Marcar entrega con el GPS del momento (prueba de dónde queda la casa)
create or replace function public.marcar_entrega_gps(p_codigo text, p_id bigint, p_estado text,
                                                     p_motivo text default '',
                                                     p_lat double precision default null,
                                                     p_lon double precision default null)
returns boolean
language plpgsql security definer set search_path = public as $$
begin
  if not marcar_entrega(p_codigo, p_id, p_estado, p_motivo) then
    return false;
  end if;
  if p_estado = 'entregado' and p_lat between -90 and 90 and p_lon between -180 and 180 then
    update entregas_salida set entrega_lat = p_lat, entrega_lon = p_lon
     where id = p_id and salida = salida_aceptada(p_codigo);
  end if;
  return true;
end;
$$;

-- "La casa no está aquí": el repartidor propone el punto correcto.
-- Devuelve 'aplicada' (coincide con su GPS o con otra corrección), 'pendiente' (la caja revisa) o null.
create or replace function public.corregir_ubicacion(p_codigo text, p_id bigint,
                                                     p_lat double precision, p_lon double precision,
                                                     p_gps_lat double precision default null,
                                                     p_gps_lon double precision default null)
returns text
language plpgsql security definer set search_path = public as $$
declare
  v_salida uuid := salida_aceptada(p_codigo);
  e entregas_salida%rowtype;
  v_dist double precision := 0;
  v_estado text := 'pendiente';
  v_quien text;
begin
  if v_salida is null or p_lat not between -90 and 90 or p_lon not between -180 and 180 then
    return null;
  end if;
  select * into e from entregas_salida where id = p_id and salida = v_salida;
  if not found then
    return null;
  end if;
  if e.lat is not null and e.lon is not null then
    v_dist := dr_distancia(e.lat, e.lon, p_lat, p_lon);
    if v_dist > 300 then
      return null; -- eso ya no es corregir: es otra dirección
    end if;
  end if;
  if (select count(*) from correcciones where entrega = p_id) >= 3 then
    return null;
  end if;
  if p_gps_lat is not null and p_gps_lon is not null
     and dr_distancia(p_gps_lat, p_gps_lon, p_lat, p_lon) <= 30 then
    v_estado := 'aplicada';
  elsif exists (select 1 from correcciones c join entregas_salida o on o.id = c.entrega
                 where o.salida <> v_salida and lower(o.direccion) = lower(e.direccion)
                   and c.estado <> 'descartada' and dr_distancia(c.lat, c.lon, p_lat, p_lon) <= 30) then
    v_estado := 'aplicada'; -- otra entrega a la misma dirección ya lo confirmó
  end if;
  select repartidor into v_quien from salidas where id = v_salida;
  insert into correcciones (entrega, repartidor, lat, lon, gps_lat, gps_lon, distancia_m, estado)
  values (p_id, coalesce(v_quien, ''), p_lat, p_lon, p_gps_lat, p_gps_lon, round(v_dist), v_estado);
  return v_estado;
end;
$$;

-- =====================================================================
-- Funciones de la app del repartidor (con su enlace personal)
-- =====================================================================

-- El repartidor del enlace, si el celular es el suyo (uso interno)
create or replace function public.dr_repartidor(p_enlace text, p_dispositivo text)
returns uuid language sql stable security definer set search_path = public as $$
  select id from repartidores
   where enlace = p_enlace and activo and dispositivo is not null and dispositivo = p_dispositivo;
$$;

-- "Mis salidas". La primera vez amarra el enlace a ese celular. Si se abre en otro: otro_dispositivo = true.
create or replace function public.mis_salidas(p_enlace text, p_dispositivo text)
returns json
language plpgsql security definer set search_path = public as $$
declare
  r repartidores%rowtype;
begin
  if p_dispositivo is null or length(p_dispositivo) not between 16 and 64 then
    return null;
  end if;
  select * into r from repartidores where enlace = p_enlace and activo;
  if not found then
    return null;
  end if;
  if r.dispositivo is null then
    update repartidores set dispositivo = p_dispositivo, visto_en = now() where id = r.id;
  elsif r.dispositivo <> p_dispositivo then
    return json_build_object('nombre', r.nombre, 'otro_dispositivo', true);
  else
    update repartidores set visto_en = now() where id = r.id;
  end if;
  return json_build_object(
    'nombre', r.nombre,
    'disponible', r.disponible,
    'otro_dispositivo', false,
    'salidas', coalesce((
      select json_agg(json_build_object(
               'codigo', s.codigo, 'tienda', s.tienda, 'creada', s.creada, 'vence_en', s.vence_en,
               'aceptada', s.aceptada is not null, 'rechazada', s.rechazada is not null,
               'vencida', s.aceptada is null and s.rechazada is null and s.vence_en is not null and now() > s.vence_en,
               'cerrada', s.cerrada, 'regresando', s.regresando is not null,
               'entregas', (select count(*) from entregas_salida e where e.salida = s.id),
               'pendientes', (select count(*) from entregas_salida e where e.salida = s.id and e.estado = 'en_camino'))
             order by s.creada desc)
      from salidas s
      where s.repartidor_id = r.id and s.creada > now() - interval '1 day'), '[]'::json));
end;
$$;

-- "Sí, ahora uso este celular": pasa el enlace al celular nuevo y avisa a la caja
create or replace function public.confirmar_dispositivo(p_enlace text, p_dispositivo text)
returns boolean
language plpgsql security definer set search_path = public as $$
declare
  n int;
begin
  if p_dispositivo is null or length(p_dispositivo) not between 16 and 64 then
    return false;
  end if;
  if not dr_limite('dispositivo:' || p_enlace, 5, interval '1 day') then
    return false;
  end if;
  update repartidores set dispositivo = p_dispositivo, dispositivo_cambio = now(), visto_en = now()
   where enlace = p_enlace and activo and dispositivo is distinct from p_dispositivo;
  get diagnostics n = row_count;
  return n = 1;
end;
$$;

-- "Disponible para salidas"
create or replace function public.cambiar_disponible(p_enlace text, p_dispositivo text, p_disponible boolean)
returns boolean
language plpgsql security definer set search_path = public as $$
declare
  v uuid := dr_repartidor(p_enlace, p_dispositivo);
begin
  if v is null then
    return false;
  end if;
  update repartidores set disponible = coalesce(p_disponible, false), visto_en = now() where id = v;
  return true;
end;
$$;

-- Guarda el celular para las notificaciones (máximo 5 por repartidor)
create or replace function public.guardar_suscripcion(p_enlace text, p_dispositivo text, p_endpoint text,
                                                      p_p256dh text, p_auth text)
returns boolean
language plpgsql security definer set search_path = public as $$
declare
  v uuid := dr_repartidor(p_enlace, p_dispositivo);
begin
  if v is null or p_endpoint is null or p_endpoint not like 'https://%' or length(p_endpoint) > 600
     or coalesce(p_p256dh, '') = '' or length(p_p256dh) > 200 or coalesce(p_auth, '') = '' or length(p_auth) > 100 then
    return false;
  end if;
  insert into suscripciones_push (repartidor, endpoint, p256dh, auth) values (v, p_endpoint, p_p256dh, p_auth)
  on conflict (endpoint) do update set repartidor = excluded.repartidor, p256dh = excluded.p256dh,
                                       auth = excluded.auth, creada = now();
  delete from suscripciones_push where repartidor = v and id not in (
    select id from suscripciones_push where repartidor = v order by creada desc limit 5);
  return true;
end;
$$;

-- =====================================================================
-- Funciones de la página del cliente (solo ve SU entrega)
-- =====================================================================

-- Lo que ve el cliente de una entrega (uso interno)
create or replace function public.dr_pedido(p_id bigint)
returns json
language plpgsql stable security definer set search_path = public as $$
declare
  e entregas_salida%rowtype;
  s salidas%rowtype;
  v_estado text;
  v_moto json;
begin
  select * into e from entregas_salida where id = p_id;
  if not found then
    return null;
  end if;
  select * into s from salidas where id = e.salida;
  if s.creada < now() - interval '7 days' then
    return null; -- enlaces muy viejos ya no muestran nada
  end if;
  v_estado := case
    when e.estado = 'entregado' then 'entregado'
    when e.estado = 'no_entregado' then 'no_entregado'
    when s.aceptada is null or s.rechazada is not null then 'preparando'
    else 'en_camino' end;
  -- la moto solo se ve mientras va hacia este cliente, con ubicación reciente y sin pausar
  if v_estado = 'en_camino' and not s.cerrada and not s.ubicacion_pausada
     and s.ubicacion_en > now() - interval '5 minutes' and s.lat is not null then
    v_moto := json_build_object('lat', s.lat, 'lon', s.lon, 'rumbo', s.rumbo, 'hora', s.ubicacion_en);
  end if;
  return json_build_object(
    'tienda', s.tienda,
    'nombre', split_part(trim(e.nombre), ' ', 1),
    'estado', v_estado,
    'faltan_antes', case when v_estado = 'en_camino' then
        (select count(*) from entregas_salida o where o.salida = s.id and o.estado = 'en_camino' and o.orden < e.orden)
      else 0 end,
    'repartidor', split_part(trim(coalesce(s.repartidor, '')), ' ', 1),
    'destino', case when e.lat is not null then json_build_object('lat', e.lat, 'lon', e.lon) end,
    'moto', v_moto,
    'ubicacion_pausada', v_estado = 'en_camino' and s.ubicacion_pausada,
    'entregada_en', e.entregada_en);
end;
$$;

-- Con el enlace largo del WhatsApp (abre directo)
create or replace function public.ver_pedido(p_enlace text)
returns json
language plpgsql security definer set search_path = public as $$
declare
  v bigint;
begin
  if p_enlace is null or length(p_enlace) < 20 then
    return null;
  end if;
  select id into v from entregas_salida where enlace_cliente = p_enlace;
  if v is null then
    return null; -- el enlace largo no se puede adivinar: no hace falta contar intentos
  end if;
  return dr_pedido(v);
end;
$$;

-- Con el código corto + los últimos 4 dígitos del celular. Máximo 10 intentos fallidos cada 10 min.
create or replace function public.ver_pedido_codigo(p_codigo text, p_ultimos4 text)
returns json
language plpgsql security definer set search_path = public as $$
declare
  v bigint;
  v_ip text := dr_ip();
  v_codigo text := upper(regexp_replace(coalesce(p_codigo, ''), '[\s-]', '', 'g'));
  v_ult text := regexp_replace(coalesce(p_ultimos4, ''), '\D', '', 'g');
begin
  if (select count(*) from intentos where clave = 'codigo:' || v_ip and hora > now() - interval '10 minutes') >= 10 then
    return json_build_object('error', 'espera'); -- demasiados intentos: espere unos minutos
  end if;
  if length(v_codigo) = 6 and length(v_ult) = 4 then
    select id into v from entregas_salida where codigo_cliente = v_codigo and ultimos4 = v_ult;
  end if;
  if v is null then
    insert into intentos (clave) values ('codigo:' || v_ip);
    return null;
  end if;
  return dr_pedido(v);
end;
$$;

-- =====================================================================
-- Mantenimiento (solo la caja, con la clave secreta)
-- =====================================================================

create or replace function public.limpiar_viejo()
returns void language sql security definer set search_path = public as $$
  delete from ubicaciones where hora < now() - interval '30 days';
  delete from intentos where hora < now() - interval '1 day';
$$;

-- =====================================================================
-- Permisos: la clave pública solo puede usar las funciones de las páginas
-- =====================================================================

revoke all on function public.dr_azar(int, text) from public, anon, authenticated;
revoke all on function public.dr_codigo_corto() from public, anon, authenticated;
revoke all on function public.dr_enlace(int) from public, anon, authenticated;
revoke all on function public.dr_ip() from public, anon, authenticated;
revoke all on function public.dr_limite(text, int, interval) from public, anon, authenticated;
revoke all on function public.dr_codigos_entrega() from public, anon, authenticated;
revoke all on function public.dr_repartidor(text, text) from public, anon, authenticated;
revoke all on function public.dr_pedido(bigint) from public, anon, authenticated;
revoke all on function public.limpiar_viejo() from public, anon, authenticated;
grant execute on function public.dr_codigo_corto(), public.dr_enlace(int), public.dr_azar(int, text),
                          public.limpiar_viejo() to service_role;

do $$
declare
  f text;
begin
  foreach f in array array[
    'public.info_salida(text)',
    'public.ruta_salida(text)',
    'public.responder_salida(text, boolean, text)',
    'public.enviar_posicion(text, double precision, double precision, double precision, double precision, double precision)',
    'public.enviar_ubicacion(text, double precision, double precision)',
    'public.pausar_ubicacion(text, boolean)',
    'public.marcar_entrega(text, bigint, text, text)',
    'public.marcar_entrega_gps(text, bigint, text, text, double precision, double precision)',
    'public.corregir_ubicacion(text, bigint, double precision, double precision, double precision, double precision)',
    'public.mis_salidas(text, text)',
    'public.confirmar_dispositivo(text, text)',
    'public.cambiar_disponible(text, text, boolean)',
    'public.guardar_suscripcion(text, text, text, text, text)',
    'public.ver_pedido(text)',
    'public.ver_pedido_codigo(text, text)']
  loop
    execute 'revoke all on function ' || f || ' from public';
    execute 'grant execute on function ' || f || ' to anon, authenticated';
  end loop;
end $$;
