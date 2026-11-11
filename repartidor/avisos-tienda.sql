-- =====================================================================
-- Avisos (notificaciones) de la tienda en línea y de Pedidos en línea
-- - El cliente que hizo un pedido puede pedir que le avise cuando cambie (aceptado, en camino...).
-- - El encargado de pedidos recibe un aviso cuando llega un pedido nuevo.
-- Los disparadores dejan el aviso en una cola y despiertan la función «avisos», que los manda.
-- Se puede correr varias veces sin dañar nada.
-- =====================================================================

create extension if not exists pg_net with schema extensions;

-- Dónde está la función que manda los avisos
create table if not exists public.avisos_ajustes (
  id int primary key default 1 check (id = 1),
  url_funcion text not null default '',
  activo boolean not null default true
);
insert into public.avisos_ajustes (id, url_funcion)
values (1, 'https://xivokswlegxetxgovhdw.supabase.co/functions/v1/avisos')
on conflict (id) do nothing;

-- Celulares de clientes que quieren saber cómo va SU pedido (por el código del pedido)
create table if not exists public.avisos_cliente (
  endpoint text not null check (endpoint like 'https://%' and length(endpoint) <= 600),
  pedido text not null references public.pedidos_web(codigo) on delete cascade,
  p256dh text not null check (length(p256dh) <= 200),
  auth text not null check (length(auth) <= 100),
  creada timestamptz not null default now(),
  primary key (endpoint, pedido)
);

-- Celulares de los encargados (pedidos nuevos)
create table if not exists public.avisos_encargado (
  endpoint text primary key check (endpoint like 'https://%' and length(endpoint) <= 600),
  encargado uuid not null references public.encargados(id) on delete cascade,
  p256dh text not null check (length(p256dh) <= 200),
  auth text not null check (length(auth) <= 100),
  creada timestamptz not null default now()
);

-- La cola: lo que falta por mandar
create table if not exists public.avisos_cola (
  id bigint generated always as identity primary key,
  endpoint text not null,
  p256dh text not null,
  auth text not null,
  titulo text not null,
  texto text not null default '',
  url text not null default './',
  creada timestamptz not null default now(),
  enviado boolean not null default false,
  intentos int not null default 0,
  error text not null default ''
);
create index if not exists avisos_cola_pendiente_idx on public.avisos_cola (creada) where not enviado;

alter table public.avisos_ajustes enable row level security;
alter table public.avisos_cliente enable row level security;
alter table public.avisos_encargado enable row level security;
alter table public.avisos_cola enable row level security;
revoke all on public.avisos_ajustes, public.avisos_cliente, public.avisos_encargado, public.avisos_cola from anon, authenticated;
grant select, insert, update, delete on public.avisos_ajustes, public.avisos_cliente, public.avisos_encargado,
  public.avisos_cola to service_role;
grant usage, select on all sequences in schema public to service_role;

-- Despierta la función (si falla, la próxima vez se mandan los que queden)
create or replace function public.avisos_despertar()
returns void language plpgsql security definer set search_path = public, extensions as $$
declare
  u text;
begin
  select url_funcion into u from avisos_ajustes where id = 1 and activo;
  if u is null or u = '' then
    return;
  end if;
  perform net.http_post(url := u, body := '{"revisar":true}'::jsonb,
                        headers := '{"Content-Type":"application/json"}'::jsonb, timeout_milliseconds := 2000);
exception when others then
  null;
end;
$$;

-- =====================================================================
-- Lo que llama la página (solo guarda el celular; no deja leer nada)
-- =====================================================================

-- Cliente: «Avisarme cuando cambie mi pedido». Solo pedidos de los últimos 3 días que no han terminado.
create or replace function public.tienda_activar_avisos(p_codigo text, p_endpoint text, p_p256dh text, p_auth text)
returns boolean language plpgsql security definer set search_path = public as $$
declare
  ok boolean;
begin
  if coalesce(p_endpoint, '') !~ '^https://' or length(p_endpoint) > 600 or length(coalesce(p_p256dh, '')) not between 20 and 200
     or length(coalesce(p_auth, '')) not between 8 and 100 then
    return false;
  end if;
  if not dr_limite('avisos-ip:' || dr_ip(), 20, interval '1 hour') then
    return false;
  end if;
  select true into ok from pedidos_web
   where codigo = p_codigo and creado > now() - interval '3 days' and estado not in ('entregado', 'cancelado');
  if ok is null then
    return false;
  end if;
  insert into avisos_cliente (endpoint, pedido, p256dh, auth) values (p_endpoint, p_codigo, p_p256dh, p_auth)
  on conflict (endpoint, pedido) do update set p256dh = excluded.p256dh, auth = excluded.auth, creada = now();
  -- máximo 5 celulares por pedido
  delete from avisos_cliente where pedido = p_codigo and endpoint not in (
    select endpoint from avisos_cliente where pedido = p_codigo order by creada desc limit 5);
  return true;
