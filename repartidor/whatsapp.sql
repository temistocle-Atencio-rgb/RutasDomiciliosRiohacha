-- DomiRuta: mensajes de WhatsApp automáticos (API de WhatsApp de Meta).
-- Cómo funciona: cuando pasa algo (llega un pedido, el repartidor acepta, la tienda cancela...) la base
-- anota el mensaje en «wa_salida» y le avisa a la función «whatsapp» de Supabase, que lo manda a Meta.
-- El token de Meta solo lo tiene la función (Secrets de Supabase): ni la base ni DomiRuta lo ven.
-- Se corre DESPUÉS de tienda.sql. Se puede correr otra vez sin dañar nada.

create extension if not exists pg_net with schema extensions;

-- =====================================================================
-- Tablas (cerradas: solo las usa la función con la clave de servicio)
-- =====================================================================

-- Ajustes (una fila): dónde está la función
create table if not exists public.wa_ajustes (
  id int primary key default 1 check (id = 1),
  url_funcion text not null default '',
  activo boolean not null default true
);
insert into public.wa_ajustes (id, url_funcion)
values (1, 'https://xivokswlegxetxgovhdw.supabase.co/functions/v1/whatsapp')
on conflict (id) do nothing;

-- Los números de cuenta de cada medio de pago (los sube DomiRuta). No salen en la página:
-- solo se le mandan por WhatsApp a quien ya hizo un pedido.
create table if not exists public.pagos_tienda (
  medio text primary key check (medio ~ '^[a-z_]{2,20}$'),
  cuenta text not null default '' check (length(cuenta) <= 60),
  titular text not null default '' check (length(titular) <= 80)
);

-- La bandeja de salida: cada mensaje que hay que mandar (y cómo le fue)
create table if not exists public.wa_salida (
  id bigint generated always as identity primary key,
  creado timestamptz not null default now(),
  enviar_desde timestamptz not null default now(),   -- «salida_nueva» espera a que suban las entregas
  para text not null check (para ~ '^57[0-9]{10}$'),
  plantilla text,                                     -- null = mensaje libre (dentro de las 24 h)
  parametros jsonb not null default '[]',             -- los {{1}}, {{2}}... del cuerpo
  boton text,                                         -- lo que va en el {{1}} del botón dinámico
  texto text,                                         -- mensaje libre
  enlace text,                                        -- mensaje libre con botón de enlace
  enlace_texto text,
  llave text unique,                                  -- para no mandar dos veces lo mismo
  estado text not null default 'pendiente' check (estado in ('pendiente', 'enviando', 'enviado', 'error')),
  intentos int not null default 0,
  error text not null default '',
  wamid text,
  enviado_en timestamptz
);
create index if not exists wa_salida_pendiente_idx on public.wa_salida (enviar_desde) where estado in ('pendiente', 'enviando');

-- Lo que llega de los clientes (texto o foto)
create table if not exists public.wa_entrada (
  id bigint generated always as identity primary key,
  creado timestamptz not null default now(),
  de text not null,
  tipo text not null,
  texto text not null default '',
  media_id text,
  wamid text unique,
  pedido bigint
);

-- Cuándo se le mandó la bienvenida a cada número (máximo una cada 12 horas)
create table if not exists public.wa_contactos (
  numero text primary key,
  bienvenida_en timestamptz,
  ultimo_mensaje timestamptz
);

-- El comprobante de pago del pedido
alter table public.pedidos_web add column if not exists comprobante text;        -- ruta en la carpeta privada
alter table public.pedidos_web add column if not exists comprobante_en timestamptz;
alter table public.pedidos_web add column if not exists pagado boolean not null default false;

alter table public.wa_ajustes enable row level security;
alter table public.pagos_tienda enable row level security;
alter table public.wa_salida enable row level security;
alter table public.wa_entrada enable row level security;
alter table public.wa_contactos enable row level security;
revoke all on public.wa_ajustes, public.pagos_tienda, public.wa_salida, public.wa_entrada, public.wa_contactos
  from anon, authenticated;
grant select, insert, update, delete on public.wa_ajustes, public.pagos_tienda, public.wa_salida, public.wa_entrada,
  public.wa_contactos to service_role;

-- Carpeta privada para los comprobantes (solo la ve DomiRuta con la clave secreta)
do $$
begin
  if exists (select 1 from information_schema.tables where table_schema = 'storage' and table_name = 'buckets') then
    insert into storage.buckets (id, name, public) values ('comprobantes', 'comprobantes', false) on conflict (id) do nothing;
  end if;
