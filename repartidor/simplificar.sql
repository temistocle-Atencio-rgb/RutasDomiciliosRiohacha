-- DomiRuta: vuelve al enlace por salida (sin cuentas ni contraseñas).
-- Quita lo de usuarios.sql y deja las funciones de la página del repartidor con el código del enlace.
-- Se puede correr otra vez sin dañar nada.

drop function if exists public.entrar(text, text);
drop function if exists public.salir(text);
drop function if exists public.yo(text);
drop function if exists public.mis_salidas(text);
drop function if exists public.mis_entregas(text);
drop function if exists public.marcar_entrega(text, bigint, text, text);
drop function if exists public.ver_mensajes(text, uuid, bigint);
drop function if exists public.enviar_mensaje(text, uuid, text);
drop function if exists public.enviar_ubicacion(text, double precision, double precision);
drop function if exists public.subir_foto(text, bigint, text);
drop function if exists public.repartidor_de(text);
drop function if exists public.salida_de_entrega(uuid, bigint);
drop function if exists public.crear_repartidor(text, text, text, text);
drop function if exists public.cambiar_clave(uuid, text);
drop function if exists public.activar_repartidor(uuid, boolean);
alter table public.salidas drop column if exists repartidor_id;
drop table if exists public.intentos;
drop table if exists public.sesiones;
drop table if exists public.repartidores;

-- ======== lo mismo de mejoras.sql (funciones con el código del enlace) ========
-- Salida: cuándo terminó todas las paradas y última ubicación del repartidor
alter table public.salidas add column if not exists regresando timestamptz;
alter table public.salidas add column if not exists lat double precision;
alter table public.salidas add column if not exists lon double precision;
alter table public.salidas add column if not exists ubicacion_en timestamptz;

-- Parada: foto de entrega (JPEG pequeño en texto data:image/jpeg;base64,...)
alter table public.entregas_salida add column if not exists foto text;
alter table public.entregas_salida add column if not exists foto_en timestamptz;

-- Mensajes entre la tienda y el repartidor
create table if not exists public.mensajes_salida (
  id bigint generated always as identity primary key,
  salida uuid not null references public.salidas(id) on delete cascade,
  de text not null check (de in ('tienda', 'repartidor')),
  texto text not null check (length(texto) between 1 and 300),
  creado timestamptz not null default now(),
  leido boolean not null default false  -- lo usa DomiRuta para el aviso de mensaje nuevo
);
create index if not exists mensajes_salida_salida_idx on public.mensajes_salida (salida, id);
alter table public.mensajes_salida enable row level security;
revoke all on public.mensajes_salida from anon, authenticated;

-- Busca la salida abierta de un código (uso interno de las funciones)
create or replace function public.salida_abierta(p_codigo text)
returns uuid language sql stable security definer set search_path = public as $$
  select id from salidas where codigo = p_codigo and not cerrada;
$$;
revoke all on function public.salida_abierta(text) from public, anon, authenticated;

-- ver_salida ahora también dice si la parada ya tiene foto
drop function if exists public.ver_salida(text);
create function public.ver_salida(p_codigo text)
returns table (id bigint, orden int, nombre text, direccion text, referencia text, telefono text,
               lat double precision, lon double precision, cobrar int, paga_con int,
               estado text, motivo text, tiene_foto boolean)
language sql stable security definer set search_path = public as $$
  select e.id, e.orden, e.nombre, e.direccion, e.referencia, e.telefono, e.lat, e.lon,
         e.cobrar, e.paga_con, e.estado, e.motivo, e.foto is not null
  from entregas_salida e
  join salidas s on s.id = e.salida
  where s.codigo = p_codigo and not s.cerrada
  order by e.orden;
$$;

-- marcar_entrega: igual que antes, y si ya no queda ninguna "en camino" la salida pasa a Regresando
create or replace function public.marcar_entrega(p_codigo text, p_id bigint, p_estado text,
                                                 p_motivo text default '')
returns boolean
language plpgsql security definer set search_path = public as $$
declare
  v_salida uuid := salida_abierta(p_codigo);
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

-- Mensajes: el repartidor escribe
create or replace function public.enviar_mensaje(p_codigo text, p_texto text)
returns boolean
language plpgsql security definer set search_path = public as $$
declare
  v_salida uuid := salida_abierta(p_codigo);
  v_texto text := left(trim(coalesce(p_texto, '')), 300);
begin
  if v_salida is null or v_texto = '' then
    return false;
  end if;
  if (select count(*) from mensajes_salida where salida = v_salida) >= 300 then
    return false; -- tope para que nadie llene la base
  end if;
  insert into mensajes_salida (salida, de, texto) values (v_salida, 'repartidor', v_texto);
  return true;
end;
$$;

-- Mensajes: el repartidor lee la conversación (p_desde = último id que ya tiene; 0 = todos)
create or replace function public.ver_mensajes(p_codigo text, p_desde bigint default 0)
returns table (id bigint, de text, texto text, creado timestamptz)
language sql stable security definer set search_path = public as $$
  select m.id, m.de, m.texto, m.creado
  from mensajes_salida m
  where m.salida = salida_abierta(p_codigo) and m.id > coalesce(p_desde, 0)
  order by m.id;
$$;

-- Ubicación: el celular del repartidor la manda cada 30 s mientras la salida está abierta
create or replace function public.enviar_ubicacion(p_codigo text, p_lat double precision, p_lon double precision)
returns boolean
language plpgsql security definer set search_path = public as $$
declare
  v_salida uuid := salida_abierta(p_codigo);
begin
  if v_salida is null or p_lat is null or p_lon is null
     or p_lat not between -90 and 90 or p_lon not between -180 and 180 then
    return false;
  end if;
  update salidas set lat = p_lat, lon = p_lon, ubicacion_en = now() where id = v_salida;
  return true;
end;
$$;

-- Foto de entrega: JPEG ya reducido en el celular (máximo ~250 KB)
create or replace function public.subir_foto(p_codigo text, p_id bigint, p_foto text)
returns boolean
language plpgsql security definer set search_path = public as $$
declare
  v_salida uuid := salida_abierta(p_codigo);
  n int;
begin
  if v_salida is null or p_foto is null
     or p_foto not like 'data:image/jpeg;base64,%' or length(p_foto) > 350000 then
    return false;
  end if;
  update entregas_salida set foto = p_foto, foto_en = now() where id = p_id and salida = v_salida;
  get diagnostics n = row_count;
  return n = 1;
end;
$$;

-- Permisos: la clave pública solo puede usar estas funciones
revoke all on function public.ver_salida(text) from public;
revoke all on function public.marcar_entrega(text, bigint, text, text) from public;
revoke all on function public.enviar_mensaje(text, text) from public;
revoke all on function public.ver_mensajes(text, bigint) from public;
revoke all on function public.enviar_ubicacion(text, double precision, double precision) from public;
revoke all on function public.subir_foto(text, bigint, text) from public;
grant execute on function public.ver_salida(text) to anon, authenticated;
grant execute on function public.marcar_entrega(text, bigint, text, text) to anon, authenticated;
grant execute on function public.enviar_mensaje(text, text) to anon, authenticated;
grant execute on function public.ver_mensajes(text, bigint) to anon, authenticated;
grant execute on function public.enviar_ubicacion(text, double precision, double precision) to anon, authenticated;
grant execute on function public.subir_foto(text, bigint, text) to anon, authenticated;
