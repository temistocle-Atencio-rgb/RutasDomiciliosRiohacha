// Función «avisos»: manda las notificaciones que quedaron en la cola (avisos_cola).
// La despiertan los disparadores de la base con {"revisar":true}; no recibe datos de afuera,
// solo revisa la cola con la clave de servicio. Las claves VAPID van en los Secrets.
import webpush from "npm:web-push@3.6.7";
import { createClient } from "npm:@supabase/supabase-js@2.45.4";

const PUBLICA = "BNBdC-dYdf6oEPT_XTO08bI4vhbVFbc8SRbCoE_go17VOT_xa9xXScUL4Z2pGqHUdesVH1spQKxesA64JvQkgTo";
const PRIVADA = Deno.env.get("VAPID_PRIVADA") ?? Deno.env.get("VAPID_PRIVATE_KEY") ?? "";
const sb = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);

Deno.serve(async () => {
  if (!PRIVADA) return new Response("falta la clave VAPID privada", { status: 500 });
  webpush.setVapidDetails("https://temistocle-atencio-rgb.github.io/domiruta-repartidor/", PUBLICA, PRIVADA);
  const { data, error } = await sb.rpc("avisos_tomar", { p_cuantos: 100 });
  if (error) return new Response(error.message, { status: 500 });
  let enviados = 0;
  for (const a of (data ?? []) as Array<Record<string, string | number>>) {
    const cuerpo = JSON.stringify({ titulo: a.titulo, texto: a.texto, url: a.url });
    try {
      await webpush.sendNotification(
        { endpoint: String(a.endpoint), keys: { p256dh: String(a.p256dh), auth: String(a.auth) } },
        cuerpo,
        { TTL: 6 * 3600, urgency: "high" },
      );
      await sb.from("avisos_cola").update({ enviado: true, error: "" }).eq("id", a.id);
      enviados++;
    } catch (e) {
      const codigo = (e as { statusCode?: number }).statusCode ?? 0;
      // 404 o 410: ese celular ya no recibe (desinstaló o quitó el permiso): se borra
      if (codigo === 404 || codigo === 410) {
        await sb.from("avisos_cliente").delete().eq("endpoint", a.endpoint);
        await sb.from("avisos_encargado").delete().eq("endpoint", a.endpoint);
        await sb.from("avisos_cola").update({ enviado: true, error: "ya no existe" }).eq("id", a.id);
      } else {
        await sb.from("avisos_cola").update({ error: String(codigo || (e as Error).message).slice(0, 200) }).eq("id", a.id);
      }
    }
  }
  return new Response(JSON.stringify({ enviados }), { headers: { "Content-Type": "application/json" } });
});
