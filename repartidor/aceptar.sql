-- DomiRuta: el domiciliario acepta o no la salida antes de empezar.
-- Mientras no acepte, puede ver y escribir a la tienda, pero no marcar entregas, ni subir fotos, ni enviar ubicación.
-- Se corre después de visto.sql. Se puede correr otra vez.

alter table public.salidas add column if not exists tienda text not null default '';
alter table public.salidas add column if not exists aceptada timestamptz;
alter table public.salidas add column if not exists rechazada timestamptz;
alter table public.salidas add column if not exists motivo_rechazo text not null default '';

-- La salida aceptada y abierta de un código (uso interno)
create or replace function public.salida_aceptada(p_codigo text)
returns uuid language sql stable security definer set search_path = public as $$
  select id from salidas where codigo = p_codigo and not cerrada and aceptada is not null and rechazada is null;
$$;
revoke all on function public.salida_aceptada(text) from public, anon, authenticated;

-- Lo que el domiciliario ve antes de aceptar: de qué tienda es y si ya respondió
create or replace function public.info_salida(p_codigo text)
returns table (tienda text, repartidor text, creada timestamptz, aceptada boolean, rechazada boolean)
language sql stable security definer set search_path = public as $$
  select s.tienda, s.repartidor, s.creada, s.aceptada is not null, s.rechazada is not null
  from salidas s where s.codigo = p_codigo and not s.cerrada;
$$;

-- Acepta (p_acepta = true) o no puede (false, con motivo). Solo se responde una vez.
create or replace function public.responder_salida(p_codigo text, p_acepta boolean, p_motivo text default '')
returns boolean
language plpgsql security definer set search_path = public as $$
declare
  n int;
begin
  if p_acepta then
    update salidas set aceptada = now()
     where codigo = p_codigo and not cerrada and aceptada is null and rechazada is null;
  else
    update salidas set rechazada = now(), motivo_rechazo = left(coalesce(p_motivo, ''), 120)
     where codigo = p_codigo and not cerrada and aceptada is null and rechazada is null;
  end if;
  get diagnostics n = row_count;
  return n = 1;
end;
$$;

-- Marcar, foto y ubicación: solo con la salida aceptada
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

create or replace function public.enviar_ubicacion(p_codigo text, p_lat double precision, p_lon double precision)
returns boolean
language plpgsql security definer set search_path = public as $$
declare
  v_salida uuid := salida_aceptada(p_codigo);
begin
  if v_salida is null or p_lat is null or p_lon is null
     or p_lat not between -90 and 90 or p_lon not between -180 and 180 then
    return false;
  end if;
  update salidas set lat = p_lat, lon = p_lon, ubicacion_en = now() where id = v_salida;
  return true;
end;
$$;

create or replace function public.subir_foto(p_codigo text, p_id bigint, p_foto text)
returns boolean
language plpgsql security definer set search_path = public as $$
declare
  v_salida uuid := salida_aceptada(p_codigo);
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

revoke all on function public.info_salida(text) from public;
revoke all on function public.responder_salida(text, boolean, text) from public;
grant execute on function public.info_salida(text) to anon, authenticated;
grant execute on function public.responder_salida(text, boolean, text) to anon, authenticated;
