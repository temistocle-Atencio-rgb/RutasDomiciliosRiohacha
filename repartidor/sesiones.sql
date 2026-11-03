-- DomiRuta: el repartidor entra con su celular y una clave de 6 números (una sola sesión a la vez).
-- Se corre DESPUÉS de en-vivo.sql y camino-cliente.sql. Se puede correr otra vez sin dañar nada.
--
-- Paso 1 (este archivo): crea las funciones nuevas «rep_…» que piden la sesión. Las de antes siguen
-- funcionando para que la página vieja no se caiga mientras se sube la nueva.
-- Paso 2 (cerrar-enlaces-viejos.sql): cuando la página y DomiRuta nuevas estén subidas, se apagan las de antes.

create extension if not exists pgcrypto with schema extensions;

-- =====================================================================
-- Claves y sesiones
-- =====================================================================

alter table public.repartidores add column if not exists clave_hash text;           -- cifrada (bcrypt): nadie la puede leer
alter table public.repartidores add column if not exists con_clave boolean not null default false;
alter table public.repartidores add column if not exists sesion_en timestamptz;     -- desde cuándo tiene la sesión (null = ninguna)

create table if not exists public.sesiones_repartidor (
  token_hash text primary key,                          -- el celular guarda la llave; aquí solo su huella (sha256)
  repartidor uuid not null unique references public.repartidores(id) on delete cascade,  -- unique: una sola sesión
  dispositivo text check (dispositivo is null or length(dispositivo) <= 64),
  creada timestamptz not null default now(),
  vista timestamptz not null default now(),
  vence timestamptz not null default now() + interval '30 days'
);
alter table public.sesiones_repartidor enable row level security;
revoke all on public.sesiones_repartidor from anon, authenticated;
grant select, insert, update, delete on public.sesiones_repartidor to service_role;

-- Los 10 números del celular (sin +57, espacios ni guiones)
create or replace function public.dr_celular(p text)
returns text language sql immutable set search_path = public as $$
  select right(regexp_replace(coalesce(p, ''), '\D', '', 'g'), 10);
$$;

create or replace function public.dr_huella(p text)
returns text language sql immutable set search_path = public as $$
  select encode(extensions.digest(coalesce(p, ''), 'sha256'), 'hex');
$$;

-- El repartidor de la sesión (null si venció, la cerraron o lo desactivaron). Uso interno.
create or replace function public.dr_sesion(p_sesion text)
returns uuid
language plpgsql security definer set search_path = public as $$
declare
  v uuid;
begin
  if p_sesion is null or length(p_sesion) <> 64 then
    return null;
  end if;
  select s.repartidor into v from sesiones_repartidor s join repartidores r on r.id = s.repartidor
   where s.token_hash = dr_huella(p_sesion) and s.vence > now() and r.activo;
  if v is not null then
    update sesiones_repartidor set vista = now() where repartidor = v and vista < now() - interval '1 minute';
  end if;
  return v;
end;
$$;

-- La sesión, o error «sesion_cerrada» (la página vuelve a pedir la clave)
create or replace function public.dr_sesion_o_error(p_sesion text)
returns uuid
language plpgsql security definer set search_path = public as $$
declare
  v uuid := dr_sesion(p_sesion);
begin
  if v is null then
    raise exception 'sesion_cerrada';
  end if;
  return v;
end;
$$;

-- Que la salida sea de quien tiene la sesión; si no: «no_es_tuya». Si no existe, sigue (las funciones de antes dicen que no hay nada).
create or replace function public.dr_dueno(p_sesion text, p_codigo text)
returns void
language plpgsql security definer set search_path = public as $$
declare
  v uuid := dr_sesion_o_error(p_sesion);
  d uuid;
begin
  select repartidor_id into d from salidas where codigo = p_codigo;
  if found and d is distinct from v then
    raise exception 'no_es_tuya';
  end if;
end;
$$;

-- =====================================================================
-- Entrar, salir, cambiar la clave (los usa la página)
-- =====================================================================

-- Entrar: celular + clave. 5 claves malas seguidas = 15 minutos bloqueado. Si ya tenía sesión en otro
-- celular, esa se cierra (y ese celular deja de recibir avisos); la tienda ve «entró desde otro celular».
create or replace function public.entrar(p_celular text, p_clave text, p_dispositivo text)
returns json
language plpgsql security definer set search_path = public as $$
declare
  v_cel text := dr_celular(p_celular);
  r repartidores%rowtype;
  fallos int;
  v_token text;
  habia boolean;
