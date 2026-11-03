// DomiRuta: función "whatsapp" (Supabase > Edge Functions). Verify JWT: apagado.
// 1) La base la despierta cuando hay mensajes en la bandeja «wa_salida» y esta los manda a Meta.
// 2) Meta le avisa aquí cuando un cliente escribe (webhook): bienvenida con el enlace de la tienda,
//    o, si manda una foto y tiene un pedido por pagar, se guarda como comprobante.
// Secrets (Edge Functions > Secrets):
//   WA_TOKEN        token permanente de Meta (usuario del sistema)
//   WA_PHONE_ID     identificador del número de WhatsApp (Phone number ID)
//   WA_VERIFY       una palabra que tú inventas; la misma va en Meta > Webhooks > Verify token
//   WA_APP_SECRET   clave secreta de la app de Meta (Configuración > Básica): comprueba que el aviso sí viene de Meta
const GRAPH = "https://graph.facebook.com/v23.0";
const IDIOMA = "es_CO";
// las plantillas con botón de enlace que cambia ({{1}} en la dirección del botón 0)
const CON_BOTON = new Set(["pedido_en_camino", "pedido_recibido", "pedido_recibido_pago", "salida_nueva"]);
// errores de Meta que no se arreglan reintentando (número no permitido, no tiene WhatsApp, pasó la ventana de 24 h...)
const SIN_REINTENTO = new Set([131026, 131030, 131047, 131051, 131009, 100]);

type Fila = {
  id: number; para: string; plantilla: string | null; parametros: string[]; boton: string | null;
  texto: string | null; enlace: string | null; enlace_texto: string | null;
};

function env(n: string): string {
  return Deno.env.get(n) ?? "";
}

/** La clave de servicio del proyecto (la pone Supabase sola en cada función). */
function claveServicio(): string {
  const vieja = env("SUPABASE_SERVICE_ROLE_KEY");
  if (vieja) return vieja;
  try {
    const nuevas = JSON.parse(env("SUPABASE_SECRET_KEYS") || "{}");
    return nuevas.default ?? Object.values(nuevas)[0] ?? "";
  } catch {
    return "";
  }
}

function cabecerasBase(): Record<string, string> {
  const k = claveServicio();
  const c: Record<string, string> = { apikey: k, "Content-Type": "application/json" };
  if (k.startsWith("eyJ")) c["Authorization"] = `Bearer ${k}`;
  return c;
}

async function rpc<T>(nombre: string, args: Record<string, unknown>): Promise<T> {
  const r = await fetch(`${env("SUPABASE_URL")}/rest/v1/rpc/${nombre}`, {
    method: "POST", headers: cabecerasBase(), body: JSON.stringify(args),
  });
  if (!r.ok) throw new Error(`rpc ${nombre}: ${r.status} ${(await r.text()).slice(0, 200)}`);
  const t = await r.text();
  return (t ? JSON.parse(t) : null) as T;
}

function json(cuerpo: unknown, estado = 200): Response {
  return new Response(JSON.stringify(cuerpo), { status: estado, headers: { "Content-Type": "application/json" } });
}

/** Lo que se le manda a Meta para una fila de la bandeja. */
export function armar(f: Fila): Record<string, unknown> {
  const base = { messaging_product: "whatsapp", recipient_type: "individual", to: f.para };
  if (f.plantilla) {
    const comps: unknown[] = [];
    if (f.parametros?.length) {
      comps.push({ type: "body", parameters: f.parametros.map((t) => ({ type: "text", text: String(t) })) });
    }
    if (CON_BOTON.has(f.plantilla) && f.boton) {
      comps.push({ type: "button", sub_type: "url", index: "0", parameters: [{ type: "text", text: f.boton }] });
    }
    return { ...base, type: "template", template: { name: f.plantilla, language: { code: IDIOMA }, components: comps } };
  }
  if (f.enlace) {
    return {
      ...base, type: "interactive",
      interactive: {
        type: "cta_url", body: { text: (f.texto ?? "").slice(0, 1024) },
        action: { name: "cta_url", parameters: { display_text: (f.enlace_texto ?? "Abrir").slice(0, 20), url: f.enlace } },
      },
    };
  }
  return { ...base, type: "text", text: { body: (f.texto ?? "").slice(0, 4096), preview_url: false } };
}

