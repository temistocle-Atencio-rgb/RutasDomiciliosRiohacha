# Pendientes (3 nov 2026) - para seguir el domingo

## Ya está hecho y subido
- Tienda en línea (tienda.html): catálogo, ficha del producto, carrito, datos, pago, estado del pedido.
- DomiRuta: "Vender en línea", subida automática, Configuración > Tienda en línea, datos para pagar, pestaña "En línea".
- Supabase: tienda.sql y whatsapp.sql instalados; función "whatsapp" publicada.
- Meta: 6 plantillas enviadas a revisión (pedido_en_camino, pedido_recibido, pedido_recibido_pago,
  pedido_cancelado, salida_nueva, salida_cambio), español (COL), Utilidad.

## Falta
1. Supabase > Edge Functions > whatsapp > Settings: "Verify JWT" apagado y Save changes (revisar que quedó guardado).
2. Token permanente de Meta (usuario del sistema "DomiRuta", activos: app DomiRuta + Test Messaging Account,
   caducidad Nunca, permisos whatsapp_business_messaging y whatsapp_business_management).
3. Secrets en Supabase: WA_TOKEN, WA_PHONE_ID, WA_APP_SECRET, WA_VERIFY (los pega el dueño, nunca se comparten).
4. Webhook en Meta: URL https://xivokswlegxetxgovhdw.supabase.co/functions/v1/whatsapp, token = WA_VERIFY,
   suscribirse al campo "messages".
5. Revisar si Meta aprobó las 6 plantillas.
6. Prueba completa: escribir "hola" al número de prueba, hacer un pedido, comprobante, aceptar salida.
7. Agregar números de prueba (profesor) en Meta.
8. Fase 0 pendiente: cerrar-enlaces-viejos.sql, vibración y sonido de 3 notas, Cancelar pedido / Anular venta.