begin
  if length(v_cel) <> 10 or coalesce(p_clave, '') !~ '^[0-9]{6}$' then
    return json_build_object('ok', false, 'error', 'datos');
  end if;
  if not dr_limite('entrar-ip:' || dr_ip(), 30, interval '15 minutes') then
    return json_build_object('ok', false, 'error', 'bloqueado', 'minutos', 15);
  end if;
  select count(*) into fallos from intentos where clave = 'entrar:' || v_cel and hora > now() - interval '15 minutes';
  if fallos >= 5 then
    return json_build_object('ok', false, 'error', 'bloqueado', 'minutos', 15);
  end if;
  select * into r from repartidores
   where activo and con_clave and clave_hash is not null and dr_celular(celular) = v_cel
   order by creado limit 1;
  if not found or r.clave_hash <> extensions.crypt(p_clave, r.clave_hash) then
    insert into intentos (clave) values ('entrar:' || v_cel);
    return json_build_object('ok', false, 'error', 'clave', 'quedan', greatest(0, 4 - fallos));
  end if;
  delete from intentos where clave = 'entrar:' || v_cel;
  habia := exists (select 1 from sesiones_repartidor where repartidor = r.id);
  delete from sesiones_repartidor where repartidor = r.id;           -- una sola sesión
  if habia then
    delete from suscripciones_push where repartidor = r.id;         -- el celular viejo deja de recibir avisos
  end if;
  v_token := encode(extensions.gen_random_bytes(32), 'hex');         -- 64 caracteres al azar
  insert into sesiones_repartidor (token_hash, repartidor, dispositivo)
  values (dr_huella(v_token), r.id, left(p_dispositivo, 64));
  update repartidores set sesion_en = now(), visto_en = now(), dispositivo = left(p_dispositivo, 64),
                          dispositivo_cambio = case when habia then now() else dispositivo_cambio end
   where id = r.id;
  return json_build_object('ok', true, 'sesion', v_token, 'nombre', r.nombre);
end;
$$;

create or replace function public.salir(p_sesion text)
returns boolean
language plpgsql security definer set search_path = public as $$
declare
  v uuid := dr_sesion(p_sesion);
begin
  if v is null then
    return false;
  end if;
  delete from sesiones_repartidor where repartidor = v;
  delete from suscripciones_push where repartidor = v;
  update repartidores set sesion_en = null where id = v;
  return true;
end;
$$;

-- Claves que no sirven: todos iguales (111111) o seguidos (123456, 654321)
create or replace function public.dr_clave_debil(p text)
returns boolean language sql immutable set search_path = public as $$
  select p ~ '^(\d)\1{5}$' or position(p in '01234567890') > 0 or position(p in '09876543210') > 0;
$$;

create or replace function public.cambiar_clave(p_sesion text, p_actual text, p_nueva text)
returns text
language plpgsql security definer set search_path = public as $$
declare
  v uuid := dr_sesion_o_error(p_sesion);
  h text;
begin
  if coalesce(p_nueva, '') !~ '^[0-9]{6}$' then
    return 'La clave nueva son 6 números';
  end if;
  if dr_clave_debil(p_nueva) then
    return 'Esa clave es muy fácil de adivinar';
  end if;
  if not dr_limite('clave:' || v, 5, interval '15 minutes') then
    return 'Muchos intentos: espera 15 minutos';
  end if;
  select clave_hash into h from repartidores where id = v;
  if h is null or h <> extensions.crypt(coalesce(p_actual, ''), h) then
    return 'La clave actual no es esa';
  end if;
  update repartidores set clave_hash = extensions.crypt(p_nueva, extensions.gen_salt('bf', 10)) where id = v;
  return 'ok';
end;
$$;

-- =====================================================================
-- Lo de siempre, pero con la sesión (la página nueva usa estas)
-- =====================================================================

create or replace function public.rep_info_salida(p_sesion text, p_codigo text)
returns json language plpgsql security definer set search_path = public as $$
begin
  perform dr_dueno(p_sesion, p_codigo);
  return (select coalesce(json_agg(t), '[]'::json) from info_salida(p_codigo) t);
end;
$$;

create or replace function public.rep_ruta_salida(p_sesion text, p_codigo text)
returns json language plpgsql security definer set search_path = public as $$
begin
  perform dr_dueno(p_sesion, p_codigo);
  return (select coalesce(json_agg(t), '[]'::json) from ruta_salida(p_codigo) t);
end;
$$;

create or replace function public.rep_ver_salida(p_sesion text, p_codigo text)
returns json language plpgsql security definer set search_path = public as $$
begin
  perform dr_dueno(p_sesion, p_codigo);
  return (select coalesce(json_agg(t), '[]'::json) from ver_salida(p_codigo) t);
end;
$$;