async function mandar(f: Fila): Promise<void> {
  let ok = false, wamid: string | null = null, error = "";
  try {
    const r = await fetch(`${GRAPH}/${env("WA_PHONE_ID")}/messages`, {
      method: "POST",
      headers: { Authorization: `Bearer ${env("WA_TOKEN")}`, "Content-Type": "application/json" },
      body: JSON.stringify(armar(f)),
    });
    const d = await r.json().catch(() => ({}));
    if (r.ok) {
      ok = true;
      wamid = d?.messages?.[0]?.id ?? null;
    } else {
      const codigo = d?.error?.code ?? r.status;
      error = `${SIN_REINTENTO.has(codigo) ? "no-reintentar:" : ""}${codigo} ${d?.error?.message ?? ""}`.slice(0, 300);
    }
  } catch (e) {
    error = `sin conexión con Meta: ${(e as Error).message}`;
  }
  await rpc("wa_resultado", { p_id: f.id, p_ok: ok, p_wamid: wamid, p_error: error });
}

/** Manda lo que haya en la bandeja; espera un poco si algo está por salir (la salida nueva espera 20 s). */
async function vaciarBandeja(): Promise<number> {
  if (!env("WA_TOKEN") || !env("WA_PHONE_ID")) return 0;
  const fin = Date.now() + 40_000;
  let enviados = 0;
  while (Date.now() < fin) {
    const filas = await rpc<Fila[]>("wa_por_enviar", { p_max: 20 });
    for (const f of filas ?? []) {
      await mandar(f);
      enviados++;
    }
    if (filas?.length) continue;
    const pronto = new Date(Date.now() + 30_000).toISOString();
    const r = await fetch(`${env("SUPABASE_URL")}/rest/v1/wa_salida?estado=eq.pendiente&enviar_desde=lte.${pronto}&intentos=lt.5&select=id&limit=1`,
      { headers: cabecerasBase() });
    const hay = r.ok ? (await r.json()).length > 0 : false;
    if (!hay) break;
    await new Promise((res) => setTimeout(res, 5000));
  }
  return enviados;
}