end $$;

-- =====================================================================
-- Ayudas
-- =====================================================================

-- 3001234567 / +57 300 123 4567 -> 573001234567 (null si no es un celular de Colombia)
create or replace function public.wa_numero(t text)
returns text language sql immutable as $$
  select case
    when d ~ '^3[0-9]{9}$' then '57' || d
    when d ~ '^573[0-9]{9}$' then d
    else null end
  from (select regexp_replace(coalesce(t, ''), '[^0-9]', '', 'g') as d) x;
$$;

create or replace function public.wa_pesos(v int)
returns text language sql immutable as $$
  select '$ ' || replace(to_char(coalesce(v, 0), 'FM999,999,999'), ',', '.');
$$;

-- El primer nombre, para saludar («Ana María Pérez» -> «Ana»)
create or replace function public.wa_nombre(t text)
returns text language sql immutable as $$
  select coalesce(nullif(split_part(trim(coalesce(t, '')), ' ', 1), ''), 'cliente');
$$;

-- Los parámetros no pueden llevar saltos de línea ni muchos espacios (regla de Meta)
create or replace function public.wa_limpio(t text, maximo int default 120)
returns text language sql immutable as $$
  select left(coalesce(nullif(regexp_replace(trim(coalesce(t, '')), '\s+', ' ', 'g'), ''), '-'), maximo);
$$;

create or replace function public.wa_medio_texto(m text)
returns text language sql immutable as $$
  select case m
    when 'efectivo' then 'efectivo' when 'nequi' then 'Nequi' when 'daviplata' then 'Daviplata'
    when 'breb' then 'Bre-B' when 'transferencia' then 'transferencia' when 'bancolombia' then 'Bancolombia'
    when 'tarjeta_debito' then 'tarjeta débito' when 'tarjeta_credito' then 'tarjeta crédito'
    when 'nu' then 'Nu' when 'dale' then 'Dale!' when 'mercado_pago' then 'Mercado Pago'
    else coalesce(m, 'efectivo') end;
$$;

-- Le avisa a la función que hay mensajes (sin esperar la respuesta). Si no hay pg_net, no pasa nada:
-- la función también revisa la bandeja cada vez que Meta le escribe.
create or replace function public.wa_despertar()
returns void language plpgsql security definer set search_path = public, extensions as $$
declare
  u text;
begin
  select url_funcion into u from wa_ajustes where id = 1 and activo;
  if u is null or u = '' then
    return;
  end if;
  perform net.http_post(url := u, body := '{"revisar":true}'::jsonb,
                        headers := '{"Content-Type":"application/json"}'::jsonb, timeout_milliseconds := 2000);
exception when others then
  null; -- sin pg_net o sin internet: se manda en la próxima revisión
end;
$$;

create or replace function public.wa_encolar(p_para text, p_plantilla text, p_parametros jsonb, p_boton text,
                                             p_llave text, p_desde timestamptz default now())
returns void language plpgsql security definer set search_path = public as $$
begin
  if p_para is null then
    return;
  end if;
  insert into wa_salida (para, plantilla, parametros, boton, llave, enviar_desde)
  values (p_para, p_plantilla, p_parametros, p_boton, p_llave, p_desde)
  on conflict (llave) do nothing;
  perform wa_despertar();
end;
$$;

-- =====================================================================
-- Cuándo se manda cada plantilla
-- =====================================================================

-- Llega un pedido de la página: «Recibimos tu pedido» (con los datos para pagar si no es efectivo)
create or replace function public.wa_tr_pedido_nuevo()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  t text := coalesce((select nombre from tienda_info where id = 1), '');
  p pagos_tienda%rowtype;
  cuenta text;
begin
  t := wa_limpio(nullif(t, ''), 60);
  if new.pago in ('efectivo', 'tarjeta_debito', 'tarjeta_credito') then
    perform wa_encolar(wa_numero(new.celular), 'pedido_recibido',
      jsonb_build_array(wa_limpio(wa_nombre(new.nombre), 40), t, new.codigo, wa_pesos(new.total), wa_medio_texto(new.pago)),
      new.codigo, 'recibido:' || new.id);
  else
    select * into p from pagos_tienda where medio = new.pago;
    cuenta := case when p.cuenta is null or p.cuenta = '' then 'el número que te escribirá la tienda'
                   when p.titular = '' then p.cuenta
                   else p.cuenta || ' a nombre de ' || p.titular end;
    perform wa_encolar(wa_numero(new.celular), 'pedido_recibido_pago',
      jsonb_build_array(wa_limpio(wa_nombre(new.nombre), 40), t, new.codigo, wa_pesos(new.total),
                        wa_medio_texto(new.pago), wa_limpio(cuenta, 140)),
      new.codigo, 'recibido:' || new.id);
  end if;
  return new;