create or replace function public.rep_responder_salida(p_sesion text, p_codigo text, p_acepta boolean, p_motivo text)
returns boolean language plpgsql security definer set search_path = public as $$
begin
  perform dr_dueno(p_sesion, p_codigo);
  return responder_salida(p_codigo, p_acepta, p_motivo);
end;
$$;

create or replace function public.rep_enviar_posicion(p_sesion text, p_codigo text, p_lat double precision,
                                                      p_lon double precision, p_precision double precision default null,
                                                      p_rumbo double precision default null,
                                                      p_velocidad double precision default null)
returns boolean language plpgsql security definer set search_path = public as $$
begin
  perform dr_dueno(p_sesion, p_codigo);
  return enviar_posicion(p_codigo, p_lat, p_lon, p_precision, p_rumbo, p_velocidad);
end;
$$;

create or replace function public.rep_pausar_ubicacion(p_sesion text, p_codigo text, p_pausar boolean)
returns boolean language plpgsql security definer set search_path = public as $$
begin
  perform dr_dueno(p_sesion, p_codigo);
  return pausar_ubicacion(p_codigo, p_pausar);
end;
$$;

create or replace function public.rep_marcar_entrega_gps(p_sesion text, p_codigo text, p_id bigint, p_estado text,
                                                         p_motivo text, p_lat double precision, p_lon double precision)
returns boolean language plpgsql security definer set search_path = public as $$
begin
  perform dr_dueno(p_sesion, p_codigo);
  return marcar_entrega_gps(p_codigo, p_id, p_estado, p_motivo, p_lat, p_lon);
end;
$$;

create or replace function public.rep_ver_mensajes(p_sesion text, p_codigo text, p_desde bigint)
returns json language plpgsql security definer set search_path = public as $$
begin
  perform dr_dueno(p_sesion, p_codigo);
  return (select coalesce(json_agg(t), '[]'::json) from ver_mensajes(p_codigo, p_desde) t);
end;
$$;

create or replace function public.rep_enviar_mensaje(p_sesion text, p_codigo text, p_texto text)
returns boolean language plpgsql security definer set search_path = public as $$
begin
  perform dr_dueno(p_sesion, p_codigo);
  return enviar_mensaje(p_codigo, p_texto);
end;
$$;

create or replace function public.rep_subir_foto(p_sesion text, p_codigo text, p_id bigint, p_foto text)
returns boolean language plpgsql security definer set search_path = public as $$
begin
  perform dr_dueno(p_sesion, p_codigo);
  return subir_foto(p_codigo, p_id, p_foto);
end;
$$;

create or replace function public.rep_corregir_ubicacion(p_sesion text, p_codigo text, p_id bigint,
                                                         p_lat double precision, p_lon double precision,
                                                         p_gps_lat double precision, p_gps_lon double precision)
returns text language plpgsql security definer set search_path = public as $$
begin
  perform dr_dueno(p_sesion, p_codigo);
  return corregir_ubicacion(p_codigo, p_id, p_lat, p_lon, p_gps_lat, p_gps_lon);
end;
$$;

-- «Mis salidas» con la sesión (ya no hace falta el enlace personal ni amarrar el celular)
create or replace function public.rep_mis_salidas(p_sesion text)
returns json
language plpgsql security definer set search_path = public as $$
declare
  r repartidores%rowtype;
begin
  select * into r from repartidores where id = dr_sesion_o_error(p_sesion);
  update repartidores set visto_en = now() where id = r.id;
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

create or replace function public.rep_cambiar_disponible(p_sesion text, p_disponible boolean)
returns boolean
language plpgsql security definer set search_path = public as $$
declare
  v uuid := dr_sesion_o_error(p_sesion);
begin
  update repartidores set disponible = coalesce(p_disponible, false), visto_en = now() where id = v;
  return true;
end;
$$;

create or replace function public.rep_guardar_suscripcion(p_sesion text, p_endpoint text, p_p256dh text, p_auth text)
returns boolean
language plpgsql security definer set search_path = public as $$
declare
  v uuid := dr_sesion_o_error(p_sesion);
begin
  if p_endpoint is null or p_endpoint not like 'https://%' or length(p_endpoint) > 600
     or coalesce(p_p256dh, '') = '' or length(p_p256dh) > 200 or coalesce(p_auth, '') = '' or length(p_auth) > 100 then
    return false;
  end if;
  -- una sola sesión: solo el celular de esa sesión recibe avisos
  delete from suscripciones_push where repartidor = v and endpoint <> p_endpoint;
  insert into suscripciones_push (repartidor, endpoint, p256dh, auth) values (v, p_endpoint, p_p256dh, p_auth)
  on conflict (endpoint) do update set repartidor = excluded.repartidor, p256dh = excluded.p256dh,
                                       auth = excluded.auth, creada = now();
  return true;
end;
$$;

