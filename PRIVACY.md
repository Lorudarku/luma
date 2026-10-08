# Privacidad de Luma

Luma registra los movimientos, cantidades, monedas, fechas, descripciones, categorías, reglas mensuales y filtros que introduces. Se usan para mostrar tus finanzas y generar mensualidades locales. Se almacenan cifrados en el espacio privado de la app.

No hay cuentas, publicidad, analítica, identificadores publicitarios, servidores propios ni permiso de Internet. Android realiza la autenticación: Luma no recibe tu PIN ni las plantillas biométricas.

Puedes eliminar movimientos. Para respetar una mensualidad ya procesada u omitida, se conserva una marca de regla/mes después de eliminar su movimiento; esa marca no contiene importe ni descripción. Cambiar el día de una regla no duplica meses anteriores. Las marcas también se incluyen en las copias cifradas. «Borrar todos los datos» o desinstalar elimina los datos privados locales y estas marcas. Las copias automáticas y transferencias de Android están excluidas. Puedes crear una copia cifrada manual y elegir su destino. Un proveedor de documentos puede ser un servicio en la nube sujeto a su propia política; recibe el archivo cifrado, no su contraseña. El borrado local no elimina esas copias externas.

La contraseña de una copia no se persiste. Durante la verificación se mantiene temporalmente en memoria hasta confirmar, cancelar o bloquear la sesión; se limpian los arrays propios al terminar. El framework y los strings de JVM no permiten garantizar un borrado forense de RAM. Sin una copia y su contraseña, perder la clave del teléfono, borrar los datos o desinstalar puede hacer que la información sea irrecuperable. El cifrado no garantiza protección absoluta si el dispositivo está comprometido mientras la información está abierta. Más detalles en [SECURITY.md](SECURITY.md).

Este documento describe el producto implementado, pero no está listo para actuar como política pública de Play hasta que el titular añada su identidad, contacto de privacidad, fecha de vigencia y URL pública estable. No deben inventarse esos datos ni presentarse como completados.