end;
$$;
drop trigger if exists wa_pedido_nuevo on public.pedidos_web;
create trigger wa_pedido_nuevo after insert on public.pedidos_web
  for each row execute function public.wa_tr_pedido_nuevo();

-- La tienda cancela un pedido de la página: «No pudimos atender tu pedido»
create or replace function public.wa_tr_pedido_estado()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if new.estado = 'cancelado' and old.estado is distinct from 'cancelado' then
    perform wa_encolar(wa_numero(new.celular), 'pedido_cancelado',
      jsonb_build_array(wa_limpio(wa_nombre(new.nombre), 40),
                        wa_limpio(nullif((select nombre from tienda_info where id = 1), ''), 60), new.codigo,
                        wa_limpio(nullif(new.motivo, ''), 120)),
      null, 'cancelado:' || new.id);
  end if;
  if new.pagado and not old.pagado then
    -- dentro de las 24 h desde que mandó el comprobante: mensaje libre (gratis)
    insert into wa_salida (para, texto, llave)
    select wa_numero(new.celular),
           '¡Listo, ' || wa_nombre(new.nombre) || '! Confirmamos tu pago del pedido ' || new.codigo
             || '. Ya lo estamos alistando.', 'pagado:' || new.id
    where wa_numero(new.celular) is not null
    on conflict (llave) do nothing;
    perform wa_despertar();
  end if;
  return new;
end;
$$;
drop trigger if exists wa_pedido_estado on public.pedidos_web;
create trigger wa_pedido_estado after update on public.pedidos_web
  for each row execute function public.wa_tr_pedido_estado();

-- La tienda despacha: «Tienes una salida nueva» al repartidor (espera 20 s a que suban las entregas)
create or replace function public.wa_tr_salida_nueva()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  cel text;
begin
  select celular into cel from repartidores where id = new.repartidor_id;
  perform wa_encolar(wa_numero(cel), 'salida_nueva', '[]'::jsonb, new.codigo, 'salida:' || new.id,
                     now() + interval '20 seconds');
  return new;
end;
$$;
drop trigger if exists wa_salida_nueva on public.salidas;
create trigger wa_salida_nueva after insert on public.salidas
  for each row execute function public.wa_tr_salida_nueva();

-- El repartidor acepta: «Tu pedido va en camino» a cada cliente de la salida, con su enlace al mapa
create or replace function public.wa_tr_salida_aceptada()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  e record;
  t text := wa_limpio(nullif(coalesce(new.tienda, ''), ''), 60);
begin
  if new.aceptada is null or old.aceptada is not null then
    return new;
  end if;
  if t = '-' then
    t := wa_limpio(nullif((select nombre from tienda_info where id = 1), ''), 60);
  end if;
  update pedidos_web set estado = 'en_camino', estado_en = now()
   where entrega = 'domicilio' and estado in ('nuevo', 'aceptado', 'preparando') and creado > now() - interval '1 day'
     and wa_numero(celular) in (select wa_numero(telefono) from entregas_salida where salida = new.id);
  for e in select * from entregas_salida where salida = new.id and enlace_cliente is not null loop
    perform wa_encolar(wa_numero(e.telefono), 'pedido_en_camino',
      jsonb_build_array(wa_limpio(wa_nombre(e.nombre), 40), t, wa_limpio(wa_nombre(new.repartidor), 40),
                        case when e.cobrar > 0 then wa_pesos(e.cobrar) || ' en efectivo' else 'ya está pagado' end),
      e.enlace_cliente, 'camino:' || e.id);
  end loop;
  return new;
end;
$$;
drop trigger if exists wa_salida_aceptada on public.salidas;
create trigger wa_salida_aceptada after update on public.salidas
  for each row execute function public.wa_tr_salida_aceptada();

-- El pedido de la página sigue solo a la entrega: cuando el repartidor acepta pasa a «Va en camino»
-- y cuando marca la entrega, a «Entregado» (se reconoce por el celular del cliente, del último día).
create or replace function public.wa_tr_entrega_estado()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if new.estado = 'entregado' and old.estado is distinct from 'entregado' and wa_numero(new.telefono) is not null then
    update pedidos_web set estado = 'entregado', estado_en = now()
     where wa_numero(celular) = wa_numero(new.telefono) and estado in ('aceptado', 'preparando', 'en_camino')
       and entrega = 'domicilio' and creado > now() - interval '1 day';
  end if;
  return new;