-- =====================================================================
-- Para la tienda (DomiRuta, con la clave secreta): poner clave y cerrar sesión
-- =====================================================================

-- Pone (o cambia) la clave: la sesión que tuviera se cierra. Devuelve 'ok' o el problema.
create or replace function public.dr_poner_clave(p_repartidor uuid, p_clave text)
returns text
language plpgsql security definer set search_path = public as $$
declare
  r repartidores%rowtype;
  otro text;
begin
  if coalesce(p_clave, '') !~ '^[0-9]{6}$' or dr_clave_debil(p_clave) then
    return 'La clave son 6 números y no puede ser fácil de adivinar';
  end if;
  select * into r from repartidores where id = p_repartidor;
  if not found then
    return 'Ese repartidor no está en Supabase';
  end if;
  if length(dr_celular(r.celular)) <> 10 then
    return 'Ponga el celular del repartidor (10 números): es su usuario para entrar';
  end if;
  select nombre into otro from repartidores
   where id <> r.id and activo and con_clave and dr_celular(celular) = dr_celular(r.celular) limit 1;
  if otro is not null then
    return 'Ese celular ya lo usa ' || otro || ' para entrar';
  end if;
  update repartidores set clave_hash = extensions.crypt(p_clave, extensions.gen_salt('bf', 10)), con_clave = true,
                          sesion_en = null
   where id = r.id;
  delete from sesiones_repartidor where repartidor = r.id;
  delete from suscripciones_push where repartidor = r.id;
  delete from intentos where clave = 'entrar:' || dr_celular(r.celular);
  return 'ok';
end;
$$;

create or replace function public.dr_cerrar_sesion(p_repartidor uuid)
returns boolean
language plpgsql security definer set search_path = public as $$
begin
  delete from sesiones_repartidor where repartidor = p_repartidor;
  delete from suscripciones_push where repartidor = p_repartidor;
  update repartidores set sesion_en = null where id = p_repartidor;
  return true;
end;
$$;

-- Si la tienda lo desactiva o le cambia el celular, la sesión se cierra sola
create or replace function public.dr_repartidor_cambio()
returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if (not new.activo and old.activo) or dr_celular(new.celular) <> dr_celular(old.celular) then
    delete from sesiones_repartidor where repartidor = new.id;
    delete from suscripciones_push where repartidor = new.id;
    new.sesion_en := null;
  end if;
  return new;
end;
$$;
drop trigger if exists repartidor_cambio on public.repartidores;
create trigger repartidor_cambio before update on public.repartidores
  for each row execute function public.dr_repartidor_cambio();

-- =====================================================================
-- Permisos
-- =====================================================================

do $$
declare
  f text;
begin
  -- internas: nadie de afuera
  foreach f in array array[
    'public.dr_celular(text)', 'public.dr_huella(text)', 'public.dr_sesion(text)', 'public.dr_sesion_o_error(text)',
    'public.dr_dueno(text, text)', 'public.dr_clave_debil(text)', 'public.dr_repartidor_cambio()']
  loop
    execute 'revoke all on function ' || f || ' from public, anon, authenticated';
  end loop;
  -- la página del repartidor
  foreach f in array array[
    'public.entrar(text, text, text)', 'public.salir(text)', 'public.cambiar_clave(text, text, text)',
    'public.rep_info_salida(text, text)', 'public.rep_ruta_salida(text, text)', 'public.rep_ver_salida(text, text)',
    'public.rep_responder_salida(text, text, boolean, text)',
    'public.rep_enviar_posicion(text, text, double precision, double precision, double precision, double precision, double precision)',
    'public.rep_pausar_ubicacion(text, text, boolean)',
    'public.rep_marcar_entrega_gps(text, text, bigint, text, text, double precision, double precision)',
    'public.rep_ver_mensajes(text, text, bigint)', 'public.rep_enviar_mensaje(text, text, text)',
    'public.rep_subir_foto(text, text, bigint, text)',
    'public.rep_corregir_ubicacion(text, text, bigint, double precision, double precision, double precision, double precision)',
    'public.rep_mis_salidas(text)', 'public.rep_cambiar_disponible(text, boolean)',
    'public.rep_guardar_suscripcion(text, text, text, text)']
  loop
    execute 'revoke all on function ' || f || ' from public';
    execute 'grant execute on function ' || f || ' to anon, authenticated';
  end loop;
  -- la tienda (clave secreta)
  foreach f in array array['public.dr_poner_clave(uuid, text)', 'public.dr_cerrar_sesion(uuid)']
  loop
    execute 'revoke all on function ' || f || ' from public, anon, authenticated';
    execute 'grant execute on function ' || f || ' to service_role';
  end loop;
end $$;