end;
$$;

-- Encargado: avisos de pedidos nuevos (con su sesión)
create or replace function public.enc_activar_avisos(p_sesion text, p_endpoint text, p_p256dh text, p_auth text)
returns boolean language plpgsql security definer set search_path = public as $$
declare
  e encargados%rowtype := enc_dueno(p_sesion);
begin
  if coalesce(p_endpoint, '') !~ '^https://' or length(p_endpoint) > 600 or length(coalesce(p_p256dh, '')) not between 20 and 200
     or length(coalesce(p_auth, '')) not between 8 and 100 then
    return false;
  end if;
  insert into avisos_encargado (endpoint, encargado, p256dh, auth) values (p_endpoint, e.id, p_p256dh, p_auth)
  on conflict (endpoint) do update set encargado = excluded.encargado, p256dh = excluded.p256dh, auth = excluded.auth,
                                       creada = now();
  delete from avisos_encargado where encargado = e.id and endpoint not in (
    select endpoint from avisos_encargado where encargado = e.id order by creada desc limit 5);
  return true;
end;
$$;

revoke all on function public.tienda_activar_avisos(text, text, text, text) from public;
revoke all on function public.enc_activar_avisos(text, text, text, text) from public;
grant execute on function public.tienda_activar_avisos(text, text, text, text) to anon, authenticated;
grant execute on function public.enc_activar_avisos(text, text, text, text) to anon, authenticated;

-- =====================================================================
-- Disparadores: qué aviso sale y a quién
-- =====================================================================

create or replace function public.avisos_tr_pedido_nuevo()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into avisos_cola (endpoint, p256dh, auth, titulo, texto, url)
  select a.endpoint, a.p256dh, a.auth, 'Pedido nuevo · ' || left(new.nombre, 40),
         (case when new.entrega = 'retiro' then 'Recoge en la tienda' else 'A domicilio' end)
           || ' · $' || replace(to_char(new.total, 'FM999,999,999'), ',', '.') || ' · ' || new.codigo,
         './pedidos.html'
    from avisos_encargado a join encargados e on e.id = a.encargado where e.activo;
  if found then
    perform avisos_despertar();
  end if;
  return new;
end;
$$;
drop trigger if exists avisos_pedido_nuevo on public.pedidos_web;
create trigger avisos_pedido_nuevo after insert on public.pedidos_web
  for each row execute function public.avisos_tr_pedido_nuevo();

create or replace function public.avisos_tr_pedido_estado()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  t text;
  x text := '';
  tienda text := coalesce(nullif((select nombre from tienda_info where id = 1), ''), 'La tienda');
begin
  if new.estado is distinct from old.estado then
    t := case new.estado
           when 'aceptado' then 'Tu pedido fue aceptado'
           when 'preparando' then 'Estamos alistando tu pedido'
           when 'en_camino' then case when new.entrega = 'retiro' then 'Tu pedido está listo para recoger'
                                      else 'Tu pedido va en camino' end
           when 'entregado' then 'Pedido entregado. ¡Gracias!'
           when 'cancelado' then 'La tienda canceló tu pedido'
         end;
    if new.estado = 'cancelado' and coalesce(new.motivo, '') <> '' then
      x := left(new.motivo, 120);
    end if;
  elsif new.pagado and not coalesce(old.pagado, false) then
    t := 'Confirmamos tu pago';
  end if;
  if t is not null then
    insert into avisos_cola (endpoint, p256dh, auth, titulo, texto, url)
    select a.endpoint, a.p256dh, a.auth, t,
           case when x <> '' then x else tienda || ' · pedido ' || new.codigo end,
           './tienda.html?pedido=' || new.codigo
      from avisos_cliente a where a.pedido = new.codigo;
    if found then
      perform avisos_despertar();
    end if;
  end if;
  -- limpieza: los avisos ya mandados de hace una semana
  if random() < 0.05 then
    delete from avisos_cola where enviado and creada < now() - interval '7 days';
  end if;
  return new;
end;
$$;
drop trigger if exists avisos_pedido_estado on public.pedidos_web;
create trigger avisos_pedido_estado after update on public.pedidos_web
  for each row execute function public.avisos_tr_pedido_estado();

-- La función toma de a pocos los pendientes (así dos llamadas al tiempo no mandan el mismo aviso)
create or replace function public.avisos_tomar(p_cuantos int default 100)
returns setof public.avisos_cola language sql security definer set search_path = public as $$
  update avisos_cola set intentos = intentos + 1
   where id in (select id from avisos_cola where not enviado and intentos < 3 and creada > now() - interval '1 day'
                 order by id limit greatest(1, least(p_cuantos, 200)) for update skip locked)
  returning *;
$$;
revoke all on function public.avisos_tomar(int) from public, anon, authenticated;
grant execute on function public.avisos_tomar(int) to service_role;