end;
$$;
drop trigger if exists wa_entrega_estado on public.entregas_salida;
create trigger wa_entrega_estado after update on public.entregas_salida
  for each row execute function public.wa_tr_entrega_estado();

-- La tienda cancela la salida o le pasa un pedido a otro: «Cambió tu salida»
create or replace function public.wa_tr_mensaje_tienda()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  s salidas%rowtype;
  cel text;
  cambio text;
begin
  if new.de <> 'tienda' or not (new.texto like 'La tienda canceló%' or new.texto like 'La tienda le pasó%') then
    return new;
  end if;
  select * into s from salidas where id = new.salida;
  select celular into cel from repartidores where id = s.repartidor_id;
  cambio := regexp_replace(substr(new.texto, length('La tienda ') + 1), '\.\s*$', '');
  perform wa_encolar(wa_numero(cel), 'salida_cambio',
    jsonb_build_array(wa_limpio(wa_nombre(s.repartidor), 40),
                      wa_limpio(nullif(coalesce(s.tienda, ''), ''), 60), wa_limpio(cambio, 200)),
    null, 'cambio:' || new.id);
  return new;
end;
$$;
drop trigger if exists wa_mensaje_tienda on public.mensajes_salida;
create trigger wa_mensaje_tienda after insert on public.mensajes_salida
  for each row execute function public.wa_tr_mensaje_tienda();

-- =====================================================================
-- Lo que usa la función «whatsapp» (solo con la clave de servicio)
-- =====================================================================

-- Los mensajes listos para mandar (los marca «enviando» para que no salgan dos veces)
create or replace function public.wa_por_enviar(p_max int default 20)
returns setof public.wa_salida language plpgsql security definer set search_path = public as $$
declare
  r wa_salida%rowtype;
  s salidas%rowtype;
  n int;
  mins int;
begin
  for r in select * from wa_salida
           where (estado = 'pendiente' or (estado = 'enviando' and enviado_en < now() - interval '2 minutes'))
             and enviar_desde <= now() and intentos < 5
           order by id limit p_max for update skip locked loop
    -- «salida_nueva»: los datos se arman ahora (las entregas ya subieron)
    if r.plantilla = 'salida_nueva' then
      select * into s from salidas where codigo = r.boton;
      select count(*) into n from entregas_salida where salida = s.id;
      if s.id is null or s.cerrada or s.aceptada is not null or s.rechazada is not null or n = 0 then
        update wa_salida set estado = 'error', error = 'la salida ya no está pendiente' where id = r.id;
        continue;
      end if;
      mins := greatest(1, least(60, coalesce(ceil(extract(epoch from (s.vence_en - now())) / 60)::int, 10)));
      r.parametros := jsonb_build_array(wa_limpio(wa_nombre(s.repartidor), 40), wa_limpio(nullif(s.tienda, ''), 60),
                                        case when n = 1 then '1 entrega' else n || ' entregas' end, mins::text);
      update wa_salida set parametros = r.parametros where id = r.id;
    end if;
    update wa_salida set estado = 'enviando', intentos = intentos + 1, enviado_en = now() where id = r.id;
    r.estado := 'enviando';
    return next r;
  end loop;
end;
$$;

create or replace function public.wa_resultado(p_id bigint, p_ok boolean, p_wamid text, p_error text)
returns void language sql security definer set search_path = public as $$
  update wa_salida set estado = case when p_ok then 'enviado'
                                     when intentos >= 5 or p_error like 'no-reintentar:%' then 'error'
                                     else 'pendiente' end,
                       wamid = p_wamid, error = left(coalesce(p_error, ''), 300),
                       enviar_desde = case when p_ok then enviar_desde else now() + interval '1 minute' end
  where id = p_id;
$$;

-- Llega un mensaje de un cliente. Devuelve qué hacer: {accion: 'comprobante', pedido} o {accion: 'nada'}.
-- La bienvenida (enlace de la tienda) se encola aquí mismo, máximo una vez cada 12 horas.
create or replace function public.wa_entrante(p_de text, p_tipo text, p_texto text, p_media text, p_wamid text)
returns json language plpgsql security definer set search_path = public as $$
declare
  num text := wa_numero(p_de);
  ped pedidos_web%rowtype;
  t tienda_info%rowtype;
  c wa_contactos%rowtype;
  enlace text := 'https://temistocle-atencio-rgb.github.io/domiruta-repartidor/tienda.html';