async function firmaValida(cuerpo: string, firma: string): Promise<boolean> {
  const secreto = env("WA_APP_SECRET");
  if (!secreto || !firma.startsWith("sha256=")) return false;
  const k = await crypto.subtle.importKey("raw", new TextEncoder().encode(secreto), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const s = new Uint8Array(await crypto.subtle.sign("HMAC", k, new TextEncoder().encode(cuerpo)));
  const hex = [...s].map((b) => b.toString(16).padStart(2, "0")).join("");
  const otra = firma.slice(7);
  if (otra.length !== hex.length) return false;
  let dif = 0;
  for (let i = 0; i < hex.length; i++) dif |= hex.charCodeAt(i) ^ otra.charCodeAt(i);
  return dif === 0;
}

/** Baja la foto de Meta y la guarda en la carpeta privada «comprobantes». */
async function guardarComprobante(pedido: number, mediaId: string): Promise<void> {
  const auth = { Authorization: `Bearer ${env("WA_TOKEN")}` };
  const info = await (await fetch(`${GRAPH}/${mediaId}`, { headers: auth })).json();
  if (!info?.url) throw new Error("Meta no dio la foto");
  const foto = await fetch(info.url, { headers: auth });
  if (!foto.ok) throw new Error(`no se pudo bajar la foto (${foto.status})`);
  const bytes = new Uint8Array(await foto.arrayBuffer());
  if (bytes.length > 8_000_000) throw new Error("foto muy pesada");
  const tipo = String(info.mime_type ?? "image/jpeg");
  const ext = tipo.includes("png") ? "png" : tipo.includes("pdf") ? "pdf" : "jpg";
  const ruta = `${pedido}-${Date.now()}.${ext}`;
  const c = cabecerasBase();
  c["Content-Type"] = tipo;
  c["x-upsert"] = "true";
  const up = await fetch(`${env("SUPABASE_URL")}/storage/v1/object/comprobantes/${ruta}`, { method: "POST", headers: c, body: bytes });
  if (!up.ok) throw new Error(`no se pudo guardar la foto (${up.status})`);
  await rpc("wa_comprobante", { p_pedido: pedido, p_ruta: ruta });
}

async function recibir(datos: any): Promise<void> {
  for (const entrada of datos?.entry ?? []) {
    for (const cambio of entrada?.changes ?? []) {
      for (const m of cambio?.value?.messages ?? []) {
        const tipo = String(m.type ?? "");
        const media = m.image?.id ?? m.document?.id ?? null;
        const texto = m.text?.body ?? m.image?.caption ?? m.button?.text ?? "";
        const r = await rpc<{ accion: string; pedido?: number }>("wa_entrante", {
          p_de: String(m.from ?? ""), p_tipo: tipo, p_texto: texto, p_media: media, p_wamid: String(m.id ?? ""),
        });
        if (r?.accion === "comprobante" && r.pedido && media) {
          try {
            await guardarComprobante(r.pedido, media);
          } catch (e) {
            console.error("comprobante:", (e as Error).message);
          }
        }
      }
    }
  }
}

/** El encargado (pedidos.html) ve la foto del comprobante: enlace de 10 minutos, solo con su sesión. */
async function enlaceComprobante(pedido: number, sesion: string): Promise<Response> {
  if (!Number.isInteger(pedido) || !/^[0-9a-f]{64}$/.test(sesion)) return json({ error: "datos" }, 400);
  let ruta: string | null = null;
  try {
    ruta = await rpc<string | null>("enc_ruta_comprobante", { p_sesion: sesion, p_pedido: pedido });
  } catch {
    return json({ error: "sesion_cerrada" }, 401);
  }
  if (!ruta || !/^[0-9]+-[0-9]+\.(jpg|png|pdf)$/.test(ruta)) return json({ error: "sin comprobante" }, 404);
  const r = await fetch(`${env("SUPABASE_URL")}/storage/v1/object/sign/comprobantes/${ruta}`, {
    method: "POST", headers: cabecerasBase(), body: JSON.stringify({ expiresIn: 600 }),
  });
  const d = await r.json().catch(() => ({}));
  if (!r.ok || !d?.signedURL) return json({ error: "no se pudo firmar" }, 502);
  const url = `${env("SUPABASE_URL")}/storage/v1${String(d.signedURL).startsWith("/") ? "" : "/"}${d.signedURL}`;
  return new Response(JSON.stringify({ url }), {
    status: 200, headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" },
  });
}

Deno.serve(async (req) => {
  // la página del encargado llama desde el navegador (otro dominio)
  if (req.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: {
      "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "POST",
      "Access-Control-Allow-Headers": "content-type, apikey, authorization, x-client-info" } });
  }
  const url = new URL(req.url);

  // Meta comprueba el webhook una sola vez (al configurarlo)
  if (req.method === "GET") {
    const ok = url.searchParams.get("hub.mode") === "subscribe" && env("WA_VERIFY") !== ""
      && url.searchParams.get("hub.verify_token") === env("WA_VERIFY");
    return ok ? new Response(url.searchParams.get("hub.challenge") ?? "", { status: 200 }) : json({ error: "no" }, 403);
  }
  if (req.method !== "POST") return json({ error: "solo POST" }, 405);

  const cuerpo = await req.text();
  const firma = req.headers.get("x-hub-signature-256") ?? "";
  let trabajo: Promise<unknown>;
  if (firma) {
    // viene de Meta: si la firma no cuadra, no se cree nada
    if (!(await firmaValida(cuerpo, firma))) return json({ error: "firma" }, 401);
    let datos: unknown = null;
    try { datos = JSON.parse(cuerpo); } catch { /* vacío */ }
    trabajo = recibir(datos).catch((e) => console.error("recibir:", e.message)).then(vaciarBandeja);
  } else {
    let datos: any = null;
    try { datos = JSON.parse(cuerpo || "null"); } catch { /* vacío */ }
    if (datos && datos.comprobante !== undefined) {
      return await enlaceComprobante(Number(datos.comprobante), String(datos.sesion ?? ""));
    }
    // la base avisa que hay mensajes en la bandeja (no trae datos: la función lee la bandeja ella misma)
    trabajo = vaciarBandeja();
  }
  const er = (globalThis as any).EdgeRuntime;
  if (er?.waitUntil) {
    er.waitUntil(trabajo.catch((e) => console.error(e)));
    return json({ ok: true });
  }
  const n = await trabajo.catch((e) => { console.error(e); return 0; });
  return json({ ok: true, enviados: n });
});