begin
  if num is null then
    return json_build_object('accion', 'nada');
  end if;
  begin
    insert into wa_entrada (de, tipo, texto, media_id, wamid) values (num, p_tipo, left(coalesce(p_texto, ''), 1000), p_media, p_wamid);
  exception when unique_violation then
    return json_build_object('accion', 'nada'); -- Meta lo mandó dos veces
  end;
  insert into wa_contactos (numero, ultimo_mensaje) values (num, now())
  on conflict (numero) do update set ultimo_mensaje = now();
  select * into t from tienda_info where id = 1;

  -- ¿una foto de alguien con un pedido por pagar (últimas 24 h)? -> es el comprobante
  if p_tipo in ('image', 'document') and p_media is not null then
    select * into ped from pedidos_web
     where wa_numero(celular) = num and pago not in ('efectivo', 'tarjeta_debito', 'tarjeta_credito')
       and not pagado and estado not in ('cancelado', 'entregado') and creado > now() - interval '24 hours'
     order by id desc limit 1;
    if found then
      update wa_entrada set pedido = ped.id where wamid = p_wamid;
      return json_build_object('accion', 'comprobante', 'pedido', ped.id, 'codigo', ped.codigo);
    end if;
  end if;

  select * into c from wa_contactos where numero = num;
  if c.bienvenida_en is null or c.bienvenida_en < now() - interval '12 hours' then
    update wa_contactos set bienvenida_en = now() where numero = num;
    insert into wa_salida (para, texto, enlace, enlace_texto, llave)
    values (num, '¡Hola! 👋 Bienvenido a ' || coalesce(nullif(t.nombre, ''), 'nuestra tienda') || '. '
                 || 'Mira los productos y haz tu pedido aquí. Te lo llevamos a la casa.'
                 || case when wa_numero(t.telefono) is not null
                         then E'\n\nSi quieres hablar con una persona, escríbenos al ' || t.telefono || '.' else '' end,
            enlace, 'Ver la tienda', 'bienvenida:' || num || ':' || to_char(now(), 'YYYYMMDDHH24MI'))
    on conflict (llave) do nothing;
  end if;
  return json_build_object('accion', 'nada');
end;
$$;

-- Ya se guardó la foto del comprobante: se anota en el pedido y se le responde al cliente
create or replace function public.wa_comprobante(p_pedido bigint, p_ruta text)
returns void language plpgsql security definer set search_path = public as $$
declare
  ped pedidos_web%rowtype;
begin
  update pedidos_web set comprobante = p_ruta, comprobante_en = now(), tomado = false
   where id = p_pedido returning * into ped;  -- tomado=false: DomiRuta lo vuelve a mirar
  if found then
    insert into wa_salida (para, texto, llave)
    values (wa_numero(ped.celular), 'Recibimos tu comprobante del pedido ' || ped.codigo
            || '. La tienda lo revisa y te avisa por aquí.', 'comprobante:' || ped.id || ':' || to_char(now(), 'YYYYMMDDHH24MISS'))
    on conflict (llave) do nothing;
  end if;
end;
$$;

-- El cliente ve en la página si ya se confirmó el pago
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
                           'entrega', r.entrega, 'pago', r.pago, 'pagado', r.pagado,
                           'comprobante', r.comprobante is not null);
end;
$$;

-- El enlace del botón «Escribir a la tienda» (la página lo abre): solo el teléfono público de la tienda
-- (ya sale en ver_catalogo, no es nada nuevo).

revoke all on function public.wa_por_enviar(int) from public, anon, authenticated;
revoke all on function public.wa_resultado(bigint, boolean, text, text) from public, anon, authenticated;
revoke all on function public.wa_entrante(text, text, text, text, text) from public, anon, authenticated;
revoke all on function public.wa_comprobante(bigint, text) from public, anon, authenticated;
revoke all on function public.wa_encolar(text, text, jsonb, text, text, timestamptz) from public, anon, authenticated;
revoke all on function public.wa_despertar() from public, anon, authenticated;
grant execute on function public.wa_por_enviar(int), public.wa_resultado(bigint, boolean, text, text),
  public.wa_entrante(text, text, text, text, text), public.wa_comprobante(bigint, text) to service_role;
grant execute on function public.ver_pedido_web(text) to anon, authenticated;
